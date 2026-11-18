//! Structured download queue: runs `download.sh` (tiddl + Shanling post-processing) one job at a
//! time, parses tiddl's per-item result lines into track statuses, and samples bytes written
//! under the destination to report throughput.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant, SystemTime};

use eframe::egui;

use crate::api::TidalApi;
use crate::netease;
use crate::types::StreamQuality;


#[derive(Clone, Copy, PartialEq, Debug)]
pub enum JobStatus {
    Queued,
    Resolving,
    Downloading,
    PostProcessing,
    Done,
    Failed,
    Cancelled,
}

impl JobStatus {
    pub fn is_active(self) -> bool {
        matches!(self, JobStatus::Resolving | JobStatus::Downloading | JobStatus::PostProcessing)
    }
    pub fn is_finished(self) -> bool {
        matches!(self, JobStatus::Done | JobStatus::Failed | JobStatus::Cancelled)
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum TrackStatus {
    Pending,
    Downloaded,
    Exists,
    Skipped,
    Failed,
}

#[derive(Clone, Debug)]
pub struct JobTrack {
    pub label: String, // "01" / "1-03"
    pub title: String,
    pub status: TrackStatus,
}

/// Metadata resolved from the TIDAL API before the download starts.
#[derive(Clone, Debug, Default)]
pub struct JobMeta {
    pub kind: String,
    pub title: String,
    pub subtitle: String,
    pub cover: Option<String>,
    pub tracks: Vec<JobTrack>,
}

pub struct DownloadJob {
    pub id: u64,
    pub url: String,
    pub quality: String,
    pub atmos: String,
    pub dest: String,
    pub status: JobStatus,
    pub meta: JobMeta,
    pub results: usize,
    pub bytes: u64,
    pub speed: f64,
    pub out_dir: Option<String>,
    pub logs: Vec<String>,
    pub pid: Option<i32>,
    pub started: Option<Instant>,
    pub elapsed: Option<Duration>,
    pub stop_sampler: Arc<AtomicBool>,
}

pub enum JobEvent {
    Meta(JobMeta),
    Started(i32),
    Log(String),
    Bytes(u64, f64),
    Saved(TrackStatus, PathBuf),
    Finished(bool),
}

impl DownloadJob {
    pub fn new(id: u64, url: String, quality: String, atmos: String, dest: String) -> Self {
        let kind = match url_kind(&url).map(|(k, _)| k) {
            _ if netease::is_link(&url) => "网易云单曲",
            Some("album") => "专辑",
            Some("track") => "单曲",
            Some("playlist") => "歌单",
            Some("artist") => "歌手",
            Some("mix") => "Mix",
            Some("video") => "视频",
            _ => "",
        }
        .to_string();
        Self {
            id,
            meta: JobMeta { kind, title: url.clone(), ..Default::default() },
            url,
            quality,
            atmos,
            dest,
            status: JobStatus::Queued,
            results: 0,
            bytes: 0,
            speed: 0.0,
            out_dir: None,
            logs: Vec::new(),
            pid: None,
            started: None,
            elapsed: None,
            stop_sampler: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn total(&self) -> usize {
        self.meta.tracks.len()
    }

    pub fn count(&self, s: TrackStatus) -> usize {
        self.meta.tracks.iter().filter(|t| t.status == s).count()
    }

    /// 0..=1, or None when the total is unknown.
    pub fn fraction(&self) -> Option<f32> {
        match self.status {
            JobStatus::PostProcessing | JobStatus::Done => Some(1.0),
            _ if self.total() > 0 => Some((self.results.min(self.total()) as f32) / self.total() as f32),
            _ => None,
        }
    }

    pub fn apply(&mut self, ev: JobEvent) {
        if self.status == JobStatus::Cancelled
            && !matches!(ev, JobEvent::Finished(_))
            && netease::is_link(&self.url)
        {
            return;
        }
        match ev {
            JobEvent::Meta(m) => {
                self.meta = m;
                if self.status == JobStatus::Resolving {
                    self.status = JobStatus::Downloading;
                }
            }
            JobEvent::Started(pid) => {
                self.pid = Some(pid);
                self.status = JobStatus::Downloading;
            }
            JobEvent::Bytes(b, s) => {
                self.bytes = b;
                self.speed = s;
            }
            JobEvent::Log(line) => self.on_line(line),
            JobEvent::Saved(status, dir) => {
                if let Some(track) = self.meta.tracks.first_mut() {
                    track.status = status;
                }
                self.results = 1;
                self.out_dir = Some(dir.to_string_lossy().into_owned());
            }
            JobEvent::Finished(ok) => {
                self.stop_sampler.store(true, Ordering::Relaxed);
                self.pid = None;
                self.speed = 0.0;
                self.elapsed = self.started.map(|s| s.elapsed());
                if self.status != JobStatus::Cancelled {
                    if !ok && netease::is_link(&self.url) {
                        for track in &mut self.meta.tracks {
                            if track.status == TrackStatus::Pending {
                                track.status = TrackStatus::Failed;
                            }
                        }
                    }
                    let any_failed = self.count(TrackStatus::Failed) > 0;
                    self.status = if ok && !any_failed { JobStatus::Done } else { JobStatus::Failed };
                }
            }
        }
    }

    fn on_line(&mut self, raw: String) {
        let line = strip_ansi(&raw);
        let t = line.trim();
        self.logs.push(line.clone());
        if self.logs.len() > 1000 {
            self.logs.drain(..200);
        }

        if t.contains("Running Shanling post-processing") {
            self.status = JobStatus::PostProcessing;
            self.stop_sampler.store(true, Ordering::Relaxed);
            self.speed = 0.0;
            return;
        }

        let (status, rest) = if let Some(r) = t.strip_prefix("Downloaded ").or_else(|| t.strip_prefix("Overwrited ")) {
            (TrackStatus::Downloaded, r)
        } else if let Some(r) = t.strip_prefix("Exists ") {
            (TrackStatus::Exists, r)
        } else if let Some(r) = t.strip_prefix("Error ").or_else(|| t.strip_prefix("Can't stream ")) {
            (TrackStatus::Failed, r)
        } else if let Some(r) = t.strip_prefix("Skipping ") {
            (TrackStatus::Skipped, r.trim_start_matches('\''))
        } else {
            return;
        };
        self.results += 1;

        // Result lines end with the album directory (absolute path).
        let mut desc = rest;
        if let Some(idx) = rest.find(" /") {
            if matches!(status, TrackStatus::Downloaded | TrackStatus::Exists) {
                self.out_dir = Some(rest[idx + 1..].trim().to_string());
            }
            desc = &rest[..idx];
        }

        // Match the longest pending title that the description starts with.
        let best = self
            .meta
            .tracks
            .iter_mut()
            .filter(|tr| tr.status == TrackStatus::Pending && desc.starts_with(tr.title.as_str()))
            .max_by_key(|tr| tr.title.len());
        match best {
            Some(tr) => tr.status = status,
            None => self.meta.tracks.push(JobTrack {
                label: format!("{:02}", self.meta.tracks.len() + 1),
                title: desc.to_string(),
                status,
            }),
        }
    }

    /// Spawn the resolver + downloader threads. Events come back as `(job_id, JobEvent)`.
    pub fn start<E: Send + 'static>(
        &mut self,
        api: TidalApi,
        tx: Sender<E>,
        wrap: fn(u64, JobEvent) -> E,
        ctx: egui::Context,
    ) {
        self.status = JobStatus::Resolving;
        self.started = Some(Instant::now());
        self.stop_sampler = Arc::new(AtomicBool::new(false));

        let id = self.id;
        let url = self.url.clone();
        let dest = PathBuf::from(&self.dest);
        let quality = self.quality.clone();
        let atmos = self.atmos.clone();
        let stop = Arc::clone(&self.stop_sampler);

        thread::spawn(move || {
            let send = |e: JobEvent| {
                let _ = tx.send(wrap(id, e));
                ctx.request_repaint();
            };

            if netease::is_link(&url) {
                let result = netease::song_link_id(&url)
                    .ok_or_else(|| "请使用网易云单曲链接".to_string())
                    .and_then(|song_id| download_netease(&api, song_id, &quality, &dest, &stop, &send));
                if let Err(error) = &result {
                    send(JobEvent::Log(error.clone()));
                }
                send(JobEvent::Finished(result.is_ok()));
                return;
            }

            match resolve_meta(&api, &url) {
                Ok(m) => send(JobEvent::Meta(m)),
                Err(e) => send(JobEvent::Log(format!("[GUI] 无法预读曲目信息（不影响下载）：{}", e))),
            }

            let mut cmd = if let Some(script) = std::env::var_os("TIDAL_DOWNLOAD_SCRIPT") {
                let mut command = Command::new(script);
                command.arg(&url).args(["-q", &quality, "-da", &atmos, "-p"]).arg(&dest);
                command
            } else {
                let mut command = Command::new(crate::api::tiddl_bin());
                command
                    .args(["download", "-q", &quality, "-da", &atmos, "-p"])
                    .arg(&dest)
                    .args(["-o", "{album.artist}/{album.title}/{item.number:02d}. {item.title}", "url", &url]);
                command
            };
            cmd.env("TIDDL_PATH", &api.config_path)
                .env("COLUMNS", "1000") // keep rich from wrapping result lines
                .env("NO_COLOR", "1")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            unsafe {
                use std::os::unix::process::CommandExt;
                // Own process group so cancel can kill bash + tiddl + ffmpeg together.
                cmd.pre_exec(|| {
                    libc::setpgid(0, 0);
                    Ok(())
                });
            }

            let mut child = match cmd.spawn() {
                Ok(c) => c,
                Err(e) => {
                    send(JobEvent::Log(format!("无法启动下载命令：{}", e)));
                    send(JobEvent::Finished(false));
                    return;
                }
            };
            send(JobEvent::Started(child.id() as i32));

            // Throughput sampler.
            {
                let (tx, ctx, stop) = (tx.clone(), ctx.clone(), Arc::clone(&stop));
                let since = SystemTime::now() - Duration::from_secs(2);
                thread::spawn(move || {
                    let (mut last_bytes, mut last_t) = (0u64, Instant::now());
                    let mut speed = 0.0f64;
                    while !stop.load(Ordering::Relaxed) {
                        thread::sleep(Duration::from_millis(1000));
                        let b = bytes_written_since(&dest, since);
                        let dt = last_t.elapsed().as_secs_f64().max(0.001);
                        let inst = b.saturating_sub(last_bytes) as f64 / dt;
                        speed = if speed == 0.0 { inst } else { speed * 0.6 + inst * 0.4 };
                        last_bytes = b;
                        last_t = Instant::now();
                        if stop.load(Ordering::Relaxed) {
                            break;
                        }
                        let _ = tx.send(wrap(id, JobEvent::Bytes(b, speed)));
                        ctx.request_repaint();
                    }
                });
            }

            let err_thread = child.stderr.take().map(|r| {
                let (tx, ctx) = (tx.clone(), ctx.clone());
                thread::spawn(move || {
                    pump(r, |l| {
                        let _ = tx.send(wrap(id, JobEvent::Log(l)));
                        ctx.request_repaint();
                    })
                })
            });
            if let Some(r) = child.stdout.take() {
                pump(r, |l| send(JobEvent::Log(l)));
            }
            if let Some(h) = err_thread {
                let _ = h.join();
            }
            let ok = child.wait().map(|s| s.success()).unwrap_or(false);
            stop.store(true, Ordering::Relaxed);
            send(JobEvent::Finished(ok));
        });
    }

    pub fn cancel(&mut self) {
        if let Some(pid) = self.pid {
            unsafe {
                libc::kill(-pid, libc::SIGTERM);
            }
        }
        self.stop_sampler.store(true, Ordering::Relaxed);
        self.status = JobStatus::Cancelled;
        self.speed = 0.0;
    }
}

/// ("album" | "track" | "playlist" | "artist" | "mix" | "video", id)
pub fn url_kind(url: &str) -> Option<(&'static str, String)> {
    for k in ["album", "track", "playlist", "artist", "mix", "video"] {
        let pat = format!("{}/", k);
        if let Some(i) = url.find(&pat) {
            let id: String = url[i + pat.len()..].chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
            if !id.is_empty() {
                return Some((k, id));
            }
        }
    }
    None
}

/// Accepts a URL or a bare numeric album id.
pub fn normalize_url(input: &str) -> String {
    let s = input.trim();
    if let Some(id) = netease::song_link_id(s) {
        return netease::link("song", id);
    }
    if !s.is_empty() && s.chars().all(|c| c.is_ascii_digit()) {
        format!("https://tidal.com/album/{}", s)
    } else {
        s.to_string()
    }
}

fn download_netease(
    api: &TidalApi,
    song_id: u64,
    quality: &str,
    dest: &std::path::Path,
    stop: &AtomicBool,
    send: &impl Fn(JobEvent),
) -> Result<(), String> {
    let cancelled = || stop.load(Ordering::Relaxed);
    let track = api.netease().track(song_id)?;
    let artist = track.artist_names();
    send(JobEvent::Meta(JobMeta {
        kind: "网易云单曲".into(),
        title: track.title.clone(),
        subtitle: artist.clone(),
        cover: track.cover().map(str::to_owned),
        tracks: vec![JobTrack { label: "01".into(), title: track.title.clone(), status: TrackStatus::Pending }],
    }));

    if cancelled() {
        return Err("下载已取消".into());
    }
    let quality = match quality {
        "high" => StreamQuality::Lossless,
        "normal" => StreamQuality::High,
        "low" => StreamQuality::Low,
        _ => StreamQuality::HiRes,
    };
    let (info, format) = api.netease().stream(song_id, quality)?;
    if !matches!(format.as_str(), "mp3" | "flac" | "m4a" | "aac" | "wav" | "ogg" | "opus") {
        return Err("网易云返回了未知音频格式".into());
    }

    let dir = dest.join("网易云");
    std::fs::create_dir_all(&dir).map_err(|e| format!("无法创建下载目录：{e}"))?;
    let name: String = format!("{artist} - {}", track.title).chars().take(55)
        .map(|c| if c.is_control() || "/\\:*?\"<>|".contains(c) { '_' } else { c })
        .collect();
    let target = dir.join(format!("{} [{song_id} {quality_label}].{format}", name.trim(), quality_label = netease::level(quality)));
    if target.is_file() {
        send(JobEvent::Saved(TrackStatus::Exists, dir));
        return Ok(());
    }

    let mut response = api.netease().audio(&info.url)?;
    let expected = response.content_length();
    let stamp = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default().as_nanos();
    let partial = dir.join(format!(".{song_id}-{}-{stamp}.part", std::process::id()));
    let mut file = std::fs::OpenOptions::new().write(true).create_new(true).open(&partial)
        .map_err(|e| format!("无法创建临时文件：{e}"))?;
    let result = (|| {
        let mut buffer = [0; 64 * 1024];
        let mut bytes = 0u64;
        let mut sampled = 0u64;
        let mut last_sample = Instant::now();
        while !cancelled() {
            let read = response.read(&mut buffer).map_err(|e| format!("读取音频失败：{e}"))?;
            if read == 0 {
                break;
            }
            file.write_all(&buffer[..read]).map_err(|e| format!("写入音频失败：{e}"))?;
            bytes += read as u64;
            let elapsed = last_sample.elapsed();
            if elapsed >= Duration::from_millis(500) {
                send(JobEvent::Bytes(bytes, (bytes - sampled) as f64 / elapsed.as_secs_f64()));
                sampled = bytes;
                last_sample = Instant::now();
            }
        }
        if cancelled() {
            return Err("下载已取消".into());
        }
        if bytes == 0 || expected.is_some_and(|len| len != bytes) {
            return Err("网易云音频下载不完整".into());
        }
        file.sync_all().map_err(|e| format!("保存音频失败：{e}"))?;
        if cancelled() {
            return Err("下载已取消".into());
        }
        let status = match std::fs::hard_link(&partial, &target) {
            Ok(()) => TrackStatus::Downloaded,
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists && target.is_file() => TrackStatus::Exists,
            Err(e) => return Err(format!("保存音频失败：{e}")),
        };
        send(JobEvent::Bytes(bytes, 0.0));
        send(JobEvent::Saved(status, dir));
        Ok(())
    })();
    drop(file);
    let _ = std::fs::remove_file(partial);
    result
}

fn resolve_meta(api: &TidalApi, url: &str) -> Result<JobMeta, String> {
    let (kind, id) = url_kind(url).ok_or("无法识别的链接")?;
    let num = || id.parse::<u64>().map_err(|_| "无效 ID".to_string());
    match kind {
        "album" => {
            let page = api.get_album_page(num()?)?;
            let (album, tracks) = (page.album, page.tracks);
            let multi = album.number_of_volumes.unwrap_or(1) > 1;
            Ok(JobMeta {
                kind: "专辑".into(),
                title: album.full_title(),
                subtitle: album.artist_names(),
                cover: album.cover.clone(),
                tracks: tracks
                    .iter()
                    .enumerate()
                    .map(|(i, t)| {
                        let n = t.track_number.unwrap_or(i as u32 + 1);
                        JobTrack {
                            label: if multi { format!("{}-{:02}", t.volume_number.unwrap_or(1), n) } else { format!("{:02}", n) },
                            title: t.title.clone(),
                            status: TrackStatus::Pending,
                        }
                    })
                    .collect(),
            })
        }
        "track" => {
            let t = api.get_track(num()?)?;
            Ok(JobMeta {
                kind: "单曲".into(),
                title: t.full_title(),
                subtitle: t.artist_names(),
                cover: t.album.as_ref().and_then(|a| a.cover.clone()),
                tracks: vec![JobTrack { label: "01".into(), title: t.title.clone(), status: TrackStatus::Pending }],
            })
        }
        "playlist" => {
            let page = api.get_playlist_page(&id)?;
            Ok(JobMeta {
                kind: "歌单".into(),
                title: page.playlist.title.clone(),
                subtitle: page.playlist.creator_name(),
                cover: page.playlist.cover().map(str::to_string),
                tracks: page
                    .tracks
                    .iter()
                    .enumerate()
                    .map(|(i, t)| JobTrack { label: format!("{:02}", i + 1), title: t.title.clone(), status: TrackStatus::Pending })
                    .collect(),
            })
        }
        other => Err(format!("{} 类型暂不预读曲目，进度将按完成数显示", other)),
    }
}

fn bytes_written_since(dest: &PathBuf, since: SystemTime) -> u64 {
    walkdir::WalkDir::new(dest)
        .max_depth(5)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file())
        .filter_map(|e| e.metadata().ok())
        .filter(|m| m.modified().map(|t| t >= since).unwrap_or(false))
        .map(|m| m.len())
        .sum()
}

fn pump<R: Read>(r: R, mut f: impl FnMut(String)) {
    for line in BufReader::new(r).lines().map_while(Result::ok) {
        f(line);
    }
}

fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\x1b' {
            if it.peek() == Some(&'[') {
                it.next();
                for c2 in it.by_ref() {
                    if c2.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

pub fn fmt_bytes(b: f64) -> String {
    const MB: f64 = 1024.0 * 1024.0;
    if b >= 1024.0 * MB {
        format!("{:.2} GB", b / 1024.0 / MB)
    } else if b >= MB {
        format!("{:.1} MB", b / MB)
    } else {
        format!("{:.0} KB", b / 1024.0)
    }
}

#[cfg(test)]
mod netease_tests {
    use super::*;
    use std::io::BufRead;
    use std::net::TcpListener;

    #[test]
    fn netease_search_and_download_respect_trial_limits() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = thread::spawn({
            let base = base.clone();
            move || {
                for _ in 0..15 {
                    let (mut socket, _) = listener.accept().unwrap();
                    let mut reader = BufReader::new(&socket);
                    let mut request = String::new();
                    reader.read_line(&mut request).unwrap();
                    loop {
                        let mut header = String::new();
                        if reader.read_line(&mut header).unwrap() == 0 || header == "\r\n" {
                            break;
                        }
                    }
                    let target = request.split_whitespace().nth(1).unwrap();
                    let url = reqwest::Url::parse(&format!("{base}{target}")).unwrap();
                    let song = serde_json::json!({
                        "id": 42, "name": "测试歌曲", "dt": 123000,
                        "ar": [{"id": 17, "name": "测试歌手"}],
                        "al": {"id": 20, "name": "测试专辑", "picUrl": "https://example.com/cover.jpg"}
                    });
                    let body = match url.path() {
                        "/api/cloudsearch" => {
                            let params: std::collections::HashMap<_, _> = url.query_pairs().collect();
                            assert_eq!(params["keywords"], "测试 + &");
                            let result = match params["type"].as_ref() {
                                "1" => serde_json::json!({"songs": [song]}),
                                "10" => serde_json::json!({"albums": [song["al"]]}),
                                "100" => serde_json::json!({"artists": song["ar"]}),
                                "1000" => serde_json::json!({"playlists": [{"id": 24, "name": "测试歌单"}]}),
                                _ => panic!("unexpected search type"),
                            };
                            serde_json::json!({"code": 200, "result": result}).to_string()
                        }
                        "/api/album" => serde_json::json!({
                            "code": 200, "album": song["al"], "songs": [song],
                        }).to_string(),
                        "/api/artists" => serde_json::json!({
                            "code": 200, "artist": song["ar"][0], "hotSongs": [song],
                        }).to_string(),
                        "/api/artist/album" => serde_json::json!({
                            "code": 200, "hotAlbums": [song["al"]],
                        }).to_string(),
                        "/api/playlist/detail" => serde_json::json!({
                            "code": 200, "playlist": {"id": 24, "name": "测试歌单", "trackCount": 1},
                        }).to_string(),
                        "/api/playlist/track/all" | "/api/song/detail" => {
                            serde_json::json!({"code": 200, "songs": [song]}).to_string()
                        }
                        "/api/song/url/v1" => {
                            let params: std::collections::HashMap<_, _> = url.query_pairs().collect();
                            assert_eq!(params["id"], "42");
                            let trial = (params["level"] == "hires").then_some(serde_json::json!({"start": 0, "end": 30}));
                            serde_json::json!({"code": 200, "data": [{
                                "id": 42, "url": format!("{base}/audio"), "type": "flac",
                                "level": "lossless", "freeTrialInfo": trial,
                            }]}).to_string()
                        }
                        "/audio" => "fLaC sample".into(),
                        path => panic!("unexpected request {path}"),
                    };
                    write!(socket, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body).unwrap();
                }
            }
        });

        let previous = std::env::var_os("NETEASE_API_URL");
        std::env::set_var("NETEASE_API_URL", format!("{base}/api/"));
        let api = TidalApi::new();
        let results = api.netease().search("测试 + &", 10).unwrap();
        let track = &results.tracks.unwrap().items[0];
        assert_ne!(track.id, 42);
        assert_eq!(track.duration, Some(123));
        assert_eq!(track.url(), "https://music.163.com/song?id=42");
        assert_eq!(normalize_url("https://music.163.com/#/song?id=42&uct=abc"), track.url());
        assert_eq!(crate::types::image_url(track.cover().unwrap(), 320), "https://example.com/cover.jpg");
        let album_id = results.albums.unwrap().items[0].id;
        let artist_id = results.artists.unwrap().items[0].id;
        let playlist_id = &results.playlists.unwrap().items[0].uuid;
        assert_eq!(api.get_album_page(album_id).unwrap().tracks[0].id, track.id);
        assert_eq!(api.get_artist_page(artist_id).unwrap().top_tracks[0].id, track.id);
        assert_eq!(api.get_playlist_page(playlist_id).unwrap().tracks[0].id, track.id);
        let playback = api.get_stream(track.id, StreamQuality::Lossless).unwrap();
        assert_eq!(playback.url, format!("{base}/audio"));

        let stamp = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_nanos();
        let dest = std::env::temp_dir().join(format!("tidal-gui-netease-test-{}-{stamp}", std::process::id()));
        let stop = AtomicBool::new(false);
        let events = std::cell::RefCell::new(Vec::new());
        let send = |event| events.borrow_mut().push(event);
        assert!(download_netease(&api, 42, "max", &dest, &stop, &send).unwrap_err().contains("试听片段"));
        assert!(!dest.exists());
        download_netease(&api, 42, "high", &dest, &stop, &send).unwrap();
        let album_dir = dest.join("网易云");
        let files = std::fs::read_dir(album_dir).unwrap().collect::<Result<Vec<_>, _>>().unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(std::fs::read(files[0].path()).unwrap(), b"fLaC sample");
        assert!(events.borrow().iter().any(|event| matches!(event, JobEvent::Saved(TrackStatus::Downloaded, _))));
        std::fs::remove_dir_all(dest).unwrap();
        server.join().unwrap();
        if let Some(previous) = previous {
            std::env::set_var("NETEASE_API_URL", previous);
        } else {
            std::env::remove_var("NETEASE_API_URL");
        }
    }
}
