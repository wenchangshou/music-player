//! Application core: state, background work, navigation and the action dispatcher.
//! Rendering lives in `crate::ui`; pages only *read* `App` and emit `Action`s.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

use eframe::egui;
use serde::{Deserialize, Serialize};

use crate::api::TidalApi;
use crate::download::{normalize_url, DownloadJob, JobEvent, JobStatus};
use crate::netease::SearchSource;
use crate::library::{FavTarget, LibState, Library};
use crate::playback::{PlayContext, Playback};
use crate::qa::run_shanling_qa;
use crate::route::{History, Route};
use crate::types::{
    AlbumItem, AlbumPage, AlbumQaReport, ArtistPage, LibraryData, PlaylistPage, SearchResponse, StreamInfo,
    StreamQuality, TrackItem,
};
use crate::ui;

// ───────────────────────────── async plumbing ─────────────────────────────

pub enum Load<T> {
    Loading,
    Ready(T),
    Failed(String),
}

#[derive(Default)]
pub struct Cache {
    pub albums: HashMap<u64, Load<AlbumPage>>,
    pub artists: HashMap<u64, Load<ArtistPage>>,
    pub playlists: HashMap<String, Load<PlaylistPage>>,
    pub searches: HashMap<(SearchSource, String), Load<SearchResponse>>,
}

impl Cache {
    /// Forget a failed/finished entry so it will be fetched again.
    fn forget(&mut self, r: &Route) {
        match r {
            Route::Album(id) => {
                self.albums.remove(id);
            }
            Route::Artist(id) => {
                self.artists.remove(id);
            }
            Route::Playlist(u) => {
                self.playlists.remove(u);
            }
            Route::Search(source, q) => {
                self.searches.remove(&(*source, q.clone()));
            }
            _ => {}
        }
    }

    fn failed(&self, r: &Route) -> bool {
        match r {
            Route::Album(id) => matches!(self.albums.get(id), Some(Load::Failed(_))),
            Route::Artist(id) => matches!(self.artists.get(id), Some(Load::Failed(_))),
            Route::Playlist(u) => matches!(self.playlists.get(u), Some(Load::Failed(_))),
            Route::Search(source, q) => matches!(self.searches.get(&(*source, q.clone())), Some(Load::Failed(_))),
            _ => false,
        }
    }
}

pub enum Ev {
    Album(u64, Result<AlbumPage, String>),
    Artist(u64, Result<ArtistPage, String>),
    Playlist(String, Result<PlaylistPage, String>),
    Search((SearchSource, String), Result<SearchResponse, String>),
    Library(Result<LibraryData, String>),
    Fav(FavTarget, bool, Result<(), String>),
    Stream(u64, Result<StreamInfo, String>),
    Job(u64, JobEvent),
    Qa(Result<AlbumQaReport, String>),
    Auth(Result<(), String>),
    PlayerAccount(Result<Option<crate::relay::PlayerAccount>, String>),
    RemoteDevices(Result<Vec<crate::remote::DeviceInfo>, String>),
    RemoteState(u64, Result<crate::remote::RemoteState, String>),
    RemoteCommand(u64, Result<Option<crate::remote::RemoteState>, String>),
    RemoteRemoved(String, Result<(), String>),
    RemoteLoggedOutOthers(Result<(), String>),
    /// A page of the controlled device's library: (generation, request, append, reply).
    RemoteLibrary(u64, u64, bool, Result<serde_json::Value, String>),
    /// The controlled device's download list (generation, reply).
    RemoteDownloads(u64, Result<serde_json::Value, String>),
    RemoteMarks(u64, Result<serde_json::Value, String>),
    /// Tracks fetched to continue a remote device's playback here: (tracks, index, seconds).
    TransferHere(Result<(Vec<TrackItem>, usize, f64), String>),
}

// ───────────────────────────── UI intents ─────────────────────────────

pub enum Action {
    Go(Route),
    Back,
    Forward,
    Refresh,
    Search(String),

    PlayTracks { tracks: Vec<TrackItem>, index: usize, shuffle: bool, context: Option<PlayContext> },
    PlayAlbum { id: u64, shuffle: bool },
    PlayPlaylist { uuid: String, shuffle: bool },
    PlayNext(Vec<TrackItem>),
    Enqueue(Vec<TrackItem>),
    TogglePause,
    Next,
    Prev,
    Seek(f64),
    SetVolume(f32),
    ToggleShuffle,
    CycleRepeat,
    JumpQueue(usize),
    RemoveQueue(usize),
    ClearQueue,
    StopPlayback,
    SetStreamQuality(StreamQuality),
    /// 网易云 level for NetEase tracks (one of `netease::PLAY_LEVELS`).
    SetNeteaseLevel(&'static str),

    ToggleFav(FavTarget),
    ReloadLibrary,

    Download { url: String, quality: &'static str, title: Option<String> },
    EnqueueInput,
    /// Manage a download on the controlled device: ("cancel" | "retry" | "remove" | "clear", job id).
    RemoteDownload(&'static str, String),
    CancelJob(u64),
    RemoveJob(u64),
    RetryJob(u64),
    ClearFinishedJobs,

    RunQa,
    ReloadAuth,
    PlayerLogin(String, String),
    PlayerLogout,
    RefreshToken,
    /// Control another device of this player account (`None` = this computer).
    SelectDevice(Option<String>),
    RefreshDevices,
    RemoveDevice(String),
    /// Sign out every other device and browser of this player account.
    LogoutOtherDevices,
    /// Browse the controlled Android device's library: search text, playlist ("" = all songs).
    RemoteLibraryFilter(String, String),
    RemoteLibraryMore,
    /// Play songs of the library page from this index on.
    RemoteLibraryPlay(usize),
    /// Queue one song of the device's library: mode "next" or "add".
    RemoteLibrarySong(i64, &'static str),
    RemoteLibraryPlayAll(bool),
    /// Move this computer's queue and position to a device and pause here.
    TransferTo(String),
    /// Continue the controlled device's queue on this computer and pause it there.
    TransferHere,
    Copy(String),
    OpenUrl(String),
}

#[derive(Clone, Copy, PartialEq, Default)]
pub enum SearchFilter {
    #[default]
    All,
    Tracks,
    Artists,
    Albums,
    Playlists,
}

#[derive(Clone, Copy, PartialEq, Default)]
pub enum LibSort {
    #[default]
    Recent,
    Title,
    Artist,
    Release,
}

/// Mutable, render-time state (text boxes, toggles). Taken out of `App` during rendering so
/// pages can borrow `&App` and `&mut UiState` at the same time.
#[derive(Default)]
pub struct UiState {
    pub search_text: String,
    pub search_source: SearchSource,
    pub focus_search: bool,
    pub search_filter: SearchFilter,
    pub lib_filter: String,
    pub lib_sort: LibSort,
    pub show_queue: bool,
    pub seek_drag: Option<f64>,
    pub volume: f32,
    pub dl_input: String,
    pub dl_quality: String,
    pub dl_atmos: String,
    pub dl_dest: String,
    pub qa_folder: String,
    pub account_username: String,
    pub account_password: String,
    pub job_tracks_open: HashSet<u64>,
    pub job_log_open: HashSet<u64>,
    pub device_remove_confirm: Option<String>,
    pub remote_volume_drag: Option<f32>,
    pub devices_logout_confirm: bool,
    pub remote_library_input: String,
}

/// Persisted between launches (window geometry is persisted by eframe).
#[derive(Serialize, Deserialize)]
#[serde(default)]
struct Persist {
    volume: f32,
    dl_atmos: String,
    dl_dest: String,
    qa_folder: String,
    show_queue: bool,
    recent_albums: Vec<AlbumItem>,
    recent_searches: Vec<String>,
    stream_quality: StreamQuality,
    netease_level: String,
    lend_tidal: bool,
}

impl Default for Persist {
    fn default() -> Self {
        Self {
            volume: 80.0,
            dl_atmos: "none".into(),
            dl_dest: std::env::var("TIDAL_DOWNLOAD_DIR").unwrap_or_else(|_| "outputs".into()),
            qa_folder: std::env::var("TIDAL_DOWNLOAD_DIR").unwrap_or_else(|_| "outputs".into()),
            show_queue: false,
            recent_albums: Vec::new(),
            recent_searches: Vec::new(),
            stream_quality: StreamQuality::HiRes,
            netease_level: "lossless".into(),
            lend_tidal: false,
        }
    }
}

pub struct App {
    pub api: TidalApi,
    pub ctx: egui::Context,
    tx: Sender<Ev>,
    rx: Receiver<Ev>,
    seq: u64,

    pub route: Route,
    pub history: History,
    pub cache: Cache,
    pub lib: Library,
    pub pb: Playback,
    pub jobs: Vec<DownloadJob>,
    next_job: u64,
    pub recent_albums: Vec<AlbumItem>,
    pub recent_searches: Vec<String>,
    pub qa_report: Option<AlbumQaReport>,
    pub qa_running: bool,
    pub auth_busy: bool,
    pub toast: Option<(String, Instant)>,
    pending_play: Option<(Route, bool)>,
    relay: Option<crate::relay::Relay>,
    pub player_account: Option<crate::relay::PlayerAccount>,
    pub player_account_busy: bool,
    pub remote: Option<crate::remote::Remote>,
    /// The controlled device whose TIDAL login this computer browses with, if any.
    borrowed_tidal: Option<String>,
    /// This computer's finished downloads, for "已下载" marks; refreshed now and then.
    local_marks: crate::marks::Marks,
    local_marks_at: Option<Instant>,
    pub ui: UiState,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let (tx, rx) = channel();
        let p: Persist = cc.storage.and_then(|s| eframe::get_value(s, eframe::APP_KEY)).unwrap_or_default();

        let mut pb = Playback::new();
        pb.player.volume = p.volume;
        pb.quality = p.stream_quality;
        pb.netease_level = crate::netease::play_level(&p.netease_level);
        crate::relay::LEND_TIDAL.store(p.lend_tidal, std::sync::atomic::Ordering::Relaxed);

        let mut app = Self {
            api: TidalApi::new(),
            ctx: cc.egui_ctx.clone(),
            tx,
            rx,
            seq: 0,
            route: Route::Home,
            history: History::default(),
            cache: Cache::default(),
            lib: Library::default(),
            pb,
            jobs: Vec::new(),
            next_job: 1,
            recent_albums: p.recent_albums,
            recent_searches: p.recent_searches,
            qa_report: None,
            qa_running: false,
            auth_busy: false,
            toast: None,
            pending_play: None,
            relay: None,
            player_account: crate::relay::PlayerAccount::load(),
            player_account_busy: false,
            remote: None,
            borrowed_tidal: None,
            local_marks: Default::default(),
            local_marks_at: None,
            ui: UiState {
                volume: p.volume,
                show_queue: p.show_queue,
                dl_quality: "max".into(), // always default to the best quality
                dl_atmos: p.dl_atmos,
                dl_dest: p.dl_dest,
                qa_folder: p.qa_folder,
                ..Default::default()
            },
        };
        let initial = crate::relay::snapshot(&app.pb, app.api.is_authenticated());
        app.relay = if let Some(account) = &app.player_account {
            crate::relay::Relay::account(app.api.clone(), app.ctx.clone(), initial, account)
        } else {
            crate::relay::Relay::configured(app.api.clone(), app.ctx.clone(), initial)
        };
        app.remote = app.player_account.as_ref().and_then(crate::relay::PlayerAccount::remote);
        app.load_library();

        // `tidal-gui <tidal link>` opens that page directly.
        if let Some(arg) = std::env::args().nth(1) {
            if let Some((kind, id)) = crate::download::url_kind(&arg) {
                let r = match (kind, id.parse::<u64>()) {
                    ("album", Ok(n)) => Some(Route::Album(n)),
                    ("artist", Ok(n)) => Some(Route::Artist(n)),
                    ("playlist", _) => Some(Route::Playlist(id)),
                    _ => None,
                };
                if let Some(r) = r {
                    app.go(r);
                }
            }
        }
        app
    }

    // ── background work ──

    fn spawn<F>(&self, f: F)
    where
        F: FnOnce(TidalApi) -> Ev + Send + 'static,
    {
        let (api, tx, ctx) = (self.api.clone(), self.tx.clone(), self.ctx.clone());
        thread::spawn(move || {
            let _ = tx.send(f(api));
            ctx.request_repaint();
        });
    }

    fn next_seq(&mut self) -> u64 {
        self.seq += 1;
        self.seq
    }

    pub fn notify(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), Instant::now()));
        self.ctx.request_repaint_after(Duration::from_millis(3600));
    }

