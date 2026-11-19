use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use reqwest::blocking::Client;
use reqwest::Method;
use serde::de::DeserializeOwned;

use crate::types::{
    AlbumItem, AlbumPage, ArtistItem, ArtistPage, AuthData, Fav, ItemList, LibraryData, PlaylistItem, PlaylistPage,
    SearchResponse, StreamInfo, StreamQuality, TrackItem,
};

pub(crate) fn tiddl_bin() -> std::ffi::OsString {
    std::env::var_os("TIDAL_TIDDL_BIN").unwrap_or_else(|| "tiddl".into())
}
pub(crate) fn python_bin() -> std::ffi::OsString {
    std::env::var_os("TIDAL_PYTHON").unwrap_or_else(|| "python3".into())
}
/// Helper that derives a Hi-Res-capable token with tiddl's own token broker.
const TOKEN_HELPER: &str = include_str!("../scripts/playback_token.py");

/// Which access token a request uses.
#[derive(Clone, Copy, PartialEq)]
enum TokenKind {
    /// The normal login token from auth.json.
    Main,
    /// Capability token for Hi-Res streams (see scripts/playback_token.py).
    HiRes,
}

/// Local path where a DASH manifest for `track_id` is written for mpv.
pub fn manifest_path(track_id: u64) -> PathBuf {
    std::env::temp_dir().join(format!("tidal-gui-{}-{}.mpd", std::process::id(), track_id))
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum FavKind {
    Album,
    Artist,
    Track,
    Playlist,
}

impl FavKind {
    fn path(self) -> &'static str {
        match self {
            FavKind::Album => "albums",
            FavKind::Artist => "artists",
            FavKind::Track => "tracks",
            FavKind::Playlist => "playlists",
        }
    }
    fn form_key(self) -> &'static str {
        match self {
            FavKind::Album => "albumIds",
            FavKind::Artist => "artistIds",
            FavKind::Track => "trackIds",
            FavKind::Playlist => "uuids",
        }
    }
}

/// Cheap to clone; all clones share the same auth state, so a token refresh done by
/// one worker thread is immediately visible everywhere.
#[derive(Clone)]
pub struct TidalApi {
    client: Client,
    auth: Arc<RwLock<AuthData>>,
    refresh_lock: Arc<Mutex<()>>,
    /// Cached Hi-Res token and its expiry (unix seconds). The mutex also serialises helper runs.
    hires: Arc<Mutex<Option<(String, String, i64)>>>,
    pub config_path: PathBuf,
    /// Another device of the player account that answers TIDAL requests while this computer has
    /// no TIDAL login of its own (see `forward` on that device).
    proxy: Arc<RwLock<Option<Proxy>>>,
}

/// Sends (method, path, urlencoded form) to the lending device and returns its response body.
pub type Proxy = Arc<dyn Fn(&str, &str, Option<String>) -> Result<String, String> + Send + Sync>;

