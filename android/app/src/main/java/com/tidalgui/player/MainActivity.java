package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.media3.common.util.UnstableApi;
import com.tidalgui.player.LocalLibrary.LocalGroup;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

@UnstableApi
public final class MainActivity extends UiKit {
    final HomeScreens homeScreens = new HomeScreens(this);
    final NeteaseScreens neteaseScreens = new NeteaseScreens(this);
    final WebDavScreens webdavScreens = new WebDavScreens(this);
    final SmbScreens smbScreens = new SmbScreens(this);
    final LocalLibraryScreens localScreens = new LocalLibraryScreens(this);
    final CatalogueScreens catalogueScreens = new CatalogueScreens(this);
    final DownloadScreens downloadScreens = new DownloadScreens(this);
    final TrackExtras trackExtras = new TrackExtras(this);
    final RemoteScreens remoteScreens = new RemoteScreens(this);
    final SettingsScreens settingsScreens = new SettingsScreens(this);
    final LoginFlow loginFlow = new LoginFlow(this);
    final ImportExport importExport = new ImportExport(this);
    final NowPlayingScreen nowPlaying = new NowPlayingScreen(this);

    static final int IMPORT_SESSION = 1;
    static final int IMPORT_AUDIO = 2;
    static final int IMPORT_FOLDER = 3;
    static final int INSTALL_PERMISSION = 4;
    static final int IMPORT_M3U = 5;
    static final int EXPORT_M3U = 6;
    static final int DOWNLOAD_DIRECTORY = 7;
    static final int JOB_DOWNLOAD_DIRECTORY = 8;
    DownloadChoice pendingDownloadChoice;
    String pendingDownloadTitle;
    int pendingDownloadQuality;
    Uri pendingDownloadDirectory;
    static final String OPEN_PLAYER = "com.tidalgui.player.OPEN_PLAYER";
    static final int LOCAL_PAGE_SIZE = 80;

    WebDavClient.Server webdavCurrentServer;
    String webdavCurrentPath = "";
    final List<WebDavClient.Item> webdavItems = new ArrayList<>();
    Page webdavBack = Page.SOURCES;

    SmbClient.Server smbCurrentServer;
    String smbCurrentPath = "";
    final List<SmbClient.Item> smbItems = new ArrayList<>();
    Page smbBack = Page.SOURCES;