    fn load_library(&mut self) {
        if !self.api.can_browse() || self.lib.state == LibState::Loading {
            return;
        }
        self.lib.state = LibState::Loading;
        self.spawn(|api| {
            if api.token_remaining().map_or(false, |r| r < 600) {
                let _ = api.refresh_token();
            }
            Ev::Library(api.get_library())
        });
    }

    /// Fetch whatever the route needs if it isn't cached yet.
    fn ensure_loaded(&mut self, route: &Route) {
        match route {
            Route::Album(id) if !self.cache.albums.contains_key(id) => {
                let id = *id;
                self.cache.albums.insert(id, Load::Loading);
                self.spawn(move |api| Ev::Album(id, api.get_album_page(id)));
            }
            Route::Artist(id) if !self.cache.artists.contains_key(id) => {
                let id = *id;
                self.cache.artists.insert(id, Load::Loading);
                self.spawn(move |api| Ev::Artist(id, api.get_artist_page(id)));
            }
            Route::Playlist(uuid) if !self.cache.playlists.contains_key(uuid) => {
                let uuid = uuid.clone();
                self.cache.playlists.insert(uuid.clone(), Load::Loading);
                self.spawn(move |api| {
                    let r = api.get_playlist_page(&uuid);
                    Ev::Playlist(uuid, r)
                });
            }
            Route::RemoteLibrary(tab) => self.load_remote_library(*tab, false),
            Route::Search(source, q) => {
                let key = (*source, q.clone());
                if !q.is_empty() && !self.cache.searches.contains_key(&key) {
                    self.cache.searches.insert(key.clone(), Load::Loading);
                    self.spawn(move |api| {
                        let result = match key.0 {
                            SearchSource::Tidal => api.search(&key.1, 50),
                            SearchSource::Netease => api.netease().search(&key.1, 50),
                        };
                        Ev::Search(key, result)
                    });
                }
            }
            _ => {}
        }
    }

    fn sync_search_inputs(&mut self, route: &Route) {
        if let Route::Search(source, query) = route {
            self.ui.search_source = *source;
            self.ui.search_text = query.clone();
            self.ui.search_filter = SearchFilter::All;
        }
    }

    pub fn go(&mut self, route: Route) {
        if route == self.route {
            return;
        }
        let old = std::mem::replace(&mut self.route, route.clone());
        self.history.push(old);
        self.sync_search_inputs(&route);
        if self.cache.failed(&route) {
            self.cache.forget(&route);
        }
        self.ensure_loaded(&route);
    }

    // ── playback ──

    fn play_index(&mut self, i: usize) {
        self.play_index_at(i, 0.0);
    }

    fn play_index_at(&mut self, i: usize, start: f64) {
        let Some(track) = self.pb.queue.get(i) else { return };
        let id = track.id;
        self.pb.index = i;
        self.pb.player.stop();
        self.pb.stream = None;
        self.pb.pending_start = start;
        if start == 0.0 {
            self.remember_album(i);
        }
        let seq = self.next_seq();
        self.pb.req = seq;
        let q = self.pb.quality;
        let level = self.pb.netease_level;
        self.spawn(move |api| Ev::Stream(seq, api.get_stream(id, q, level)));
    }

    fn remember_album(&mut self, i: usize) {
        let Some(t) = self.pb.queue.get(i) else { return };
        let Some(mut a) = t.album.clone() else { return };
        if a.artist.is_none() && a.artists.is_none() {
            a.artist = t.main_artist().cloned();
        }
        self.recent_albums.retain(|x| x.id != a.id);
        self.recent_albums.insert(0, a);
        self.recent_albums.truncate(24);
    }

    fn start_queue(&mut self, tracks: Vec<TrackItem>, index: usize, shuffle: bool, context: Option<PlayContext>) {
        if tracks.is_empty() {
            self.notify("没有可播放的曲目");
            return;
        }
        if self.remote_target().is_some() {
            self.remote_tracks(tracks, index, shuffle, "play", 0.0);
            return;
        }
        self.start_queue_local(tracks, index, shuffle, context, 0.0);
    }

    /// Replace this computer's queue, even while another device is being controlled.
    fn start_queue_local(&mut self, tracks: Vec<TrackItem>, index: usize, shuffle: bool, context: Option<PlayContext>, start: f64) {
        if tracks.is_empty() {
            return;
        }
        self.pb.set_queue(tracks, index, shuffle);
        self.pb.context = context;
        self.play_index_at(self.pb.index, start);
    }