impl TidalApi {
    pub fn new() -> Self {
        let config_path = std::env::var("TIDDL_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default().join(".tiddl"));
        let auth = Self::load_auth(&config_path);
        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .unwrap_or_default();
        Self {
            client,
            auth: Arc::new(RwLock::new(auth)),
            refresh_lock: Arc::new(Mutex::new(())),
            hires: Arc::new(Mutex::new(None)),
            config_path,
            proxy: Arc::new(RwLock::new(None)),
        }
    }

    fn load_auth(config_dir: &Path) -> AuthData {
        fs::read_to_string(config_dir.join("auth.json"))
            .ok()
            .and_then(|c| serde_json::from_str::<AuthData>(&c).ok())
            .unwrap_or_default()
    }

    pub fn auth(&self) -> AuthData {
        self.auth.read().map(|a| a.clone()).unwrap_or_default()
    }

    pub fn is_authenticated(&self) -> bool {
        let a = self.auth();
        a.token.is_some() && a.user_id.is_some()
    }

    pub fn set_proxy(&self, proxy: Option<Proxy>) {
        if let Ok(mut current) = self.proxy.write() {
            *current = proxy;
        }
    }

    fn proxy(&self) -> Option<Proxy> {
        self.proxy.read().ok().and_then(|proxy| proxy.clone())
    }

    /// Browsing TIDAL through a controlled device's login, since this computer has none.
    pub fn borrowed(&self) -> bool {
        !self.is_authenticated() && self.proxy().is_some()
    }

    /// TIDAL pages can load: with this computer's login or a borrowed one.
    pub fn can_browse(&self) -> bool {
        self.is_authenticated() || self.proxy().is_some()
    }

    pub fn reload_auth(&self) {
        let fresh = Self::load_auth(&self.config_path);
        if let Ok(mut a) = self.auth.write() {
            *a = fresh;
        }
    }

    /// Seconds until the token expires (negative = expired).
    pub fn token_remaining(&self) -> Option<i64> {
        self.auth().expires_at.map(|e| e - chrono::Utc::now().timestamp())
    }

    /// Run `tiddl auth refresh` (it rewrites auth.json) and reload. Serialised across threads.
    pub fn refresh_token(&self) -> Result<(), String> {
        let _g = self.refresh_lock.lock().map_err(|_| "refresh lock poisoned")?;
        // Another thread may have refreshed while we waited.
        let before = self.auth().token;
        self.reload_auth();
        if self.auth().token != before && self.token_remaining().unwrap_or(0) > 300 {
            return Ok(());
        }
        let out = std::process::Command::new(tiddl_bin())
            .args(["auth", "refresh"])
            .env("TIDDL_PATH", &self.config_path)
            .output()
            .map_err(|e| format!("无法运行 tiddl: {}", e))?;
        self.reload_auth();
        if out.status.success() {
            Ok(())
        } else {
            Err(format!("Token 刷新失败: {}", String::from_utf8_lossy(&out.stderr).trim()))
        }
    }

    /// Hi-Res capability token, cached until shortly before it expires.
    pub(crate) fn cached_hires_session(&self, user: &str) -> Option<(String, i64)> {
        let cache = self.hires.try_lock().ok()?;
        let (owner, token, expiry) = cache.as_ref()?;
        (owner == user && *expiry > chrono::Utc::now().timestamp())
            .then(|| (token.clone(), *expiry))
    }

    pub(crate) fn hires_token(&self, force: bool) -> Result<String, String> {
        let user = self.user_id()?;
        let mut cache = self.hires.lock().map_err(|_| "token cache poisoned")?;
        let now = chrono::Utc::now().timestamp();
        if !force {
            if let Some((owner, t, exp)) = cache.as_ref() {
                if owner == &user && *exp - now > 120 {
                    return Ok(t.clone());
                }
            }
        }
        let _refresh = self.refresh_lock.lock().map_err(|_| "refresh lock poisoned")?;
        let out = std::process::Command::new(python_bin())
            .args(["-c", TOKEN_HELPER])
            .arg("hires")
            .env("TIDDL_PATH", &self.config_path)
            .output()
            .map_err(|e| format!("无法运行 Hi-Res token 助手: {}", e))?;
        if !out.status.success() {
            return Err(format!("获取 Hi-Res token 失败: {}", String::from_utf8_lossy(&out.stderr).lines().last().unwrap_or("")));
        }
        let v: serde_json::Value = serde_json::from_slice(&out.stdout).map_err(|e| format!("Hi-Res token 解析失败: {}", e))?;
        let tok = v["access_token"].as_str().ok_or("Hi-Res token 缺失")?.to_string();
        if self.user_id()? != user || Self::load_auth(&self.config_path).user_id.as_deref() != Some(user.as_str()) {
            return Err("TIDAL 登录已切换".into());
        }
        *cache = Some((user, tok.clone(), v["expires_at"].as_i64().unwrap_or(now + 3600)));
        Ok(tok)
    }

    fn user_id(&self) -> Result<String, String> {
        if self.borrowed() {
            // The lending device fills in its own user.
            return Ok("{user}".into());
        }
        self.auth().user_id.ok_or_else(|| "未登录 TIDAL".to_string())
    }

    /// Request `https://api.tidal.com/v1/{path}` (countryCode appended). Retries once after a
    /// token refresh on 401.
    fn request(&self, method: Method, path: &str, form: Option<&[(&str, String)]>) -> Result<String, String> {
        self.request_as(TokenKind::Main, method, path, form)
    }

    fn request_as(&self, kind: TokenKind, method: Method, path: &str, form: Option<&[(&str, String)]>) -> Result<String, String> {
        if !self.is_authenticated() {
            if let Some(proxy) = self.proxy() {
                let form = form.map(|pairs| {
                    pairs.iter().map(|(key, value)| format!("{}={}", urlencoding::encode(key), urlencoding::encode(value)))
                        .collect::<Vec<_>>().join("&")
                });
                return proxy(method.as_str(), path, form);
            }
        }
        for attempt in 0..2 {
            let auth = self.auth();
            let token = match kind {
                TokenKind::Main => auth.token.ok_or("未登录 TIDAL")?,
                TokenKind::HiRes => self.hires_token(attempt > 0)?,
            };
            let country = auth.country_code.unwrap_or_else(|| "US".into());
            let sep = if path.contains('?') { '&' } else { '?' };
            let url = format!("https://api.tidal.com/v1/{}{}countryCode={}", path, sep, country);
            let mut rb = self
                .client
                .request(method.clone(), &url)
                .header("Authorization", format!("Bearer {}", token))
                .header("Accept", "application/json");
            if let Some(f) = form {
                rb = rb.form(f);
            }
            let res = rb.send().map_err(|e| format!("网络错误: {}", e))?;
            let status = res.status();
            if status.as_u16() == 401 && attempt == 0 {
                if kind == TokenKind::Main {
                    self.refresh_token()?;
                } // HiRes: the next attempt forces a fresh capability token
                continue;
            }
            if !status.is_success() {
                return Err(match status.as_u16() {
                    401 => "TIDAL 401：登录已失效，请在设置中重新授权".to_string(),
                    403 => "TIDAL 403：没有权限访问该资源".to_string(),
                    404 => "TIDAL 404：资源不存在或当前地区不可用".to_string(),
                    _ => format!("TIDAL API HTTP {}", status),
                });
            }
            return res.text().map_err(|e| format!("读取响应失败: {}", e));
        }
        Err("TIDAL 请求失败".into())
    }

    /// Answer a TIDAL request made by another device of the player account that has no TIDAL
    /// login: catalogue and library reads, and favourite changes. `{user}` in the path stands for
    /// this login's user. Stream and download URLs are never handed out.
    pub fn forward(&self, method: &str, path: &str, form: Option<&str>) -> Result<String, String> {
        forwardable(method, path, form)?;
        let path = path.replace("{user}", &self.user_id()?);
        let pairs: Vec<(String, String)> = match (method, form) {
            ("POST", Some(form)) => reqwest::Url::parse(&format!("http://form/?{form}"))
                .map_err(|_| "请求内容无效")?
                .query_pairs()
                .map(|(key, value)| (key.into_owned(), value.into_owned()))
                .collect(),
            _ => Vec::new(),
        };
        let form: Vec<(&str, String)> = pairs.iter().map(|(key, value)| (key.as_str(), value.clone())).collect();
        let method = match method {
            "POST" => Method::POST,
            "DELETE" => Method::DELETE,
            _ => Method::GET,
        };
        let post = method == Method::POST;
        self.request(method, &path, post.then_some(form.as_slice()))
    }

    fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T, String> {
        let body = self.request(Method::GET, path, None)?;
        serde_json::from_str(&body).map_err(|e| format!("解析响应失败: {}", e))
    }

    /// Fetch every page of a list endpoint (`limit`/`offset` paging).
    fn get_all<T: DeserializeOwned>(&self, path: &str, page: usize, max: usize) -> Result<Vec<T>, String> {
        let mut out = Vec::new();
        let sep = if path.contains('?') { '&' } else { '?' };
        loop {
            let list: ItemList<T> = self.get(&format!("{}{}limit={}&offset={}", path, sep, page, out.len()))?;
            let n = list.items.len();
            out.extend(list.items);
            let total = list.total_number_of_items.unwrap_or(0) as usize;
            if n == 0 || out.len() >= total || out.len() >= max {
                break;
            }
        }
        Ok(out)
    }

    // ── Catalogue ──

    pub fn netease(&self) -> crate::netease::Netease<'_> {
        crate::netease::Netease(&self.client)
    }

