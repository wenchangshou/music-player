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
                let mut message = json!({"state": state, "desktop_session": session});
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
                match result {
                    Ok(response) => {
                        receipt = None;
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
                thread::sleep(Duration::from_millis(1500));
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

fn prepare(api: &TidalApi, mut command: Value) -> Result<Value, String> {
    if command["op"] == "add" {
        let id = command["id"]
            .as_u64()
            .filter(|id| *id > 0)
            .ok_or("曲目编号无效")?;
        command["track"] = serde_json::to_value(api.get_track(id)?).map_err(|e| e.to_string())?;
    }
    Ok(command)
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

fn track(item: &TrackItem) -> Value {
    json!({"id": item.id, "title": item.full_title(), "artist": item.artist_names(),
        "album": item.album.as_ref().map(|album| &album.title)})
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
        "loggedIn": logged_in, "version": env!("CARGO_PKG_VERSION"), "shuffle": playback.shuffle, "error": player.error,
        "repeat": match playback.repeat { Repeat::Off => 0, Repeat::All => 1, Repeat::One => 2 }})
}