    /// Play an album/playlist; if not cached yet, load it and play when it arrives.
    fn play_collection(&mut self, route: Route, shuffle: bool) {
        let ready = match &route {
            Route::Album(id) => match self.cache.albums.get(id) {
                Some(Load::Ready(p)) => Some((p.tracks.clone(), format!("专辑 · {}", p.album.title))),
                _ => None,
            },
            Route::Playlist(u) => match self.cache.playlists.get(u) {
                Some(Load::Ready(p)) => Some((p.tracks.clone(), format!("歌单 · {}", p.playlist.title))),
                _ => None,
            },
            _ => None,
        };
        match ready {
            Some((tracks, label)) => self.start_queue(tracks, 0, shuffle, Some(PlayContext { label, route })),
            None => {
                if self.cache.failed(&route) {
                    self.cache.forget(&route);
                }
                self.ensure_loaded(&route);
                self.pending_play = Some((route, shuffle));
                self.notify("正在加载…");
            }
        }
    }

    fn tick_playback(&mut self) {
        let st = self.pb.player.snapshot();
        if st.finished {
            self.pb.player.stop();
            match self.pb.next_index(true) {
                Some(i) => self.play_index(i),
                None => self.notify("队列播放完毕"),
            }
        } else if let Some(e) = st.error.filter(|_| !st.active) {
            self.pb.player.stop();
            self.notify(format!("播放错误：{}", e));
        }
    }

    // ── downloads ──

    fn enqueue_download(&mut self, url: String, quality: &str, title: Option<String>) -> bool {
        let url = normalize_url(&url);
        if url.is_empty() || self.jobs.iter().any(|j| j.url == url && !j.status.is_finished()) {
            return false;
        }
        let mut job = DownloadJob::new(
            self.next_job,
            url,
            quality.to_string(),
            self.ui.dl_atmos.clone(),
            self.ui.dl_dest.clone(),
        );
        if let Some(t) = title {
            job.meta.title = t;
        }
        self.next_job += 1;
        self.jobs.push(job);
        true
    }

    fn schedule_jobs(&mut self) {
        if self.jobs.iter().any(|j| j.status.is_active()) {
            return;
        }
        let (api, tx, ctx) = (self.api.clone(), self.tx.clone(), self.ctx.clone());
        if let Some(job) = self.jobs.iter_mut().find(|j| j.status == JobStatus::Queued) {
            job.start(api, tx, Ev::Job, ctx);
        }
    }

    pub fn active_downloads(&self) -> usize {
        self.jobs.iter().filter(|j| !j.status.is_finished()).count()
    }

    // ── events ──

    fn handle_events(&mut self) {
        while let Ok(ev) = self.rx.try_recv() {
            match ev {
                Ev::Album(id, r) => {
                    let ok = r.is_ok();
                    self.cache.albums.insert(id, r.map_or_else(Load::Failed, Load::Ready));
                    self.resume_pending(&Route::Album(id), ok);
                }
                Ev::Artist(id, r) => {
                    self.cache.artists.insert(id, r.map_or_else(Load::Failed, Load::Ready));
                }
                Ev::Playlist(uuid, r) => {
                    let ok = r.is_ok();
                    self.cache.playlists.insert(uuid.clone(), r.map_or_else(Load::Failed, Load::Ready));
                    self.resume_pending(&Route::Playlist(uuid), ok);
                }
                Ev::Search(q, r) => {
                    self.cache.searches.insert(q, r.map_or_else(Load::Failed, Load::Ready));
                }
                Ev::Library(r) => match r {
                    Ok(d) => self.lib.set_data(d),
                    Err(e) => self.lib.state = LibState::Failed(e),
                },
                Ev::Fav(t, add, r) => {
                    if let Err(e) = r {
                        self.lib.apply(&t, !add); // roll back the optimistic update
                        self.notify(format!("收藏同步失败：{}", e));
                    }
                }
                Ev::Stream(seq, r) => {
                    if seq != self.pb.req {
                        continue; // superseded by a newer track choice
                    }
                    self.pb.req = 0;
                    let start = std::mem::take(&mut self.pb.pending_start);
                    let res = r.and_then(|info| {
                        let res = self.pb.player.play(&info.url, start, &self.ctx);
                        if let Some(n) = &info.note {
                            self.toast = Some((n.clone(), Instant::now()));
                        }
                        self.pb.stream = Some(info);
                        res
                    });
                    if let Err(e) = res {
                        self.pb.stream = None;
                        self.notify(format!("无法播放：{}", e));
                    }
                }
                Ev::Job(id, e) => {
                    if let Some(job) = self.jobs.iter_mut().find(|j| j.id == id) {
                        let fin = matches!(e, JobEvent::Finished(_));
                        job.apply(e);
                        if fin {
                            let msg = match job.status {
                                JobStatus::Done => format!("✔ 下载完成：{}", job.meta.title),
                                JobStatus::Cancelled => format!("已取消：{}", job.meta.title),
                                _ => format!("✖ 下载失败：{}", job.meta.title),
                            };
                            self.notify(msg);
                        }
                    }
                }
                Ev::Qa(r) => {
                    self.qa_running = false;
                    match r {
                        Ok(rep) => {
                            self.qa_report = Some(rep);
                            self.notify("质检与封面处理完成");
                        }
                        Err(e) => self.notify(format!("质检失败：{}", e)),
                    }
                }
                Ev::PlayerAccount(result) => {
                    self.player_account_busy = false;
                    match result {
                        Ok(account) => {
                            self.relay = account.as_ref().and_then(|account| crate::relay::Relay::account(
                                self.api.clone(), self.ctx.clone(),
                                crate::relay::snapshot(&self.pb, self.api.is_authenticated()), account,
                            ));
                            self.remote = account.as_ref().and_then(crate::relay::PlayerAccount::remote);
                            self.player_account = account;
                            if self.route == Route::Devices && self.remote.is_none() {
                                self.go(Route::Settings);
                            }
                            self.notify(if self.player_account.is_some() { "播放器账号已登录" } else { "已退出播放器账号" });
                        }
                        Err(error) => self.notify(error),
                    }
                }
                Ev::RemoteDevices(result) => {
                    if let Some(remote) = &mut self.remote {
                        remote.set_devices(result);
                        // Fall back to this computer when the controlled device is gone or offline.
                        let lost = remote.devices_loaded
                            && remote.target.is_some()
                            && remote.target_device().is_none_or(|device| !device.online);
                        if lost {
                            let name = remote.target_name();
                            remote.select(None);
                            self.notify(format!("{name} 已离线，已切回本机"));
                        }
                    }
                }
                Ev::RemoteState(generation, result) => {
                    if let Some(remote) = &mut self.remote {
                        remote.set_state(generation, result);
                    }
                }
                Ev::RemoteCommand(generation, result) => {
                    let error = self.remote.as_mut().and_then(|remote| remote.command_done(generation, result));
                    if let Some(error) = error {
                        self.notify(error);
                    }
                }
                Ev::RemoteRemoved(id, result) => match result {
                    Ok(()) => {
                        if let Some(remote) = &mut self.remote {
                            if remote.target.as_deref() == Some(id.as_str()) {
                                remote.select(None);
                            }
                            remote.devices.retain(|device| device.id != id);
                            remote.refresh_devices_soon();
                        }
                        self.notify("设备已移除，该设备需要重新登录播放器账号");
                    }
                    Err(error) => self.notify(error),
                },
                Ev::RemoteLoggedOutOthers(result) => match result {
                    Ok(()) => {
                        if let Some(remote) = &mut self.remote {
                            remote.select(None);
                            remote.devices.retain(|device| device.is_self);
                            remote.refresh_devices_soon();
                        }
                        self.notify("已退出其他所有设备，它们需要重新登录播放器账号");
                    }
                    Err(error) => self.notify(error),
                },
                Ev::RemoteMarks(generation, result) => {
                    if let Some(remote) = self.remote.as_mut().filter(|remote| remote.generation == generation) {
                        remote.marks_busy = false;
                        if let Ok(reply) = result {
                            remote.marks = Some(crate::marks::Marks::from_json(&reply["marks"]));
                        }
                    }
                }
                Ev::RemoteDownloads(generation, result) => {
                    if let Some(remote) = &mut self.remote {
                        if remote.generation == generation {
                            remote.downloads_busy = false;
                            match result {
                                Ok(list) => {
                                    if let (Some(queued), Some(skipped)) = (list["queued"].as_u64(), list["skipped"].as_u64()) {
                                        let name = remote.target_name();
                                        self.toast = Some((if skipped > 0 {
                                            format!("⬇ 已在 {name} 加入 {queued} 项，{skipped} 项已下载或正在下载")
                                        } else {
                                            format!("⬇ 已在 {name} 加入 {queued} 项下载")
                                        }, Instant::now()));
                                    }
                                    remote.downloads = Some(list);
                                    remote.downloads_error = None;
                                }
                                Err(error) => remote.downloads_error = Some(error),
                            }
                        }
                    }
                }
                Ev::RemoteLibrary(generation, request, append, result) => {
                    if let Some(remote) = &mut self.remote {
                        let library = &mut remote.library;
                        if remote.generation == generation && library.request == request {
                            library.loading = false;
                            match result {
                                Ok(page) => {
                                    library.apply(&page, append);
                                    library.error = None;
                                }
                                Err(error) => library.error = Some(error),
                            }
                        }
                    }
                }
                Ev::TransferHere(result) => match result {
                    Ok((tracks, index, start)) => {
                        let title = tracks.get(index).map(TrackItem::full_title).unwrap_or_default();
                        self.start_queue_local(tracks, index, false, None, start);
                        self.notify(format!("已在本机继续播放：{title}"));
                    }
                    Err(error) => self.notify(error),
                },
                Ev::Auth(r) => {
                    self.auth_busy = false;
                    match r {
                        Ok(()) => {
                            self.notify("Token 已刷新");
                            self.lib.state = LibState::Idle;
                            self.load_library();
                        }
                        Err(e) => self.notify(e),
                    }
                }
            }
        }
    }

