use std::sync::{
    atomic::{AtomicBool, Ordering},
    mpsc, Arc, Mutex,
};
use std::thread;
use std::time::{Duration, Instant};

use eframe::egui;
use reqwest::blocking::Client;
use serde_json::{json, Value};

use crate::api::TidalApi;
use crate::playback::{Playback, Repeat};
use crate::types::TrackItem;

pub struct Command {
    pub body: Value,
    pub reply: mpsc::SyncSender<Value>,
}

pub fn account_origin() -> Option<String> {
    let value = std::env::var("TIDAL_ACCOUNT_ORIGIN").ok()?;
    let url = reqwest::Url::parse(&value).ok()?;
    (url.scheme() == "https" && url.host().is_some()
        && url.username().is_empty() && url.password().is_none()
        && url.path() == "/" && url.query().is_none() && url.fragment().is_none())
        .then(|| url.origin().ascii_serialization())
}

pub struct Relay {
    stop: Arc<AtomicBool>,
    state: Arc<Mutex<Value>>,
    commands: mpsc::Receiver<Command>,
}

impl Relay {
    pub fn configured(api: TidalApi, ctx: egui::Context, initial: Value) -> Option<Self> {
        let (origin, id, key) = connection_settings()?;
        Self::start(api, ctx, initial, origin, id, key)
    }

    pub fn account(
        api: TidalApi,
        ctx: egui::Context,
        initial: Value,
        account: &PlayerAccount,
    ) -> Option<Self> {
        Self::start(
            api,
            ctx,
            initial,
            account.origin()?,
            account.id.clone(),
            account.key.clone(),
        )
    }