    pub fn search(&self, query: &str, limit: u32) -> Result<SearchResponse, String> {
        self.get(&format!(
            "search?query={}&limit={}&types=ARTISTS,ALBUMS,TRACKS,PLAYLISTS",
            urlencoding::encode(query),
            limit
        ))
    }

    pub fn get_track(&self, track_id: u64) -> Result<TrackItem, String> {
        if let Some(id) = crate::netease::native_id(track_id) {
            return self.netease().track(id);
        }
        self.get(&format!("tracks/{}", track_id))
    }

    pub fn get_artist(&self, artist_id: u64) -> Result<ArtistItem, String> {
        let mut v: serde_json::Value = self.get(&format!("artists/{}", artist_id))?;
        // Some artists have no photo; TIDAL then supplies an album cover fallback.
        if v.get("picture").map_or(true, |p| p.is_null()) {
            if let Some(fb) = v.get("selectedAlbumCoverFallback").cloned() {
                v["picture"] = fb;
            }
        }
        serde_json::from_value(v).map_err(|e| format!("解析响应失败: {}", e))
    }

    fn artist_releases(&self, artist_id: u64, filter: Option<&str>) -> Result<Vec<AlbumItem>, String> {
        let f = filter.map(|f| format!("?filter={}", f)).unwrap_or_default();
        let mut items: Vec<AlbumItem> = self.get_all(&format!("artists/{}/albums{}", artist_id, f), 100, 300)?;
        items.sort_by(|a, b| b.release_date.cmp(&a.release_date));
        Ok(items)
    }

