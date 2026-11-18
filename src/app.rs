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

    ToggleFav(FavTarget),
    ReloadLibrary,

    Download { url: String, quality: &'static str, title: Option<String> },
    EnqueueInput,
    CancelJob(u64),
    RemoveJob(u64),
    RetryJob(u64),
    ClearFinishedJobs,

    RunQa,
    ReloadAuth,
    PlayerLogin(String, String),
    PlayerLogout,
    RefreshToken,
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
    pub ui: UiState,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let (tx, rx) = channel();
        let p: Persist = cc.storage.and_then(|s| eframe::get_value(s, eframe::APP_KEY)).unwrap_or_default();

        let mut pb = Playback::new();
        pb.player.volume = p.volume;
        pb.quality = p.stream_quality;

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
        if !self.api.is_authenticated() || self.lib.state == LibState::Loading {
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
        self.spawn(move |api| Ev::Stream(seq, api.get_stream(id, q)));
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
        self.pb.set_queue(tracks, index, shuffle);
        self.pb.context = context;
        self.play_index(self.pb.index);
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
                            self.player_account = account;
                            self.notify(if self.player_account.is_some() { "播放器账号已登录" } else { "已退出播放器账号" });
                        }
                        Err(error) => self.notify(error),
                    }
                }
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

                Action::ToggleFav(t) => {
                    if t.is_netease() {
                        continue;
                    }
                    if let FavTarget::Playlist(p) = &t {
                        if self.lib.is_mine(&p.uuid) {
                            self.notify("这是你自己创建的歌单");
                            continue;
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
            "add" => {
                let track = serde_json::from_value(body["track"].clone()).map_err(|_| "曲目信息无效")?;
                if body["next"] == true { Action::PlayNext(vec![track]) } else { Action::Enqueue(vec![track]) }
            }
            _ => return Err("未知遥控操作".into()),
        };
        self.apply(vec![action]);
        Ok(())
    }

    fn relay_tick(&mut self) {
        if let Some(command) = self.relay.as_ref().and_then(crate::relay::Relay::recv) {
            let response = match self.relay_command(command.body) {
                Ok(()) => crate::relay::snapshot(&self.pb, self.api.is_authenticated()),
                Err(error) => serde_json::json!({"error": error}),
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
        };
        eframe::set_value(storage, eframe::APP_KEY, &p);
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.handle_events();
        self.tick_playback();
        self.relay_tick();
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