    fn resume_pending(&mut self, route: &Route, ok: bool) {
        if self.pending_play.as_ref().map(|(r, _)| r) == Some(route) {
            let (r, shuffle) = self.pending_play.take().unwrap();
            if ok {
                self.play_collection(r, shuffle);
            } else {
                self.notify("加载失败，无法播放");
            }
        }
    }

    // ── action dispatcher ──

    fn apply(&mut self, actions: Vec<Action>) {
        for a in actions {
            if let Some(a) = self.remote_action(a) {
                self.apply_local(a);
            }
        }
    }

    /// Apply an action to this computer, never to the controlled remote device.
    fn apply_local(&mut self, a: Action) {
        match a {
            Action::Go(r) => self.go(r),
            Action::Back => {
                if let Some(r) = self.history.back.pop() {
                    let cur = std::mem::replace(&mut self.route, r.clone());
                    self.history.fwd.push(cur);
                    self.sync_search_inputs(&r);
                    self.ensure_loaded(&r);
                }
            }
            Action::Forward => {
                if let Some(r) = self.history.fwd.pop() {
                    let cur = std::mem::replace(&mut self.route, r.clone());
                    self.history.back.push(cur);
                    self.sync_search_inputs(&r);
                    self.ensure_loaded(&r);
                }
            }
            Action::Refresh => {
                let r = self.route.clone();
                match r {
                    Route::Home | Route::Library(_) => {
                        self.lib.state = LibState::Idle;
                        self.load_library();
                    }
                    Route::RemoteLibrary(tab) => {
                        if let Some(remote) = &mut self.remote {
                            remote.library.loaded = None;
                            remote.library.error = None;
                        }
                        self.load_remote_library(tab, false);
                    }
                    _ => {
                        self.cache.forget(&r);
                        self.ensure_loaded(&r);
                    }
                }
            }
            Action::Search(q) => {
                let q = q.trim().to_string();
                if !q.is_empty() {
                    self.recent_searches.retain(|x| x != &q);
                    self.recent_searches.insert(0, q.clone());
                    self.recent_searches.truncate(12);
                }
                self.ui.search_text = q.clone();
                self.ui.search_filter = SearchFilter::All;
                self.go(Route::Search(self.ui.search_source, q));
            }

            Action::PlayTracks { tracks, index, shuffle, context } => self.start_queue(tracks, index, shuffle, context),
            Action::PlayAlbum { id, shuffle } => self.play_collection(Route::Album(id), shuffle),
            Action::PlayPlaylist { uuid, shuffle } => self.play_collection(Route::Playlist(uuid), shuffle),
            Action::PlayNext(ts) => {
                let n = ts.len();
                let was_empty = self.pb.queue.is_empty();
                self.pb.insert_next(ts);
                if was_empty {
                    self.play_index(0);
                }
                self.notify(format!("已添加 {} 首到“下一首播放”", n));
            }
            Action::Enqueue(ts) => {
                let n = ts.len();
                let was_empty = self.pb.queue.is_empty();
                self.pb.append(ts);
                if was_empty {
                    self.play_index(0);
                }
                self.notify(format!("已添加 {} 首到播放队列", n));
            }
            Action::TogglePause => {
                if self.pb.player.snapshot().active {
                    self.pb.player.toggle_pause();
                } else if self.pb.current().is_some() && !self.pb.is_loading() {
                    self.play_index(self.pb.index);
                }
            }
            Action::Next => {
                if let Some(i) = self.pb.next_index(false) {
                    self.play_index(i);
                }
            }
            Action::Prev => {
                let st = self.pb.player.snapshot();
                if st.active && st.position > 3.0 {
                    self.pb.player.seek(0.0);
                } else if let Some(i) = self.pb.prev_index() {
                    self.play_index(i);
                }
            }
            Action::Seek(s) => self.pb.player.seek(s),
            Action::SetVolume(v) => self.pb.player.set_volume(v),
            Action::ToggleShuffle => self.pb.toggle_shuffle(),
            Action::CycleRepeat => self.pb.cycle_repeat(),
            Action::JumpQueue(i) => self.play_index(i),
            Action::RemoveQueue(i) => self.pb.remove(i),
            Action::ClearQueue => self.pb.clear_upcoming(),
            Action::StopPlayback => self.pb.stop(),
            Action::SetStreamQuality(q) => {
                if q != self.pb.quality {
                    self.pb.quality = q;
                    self.notify(format!("试听音质：{}（{}）", q.short(), q.describe()));
                    // Re-open the current track at the same position with the new quality.
                    let st = self.pb.player.snapshot();
                    if self.pb.current().is_some() && (st.active || self.pb.is_loading()) {
                        let pos = st.position;
                        self.play_index_at(self.pb.index, pos);
                    }
                }
            }

            Action::SetNeteaseLevel(level) => {
                if level != self.pb.netease_level {
                    self.pb.netease_level = level;
                    self.notify(format!("网易云音质：{}", crate::netease::level_name(level)));
                    let st = self.pb.player.snapshot();
                    let netease = self.pb.current().is_some_and(|t| crate::netease::native_id(t.id).is_some());
                    if netease && (st.active || self.pb.is_loading()) {
                        let pos = st.position;
                        self.play_index_at(self.pb.index, pos);
                    }
                }
            }

            Action::ToggleFav(t) => {
                if t.is_netease() {
                    return;
                }
                if let FavTarget::Playlist(p) = &t {
                    if self.lib.is_mine(&p.uuid) {
                        self.notify("这是你自己创建的歌单");
                        return;
                    }
                }
                let add = !self.lib.is_fav(&t);
                self.lib.apply(&t, add);
                self.notify(if add { format!("♥ 已收藏：{}", t.name()) } else { format!("已取消收藏：{}", t.name()) });
                self.spawn(move |api| {
                    let r = api.set_favorite(t.kind(), &t.id(), add);
                    Ev::Fav(t, add, r)
                });
            }
            Action::ReloadLibrary => {
                self.lib.state = LibState::Idle;
                self.load_library();
            }

            Action::Download { url, quality, title } => {
                let name = title.clone().unwrap_or_else(|| url.clone());
                if self.enqueue_download(url, quality, title) {
                    self.notify(format!("⬇ 已加入下载队列：{}", name));
                } else {
                    self.notify("该任务已在下载队列中");
                }
            }
            // Only meaningful while controlling a device; see `remote_action`.
            Action::RemoteDownload(..) => {}
            Action::EnqueueInput => {
                let input = std::mem::take(&mut self.ui.dl_input);
                let q = self.ui.dl_quality.clone();
                let n = input
                    .split_whitespace()
                    .map(str::to_string)
                    .collect::<Vec<_>>()
                    .into_iter()
                    .filter(|u| self.enqueue_download(u.clone(), &q, None))
                    .count();
                self.notify(format!("已加入 {} 个下载任务", n));
            }
            Action::CancelJob(id) => {
                if let Some(j) = self.jobs.iter_mut().find(|j| j.id == id) {
                    j.cancel();
                }
            }
            Action::RemoveJob(id) => self.jobs.retain(|j| j.id != id),
            Action::RetryJob(id) => {
                if let Some(pos) = self.jobs.iter().position(|j| j.id == id) {
                    let old = self.jobs.remove(pos);
                    let mut nj = DownloadJob::new(self.next_job, old.url, old.quality, old.atmos, old.dest);
                    nj.meta.title = old.meta.title;
                    nj.meta.subtitle = old.meta.subtitle;
                    nj.meta.cover = old.meta.cover;
                    self.next_job += 1;
                    self.jobs.push(nj);
                }
            }
            Action::ClearFinishedJobs => self.jobs.retain(|j| !j.status.is_finished()),

            Action::RunQa => {
                if !self.qa_running {
                    self.qa_running = true;
                    let target = PathBuf::from(&self.ui.qa_folder);
                    let (tx, ctx) = (self.tx.clone(), self.ctx.clone());
                    thread::spawn(move || {
                        let _ = tx.send(Ev::Qa(run_shanling_qa(&target)));
                        ctx.request_repaint();
                    });
                }
            }
            Action::PlayerLogin(username, password) => {
                if !self.player_account_busy {
                    self.player_account_busy = true;
                    self.spawn(move |_| Ev::PlayerAccount(crate::relay::PlayerAccount::login(username, password).map(Some)));
                }
            }
            Action::PlayerLogout => {
                if !self.player_account_busy {
                    self.player_account_busy = true;
                    self.spawn(|_| Ev::PlayerAccount(crate::relay::PlayerAccount::logout().map(|_| None)));
                }
            }
            Action::ReloadAuth => {
                self.api.reload_auth();
                self.notify("已重新读取 auth.json");
                self.lib.state = LibState::Idle;
                self.load_library();
            }
            Action::RefreshToken => {
                if !self.auth_busy {
                    self.auth_busy = true;
                    self.spawn(|api| Ev::Auth(api.refresh_token()));
                }
            }
            Action::Copy(s) => {
                self.ctx.copy_text(s);
                self.notify("链接已复制到剪贴板");
            }
            Action::OpenUrl(u) => {
                let _ = open::that(u);
            }
            Action::SelectDevice(target) => {
                let Some(remote) = &mut self.remote else { return };
                let target = target.filter(|id| remote.devices.iter().any(|device| &device.id == id && !device.is_self));
                remote.select(target);
                let message = match remote.target_device() {
                    Some(device) => format!("{} 正在遥控：{}", device.icon(), device.name),
                    None => "已切回本机播放".to_string(),
                };
                self.notify(message);
            }
            Action::RemoteLibraryFilter(query, playlist) => {
                if let Some(remote) = &mut self.remote {
                    remote.library.query = query.trim().to_string();
                    remote.library.playlist = playlist;
                }
            }
            Action::RemoteLibraryMore => {
                if let Route::RemoteLibrary(tab) = self.route {
                    self.load_remote_library(tab, true);
                }
            }
            Action::RemoteLibraryPlay(index) => {
                let Some(remote) = self.remote_target() else { return };
                let library = &remote.library;
                let Some(track) = library.tracks.get(index) else { return };
                let command = if library.tab == Some(crate::route::RemoteTab::Songs) && track.local() {
                    // Local files play as a list from the chosen song on, like this computer's lists.
                    let ids: Vec<i64> = library.tracks[index..].iter().filter(|track| track.local()).map(|track| track.id).take(50).collect();
                    serde_json::json!({"op": "library", "action": "play-list", "ids": ids, "shuffle": false})
                } else {
                    serde_json::json!({"op": "library", "action": "song", "id": track.id, "mode": "play"})
                };
                let message = format!("正在 {} 播放：{}", remote.target_name(), track.title);
                if self.remote_send(command) {
                    self.notify(message);
                }
            }
            Action::RemoteLibrarySong(id, mode) => {
                let name = self.remote_target().map(crate::remote::Remote::target_name).unwrap_or_default();
                if self.remote_send(serde_json::json!({"op": "library", "action": "song", "id": id, "mode": mode})) {
                    self.notify(if mode == "next" { format!("已加入 {name} 的下一首") } else { format!("已加入 {name} 的队列") });
                }
            }
            Action::RemoteLibraryPlayAll(shuffle) => {
                let Some(remote) = self.remote_target() else { return };
                let ids: Vec<i64> = remote.library.tracks.iter().filter(|track| track.local()).map(|track| track.id).take(50).collect();
                if ids.is_empty() {
                    self.notify("此页没有可播放的本地歌曲");
                    return;
                }
                let name = remote.target_name();
                if self.remote_send(serde_json::json!({"op": "library", "action": "play-list", "ids": ids, "shuffle": shuffle})) {
                    self.notify(format!("正在 {name} 播放本页歌曲"));
                }
            }
            Action::LogoutOtherDevices => {
                if let Some(remote) = &self.remote {
                    let connection = remote.connection.clone();
                    self.spawn(move |_| Ev::RemoteLoggedOutOthers(connection.logout_others()));
                }
            }
            Action::TransferTo(id) => {
                let Some(remote) = &mut self.remote else { return };
                let Some(name) = remote.devices.iter().find(|device| device.id == id && device.online && !device.is_self)
                    .map(|device| device.name.clone()) else {
                    self.notify("设备离线");
                    return;
                };
                match self.pb.current() {
                    None => {
                        self.notify("本机没有正在播放的歌曲");
                        return;
                    }
                    Some(track) if crate::netease::native_id(track.id).is_some() => {
                        self.notify("网易云歌曲只能在本机播放");
                        return;
                    }
                    Some(_) => {}
                }
                remote.select(Some(id));
                let player = self.pb.player.snapshot();
                let position = if player.active { player.position } else { 0.0 };
                let (tracks, index) = (self.pb.queue.clone(), self.pb.index);
                if self.remote_tracks(tracks, index, false, "play", position) > 0 {
                    if player.active && !player.paused {
                        self.pb.player.toggle_pause();
                    }
                    self.notify(format!("已转到 {name} 继续播放"));
                }
            }
            Action::TransferHere => {
                let Some(remote) = self.remote_target() else { return };
                let Some(state) = &remote.state else {
                    self.notify("尚未读取到设备状态，请稍候");
                    return;
                };
                let Some(index) = state.index else {
                    self.notify("该设备没有正在播放的歌曲");
                    return;
                };
                if state.queue[index].tidal_id.is_none() {
                    self.notify("当前歌曲是该设备的本地或网易云歌曲，无法在本机继续");
                    return;
                }
                // Same 50-track window around the current track as when sending a list.
                let tidal: Vec<(usize, u64)> = state.queue.iter().enumerate()
                    .filter_map(|(at, track)| track.tidal_id.map(|id| (at, id))).collect();
                let current = tidal.iter().position(|(at, _)| *at == index).unwrap_or(0);
                let start = current.min(tidal.len().saturating_sub(50));
                let ids: Vec<u64> = tidal[start..tidal.len().min(start + 50)].iter().map(|(_, id)| *id).collect();
                let current_id = tidal[current].1;
                let position = state.position();
                let pause = state.playing.then(|| (remote.connection.clone(), remote.target.clone().unwrap_or_default()));
                let name = remote.target_name();
                // Pause the device directly: the command queue is dropped when switching back here.
                if let Some((connection, target)) = pause {
                    thread::spawn(move || { let _ = connection.control(&target, &serde_json::json!({"op": "toggle"})); });
                }
                if let Some(remote) = &mut self.remote {
                    remote.select(None);
                }
                self.notify(format!("正在把 {name} 的播放转到本机…"));
                self.spawn(move |api| {
                    let tracks: Vec<TrackItem> = crate::relay::fetch_tracks(&api, &ids).into_iter().flatten().collect();
                    Ev::TransferHere(match tracks.iter().position(|track| track.id == current_id) {
                        Some(index) => Ok((tracks, index, position)),
                        None => Err("无法读取当前歌曲，未能在本机继续".into()),
                    })
                });
            }
            Action::RefreshDevices => {
                if let Some(remote) = &mut self.remote {
                    remote.refresh_devices_soon();
                }
            }
            Action::RemoveDevice(id) => {
                if let Some(remote) = &self.remote {
                    let connection = remote.connection.clone();
                    self.spawn(move |_| {
                        let result = connection.remove(&id);
                        Ev::RemoteRemoved(id, result)
                    });
                }
            }
        }
    }