    pub fn get_artist_page(&self, artist_id: u64) -> Result<ArtistPage, String> {
        if let Some(id) = crate::netease::native_id(artist_id) {
            return self.netease().artist(id);
        }
        let artist = self.get_artist(artist_id)?;
        // derive(Default) on ItemList would require TrackItem: Default, so build the fallback by hand.
        let top: ItemList<TrackItem> = self
            .get(&format!("artists/{}/toptracks?limit=10", artist_id))
            .unwrap_or_else(|_| ItemList { items: Vec::new(), total_number_of_items: None });
        let albums = self.artist_releases(artist_id, None)?;
        let singles = self.artist_releases(artist_id, Some("EPSANDSINGLES")).unwrap_or_default();
        Ok(ArtistPage { artist, top_tracks: top.items, albums, singles })
    }

    pub fn get_album_page(&self, album_id: u64) -> Result<AlbumPage, String> {
        if let Some(id) = crate::netease::native_id(album_id) {
            return self.netease().album(id);
        }
        let album: AlbumItem = self.get(&format!("albums/{}", album_id))?;
        let mut tracks: Vec<TrackItem> = self.get_all(&format!("albums/{}/tracks", album_id), 100, 1000)?;
        // Track payloads carry a reduced album object; fill what the UI needs.
        for t in &mut tracks {
            match &mut t.album {
                Some(a) => {
                    if a.cover.is_none() {
                        a.cover = album.cover.clone();
                    }
                }
                None => t.album = Some(album.clone()),
            }
        }
        Ok(AlbumPage { album, tracks })
    }

    pub fn get_playlist_page(&self, uuid: &str) -> Result<PlaylistPage, String> {
        if let Some(id) = crate::netease::playlist_id(uuid) {
            return self.netease().playlist(id);
        }
        let playlist: PlaylistItem = self.get(&format!("playlists/{}", uuid))?;
        let tracks = self.get_all(&format!("playlists/{}/tracks", uuid), 100, 2000)?;
        Ok(PlaylistPage { playlist, tracks })
    }