    final Handler handler = new Handler(Looper.getMainLooper());
    static final ExecutorService downloads = Executors.newSingleThreadExecutor();
    final ExecutorService worker = Executors.newFixedThreadPool(3);
    final List<TidalClient.Track> tracks = new ArrayList<>();
    final List<TidalClient.Collection> collections = new ArrayList<>();
    final java.util.ArrayDeque<TidalClient.Collection> collectionTrail = new java.util.ArrayDeque<>();
    PlaybackService playback;
    boolean bound;
    final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { goBack(); }
    };
    Page page = Page.HOME;
    Page downloadBack = Page.HOME;
    DownloadTasks.OfflineAlbum offlineAlbum;
    DownloadTasks downloadTasks;
    boolean selectingDownloads;
    final java.util.Set<String> selectedDownloads = new java.util.LinkedHashSet<>();
    long downloadNextRefresh;
    Page playerBack = Page.HOME;
    static final String[] SQUARE_CATEGORIES = {"全部", "华语", "欧美", "流行", "摇滚", "民谣", "电子", "轻音乐"};
    Page collectionBack = Page.LIBRARY;
    TidalClient.Collection collection;
    String term = "";
    String input = "";
    String notice = "";
    int filter;
    int libraryFilter;

    static final String HOME_SOURCE = "home_source";
    SearchSource homeSource = SearchSource.LOCAL;
    SearchSource searchSource = SearchSource.LOCAL;
    final java.util.EnumMap<SearchSource, SearchState> searches = new java.util.EnumMap<>(SearchSource.class);
    boolean localBusy;
    String localScanSummary = "";
    String localPlaylist;
    LocalGroup localGroup;
    String exportPlaylist;
    int localSort;
    static final String[] LIBRARY_TABS = {"歌曲", "专辑", "歌单", "艺人", "专辑艺人", "流派", "年份"};
    static final String[] LIBRARY_SORTS = {"标题", "艺人", "专辑", "年份（新到旧）", "导入顺序"};
    int localTab;
    /** Items already built for the list that grows as the user scrolls. */
    Runnable loadMore;
    boolean loadingMore;
    int[] grown;
    RangeDrawer growDrawer;
    final android.util.SparseArray<View> indexAnchors = new android.util.SparseArray<>();
    ScrollView listScroll;
    android.widget.FrameLayout listStage;
    android.widget.FrameLayout miniTrack;
    View miniProgress;
    long visibleLocalRevision;
    int generation;
    int importGeneration;
    TextView relayStatus;
    Button checkUpdateButton;
    TextView updateStatus;
    org.json.JSONObject installManifest;
    Future<?> catalogue;
    boolean catalogueLoading;
    boolean dragging;
    boolean favoriteBusy;
    boolean keyboardHidden = true;
    LyricsDialog lyrics;
    final java.util.Set<Long> favoriteIds = new java.util.HashSet<>();
    final Runnable renderPlayer = nowPlaying::updatePlayer;

    LinearLayout root;
    LinearLayout mini;
    TextView miniTitle;
    TextView miniArtist;
    ImageView miniArt;
    android.widget.ImageButton miniPlay;
    TextView title;
    TextView subtitle;
    /** Now Playing: the album, which opens the album's songs; the artist line opens the artist. */
    TextView playerAlbum;
    TextView total;
    TextView message;
    ImageView cover;
    android.widget.ImageButton play;
    android.widget.ImageButton shuffle;
    android.widget.ImageButton repeat;
    android.widget.ImageButton favorite;
    Button playerDetails;
    Button playerQuality;
    Button playerLyrics;
    Button playerComments;
    TextView playerSubHeading;
    TextView playerNextTrack;
    TextView playerSpecTag;
    TextView playerSpecFormat;
    TextView playerSpecBitrate;
    TextView playerSpecEngine;
    TextView playerSpecPort;
    Button playerQueueBtn;
    SeekBar seek;
    long visibleTrack = Long.MIN_VALUE;
    int visibleQueueIndex = -2;
    long visibleQueueRevision = -1;
    String visibleRemoteLink = "";
    TidalClient visibleAccount;
    /** Downloaded songs and albums, read once per page so lists can mark them. */
    DownloadTasks.Downloaded downloaded;
    /** Remote control of this player account's other devices; null when signed out. */
    RemoteDevices devices;
    boolean started;
    String visibleRemoteTarget;
    String visibleDevices = "";
    String visibleRemoteQueue = "";
    String visibleRemoteCover;
    Page devicesBack = Page.CONNECTIONS;
    TextView playerHeading;
    /** Now Playing: lyrics shown in place of the cover, and whether lyrics mode is on. */
    android.widget.FrameLayout lyricsPane;
    boolean lyricsMode;
    /** Same tab numbers as {@link #LIBRARY_TABS}; {@link #REMOTE_RECENT} for recently played. */
    int remoteLibTab;
    String remoteLibQuery = "";
    String remoteLibPlaylist = "";
    String remoteLibGroup = "";
    String remoteLibGroupTitle = "";

    final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.listener = renderPlayer;
            if (getIntent().getBooleanExtra(OPEN_PLAYER, false) && playback.current() != null) {
                open(Page.PLAYER);
            } else {
                render();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            render();
        }
    };

    final Runnable tick = new Runnable() {
        @Override public void run() {
            nowPlaying.updatePosition();
            if (page == Page.CONNECTIONS && relayStatus != null && playback != null && playback.relay != null) {
                String status = playback.relay.status;
                if (!TextUtils.equals(relayStatus.getText(), status)) relayStatus.setText(status);
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (page == Page.DOWNLOADS && now >= downloadNextRefresh) {
                downloadNextRefresh = now + (visibleRemoteDownloads ? 2000 : 1000);
                downloadScreens.refreshDownloads();
            }
            if (now >= marksNextCheck) {
                marksNextCheck = now + 2000;
                downloadScreens.refreshLocalMarks();
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        artwork = new ArtworkLoader(this);
        downloadTasks = DownloadTasks.get(this);
        localSort = Math.max(0, Math.min(LIBRARY_SORTS.length - 1,
                getPreferences(MODE_PRIVATE).getInt("local_sort", 0)));
        String source = getPreferences(MODE_PRIVATE).getString(HOME_SOURCE, SearchSource.LOCAL.name());
        try {
            homeSource = SearchSource.valueOf(source);
        } catch (IllegalArgumentException ignored) {
            homeSource = SearchSource.LOCAL;
        }
        searchSource = homeSource;
        for (SearchSource entry : SearchSource.values()) searches.put(entry, new SearchState());
        if (state != null) exportPlaylist = state.getString("export_playlist");
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                keyboardHidden = ime == 0;
                nowPlaying.applyMiniVisibility();
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        getOnBackPressedDispatcher().addCallback(this, back);
        bound = bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND),
                playbackConnection, BIND_AUTO_CREATE);
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(OPEN_PLAYER, false) && playback != null && playback.current() != null) {
            open(Page.PLAYER);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        remoteScreens.syncDevices();
        if (devices != null) devices.resume();
        handler.post(tick);
    }

    @Override protected void onStop() {
        started = false;
        if (devices != null) devices.pause();
        handler.removeCallbacks(tick);
        if (playback != null) playback.saveProgress();
        super.onStop();
    }

    @Override protected void onDestroy() {
        loginFlow.cancelLogin();
        if (lyrics != null) lyrics.dismiss();
        if (devices != null) devices.close();
        if (playback != null && playback.listener == renderPlayer) playback.listener = null;
        if (bound) unbindService(playbackConnection);
        worker.shutdownNow();
        artwork.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void goBack() {
        switch (page) {
            case PLAYER: navigate(playerBack); break;
            case COLLECTION:
                if (collectionTrail.isEmpty()) navigate(collectionBack);
                else {
                    collection = collectionTrail.pop();
                    catalogueScreens.load(false);
                }
                break;
            case QUEUE: navigate(queueBack); break;
            case DEVICES: navigate(devicesBack); break;
            case OFFLINE:
                if (offlineAlbum != null) {
                    offlineAlbum = null;
                    render();
                } else navigate(Page.LOCAL);
                break;
            case DOWNLOADS:
                if (selectingDownloads) {
                    selectingDownloads = false;
                    selectedDownloads.clear();
                    downloadScreens.refreshDownloads();
                } else navigate(downloadBack);
                break;
            case LOCAL:
                if (remoteScreens.remoteLibInside()) remoteScreens.remoteLibLeave();
                else if (!remoteScreens.remoteMode() && (localPlaylist != null || localGroup != null)) {
                    localPlaylist = null;
                    localGroup = null;
                    render();
                } else navigate(Page.HOME);
                break;
            case LIBRARY: navigate(Page.SOURCES); break;
            case CONNECTIONS: navigate(Page.SETTINGS); break;
            case ACCOUNT: navigate(Page.SOURCES); break;
            case WEBDAV:
                if (webdavCurrentServer != null) {
                    if (webdavCurrentPath != null && !webdavCurrentPath.isEmpty() && !"/".equals(webdavCurrentPath)) {
                        String p = webdavCurrentPath.endsWith("/")
                                ? webdavCurrentPath.substring(0, webdavCurrentPath.length() - 1) : webdavCurrentPath;
                        int lastSlash = p.lastIndexOf('/');
                        webdavCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : "/";
                        webdavScreens.loadWebDavDirectory();
                    } else {
                        webdavCurrentServer = null;
                        webdavCurrentPath = "";
                        webdavItems.clear();
                        render();
                    }
                } else {
                    navigate(webdavBack);
                }
                break;
            case SMB:
                if (smbCurrentServer != null) {
                    String root = smbCurrentServer.rootUrl();
                    if (!root.endsWith("/")) root += "/";
                    String cur = smbCurrentPath != null ? smbCurrentPath : "";
                    if (!cur.endsWith("/")) cur += "/";

                    if (!cur.isEmpty() && !cur.equalsIgnoreCase(root) && !"/".equals(cur)) {
                        String p = cur.endsWith("/") ? cur.substring(0, cur.length() - 1) : cur;
                        int lastSlash = p.lastIndexOf('/');
                        smbCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : root;
                        smbScreens.loadSmbDirectory();
                    } else {
                        smbCurrentServer = null;
                        smbCurrentPath = "";
                        smbItems.clear();
                        render();
                    }
                } else {
                    navigate(smbBack);
                }
                break;
            case HOME: getOnBackPressedDispatcher().onBackPressed(); break;
            default: open(Page.HOME);
        }
    }

    Page queueBack = Page.HOME;

    void open(Page next) {
        if (page == next) return;
        if (next == Page.PLAYER) playerBack = page;
        if (next == Page.DOWNLOADS) {
            downloadBack = page;
            selectingDownloads = false;
            selectedDownloads.clear();
        }
        if (next == Page.QUEUE) {
            queueBack = page;
            revealQueue = true;
        }
        if (next == Page.DEVICES) devicesBack = page;
        navigate(next);
    }

    void navigate(Page next) {
        boolean refresh = (next == Page.SEARCH && searchSource != SearchSource.LOCAL) || next == Page.LIBRARY || next == Page.COLLECTION;
        if (refresh && next == Page.LIBRARY && page != Page.COLLECTION) libraryFilter = 0;
        if (next == Page.HOME) {
            generation++;
            if (catalogue != null) catalogue.cancel(true);
        }
        page = next;
        notice = "";
        if (refresh) catalogueScreens.load(false);
        else render();
    }

    void render() {
        remoteScreens.syncDevices();
        downloaded = downloadScreens.shownDownloads();
        markUpdates.clear();
        visibleRemoteTarget = devices == null ? null : devices.target;
        visibleDevices = remoteScreens.devicesSignature();
        visibleRemoteQueue = remoteScreens.remoteQueueSignature();
        visibleRemoteCover = null;
        back.setEnabled(page != Page.HOME);
        root.removeAllViews();
        checkUpdateButton = null;
        updateStatus = null;
        mini = null;
        miniProgress = null;
        miniTrack = null;
        loadMore = null;
        loadingMore = false;
        grown = null;
        growDrawer = null;
        indexAnchors.clear();
        listScroll = null;
        listStage = null;
        seek = null;
        dragging = false;
        visibleTrack = Long.MIN_VALUE;
        visibleLocalRevision = playback == null ? -1 : playback.localRevision;
        visibleQueueIndex = playback == null ? -1 : playback.index;
        visibleQueueRevision = playback == null ? -1 : playback.queueRevision;
        visibleRemoteLink = playback == null ? "" : playback.remoteLink();
        visibleAccount = remoteScreens.tidalApi();
        lyrics = null;
        lyricsPane = null;
        playerSubHeading = null;
        playerNextTrack = null;
        playerSpecTag = null;
        playerSpecFormat = null;
        playerSpecBitrate = null;
        playerSpecEngine = null;
        playerSpecPort = null;
        playerQueueBtn = null;
        if (page == Page.PLAYER) {
            nowPlaying.buildNowPlaying();
            nowPlaying.updatePlayer();
            if (lyricsMode) trackExtras.showLyrics();
            return;
        }

        LinearLayout header = row();
        header.setPadding(dp(16), dp(6), dp(16), dp(6));
        header.setMinimumHeight(dp(48));
        root.addView(header);
        boolean subpage = page == Page.OFFLINE || page == Page.NETEASE_PLAYLISTS || page == Page.NETEASE_SQUARE || page == Page.DOWNLOADS || page == Page.COLLECTION || page == Page.QUEUE || page == Page.SETTINGS
                || page == Page.CONNECTIONS || page == Page.ACCOUNT || page == Page.LIBRARY || page == Page.DEVICES
                || (page == Page.LOCAL && (remoteScreens.remoteMode() ? remoteScreens.remoteLibInside() : localPlaylist != null || localGroup != null));
        if (subpage) header.addView(iconButton("back", "返回", this::goBack));
        TextView heading = text(pageTitle(), subpage ? 18 : 22, INK, true);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (devices != null && (page == Page.HOME || page == Page.SOURCES || page == Page.LOCAL || page == Page.SEARCH) && !subpage) {
            // Which device the whole app controls, always in view on the main pages.
            boolean remote = remoteScreens.remoteMode();
            Button chip = withIcon(pill(remote ? devices.targetName() : "本机", true, remoteScreens::showDevicePicker),
                    "devices", remote ? BG : ACCENT);
            chip.setSingleLine();
            chip.setMaxWidth(dp(150));
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setContentDescription(remote ? "正在遥控 " + devices.targetName() + "，切换播放设备" : "切换播放设备");
            if (remote) {
                chip.setBackground(shape(ACCENT, 14));
                chip.setTextColor(BG);
            }
            LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(-2, dp(30));
            chipLayout.setMarginEnd(dp(4));
            header.addView(chip, chipLayout);
        }
        if (page == Page.HOME || page == Page.SOURCES) {
            header.addView(glyph("download", INK, 17, "下载管理", () -> open(Page.DOWNLOADS)),
                    new LinearLayout.LayoutParams(dp(34), dp(34)));
            header.addView(glyph("settings", INK, 17, "设置", () -> open(Page.SETTINGS)),
                    new LinearLayout.LayoutParams(dp(34), dp(34)));
        }
        if (page == Page.LOCAL && !subpage && !remoteScreens.remoteMode()) {
            header.addView(glyph("plus", INK, 18, "导入本地音乐", localScreens::importMenu),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            header.addView(glyph("more", INK, 18, "音乐库工具", localScreens::libraryTools),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        if (page == Page.SEARCH) buildSearch();
        if (remoteScreens.remoteMode()) remoteScreens.remoteBanner();

        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        listScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
            if (loadMore == null) return;
            View child = ((ScrollView) view).getChildAt(0);
            if (child != null && scrollY + view.getHeight() >= child.getHeight() - dp(480)) loadMore.run();
        });
        listStage = new android.widget.FrameLayout(this);
        listStage.addView(listScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(listStage, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column();
        content.setPadding(dp(20), dp(6), dp(20), dp(18));
        listScroll.addView(content);
        switch (page) {
            case HOME:
                buildHomeSources();
                if (homeSource == SearchSource.LOCAL) homeScreens.home();
                else homeScreens.sourceHome(homeSource);
                break;
            case LOCAL: localScreens.localMusic(); break;
            case SOURCES: neteaseScreens.sources(); break;
            case WEBDAV: webdavScreens.webdavPage(); break;
            case SMB: smbScreens.smbPage(); break;
            case NETEASE_PLAYLISTS:
            case NETEASE_SQUARE: neteaseScreens.neteasePlaylistPage(); break;
            case SEARCH:
                if (searchSource != SearchSource.LOCAL) catalogueScreens.catalogue();
                else localScreens.localResults(term);
                break;
            case LIBRARY:
            case COLLECTION: catalogueScreens.catalogue(); break;
            case QUEUE: queue(); break;
            case DEVICES: remoteScreens.devicesPage(); break;
            case SETTINGS: settingsScreens.settings(); break;
            case DOWNLOADS: downloadScreens.downloadsPage(); break;
            case OFFLINE: downloadScreens.offlinePage(); break;
            case CONNECTIONS: settingsScreens.connections(); break;
            case ACCOUNT: settingsScreens.tidalSettings(); break;
            default: break;
        }
        nowPlaying.buildMini();
        buildNav();
        root.requestApplyInsets();
        nowPlaying.updatePlayer();
        if (homeScrollRestore >= 0 && listScroll != null) {
            int y = homeScrollRestore;
            homeScrollRestore = -1;
            if (y > 0) listScroll.post(() -> listScroll.scrollTo(0, y));
        }
        remoteScreens.revealPlayingRow();
        if (revealQueue) {
            revealQueue = false;
            remoteScreens.revealTag(QUEUE_PLAYING);
        }
    }

    String pageTitle() {
        switch (page) {
            case HOME: return homeSource.homeTitle();
            case SEARCH: return searchSource == SearchSource.LOCAL ? "搜索" : searchSource.label + "搜索";
            case LOCAL: if (remoteScreens.remoteMode()) return remoteScreens.remoteLibInside()
                    ? (remoteLibGroup.isEmpty() ? remoteLibPlaylist : remoteLibGroupTitle)
                    : devices.targetName() + " 的音乐库";
                return localPlaylist != null ? localPlaylist : localGroup != null ? localGroup.label : "音乐库";
            case SOURCES: return "音源";
            case WEBDAV: return webdavScreens.webdavTitle();
            case SMB: return smbScreens.smbTitle();
            case NETEASE_PLAYLISTS: return "我的歌单";
            case NETEASE_SQUARE: return "歌单广场";
            case LIBRARY: return "TIDAL 收藏";
            case COLLECTION: return collection.title;
            case QUEUE: return "播放队列";
            case SETTINGS: return "设置";
            case DOWNLOADS: return "下载管理";
            case OFFLINE: return offlineAlbum == null ? "已下载" : offlineAlbum.title;
            case CONNECTIONS: return "设备与连接";
            case DEVICES: return "我的设备";
            case ACCOUNT: return "TIDAL 账号";
            default: return "听音室";
        }
    }

    void buildSearch() {
        LinearLayout bar = row();
        bar.setPadding(dp(16), 0, dp(16), dp(6));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setHint(searchSource != SearchSource.LOCAL ? "搜索 " + searchSource.label : "搜索本地歌曲、专辑或艺人");
        search.setBackground(shapeWithStroke(SURFACE, 0x1affffff, 1, 14));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setText(input);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { input = s.toString(); }
            @Override public void afterTextChanged(android.text.Editable editable) {}
        });
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Runnable submit = () -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            term = input.trim();
            if (searchSource != SearchSource.LOCAL) catalogueScreens.load(false);
            else render();
        };
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(action("搜索", "提交搜索", submit));
        root.addView(bar);
        if (focusSearch) {
            focusSearch = false;
            search.setSelection(search.getText().length());
            search.requestFocus();
            search.post(() -> {
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            });
        }
        LinearLayout scopes = row();
        scopes.setPadding(dp(20), 0, dp(20), 0);
        for (SearchSource source : SearchSource.values()) {
            scopes.addView(pill(source.label, searchSource == source, () -> openSearch(source)));
        }
        root.addView(scopes);
    }

    void switchSearchSource(SearchSource source) {
        if (searchSource == source) return;
        SearchState previous = searches.get(searchSource);
        previous.input = input;
        previous.term = term;
        previous.filter = filter;
        searchSource = source;
        SearchState next = searches.get(source);
        input = next.input;
        term = next.term;
        filter = next.filter;
    }

    boolean focusSearch;

    void openSearch(SearchSource source) {
        boolean changed = searchSource != source;
        switchSearchSource(source);
        if (page != Page.SEARCH) {
            focusSearch = true;
            open(Page.SEARCH);
        } else if (changed) {
            focusSearch = true;
            if (source != SearchSource.LOCAL) catalogueScreens.load(false);
            else {
                generation++;
                if (catalogue != null) catalogue.cancel(true);
                render();
            }
        }
    }

    void enterSource(SearchSource source) {
        homeSource = source;
        getPreferences(MODE_PRIVATE).edit().putString(HOME_SOURCE, source.name()).apply();
        switchSearchSource(source);
        navigate(Page.HOME);
    }

    void buildHomeSources() {
        LinearLayout selector = row();
        selector.setPadding(0, 0, 0, dp(12));
        for (SearchSource source : SearchSource.values()) {
            Button tab = pill(source.label, homeSource == source, () -> enterSource(source));
            tab.setContentDescription("切换到" + source.homeTitle() + "首页");
            tab.setSelected(homeSource == source);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (selector.getChildCount() > 0) params.setMarginStart(dp(6));
            selector.addView(tab, params);
        }
        content.addView(selector);
    }

    void buildNav() {
        LinearLayout nav = row();
        nav.setPadding(dp(8), dp(2), dp(8), dp(2));
        nav.setBackgroundColor(BG);
        Page[] pages = {Page.HOME, Page.LOCAL, Page.SEARCH, Page.SOURCES};
        String[] labels = {"首页", "曲库", "搜索", "服务"};
        String[] icons = {"home", "library", "search", "settings"};
        for (int i = 0; i < pages.length; i++) {
            Page target = pages[i];
            boolean selected = page == target || (target == Page.SOURCES
                    && (page == Page.LIBRARY || (page == Page.COLLECTION && collectionBack == Page.LIBRARY)));
            Button tab = action(labels[i], labels[i], () -> {
                if (target == Page.SEARCH) {
                    if (page == Page.SEARCH) render();
                    else openSearch(homeSource);
                    return;
                }
                if (target == Page.LOCAL) {
                    localPlaylist = null;
                    localGroup = null;
                }
                if (target == page) render();
                else open(target);
            });
            int color = selected ? GOLD : MUTED;
            tab.setTextColor(color);
            tab.setTextSize(10);
            tab.setCompoundDrawablesWithIntrinsicBounds(null, new PlayerIcon(icons[i], color, dp(16)), null, null);
            tab.setCompoundDrawablePadding(dp(2));
            tab.setPadding(0, dp(4), 0, dp(2));
            if (selected) tab.setBackground(shapeWithStroke(RAISED, 0x22d4a359, 1, 10));
            nav.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        root.addView(nav);
    }
    /** Scroll position to keep when a home section arrives and the page is rebuilt. */
    int homeScrollRestore = -1;
    /** Track to bring into view after opening its album or artist from Now Playing. */
    long revealTrackId = Long.MIN_VALUE;
    /** Set when the queue page opens, so that render lands on the playing row. */
    boolean revealQueue;
    static final Object QUEUE_PLAYING = new Object();

    /** What the controlled device has saved, so lists here mark its downloads rather than this phone's. */
    DownloadTasks.Downloaded remoteMarks;

    /** Views showing download marks on this page, updated in place when the marks change. */
    final List<Runnable> markUpdates = new ArrayList<>();

    long marksNextCheck;
    String remoteMarksKey = "";
    boolean remoteMarksBusy;

    /** The controlled device's latest download list, while the downloads page shows it. */
    JSONObject remoteDownloadList;
    /** Rows asked of the controlled device; "显示更多" raises it. */
    int remoteDownloadLimit = 300;
    boolean visibleRemoteDownloads;

    void share(TidalClient.Track track) {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, track.title + " · " + track.artist
                        + "\n" + (track.isNetease() ? NeteaseClient.songLink(track.id) : "https://tidal.com/browse/track/" + track.id));
        startActivity(Intent.createChooser(share, "分享歌曲"));
    }

    void changeFavorite(TidalClient.Track track, boolean add) {
        TidalClient api = remoteScreens.tidalApi();
        if (playback == null || !track.needsTidal() || api == null || favoriteBusy) return;
        favoriteBusy = true;
        nowPlaying.updatePlayer();
        worker.submit(() -> {
            try {
                api.favorite(track.id, add);
                runOnUiThread(() -> {
                    if (isDestroyed() || playback == null || remoteScreens.tidalApi() != api) return;
                    favoriteBusy = false;
                    if (add) favoriteIds.add(track.id); else favoriteIds.remove(track.id);
                    toast(add ? "已收藏" : "已取消收藏");
                    if (page == Page.LIBRARY && libraryFilter == 0) catalogueScreens.load(false);
                    else nowPlaying.updatePlayer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || playback == null || remoteScreens.tidalApi() != api) return;
                    favoriteBusy = false;
                    toast("收藏更新失败 · " + readable(e));
                    nowPlaying.updatePlayer();
                });
            }
        });
    }

    void queue() {
        if (remoteScreens.remoteMode()) {
            remoteScreens.remoteQueue();
            return;
        }
        if (devices != null) content.addView(menuRow("本机 · 切换播放设备", "切换播放设备", remoteScreens::showDevicePicker));
        if (playback == null || playback.queue.isEmpty()) {
            empty("队列是空的", "从歌曲菜单加入歌曲，或直接播放一张专辑");
            return;
        }
        section(playback.queue.size() + " 首歌曲");
        catalogueScreens.showGrowing(playback.queue.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                int at = i;
                TidalClient.Track track = playback.queue.get(at);
                boolean playingNow = i == playback.index;
                LinearLayout entry = entry(displayTitle(track),
                        playingNow ? "正在播放" + (track.artist.isEmpty() ? "" : " · " + track.artist) : track.artist,
                        track.cover, () -> playback.playAt(at, 0, true));
                artwork.load((ImageView) entry.getChildAt(0), track, 160);
                if (playingNow) {
                    entry.setTag(QUEUE_PLAYING);
                    entry.setBackground(shape(RAISED, 12));
                    LinearLayout info = (LinearLayout) entry.getChildAt(1);
                    ((TextView) info.getChildAt(0)).setTextColor(ACCENT);
                    ((TextView) info.getChildAt(1)).setTextColor(ACCENT);
                }
                entry.addView(iconButton("more", "调整队列 " + track.title, () -> remoteScreens.editQueue(at, track)));
                content.addView(entry);
            }
        });
        if (revealQueue && playback.index >= LOCAL_PAGE_SIZE) catalogueScreens.fillTo(playback.index);
    }

    /** While controlling another device, the volume keys change that device's media volume. */
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        boolean volumeKey = code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        RemoteDevices.State state = remoteScreens.remoteMode() ? devices.state : null;
        if (!volumeKey || state == null || state.volume < 0) return super.dispatchKeyEvent(event);
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int step = code == android.view.KeyEvent.KEYCODE_VOLUME_UP ? 7 : -7;
            int volume = Math.max(0, Math.min(100, state.volume + step));
            try {
                devices.send(new JSONObject().put("op", "volume").put("value", volume));
            } catch (org.json.JSONException ignored) {}
            if (message != null && seek != null) message.setText(devices.targetName() + " 音量 " + volume);
            else toast(devices.targetName() + " 音量 " + volume);
        }
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (exportPlaylist != null) state.putString("export_playlist", exportPlaylist);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_PERMISSION) {
            org.json.JSONObject offer = installManifest;
            installManifest = null;
            if (offer == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
                settingsScreens.downloadUpdate(offer);
            } else {
                settingsScreens.updateState(false, "安装权限尚未开启，可以稍后再更新。");
            }
            return;
        }
        if (request == EXPORT_M3U) {
            String playlist = exportPlaylist;
            exportPlaylist = null;
            if (result == RESULT_OK && data != null && data.getData() != null) importExport.exportM3u(data.getData(), playlist);
            return;
        }
        if (request == JOB_DOWNLOAD_DIRECTORY) {
            DownloadChoice callback = pendingDownloadChoice;
            pendingDownloadChoice = null;
            if (callback == null) return;
            Uri directory = pendingDownloadDirectory;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                try {
                    int mask = Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    int flags = data.getFlags() & mask;
                    if (flags != mask) throw new SecurityException();
                    getContentResolver().takePersistableUriPermission(data.getData(), flags);
                    directory = data.getData();
                } catch (SecurityException | IllegalArgumentException error) {
                    toast("无法保存此目录的读写权限");
                }
            }
            downloadScreens.downloadChoices(pendingDownloadTitle, pendingDownloadQuality, directory, callback);
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == DOWNLOAD_DIRECTORY && data.getData() != null) {
            Uri directory = data.getData();
            try {
                android.provider.DocumentsContract.getTreeDocumentId(directory);
                int permission = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                if ((data.getFlags() & permission) != permission) throw new SecurityException();
                getContentResolver().takePersistableUriPermission(directory, permission);
                getSharedPreferences("tidal", MODE_PRIVATE).edit()
                        .putString(MusicDownload.DIRECTORY, directory.toString()).apply();
                render();
            } catch (SecurityException | IllegalArgumentException error) {
                toast("无法保存此目录的写入权限，请重新选择");
            }
            return;
        }
        if (request == IMPORT_M3U && data.getData() != null) {
            importExport.importM3u(data.getData());
            return;
        }
        if (request == IMPORT_AUDIO) {
            importExport.importAudio(data);
            return;
        }
        if (request == IMPORT_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            int granted = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                granted |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            try {
                getContentResolver().takePersistableUriPermission(tree, granted);
                importExport.scanDirectory(tree, true);
            } catch (SecurityException problem) {
                toast("无法保存此目录的读取权限");
            }
            return;
        }
        if (request != IMPORT_SESSION || data.getData() == null) return;
        Uri uri = data.getData();
        int version = ++importGeneration;
        worker.submit(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法打开会话文件");
                TidalClient.Session session = new TidalClient.Session(TidalClient.readText(input, 65536));
                String saved = session.toJson();
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration || playback == null) return;
                    loginFlow.finishSignIn(session, saved, "已导入 TIDAL 会话");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && version == importGeneration) {
                        new AlertDialog.Builder(this).setTitle("导入失败").setMessage(readable(error))
                                .setPositiveButton("确定", null).show();
                    }
                });
            }
        });
    }
}