    fn shortcuts(&self, ctx: &egui::Context, st: &mut UiState, acts: &mut Vec<Action>) {
        let typing = ctx.memory(|m| m.focused().is_some());
        ctx.input(|i| {
            if i.pointer.button_pressed(egui::PointerButton::Extra1) || (i.modifiers.alt && i.key_pressed(egui::Key::ArrowLeft)) {
                acts.push(Action::Back);
            }
            if i.pointer.button_pressed(egui::PointerButton::Extra2) || (i.modifiers.alt && i.key_pressed(egui::Key::ArrowRight)) {
                acts.push(Action::Forward);
            }
            if !typing && i.key_pressed(egui::Key::Space) {
                acts.push(Action::TogglePause);
            }
            if i.modifiers.command && i.key_pressed(egui::Key::F) {
                st.focus_search = true;
            }
            if i.modifiers.command && i.key_pressed(egui::Key::ArrowRight) {
                acts.push(Action::Next);
            }
            if i.modifiers.command && i.key_pressed(egui::Key::ArrowLeft) {
                acts.push(Action::Prev);
            }
        });
    }
}

impl App {
    fn relay_command(&mut self, body: serde_json::Value) -> Result<(), String> {
        let operation = body["op"].as_str().unwrap_or_default();
        let index = || {
            if body["revision"].as_str() != Some(&crate::relay::revision(&self.pb)) {
                return Err("队列已更新，请重试".to_string());
            }
            body["index"].as_u64().and_then(|index| usize::try_from(index).ok())
                .filter(|index| *index < self.pb.queue.len()).ok_or_else(|| "队列项不存在".to_string())
        };
        let action = match operation {
            "toggle" => Action::TogglePause,
            "next" => Action::Next,
            "previous" => Action::Prev,
            "shuffle" => Action::ToggleShuffle,
            "repeat" => Action::CycleRepeat,
            "seek" => {
                let player = self.pb.player.snapshot();
                let ms = body["ms"].as_u64().ok_or("进度无效")?;
                if !player.active || player.duration <= 0.0 { return Err("当前曲目不可跳转".into()); }
                Action::Seek((ms as f64 / 1000.0).min(player.duration))
            }
            "play" => Action::JumpQueue(index()?),
            "remove" => {
                let at = index()?;
                if at == self.pb.index { return Err("正在播放的歌曲不能移除".into()); }
                Action::RemoveQueue(at)
            }
            "move" => {
                let from = index()?;
                let to = body["to"].as_u64().and_then(|to| usize::try_from(to).ok())
                    .filter(|to| *to < self.pb.queue.len()).ok_or("移动位置无效")?;
                self.pb.move_track(from, to);
                return Ok(());
            }
            "tracks" => {
                let tracks: Vec<TrackItem> = serde_json::from_value(body["tracks"].clone()).map_err(|_| "曲目信息无效")?;
                match body["mode"].as_str() {
                    Some("play") => {
                        let index = body["index"].as_u64().map_or(0, |index| index as usize).min(tracks.len().saturating_sub(1));
                        let start = body["position"].as_u64().unwrap_or(0) as f64 / 1000.0;
                        self.start_queue_local(tracks, index, body["shuffle"] == true, None, start);
                        return Ok(());
                    }
                    Some("next") => Action::PlayNext(tracks),
                    Some("end") => Action::Enqueue(tracks),
                    _ => return Err("无效播放操作".into()),
                }
            }
            "clear" => Action::ClearQueue,
            "quality" => match body["value"].as_str().unwrap_or_default().strip_prefix("netease:") {
                Some(level) => Action::SetNeteaseLevel(
                    crate::netease::PLAY_LEVELS.iter().find(|(code, _, _)| *code == level).map(|(code, _, _)| *code).ok_or("音质无效")?,
                ),
                None => Action::SetStreamQuality(
                    body["value"].as_str().and_then(crate::remote::stream_quality).ok_or("音质无效")?,
                ),
            },
            "volume" => {
                let volume = body["value"].as_f64().filter(|value| (0.0..=100.0).contains(value)).ok_or("音量无效")? as f32;
                self.ui.volume = volume;
                Action::SetVolume(volume)
            }
            "add" => {
                let track = serde_json::from_value(body["track"].clone()).map_err(|_| "曲目信息无效")?;
                if body["next"] == true { Action::PlayNext(vec![track]) } else { Action::Enqueue(vec![track]) }
            }
            _ => return Err("未知遥控操作".into()),
        };
        self.apply_local(action);
        Ok(())
    }