    // ── Collection ──

    fn favorites<T: DeserializeOwned>(&self, kind: FavKind) -> Result<Vec<Fav<T>>, String> {
        let uid = self.user_id()?;
        self.get_all(
            &format!("users/{}/favorites/{}?order=DATE&orderDirection=DESC", uid, kind.path()),
            100,
            5000,
        )
    }

    pub fn get_library(&self) -> Result<LibraryData, String> {
        let uid = self.user_id()?;
        Ok(LibraryData {
            albums: self.favorites(FavKind::Album)?,
            artists: self.favorites(FavKind::Artist)?,
            tracks: self.favorites(FavKind::Track)?,
            playlists: self.favorites(FavKind::Playlist)?,
            my_playlists: self.get_all(&format!("users/{}/playlists", uid), 100, 1000)?,
        })
    }

    pub fn set_favorite(&self, kind: FavKind, id: &str, add: bool) -> Result<(), String> {
        let uid = self.user_id()?;
        if add {
            let form = [(kind.form_key(), id.to_string()), ("onArtifactNotFound", "FAIL".to_string())];
            self.request(Method::POST, &format!("users/{}/favorites/{}", uid, kind.path()), Some(&form))?;
        } else {
            self.request(Method::DELETE, &format!("users/{}/favorites/{}/{}", uid, kind.path(), id), None)?;
        }
        Ok(())
    }

    // ── Playback ──

    /// Resolve a playable stream for preview.
    ///
    /// All qualities are requested with the Hi-Res capability token: that client profile gets
    /// stereo streams (the normal login token belongs to the Atmos profile and would return
    /// Dolby Atmos E-AC-3 for tracks that have it). Responses are MPEG-DASH manifests (written
    /// to a temp file for mpv) or single-URL BTS manifests. If the capability token can't be
    /// obtained we fall back to the normal token (and LOSSLESS for Hi-Res) and say why in `note`.
    /// `netease_level` is the 网易云 level (see `netease::PLAY_LEVELS`) for NetEase tracks.
    pub fn get_stream(&self, track_id: u64, q: StreamQuality, netease_level: &str) -> Result<StreamInfo, String> {
        if let Some(id) = crate::netease::native_id(track_id) {
            return self.netease().stream_level(id, netease_level, q).map(|(info, _)| info);
        }
        self.get_stream_as(TokenKind::HiRes, track_id, q)
    }

    fn get_stream_as(&self, kind: TokenKind, track_id: u64, q: StreamQuality) -> Result<StreamInfo, String> {
        use base64::Engine;
        let path = format!(
            "tracks/{}/playbackinfopostpaywall?audioquality={}&playbackmode=STREAM&assetpresentation=FULL",
            track_id,
            q.api_name()
        );
        let body = match self.request_as(kind, Method::GET, &path, None) {
            Ok(b) => b,
            Err(e) if kind == TokenKind::HiRes => {
                let fallback = if q == StreamQuality::HiRes { StreamQuality::Lossless } else { q };
                let mut info = self.get_stream_as(TokenKind::Main, track_id, fallback)?;
                info.requested = q;
                info.note = Some(format!("立体声音频流不可用，已改用普通会话（{}）", e));
                return Ok(info);
            }
            Err(e) => return Err(e),
        };
        let v: serde_json::Value = serde_json::from_str(&body).map_err(|e| format!("解析响应失败: {}", e))?;
        let raw = base64::engine::general_purpose::STANDARD
            .decode(v["manifest"].as_str().unwrap_or(""))
            .map_err(|e| format!("清单解码失败: {}", e))?;

        let url = match v["manifestMimeType"].as_str().unwrap_or("") {
            "application/vnd.tidal.bts" => {
                let m: serde_json::Value = serde_json::from_slice(&raw).map_err(|e| format!("清单解析失败: {}", e))?;
                if m["encryptionType"].as_str().unwrap_or("NONE") != "NONE" {
                    return Err("该曲目音频流已加密，无法试听".into());
                }
                m["urls"][0].as_str().ok_or("清单中没有音频地址")?.to_string()
            }
            "application/dash+xml" => {
                if String::from_utf8_lossy(&raw).contains("ContentProtection") {
                    return Err("该曲目音频流已加密，无法试听".into());
                }
                let p = manifest_path(track_id);
                fs::write(&p, &raw).map_err(|e| format!("写入播放清单失败: {}", e))?;
                p.to_string_lossy().to_string()
            }
            other => return Err(format!("暂不支持的流格式：{}", other)),
        };

        let quality = v["audioQuality"].as_str().unwrap_or("").to_string();
        let note = if v["audioMode"].as_str() == Some("DOLBY_ATMOS") {
            Some("TIDAL 返回的是 Dolby Atmos 音频流".to_string())
        } else {
            (quality != q.api_name()).then(|| match q {
                StreamQuality::HiRes => "该曲目没有 Max 版本，正在播放 High".to_string(),
                _ => format!("TIDAL 提供的音质为 {}", crate::types::tidal_quality_name(&quality).unwrap_or(&quality)),
            })
        };
        Ok(StreamInfo {
            url,
            quality,
            bit_depth: v["bitDepth"].as_u64().map(|x| x as u32),
            sample_rate: v["sampleRate"].as_u64().map(|x| x as u32),
            requested: q,
            note,
        })
    }
}

