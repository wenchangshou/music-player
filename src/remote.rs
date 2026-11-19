//! Remote control of the other players signed in to the same player account.
//!
//! Choosing a device sends the player bar, queue panel and shortcuts to that device, in the
//! style of Spotify Connect. Requests go through the relay's `/api` routes with this
//! installation's account session. Commands are sent one at a time, each followed by polling
//! for its receipt.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use reqwest::blocking::Client;
use serde_json::{json, Value};

use crate::playback::Repeat;

#[derive(Clone, Debug, Default)]
pub struct NowPlaying {
    pub title: String,
    pub artist: String,
    pub cover: Option<String>,
    pub playing: bool,
}

#[derive(Clone, Debug, Default)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub online: bool,
    pub version: String,
    pub is_self: bool,
    pub now: Option<NowPlaying>,
    /// Seconds since the device last reached the relay; `None` if not since the relay started.
    pub last_seen_ago: Option<u64>,
}

impl DeviceInfo {
    pub fn icon(&self) -> &'static str {
        if self.platform == "android" { "📱" } else { "💻" }
    }

    pub fn platform_label(&self) -> &'static str {
        if self.platform == "android" { "Android" } else { "Linux 桌面" }
    }

    pub fn last_seen_label(&self) -> String {
        match self.last_seen_ago {
            None => "最近未连接".into(),
            Some(seconds) if seconds < 60 => "刚刚在线".into(),
            Some(seconds) if seconds < 3600 => format!("{} 分钟前在线", seconds / 60),
            Some(seconds) if seconds < 86400 => format!("{} 小时前在线", seconds / 3600),
            Some(seconds) => format!("{} 天前在线", seconds / 86400),
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct RemoteTrack {
    /// TIDAL track id; `None` for local files and NetEase tracks, which only play on their device.
    pub tidal_id: Option<u64>,
    pub title: String,
    pub artist: String,
    pub cover: Option<String>,
    /// Where the audio comes from: "TIDAL", "网易云" or "本地音乐".
    pub source: String,
}

#[derive(Clone, Debug)]
pub struct RemoteState {
    pub queue: Vec<RemoteTrack>,
    pub revision: Value,
    pub index: Option<usize>,
    pub playing: bool,
    pub loading: bool,
    position_ms: u64,
    pub duration_ms: u64,
    pub seekable: bool,
    pub shuffle: bool,
    pub repeat: Repeat,
    pub error: Option<String>,
    pub quality: Option<String>,
    /// Output volume 0–100, when the device reports one.
    pub volume: Option<f32>,
    /// The quality the device asks TIDAL for.
    pub requested: Option<crate::types::StreamQuality>,
    /// Storage path of the current track when it is a local file on the device.
    pub source_path: Option<String>,
    /// The 网易云 level the device asks for (see `netease::PLAY_LEVELS`).
    pub requested_netease: Option<String>,
    /// The device is signed in to TIDAL and lets this one browse and download through it.
    pub lends_tidal: bool,
    /// The device is signed in to TIDAL, whether or not it lends the login.
    pub tidal_logged_in: bool,
    /// The device runs downloads for this one and lists them.
    pub downloads: bool,
    received: Instant,
}

impl RemoteState {
    fn parse(value: &Value) -> Option<Self> {
        let queue = value["queue"].as_array()?;
        let text = |item: &Value, key: &str| item[key].as_str().unwrap_or_default().to_owned();
        let queue: Vec<RemoteTrack> = queue
            .iter()
            .map(|item| RemoteTrack {
                tidal_id: item["id"].as_u64().filter(|id| {
                    *id > 0 && item["kind"].as_str().map_or(item["source"] != "本地音乐", |kind| kind == "tidal")
                        && crate::netease::native_id(*id).is_none()
                }),
                title: text(item, "title"),
                artist: text(item, "artist"),
                // Only web artwork can be shown here; Android local covers are content:// URIs.
                cover: item["cover"].as_str().filter(|url| url.starts_with("https://")).map(str::to_owned),
                source: item["source"].as_str().map(str::to_owned).unwrap_or_else(|| match item["kind"].as_str() {
                    Some("local") => "本地音乐".into(),
                    Some("netease") => "网易云".into(),
                    _ => "TIDAL".into(),
                }),
            })
            .collect();
        let index = value["index"].as_u64().map(|index| index as usize).filter(|index| *index < queue.len());
        let error = value["error"].as_str().filter(|error| !error.is_empty()).map(str::to_owned);
        // TIDAL levels by TIDAL's names; NetEase devices already report "网易云 · 超清母带" etc.
        let netease = value["actualQuality"].as_str().filter(|quality| quality.starts_with("网易云")).map(str::to_owned);
        let quality = netease.or_else(|| value["actualQuality"].as_str().and_then(quality_label).map(|label| {
            match value["resolution"].as_str().filter(|text| !text.is_empty()) {
                Some(resolution) => format!("{label} · {resolution}"),
                None => label.to_owned(),
            }
        }));
        let features: Vec<&str> = value["features"].as_array().map(Vec::as_slice).unwrap_or_default()
            .iter().filter_map(Value::as_str).collect();
        Some(Self {
            queue,
            revision: value["revision"].clone(),
            index,
            playing: value["playing"] == true,
            loading: value["loading"] == true,
            position_ms: value["position"].as_u64().unwrap_or(0),
            duration_ms: value["duration"].as_u64().unwrap_or(0),
            seekable: value["seekable"] == true,
            shuffle: value["shuffle"] == true,
            repeat: match value["repeat"].as_u64() {
                Some(1) => Repeat::All,
                Some(2) => Repeat::One,
                _ => Repeat::Off,
            },
            error,
            quality,
            volume: value["volume"].as_f64().map(|volume| volume.clamp(0.0, 100.0) as f32),
            requested: value["requestedQuality"].as_str().and_then(stream_quality),
            requested_netease: value["requestedNetease"].as_str().filter(|level| !level.is_empty()).map(str::to_owned),
            lends_tidal: value["loggedIn"] == true && features.contains(&"tidal"),
            tidal_logged_in: value["loggedIn"] == true,
            downloads: features.contains(&"downloads"),
            source_path: value["sourcePath"].as_str().filter(|path| !path.is_empty()).map(str::to_owned),
            received: Instant::now(),
        })
    }

    pub fn current(&self) -> Option<&RemoteTrack> {
        self.index.and_then(|index| self.queue.get(index))
    }

    /// Position in seconds, advanced locally between polls while the device is playing.
    pub fn position(&self) -> f64 {
        let mut ms = self.position_ms as f64;
        if self.playing && !self.loading {
            ms += self.received.elapsed().as_millis() as f64;
        }
        if self.duration_ms > 0 {
            ms = ms.min(self.duration_ms as f64);
        }
        ms / 1000.0
    }

    pub fn duration(&self) -> f64 {
        self.duration_ms as f64 / 1000.0
    }

    pub fn has_next(&self) -> bool {
        self.index.is_some_and(|index| index + 1 < self.queue.len()) || self.repeat != Repeat::Off || self.shuffle
    }

    /// Immediate feedback before the device confirms a command.
    fn predict(&mut self, command: &Value) {
        let position = (self.position() * 1000.0) as u64;
        match command["op"].as_str() {
            Some("toggle") => {
                self.position_ms = position;
                self.received = Instant::now();
                self.playing = !self.playing;
            }
            Some("seek") => {
                self.position_ms = command["ms"].as_u64().unwrap_or(position);
                self.received = Instant::now();
            }
            Some("shuffle") => self.shuffle = !self.shuffle,
            Some("volume") => self.volume = command["value"].as_f64().map(|volume| volume as f32),
            Some("quality") => match command["value"].as_str().unwrap_or_default().strip_prefix("netease:") {
                Some(level) => self.requested_netease = Some(level.to_owned()),
                None => self.requested = command["value"].as_str().and_then(stream_quality),
            },
            Some("clear") => {
                if let Some(index) = self.index {
                    self.queue.truncate(index + 1);
                }
            }
            Some("repeat") => self.repeat = match self.repeat {
                Repeat::Off => Repeat::All,
                Repeat::All => Repeat::One,
                Repeat::One => Repeat::Off,
            },
            _ => {}
        }
    }
}

pub fn stream_quality(name: &str) -> Option<crate::types::StreamQuality> {
    crate::types::StreamQuality::ALL.into_iter().find(|quality| quality.api_name() == name)
}

fn quality_label(quality: &str) -> Option<&'static str> {
    crate::types::tidal_quality_name(quality)
}

/// One song of a controlled Android device's library. Local files have negative ids.
#[derive(Clone, Debug)]
pub struct LibraryTrack {
    pub id: i64,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub cover: Option<String>,
}

impl LibraryTrack {
    pub fn local(&self) -> bool {
        self.id < 0
    }
}

/// What the library page of the controlled device shows, and what it asked for.
#[derive(Default)]
pub struct LibraryView {
    pub tab: Option<crate::route::RemoteTab>,
    pub query: String,
    pub playlist: String,
    /// (tab, query, playlist) the tracks below belong to.
    pub loaded: Option<(crate::route::RemoteTab, String, String)>,
    pub tracks: Vec<LibraryTrack>,
    pub next: Option<u64>,
    pub playlists: Vec<String>,
    pub loading: bool,
    pub error: Option<String>,
    pub request: u64,
}

impl LibraryView {
    /// Apply one page of a `library` reply.
    pub fn apply(&mut self, page: &Value, append: bool) {
        let text = |item: &Value, key: &str| item[key].as_str().unwrap_or_default().to_owned();
        let tracks = page["tracks"].as_array().map(Vec::as_slice).unwrap_or_default().iter().filter_map(|item| {
            Some(LibraryTrack {
                id: item["id"].as_i64()?,
                title: text(item, "title"),
                artist: text(item, "artist"),
                album: text(item, "album"),
                cover: item["cover"].as_str().filter(|url| url.starts_with("https://")).map(str::to_owned),
            })
        });
        if !append {
            self.tracks.clear();
        }
        self.tracks.extend(tracks);
        self.next = page["next"].as_i64().filter(|next| *next >= 0).map(|next| next as u64);
        if let Some(playlists) = page["playlists"].as_array() {
            self.playlists = playlists.iter().filter_map(Value::as_str).map(str::to_owned).collect();
        }
    }
}

/// Cheap, cloneable handle for the blocking HTTP calls made on worker threads.
#[derive(Clone)]
pub struct Connection {
    origin: String,
    key: String,
    client: Client,
}

impl Connection {
    fn request(&self, request: reqwest::blocking::RequestBuilder) -> Result<Value, String> {
        let response = request.bearer_auth(&self.key).send().map_err(|_| "无法连接多端后台".to_string())?;
        let status = response.status();
        let body: Value = response.json().unwrap_or_default();
        if status.is_success() {
            return Ok(body);
        }
        Err(match status.as_u16() {
            401 => "播放器账号登录已失效，请重新登录".into(),
            409 => "设备离线".into(),
            _ => body["error"].as_str().map(str::to_owned).unwrap_or_else(|| format!("后台请求失败（HTTP {status}）")),
        })
    }

    fn get(&self, path: &str) -> Result<Value, String> {
        self.request(self.client.get(format!("{}{path}", self.origin)))
    }

    pub fn devices(&self) -> Result<Vec<DeviceInfo>, String> {
        let body = self.get("/api/devices")?;
        let text = |item: &Value, key: &str| item[key].as_str().unwrap_or_default().to_owned();
        Ok(body["devices"]
            .as_array()
            .map(Vec::as_slice)
            .unwrap_or_default()
            .iter()
            .map(|item| DeviceInfo {
                id: text(item, "id"),
                name: text(item, "name"),
                platform: text(item, "platform"),
                online: item["online"] == true,
                version: text(item, "version"),
                is_self: item["self"] == true,
                last_seen_ago: item["lastSeenAgo"].as_u64(),
                now: item["nowPlaying"].as_object().map(|now| NowPlaying {
                    title: now.get("title").and_then(Value::as_str).unwrap_or_default().to_owned(),
                    artist: now.get("artist").and_then(Value::as_str).unwrap_or_default().to_owned(),
                    cover: now.get("cover").and_then(Value::as_str)
                        .filter(|url| url.starts_with("https://")).map(str::to_owned),
                    playing: now.get("playing").and_then(Value::as_bool).unwrap_or(false),
                }),
            })
            .collect())
    }

    pub fn state(&self, device: &str) -> Result<RemoteState, String> {
        let body = self.get(&format!("/api/state?device={}", urlencoding::encode(device)))?;
        RemoteState::parse(&body).ok_or_else(|| "设备状态无效".into())
    }

    /// Send one command and wait for the device's receipt. Returns the new state when the
    /// device reports one.
    pub fn control(&self, device: &str, command: &Value) -> Result<Option<RemoteState>, String> {
        self.query(device, command).map(|result| RemoteState::parse(&result))
    }

    /// Send one command and return the device's raw reply, e.g. a page of its music library.
    pub fn query(&self, device: &str, command: &Value) -> Result<Value, String> {
        let device = urlencoding::encode(device);
        let sent = self.request(
            self.client.post(format!("{}/api/control?device={device}", self.origin)).json(command),
        )?;
        let id = sent["command"].as_str().ok_or("后台未返回命令编号")?;
        // `wait=1` lets the relay hold the request until the device answers; older relays answer
        // at once without `held`, so pause between requests for them.
        let path = format!("/api/receipt?device={device}&command={}&wait=1", urlencoding::encode(id));
        let deadline = Instant::now() + Duration::from_secs(20);
        while Instant::now() < deadline {
            let receipt = self.get(&path)?;
            if receipt["pending"] == true {
                if receipt["held"] != true {
                    std::thread::sleep(Duration::from_millis(500));
                }
                continue;
            }
            let result = receipt["result"].clone();
            if let Some(error) = result["error"].as_str() {
                return Err(error.to_owned());
            }
            return Ok(result);
        }
        Err("设备未及时响应".into())
    }

    /// End every other session of this player account, including web sessions.
    pub fn logout_others(&self) -> Result<(), String> {
        self.request(self.client.post(format!("{}/account/logout-others", self.origin)).json(&json!({})))
            .map(|_| ())
    }

    pub fn remove(&self, device: &str) -> Result<(), String> {
        self.request(
            self.client.post(format!("{}/account/remove-device", self.origin)).json(&json!({"id": device})),
        )
        .map(|_| ())
    }
}

/// Remote-control state owned by the UI thread. Network work runs on threads spawned by `App`.
pub struct Remote {
    pub connection: Connection,
    pub devices: Vec<DeviceInfo>,
    pub devices_error: Option<String>,
    pub devices_loaded: bool,
    pub devices_busy: bool,
    last_devices: Option<Instant>,

    pub target: Option<String>,
    pub state: Option<RemoteState>,
    pub error: Option<String>,
    /// Bumped whenever the target changes so late replies for the old device are dropped.
    pub generation: u64,
    pub state_busy: bool,
    last_state: Option<Instant>,
    commands: VecDeque<Value>,
    pub command_busy: bool,
    pub library: LibraryView,
    /// The controlled device's download list while the downloads page shows it.
    pub downloads: Option<Value>,
    pub downloads_error: Option<String>,
    pub downloads_busy: bool,
    pub last_downloads: Option<Instant>,
    /// What the controlled device has saved, for "已下载" marks in lists here.
    pub marks: Option<crate::marks::Marks>,
    pub marks_busy: bool,
    pub last_marks: Option<Instant>,
}

impl Remote {
    pub fn new(origin: String, key: String) -> Option<Self> {
        let client = Client::builder()
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .ok()?;
        Some(Self {
            connection: Connection { origin, key, client },
            devices: Vec::new(),
            devices_error: None,
            devices_loaded: false,
            devices_busy: false,
            last_devices: None,
            target: None,
            state: None,
            error: None,
            generation: 0,
            state_busy: false,
            last_state: None,
            commands: VecDeque::new(),
            command_busy: false,
            library: LibraryView::default(),
            downloads: None,
            downloads_error: None,
            downloads_busy: false,
            last_downloads: None,
            marks: None,
            marks_busy: false,
            last_marks: None,
        })
    }

    pub fn target_device(&self) -> Option<&DeviceInfo> {
        let target = self.target.as_ref()?;
        self.devices.iter().find(|device| &device.id == target)
    }

    /// The controlled device has its own music library to browse (Android phones do).
    pub fn target_has_library(&self) -> bool {
        self.target_device().is_some_and(|device| device.platform == "android")
    }

    pub fn target_name(&self) -> String {
        self.target_device().map(|device| device.name.clone()).unwrap_or_else(|| "远程设备".into())
    }

    pub fn select(&mut self, target: Option<String>) {
        if self.target == target {
            return;
        }
        self.target = target;
        self.state = None;
        self.error = None;
        self.generation += 1;
        self.commands.clear();
        self.last_state = None;
        // Replies still in flight for the old target carry the old generation and are dropped.
        self.state_busy = false;
        self.command_busy = false;
        self.library = LibraryView::default();
        self.downloads = None;
        self.downloads_error = None;
        self.downloads_busy = false;
        self.last_downloads = None;
        self.marks = None;
        self.marks_busy = false;
        self.last_marks = None;
    }

    /// Downloads run on the controlled device and the downloads page lists them.
    pub fn remote_downloads(&self) -> bool {
        self.target.is_some() && self.state.as_ref().is_some_and(|state| state.downloads)
    }

    pub fn devices_due(&self, period: Duration) -> bool {
        !self.devices_busy && self.last_devices.is_none_or(|last| last.elapsed() >= period)
    }

    pub fn devices_started(&mut self) {
        self.devices_busy = true;
        self.last_devices = Some(Instant::now());
    }

    pub fn refresh_devices_soon(&mut self) {
        self.last_devices = None;
    }

    pub fn set_devices(&mut self, result: Result<Vec<DeviceInfo>, String>) {
        self.devices_busy = false;
        match result {
            Ok(devices) => {
                self.devices = devices;
                self.devices_error = None;
                self.devices_loaded = true;
            }
            Err(error) => self.devices_error = Some(error),
        }
    }

    pub fn state_due(&self) -> bool {
        self.target.is_some()
            && !self.state_busy
            && !self.command_busy
            && self.commands.is_empty()
            && self.last_state.is_none_or(|last| last.elapsed() >= Duration::from_millis(1000))
    }

    pub fn state_started(&mut self) {
        self.state_busy = true;
        self.last_state = Some(Instant::now());
    }

    pub fn set_state(&mut self, generation: u64, result: Result<RemoteState, String>) {
        if generation != self.generation {
            return;
        }
        self.state_busy = false;
        match result {
            Ok(state) => {
                self.state = Some(state);
                self.error = None;
            }
            Err(error) => self.error = Some(error),
        }
    }

    pub fn push(&mut self, command: Value) {
        if let Some(state) = &mut self.state {
            state.predict(&command);
        }
        self.commands.push_back(command);
    }

    pub fn next_command(&mut self) -> Option<Value> {
        if self.command_busy || self.state_busy {
            return None;
        }
        let command = self.commands.pop_front()?;
        self.command_busy = true;
        Some(command)
    }

    /// Returns the error to show, if any.
    pub fn command_done(&mut self, generation: u64, result: Result<Option<RemoteState>, String>) -> Option<String> {
        if generation != self.generation {
            return None;
        }
        self.command_busy = false;
        self.last_state = Some(Instant::now());
        match result {
            Ok(Some(state)) => {
                self.state = Some(state);
                None
            }
            Ok(None) => None,
            Err(error) => {
                // Our prediction may be wrong now; fetch the real state right away.
                self.last_state = None;
                Some(error)
            }
        }
    }

    pub fn pending(&self) -> usize {
        self.commands.len() + usize::from(self.command_busy)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_android_and_desktop_states() {
        let android = json!({"queue": [{"id": -1, "title": "A", "artist": "X", "cover": "content://local", "kind": "local", "source": "本地音乐"},
                                       {"id": 2, "title": "B", "artist": "Y", "cover": "https://resources.tidal.com/b.jpg"}],
            "revision": 9, "index": 1, "playing": false, "position": 5000, "duration": 200000,
            "seekable": true, "shuffle": true, "repeat": 2, "error": "", "actualQuality": "HI_RES_LOSSLESS",
            "resolution": "24bit/96kHz", "sourcePath": "/storage/emulated/0/Music/A.flac"});
        let state = RemoteState::parse(&android).unwrap();
        assert_eq!(state.current().unwrap().title, "B");
        assert_eq!(state.queue[0].cover, None, "content:// covers are not loadable here");
        assert_eq!((state.queue[0].source.as_str(), state.queue[0].tidal_id), ("本地音乐", None));
        assert_eq!(state.queue[1].source, "TIDAL", "older devices without a source field count as TIDAL");
        assert_eq!(state.source_path.as_deref(), Some("/storage/emulated/0/Music/A.flac"));
        assert_eq!(state.repeat, Repeat::One);
        assert_eq!(state.error, None);
        assert_eq!(state.quality.as_deref(), Some("Max · 24bit/96kHz"));
        assert!((state.position() - 5.0).abs() < 0.01);

        let desktop = json!({"queue": [], "revision": "00ff", "index": null, "playing": false,
            "error": null, "repeat": 1, "actualQuality": ""});
        let state = RemoteState::parse(&desktop).unwrap();
        assert!(state.current().is_none());
        assert_eq!(state.repeat, Repeat::All);
        assert_eq!(state.revision, json!("00ff"));
        assert!(RemoteState::parse(&json!({"error": "x"})).is_none());
    }

    #[test]
    fn netease_levels_use_official_names() {
        assert_eq!(crate::netease::level_name("jymaster"), "超清母带");
        assert_eq!(crate::netease::level_name("higher"), "较高");
        assert_eq!(crate::netease::play_level("jymaster"), "jymaster");
        assert_eq!(crate::netease::play_level("dolby"), "lossless", "unknown saved levels fall back to 无损");
        assert_eq!(crate::types::tidal_quality_name("LOSSLESS"), Some("High"));
        let netease = RemoteState::parse(&json!({"queue": [{"title": "N", "kind": "netease"}], "index": 0,
            "actualQuality": "网易云 · 超清母带", "requestedNetease": "jymaster"})).unwrap();
        assert_eq!(netease.quality.as_deref(), Some("网易云 · 超清母带"));
        assert_eq!(netease.requested_netease.as_deref(), Some("jymaster"));
        assert_eq!(netease.queue[0].source, "网易云");
    }

    #[test]
    fn library_pages_append_and_keep_playlists() {
        let mut view = LibraryView::default();
        view.apply(&json!({"tracks": [{"id": -3, "title": "Local", "artist": "A", "album": "B", "cover": ""},
                                      {"id": 77, "title": "Tidal", "cover": "https://resources.tidal.com/x.jpg"}],
                           "next": 50, "playlists": ["跑步"]}), false);
        assert_eq!(view.tracks.len(), 2);
        assert!(view.tracks[0].local() && !view.tracks[1].local());
        assert_eq!(view.tracks[0].cover, None);
        assert_eq!(view.next, Some(50));
        view.apply(&json!({"tracks": [{"id": -4, "title": "More"}], "next": -1}), true);
        assert_eq!(view.tracks.len(), 3);
        assert_eq!(view.next, None);
        assert_eq!(view.playlists, vec!["跑步".to_string()], "pages without playlists keep the list");
    }

    #[test]
    fn predicts_toggle_and_repeat() {
        let mut state = RemoteState::parse(&json!({"queue": [{"title": "A"}], "index": 0,
            "playing": false, "position": 1000, "repeat": 0})).unwrap();
        state.predict(&json!({"op": "toggle"}));
        assert!(state.playing);
        state.predict(&json!({"op": "repeat"}));
        assert_eq!(state.repeat, Repeat::All);
        state.predict(&json!({"op": "seek", "ms": 3000}));
        assert!(state.position() >= 3.0);
    }
}