    /// Downloads another device of the account starts here or manages here ("download" / "downloads").
    fn relay_downloads(&mut self, body: &serde_json::Value) -> Result<serde_json::Value, String> {
        if !crate::relay::lends_tidal() {
            return Err(crate::relay::NOT_LENT.into());
        }
        if body["op"] == "download" {
            if !self.api.is_authenticated() {
                return Err("此设备尚未登录 TIDAL".into());
            }
            let quality = match body["quality"].as_str().unwrap_or("BEST") {
                "BEST" | "HI_RES_LOSSLESS" => "max",
                "LOSSLESS" => "high",
                "HIGH" => "normal",
                "LOW" => "low",
                _ => return Err("无效的下载音质".into()),
            };
            let urls: Vec<String> = if let Some(album) = body["album"].as_str() {
                if album.is_empty() || album.len() > 20 || !album.chars().all(|c| c.is_ascii_digit()) {
                    return Err("无效的专辑".into());
                }
                vec![format!("https://tidal.com/album/{album}")]
            } else {
                let ids = body["ids"].as_array().ok_or("曲目列表无效")?;
                if ids.is_empty() || ids.len() > 100 {
                    return Err("每次可下载 1–100 首歌曲".into());
                }
                ids.iter()
                    .map(|id| id.as_u64().filter(|id| *id > 0 && crate::netease::native_id(*id).is_none()))
                    .map(|id| id.map(|id| format!("https://tidal.com/track/{id}")))
                    .collect::<Option<_>>()
                    .ok_or("曲目编号无效")?
            };
            let total = urls.len();
            let queued = urls.into_iter().filter(|url| self.enqueue_download(url.clone(), quality, None)).count();
            self.notify(format!("⬇ 其他设备加入了 {queued} 个下载任务"));
            let mut list = self.download_list(300);
            list["queued"] = queued.into();
            list["skipped"] = (total - queued).into();
            return Ok(list);
        }
        let limit = body["limit"].as_u64().unwrap_or(300).clamp(1, 2000) as usize;
        // One task ("id") or, from multi-select, several ("ids").
        let ids: Vec<u64> = match body["ids"].as_array() {
            Some(many) if many.len() > 500 => return Err("每次最多处理 500 项".into()),
            Some(many) => many.iter().filter_map(|id| id.as_str()?.parse().ok()).collect(),
            None => body["id"].as_str().and_then(|id| id.parse().ok()).into_iter().collect(),
        };
        let single = body["ids"].is_null();
        let make: fn(u64) -> Action = match body["action"].as_str().unwrap_or("list") {
            "list" => return Ok(self.download_list(limit)),
            "marks" => return Ok(serde_json::json!({"marks": self.download_marks()})),
            "clear" => {
                self.apply_local(Action::ClearFinishedJobs);
                return Ok(self.download_list(limit));
            }
            "cancel" => Action::CancelJob,
            "retry" => Action::RetryJob,
            "remove" => Action::RemoveJob,
            "parallel" => return Err("桌面端逐个下载任务".into()),
            _ => return Err("无效的下载操作".into()),
        };
        let cancel = body["action"] == "cancel";
        let mut skipped = 0;
        for id in &ids {
            let Some(job) = self.jobs.iter().find(|job| job.id == *id) else {
                if single {
                    return Err("下载记录已不存在".into());
                }
                skipped += 1;
                continue;
            };
            if !cancel && !job.status.is_finished() {
                if single {
                    return Err("任务仍在下载".into());
                }
                skipped += 1;
                continue;
            }
            self.apply_local(make(*id));
        }
        if single && ids.is_empty() {
            return Err("下载记录已不存在".into());
        }
        let mut list = self.download_list(limit);
        list["skipped"] = skipped.into();
        Ok(list)
    }