    fn start(
        api: TidalApi,
        ctx: egui::Context,
        initial: Value,
        origin: String,
        id: String,
        key: String,
    ) -> Option<Self> {
        let origin = reqwest::Url::parse(&origin).ok()?;
        if origin.scheme() != "https"
            || origin.host().is_none()
            || !origin.username().is_empty()
            || origin.password().is_some()
            || origin.path() != "/"
            || origin.query().is_some()
            || origin.fragment().is_some()
            || !url_safe(&id, 1, 64)
            || !url_safe(&key, 32, 128)
        {
            eprintln!("TIDAL relay: invalid HTTPS URL or device configuration");
            return None;
        }

        let endpoint = origin.join("/device/poll").ok()?;
        let client = Client::builder()
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .ok()?;
        let state = Arc::new(Mutex::new(initial));
        let latest = Arc::clone(&state);
        let (send, commands) = mpsc::sync_channel(1);
        let stop = Arc::new(AtomicBool::new(false));
        let running = Arc::clone(&stop);
        thread::spawn(move || {
            let mut receipt: Option<Value> = None;
            let mut last_refresh: Option<Instant> = None;
            let mut last_hires_refresh: Option<Instant> = None;
            while !running.load(Ordering::Relaxed) {
                let state = latest.lock().expect("relay state lock").clone();
                let auth = api.auth();
                let session = match (auth.token, auth.user_id, auth.country_code, auth.expires_at) {
                    (Some(token), Some(user), Some(country), Some(expires)) => {
                        let hires = api.cached_hires_session(&user);
                        if hires.as_ref().is_none_or(|(_, expiry)| *expiry - chrono::Utc::now().timestamp() <= 120)
                            && last_hires_refresh.is_none_or(|last| last.elapsed() >= Duration::from_secs(60))
                        {
                            last_hires_refresh = Some(Instant::now());
                            let refresh_api = api.clone();
                            thread::spawn(move || { let _ = refresh_api.hires_token(false); });
                        }
                        let mut session = json!({
                            "token": token, "user_id": user, "country_code": country, "expires_at": expires,
                        });
                        if let Some((token, expiry)) = hires {
                            session["hires_token"] = json!(token);
                            session["hires_expires_at"] = json!(expiry);
                        }
                        session
                    }
                    _ => Value::Null,
                };
                // `wait` asks the relay to hold the poll until a command arrives (long polling).
                let mut message = json!({"state": state, "desktop_session": session, "wait": true});
                if let Some(value) = &receipt {
                    message["result"] = value.clone();
                }
                let result = client
                    .post(endpoint.clone())
                    .bearer_auth(&key)
                    .header("X-Device-ID", &id)
                    .json(&message)
                    .send()
                    .and_then(reqwest::blocking::Response::error_for_status)
                    .and_then(|response| response.json::<Value>());
                let mut pause = Duration::from_millis(1500);
                match result {
                    Ok(response) => {
                        receipt = None;
                        // A relay that held the request can be polled again right away.
                        if response["wait"] == true {
                            pause = Duration::ZERO;
                        }
                        if api.is_authenticated()
                            && api
                                .token_remaining()
                                .is_some_and(|remaining| remaining <= 120)
                            && last_refresh
                                .is_none_or(|last| last.elapsed() >= Duration::from_secs(60))
                        {
                            last_refresh = Some(Instant::now());
                            let refresh_api = api.clone();
                            thread::spawn(move || {
                                let _ = refresh_api.refresh_token();
                            });
                        }
                        if let (Some(id), Some(body)) =
                            (response["id"].as_str(), response.get("command"))
                        {
                            let payload = if body["op"] == "search" {
                                search(&api, body)
                            } else if body["op"] == "tidal" {
                                forward(&api, body)
                            } else {
                                let body = match prepare(&api, body.clone()) {
                                    Ok(body) => body,
                                    Err(error) => {
                                        receipt =
                                            Some(json!({"id": id, "payload": {"error": error}}));
                                        continue;
                                    }
                                };
                                let (reply, outcome) = mpsc::sync_channel(1);
                                if send.send(Command { body, reply }).is_err() {
                                    break;
                                }
                                ctx.request_repaint();
                                outcome
                                    .recv_timeout(Duration::from_secs(5))
                                    .unwrap_or_else(|_| json!({"error": "桌面播放器未响应"}))
                            };
                            receipt = Some(json!({"id": id, "payload": payload}));
                            continue;
                        }
                    }
                    Err(_) => thread::sleep(Duration::from_secs(3)),
                }
                thread::sleep(pause);
            }
        });
        Some(Self {
            stop,
            state,
            commands,
        })
    }

    pub fn publish(&self, state: Value) {
        *self.state.lock().expect("relay state lock") = state;
    }

    pub fn recv(&self) -> Option<Command> {
        self.commands.try_recv().ok()
    }
}

impl Drop for Relay {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct PlayerAccount {
    pub username: String,
    #[serde(default)]
    origin: Option<String>,
    id: String,
    key: String,
}

impl PlayerAccount {
    /// Account session for remote control of this account's other devices.
    pub fn remote(&self) -> Option<crate::remote::Remote> {
        crate::remote::Remote::new(self.origin()?, self.key.clone())
    }

    pub fn origin(&self) -> Option<String> {
        let configured = account_origin()?;
        if self.origin.as_ref().is_some_and(|saved| saved != &configured) {
            return None;
        }
        Some(configured)
    }

    fn path() -> Result<std::path::PathBuf, String> {
        Ok(
            std::path::PathBuf::from(std::env::var_os("HOME").ok_or("找不到用户目录")?)
                .join(".config/tidal-relay/player-account.json"),
        )
    }

    pub fn load() -> Option<Self> {
        serde_json::from_slice(&std::fs::read(Self::path().ok()?).ok()?).ok()
    }

    fn client() -> Result<Client, String> {
        Client::builder()
            .timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| e.to_string())
    }

