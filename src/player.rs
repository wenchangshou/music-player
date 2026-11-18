//! Online playback through an `mpv` child process controlled via its JSON IPC socket.
//! Using mpv avoids native audio build deps and gives us FLAC/AAC decoding, seeking and volume.

use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use eframe::egui;
use serde_json::{json, Value};

#[derive(Default, Clone)]
pub struct PlayerState {
    /// Bumped on every play/stop so a stale monitor thread can't overwrite newer state.
    pub generation: u64,
    pub active: bool,
    pub paused: bool,
    pub position: f64,
    pub duration: f64,
    /// Set when the track ended by itself (not via `stop`).
    pub finished: bool,
    pub error: Option<String>,
    pub audio: AudioParams,
}

/// Live decoder/output parameters reported by mpv.
#[derive(Default, Clone, Debug)]
pub struct AudioParams {
    pub codec: Option<String>,
    /// Decoded sample format, e.g. "s16", "s32", "floatp".
    pub format: Option<String>,
    pub samplerate: Option<u32>,
    pub channels: Option<u32>,
    pub bitrate: Option<f64>,
    pub out_format: Option<String>,
    pub out_samplerate: Option<u32>,
    /// Audio output driver in use (pipewire / pulse / alsa …).
    pub ao: Option<String>,
}

fn parse_params(d: Option<&Value>) -> (Option<String>, Option<u32>, Option<u32>) {
    match d {
        Some(Value::Object(m)) => (
            m.get("format").and_then(Value::as_str).map(str::to_string),
            m.get("samplerate").and_then(Value::as_u64).map(|x| x as u32),
            m.get("channel-count").and_then(Value::as_u64).map(|x| x as u32),
        ),
        _ => (None, None, None),
    }
}

pub struct Player {
    child: Option<Child>,
    sock: PathBuf,
    pub state: Arc<Mutex<PlayerState>>,
    pub volume: f32,
}

impl Player {
    pub fn new() -> Self {
        clean_stale_temp_files();
        Self {
            child: None,
            sock: std::env::temp_dir().join(format!("tidal-gui-mpv-{}.sock", std::process::id())),
            state: Arc::new(Mutex::new(PlayerState::default())),
            volume: 80.0,
        }
    }

    pub fn snapshot(&self) -> PlayerState {
        self.state.lock().map(|s| s.clone()).unwrap_or_default()
    }

    /// Start `url` (a stream URL or a local DASH manifest), optionally at `start` seconds.
    pub fn play(&mut self, url: &str, start: f64, ctx: &egui::Context) -> Result<(), String> {
        self.kill_child();
        let _ = std::fs::remove_file(&self.sock);

        let mut cmd = Command::new("mpv");
        cmd.arg("--no-video")
            .arg("--no-terminal")
            .arg("--idle=no")
            .arg(format!("--input-ipc-server={}", self.sock.display()))
            .arg(format!("--volume={}", self.volume.round()))
            // DASH manifests are local files that reference https segments.
            .arg("--demuxer-lavf-o=protocol_whitelist=[file,http,https,tcp,tls,crypto,data]")
            .arg("--cover-art-auto=no")
            .arg("--audio-display=no")
            .arg(format!("--start={:.1}", start.max(0.0)))
            .arg(url)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        // Make mpv exit if the GUI dies without running Drop (crash, SIGKILL, …).
        unsafe {
            use std::os::unix::process::CommandExt;
            cmd.pre_exec(|| {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
                Ok(())
            });
        }
        let child = cmd
            .spawn()
            .map_err(|e| format!("无法启动 mpv（请确认已安装）：{}", e))?;
        self.child = Some(child);

        let gen = {
            let mut st = self.state.lock().map_err(|_| "player state poisoned")?;
            let gen = st.generation + 1;
            *st = PlayerState { generation: gen, active: true, ..Default::default() };
            gen
        };

        let (sock, state, ctx) = (self.sock.clone(), Arc::clone(&self.state), ctx.clone());
        thread::spawn(move || monitor(gen, sock, state, ctx));
        Ok(())
    }

    fn send(&self, cmd: Value) {
        if let Ok(mut s) = UnixStream::connect(&self.sock) {
            let _ = writeln!(s, "{}", json!({ "command": cmd }));
        }
    }

    pub fn toggle_pause(&self) {
        self.send(json!(["cycle", "pause"]));
    }

    pub fn seek(&self, secs: f64) {
        self.send(json!(["seek", secs, "absolute"]));
        if let Ok(mut st) = self.state.lock() {
            st.position = secs;
        }
    }

    pub fn set_volume(&mut self, v: f32) {
        self.volume = v;
        self.send(json!(["set_property", "volume", v]));
    }

    fn kill_child(&mut self) {
        if let Some(mut c) = self.child.take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }

    pub fn stop(&mut self) {
        if let Ok(mut st) = self.state.lock() {
            st.generation += 1;
            st.active = false;
            st.paused = false;
            st.finished = false;
        }
        self.kill_child();
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        self.kill_child();
        let _ = std::fs::remove_file(&self.sock);
        // DASH manifests written for this process.
        let prefix = format!("tidal-gui-{}-", std::process::id());
        if let Ok(rd) = std::fs::read_dir(std::env::temp_dir()) {
            for e in rd.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if n.starts_with(&prefix) && n.ends_with(".mpd") {
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
    }
}

/// Follows mpv property changes until the process exits.
fn monitor(gen: u64, sock: PathBuf, state: Arc<Mutex<PlayerState>>, ctx: egui::Context) {
    let update = |f: &mut dyn FnMut(&mut PlayerState)| {
        if let Ok(mut st) = state.lock() {
            if st.generation == gen {
                f(&mut st);
            }
        }
    };

    // mpv creates the socket shortly after start.
    let mut stream = None;
    for _ in 0..100 {
        if let Ok(s) = UnixStream::connect(&sock) {
            stream = Some(s);
            break;
        }
        thread::sleep(Duration::from_millis(50));
    }
    let Some(mut stream) = stream else {
        update(&mut |st| {
            st.active = false;
            st.error = Some("mpv 未能启动播放".into());
        });
        ctx.request_repaint();
        return;
    };

    let observed = [
        "time-pos",
        "duration",
        "pause",
        "audio-params",
        "audio-out-params",
        "audio-codec-name",
        "audio-bitrate",
        "current-ao",
    ];
    for (i, prop) in observed.iter().enumerate() {
        let _ = writeln!(stream, "{}", json!({ "command": ["observe_property", i + 1, prop] }));
    }

    let mut last_sec = -1i64;
    let mut ended_ok = false;
    for line in BufReader::new(stream).lines().map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        match v.get("event").and_then(Value::as_str) {
            Some("property-change") => {
                let data = v.get("data");
                match v.get("name").and_then(Value::as_str) {
                    Some("time-pos") => {
                        if let Some(p) = data.and_then(Value::as_f64) {
                            update(&mut |st| st.position = p);
                            if p as i64 != last_sec {
                                last_sec = p as i64;
                                ctx.request_repaint();
                            }
                        }
                    }
                    Some("duration") => {
                        if let Some(d) = data.and_then(Value::as_f64) {
                            update(&mut |st| st.duration = d);
                            ctx.request_repaint();
                        }
                    }
                    Some("pause") => {
                        let p = data.and_then(Value::as_bool).unwrap_or(false);
                        update(&mut |st| st.paused = p);
                        ctx.request_repaint();
                    }
                    Some("audio-params") => {
                        let (f, r, c) = parse_params(data);
                        update(&mut |st| {
                            st.audio.format = f.clone();
                            st.audio.samplerate = r;
                            st.audio.channels = c;
                        });
                        ctx.request_repaint();
                    }
                    Some("audio-out-params") => {
                        let (f, r, _) = parse_params(data);
                        update(&mut |st| {
                            st.audio.out_format = f.clone();
                            st.audio.out_samplerate = r;
                        });
                    }
                    Some("audio-codec-name") => {
                        let c = data.and_then(Value::as_str).map(str::to_string);
                        update(&mut |st| st.audio.codec = c.clone());
                    }
                    // Bitrate fluctuates constantly; it is repainted with the per-second tick.
                    Some("audio-bitrate") => {
                        let b = data.and_then(Value::as_f64);
                        update(&mut |st| st.audio.bitrate = b);
                    }
                    Some("current-ao") => {
                        let a = data.and_then(Value::as_str).map(str::to_string);
                        update(&mut |st| st.audio.ao = a.clone());
                    }
                    _ => {}
                }
            }
            Some("end-file") => {
                let reason = v.get("reason").and_then(Value::as_str).unwrap_or("");
                ended_ok = reason == "eof";
                if reason == "error" {
                    update(&mut |st| st.error = Some("音频流播放出错".into()));
                }
            }
            _ => {}
        }
    }

    // Socket closed => mpv exited.
    update(&mut |st| {
        st.active = false;
        st.finished = ended_ok;
    });
    ctx.request_repaint();
}

/// Remove sockets/manifests left in the temp dir by instances that were killed without
/// running `Drop` (file names embed the owning pid).
fn clean_stale_temp_files() {
    let Ok(rd) = std::fs::read_dir(std::env::temp_dir()) else { return };
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let pid = if let Some(r) = name.strip_prefix("tidal-gui-mpv-").and_then(|r| r.strip_suffix(".sock")) {
            r.parse::<u32>().ok()
        } else if let Some(r) = name.strip_prefix("tidal-gui-").filter(|_| name.ends_with(".mpd")) {
            r.split('-').next().and_then(|p| p.parse::<u32>().ok())
        } else {
            None
        };
        if let Some(pid) = pid {
            if pid != std::process::id() && !std::path::Path::new(&format!("/proc/{}", pid)).exists() {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
}