    /// Finished TIDAL downloads, so controlling devices mark them in their lists
    /// ({"tracks": {"TIDAL:id": quality}, "albums": {"TIDAL:id": songs}}).
    fn download_marks(&self) -> serde_json::Value {
        use crate::download::TrackStatus;
        let (mut tracks, mut albums) = (serde_json::Map::new(), serde_json::Map::new());
        for job in self.jobs.iter().filter(|job| job.status == JobStatus::Done) {
            let quality = crate::ui::widgets::quality_label(&job.quality);
            match crate::download::url_kind(&job.url) {
                Some(("track", id)) => { tracks.insert(format!("TIDAL:{id}"), quality.into()); }
                Some(("album", id)) => {
                    let saved = job.count(TrackStatus::Downloaded) + job.count(TrackStatus::Exists);
                    albums.insert(format!("TIDAL:{id}"), saved.max(1).into());
                }
                _ => {}
            }
        }
        serde_json::json!({"tracks": tracks, "albums": albums})
    }

    /// The download list as other devices show it, newest first. One row per download task.
    fn download_list(&self, limit: usize) -> serde_json::Value {
        use crate::download::TrackStatus;
        let jobs: Vec<_> = self.jobs.iter().rev().take(limit).map(|job| {
            let state = match job.status {
                JobStatus::Queued => "queued",
                JobStatus::Resolving => "preparing",
                JobStatus::Downloading | JobStatus::PostProcessing => "transferring",
                JobStatus::Done => "complete",
                JobStatus::Failed => "failed",
                JobStatus::Cancelled => "cancelled",
            };
            let saved = job.count(TrackStatus::Downloaded) + job.count(TrackStatus::Exists);
            let detail = match (job.meta.kind.is_empty(), job.total()) {
                (_, 0) => job.meta.kind.clone(),
                (true, total) => format!("{saved}/{total} 首"),
                (false, total) => format!("{} · {saved}/{total} 首", job.meta.kind),
            };
            serde_json::json!({"id": job.id.to_string(), "track": 0, "title": job.meta.title,
                "artist": job.meta.subtitle, "album": "",
                "source": if crate::netease::is_link(&job.url) { "网易云" } else { "TIDAL" },
                "state": state, "bytes": job.bytes, "total": -1, "resolution": detail,
                "error": if job.status == JobStatus::Failed { job.logs.last().cloned().unwrap_or_default() } else { String::new() },
                "batch": "", "order": 0, "quality": job.quality})
        }).collect();
        serde_json::json!({"jobs": jobs, "total": self.jobs.len(), "parallel": 1, "maxParallel": 1, "revision": 0, "perTrack": false})
    }

    fn relay_tick(&mut self) {
        if let Some(command) = self.relay.as_ref().and_then(crate::relay::Relay::recv) {
            let operation = command.body["op"].as_str().unwrap_or_default();
            let response = if operation == "download" || operation == "downloads" {
                self.relay_downloads(&command.body).unwrap_or_else(|error| serde_json::json!({"error": error}))
            } else {
                match self.relay_command(command.body) {
                    Ok(()) => crate::relay::snapshot(&self.pb, self.api.is_authenticated()),
                    Err(error) => serde_json::json!({"error": error}),
                }
            };
            if let Some(relay) = &self.relay {
                if response.get("queue").is_some() {
                    relay.publish(response.clone());
                }
            }
            let _ = command.reply.send(response);
        }
        if let Some(relay) = &self.relay {
            relay.publish(crate::relay::snapshot(&self.pb, self.api.is_authenticated()));
            self.ctx.request_repaint_after(std::time::Duration::from_millis(850));
        }
    }
}

impl eframe::App for App {
    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        let p = Persist {
            volume: self.pb.player.volume,
            dl_atmos: self.ui.dl_atmos.clone(),
            dl_dest: self.ui.dl_dest.clone(),
            qa_folder: self.ui.qa_folder.clone(),
            show_queue: self.ui.show_queue,
            recent_albums: self.recent_albums.clone(),
            recent_searches: self.recent_searches.clone(),
            stream_quality: self.pb.quality,
            netease_level: self.pb.netease_level.to_string(),
            lend_tidal: crate::relay::lends_tidal(),
        };
        eframe::set_value(storage, eframe::APP_KEY, &p);
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.handle_events();
        self.tick_playback();
        self.relay_tick();
        self.remote_tick();
        self.schedule_jobs();
        let r = self.route.clone();
        self.ensure_loaded(&r);

        // Render with `&App` + `&mut UiState`; collect intents, apply afterwards.
        let mut acts = Vec::new();
        let mut st = std::mem::take(&mut self.ui);
        self.shortcuts(ctx, &mut st, &mut acts);
        ui::render(ctx, self, &mut st, &mut acts);
        self.ui = st;
        self.apply(acts);
    }
}

// ───────────────────────────── remote control ─────────────────────────────

impl App {
    /// The remote-control state while another device of this account is selected.
    pub fn remote_target(&self) -> Option<&crate::remote::Remote> {
        self.remote.as_ref().filter(|remote| remote.target.is_some())
    }

    fn remote_send(&mut self, command: serde_json::Value) -> bool {
        let Some(remote) = &mut self.remote else { return false };
        if remote.pending() >= 60 {
            self.notify("遥控命令过多，请稍后再试");
            return false;
        }
        remote.push(command);
        self.ctx.request_repaint();
        true
    }

    /// Send a track list to the remote device in one command. `mode` is "play" (replace the queue
    /// and start at `index`), "next" or "end". The device looks tracks up by TIDAL id, so NetEase
    /// tracks only play on this computer. Returns how many tracks were sent.
    fn remote_tracks(&mut self, tracks: Vec<TrackItem>, index: usize, shuffle: bool, mode: &str, start_at: f64) -> usize {
        let chosen = tracks.get(index).map(|track| track.id);
        let tracks: Vec<_> = tracks.into_iter().filter(|track| crate::netease::native_id(track.id).is_none()).collect();
        if tracks.is_empty() {
            self.notify("网易云歌曲只能在本机播放");
            return 0;
        }
        let index = chosen.and_then(|id| tracks.iter().position(|track| track.id == id)).unwrap_or(0);
        // One command carries at most 50 tracks: keep the chosen track inside the window.
        let start = index.min(tracks.len().saturating_sub(50));
        let window = &tracks[start..tracks.len().min(start + 50)];
        let ids: Vec<u64> = window.iter().map(|track| track.id).collect();
        let name = self.remote.as_ref().map(crate::remote::Remote::target_name).unwrap_or_default();
        let first = index - start;
        let sent = self.remote_send(serde_json::json!({"op": "tracks", "ids": ids, "index": first,
            "shuffle": shuffle, "mode": mode, "position": (start_at.max(0.0) * 1000.0) as u64}));
        if sent && mode == "play" {
            self.notify(format!("正在 {name} 播放：{}", window[first].full_title()));
        }
        if sent { ids.len() } else { 0 }
    }

    /// Send playback actions to the selected remote device. Returns the action back when it
    /// should run on this computer instead.
    fn remote_action(&mut self, action: Action) -> Option<Action> {
        use serde_json::json;
        let Some(remote) = self.remote_target() else { return Some(action) };
        let state = remote.state.as_ref();
        let revision = state.map(|state| state.revision.clone()).unwrap_or_default();
        let queue_len = state.map_or(0, |state| state.queue.len());
        let current = state.and_then(|state| state.index);
        let name = remote.target_name();
        let downloads = remote.remote_downloads();
        match action {
            Action::Download { url, quality, .. } if downloads => {
                let quality = match quality {
                    "max" => "BEST",
                    "high" => "LOSSLESS",
                    "normal" => "HIGH",
                    _ => "LOW",
                };
                let command = match crate::download::url_kind(&normalize_url(&url)) {
                    Some(("album", id)) => json!({"op": "download", "album": id, "quality": quality}),
                    Some(("track", id)) if id.parse::<u64>().is_ok() => {
                        json!({"op": "download", "ids": [id.parse::<u64>().unwrap_or(0)], "quality": quality})
                    }
                    _ => {
                        self.notify(format!("{name} 只能下载 TIDAL 专辑和歌曲"));
                        return None;
                    }
                };
                self.notify(format!("正在发送到 {name}…"));
                self.remote_downloads_query(command);
            }
            Action::RemoteDownload(action, id) => {
                self.remote_downloads_query(json!({"op": "downloads", "action": action, "id": id}));
            }
            Action::TogglePause => { self.remote_send(json!({"op": "toggle"})); }
            Action::Next => { self.remote_send(json!({"op": "next"})); }
            Action::Prev => { self.remote_send(json!({"op": "previous"})); }
            Action::ToggleShuffle => { self.remote_send(json!({"op": "shuffle"})); }
            Action::CycleRepeat => { self.remote_send(json!({"op": "repeat"})); }
            Action::Seek(seconds) => { self.remote_send(json!({"op": "seek", "ms": (seconds.max(0.0) * 1000.0) as u64})); }
            Action::JumpQueue(index) if index < queue_len => {
                self.remote_send(json!({"op": "play", "index": index, "revision": revision}));
            }
            Action::RemoveQueue(index) if index < queue_len && Some(index) != current => {
                self.remote_send(json!({"op": "remove", "index": index, "revision": revision}));
            }
            Action::JumpQueue(_) | Action::RemoveQueue(_) => {}
            Action::PlayNext(tracks) => {
                let count = self.remote_tracks(tracks, 0, false, "next", 0.0);
                if count > 0 {
                    self.notify(format!("已发送 {count} 首到 {name} 的“下一首播放”"));
                }
            }
            Action::Enqueue(tracks) => {
                let count = self.remote_tracks(tracks, 0, false, "end", 0.0);
                if count > 0 {
                    self.notify(format!("已发送 {count} 首到 {name} 的播放队列"));
                }
            }
            Action::SetVolume(volume) => { self.remote_send(json!({"op": "volume", "value": volume.clamp(0.0, 100.0).round()})); }
            Action::ClearQueue => { self.remote_send(json!({"op": "clear"})); }
            Action::SetStreamQuality(quality) => { self.remote_send(json!({"op": "quality", "value": quality.api_name()})); }
            Action::SetNeteaseLevel(level) => { self.remote_send(json!({"op": "quality", "value": format!("netease:{level}")})); }
            Action::StopPlayback => {
                self.notify(format!("请在 {name} 上进行此操作"));
            }
            other => return Some(other),
        }
        None
    }