    pub fn login(username: String, password: String) -> Result<Self, String> {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let path = Self::path()?;
        let origin = account_origin().ok_or("请先配置 TIDAL_ACCOUNT_ORIGIN")?;
        let response = Self::client()?.post(format!("{origin}/account/login"))
            .json(&json!({"username":username,"password":password,"platform":"desktop","name":"Linux 桌面播放器"}))
            .send().map_err(|e| e.to_string())?;
        let success = response.status().is_success();
        let body: Value = response.json().map_err(|e| e.to_string())?;
        if !success {
            return Err(body["error"]
                .as_str()
                .unwrap_or("播放器账号登录失败")
                .to_owned());
        }
        let mut account: Self = serde_json::from_value(body).map_err(|e| e.to_string())?;
        account.origin = Some(origin);
        let save = || -> Result<(), String> {
            std::fs::create_dir_all(path.parent().ok_or("找不到配置目录")?)
                .map_err(|e| e.to_string())?;
            let temporary = path.with_extension(format!("{}.tmp", std::process::id()));
            let result = (|| -> Result<(), String> {
                let mut file = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(&temporary)
                    .map_err(|e| e.to_string())?;
                file.write_all(&serde_json::to_vec(&account).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                std::fs::rename(&temporary, &path).map_err(|e| e.to_string())
            })();
            if result.is_err() {
                let _ = std::fs::remove_file(temporary);
            }
            result
        };
        if let Err(error) = save() {
            let _ = account.revoke();
            return Err(error);
        }
        Ok(account)
    }

    fn revoke(&self) -> Result<(), String> {
        let origin = self.origin().ok_or("请先配置该账号的 TIDAL_ACCOUNT_ORIGIN")?;
        let status = Self::client()?
            .post(format!("{origin}/account/logout"))
            .bearer_auth(&self.key)
            .send()
            .map_err(|e| e.to_string())?
            .status();
        if status.is_success() || status == reqwest::StatusCode::UNAUTHORIZED {
            return Ok(());
        }
        Err(format!("退出登录失败（HTTP {status}）"))
    }

    pub fn logout() -> Result<(), String> {
        if let Some(account) = Self::load() {
            account.revoke()?;
        }
        match std::fs::remove_file(Self::path()?) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.to_string()),
        }
    }
}

fn connection_settings() -> Option<(String, String, String)> {
    Some((
        std::env::var("TIDAL_RELAY_URL").ok()?,
        std::env::var("TIDAL_RELAY_DEVICE").ok()?,
        std::env::var("TIDAL_RELAY_KEY").ok()?,
    ))
}

fn url_safe(value: &str, min: usize, max: usize) -> bool {
    (min..=max).contains(&value.len())
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
}

/// Look tracks up a few at a time, so a whole album arrives within the receipt window.
pub fn fetch_tracks(api: &TidalApi, ids: &[u64]) -> Vec<Result<TrackItem, String>> {
    let mut tracks = Vec::with_capacity(ids.len());
    for chunk in ids.chunks(8) {
        std::thread::scope(|scope| {
            let jobs: Vec<_> = chunk.iter().map(|id| scope.spawn(move || api.get_track(*id))).collect();
            tracks.extend(jobs.into_iter().map(|job| job.join().unwrap_or_else(|_| Err("读取曲目失败".into()))));
        });
    }
    tracks
}

fn prepare(api: &TidalApi, mut command: Value) -> Result<Value, String> {
    if command["op"] == "add" {
        let id = command["id"]
            .as_u64()
            .filter(|id| *id > 0)
            .ok_or("曲目编号无效")?;
        command["track"] = serde_json::to_value(api.get_track(id)?).map_err(|e| e.to_string())?;
    }
    if command["op"] == "tracks" {
        let ids: Vec<u64> = command["ids"].as_array().ok_or("曲目列表无效")?.iter()
            .map(|id| id.as_u64().filter(|id| *id > 0 && crate::netease::native_id(*id).is_none()))
            .collect::<Option<_>>().ok_or("曲目编号无效")?;
        if ids.is_empty() || ids.len() > 50 {
            return Err("每次可发送 1–50 首歌曲".into());
        }
        let tracks = fetch_tracks(api, &ids).into_iter().collect::<Result<Vec<_>, _>>()?;
        command["tracks"] = serde_json::to_value(tracks).map_err(|e| e.to_string())?;
    }
    Ok(command)
}