/// Whether another device may send this TIDAL request through this login (see `TidalApi::forward`).
pub fn forwardable(method: &str, path: &str, form: Option<&str>) -> Result<(), String> {
    const ROOTS: [&str; 8] = ["search", "tracks", "albums", "artists", "playlists", "mixes", "pages", "users/{user}"];
    let refused = || Err("不支持的 TIDAL 请求".to_string());
    let lower = path.to_ascii_lowercase();
    let (base, query) = path.split_once('?').unwrap_or((path, ""));
    let rest = ROOTS.iter().find_map(|root| {
        if base == *root { Some("") } else { base.strip_prefix(root).and_then(|rest| rest.strip_prefix('/')) }
    });
    let Some(rest) = rest else { return refused() };
    if path.len() > 800
        || path.contains("..")
        || !rest.chars().all(|c| c.is_ascii_alphanumeric() || "_./-".contains(c))
        || query.chars().any(|c| c.is_whitespace() || c == '#')
        || ["playbackinfo", "streamurl", "urlpostpaywall", "countrycode="].iter().any(|word| lower.contains(word))
    {
        return refused();
    }
    let favourite = path.starts_with("users/{user}/favorites/");
    if method != "GET" && !(favourite && (method == "POST" || method == "DELETE")) {
        return refused();
    }
    if form.is_some_and(|form| form.len() > 400) {
        return Err("请求内容过长".into());
    }
    Ok(())
}

#[cfg(test)]
mod forward_tests {
    use super::forwardable;

    #[test]
    fn only_catalogue_reads_and_favourites_are_forwarded() {
        assert!(forwardable("GET", "search?types=TRACKS&limit=50&offset=0&query=a%20b", None).is_ok());
        assert!(forwardable("GET", "albums/123/tracks?limit=100&offset=0", None).is_ok());
        assert!(forwardable("GET", "users/{user}/favorites/albums?order=DATE", None).is_ok());
        assert!(forwardable("POST", "users/{user}/favorites/tracks", Some("trackIds=1&onArtifactNotFound=FAIL")).is_ok());
        assert!(forwardable("DELETE", "users/{user}/favorites/tracks/1", None).is_ok());
        assert!(forwardable("GET", "tracks/1/playbackinfopostpaywall?audioquality=LOSSLESS", None).is_err());
        assert!(forwardable("GET", "users/42/favorites/tracks", None).is_err());
        assert!(forwardable("GET", "sessions", None).is_err());
        assert!(forwardable("POST", "playlists/abc/items", None).is_err());
        assert!(forwardable("GET", "albums/../sessions", None).is_err());
        assert!(forwardable("GET", "albums/1?countryCode=JP", None).is_err());
    }
}