    /// Fetch a page of the controlled Android device's library for its library page.
    fn load_remote_library(&mut self, tab: crate::route::RemoteTab, append: bool) {
        let Some(remote) = self.remote.as_mut().filter(|remote| remote.target.is_some() && remote.target_has_library()) else { return };
        let library = &mut remote.library;
        let key = (tab, library.query.clone(), library.playlist.clone());
        if library.loading || (!append && library.loaded.as_ref() == Some(&key)) || (append && library.next.is_none()) {
            return;
        }
        let offset = if append { library.next.unwrap_or(0) } else { 0 };
        library.loading = true;
        library.loaded = Some(key);
        library.tab = Some(tab);
        library.request += 1;
        let (request, generation) = (library.request, remote.generation);
        let action = match tab {
            crate::route::RemoteTab::Songs => "local",
            crate::route::RemoteTab::Recent => "recent",
        };
        let command = serde_json::json!({"op": "library", "action": action, "query": library.query,
            "playlist": library.playlist, "offset": offset});
        let (connection, target) = (remote.connection.clone(), remote.target.clone().unwrap_or_default());
        self.spawn(move |_| Ev::RemoteLibrary(generation, request, append, connection.query(&target, &command)));
    }

    /// Ask the controlled device about its downloads; the reply refreshes the downloads page.
    fn remote_downloads_query(&mut self, command: serde_json::Value) {
        let Some(remote) = &mut self.remote else { return };
        let Some(target) = remote.target.clone() else { return };
        let (generation, connection) = (remote.generation, remote.connection.clone());
        remote.downloads_busy = true;
        remote.last_downloads = Some(Instant::now());
        self.spawn(move |_| Ev::RemoteDownloads(generation, connection.query(&target, &command)));
    }

    /// Lend this computer the controlled device's TIDAL login while it has none of its own.
    fn sync_borrowed_tidal(&mut self) {
        let lender = self.remote.as_ref().filter(|_| !self.api.is_authenticated()).and_then(|remote| {
            let target = remote.target.clone()?;
            remote.state.as_ref().filter(|state| state.lends_tidal)?;
            Some((target, remote.connection.clone()))
        });
        if lender.as_ref().map(|(target, _)| target) == self.borrowed_tidal.as_ref() {
            return;
        }
        self.borrowed_tidal = lender.as_ref().map(|(target, _)| target.clone());
        self.api.set_proxy(lender.map(|(target, connection)| -> crate::api::Proxy {
            std::sync::Arc::new(move |method: &str, path: &str, form: Option<String>| {
                let mut command = serde_json::json!({"op": "tidal", "method": method, "path": path, "accept": "gzip"});
                if let Some(form) = form {
                    command["form"] = form.into();
                }
                connection.query(&target, &command).and_then(|reply| crate::relay::tidal_body(&reply))
            })
        }));
        // TIDAL pages and the library now come from the other login (or none).
        self.lib = Default::default();
        self.load_library();
        let route = self.route.clone();
        if self.cache.failed(&route) {
            self.cache.forget(&route);
        }
        self.ensure_loaded(&route);
    }

    /// "已下载" marks for lists: the controlled device's while it downloads for this computer,
    /// otherwise this computer's own.
    pub fn marks(&self) -> &crate::marks::Marks {
        match &self.remote {
            Some(remote) if remote.remote_downloads() => {
                static EMPTY: std::sync::OnceLock<crate::marks::Marks> = std::sync::OnceLock::new();
                remote.marks.as_ref().unwrap_or_else(|| EMPTY.get_or_init(Default::default))
            }
            _ => &self.local_marks,
        }
    }

    fn marks_tick(&mut self) {
        if self.local_marks_at.is_none_or(|at| at.elapsed() >= Duration::from_secs(2)) {
            self.local_marks_at = Some(Instant::now());
            let mut marks = crate::marks::Marks::from_json(&self.download_marks());
            use crate::download::TrackStatus;
            for job in self.jobs.iter().filter(|job| job.status == JobStatus::Done) {
                if let Some(("album", id)) = crate::download::url_kind(&job.url) {
                    if let Ok(id) = id.parse() {
                        marks.add_album_songs(id, job.meta.tracks.iter()
                            .filter(|track| matches!(track.status, TrackStatus::Downloaded | TrackStatus::Exists))
                            .map(|track| track.title.as_str()));
                    }
                }
            }
            self.local_marks = marks;
        }
        let Some(remote) = &mut self.remote else { return };
        if !remote.remote_downloads() || remote.marks_busy
            || remote.last_marks.is_some_and(|at| at.elapsed() < Duration::from_secs(20))
        {
            return;
        }
        let Some(target) = remote.target.clone() else { return };
        let (generation, connection) = (remote.generation, remote.connection.clone());
        remote.marks_busy = true;
        remote.last_marks = Some(Instant::now());
        self.spawn(move |_| Ev::RemoteMarks(generation, connection.query(&target, &serde_json::json!({"op": "downloads", "action": "marks"}))));
    }

    fn remote_tick(&mut self) {
        self.sync_borrowed_tidal();
        self.marks_tick();
        if self.route == Route::Downloads {
            if let Some(remote) = &self.remote {
                if remote.remote_downloads() && !remote.downloads_busy
                    && remote.last_downloads.is_none_or(|last| last.elapsed() >= Duration::from_secs(2))
                {
                    self.remote_downloads_query(serde_json::json!({"op": "downloads", "action": "list"}));
                }
            }
        }
        let Some(remote) = &mut self.remote else { return };
        let period = if remote.target.is_some() || self.route == Route::Devices {
            Duration::from_secs(3)
        } else {
            Duration::from_secs(10)
        };
        if remote.devices_due(period) {
            remote.devices_started();
            let connection = remote.connection.clone();
            let (tx, ctx) = (self.tx.clone(), self.ctx.clone());
            thread::spawn(move || {
                let _ = tx.send(Ev::RemoteDevices(connection.devices()));
                ctx.request_repaint();
            });
        }
        let Some(target) = remote.target.clone() else { return };
        let generation = remote.generation;
        if let Some(command) = remote.next_command() {
            let connection = remote.connection.clone();
            let (tx, ctx) = (self.tx.clone(), self.ctx.clone());
            thread::spawn(move || {
                let _ = tx.send(Ev::RemoteCommand(generation, connection.control(&target, &command)));
                ctx.request_repaint();
            });
        } else if remote.state_due() {
            remote.state_started();
            let connection = remote.connection.clone();
            let (tx, ctx) = (self.tx.clone(), self.ctx.clone());
            thread::spawn(move || {
                let _ = tx.send(Ev::RemoteState(generation, connection.state(&target)));
                ctx.request_repaint();
            });
        }
        // Keep the progress bar moving and keep polling.
        self.ctx.request_repaint_after(Duration::from_millis(250));
    }
}