/// A TIDAL request from another device of the account, answered with this device's login.
fn forward(api: &TidalApi, command: &Value) -> Value {
    if !api.is_authenticated() {
        return json!({"error": "此设备尚未登录 TIDAL"});
    }
    let method = command["method"].as_str().unwrap_or("GET");
    let Some(path) = command["path"].as_str() else { return json!({"error": "请求无效"}) };
    match api.forward(method, path, command["form"].as_str()) {
        // Replies travel through the relay, which accepts device requests of up to 1 MB.
        Ok(body) if body.len() > 900 * 1024 => json!({"error": "TIDAL 返回的内容过多，无法通过远程连接传送"}),
        Ok(body) => json!({"body": body}),
        Err(error) => json!({"error": error}),
    }
}

fn search(api: &TidalApi, command: &Value) -> Value {
    let query = command["query"].as_str().unwrap_or("").trim();
    if query.is_empty() || query.chars().count() > 160 {
        return json!({"error": "请输入 1–160 个字符"});
    }
    match api.search(query, 50) {
        Ok(results) => {
            json!({"tracks": results.tracks.into_iter().flat_map(|list| list.items).map(|item| track(&item)).collect::<Vec<_>>()})
        }
        Err(error) => json!({"error": error}),
    }
}

/// Other devices load only HTTPS covers; NetEase image hosts also serve their covers over HTTPS.
fn shareable_cover(url: String) -> String {
    match url.strip_prefix("http://") {
        Some(rest) if rest.split('/').next().is_some_and(|host| host.ends_with(".music.126.net")) => format!("https://{rest}"),
        _ => url,
    }
}

fn track(item: &TrackItem) -> Value {
    json!({"id": item.id, "title": item.full_title(), "artist": item.artist_names(),
        "album": item.album.as_ref().map(|album| &album.title),
        "cover": item.cover().map(|cover| shareable_cover(crate::types::image_url(cover, 640))),
        "kind": if crate::netease::native_id(item.id).is_some() { "netease" } else { "tidal" },
        "source": if crate::netease::native_id(item.id).is_some() { "网易云" } else { "TIDAL" }})
}

fn stream_resolution(stream: &crate::types::StreamInfo) -> String {
    match (stream.bit_depth, stream.sample_rate) {
        (Some(bits), Some(rate)) => format!("{bits}bit/{}kHz", rate as f32 / 1000.0),
        _ => String::new(),
    }
}

pub fn revision(playback: &Playback) -> String {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    for item in &playback.queue {
        item.id.hash(&mut hasher);
    }
    format!("{:016x}", hasher.finish())
}

pub fn snapshot(playback: &Playback, logged_in: bool) -> Value {
    let player = playback.player.snapshot();
    json!({"queue": playback.queue.iter().map(track).collect::<Vec<_>>(),
        "revision": revision(playback), "index": playback.current().map(|_| playback.index),
        "playing": player.active && !player.paused, "loading": playback.is_loading(),
        "position": (player.position.max(0.0) * 1000.0) as u64,
        "duration": (player.duration.max(0.0) * 1000.0) as u64, "seekable": player.active && player.duration > 0.0,
        "loggedIn": logged_in, "features": ["tidal", "downloads"], "version": env!("CARGO_PKG_VERSION"), "volume": playback.player.volume.round(), "requestedQuality": playback.quality.api_name(), "requestedNetease": playback.netease_level, "shuffle": playback.shuffle, "error": player.error,
        "actualQuality": playback.stream.as_ref().map(|stream| stream.quality.as_str()).unwrap_or(""),
        "resolution": playback.stream.as_ref().map(stream_resolution).unwrap_or_default(),
        "repeat": match playback.repeat { Repeat::Off => 0, Repeat::All => 1, Repeat::One => 2 }})
}
