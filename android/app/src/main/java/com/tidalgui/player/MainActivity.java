package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.tidalgui.player.LocalLibrary.LocalGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.BORDER;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

@UnstableApi
public final class MainActivity extends ComponentActivity {
    private static final int IMPORT_SESSION = 1;
    private static final int IMPORT_AUDIO = 2;
    private static final int IMPORT_FOLDER = 3;
    private static final int INSTALL_PERMISSION = 4;
    private static final int IMPORT_M3U = 5;
    private static final int EXPORT_M3U = 6;
    private static final int DOWNLOAD_DIRECTORY = 7;
    private static final int JOB_DOWNLOAD_DIRECTORY = 8;
    private interface DownloadChoice { void chosen(int quality, Uri directory); }
    private interface RangeDrawer { void draw(int from, int to); }
    private DownloadChoice pendingDownloadChoice;
    private String pendingDownloadTitle;
    private int pendingDownloadQuality;
    private Uri pendingDownloadDirectory;
    static final String OPEN_PLAYER = "com.tidalgui.player.OPEN_PLAYER";
    private static final int PAGE_SIZE = 50;
    private static final int LOCAL_PAGE_SIZE = 80;

    private enum Page { HOME, LOCAL, SEARCH, SOURCES, LIBRARY, COLLECTION, QUEUE, PLAYER, SETTINGS, CONNECTIONS, ACCOUNT, DOWNLOADS, NETEASE_PLAYLISTS, NETEASE_SQUARE, OFFLINE, DEVICES, WEBDAV, SMB }

    private WebDavClient.Server webdavCurrentServer;
    private String webdavCurrentPath = "";
    private final List<WebDavClient.Item> webdavItems = new ArrayList<>();
    private boolean webdavLoading;
    private String webdavError = "";
    private Page webdavBack = Page.SOURCES;

    private SmbClient.Server smbCurrentServer;
    private String smbCurrentPath = "";
    private final List<SmbClient.Item> smbItems = new ArrayList<>();
    private boolean smbLoading;
    private String smbError = "";
    private Page smbBack = Page.SOURCES;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private ArtworkLoader artwork;
    private final List<TidalClient.Track> tracks = new ArrayList<>();
    private final List<TidalClient.Collection> collections = new ArrayList<>();
    private final java.util.ArrayDeque<TidalClient.Collection> collectionTrail = new java.util.ArrayDeque<>();
    private PlaybackService playback;
    private boolean bound;
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { goBack(); }
    };
    private Page page = Page.HOME;
    private Page downloadBack = Page.HOME;
    private DownloadTasks.OfflineAlbum offlineAlbum;
    private final java.util.Set<String> expandedDownloads = new java.util.HashSet<>();
    private DownloadTasks downloadTasks;
    private LinearLayout downloadRows;
    private boolean downloadRefreshPending;
    private String downloadSignature;
    private int downloadFilter;
    private boolean selectingDownloads;
    private final java.util.Set<String> selectedDownloads = new java.util.LinkedHashSet<>();
    private long downloadNextRefresh;
    private Page playerBack = Page.HOME;
    private static final String[] SQUARE_CATEGORIES = {"全部", "华语", "欧美", "流行", "摇滚", "民谣", "电子", "轻音乐"};
    private final List<TidalClient.Collection> neteaseCreated = new ArrayList<>();
    private final List<TidalClient.Collection> neteaseCollected = new ArrayList<>();
    private String squareCategory = SQUARE_CATEGORIES[0];
    private boolean showCollected;
    private boolean neteasePlaylistLoading;
    private boolean neteasePlaylistMore;
    private int neteasePlaylistOffset;
    private long neteasePlaylistUser;
    private int neteasePlaylistGeneration;
    private String neteasePlaylistNotice = "";
    private Page collectionBack = Page.LIBRARY;
    private TidalClient.Collection collection;
    private ArtistDetails artistDetails;
    private boolean showAllArtistTracks;

    private static final class ArtistDetails {
        final TidalClient.Collection artist;
        final List<TidalClient.Track> top;
        final List<TidalClient.Collection> albums;
        final List<TidalClient.Collection> singles;
        final List<TidalClient.Collection> similar;
        final String biography;

        ArtistDetails(TidalClient api, TidalClient.Collection selected) throws Exception {
            String id = selected.id;
            artist = optional(() -> api.artist(id), selected);
            albums = optional(() -> api.artistReleases(id, false), java.util.Collections.emptyList());
            singles = optional(() -> api.artistReleases(id, true), java.util.Collections.emptyList());
            similar = optional(() -> api.similarArtists(id), java.util.Collections.emptyList());
            biography = optional(() -> api.artistBiography(id), "");
            if (albums.isEmpty() && singles.isEmpty() && similar.isEmpty() && biography.isEmpty()) {
                top = api.collectionTracks(selected, 0);
            } else {
                top = optional(() -> api.collectionTracks(selected, 0), java.util.Collections.emptyList());
            }
        }

        private static <T> T optional(java.util.concurrent.Callable<T> request, T fallback) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                return request.call();
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception unavailable) {
                return fallback;
            }
        }
    }
    private String term = "";
    private String input = "";
    private String notice = "";
    private int filter;
    private int libraryFilter;
    private enum SearchSource {
        LOCAL("本地音乐"), TIDAL("TIDAL"), NETEASE("网易云");
        final String label;
        SearchSource(String label) { this.label = label; }

        String homeTitle() {
            switch (this) {
                case TIDAL: return "TIDAL";
                case NETEASE: return "网易云音乐";
                default: return "听音室";
            }
        }

        boolean includes(TidalClient.Track track) {
            switch (this) {
                case TIDAL: return track.needsTidal();
                case NETEASE: return track.isNetease();
                default: return track.isLocal();
            }
        }
    }

    private static final String HOME_SOURCE = "home_source";
    private SearchSource homeSource = SearchSource.LOCAL;
    private SearchSource searchSource = SearchSource.LOCAL;
    private final java.util.EnumMap<SearchSource, SearchState> searches = new java.util.EnumMap<>(SearchSource.class);

    private static final class SearchState {
        String input = "";
        String term = "";
        int filter;
    }
    private boolean localBusy;
    private String localScanSummary = "";
    private String localPlaylist;
    private LocalGroup localGroup;
    private String exportPlaylist;
    private int localSort;
    private static final String[] LIBRARY_TABS = {"歌曲", "专辑", "歌单", "艺人", "专辑艺人", "流派", "年份"};
    private static final String[] LIBRARY_SORTS = {"标题", "艺人", "专辑", "年份（新到旧）", "导入顺序"};
    private int localTab;
    /** Items already built for the list that grows as the user scrolls. */
    private Runnable loadMore;
    private boolean loadingMore;
    private int[] grown;
    private int growTotal;
    private RangeDrawer growDrawer;
    private final android.util.SparseArray<View> indexAnchors = new android.util.SparseArray<>();
    private ScrollView listScroll;
    private android.widget.FrameLayout listStage;
    private android.widget.FrameLayout miniTrack;
    private View miniProgress;
    private static final java.util.regex.Pattern YEAR_KEY = java.util.regex.Pattern.compile("(19|20)\\d{2}");
    private static Object nameLatin;
    private long visibleLocalRevision;
    private int generation;
    private int importGeneration;
    private Future<?> loginTask;
    private AlertDialog loginDialog;
    private TextView relayStatus;
    private boolean updateBusy;
    private Button checkUpdateButton;
    private TextView updateStatus;
    private String updateMessage = "";
    private org.json.JSONObject installManifest;
    private Future<?> catalogue;
    private boolean hasMore;
    private boolean catalogueLoading;
    private boolean dragging;
    private boolean favoriteBusy;
    private boolean keyboardHidden = true;
    private LyricsDialog lyrics;
    private final java.util.Set<Long> favoriteIds = new java.util.HashSet<>();
    private final Runnable renderPlayer = this::updatePlayer;

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout mini;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniArt;
    private android.widget.ImageButton miniPlay;
    private TextView title;
    private TextView subtitle;
    /** Now Playing: the album, which opens the album's songs; the artist line opens the artist. */
    private TextView playerAlbum;
    private TextView time;
    private TextView total;
    private TextView message;
    private ImageView cover;
    private android.widget.ImageButton play;
    private android.widget.ImageButton shuffle;
    private android.widget.ImageButton repeat;
    private android.widget.ImageButton favorite;
    private Button playerDetails;
    private Button playerQuality;
    private Button playerLyrics;
    private Button playerComments;
    private TextView playerSubHeading;
    private TextView playerNextTrack;
    private TextView playerSpecTag;
    private TextView playerSpecFormat;
    private TextView playerSpecBitrate;
    private TextView playerSpecEngine;
    private TextView playerSpecPort;
    private Button playerQueueBtn;
    private SeekBar seek;
    private long visibleTrack = Long.MIN_VALUE;
    private int visibleQueueIndex = -2;
    private long visibleQueueRevision = -1;
    private String visibleRemoteLink = "";
    private TidalClient visibleAccount;
    /** Downloaded songs and albums, read once per page so lists can mark them. */
    private DownloadTasks.Downloaded downloaded;
    /** Remote control of this player account's other devices; null when signed out. */
    private RemoteDevices devices;
    private String devicesKey = "";
    private boolean started;
    private String visibleRemoteTarget;
    private String visibleDevices = "";
    private String visibleRemoteQueue = "";
    private String visibleRemoteCover;
    private Page devicesBack = Page.CONNECTIONS;
    private TextView playerHeading;
    /** Now Playing: lyrics shown in place of the cover, and whether lyrics mode is on. */
    private android.widget.FrameLayout lyricsPane;
    private boolean lyricsMode;
    private Button playerDevices;
    /** The controlled device's music library, shown in place of this phone's while controlling it. */
    private final List<JSONObject> remoteLibTracks = new ArrayList<>();
    private final List<String> remoteLibPlaylists = new ArrayList<>();
    private String remoteLibKey = "";
    private boolean remoteLibLoading;
    private String remoteLibError = "";
    private int remoteLibNext = -1;
    /** Same tab numbers as {@link #LIBRARY_TABS}; {@link #REMOTE_RECENT} for recently played. */
    private int remoteLibTab;
    private static final int REMOTE_RECENT = -1;
    private String remoteLibQuery = "";
    private String remoteLibPlaylist = "";
    private final List<JSONObject> remoteLibGroups = new ArrayList<>();
    private String remoteLibGroup = "";
    private String remoteLibGroupTitle = "";
    private int remoteLibSort;

    private final ServiceConnection playbackConnection = new ServiceConnection() {
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

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updatePosition();
            if (page == Page.CONNECTIONS && relayStatus != null && playback != null && playback.relay != null) {
                String status = playback.relay.status;
                if (!TextUtils.equals(relayStatus.getText(), status)) relayStatus.setText(status);
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (page == Page.DOWNLOADS && now >= downloadNextRefresh) {
                downloadNextRefresh = now + (visibleRemoteDownloads ? 2000 : 1000);
                refreshDownloads();
            }
            if (now >= marksNextCheck) {
                marksNextCheck = now + 2000;
                refreshLocalMarks();
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
                applyMiniVisibility();
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
        syncDevices();
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
        cancelLogin();
        if (lyrics != null) lyrics.dismiss();
        if (devices != null) devices.close();
        if (playback != null && playback.listener == renderPlayer) playback.listener = null;
        if (bound) unbindService(playbackConnection);
        worker.shutdownNow();
        artwork.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void goBack() {
        switch (page) {
            case PLAYER: navigate(playerBack); break;
            case COLLECTION:
                if (collectionTrail.isEmpty()) navigate(collectionBack);
                else {
                    collection = collectionTrail.pop();
                    load(false);
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
                    refreshDownloads();
                } else navigate(downloadBack);
                break;
            case LOCAL:
                if (remoteLibInside()) remoteLibLeave();
                else if (!remoteMode() && (localPlaylist != null || localGroup != null)) {
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
                        loadWebDavDirectory();
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
                        loadSmbDirectory();
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

    private Page queueBack = Page.HOME;

    private void open(Page next) {
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

    private void navigate(Page next) {
        boolean refresh = (next == Page.SEARCH && searchSource != SearchSource.LOCAL) || next == Page.LIBRARY || next == Page.COLLECTION;
        if (refresh && next == Page.LIBRARY && page != Page.COLLECTION) libraryFilter = 0;
        if (next == Page.HOME) {
            generation++;
            if (catalogue != null) catalogue.cancel(true);
        }
        page = next;
        notice = "";
        if (refresh) load(false);
        else render();
    }

    private void render() {
        syncDevices();
        downloaded = shownDownloads();
        markUpdates.clear();
        visibleRemoteTarget = devices == null ? null : devices.target;
        visibleDevices = devicesSignature();
        visibleRemoteQueue = remoteQueueSignature();
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
        visibleAccount = tidalApi();
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
            buildNowPlaying();
            updatePlayer();
            if (lyricsMode) showLyrics();
            return;
        }

        LinearLayout header = row();
        header.setPadding(dp(16), dp(6), dp(16), dp(6));
        header.setMinimumHeight(dp(48));
        root.addView(header);
        boolean subpage = page == Page.OFFLINE || page == Page.NETEASE_PLAYLISTS || page == Page.NETEASE_SQUARE || page == Page.DOWNLOADS || page == Page.COLLECTION || page == Page.QUEUE || page == Page.SETTINGS
                || page == Page.CONNECTIONS || page == Page.ACCOUNT || page == Page.LIBRARY || page == Page.DEVICES
                || (page == Page.LOCAL && (remoteMode() ? remoteLibInside() : localPlaylist != null || localGroup != null));
        if (subpage) header.addView(iconButton("back", "返回", this::goBack));
        TextView heading = text(pageTitle(), subpage ? 18 : 22, INK, true);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (devices != null && (page == Page.HOME || page == Page.SOURCES || page == Page.LOCAL || page == Page.SEARCH) && !subpage) {
            // Which device the whole app controls, always in view on the main pages.
            boolean remote = remoteMode();
            Button chip = withIcon(pill(remote ? devices.targetName() : "本机", true, this::showDevicePicker),
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
        if (page == Page.LOCAL && !subpage && !remoteMode()) {
            header.addView(glyph("plus", INK, 18, "导入本地音乐", this::importMenu),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            header.addView(glyph("more", INK, 18, "音乐库工具", this::libraryTools),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        if (page == Page.SEARCH) buildSearch();
        if (remoteMode()) remoteBanner();

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
                if (homeSource == SearchSource.LOCAL) home();
                else sourceHome(homeSource);
                break;
            case LOCAL: localMusic(); break;
            case SOURCES: sources(); break;
            case WEBDAV: webdavPage(); break;
            case SMB: smbPage(); break;
            case NETEASE_PLAYLISTS:
            case NETEASE_SQUARE: neteasePlaylistPage(); break;
            case SEARCH:
                if (searchSource != SearchSource.LOCAL) catalogue();
                else localResults(term);
                break;
            case LIBRARY:
            case COLLECTION: catalogue(); break;
            case QUEUE: queue(); break;
            case DEVICES: devicesPage(); break;
            case SETTINGS: settings(); break;
            case DOWNLOADS: downloadsPage(); break;
            case OFFLINE: offlinePage(); break;
            case CONNECTIONS: connections(); break;
            case ACCOUNT: tidalSettings(); break;
            default: break;
        }
        buildMini();
        buildNav();
        root.requestApplyInsets();
        updatePlayer();
        if (homeScrollRestore >= 0 && listScroll != null) {
            int y = homeScrollRestore;
            homeScrollRestore = -1;
            if (y > 0) listScroll.post(() -> listScroll.scrollTo(0, y));
        }
        revealPlayingRow();
        if (revealQueue) {
            revealQueue = false;
            revealTag(QUEUE_PLAYING);
        }
    }

    private String pageTitle() {
        switch (page) {
            case HOME: return homeSource.homeTitle();
            case SEARCH: return searchSource == SearchSource.LOCAL ? "搜索" : searchSource.label + "搜索";
            case LOCAL: if (remoteMode()) return remoteLibInside()
                    ? (remoteLibGroup.isEmpty() ? remoteLibPlaylist : remoteLibGroupTitle)
                    : devices.targetName() + " 的音乐库";
                return localPlaylist != null ? localPlaylist : localGroup != null ? localGroup.label : "音乐库";
            case SOURCES: return "音源";
            case WEBDAV: return webdavTitle();
            case SMB: return smbTitle();
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

    private void buildSearch() {
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
            if (searchSource != SearchSource.LOCAL) load(false);
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

    private void switchSearchSource(SearchSource source) {
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

    private boolean focusSearch;

    private void openSearch(SearchSource source) {
        boolean changed = searchSource != source;
        switchSearchSource(source);
        if (page != Page.SEARCH) {
            focusSearch = true;
            open(Page.SEARCH);
        } else if (changed) {
            focusSearch = true;
            if (source != SearchSource.LOCAL) load(false);
            else {
                generation++;
                if (catalogue != null) catalogue.cancel(true);
                render();
            }
        }
    }

    private void enterSource(SearchSource source) {
        homeSource = source;
        getPreferences(MODE_PRIVATE).edit().putString(HOME_SOURCE, source.name()).apply();
        switchSearchSource(source);
        navigate(Page.HOME);
    }

    private void buildHomeSources() {
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

    private void buildNav() {
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

    private void home() {
        if (remoteMode()) {
            remoteHome();
            return;
        }
        LocalLibrary library = playback == null ? null : playback.localLibrary;
        List<TidalClient.Track> recent = playback == null ? new ArrayList<>() : new ArrayList<>(playback.history);
        int songs = library == null ? 0 : library.size();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(6));
        head.addView(text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(action("搜索", "搜索本地音乐", () -> openSearch(SearchSource.LOCAL)));
        content.addView(head);
        if (songs == 0 && recent.isEmpty()) {
            content.addView(primary("导入音乐", this::importMenu), new LinearLayout.LayoutParams(-2, dp(40)));
        }

        trackShelf("最近播放", recent.isEmpty() ? null : "播放全部",
                recent.isEmpty() ? null : () -> playList(recent, 0, false), recent);
        if (library != null && songs > 0) {
            groupShelf("专辑", () -> openLocalLibrary(1), localAlbums(library, recent));
            groupShelf("歌单", () -> openLocalLibrary(2), localPlaylists(library));
        }
        if (!hasPlayerAccount()) {
            LinearLayout sync = row();
            sync.setPadding(dp(14), dp(12), dp(10), dp(12));
            sync.setBackground(shape(SURFACE, 15));
            ImageView symbol = new ImageView(this);
            symbol.setImageDrawable(new PlayerIcon("user", ACCENT, dp(24)));
            sync.addView(symbol, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView about = text("登录播放器账号，在电脑和手机之间同步登录、遥控播放。", 13, MUTED, false);
            about.setPadding(dp(12), 0, dp(8), 0);
            sync.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
            sync.addView(secondary("登录", this::playerAccountLogin), new LinearLayout.LayoutParams(-2, dp(38)));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.topMargin = dp(12);
            content.addView(sync, gap);
        }
    }

    private List<LocalLibrary.LocalGroup> localAlbums(LocalLibrary library, List<TidalClient.Track> recent) {
        java.util.Map<String, LocalLibrary.LocalGroup> grouped = new java.util.LinkedHashMap<>();
        for (TidalClient.Track track : recent) rememberAlbum(grouped, track);
        if (grouped.size() < 12) {
            for (TidalClient.Track track : library.search("", null)) {
                rememberAlbum(grouped, track);
                if (grouped.size() >= 12) break;
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private static LocalLibrary.LocalGroup rememberAlbum(java.util.Map<String, LocalLibrary.LocalGroup> grouped,
                                                         TidalClient.Track track) {
        if (track == null || !track.isLocal()) return null;
        LocalLibrary.LocalGroup candidate = LocalLibrary.groupFor(track, 1);
        LocalLibrary.LocalGroup group = grouped.get(candidate.key);
        if (group == null) {
            if (grouped.size() >= 12) return null;
            group = candidate;
            grouped.put(group.key, group);
        }
        if (group.members.size() < 40) group.members.add(track);
        return group;
    }

    private List<LocalLibrary.LocalGroup> localPlaylists(LocalLibrary library) {
        List<LocalLibrary.LocalGroup> groups = new ArrayList<>();
        for (String name : library.playlists()) {
            if (groups.size() >= 12) break;
            List<TidalClient.Track> songs = library.search("", name);
            LocalLibrary.LocalGroup group = new LocalLibrary.LocalGroup(name, name, songs.size() + " 首", 2);
            group.members.addAll(songs);
            groups.add(group);
        }
        return groups;
    }

    private void groupShelf(String heading, Runnable openMore, List<LocalLibrary.LocalGroup> groups) {
        if (groups.isEmpty()) return;
        sectionLink(heading, openMore == null ? null : "全部", openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (LocalLibrary.LocalGroup group : groups) {
            LinearLayout card = column();
            card.setContentDescription(group.label);
            card.setOnClickListener(view -> {
                if (group.category == 2) {
                    localPlaylist = group.label;
                    localGroup = null;
                    localTab = 2;
                    if (page == Page.LOCAL) render();
                    else open(Page.LOCAL);
                } else {
                    openLocalGroup(group);
                }
            });
            ImageView cover = art(124);
            if (!group.members.isEmpty()) artwork.load(cover, group.members.get(0), 320);
            card.addView(cover, new LinearLayout.LayoutParams(dp(124), dp(124)));
            TextView name = single(group.label, 13, INK, true);
            name.setPadding(0, dp(6), 0, 0);
            card.addView(name);
            String caption = group.detail.isEmpty() ? group.members.size() + " 首" : group.detail;
            card.addView(single(caption, 11, MUTED, false));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(124), -2);
            cardParams.setMarginEnd(dp(10));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        content.addView(scroll);
    }

    private void openLocalLibrary(int tab) {
        localTab = tab;
        localPlaylist = null;
        localGroup = null;
        if (page == Page.LOCAL) render();
        else open(Page.LOCAL);
    }

    private List<?> personalKey;
    private List<TidalClient.Track> personalTracks = java.util.Collections.emptyList();
    private List<TidalClient.Track> personalLiked = java.util.Collections.emptyList();
    private List<TidalClient.Collection> personalPlaylists = java.util.Collections.emptyList();
    private List<TidalClient.Collection> personalSquare = java.util.Collections.emptyList();
    private List<TidalClient.Collection> personalAlbums = java.util.Collections.emptyList();
    private List<TidalClient.HomeShelf> personalDiscover = java.util.Collections.emptyList();
    private boolean personalPlaylistsForAccount;
    private boolean personalLoading;
    /** Scroll position to keep when a home section arrives and the page is rebuilt. */
    private int homeScrollRestore = -1;
    /** Track to bring into view after opening its album or artist from Now Playing. */
    private long revealTrackId = Long.MIN_VALUE;
    /** Set when the queue page opens, so that render lands on the playing row. */
    private boolean revealQueue;
    private static final Object QUEUE_PLAYING = new Object();

    private void loadPersonalHome(SearchSource source, NeteaseClient netease,
                                  android.content.SharedPreferences prefs) {
        TidalClient tidal = tidalApi();
        List<?> key = source == SearchSource.TIDAL ? java.util.Arrays.asList(source, tidal)
                : java.util.Arrays.asList(source, prefs.getString(NeteaseClient.SETTING, ""),
                        prefs.getString(NeteaseClient.SESSION, ""));
        if (key.equals(personalKey)) return;
        personalKey = key;
        personalTracks = java.util.Collections.emptyList();
        personalLiked = java.util.Collections.emptyList();
        personalPlaylists = java.util.Collections.emptyList();
        personalSquare = java.util.Collections.emptyList();
        personalAlbums = java.util.Collections.emptyList();
        personalDiscover = java.util.Collections.emptyList();
        personalPlaylistsForAccount = false;
        personalLoading = source == SearchSource.NETEASE || tidal != null;
        if (!personalLoading) return;

        worker.submit(() -> {
            if (source == SearchSource.TIDAL) {
                List<TidalClient.Track> picks = new ArrayList<>();
                List<TidalClient.Collection> playlists = new ArrayList<>();
                List<TidalClient.Collection> albums = new ArrayList<>();
                List<TidalClient.HomeShelf> discover = new ArrayList<>();
                addPersonalItems(picks, () -> tidal.favorites(0));
                addPersonalItems(albums, () -> tidal.collections("", TidalClient.Collection.Kind.ALBUM, 0, false));
                addPersonalItems(playlists, () -> tidal.collections("", TidalClient.Collection.Kind.PLAYLIST, 0, true));
                boolean ownPlaylists = !playlists.isEmpty();
                if (playlists.isEmpty()) {
                    addPersonalItems(playlists, () -> tidal.collections("", TidalClient.Collection.Kind.PLAYLIST, 0, false));
                }
                try {
                    discover.addAll(tidal.home());
                } catch (Exception ignored) {
                    // Favourite rows still render when the editorial home is unavailable.
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || personalKey != key) return;
                    personalTracks = picks;
                    personalPlaylists = playlists;
                    personalAlbums = albums;
                    personalDiscover = discover;
                    personalPlaylistsForAccount = ownPlaylists;
                    personalLoading = false;
                    if (page == Page.HOME && homeSource == source) render();
                });
                return;
            }
            boolean signedIn = netease.signedIn();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(signedIn ? 4 : 2);
        if (signedIn) {
            worker.submit(() -> {
                List<TidalClient.Track> rows = new ArrayList<>();
                addPersonalItems(rows, netease::dailyTracks);
                deliverHomeSection(key, source, pending, () -> personalTracks = rows);
            });
            worker.submit(() -> {
                List<TidalClient.Track> rows = new ArrayList<>();
                addPersonalItems(rows, netease::likedTracks);
                deliverHomeSection(key, source, pending, () -> personalLiked = rows);
            });
        }
        worker.submit(() -> {
            List<TidalClient.Collection> rows = new ArrayList<>();
            boolean own = false;
            if (signedIn) {
                addPersonalItems(rows, () -> netease.recommendedPlaylists(true));
                own = !rows.isEmpty();
            }
            if (rows.isEmpty()) addPersonalItems(rows, () -> netease.recommendedPlaylists(false));
            boolean account = own;
            deliverHomeSection(key, source, pending, () -> {
                personalPlaylists = rows;
                personalPlaylistsForAccount = account;
            });
        });
        worker.submit(() -> {
            List<TidalClient.Collection> rows = new ArrayList<>();
            addPersonalItems(rows, () -> {
                List<TidalClient.Collection> page = netease.playlistSquare("全部", 0).created;
                return page.size() > 12 ? new ArrayList<>(page.subList(0, 12)) : page;
            });
            deliverHomeSection(key, source, pending, () -> personalSquare = rows);
        });
        });
    }

    /** Show one NetEase home section as soon as it arrives, without waiting for the others. */
    private void deliverHomeSection(List<?> key, SearchSource source,
                                    java.util.concurrent.atomic.AtomicInteger pending, Runnable apply) {
        runOnUiThread(() -> {
            if (isDestroyed() || personalKey != key) return;
            apply.run();
            if (pending.decrementAndGet() == 0) personalLoading = false;
            if (page == Page.HOME && homeSource == source) {
                homeScrollRestore = listScroll == null ? 0 : listScroll.getScrollY();
                render();
            }
        });
    }

    private interface PersonalItems<T> { List<T> load() throws Exception; }

    private static <T> void addPersonalItems(List<T> destination, PersonalItems<T> source) {
        try {
            destination.addAll(source.load());
        } catch (Exception ignored) {
            // Other sections and local listening history remain available.
        }
    }

    private void neteaseHome() {
        android.content.SharedPreferences prefs = getSharedPreferences("tidal", MODE_PRIVATE);
        NeteaseClient netease = new NeteaseClient(prefs);
        boolean signedIn = netease.signedIn();
        loadPersonalHome(SearchSource.NETEASE, netease, prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (playback != null) {
            for (TidalClient.Track track : playback.history) {
                if (SearchSource.NETEASE.includes(track)) recent.add(track);
            }
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(6));
        head.addView(text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(action("搜索", "搜索网易云", () -> openSearch(SearchSource.NETEASE)));
        content.addView(head);
        if (!signedIn && netease.usesDefaultBackend()) {
            content.addView(primary("登录网易云", () -> showNeteaseLogin(netease)),
                    new LinearLayout.LayoutParams(-2, dp(40)));
        }
        if (personalLoading && personalTracks.isEmpty() && personalLiked.isEmpty()
                && personalPlaylists.isEmpty() && personalSquare.isEmpty()) {
            TextView waiting = text("正在读取每日推荐和歌单…", 13, MUTED, false);
            waiting.setPadding(0, dp(8), 0, dp(4));
            content.addView(waiting);
        }

        List<TidalClient.Track> daily = personalTracks;
        trackShelf("每日推荐", daily.isEmpty() ? null : "播放全部",
                daily.isEmpty() ? null : () -> playList(daily, 0, false), daily);
        if (!personalLoading && signedIn && daily.isEmpty()) homeNote("今天的每日推荐还没有返回");

        List<TidalClient.Track> liked = personalLiked;
        trackShelf("喜欢的歌", liked.isEmpty() ? null : "播放全部",
                liked.isEmpty() ? null : () -> playList(liked, 0, false), liked);
        if (!personalLoading && signedIn && liked.isEmpty()) homeNote("还没有红心歌曲，或喜欢列表暂时读不到");

        linkedShelf(personalPlaylistsForAccount ? "专属歌单" : "推荐歌单",
                () -> openNeteasePlaylists(Page.NETEASE_PLAYLISTS), personalPlaylists);
        linkedShelf("歌单广场", () -> openNeteasePlaylists(Page.NETEASE_SQUARE), personalSquare);

        if (!recent.isEmpty()) {
            section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                content.addView(trackEntry(recent.get(at), false, () -> playList(recent, at, false)));
            }
        }
    }

    private void linkedShelf(String heading, Runnable openMore, List<TidalClient.Collection> items) {
        if (items.isEmpty()) {
            if (!personalLoading) sectionLink(heading, "全部", openMore);
            return;
        }
        shelf(heading, "全部", openMore, items);
    }

    private void homeNote(String value) {
        TextView line = text(value, 13, MUTED, false);
        line.setPadding(dp(2), 0, 0, dp(8));
        content.addView(line);
    }

    private void tidalHome() {
        boolean signedIn = tidalApi() != null;
        android.content.SharedPreferences prefs = getSharedPreferences("tidal", MODE_PRIVATE);
        loadPersonalHome(SearchSource.TIDAL, new NeteaseClient(prefs), prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (playback != null) {
            for (TidalClient.Track track : playback.history) {
                if (SearchSource.TIDAL.includes(track)) recent.add(track);
            }
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(6));
        head.addView(text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(action("搜索", "搜索 TIDAL", () -> openSearch(SearchSource.TIDAL)));
        content.addView(head);
        if (!signedIn) {
            content.addView(primary("登录 TIDAL", this::login), new LinearLayout.LayoutParams(-2, dp(40)));
        }
        boolean nothing = personalDiscover.isEmpty() && personalTracks.isEmpty()
                && personalAlbums.isEmpty() && personalPlaylists.isEmpty();
        if (personalLoading && nothing) {
            TextView waiting = text("正在读取推荐、混音和收藏…", 13, MUTED, false);
            waiting.setPadding(0, dp(8), 0, dp(4));
            content.addView(waiting);
        }
        for (TidalClient.HomeShelf shelf : personalDiscover) {
            String title = tidalShelfTitle(shelf.title);
            if (!shelf.tracks.isEmpty()) {
                List<TidalClient.Track> tracks = shelf.tracks;
                trackShelf(title, "播放全部", () -> playList(tracks, 0, false), tracks);
            } else if (!shelf.collections.isEmpty()) {
                shelf(title, shelf.collections);
            }
        }
        if (signedIn) {
            List<TidalClient.Track> liked = personalTracks;
            trackShelf("收藏的歌", liked.isEmpty() ? null : "播放全部",
                    liked.isEmpty() ? null : () -> playList(liked, 0, false), liked);
            linkedShelf("收藏专辑", () -> openLibrary(1), personalAlbums);
            linkedShelf(personalPlaylistsForAccount ? "自建歌单" : "收藏歌单",
                    () -> openLibrary(personalPlaylistsForAccount ? 4 : 2), personalPlaylists);
        }
        if (!personalLoading && signedIn && nothing) homeNote("首页推荐和收藏暂时都没有返回");
        if (!recent.isEmpty()) {
            section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                content.addView(trackEntry(recent.get(at), false, () -> playList(recent, at, false)));
            }
        }
    }

    /** Common TIDAL home headings. Anything else stays in the language TIDAL returned. */
    private static String tidalShelfTitle(String title) {
        String key = title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (key.equals("recently played")) return "最近播放";
        if (key.equals("custom mixes")) return "专属混音";
        if (key.startsWith("radio stations")) return "为你推荐的电台";
        if (key.startsWith("suggested new albums")) return "新专辑推荐";
        return title == null ? "" : title;
    }

    private void openLibrary(int filter) {
        libraryFilter = filter;
        if (page != Page.LIBRARY) page = Page.LIBRARY;
        notice = "";
        load(false);
    }

    private void sourceHome(SearchSource source) {
        if (source == SearchSource.NETEASE) {
            neteaseHome();
            return;
        }
        if (source == SearchSource.TIDAL) {
            tidalHome();
            return;
        }
        boolean tidal = source == SearchSource.TIDAL;
        android.content.SharedPreferences prefs = getSharedPreferences("tidal", MODE_PRIVATE);
        NeteaseClient netease = new NeteaseClient(prefs);
        boolean signedIn = tidal ? tidalApi() != null : netease.signedIn();
        loadPersonalHome(source, netease, prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (playback != null) {
            for (TidalClient.Track track : playback.history) {
                if (source.includes(track)) recent.add(track);
            }
        }

        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        TextView title = text(greeting, 25, INK, true);
        title.setPadding(0, 0, 0, dp(10));
        content.addView(title);

        List<TidalClient.Track> featured = personalTracks.isEmpty() ? recent : personalTracks;
        LinearLayout hero = column();
        hero.setPadding(dp(15), dp(14), dp(15), dp(13));
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                tidal ? new int[]{0xff173542, SURFACE} : new int[]{0xff48252e, SURFACE});
        backdrop.setCornerRadius(dp(20));
        hero.setBackground(backdrop);
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (featured.isEmpty() && tidal && signedIn && !personalAlbums.isEmpty()) {
            TidalClient.Collection first = personalAlbums.get(0);
            LinearLayout feature = row();
            ImageView image = art(72);
            artwork.load(image, first.cover, 320, first.title);
            feature.addView(image, new LinearLayout.LayoutParams(dp(72), dp(72)));
            LinearLayout details = column();
            details.setPadding(dp(12), 0, 0, 0);
            details.addView(text("最近收藏的专辑 · 共 " + personalAlbums.size() + " 张", 12, ACCENT, true));
            details.addView(single(first.title, 20, INK, true));
            details.addView(single(first.subtitle, 13, MUTED, false));
            feature.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            hero.addView(feature);
            LinearLayout actions = row();
            actions.setPadding(0, dp(12), 0, 0);
            actions.addView(withIcon(primary("打开专辑", () -> openCollection(first)), "forward", BG),
                    new LinearLayout.LayoutParams(-2, dp(40)));
            hero.addView(actions);
        } else if (featured.isEmpty()) {
            String label = personalLoading ? "正在准备你的音乐"
                    : tidal && !signedIn ? "登录后查看你的收藏"
                    : !tidal && !signedIn && netease.usesDefaultBackend() ? "登录后查看每日推荐" : "开始聆听";
            hero.addView(text(label, 19, INK, true));
            String hint = personalLoading ? "稍等片刻，正在读取推荐与收藏。"
                    : tidal && !signedIn ? "在 TIDAL 网页确认一次授权，即可收听 Hi-Res 无损并同步收藏歌曲与歌单。"
                    : "搜索歌曲、专辑或艺人，开始一段新的聆听。";
            TextView about = text(hint, 13, MUTED, false);
            about.setPadding(0, dp(4), 0, dp(12));
            hero.addView(about);
            if (!personalLoading) {
                String button = tidal && !signedIn ? "登录 TIDAL" : "搜索音乐";
                hero.addView(primary(button, tidal && !signedIn ? this::login : () -> openSearch(source)),
                        new LinearLayout.LayoutParams(-2, dp(42)));
            }
        } else {
            TidalClient.Track first = featured.get(0);
            LinearLayout feature = row();
            ImageView image = art(62);
            artwork.load(image, first, 320);
            feature.addView(image, new LinearLayout.LayoutParams(dp(62), dp(62)));
            LinearLayout details = column();
            details.setPadding(dp(12), 0, 0, 0);
            String kind = personalTracks.isEmpty() ? "继续聆听" : tidal ? "从你的收藏听起" : "每日推荐";
            details.addView(text(kind + " · " + featured.size() + " 首", 12, ACCENT, true));
            details.addView(single(first.title, 20, INK, true));
            details.addView(single(first.artist, 13, MUTED, false));
            feature.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            hero.addView(feature);
            LinearLayout actions = row();
            actions.setPadding(0, dp(9), 0, 0);
            actions.addView(withIcon(primary("立即播放", () -> playList(featured, 0, false)), "play", BG),
                    new LinearLayout.LayoutParams(-2, dp(40)));
            View gap = new View(this);
            actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            actions.addView(withIcon(secondary("加入队列", () -> {
                if (playback == null) return;
                playback.enqueueAll(featured);
                toast("已加入 " + featured.size() + " 首");
            }), "queue", ACCENT), new LinearLayout.LayoutParams(-2, dp(40)));
            hero.addView(actions);
        }

        LinearLayout links = row();
        links.setPadding(0, dp(12), 0, 0);
        links.addView(tile("search", "搜索", () -> openSearch(source)));
        if (!featured.isEmpty()) {
            links.addView(tile("shuffle", "随机听", () -> playList(featured,
                    new java.util.Random().nextInt(featured.size()), false)));
        }
        if (tidal) {
            links.addView(tile("heart", "我的收藏", signedIn ? () -> open(Page.LIBRARY) : this::login));
            links.addView(tile("user", "账号", () -> open(Page.ACCOUNT)));
        } else {
            if (netease.usesDefaultBackend()) {
                links.addView(tile("user", signedIn ? "账号" : "登录网易云",
                        signedIn ? () -> open(Page.SOURCES) : () -> showNeteaseLogin(netease)));
            }
        }
        for (int i = 0; i < links.getChildCount(); i++) {
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(70), 1);
            if (i > 0) cell.leftMargin = dp(8);
            links.getChildAt(i).setLayoutParams(cell);
        }
        content.addView(links);
        if (!tidal) {
            LinearLayout playlistLinks = row();
            addShortcut(playlistLinks, "我的歌单", "我创建的与收藏的", "library",
                    () -> openNeteasePlaylists(Page.NETEASE_PLAYLISTS));
            addShortcut(playlistLinks, "歌单广场", "按分类发现歌单", "sources",
                    () -> openNeteasePlaylists(Page.NETEASE_SQUARE));
            content.addView(playlistLinks);
        }

        if (!recent.isEmpty()) {
            section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                content.addView(trackEntry(recent.get(at), false, () -> playList(recent, at, false)));
            }
        }
        if (tidal && personalAlbums.size() > 1) shelf("收藏专辑", personalAlbums.subList(1, personalAlbums.size()));
        if (!personalPlaylists.isEmpty()) {
            shelf(tidal ? "歌单" : personalPlaylistsForAccount ? "专属歌单" : "推荐歌单", personalPlaylists);
        }
    }

    /** Horizontally scrolling row of album / playlist covers. */
    private void shelf(String heading, List<TidalClient.Collection> items) {
        shelf(heading, null, null, items);
    }

    private void shelf(String heading, String more, Runnable openMore, List<TidalClient.Collection> items) {
        if (items.isEmpty()) return;
        sectionLink(heading, more, openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(12, items.size()); i++) {
            TidalClient.Collection item = items.get(i);
            LinearLayout card = column();
            ImageView cover = art(124);
            artwork.load(cover, item.cover, 320, item.title);
            card.addView(cover, new LinearLayout.LayoutParams(dp(124), dp(124)));
            TextView name = single(item.title, 13, INK, true);
            name.setPadding(0, dp(7), 0, 0);
            card.addView(name);
            TextView caption = single(item.subtitle, 12, MUTED, false);
            card.addView(caption);
            mark(() -> {
                int saved = item.kind == TidalClient.Collection.Kind.ALBUM && downloaded != null ? downloaded.album(item) : 0;
                caption.setText(saved > 0 ? "已下载 " + saved + " 首" : item.subtitle);
                caption.setTextColor(saved > 0 ? ACCENT : MUTED);
                caption.setVisibility(saved > 0 || !item.subtitle.isEmpty() ? View.VISIBLE : View.GONE);
            });
            card.setContentDescription(item.title);
            card.setOnClickListener(view -> openCollection(item));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(124), -2);
            cardParams.setMarginEnd(dp(12));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        content.addView(scroll);
    }

    /** Horizontally scrolling row of songs, used by the NetEase home. */
    private void trackShelf(String heading, String more, Runnable openMore, List<TidalClient.Track> tracks) {
        if (tracks.isEmpty()) return;
        sectionLink(heading, more, openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(20, tracks.size()); i++) {
            int index = i;
            TidalClient.Track track = tracks.get(index);
            LinearLayout card = column();
            card.setContentDescription(displayTitle(track) + "，" + track.artist);
            card.setOnClickListener(view -> playList(tracks, index, false));
            ImageView cover = art(112);
            artwork.load(cover, track, 320);
            card.addView(cover, new LinearLayout.LayoutParams(dp(112), dp(112)));
            TextView name = single(displayTitle(track), 13, INK, true);
            name.setPadding(0, dp(6), 0, 0);
            card.addView(name);
            card.addView(single(track.artist, 11, MUTED, false));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(112), -2);
            cardParams.setMarginEnd(dp(10));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        content.addView(scroll);
    }

    private void sectionLink(String heading, String more, Runnable openMore) {
        if (more == null || openMore == null) {
            section(heading);
            return;
        }
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(6));
        line.addView(text(heading, 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button open = action(more, more + heading, openMore);
        open.setTextSize(13);
        open.setPadding(dp(8), 0, 0, 0);
        line.addView(open);
        content.addView(line);
    }

    private void openNeteasePlaylists(Page target) {
        if (page == target) return;
        neteasePlaylistGeneration++;
        neteasePlaylistLoading = false;
        neteasePlaylistMore = false;
        neteasePlaylistNotice = "";
        neteaseCreated.clear();
        neteaseCollected.clear();
        open(target);
        loadNeteasePlaylists(false);
    }

    private void neteasePlaylistPage() {
        boolean mine = page == Page.NETEASE_PLAYLISTS;
        if (mine) {
            LinearLayout filters = row();
            filters.addView(pill("我创建的", !showCollected, () -> {
                showCollected = false;
                render();
            }));
            filters.addView(pill("我收藏的", showCollected, () -> {
                showCollected = true;
                render();
            }));
            content.addView(filters);
        } else {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout filters = row();
            for (String category : SQUARE_CATEGORIES) {
                filters.addView(pill(category, squareCategory.equals(category), () -> {
                    if (squareCategory.equals(category)) return;
                    squareCategory = category;
                    loadNeteasePlaylists(false);
                }));
            }
            scroll.addView(filters);
            content.addView(scroll);
        }
        NeteaseClient account = new NeteaseClient(getSharedPreferences("tidal", MODE_PRIVATE));
        if (mine && !account.signedIn()) {
            empty("登录后查看个人歌单", "查看你创建和收藏的网易云歌单");
            if (account.usesDefaultBackend()) content.addView(primary("登录网易云", () -> showNeteaseLogin(account)));
            return;
        }
        List<TidalClient.Collection> items = mine && showCollected ? neteaseCollected : neteaseCreated;
        for (TidalClient.Collection item : items) collectionRow(item);
        if (neteasePlaylistLoading) {
            if (items.isEmpty()) empty("正在加载歌单…", "");
        } else if (!neteasePlaylistNotice.isEmpty()) {
            empty(neteasePlaylistNotice, "");
            content.addView(action("重试", "重新加载歌单", () -> loadNeteasePlaylists(neteasePlaylistOffset > 0)));
        } else if (items.isEmpty() && !neteasePlaylistMore) {
            empty("暂无歌单", "");
        }
        if (!neteasePlaylistLoading && neteasePlaylistMore && neteasePlaylistNotice.isEmpty()) {
            content.addView(action("加载更多", "加载更多歌单", () -> loadNeteasePlaylists(true)));
        }
    }

    private void loadNeteasePlaylists(boolean append) {
        Page target = page;
        NeteaseClient account = new NeteaseClient(getSharedPreferences("tidal", MODE_PRIVATE));
        int request = ++neteasePlaylistGeneration;
        if (!append) {
            neteaseCreated.clear();
            neteaseCollected.clear();
            neteasePlaylistOffset = 0;
            neteasePlaylistUser = 0;
            neteasePlaylistMore = false;
        }
        neteasePlaylistNotice = "";
        neteasePlaylistLoading = target == Page.NETEASE_SQUARE || account.signedIn();
        render();
        if (!neteasePlaylistLoading) return;
        int offset = neteasePlaylistOffset;
        long user = neteasePlaylistUser;
        String category = squareCategory;
        worker.submit(() -> {
            try {
                NeteaseClient.PlaylistPage result = target == Page.NETEASE_SQUARE
                        ? account.playlistSquare(category, offset) : account.myPlaylists(user, offset);
                runOnUiThread(() -> {
                    if (isDestroyed() || neteasePlaylistGeneration != request) return;
                    neteaseCreated.addAll(result.created);
                    neteaseCollected.addAll(result.collected);
                    neteasePlaylistOffset = result.nextOffset;
                    neteasePlaylistUser = result.user;
                    neteasePlaylistMore = result.more;
                    neteasePlaylistLoading = false;
                    if (page == target) render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || neteasePlaylistGeneration != request) return;
                    neteasePlaylistNotice = "歌单加载失败 · " + readable(error);
                    neteasePlaylistLoading = false;
                    if (page == target) render();
                });
            }
        });
    }

    private void addShortcut(LinearLayout parent, String title, String subtitle, String icon, Runnable choose) {
        LinearLayout card = column();
        card.setPadding(dp(13), dp(14), dp(10), dp(13));
        card.setBackground(shape(SURFACE, 15));
        TextView name = text(title, 16, INK, true);
        ImageView symbol = new ImageView(this);
        symbol.setImageDrawable(new PlayerIcon(icon, ACCENT, dp(23)));
        card.addView(symbol, new LinearLayout.LayoutParams(dp(23), dp(23)));
        name.setPadding(0, dp(9), 0, dp(2));
        card.addView(name);
        card.addView(text(subtitle, 13, MUTED, false));
        card.setOnClickListener(view -> choose.run());
        card.setContentDescription(title + "，" + subtitle);
        LinearLayout.LayoutParams style = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) style.leftMargin = dp(10);
        parent.addView(card, style);
    }

    private boolean hasPlayerAccount() {
        return !getSharedPreferences("tidal", MODE_PRIVATE).getString("player_account", "").isEmpty();
    }

    private void sources() {
        boolean loggedIn = tidalApi() != null;
        String hint = lendHint();
        LinearLayout tidal = panel("TIDAL", borrowsTidal() ? "使用「" + devices.targetName() + "」的登录 · 浏览、收藏和下载都经由该设备，"
                + "歌曲在该设备上播放" : hint != null ? hint : loggedIn ? "已连接 · Hi-Res 无损、收藏与歌单" : "在 TIDAL 网页授权一次即可，无需电脑。");
        Button enterTidal = withIcon(loggedIn ? primary("进入 TIDAL", () -> enterSource(SearchSource.TIDAL))
                : secondary("浏览", () -> enterSource(SearchSource.TIDAL)), "forward", loggedIn ? BG : ACCENT);
        tidal.addView(loggedIn ? toolbar(enterTidal, secondary("账号设置", () -> open(Page.ACCOUNT)))
                : toolbar(withIcon(primary("登录 TIDAL", this::login), "user", BG), enterTidal,
                secondary("账号设置", () -> open(Page.ACCOUNT))));
        android.content.SharedPreferences sourcePrefs = getSharedPreferences("tidal", MODE_PRIVATE);
        NeteaseClient neteaseAccount = new NeteaseClient(sourcePrefs);
        boolean neteaseIn = neteaseAccount.signedIn();
        LinearLayout netease = panel("网易云音乐", neteaseIn
                ? "已登录 · 使用网易云账号播放" : "登录后可播放账号有权访问的曲目，访客可浏览推荐歌单。");
        List<Button> neteaseActions = new ArrayList<>();
        boolean offerLogin = neteaseAccount.usesDefaultBackend() && !neteaseIn;
        if (offerLogin) neteaseActions.add(withIcon(primary("扫码登录", () -> showNeteaseLogin(neteaseAccount)), "user", BG));
        neteaseActions.add(withIcon(offerLogin ? secondary("浏览", () -> enterSource(SearchSource.NETEASE))
                : primary("进入网易云", () -> enterSource(SearchSource.NETEASE)), "forward", offerLogin ? ACCENT : BG));
        if (neteaseAccount.usesDefaultBackend() && neteaseIn) {
            neteaseActions.add(secondary("退出登录", () -> new AlertDialog.Builder(this)
                    .setTitle("退出网易云登录？").setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        sourcePrefs.edit().remove(NeteaseClient.SESSION).apply();
                        render();
                    }).show()));
        }
        neteaseActions.add(secondary("接口设置", this::configureNetease));
        netease.addView(toolbar(neteaseActions.toArray(new Button[0])));
        LinearLayout local = panel("本地文件", "音乐保存在手机或已授权的外接存储中。");
        local.addView(toolbar(withIcon(primary("打开音乐库", () -> open(Page.LOCAL)), "library", BG),
                withIcon(secondary("扫描目录", this::chooseFolders), "folder", ACCENT)));
        List<WebDavClient.Server> davServers = WebDavClient.loadServers(this);
        LinearLayout webdav = panel("WebDAV 网络存储", davServers.isEmpty()
                ? "连接 NAS、AList、坚果云等网络存储，在线流式播放与下载。"
                : "已添加 " + davServers.size() + " 个服务器 · 支持直接串流与整目录播放。");
        List<Button> davActions = new ArrayList<>();
        if (!davServers.isEmpty()) {
            davActions.add(withIcon(primary("浏览存储", () -> {
                webdavBack = Page.SOURCES;
                webdavCurrentServer = null;
                webdavCurrentPath = "";
                open(Page.WEBDAV);
            }), "folder", BG));
        }
        davActions.add(withIcon(secondary("添加服务器", () -> showEditWebDavServer(null)), "plus", ACCENT));
        webdav.addView(toolbar(davActions.toArray(new Button[0])));
        List<SmbClient.Server> smbServers = SmbClient.loadServers(this);
        LinearLayout smb = panel("SMB / Windows 共享", smbServers.isEmpty()
                ? "连接 Windows 电脑、NAS、Samba 共享文件夹，直接流式播放网络音频与下载。"
                : "已添加 " + smbServers.size() + " 个服务器 · 支持直接串流与整目录播放。");
        List<Button> smbActions = new ArrayList<>();
        if (!smbServers.isEmpty()) {
            smbActions.add(withIcon(primary("浏览共享", () -> {
                smbBack = Page.SOURCES;
                smbCurrentServer = null;
                smbCurrentPath = "";
                open(Page.SMB);
            }), "folder", BG));
        }
        smbActions.add(withIcon(secondary("添加共享", () -> showEditSmbServer(null)), "plus", ACCENT));
        smb.addView(toolbar(smbActions.toArray(new Button[0])));
        if (!hasPlayerAccount()) {
            LinearLayout account = panel("播放器账号（可选）", "在电脑和手机之间同步登录、遥控播放。");
            account.addView(toolbar(withIcon(secondary("登录播放器账号", this::playerAccountLogin), "user", ACCENT)));
        }
    }

    private void showNeteaseLogin(NeteaseClient api) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView status = new TextView(this);
        status.setTextColor(MUTED);
        body.addView(status);
        ImageView qr = new ImageView(this);
        qr.setContentDescription("网易云登录二维码");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setVisibility(View.GONE);
        body.addView(qr, new LinearLayout.LayoutParams(-1, dp(220)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录网易云")
                .setView(body).setNegativeButton("关闭", null).setNeutralButton("刷新二维码", null).show();
        Button retry = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

        final class Login {
            private Future<?> request;
            private int attempt;
            private long deadline;

            void cancel() {
                attempt++;
                if (request != null) request.cancel(true);
            }

            boolean active(int id) {
                return id == attempt && dialog.isShowing() && !isDestroyed();
            }

            void start() {
                cancel();
                int id = attempt;
                status.setText("正在生成二维码…");
                qr.setVisibility(View.GONE);
                request = worker.submit(() -> {
                    try {
                        org.json.JSONObject data = api.startLogin();
                        String image = data.getString("qr");
                        if (!image.startsWith("data:image/png;base64,") || image.length() > 200000) {
                            throw new java.io.IOException("二维码格式无效");
                        }
                        byte[] bytes = android.util.Base64.decode(image.substring(22), android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bitmap == null) throw new java.io.IOException("无法读取二维码");
                        String proof = data.getString("proof");
                        handler.post(() -> {
                            if (!active(id)) return;
                            qr.setImageBitmap(bitmap);
                            qr.setVisibility(View.VISIBLE);
                            status.setText("请用另一台设备上的网易云音乐 App 扫码，并确认登录。");
                            deadline = android.os.SystemClock.elapsedRealtime() + 170000;
                            schedule(id, proof);
                        });
                    } catch (Exception error) {
                        handler.post(() -> { if (active(id)) status.setText("登录失败 · " + readable(error)); });
                    }
                });
            }

            void schedule(int id, String proof) {
                handler.postDelayed(() -> {
                    if (!active(id)) return;
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        expire();
                        return;
                    }
                    request = worker.submit(() -> {
                        try {
                            org.json.JSONObject result = api.pollLogin(proof);
                            handler.post(() -> update(id, proof, result));
                        } catch (Exception error) {
                            handler.post(() -> {
                                if (!active(id)) return;
                                status.setText("正在重新连接网易云…");
                                schedule(id, proof);
                            });
                        }
                    });
                }, 2500);
            }

            void expire() {
                qr.setVisibility(View.GONE);
                status.setText("二维码已过期，请刷新后重新扫码。");
            }

            void update(int id, String proof, org.json.JSONObject result) {
                if (!active(id)) return;
                switch (result.optInt("code")) {
                    case 800:
                        expire();
                        return;
                    case 801:
                        status.setText("请用另一台设备上的网易云音乐 App 扫码，并确认登录。");
                        break;
                    case 802:
                        status.setText("已扫码，请在网易云音乐 App 中确认。");
                        break;
                    case 803:
                        String session = result.optString("session");
                        if (!session.matches("[A-Za-z0-9._~%+-]{16,4096}")) {
                            status.setText("未能取得登录会话，请刷新后再试。");
                            return;
                        }
                        getSharedPreferences("tidal", MODE_PRIVATE).edit()
                                .putString(NeteaseClient.SESSION, session).apply();
                        dialog.dismiss();
                        if (page == Page.NETEASE_PLAYLISTS) loadNeteasePlaylists(false);
                        else render();
                        toast("已登录网易云");
                        return;
                    default:
                        status.setText("网易云未能完成授权，请刷新二维码重试。");
                        return;
                }
                schedule(id, proof);
            }
        }

        Login login = new Login();
        retry.setOnClickListener(view -> login.start());
        dialog.setOnDismissListener(ignored -> login.cancel());
        login.start();
    }

    private void configureNetease() {
        android.content.SharedPreferences prefs = getSharedPreferences("tidal", MODE_PRIVATE);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint(NeteaseClient.DEFAULT_BASE);
        address.setText(prefs.getString(NeteaseClient.SETTING, ""));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("网易云接口")
                .setMessage("留空使用播放器默认后台。").setView(address)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String value = address.getText().toString().trim();
                String endpoint = value.isEmpty() ? "" : NeteaseClient.validateBase(value);
                prefs.edit().putString(NeteaseClient.SETTING, endpoint).apply();
                dialog.dismiss();
                if ((page == Page.SEARCH && searchSource == SearchSource.NETEASE)
                        || (page == Page.COLLECTION && collection.isNetease())) load(false);
                else render();
            } catch (java.io.IOException invalid) {
                address.setError(invalid.getMessage());
            }
        });
    }

    private String webdavTitle() {
        if (webdavCurrentServer == null) return "WebDAV 网络存储";
        if (webdavCurrentPath == null || webdavCurrentPath.isEmpty() || "/".equals(webdavCurrentPath)) {
            return webdavCurrentServer.name;
        }
        String p = webdavCurrentPath.endsWith("/")
                ? webdavCurrentPath.substring(0, webdavCurrentPath.length() - 1) : webdavCurrentPath;
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    private void webdavPage() {
        if (webdavCurrentServer == null) {
            webdavServerList();
        } else {
            webdavDirectoryBrowser();
        }
    }

    private void webdavServerList() {
        List<WebDavClient.Server> servers = WebDavClient.loadServers(this);
        LinearLayout topBar = row();
        topBar.addView(text("已配置的 WebDAV 服务器", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(withIcon(secondary("添加服务器", () -> showEditWebDavServer(null)), "plus", ACCENT));
        content.addView(topBar);

        if (servers.isEmpty()) {
            empty("尚未添加 WebDAV 服务器", "支持连接群晖、威联通、AList、坚果云等任意 WebDAV 服务，直接流式播放网络音频。");
            content.addView(toolbar(withIcon(primary("立即添加 WebDAV 服务器", () -> showEditWebDavServer(null)), "plus", BG)));
            return;
        }

        for (WebDavClient.Server server : servers) {
            LinearLayout card = panel(server.name, server.url + (server.user.isEmpty() ? "" : " · " + server.user));
            Button browse = withIcon(primary("进入浏览", () -> {
                webdavCurrentServer = server;
                webdavCurrentPath = "/";
                loadWebDavDirectory();
            }), "folder", BG);
            Button edit = secondary("设置 / 删除", () -> showEditWebDavServer(server));
            card.addView(toolbar(browse, edit));
        }
    }

    private void webdavDirectoryBrowser() {
        LinearLayout navBar = row();
        navBar.setPadding(0, 0, 0, dp(8));
        boolean canGoUp = webdavCurrentPath != null && !webdavCurrentPath.isEmpty() && !"/".equals(webdavCurrentPath);
        Button backBtn = withIcon(secondary(canGoUp ? "上一级" : "服务器列表", () -> {
            if (canGoUp) {
                String p = webdavCurrentPath.endsWith("/")
                        ? webdavCurrentPath.substring(0, webdavCurrentPath.length() - 1) : webdavCurrentPath;
                int lastSlash = p.lastIndexOf('/');
                webdavCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : "/";
                loadWebDavDirectory();
            } else {
                webdavCurrentServer = null;
                webdavCurrentPath = "";
                webdavItems.clear();
                render();
            }
        }), "back", ACCENT);
        navBar.addView(backBtn);

        String pathDisplay = (webdavCurrentPath == null || webdavCurrentPath.isEmpty() || "/".equals(webdavCurrentPath))
                ? "/" : webdavCurrentPath;
        TextView pathView = single(pathDisplay, 13, MUTED, false);
        pathView.setPadding(dp(12), 0, dp(8), 0);
        navBar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        navBar.addView(iconButton("refresh", "刷新", this::loadWebDavDirectory));
        content.addView(navBar);

        List<WebDavClient.Item> audioItems = new ArrayList<>();
        List<WebDavClient.Item> dirItems = new ArrayList<>();
        for (WebDavClient.Item item : webdavItems) {
            if (item.isDirectory) dirItems.add(item);
            else if (item.isAudio) audioItems.add(item);
        }

        if (!audioItems.isEmpty()) {
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            String folderName = webdavTitle();
            for (WebDavClient.Item item : audioItems) {
                try {
                    audioTracks.add(WebDavClient.toTrack(webdavCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }
            Button playAll = withIcon(primary("播放全部 (" + audioTracks.size() + ")", () -> playList(audioTracks, 0, false)), "play", BG);
            Button random = withIcon(secondary("随机", () -> playList(audioTracks, 0, true)), "shuffle", ACCENT);
            Button downloadAll = withIcon(secondary("下载本目录", () -> downloadWebDavDirectory(webdavCurrentServer, folderName, audioItems)), "download", ACCENT);
            content.addView(toolbar(playAll, random, downloadAll));
        }

        if (webdavLoading) {
            empty("正在加载目录…", "");
            return;
        }

        if (!webdavError.isEmpty()) {
            empty("加载失败 · " + webdavError, "");
            content.addView(toolbar(withIcon(secondary("重试", this::loadWebDavDirectory), "refresh", ACCENT)));
            return;
        }

        if (webdavItems.isEmpty()) {
            empty("目录为空", "此路径下未发现文件或文件夹。");
            return;
        }

        if (!dirItems.isEmpty()) {
            section("文件夹 (" + dirItems.size() + ")");
            for (WebDavClient.Item dir : dirItems) {
                LinearLayout row = entry(dir.name, "文件夹", "", () -> {
                    webdavCurrentPath = dir.path;
                    loadWebDavDirectory();
                });
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("folder", ACCENT, dp(22)));
                content.addView(row);
            }
        }

        if (!audioItems.isEmpty()) {
            section("音频文件 (" + audioItems.size() + ")");
            String folderName = webdavTitle();
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            for (WebDavClient.Item item : audioItems) {
                try {
                    audioTracks.add(WebDavClient.toTrack(webdavCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }

            for (int i = 0; i < audioTracks.size(); i++) {
                int at = i;
                TidalClient.Track track = audioTracks.get(i);
                WebDavClient.Item item = audioItems.get(i);
                String sizeLabel = formatBytes(item.size);
                String sub = sizeLabel.isEmpty() ? "WebDAV 音频" : sizeLabel + " · WebDAV";
                LinearLayout row = entry(track.title, sub, "", () -> playList(audioTracks, at, false));
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("note", ACCENT, dp(22)));
                row.addView(iconButton("more", "更多 " + track.title, () -> webdavTrackMenu(track, item)));
                content.addView(row);
            }
        }
    }

    private void loadWebDavDirectory() {
        if (webdavCurrentServer == null) return;
        webdavLoading = true;
        webdavError = "";
        webdavItems.clear();
        render();

        WebDavClient.Server targetServer = webdavCurrentServer;
        String targetPath = webdavCurrentPath;
        worker.submit(() -> {
            try {
                List<WebDavClient.Item> result = WebDavClient.list(targetServer, targetPath);
                handler.post(() -> {
                    if (webdavCurrentServer != targetServer || !java.util.Objects.equals(webdavCurrentPath, targetPath)) return;
                    webdavItems.clear();
                    webdavItems.addAll(result);
                    webdavLoading = false;
                    webdavError = "";
                    render();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (webdavCurrentServer != targetServer || !java.util.Objects.equals(webdavCurrentPath, targetPath)) return;
                    webdavLoading = false;
                    webdavError = e.getMessage() != null ? e.getMessage() : "读取目录失败";
                    render();
                });
            }
        });
    }

    private void showEditWebDavServer(WebDavClient.Server existing) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView nameLabel = text("服务器名称", 12, MUTED, false);
        body.addView(nameLabel);
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("如：我的群晖 NAS / AList");
        if (existing != null) nameInput.setText(existing.name);
        body.addView(nameInput);

        TextView urlLabel = text("WebDAV 地址 (URL)", 12, MUTED, false);
        urlLabel.setPadding(0, dp(8), 0, 0);
        body.addView(urlLabel);
        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://192.168.1.100:5005/music/");
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) urlInput.setText(existing.url);
        body.addView(urlInput);

        TextView userLabel = text("用户名 (可选)", 12, MUTED, false);
        userLabel.setPadding(0, dp(8), 0, 0);
        body.addView(userLabel);
        EditText userInput = new EditText(this);
        userInput.setSingleLine(true);
        userInput.setHint("如：admin");
        if (existing != null) userInput.setText(existing.user);
        body.addView(userInput);

        TextView passLabel = text("密码 (可选)", 12, MUTED, false);
        passLabel.setPadding(0, dp(8), 0, 0);
        body.addView(passLabel);
        EditText passInput = new EditText(this);
        passInput.setSingleLine(true);
        passInput.setHint("密码");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) passInput.setText(existing.pass);
        body.addView(passInput);

        TextView status = text("", 12, 0xffff6b6b, false);
        status.setPadding(0, dp(8), 0, 0);
        body.addView(status);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加 WebDAV 服务器" : "编辑 WebDAV 服务器")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("测试并保存", null);

        if (existing != null) {
            builder.setNeutralButton("删除服务器", (d, which) -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除「" + existing.name + "」？")
                        .setMessage("确定从列表中移除此 WebDAV 服务器吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d2, which2) -> {
                            WebDavClient.deleteServer(this, existing.id);
                            if (webdavCurrentServer != null && webdavCurrentServer.id.equals(existing.id)) {
                                webdavCurrentServer = null;
                                webdavCurrentPath = "";
                                webdavItems.clear();
                            }
                            render();
                        }).show();
            });
        }

        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            String user = userInput.getText().toString().trim();
            String pass = passInput.getText().toString();

            if (url.isEmpty()) {
                urlInput.setError("请输入服务器地址");
                return;
            }
            if (name.isEmpty()) name = "WebDAV";

            String id = existing != null ? existing.id : UUID.randomUUID().toString();
            WebDavClient.Server candidate = new WebDavClient.Server(id, name, url, user, pass);

            btn.setEnabled(false);
            status.setText("正在测试连接…");
            status.setTextColor(ACCENT);

            worker.submit(() -> {
                try {
                    WebDavClient.testConnection(candidate);
                    handler.post(() -> {
                        WebDavClient.saveServer(this, candidate);
                        dialog.dismiss();
                        toast("连接成功并已保存");
                        render();
                    });
                } catch (Exception ex) {
                    handler.post(() -> {
                        btn.setEnabled(true);
                        status.setTextColor(0xffff6b6b);
                        status.setText("测试连接失败: " + (ex.getMessage() != null ? ex.getMessage() : "无法连接"));
                    });
                }
            });
        });
    }

    private interface FolderPickerCallback {
        void onFolder(Uri folderUri);
    }

    private void pickDownloadRoot(FolderPickerCallback callback) {
        if (playback == null || playback.localLibrary == null) return;
        List<Uri> roots = playback.localLibrary.folders();
        if (roots.isEmpty()) {
            toast("请先在【本地文件】中扫描或授权一个存储目录");
            chooseFolders();
            return;
        }
        if (roots.size() == 1) {
            callback.onFolder(roots.get(0));
            return;
        }
        String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            labels[i] = LocalMusicFiles.displayPath(roots.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择存储目录")
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < roots.size()) {
                        callback.onFolder(roots.get(which));
                    }
                })
                .show();
    }

    private void webdavTrackMenu(TidalClient.Track track, WebDavClient.Item item) {
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add("下一首播放");
        actions.add(() -> { if (playback != null) { playback.enqueue(track, true); toast("已加入下一首"); } });
        choices.add("追加到队列");
        actions.add(() -> { if (playback != null) { playback.enqueue(track, false); toast("已加入队列"); } });

        WebDavClient.Server targetServer = webdavCurrentServer;
        if (targetServer == null) {
            for (WebDavClient.Server s : WebDavClient.loadServers(this)) {
                if (track.webdavUrl.startsWith(s.url) || s.authHeader().equals(track.webdavAuth)) {
                    targetServer = s;
                    break;
                }
            }
        }
        if (targetServer != null) {
            final WebDavClient.Server s = targetServer;
            choices.add("下载到本地");
            actions.add(() -> downloadWebDavTrack(s, track, item));
        }

        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    private void downloadWebDavTrack(WebDavClient.Server server, TidalClient.Track track, WebDavClient.Item item) {
        pickDownloadRoot(targetRoot -> {
            toast("开始下载: " + track.title);
            worker.submit(() -> {
                try {
                    WebDavClient.Item targetItem = item;
                    if (targetItem == null) {
                        targetItem = new WebDavClient.Item(track.title + ".flac", "", track.webdavUrl, false, 0);
                    }
                    WebDavClient.downloadFile(this, server, targetItem, targetRoot, null);
                    handler.post(() -> {
                        toast("下载完成: " + track.title);
                        scanDirectories(java.util.Collections.singletonList(targetRoot));
                    });
                } catch (Exception e) {
                    handler.post(() -> toast("下载失败: " + (e.getMessage() != null ? e.getMessage() : "网络错误")));
                }
            });
        });
    }

    private void downloadWebDavDirectory(WebDavClient.Server server, String folderName, List<WebDavClient.Item> items) {
        pickDownloadRoot(targetRoot -> {
            List<WebDavClient.Item> toDownload = new ArrayList<>();
            for (WebDavClient.Item item : items) {
                if (item.isAudio || item.name.toLowerCase(Locale.ROOT).endsWith(".lrc")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".jpg")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    toDownload.add(item);
                }
            }

            if (toDownload.isEmpty()) {
                toast("此目录下没有可下载的文件");
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle("下载整目录")
                    .setMessage("即将下载「" + folderName + "」共 " + toDownload.size() + " 个文件到本地存储。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始下载", (dialog, which) -> {
                        toast("开始下载 " + toDownload.size() + " 个文件…");
                        worker.submit(() -> {
                            int success = 0;
                            int failed = 0;
                            Uri folderUri = WebDavClient.findOrCreateDirectory(this, targetRoot, folderName);
                            for (WebDavClient.Item item : toDownload) {
                                try {
                                    WebDavClient.downloadFile(this, server, item, folderUri, null);
                                    success++;
                                } catch (Exception e) {
                                    failed++;
                                }
                            }
                            int totalSuccess = success;
                            int totalFailed = failed;
                            handler.post(() -> {
                                toast("目录下载完成：成功 " + totalSuccess + " 个文件" + (totalFailed > 0 ? "，失败 " + totalFailed + " 个" : ""));
                                scanDirectories(java.util.Collections.singletonList(targetRoot));
                            });
                        });
                    })
                    .show();
        });
    }

    private String smbTitle() {
        if (smbCurrentServer == null) return "SMB / Windows 共享";
        if (smbCurrentPath == null || smbCurrentPath.isEmpty() || "/".equals(smbCurrentPath)
                || smbCurrentPath.equalsIgnoreCase(smbCurrentServer.rootUrl())) {
            return smbCurrentServer.name;
        }
        String p = smbCurrentPath.endsWith("/")
                ? smbCurrentPath.substring(0, smbCurrentPath.length() - 1) : smbCurrentPath;
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    private void smbPage() {
        if (smbCurrentServer == null) {
            smbServerList();
        } else {
            smbDirectoryBrowser();
        }
    }

    private void smbServerList() {
        List<SmbClient.Server> servers = SmbClient.loadServers(this);
        LinearLayout topBar = row();
        topBar.addView(text("已配置的 SMB 共享服务器", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(withIcon(secondary("添加共享", () -> showEditSmbServer(null)), "plus", ACCENT));
        content.addView(topBar);

        if (servers.isEmpty()) {
            empty("尚未添加 SMB 共享", "连接 Windows 电脑、NAS、路由器等局域网共享文件夹，直接流式播放网络音频与下载。");
            content.addView(toolbar(withIcon(primary("立即添加 SMB 共享", () -> showEditSmbServer(null)), "plus", BG)));
            return;
        }

        for (SmbClient.Server server : servers) {
            LinearLayout card = panel(server.name, server.displayAddress());
            Button browse = withIcon(primary("进入浏览", () -> {
                smbCurrentServer = server;
                smbCurrentPath = server.rootUrl();
                loadSmbDirectory();
            }), "folder", BG);
            Button edit = secondary("设置 / 删除", () -> showEditSmbServer(server));
            card.addView(toolbar(browse, edit));
        }
    }

    private void smbDirectoryBrowser() {
        LinearLayout navBar = row();
        navBar.setPadding(0, 0, 0, dp(8));
        String rootRaw = smbCurrentServer != null ? smbCurrentServer.rootUrl() : "";
        final String root = rootRaw.endsWith("/") ? rootRaw : rootRaw + "/";
        String curRaw = smbCurrentPath != null ? smbCurrentPath : "";
        final String cur = curRaw.endsWith("/") ? curRaw : curRaw + "/";
        boolean canGoUp = !cur.isEmpty() && !cur.equalsIgnoreCase(root) && !"/".equals(cur);

        Button backBtn = withIcon(secondary(canGoUp ? "上一级" : "服务器列表", () -> {
            if (canGoUp) {
                String p = cur.endsWith("/") ? cur.substring(0, cur.length() - 1) : cur;
                int lastSlash = p.lastIndexOf('/');
                smbCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : root;
                loadSmbDirectory();
            } else {
                smbCurrentServer = null;
                smbCurrentPath = "";
                smbItems.clear();
                render();
            }
        }), "back", ACCENT);
        navBar.addView(backBtn);

        String pathDisplay = smbDisplayPath(smbCurrentPath);
        TextView pathView = single(pathDisplay, 13, MUTED, false);
        pathView.setPadding(dp(12), 0, dp(8), 0);
        navBar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        navBar.addView(iconButton("refresh", "刷新", this::loadSmbDirectory));
        content.addView(navBar);

        List<SmbClient.Item> audioItems = new ArrayList<>();
        List<SmbClient.Item> dirItems = new ArrayList<>();
        for (SmbClient.Item item : smbItems) {
            if (item.isDirectory) dirItems.add(item);
            else if (item.isAudio) audioItems.add(item);
        }

        if (!audioItems.isEmpty()) {
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            String folderName = smbTitle();
            for (SmbClient.Item item : audioItems) {
                try {
                    audioTracks.add(SmbClient.toTrack(smbCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }
            Button playAll = withIcon(primary("播放全部 (" + audioTracks.size() + ")", () -> playList(audioTracks, 0, false)), "play", BG);
            Button random = withIcon(secondary("随机", () -> playList(audioTracks, 0, true)), "shuffle", ACCENT);
            Button downloadAll = withIcon(secondary("下载本目录", () -> downloadSmbDirectory(smbCurrentServer, folderName, audioItems)), "download", ACCENT);
            content.addView(toolbar(playAll, random, downloadAll));
        }

        if (smbLoading) {
            empty("正在加载目录…", "");
            return;
        }

        if (!smbError.isEmpty()) {
            empty("加载失败 · " + smbError, "");
            content.addView(toolbar(withIcon(secondary("重试", this::loadSmbDirectory), "refresh", ACCENT)));
            return;
        }

        if (smbItems.isEmpty()) {
            empty("目录为空", "此路径下未发现文件或文件夹。");
            return;
        }

        if (!dirItems.isEmpty()) {
            section("文件夹 (" + dirItems.size() + ")");
            for (SmbClient.Item dir : dirItems) {
                LinearLayout row = entry(dir.name, "文件夹", "", () -> {
                    smbCurrentPath = dir.url;
                    loadSmbDirectory();
                });
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("folder", ACCENT, dp(22)));
                content.addView(row);
            }
        }

        if (!audioItems.isEmpty()) {
            section("音频文件 (" + audioItems.size() + ")");
            String folderName = smbTitle();
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            for (SmbClient.Item item : audioItems) {
                try {
                    audioTracks.add(SmbClient.toTrack(smbCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }

            for (int i = 0; i < audioTracks.size(); i++) {
                int at = i;
                TidalClient.Track track = audioTracks.get(i);
                SmbClient.Item item = audioItems.get(i);
                String sizeLabel = formatBytes(item.size);
                String sub = sizeLabel.isEmpty() ? "SMB 音频" : sizeLabel + " · SMB";
                LinearLayout row = entry(track.title, sub, "", () -> playList(audioTracks, at, false));
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("note", ACCENT, dp(22)));
                row.addView(iconButton("more", "更多 " + track.title, () -> smbTrackMenu(track, item)));
                content.addView(row);
            }
        }
    }

    private void loadSmbDirectory() {
        if (smbCurrentServer == null) return;
        smbLoading = true;
        smbError = "";
        smbItems.clear();
        render();

        SmbClient.Server targetServer = smbCurrentServer;
        String targetPath = smbCurrentPath;
        worker.submit(() -> {
            try {
                List<SmbClient.Item> result = SmbClient.list(targetServer, targetPath);
                handler.post(() -> {
                    if (smbCurrentServer != targetServer || !java.util.Objects.equals(smbCurrentPath, targetPath)) return;
                    smbItems.clear();
                    smbItems.addAll(result);
                    smbLoading = false;
                    smbError = "";
                    render();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (smbCurrentServer != targetServer || !java.util.Objects.equals(smbCurrentPath, targetPath)) return;
                    smbLoading = false;
                    smbError = e.getMessage() != null ? e.getMessage() : "读取共享目录失败";
                    render();
                });
            }
        });
    }

    private void showEditSmbServer(SmbClient.Server existing) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView nameLabel = text("服务器备注", 12, MUTED, false);
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("如：家庭 NAS / 电脑共享");
        if (existing != null) nameInput.setText(existing.name);

        TextView hostLabel = text("主机地址 / IP", 12, MUTED, false);
        hostLabel.setPadding(0, dp(8), 0, 0);
        EditText hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("如：192.168.1.100 或 nas.local");
        if (existing != null) hostInput.setText(existing.host);

        TextView portLabel = text("端口 (默认 445)", 12, MUTED, false);
        portLabel.setPadding(0, dp(8), 0, 0);
        EditText portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setHint("445");
        if (existing != null) portInput.setText(String.valueOf(existing.port));

        TextView shareLabel = text("共享文件夹名称 (可选，留空自动列出所有共享)", 12, MUTED, false);
        shareLabel.setPadding(0, dp(8), 0, 0);
        EditText shareInput = new EditText(this);
        shareInput.setSingleLine(true);
        shareInput.setHint("如：music / share (留空则列出所有共享)");
        if (existing != null) shareInput.setText(existing.share);

        TextView userLabel = text("用户名 (匿名访问可留空)", 12, MUTED, false);
        userLabel.setPadding(0, dp(8), 0, 0);
        EditText userInput = new EditText(this);
        userInput.setSingleLine(true);
        userInput.setHint("如：guest 或 username");
        if (existing != null) userInput.setText(existing.user);

        TextView passLabel = text("密码", 12, MUTED, false);
        passLabel.setPadding(0, dp(8), 0, 0);
        EditText passInput = new EditText(this);
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) passInput.setText(existing.pass);

        TextView domainLabel = text("域名 / 工作组 (可选，默认 WORKGROUP)", 12, MUTED, false);
        domainLabel.setPadding(0, dp(8), 0, 0);
        EditText domainInput = new EditText(this);
        domainInput.setSingleLine(true);
        domainInput.setHint("留空使用默认工作组");
        if (existing != null) domainInput.setText(existing.domain);

        body.addView(nameLabel);
        body.addView(nameInput);
        body.addView(hostLabel);
        body.addView(hostInput);
        body.addView(portLabel);
        body.addView(portInput);
        body.addView(shareLabel);
        body.addView(shareInput);
        body.addView(userLabel);
        body.addView(userInput);
        body.addView(passLabel);
        body.addView(passInput);
        body.addView(domainLabel);
        body.addView(domainInput);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加 SMB 共享" : "编辑 SMB 共享")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .setNeutralButton("测试连接", null);

        if (existing != null) {
            builder.setNegativeButton("删除", (dialog, which) -> {
                new AlertDialog.Builder(this)
                        .setTitle("确认删除")
                        .setMessage("确定从列表中移除此 SMB 共享吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d2, w2) -> {
                            List<SmbClient.Server> list = SmbClient.loadServers(this);
                            for (java.util.Iterator<SmbClient.Server> iterator = list.iterator(); iterator.hasNext();) {
                                if (iterator.next().id.equals(existing.id)) iterator.remove();
                            }
                            SmbClient.saveServers(this, list);
                            if (smbCurrentServer != null && smbCurrentServer.id.equals(existing.id)) {
                                smbCurrentServer = null;
                                smbCurrentPath = "";
                                smbItems.clear();
                            }
                            render();
                        })
                        .show();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                toast("请输入主机地址 / IP");
                return;
            }
            int port = 445;
            try {
                String pStr = portInput.getText().toString().trim();
                if (!pStr.isEmpty()) port = Integer.parseInt(pStr);
            } catch (Exception ignored) {}

            SmbClient.Server testServer = new SmbClient.Server(
                    existing != null ? existing.id : null,
                    nameInput.getText().toString().trim(),
                    host,
                    port,
                    shareInput.getText().toString().trim(),
                    userInput.getText().toString().trim(),
                    passInput.getText().toString(),
                    domainInput.getText().toString().trim()
            );

            toast("正在测试连接 SMB…");
            worker.submit(() -> {
                try {
                    SmbClient.testConnection(testServer);
                    handler.post(() -> toast("SMB 连接成功！认证通过。"));
                } catch (Exception e) {
                    handler.post(() -> toast("连接失败: " + (e.getMessage() != null ? e.getMessage() : "网络超时")));
                }
            });
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                toast("请输入主机地址 / IP");
                return;
            }
            int port = 445;
            try {
                String pStr = portInput.getText().toString().trim();
                if (!pStr.isEmpty()) port = Integer.parseInt(pStr);
            } catch (Exception ignored) {}

            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) name = host;

            SmbClient.Server server = new SmbClient.Server(
                    existing != null ? existing.id : null,
                    name,
                    host,
                    port,
                    shareInput.getText().toString().trim(),
                    userInput.getText().toString().trim(),
                    passInput.getText().toString(),
                    domainInput.getText().toString().trim()
            );

            List<SmbClient.Server> list = SmbClient.loadServers(this);
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(server.id)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) list.set(idx, server);
            else list.add(server);

            SmbClient.saveServers(this, list);
            if (smbCurrentServer != null && smbCurrentServer.id.equals(server.id)) {
                smbCurrentServer = server;
            }
            dialog.dismiss();
            render();
        });
    }

    private void smbTrackMenu(TidalClient.Track track, SmbClient.Item item) {
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add("下一首播放");
        actions.add(() -> { if (playback != null) { playback.enqueue(track, true); toast("已加入下一首"); } });
        choices.add("追加到队列");
        actions.add(() -> { if (playback != null) { playback.enqueue(track, false); toast("已加入队列"); } });

        SmbClient.Server targetServer = smbCurrentServer;
        if (targetServer == null) {
            for (SmbClient.Server s : SmbClient.loadServers(this)) {
                if (track.smbUrl.startsWith(s.rootUrl()) || (!s.host.isEmpty() && track.smbUrl.contains(s.host))) {
                    targetServer = s;
                    break;
                }
            }
        }
        if (targetServer != null) {
            final SmbClient.Server s = targetServer;
            choices.add("下载到本地");
            actions.add(() -> downloadSmbTrack(s, track, item));
        }

        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    private void downloadSmbTrack(SmbClient.Server server, TidalClient.Track track, SmbClient.Item item) {
        pickDownloadRoot(targetRoot -> {
            toast("开始下载: " + track.title);
            worker.submit(() -> {
                try {
                    SmbClient.Item targetItem = item;
                    if (targetItem == null) {
                        targetItem = new SmbClient.Item(track.title + ".flac", "", track.smbUrl, false, 0);
                    }
                    SmbClient.downloadFile(this, server, targetItem, targetRoot, null);
                    handler.post(() -> {
                        toast("下载完成: " + track.title);
                        scanDirectories(java.util.Collections.singletonList(targetRoot));
                    });
                } catch (Exception e) {
                    handler.post(() -> toast("下载失败: " + (e.getMessage() != null ? e.getMessage() : "网络错误")));
                }
            });
        });
    }

    private void downloadSmbDirectory(SmbClient.Server server, String folderName, List<SmbClient.Item> items) {
        pickDownloadRoot(targetRoot -> {
            List<SmbClient.Item> toDownload = new ArrayList<>();
            for (SmbClient.Item item : items) {
                if (item.isAudio || item.name.toLowerCase(Locale.ROOT).endsWith(".lrc")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".jpg")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    toDownload.add(item);
                }
            }

            if (toDownload.isEmpty()) {
                toast("此目录下没有可下载的文件");
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle("下载整目录")
                    .setMessage("即将下载「" + folderName + "」共 " + toDownload.size() + " 个文件到本地存储。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始下载", (dialog, which) -> {
                        toast("开始下载 " + toDownload.size() + " 个文件…");
                        worker.submit(() -> {
                            int success = 0;
                            int failed = 0;
                            Uri folderUri = SmbClient.findOrCreateDirectory(this, targetRoot, folderName);
                            for (SmbClient.Item item : toDownload) {
                                try {
                                    SmbClient.downloadFile(this, server, item, folderUri, null);
                                    success++;
                                } catch (Exception e) {
                                    failed++;
                                }
                            }
                            int totalSuccess = success;
                            int totalFailed = failed;
                            handler.post(() -> {
                                toast("目录下载完成：成功 " + totalSuccess + " 个文件" + (totalFailed > 0 ? "，失败 " + totalFailed + " 个" : ""));
                                scanDirectories(java.util.Collections.singletonList(targetRoot));
                            });
                        });
                    })
                    .show();
        });
    }

    private String smbDisplayPath(String path) {
        if (path == null || path.isEmpty()) return "/";
        try {
            java.net.URI uri = new java.net.URI(path);
            String p = uri.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Exception ignored) {
            return path;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void localMusic() {
        if (remoteMode()) {
            remoteLibraryPage(true);
            return;
        }
        if (playback == null) return;
        LocalLibrary library = playback.localLibrary;
        List<TidalClient.Track> tracks = library.search("", localPlaylist);
        if (localPlaylist != null || localGroup != null) {
            if (localGroup != null) {
                LocalGroup selected = localGroup;
                for (int i = tracks.size() - 1; i >= 0; i--) {
                    if (!LocalLibrary.groupFor(tracks.get(i), selected.category).key.equals(selected.key)) tracks.remove(i);
                }
                if (selected.category == 1) sortAlbum(tracks);
                else sortLocal(tracks);
            }
            if (localPlaylist != null) {
                content.addView(toolbar(secondary("导出 M3U", this::pickPlaylistDestination),
                        secondary("删除歌单", this::deleteLocalPlaylist)));
            }
            if (tracks.isEmpty()) {
                empty("暂无歌曲", localPlaylist != null ? "从本地歌曲的更多菜单加入曲目。" : "");
            } else if (localGroup != null && localGroup.category == 1) {
                renderAlbumDetailView(tracks, localGroup.label);
            } else {
                content.addView(text(tracks.size() + " 首歌曲", 13, MUTED, false));
                showTracks(tracks, false);
            }
            return;
        }

        pinLibraryTabs();
        LinearLayout meta = row();
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, 0, 0, dp(4));
        TextView info = text(library.size() + " 首歌曲 · " + library.folders().size() + " 个目录", 13, MUTED, false);
        meta.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        if (localTab == 0 && !tracks.isEmpty()) {
            Button sort = action("排序 · " + LIBRARY_SORTS[localSort], "歌曲排序", this::chooseLocalSort);
            sort.setTextSize(12);
            meta.addView(sort);
        }
        content.addView(meta);
        if (localBusy) content.addView(text("正在扫描音乐…", 12, ACCENT, false));
        else if (!localScanSummary.isEmpty()) content.addView(text(localScanSummary, 12, MUTED, false));

        if (localTab == 2) {
            content.addView(toolbar(withIcon(secondary("新建歌单", () -> newLocalPlaylist(null)), "plus", ACCENT),
                    secondary("导入 M3U", this::pickPlaylistSource)));
            for (String name : library.playlists()) {
                content.addView(entry(name, library.search("", name).size() + " 首歌曲", "", () -> {
                    localPlaylist = name;
                    render();
                }));
            }
        } else if (tracks.isEmpty()) {
            LinearLayout empty = panel("添加本地音乐", "导入歌曲，或授权音乐目录扫描其中的音频文件。");
            empty.addView(toolbar(withIcon(primary("扫描文件夹", this::pickFolder), "folder", BG),
                    secondary("选择音频文件", this::pickAudio)));
        } else if (localTab == 0) {
            sortLocal(tracks);
            showTracks(tracks, false);
        } else {
            java.util.Map<String, LocalGroup> grouped = new java.util.LinkedHashMap<>();
            for (TidalClient.Track track : tracks) {
                LocalGroup candidate = LocalLibrary.groupFor(track, localTab);
                LocalGroup group = grouped.get(candidate.key);
                if (group == null) {
                    group = candidate;
                    grouped.put(group.key, group);
                }
                group.members.add(track);
            }
            List<LocalGroup> groups = new ArrayList<>(grouped.values());
            java.text.Collator collator = java.text.Collator.getInstance();
            java.util.Collections.sort(groups, (a, b) -> {
                if (localTab == 6) {
                    int yearOrder = Integer.compare(yearNumber(b.label), yearNumber(a.label));
                    if (yearOrder != 0) return yearOrder;
                }
                int labelOrder = collator.compare(a.label, b.label);
                return labelOrder != 0 ? labelOrder : collator.compare(a.detail, b.detail);
            });
            if (!groups.isEmpty()) {
                Button locate = action("定位", "快速定位", () -> chooseGroup(groups));
                locate.setTextSize(12);
                meta.addView(locate);
            }
            showGroups(groups);
        }
    }

    /** Category labels stay put while the library list scrolls. */
    private void pinLibraryTabs() {
        android.widget.HorizontalScrollView categories = new android.widget.HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        categories.setBackgroundColor(BG);
        LinearLayout tabs = row();
        tabs.setBaselineAligned(false);
        tabs.setPadding(dp(8), dp(2), dp(8), dp(4));
        View selected = null;
        for (int i = 0; i < LIBRARY_TABS.length; i++) {
            int tab = i;
            boolean on = localTab == i;
            TextView label = text(LIBRARY_TABS[i], 15, on ? GOLD : MUTED, true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(12), dp(8), dp(12), dp(6));
            label.setMinWidth(dp(48));
            GradientDrawable mark = new GradientDrawable();
            mark.setColor(on ? GOLD : 0x00000000);
            mark.setSize(dp(18), dp(2));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, mark);
            label.setCompoundDrawablePadding(dp(4));
            label.setContentDescription(LIBRARY_TABS[i]);
            label.setOnClickListener(v -> {
                if (localTab == tab) return;
                localTab = tab;
                render();
            });
            tabs.addView(label, new LinearLayout.LayoutParams(-2, -2));
            if (on) selected = label;
        }
        categories.addView(tabs, new android.widget.FrameLayout.LayoutParams(-2, -2));
        View anchor = listStage != null ? listStage : null;
        int index = anchor == null ? -1 : root.indexOfChild(anchor);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, -2);
        if (index >= 0) root.addView(categories, index, bar);
        else content.addView(categories, 0, bar);
        if (selected != null) {
            View shown = selected;
            categories.post(() -> categories.scrollTo(Math.max(0, shown.getLeft() - dp(24)), 0));
        }
    }

    private void libraryTools() {
        LocalLibrary library = playback == null ? null : playback.localLibrary;
        java.util.List<String> labels = new ArrayList<>();
        labels.add("已下载");
        if (library != null && library.hasFolders()) labels.add(localBusy ? "正在扫描…" : "更新音乐库");
        labels.add("补全歌词");
        new AlertDialog.Builder(this).setTitle("音乐库")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String choice = labels.get(which);
                    if ("已下载".equals(choice)) {
                        offlineAlbum = null;
                        open(Page.OFFLINE);
                    } else if ("更新音乐库".equals(choice) && library != null) {
                        scanDirectories(library.folders());
                    } else if (choice.startsWith("正在")) {
                        toast("正在扫描音乐…");
                    } else {
                        showBatchLyricsFill();
                    }
                }).show();
    }

    /** Albums, artists and the other groupings, with a letter index once the list is long. */
    private void showGroups(List<LocalGroup> groups) {
        String[] keys = new String[groups.size()];
        java.util.LinkedHashMap<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            keys[i] = sectionKey(groups.get(i).label);
            if (!index.containsKey(keys[i])) index.put(keys[i], i);
        }
        int columns = localTab == 1 ? 2 : localTab == 3 ? 3 : 1;
        showGrowing(groups.size(), (from, to) -> {
            int cursor = from;
            while (cursor < to) {
                if (cursor == 0 || !keys[cursor].equals(keys[cursor - 1])) {
                    TextView head = text(keys[cursor], 12, GOLD, true);
                    head.setPadding(dp(4), dp(12), dp(36), dp(2));
                    indexAnchors.put(cursor, head);
                    content.addView(head);
                }
                if (columns == 1) {
                    LocalGroup group = groups.get(cursor);
                    String detail = group.members.size() + " 首歌曲";
                    if (!group.detail.isEmpty()) detail = group.detail + " · " + detail;
                    content.addView(entry(group.label, detail, "", () -> openGroup(group)));
                    cursor++;
                    continue;
                }
                LinearLayout gridRow = row();
                gridRow.setPadding(0, 0, dp(20), dp(8));
                String letter = keys[cursor];
                int filled = 0;
                while (filled < columns && cursor < to && keys[cursor].equals(letter)) {
                    LocalGroup group = groups.get(cursor);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    if (filled > 0) cell.leftMargin = dp(8);
                    gridRow.addView(localTab == 1 ? albumCard(group) : artistCard(group), cell);
                    filled++;
                    cursor++;
                }
                while (filled < columns) {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    cell.leftMargin = dp(8);
                    gridRow.addView(spacer, cell);
                    filled++;
                }
                content.addView(gridRow);
            }
        });
        if (index.size() > 1) attachIndex(index);
    }

    private void attachIndex(java.util.LinkedHashMap<String, Integer> index) {
        if (listStage == null) return;
        boolean wide = false;
        for (String key : index.keySet()) if (key.length() > 1) wide = true;
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), dp(wide ? 44 : 28), content.getPaddingBottom());
        LinearLayout rail = column();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, dp(4), dp(2), dp(4));
        for (java.util.Map.Entry<String, Integer> entry : index.entrySet()) {
            TextView tick = text(entry.getKey(), wide ? 8 : 10, MUTED, true);
            tick.setGravity(Gravity.CENTER);
            tick.setIncludeFontPadding(false);
            tick.setContentDescription("定位到 " + entry.getKey());
            int at = entry.getValue();
            tick.setOnClickListener(v -> scrollToSection(at));
            rail.addView(tick, new LinearLayout.LayoutParams(-2, 0, 1));
        }
        listStage.addView(rail, new android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));
    }

    private void scrollToSection(int index) {
        fillTo(index);
        View anchor = indexAnchors.get(index);
        if (anchor == null || listScroll == null) return;
        listScroll.post(() -> listScroll.smoothScrollTo(0, anchor.getTop()));
    }

    private String sectionKey(String label) {
        if (localTab == 6) {
            java.util.regex.Matcher year = YEAR_KEY.matcher(label == null ? "" : label);
            return year.find() ? year.group() : "#";
        }
        return indexLetter(label);
    }

    /** First letter for the library index. Chinese names use pinyin on Android 10 and later. */
    private static String indexLetter(String label) {
        if (label == null) return "#";
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return "#";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                if (nameLatin == null) nameLatin = android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII; Upper");
                String converted = ((android.icu.text.Transliterator) nameLatin).transliterate(trimmed);
                if (!converted.isEmpty()) trimmed = converted;
            } catch (Exception ignored) {}
        }
        int point = trimmed.codePointAt(0);
        if (point >= 'a' && point <= 'z') return String.valueOf((char) (point - 32));
        if (point >= 'A' && point <= 'Z') return String.valueOf((char) point);
        return "#";
    }

    private LinearLayout albumCard(LocalGroup group) {
        LinearLayout card = column();
        card.setBackground(shape(SURFACE, 10));
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setOnClickListener(v -> openGroup(group));

        ImageView cover = art(0);
        cover.setBackground(shape(RAISED, 8));
        cover.setClipToOutline(true);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(-1, dp(120));
        card.addView(cover, imgLp);

        if (!group.members.isEmpty()) {
            artwork.load(cover, group.members.get(0), 320);
        }

        TextView title = single(group.label, 12, INK, true);
        title.setPadding(0, dp(6), 0, dp(1));
        card.addView(title);

        String subtitle = group.detail;
        if (subtitle.isEmpty() && !group.members.isEmpty()) {
            subtitle = group.members.get(0).artist;
        }
        if (!group.members.isEmpty()) {
            String year = group.members.get(0).payload.optString("year", "");
            if (!year.isEmpty()) subtitle += (subtitle.isEmpty() ? "" : " · ") + year;
        }
        TextView sub = single(subtitle, 10, MUTED, false);
        card.addView(sub);
        return card;
    }

    private LinearLayout artistCard(LocalGroup group) {
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(shape(SURFACE, 10));
        card.setPadding(dp(6), dp(10), dp(6), dp(10));
        card.setOnClickListener(v -> openGroup(group));

        ImageView avatar = art(60);
        avatar.setBackground(shape(RAISED, 30));
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!group.members.isEmpty()) {
            artwork.loadPhoto(avatar, group.members.get(0), 160);
        }
        card.addView(avatar, new LinearLayout.LayoutParams(dp(60), dp(60)));

        TextView name = single(group.label, 12, INK, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, dp(1));
        card.addView(name);

        TextView count = single(group.members.size() + " 首歌曲", 10, MUTED, false);
        count.setGravity(Gravity.CENTER);
        card.addView(count);
        return card;
    }

    private void renderAlbumDetailView(List<TidalClient.Track> tracks, String albumName) {
        if (tracks.isEmpty()) return;
        TidalClient.Track sample = tracks.get(0);
        LinearLayout banner = row();
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setBackground(shape(SURFACE, 12));

        ImageView coverView = art(84);
        coverView.setBackground(shape(RAISED, 8));
        artwork.load(coverView, sample, 320);
        banner.addView(coverView);

        LinearLayout meta = column();
        meta.setPadding(dp(12), 0, 0, 0);

        TextView aTitle = text(sample.album.isEmpty() ? albumName : sample.album, 16, INK, true);
        aTitle.setMaxLines(2);
        aTitle.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(aTitle);

        TextView aArtist = text(sample.artist, 12, ACCENT, false);
        aArtist.setPadding(0, dp(2), 0, dp(4));
        meta.addView(aArtist);

        LinearLayout badges = row();
        badges.setGravity(Gravity.CENTER_VERTICAL);
        AudioBadge spec = audioSpecBadge(sample);
        if (spec != null) {
            TextView bView = text(spec.text, 8, spec.textColor, true);
            bView.setPadding(dp(4), dp(1), dp(4), dp(1));
            bView.setBackground(shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            badges.addView(bView);
        }
        String year = sample.payload.optString("year", "");
        String infoText = (year.isEmpty() ? "" : year + " · ") + tracks.size() + " 首歌曲";
        TextView iView = text(infoText, 10, MUTED, false);
        iView.setPadding(dp(6), 0, 0, 0);
        badges.addView(iView);
        meta.addView(badges);

        banner.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(banner);

        Button all = withIcon(primary("播放全部", () -> playList(tracks, 0, false)), "play", BG);
        Button random = withIcon(secondary("随机播放", () -> playList(tracks, 0, true)), "shuffle", ACCENT);
        content.addView(toolbar(all, random));

        int lastDisc = -1;
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            TidalClient.Track track = tracks.get(i);
            int disc = track.payload.optInt("disc_number", 0);
            if (disc > 0 && disc != lastDisc) {
                lastDisc = disc;
                TextView discHeader = text("Disc " + disc, 12, GOLD, true);
                discHeader.setPadding(dp(4), dp(10), dp(4), dp(3));
                content.addView(discHeader);
            }
            content.addView(albumTrackEntry(track, i + 1, () -> playList(tracks, index, false)));
        }
    }

    private void openGroup(LocalGroup group) {
        localGroup = group;
        render();
    }

    private interface QueryFilter { void apply(String query); }

    private void chooseGroup(List<LocalGroup> groups) {
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("输入分类名称");
        List<String> labels = new ArrayList<>();
        List<LocalGroup> matches = new ArrayList<>();
        android.widget.ArrayAdapter<String> results = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(results);
        LinearLayout body = column();
        body.addView(query);
        body.addView(list, new LinearLayout.LayoutParams(-1, dp(280)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("快速定位")
                .setView(body).setNegativeButton("取消", null).create();
        QueryFilter filter = term -> {
            String needle = term.toLowerCase(Locale.ROOT).trim();
            matches.clear();
            results.setNotifyOnChange(false);
            results.clear();
            for (LocalGroup group : groups) {
                String full = group.label + " " + group.detail;
                if (full.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(group);
                    results.add(group.detail.isEmpty() ? group.label : group.label + " · " + group.detail);
                }
            }
            results.notifyDataSetChanged();
        };
        filter.apply("");
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.apply(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable editable) {}
        });
        list.setOnItemClickListener((view, item, position, id) -> {
            dialog.dismiss();
            openGroup(matches.get(position));
        });
        dialog.show();
    }

    private void chooseLocalSort() {
        new AlertDialog.Builder(this).setTitle("歌曲排序")
                .setSingleChoiceItems(LIBRARY_SORTS, localSort, (dialog, index) -> {
                    localSort = index;
                    getPreferences(MODE_PRIVATE).edit().putInt("local_sort", index).apply();
                    dialog.dismiss();
                    render();
                }).setNegativeButton("取消", null).show();
    }

    private void sortLocal(List<TidalClient.Track> tracks) {
        int order = localSort;
        if (order == 4) return;
        java.text.Collator collator = java.text.Collator.getInstance();
        java.util.Collections.sort(tracks, (a, b) -> {
            int key;
            switch (order) {
                case 1: key = collator.compare(a.artist, b.artist); break;
                case 2: key = collator.compare(a.album, b.album); break;
                case 3: key = Integer.compare(yearNumber(b.payload.optString("year")), yearNumber(a.payload.optString("year"))); break;
                default: key = 0; break;
            }
            return key != 0 ? key : collator.compare(a.title, b.title);
        });
    }

    private static int yearNumber(String year) {
        try { return Integer.parseInt(year); }
        catch (NumberFormatException absent) { return 0; }
    }

    private static int trackNumber(TidalClient.Track track, String field, int fallback) {
        String value = track.payload.optString(field).split("/", 2)[0].trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException absent) { return fallback; }
    }

    private static void sortAlbum(List<TidalClient.Track> tracks) {
        java.util.Collections.sort(tracks, (a, b) -> {
            int disc = Integer.compare(trackNumber(a, "disc_number", 1), trackNumber(b, "disc_number", 1));
            if (disc != 0) return disc;
            int track = Integer.compare(trackNumber(a, "track_number", Integer.MAX_VALUE),
                    trackNumber(b, "track_number", Integer.MAX_VALUE));
            return track != 0 ? track : a.title.compareToIgnoreCase(b.title);
        });
    }

    private void localResults(String query) {
        if (remoteMode()) {
            remoteLibTab = 0;
            remoteLibQuery = query;
            remoteLibPlaylist = "";
            remoteLibGroup = "";
            remoteLibGroupTitle = "";
            if (query.isEmpty()) {
                empty("搜索 " + devices.targetName() + " 的本地音乐", "可以按歌曲、艺人、专辑、流派或年份查找。");
                return;
            }
            remoteLibraryPage(false);
            return;
        }
        if (query.isEmpty()) {
            empty("搜索本地音乐", "可以按歌曲、艺人、专辑、流派或年份查找。");
        } else if (playback != null) {
            List<TidalClient.Track> result = playback.localLibrary.search(query, null);
            List<LocalGroup> albums = localGroups(result, 1, query);
            List<LocalGroup> artists = localGroups(result, 3, query);
            if (result.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
                empty("没有找到匹配的本地音乐", "");
                return;
            }
            groupShelf("专辑", null, albums);
            groupShelf("艺人", null, artists);
            if (!result.isEmpty()) {
                section("歌曲");
                sortLocal(result);
                showTracks(result, false);
            }
        }
    }

    /** Albums or artists whose name contains the query. */
    private static List<LocalGroup> localGroups(List<TidalClient.Track> tracks, int category, String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, LocalGroup> grouped = new java.util.LinkedHashMap<>();
        for (TidalClient.Track track : tracks) {
            LocalGroup candidate = LocalLibrary.groupFor(track, category);
            if (!candidate.label.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            LocalGroup group = grouped.get(candidate.key);
            if (group == null) {
                if (grouped.size() >= 12) continue;
                group = candidate;
                grouped.put(group.key, group);
            }
            if (group.members.size() < 40) group.members.add(track);
        }
        return new ArrayList<>(grouped.values());
    }

    private void importMenu() {
        new AlertDialog.Builder(this).setTitle("添加本地音乐")
                .setItems(new String[]{"扫描文件夹", "选择音频文件", "管理已授权目录"}, (dialog, which) -> {
                    if (which == 0) pickFolder();
                    else if (which == 1) pickAudio();
                    else chooseFolders();
                }).show();
    }

    private void newLocalPlaylist(TidalClient.Track initial) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("歌单名称");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("新建本地歌单").setView(name)
                .setNegativeButton("取消", null).setPositiveButton("创建", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (playback == null) return;
            String value = name.getText().toString().trim();
            if (!playback.localLibrary.create(value)) {
                name.setError("请输入新的名称（1–64 个字符）");
                return;
            }
            if (initial != null) playback.localLibrary.add(value, initial);
            dialog.dismiss();
            playback.libraryChanged();
            toast("已创建歌单");
        });
    }

    private void deleteLocalPlaylist() {
        String name = localPlaylist;
        new AlertDialog.Builder(this).setTitle("删除歌单 " + name + "？")
                .setMessage("音频文件仍会保留。").setNegativeButton("取消", null)
                .setPositiveButton("删除歌单", (dialog, which) -> {
                    if (playback == null) return;
                    playback.localLibrary.deletePlaylist(name);
                    localPlaylist = null;
                    playback.libraryChanged();
                }).show();
    }

    private void catalogue() {
        if (page != Page.COLLECTION) {
            LinearLayout chips = row();
            String[] names = page == Page.LIBRARY
                    ? new String[]{"歌曲", "专辑", "歌单", "艺人", "自建"}
                    : new String[]{"歌曲", "专辑", "歌单", "艺人"};
            for (int i = 0; i < names.length; i++) {
                int selected = i;
                Button chip = action(names[i], names[i], () -> {
                    if (page == Page.LIBRARY) libraryFilter = selected;
                    else filter = selected;
                    load(false);
                });
                if (i == (page == Page.LIBRARY ? libraryFilter : filter)) chip.setBackground(shape(RAISED, 18));
                chips.addView(chip);
            }
            content.addView(chips);
        } else {
            artHeader(artistDetails == null ? collection : artistDetails.artist);
        }

        if (catalogueLoading && tracks.isEmpty() && collections.isEmpty()) {
            empty("正在加载…", "");
        } else if (!notice.isEmpty()) {
            empty(notice, "");
            if (notice.startsWith("加载失败")) content.addView(action("重试", "重试", () -> load(false)));
        } else if (artistDetails != null) {
            if (!tracks.isEmpty()) {
                section("热门歌曲");
                showTracks(new ArrayList<>(tracks), false, showAllArtistTracks ? Integer.MAX_VALUE : 8);
                if (tracks.size() > 8) {
                    String label = showAllArtistTracks ? "收起热门歌曲" : "查看全部热门歌曲";
                    content.addView(action(label, label, () -> {
                        showAllArtistTracks = !showAllArtistTracks;
                        render();
                    }));
                }
            }
            artistSection("专辑", artistDetails.albums);
            artistSection("单曲与 EP", artistDetails.singles);
            artistSection("相似艺人", artistDetails.similar);
            if (!artistDetails.biography.isEmpty()) {
                section("艺人简介");
                TextView biography = text(artistDetails.biography, 15, INK, false);
                biography.setMaxLines(5);
                biography.setEllipsize(android.text.TextUtils.TruncateAt.END);
                content.addView(biography);
                content.addView(action("阅读简介", "阅读艺人简介", () -> new AlertDialog.Builder(this)
                        .setTitle(artistDetails.artist.title).setMessage(artistDetails.biography)
                        .setPositiveButton("关闭", null).show()));
            }
            if (tracks.isEmpty() && artistDetails.albums.isEmpty() && artistDetails.singles.isEmpty()
                    && artistDetails.similar.isEmpty() && artistDetails.biography.isEmpty()) {
                empty("暂无艺人作品", "");
            }
        } else if (!tracks.isEmpty() || !collections.isEmpty()) {
            if (!tracks.isEmpty()) showTracks(new ArrayList<>(tracks), page == Page.LIBRARY);
            if (page == Page.COLLECTION && collection.kind == TidalClient.Collection.Kind.ARTIST
                    && !collections.isEmpty()) section("专辑");
            for (TidalClient.Collection item : collections) collectionRow(item);
        } else if (page == Page.SEARCH && term.isEmpty()) {
            empty("搜索音乐", "输入歌曲名、艺人名，或按类型查找专辑和歌单");
        } else {
            empty("暂无内容", "");
        }
        if (hasMore) content.addView(action("加载更多", "加载更多", () -> load(true)));
    }

    private void load(boolean append) {
        int request = ++generation;
        if (catalogue != null) catalogue.cancel(true);
        if (!append) {
            tracks.clear();
            collections.clear();
            artistDetails = null;
            showAllArtistTracks = false;
        }
        notice = "";
        hasMore = false;
        catalogueLoading = false;
        if (page == Page.SEARCH && term.isEmpty()) {
            render();
            return;
        }
        TidalClient api = tidalApi();
        boolean useNetease = page == Page.SEARCH && searchSource == SearchSource.NETEASE
                || page == Page.COLLECTION && collection.isNetease();
        NeteaseClient netease = playback == null ? null : playback.netease();
        if (useNetease && netease == null) {
            notice = "播放器正在连接，请稍后重试";
            render();
            return;
        }
        if (!useNetease && api == null) {
            notice = "请先登录 TIDAL";
            render();
            return;
        }
        Page requestedPage = page;
        int requestedFilter = requestedPage == Page.LIBRARY ? libraryFilter : filter;
        String query = term;
        TidalClient.Collection selected = collection;
        boolean collectionList = requestedPage != Page.COLLECTION && requestedFilter != 0;
        int offset = collectionList ? collections.size() : tracks.size();
        catalogueLoading = true;
        render();
        catalogue = worker.submit(() -> {
            try {
                if (requestedPage == Page.COLLECTION && selected.kind == TidalClient.Collection.Kind.ARTIST
                        && !useNetease) {
                    ArtistDetails details = new ArtistDetails(api, selected);
                    runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        artistDetails = details;
                        tracks.addAll(details.top);
                        finishLoad(false);
                    });
                } else if (collectionList) {
                    TidalClient.Collection.Kind kind;
                    switch (requestedFilter) {
                        case 1: kind = TidalClient.Collection.Kind.ALBUM; break;
                        case 3: kind = TidalClient.Collection.Kind.ARTIST; break;
                        default: kind = TidalClient.Collection.Kind.PLAYLIST;
                    }
                    List<TidalClient.Collection> found = useNetease ? netease.collections(query, kind, offset) : api.collections(
                            requestedPage == Page.LIBRARY ? "" : query, kind, offset, requestedFilter == 4);
                    runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        collections.addAll(found);
                        finishLoad(found.size() == 40);
                    });
                } else {
                    List<TidalClient.Track> found;
                    List<TidalClient.Collection> releases = new ArrayList<>();
                    boolean artist = requestedPage == Page.COLLECTION && selected.kind == TidalClient.Collection.Kind.ARTIST;
                    if (requestedPage == Page.COLLECTION) {
                        found = useNetease ? netease.collectionTracks(selected, offset) : api.collectionTracks(selected, offset);
                        if (artist && offset == 0) {
                            try {
                                releases.addAll(netease.artistAlbums(selected.id));
                            } catch (Exception ignored) {
                                // The playable top tracks remain available if releases cannot be loaded.
                            }
                        }
                    } else if (requestedPage == Page.LIBRARY) {
                        found = api.favorites(offset);
                    } else {
                        found = useNetease ? netease.search(query, offset) : api.search(query, offset);
                    }
                    runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        tracks.addAll(found);
                        collections.addAll(releases);
                        if (requestedPage == Page.LIBRARY) {
                            for (TidalClient.Track track : found) {
                                favoriteIds.add(track.id);
                            }
                        }
                        boolean paged = !artist && (!useNetease || requestedPage != Page.COLLECTION
                                || selected.kind == TidalClient.Collection.Kind.PLAYLIST);
                        finishLoad(paged && found.size() == PAGE_SIZE);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!current(request, requestedPage)) return;
                    notice = "加载失败 · " + readable(e);
                    finishLoad(false);
                });
            }
        });
    }

    private boolean current(int request, Page requestedPage) {
        return !isDestroyed() && page == requestedPage && request == generation;
    }

    private void finishLoad(boolean more) {
        catalogueLoading = false;
        hasMore = more;
        render();
    }

    private void artHeader(TidalClient.Collection item) {
        boolean artist = item.kind == TidalClient.Collection.Kind.ARTIST;
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(6));
        ImageView image = art(140);
        if (artist) image.setBackground(shape(RAISED, 70));
        artwork.load(image, item.cover, 320, item.title);
        header.addView(image, new LinearLayout.LayoutParams(dp(140), dp(140)));
        LinearLayout info = column();
        info.setPadding(dp(16), 0, 0, 0);
        info.addView(text(collectionKind(item), 12, ACCENT, true));
        TextView name = text(item.title, 22, INK, true);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(2), 0, dp(4));
        info.addView(name);
        if (!item.subtitle.isEmpty()) info.addView(single(item.subtitle, 13, MUTED, false));
        if (item.kind == TidalClient.Collection.Kind.ALBUM && !item.isNetease()) {
            Button download = withIcon(secondary("下载专辑",
                    () -> chooseTidalDownload(item, java.util.Collections.emptyList())), "download", ACCENT);
            mark(() -> {
                int saved = downloaded == null ? 0 : downloaded.album(item);
                download.setText(saved > 0 ? "已下载 " + saved + " 首 · 继续下载" : "下载专辑");
            });
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, dp(36));
            chip.topMargin = dp(12);
            info.addView(download, chip);
        }
        header.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(header);
        if (item.kind != TidalClient.Collection.Kind.ARTIST || item.isNetease()) {
            section(item.kind == TidalClient.Collection.Kind.ARTIST ? "热门歌曲"
                    : item.kind == TidalClient.Collection.Kind.PLAYLIST ? "歌单曲目"
                    : item.kind == TidalClient.Collection.Kind.MIX ? "混音曲目" : "专辑曲目");
        }
    }

    private void artistSection(String title, List<TidalClient.Collection> items) {
        if (items.isEmpty()) return;
        section(title);
        int preview = Math.min(6, items.size());
        for (int i = 0; i < preview; i++) collectionRow(items.get(i));
        if (items.size() > preview) {
            content.addView(action("查看全部 · " + title, "查看全部" + title, () -> {
                String[] names = new String[items.size()];
                for (int i = 0; i < names.length; i++) {
                    TidalClient.Collection item = items.get(i);
                    names[i] = item.subtitle.isEmpty() ? item.title : item.title + " · " + item.subtitle;
                }
                new AlertDialog.Builder(this).setTitle(title).setItems(names, (dialog, index) ->
                        openCollection(items.get(index))).setNegativeButton("关闭", null).show();
            }));
        }
    }

    private static String collectionKind(TidalClient.Collection item) {
        switch (item.kind) {
            case ARTIST: return "艺人";
            case PLAYLIST: return "歌单";
            case MIX: return "混音";
            default: return "专辑";
        }
    }

    private void collectionRow(TidalClient.Collection item) {
        String type = collectionKind(item);
        String detail = item.subtitle.isEmpty() ? type : item.subtitle;
        LinearLayout row = entry(item.title, detail, item.cover, () -> openCollection(item));
        TextView subtitle = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1);
        mark(() -> {
            int saved = item.kind == TidalClient.Collection.Kind.ALBUM && downloaded != null ? downloaded.album(item) : 0;
            subtitle.setText(saved > 0 ? "已下载 " + saved + " 首 · " + detail : detail);
            subtitle.setTextColor(saved > 0 ? ACCENT : MUTED);
        });
        content.addView(row);
    }

    private void openCollection(TidalClient.Collection item) {
        if (page == Page.COLLECTION) {
            if (collection.kind == item.kind && collection.id.equals(item.id)) return;
            collectionTrail.push(collection);
            collection = item;
            load(false);
        } else {
            collectionTrail.clear();
            collectionBack = page;
            collection = item;
            open(Page.COLLECTION);
        }
    }

    private void showTracks(List<TidalClient.Track> source, boolean favoriteList) {
        showTracks(source, favoriteList, Integer.MAX_VALUE);
    }

    private void showTracks(List<TidalClient.Track> source, boolean favoriteList, int maximumVisible) {
        List<TidalClient.Track> downloadable = new ArrayList<>();
        for (TidalClient.Track track : source) {
            if (track.needsTidal()) downloadable.add(track);
        }
        Button all = withIcon(primary("全部播放", () -> playList(source, 0, false)), "play", BG);
        Button random = withIcon(secondary("随机", () -> playList(source, 0, true)), "shuffle", ACCENT);
        content.addView(downloadable.size() > 1
                ? toolbar(all, random, withIcon(secondary("多选下载", () -> selectTidalDownloads(downloadable)), "download", ACCENT))
                : toolbar(all, random));
        boolean local = !source.isEmpty() && source.get(0).isLocal();
        int limit = Math.min(source.size(), maximumVisible);
        if (revealTrackId != Long.MIN_VALUE) {
            for (int i = limit; i < source.size(); i++) {
                if (source.get(i).id == revealTrackId) {
                    limit = i + 1;
                    break;
                }
            }
        }
        RangeDrawer draw = (from, to) -> {
            for (int i = from; i < to; i++) {
                int index = i;
                content.addView(trackEntry(source.get(index), favoriteList, () -> playList(source, index, false)));
            }
        };
        if (local) {
            showGrowing(limit, draw);
            if (revealTrackId != Long.MIN_VALUE) {
                for (int i = LOCAL_PAGE_SIZE; i < limit; i++) {
                    if (source.get(i).id == revealTrackId) {
                        fillTo(i);
                        break;
                    }
                }
            }
        } else draw.draw(0, limit);
    }

    /** Draw the first page now, and the rest when the list scrolls near the bottom. */
    private void showGrowing(int total, RangeDrawer drawer) {
        growTotal = total;
        growDrawer = drawer;
        grown = new int[] {Math.min(total, LOCAL_PAGE_SIZE)};
        if (grown[0] > 0) drawer.draw(0, grown[0]);
        armGrow();
    }

    private void armGrow() {
        if (grown == null || grown[0] >= growTotal) {
            loadMore = null;
            return;
        }
        loadMore = () -> fillTo(grown[0] + LOCAL_PAGE_SIZE - 1);
    }

    private void fillTo(int index) {
        if (loadingMore || grown == null || growDrawer == null) return;
        loadingMore = true;
        int guard = 0;
        while (grown[0] <= index && grown[0] < growTotal && guard++ < 80) {
            int from = grown[0];
            int to = Math.min(growTotal, Math.max(from + LOCAL_PAGE_SIZE, index + 1));
            growDrawer.draw(from, to);
            grown[0] = to;
        }
        loadingMore = false;
        armGrow();
    }

    private void playList(List<TidalClient.Track> list, int index, boolean random) {
        if (remoteMode() && !list.isEmpty()) {
            remoteTracks(list, index, random, "play");
            return;
        }
        if (playback == null || list.isEmpty()) return;
        playback.replaceQueue(list, index, random);
        updatePlayer();
    }

    private LinearLayout trackEntry(TidalClient.Track track, boolean favoriteList, Runnable playTrack) {
        LinearLayout row = row();
        row.setTag(track.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setBackground(shape(SURFACE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(3);
        row.setLayoutParams(params);

        ImageView image = art(36);
        image.setBackground(shape(RAISED, 6));
        artwork.load(image, track, 160);
        image.setOnClickListener(v -> playTrack.run());
        row.addView(image);

        LinearLayout info = column();
        info.setPadding(dp(10), 0, dp(6), 0);

        TextView titleView = single(displayTitle(track), 13, INK, true);
        info.addView(titleView);

        LinearLayout metaRow = row();
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        AudioBadge spec = audioSpecBadge(track);
        if (spec != null) {
            TextView bView = text(spec.text, 8, spec.textColor, true);
            bView.setPadding(dp(3), dp(1), dp(3), dp(1));
            bView.setBackground(shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.rightMargin = dp(4);
            metaRow.addView(bView, bLp);
        }

        String sourceName = track.isLocal() ? "本地"
                : track.isNetease() ? "网易云"
                : track.isWebdav() ? "WebDAV"
                : track.isSmb() ? "SMB" : "TIDAL";
        TextView sView = text(sourceName, 8, MUTED, false);
        sView.setPadding(dp(3), dp(1), dp(3), dp(1));
        sView.setBackground(shape(0x18ffffff, 3));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.rightMargin = dp(5);
        metaRow.addView(sView, sLp);

        String byline = track.artist;
        if (!track.album.isEmpty()) byline += (byline.isEmpty() ? "" : " · ") + track.album;
        if (byline.isEmpty()) byline = sourceName;
        TextView artistView = single(byline, 11, MUTED, false);
        metaRow.addView(artistView, new LinearLayout.LayoutParams(0, -2, 1));

        info.addView(metaRow);
        info.setOnClickListener(v -> playTrack.run());
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        long dur = DownloadTasks.length(track);
        if (dur > 0) {
            TextView durView = text(formatTime(dur * 1000), 10, MUTED, false);
            durView.setPadding(0, 0, dp(4), 0);
            row.addView(durView);
        }

        row.addView(iconButton("more", "更多 " + track.title, () -> trackMenu(track, favoriteList)),
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        return row;
    }

    private LinearLayout albumTrackEntry(TidalClient.Track track, int trackNum, Runnable playTrack) {
        LinearLayout row = row();
        row.setTag(track.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setBackground(shape(SURFACE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.bottomMargin = dp(2);
        row.setLayoutParams(params);

        boolean isCurrent = playback != null && playback.current() != null && playback.current().id == track.id;

        TextView numView = text(isCurrent ? "▶" : String.format(Locale.ROOT, "%02d", trackNum), 11, isCurrent ? GOLD : MUTED, isCurrent);
        numView.setGravity(Gravity.CENTER);
        row.addView(numView, new LinearLayout.LayoutParams(dp(28), -2));

        LinearLayout info = column();
        info.setPadding(dp(6), 0, dp(6), 0);

        TextView titleView = single(displayTitle(track), 13, isCurrent ? GOLD : INK, isCurrent);
        info.addView(titleView);

        if (!track.artist.isEmpty()) {
            TextView artistView = single(track.artist, 10, MUTED, false);
            info.addView(artistView);
        }
        info.setOnClickListener(v -> playTrack.run());
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        AudioBadge spec = audioSpecBadge(track);
        if (spec != null) {
            TextView bView = text(spec.text, 8, spec.textColor, true);
            bView.setPadding(dp(3), dp(1), dp(3), dp(1));
            bView.setBackground(shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.rightMargin = dp(6);
            row.addView(bView, bLp);
        }

        long dur = DownloadTasks.length(track);
        if (dur > 0) {
            TextView durView = text(formatTime(dur * 1000), 10, MUTED, false);
            durView.setPadding(0, 0, dp(4), 0);
            row.addView(durView);
        }

        row.addView(iconButton("more", "更多 " + track.title, () -> trackMenu(track, false)),
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        return row;
    }

    private void trackMenu(TidalClient.Track track, boolean favoriteList) {
        if (track.isLocal()) {
            localTrackMenu(track);
            return;
        }
        if (track.isWebdav()) {
            webdavTrackMenu(track, null);
            return;
        }
        if (track.isSmb()) {
            smbTrackMenu(track, null);
            return;
        }
        boolean liked = favoriteList || favoriteIds.contains(track.id);
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (remoteMode() && !track.isNetease()) {
            String name = devices.targetName();
            choices.add("在 " + name + " 播放");
            actions.add(() -> remoteTracks(java.util.Collections.singletonList(track), 0, false, "play"));
            choices.add("下一首播放 · " + name);
            actions.add(() -> { if (remoteAdd(track, true)) toast("已发送到 " + name + " 的下一首"); });
            choices.add("追加到队列 · " + name);
            actions.add(() -> { if (remoteAdd(track, false)) toast("已发送到 " + name + " 的队列"); });
        } else {
            choices.add("下一首播放");
            actions.add(() -> { playback.enqueue(track, true); toast("已加入下一首"); });
            choices.add("追加到队列");
            actions.add(() -> { playback.enqueue(track, false); toast("已加入队列"); });
        }
        if (!track.isNetease()) {
            choices.add(liked ? "取消收藏" : "收藏歌曲");
            actions.add(() -> changeFavorite(track, !liked));
        }
        choices.add("分享歌曲");
        actions.add(() -> share(track));
        if (track.isNetease()) {
            choices.add("查看评论");
            actions.add(() -> showNeteaseComments(track));
            choices.add(downloadLabel(track));
            actions.add(() -> downloadNetease(track));
        } else {
            choices.add(downloadLabel(track));
            actions.add(() -> downloadTidal(track));
            if (track.artistId > 0 && !track.artist.isEmpty()) {
                choices.add("查看艺人");
                actions.add(() -> {
                    try {
                        openCollection(new TidalClient.Collection(new JSONObject().put("id", track.artistId)
                                .put("name", track.artist).put("picture", track.artistPicture),
                                TidalClient.Collection.Kind.ARTIST));
                    } catch (org.json.JSONException ignored) {}
                });
            }
            choices.add("歌曲电台");
            actions.add(() -> playRadio(track));
        }
        if (track.albumId > 0) {
            choices.add("查看专辑");
            actions.add(() -> {
                try {
                    openCollection(new TidalClient.Collection(new org.json.JSONObject()
                            .put("id", track.albumId).put("title", track.album).put("cover", track.cover),
                            TidalClient.Collection.Kind.ALBUM));
                } catch (org.json.JSONException ignored) {}
            });
        }
        new AlertDialog.Builder(this).setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, choice) -> {
                    if (playback != null) actions.get(choice).run();
                }).show();
    }

    private void showNeteaseComments(TidalClient.Track track) {
        if (playback == null || track == null || !track.isNetease()) return;
        new NeteaseCommentsDialog(this, playback.netease(), track, worker).show();
    }

    private void downloadNetease(TidalClient.Track track) {
        DownloadTasks.Resolve resolve = downloadResolver("网易云");
        if (resolve == null) {
            toast("请先配置网易云接口");
            return;
        }
        downloadChoices("下载 · " + track.title, DownloadTasks.HIGHEST, MusicDownload.directory(this),
                (quality, directory) -> queueDownloads(java.util.Collections.singletonList(track), "网易云",
                        quality, directory, resolve));
    }

    /** "下载歌曲", or "已下载（Max）· 重新检查" when it is on the phone; downloading again checks and repairs it. */
    private String downloadLabel(TidalClient.Track track) {
        String saved = downloaded == null ? null : downloaded.label(track);
        if (saved == null) return "下载歌曲";
        if (saved.startsWith("已下载 · ")) return "已下载（" + saved.substring("已下载 · ".length()) + "）· 重新检查";
        return saved + " · 重新检查";
    }

        private void downloadTidal(TidalClient.Track track) {
        chooseTidalDownload(null, java.util.Collections.singletonList(track));
    }

    private void selectTidalDownloads(List<TidalClient.Track> options) {
        String[] labels = new String[options.size()];
        boolean[] selected = new boolean[options.size()];
        for (int i = 0; i < options.size(); i++) {
            TidalClient.Track track = options.get(i);
            labels[i] = track.artist.isEmpty() ? track.title : track.title + " · " + track.artist;
            if (downloaded != null && downloaded.label(track) != null) labels[i] += "（已下载）";
        }
        AlertDialog dialogue = new AlertDialog.Builder(this).setTitle("选择下载歌曲")
                .setMultiChoiceItems(labels, selected, (dialog, index, checked) -> {
                    selected[index] = checked;
                    boolean any = false;
                    for (boolean item : selected) any |= item;
                    ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                }).setPositiveButton("继续", (dialog, button) -> {
                    List<TidalClient.Track> chosen = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) {
                        if (selected[i]) chosen.add(options.get(i));
                    }
                    chooseTidalDownload(null, chosen);
                }).setNegativeButton("取消", null).show();
        dialogue.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void chooseTidalDownload(TidalClient.Collection album, List<TidalClient.Track> selected) {
        if (remoteDownloads()) {
            remoteDownload(album, selected);
            return;
        }
        TidalClient api = playback == null ? null : playback.client;
        if (api == null) {
            toast("请先登录 TIDAL");
            return;
        }
        String title = album == null ? "下载 " + selected.size() + " 首" : "下载专辑";
        DownloadTasks.Resolve resolve = downloadResolver("TIDAL");
        downloadChoices(title, DownloadTasks.HIGHEST, MusicDownload.directory(this), (quality, directory) -> {
            toast("正在读取曲目信息");
            downloads.submit(() -> {
                try {
                    List<TidalClient.Track> items;
                    TidalClient.Collection metadata = album;
                    if (album != null) {
                        metadata = DownloadTasks.album(api, album.id, album);
                        items = api.albumTracks(metadata);
                    } else items = DownloadTasks.withAlbums(api, selected);
                    if (items.isEmpty()) throw new IOException("暂无可下载的曲目");
                    int skipped = downloadTasks.add(items, "TIDAL", quality, directory, resolve, metadata);
                    handler.post(() -> showQueuedDownloads(skipped));
                } catch (Exception error) {
                    handler.post(() -> {
                        if (!isDestroyed()) toast("下载失败 · " + readable(error));
                    });
                }
            });
        });
    }

    private static final String[] REMOTE_DOWNLOAD_QUALITIES = {"BEST", "HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"};

    /** Download on the controlled device, with its TIDAL login and into its download folder. */
    private void remoteDownload(TidalClient.Collection album, List<TidalClient.Track> selected) {
        JSONArray ids = new JSONArray();
        if (album == null) {
            for (TidalClient.Track track : selected) {
                if (track.needsTidal() && !track.isNetease() && track.id > 0 && ids.length() < 100) ids.put(track.id);
            }
            if (ids.length() == 0) {
                toast("没有可在远程设备下载的 TIDAL 歌曲");
                return;
            }
        }
        String device = devices.targetName();
        String[] names = {"最高可用（Max 或 High）", "Max", "High", "Low", "Low 96k"};
        int[] choice = {0};
        String what = album == null ? ids.length() + " 首歌曲" : "专辑「" + album.title + "」";
        new AlertDialog.Builder(this).setTitle("在「" + device + "」下载" + what)
                .setSingleChoiceItems(names, 0, (dialog, which) -> choice[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", (dialog, button) -> {
                    try {
                        JSONObject command = new JSONObject().put("op", "download")
                                .put("quality", REMOTE_DOWNLOAD_QUALITIES[choice[0]]);
                        if (album != null) command.put("album", album.id);
                        else command.put("ids", ids);
                        toast("正在发送到 " + device);
                        devices.query(command, (result, error) -> {
                            if (isDestroyed()) return;
                            if (!error.isEmpty()) {
                                toast("下载失败 · " + error);
                                return;
                            }
                            int skipped = result.optInt("skipped");
                            int queued = result.optInt("queued", -1);
                            toast(queued < 0 ? "已在 " + device + " 加入下载"
                                    : "已在 " + device + " 加入 " + queued + " 首" + (skipped > 0 ? "，" + skipped + " 首已下载" : ""));
                            remoteDownloadList = result;
                            open(Page.DOWNLOADS);
                        });
                    } catch (org.json.JSONException ignored) {}
                }).show();
    }

    /** What the controlled device has saved, so lists here mark its downloads rather than this phone's. */
    private DownloadTasks.Downloaded remoteMarks;

    /** Views showing download marks on this page, updated in place when the marks change. */
    private final List<Runnable> markUpdates = new ArrayList<>();

    private long marksNextCheck;
    private String localMarksStamp = "";

    /** Songs this phone finishes downloading get marked on the page shown, without rebuilding it. */
    private void refreshLocalMarks() {
        if (playback == null || remoteDownloads() || markUpdates.isEmpty()) return;
        String stamp = downloadTasks.revision() + ":" + playback.localRevision;
        if (stamp.equals(localMarksStamp)) return;
        boolean first = localMarksStamp.isEmpty();
        localMarksStamp = stamp;
        if (first) return;
        downloaded = shownDownloads();
        for (Runnable update : new ArrayList<>(markUpdates)) update.run();
    }

    private void mark(Runnable update) {
        update.run();
        markUpdates.add(update);
    }
    private String remoteMarksKey = "";
    private long remoteMarksAt;
    private boolean remoteMarksBusy;

    private DownloadTasks.Downloaded shownDownloads() {
        if (remoteDownloads()) return remoteMarks;
        return downloadTasks.downloaded(playback == null ? -1 : playback.localRevision);
    }

    /** Fetch the controlled device's saved songs when they may have changed. */
    private void syncRemoteMarks() {
        if (!remoteDownloads() || remoteMarksBusy) return;
        String key = devices.target + ":" + devices.state.filesRevision;
        long now = android.os.SystemClock.elapsedRealtime();
        // Desktop devices report no files revision, so refresh theirs now and then.
        if (key.equals(remoteMarksKey) && (remoteMarks != null && (!devices.state.filesRevision.isEmpty()
                || now - remoteMarksAt < 60_000))) return;
        remoteMarksBusy = true;
        String target = devices.target;
        try {
            devices.query(new JSONObject().put("op", "downloads").put("action", "marks"), (result, error) -> {
                remoteMarksBusy = false;
                remoteMarksAt = android.os.SystemClock.elapsedRealtime();
                remoteMarksKey = key;
                if (!error.isEmpty() || devices == null || !target.equals(devices.target)) return;
                remoteMarks = DownloadTasks.Downloaded.fromJson(result.optJSONObject("marks"));
                downloaded = remoteMarks;
                // Only the marks change, so browsing is not interrupted while the device downloads.
                for (Runnable update : new ArrayList<>(markUpdates)) update.run();
            });
        } catch (org.json.JSONException ignored) {
            remoteMarksBusy = false;
        }
    }

    /** The controlled device's latest download list, while the downloads page shows it. */
    private JSONObject remoteDownloadList;
    /** Rows asked of the controlled device; "显示更多" raises it. */
    private int remoteDownloadLimit = 300;
    private boolean visibleRemoteDownloads;

    private void remoteDownloadsArrived(JSONObject result) {
        remoteDownloadList = result;
        if (page != Page.DOWNLOADS || isDestroyed() || !visibleRemoteDownloads) return;
        if (remoteParallelRow != null) {
            String label = remoteParallelLabel();
            if (!TextUtils.equals(remoteParallelRow.getText(), label)) remoteParallelRow.setText(label);
        }
        showDownloadRows(RemoteTidal.parse(result));
    }

    private TextView remoteParallelRow;

    private String remoteParallelLabel() {
        int parallel = remoteDownloadList == null ? 0 : remoteDownloadList.optInt("parallel");
        return parallel > 0 ? "同时下载 · " + parallel + " 首" : "同时下载";
    }

    private void remoteDownloadAction(String action, String id) {
        if (!remoteDownloads()) return;
        try {
            JSONObject command = new JSONObject().put("op", "downloads").put("action", action)
                    .put("limit", remoteDownloadLimit);
            if (id != null) command.put("id", id);
            devices.query(command, (result, error) -> {
                if (!error.isEmpty()) toast(error);
                else remoteDownloadsArrived(result);
            });
        } catch (org.json.JSONException ignored) {}
    }

    /** Selected rows of the controlled device's list, cancelled, retried or removed there at once. */
    private void remoteDownloadBatch(DownloadBatch action, List<DownloadTasks.Job> targets) {
        if (!remoteDownloads()) return;
        String name = action == DownloadBatch.CANCEL ? "cancel" : action == DownloadBatch.RETRY ? "retry" : "remove";
        JSONArray ids = new JSONArray();
        for (DownloadTasks.Job job : targets) {
            ids.put(job.id);
            selectedDownloads.remove(job.id);
        }
        try {
            devices.query(new JSONObject().put("op", "downloads").put("action", name).put("ids", ids)
                    .put("limit", remoteDownloadLimit), (result, error) -> {
                if (!error.isEmpty()) {
                    toast(error);
                    return;
                }
                int skipped = result.optInt("skipped");
                if (skipped > 0) toast(skipped + " 项未处理" + (action == DownloadBatch.RETRY
                        ? "，" + devices.targetName() + " 可能未连接对应音源" : ""));
                remoteDownloadsArrived(result);
            });
        } catch (org.json.JSONException ignored) {}
    }

    private void playRemoteDownload(DownloadTasks.Job job) {
        try {
            if (devices.send(new JSONObject().put("op", "tracks").put("ids", new JSONArray().put(job.track))
                    .put("index", 0).put("mode", "play"))) toast("正在 " + devices.targetName() + " 播放：" + job.title);
        } catch (org.json.JSONException ignored) {}
    }

    private void chooseRemoteParallel() {
        int max = remoteDownloadList == null ? DownloadTasks.MAX_PARALLEL
                : Math.max(1, remoteDownloadList.optInt("maxParallel", DownloadTasks.MAX_PARALLEL));
        int current = remoteDownloadList == null ? 0 : remoteDownloadList.optInt("parallel");
        String[] choices = new String[max];
        for (int i = 0; i < max; i++) choices[i] = (i + 1) + " 首" + (i == 0 ? "（逐首下载）" : "");
        new AlertDialog.Builder(this).setTitle(devices.targetName() + " · 同时下载")
                .setSingleChoiceItems(choices, Math.max(-1, current - 1), (dialog, choice) -> {
                    dialog.dismiss();
                    try {
                        devices.query(new JSONObject().put("op", "downloads").put("action", "parallel").put("count", choice + 1),
                                (result, error) -> {
                                    if (!error.isEmpty()) toast(error);
                                    else remoteDownloadsArrived(result);
                                });
                    } catch (org.json.JSONException ignored) {}
                }).setNegativeButton("取消", null).show();
    }

    private void downloadChoices(String title, int quality, Uri directory, DownloadChoice callback) {
        LinearLayout dialogContent = column();
        dialogContent.setPadding(dp(22), dp(5), dp(22), dp(8));
        TextView destination = text(directory == null ? "系统下载目录"
                : android.provider.DocumentsContract.getTreeDocumentId(directory), 14, MUTED, false);
        dialogContent.addView(text("保存位置", 14, INK, true));
        dialogContent.addView(destination);
        android.widget.Spinner selector = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> options = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, DownloadTasks.QUALITY_NAMES);
        selector.setAdapter(options);
        selector.setSelection(quality);
        dialogContent.addView(text("下载音质", 14, INK, true));
        dialogContent.addView(selector, new LinearLayout.LayoutParams(-1, dp(48)));
        new AlertDialog.Builder(this).setTitle(title).setView(dialogContent)
                .setNeutralButton("更改目录", (dialog, button) -> {
                    pendingDownloadChoice = callback;
                    pendingDownloadTitle = title;
                    pendingDownloadQuality = selector.getSelectedItemPosition();
                    pendingDownloadDirectory = directory;
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    if (directory != null && android.os.Build.VERSION.SDK_INT >= 26) {
                        picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, directory);
                    }
                    startActivityForResult(picker, JOB_DOWNLOAD_DIRECTORY);
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", (dialog, button) -> callback.chosen(selector.getSelectedItemPosition(), directory))
                .show();
    }

    private void queueDownloads(List<TidalClient.Track> tracks, String source, int quality, Uri directory,
                                DownloadTasks.Resolve resolve) {
        downloads.submit(() -> {
            int skipped = downloadTasks.add(tracks, source, quality, directory, resolve);
            handler.post(() -> showQueuedDownloads(skipped));
        });
    }

    private void showQueuedDownloads(int skipped) {
        if (isDestroyed()) return;
        if (skipped > 0) toast(skipped + " 首已下载或正在下载");
        open(Page.DOWNLOADS);
        refreshDownloads();
    }

    private void offlinePage() {
        if (offlineAlbum != null) {
            DownloadTasks.OfflineAlbum album = offlineAlbum;
            content.addView(primary("播放全部", () -> playOffline(album.tracks, 0)));
            for (int i = 0; i < album.tracks.size(); i++) {
                int index = i;
                TidalClient.Track track = album.tracks.get(i);
                content.addView(entry(track.title, track.artist, "", () -> playOffline(album.tracks, index)));
            }
            return;
        }
        LinearLayout destination = content;
        destination.addView(text("正在读取已下载音乐…", 14, MUTED, false));
        worker.submit(() -> {
            List<DownloadTasks.OfflineAlbum> albums = downloadTasks.offlineAlbums();
            handler.post(() -> {
                if (isDestroyed() || page != Page.OFFLINE || content != destination || offlineAlbum != null) return;
                destination.removeAllViews();
                if (albums.isEmpty()) {
                    panel(destination, "暂无已下载音乐", "完成下载后，可以在这里离线播放。");
                    return;
                }
                for (DownloadTasks.OfflineAlbum album : albums) {
                    destination.addView(entry(album.title, album.source + " · " + album.tracks.size() + " 首", "", () -> {
                        offlineAlbum = album;
                        render();
                    }));
                }
            });
        });
    }

    private void playOffline(List<TidalClient.Track> tracks, int index) {
        if (playback == null) return;
        playList(tracks, index, false);
        open(Page.PLAYER);
    }

    private DownloadTasks.Resolve downloadResolver(String source) {
        if (playback == null) return null;
        if ("网易云".equals(source)) return DownloadTasks.netease(playback.netease());
        return playback.client == null ? null : DownloadTasks.tidal(playback.client);
    }

    private void downloadsPage() {
        visibleRemoteDownloads = remoteDownloads();
        remoteParallelRow = null;
        if (visibleRemoteDownloads) {
            TextView where = text("正在查看「" + devices.targetName() + "」上的下载，文件保存在该设备", 13, ACCENT, true);
            where.setPadding(dp(2), 0, dp(2), dp(8));
            content.addView(where);
            Button row = menuRow(remoteParallelLabel(), "设置该设备同时下载的歌曲数", this::chooseRemoteParallel);
            content.addView(row);
            remoteParallelRow = row;
            downloadRows = column();
            content.addView(downloadRows);
            downloadSignature = null;
            if (remoteDownloadList != null) showDownloadRows(RemoteTidal.parse(remoteDownloadList));
            refreshDownloads();
            return;
        }
        content.addView(menuRow("同时下载 · " + downloadTasks.parallel() + " 首", "设置同时下载的歌曲数",
                this::chooseParallelDownloads));
        downloadRows = column();
        content.addView(downloadRows);
        downloadSignature = null;
        refreshDownloads();
    }

    /** How many songs download at the same time (1–5). */
    private void chooseParallelDownloads() {
        String[] choices = new String[DownloadTasks.MAX_PARALLEL];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = (i + 1) + " 首" + (i == 0 ? "（逐首下载）" : i == 2 ? "（推荐）" : "");
        }
        new AlertDialog.Builder(this).setTitle("同时下载")
                .setSingleChoiceItems(choices, downloadTasks.parallel() - 1, (dialog, choice) -> {
                    dialog.dismiss();
                    downloadTasks.setParallel(choice + 1);
                    toast("同时下载 " + (choice + 1) + " 首，正在下载的歌曲不受影响");
                    render();
                }).setNegativeButton("取消", null).show();
    }

    private void refreshDownloads() {
        if (downloadRefreshPending || page != Page.DOWNLOADS) return;
        if (visibleRemoteDownloads) {
            if (!remoteDownloads()) return;
            downloadRefreshPending = true;
            try {
                devices.query(new JSONObject().put("op", "downloads").put("action", "list")
                        .put("limit", remoteDownloadLimit), (result, error) -> {
                    downloadRefreshPending = false;
                    if (error.isEmpty()) remoteDownloadsArrived(result);
                });
            } catch (org.json.JSONException ignored) {
                downloadRefreshPending = false;
            }
            return;
        }
        downloadRefreshPending = true;
        worker.submit(() -> {
            List<DownloadTasks.Job> jobs = downloadTasks.snapshot();
            handler.post(() -> {
                downloadRefreshPending = false;
                if (page == Page.DOWNLOADS && !isDestroyed()) showDownloadRows(jobs);
            });
        });
    }

    private void showDownloadRows(List<DownloadTasks.Job> jobs) {
        List<DownloadTasks.Job> visible = new ArrayList<>();
        java.util.Set<String> visibleIds = new java.util.HashSet<>();
        for (DownloadTasks.Job job : jobs) {
            if (downloadFilter == 0 || downloadGroup(job) == downloadFilter) {
                visible.add(job);
                visibleIds.add(job.id);
            }
        }
        selectedDownloads.retainAll(visibleIds);
        StringBuilder signature = new StringBuilder().append(expandedDownloads).append(':').append(downloadFilter)
                .append(':').append(selectingDownloads).append(':').append(selectedDownloads)
                .append(':').append(visibleRemoteDownloads && remoteDownloadList != null ? remoteDownloadList.optInt("total") : 0);
        for (DownloadTasks.Job job : jobs) {
            signature.append('|').append(job.id).append(':').append(downloadStatus(job))
                    .append(':').append(downloadProgress(job)).append(':').append(job.error).append(':').append(job.resolution);
        }
        if (signature.toString().equals(downloadSignature)) return;
        downloadSignature = signature.toString();
        downloadRows.removeAllViews();

        int[] counts = new int[4];
        counts[0] = jobs.size();
        for (DownloadTasks.Job job : jobs) counts[downloadGroup(job)]++;
        String[] labels = {"全部", "进行中", "已完成", "失败和取消"};
        LinearLayout filter = row();
        filter.setPadding(0, dp(2), 0, dp(12));
        downloadRows.addView(filter);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = text(labels[i] + "\n" + counts[i], 13, i == downloadFilter ? BG : MUTED, true);
            tab.setGravity(Gravity.CENTER);
            tab.setSelected(i == downloadFilter);
            tab.setBackground(shape(i == downloadFilter ? ACCENT : SURFACE, 10));
            tab.setContentDescription(labels[i] + " " + counts[i] + " 项");
            tab.setFocusable(true);
            tab.setOnClickListener(view -> {
                if (downloadFilter != index) selectedDownloads.clear();
                downloadFilter = index;
                showDownloadRows(jobs);
                refreshDownloads();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
            if (i > 0) params.leftMargin = dp(5);
            filter.addView(tab, params);
        }

        if (!visible.isEmpty() || selectingDownloads) showDownloadSelection(jobs, visible);
        if (counts[downloadFilter] == 0) {
            panel(downloadRows, downloadFilter == 0 ? "暂无下载任务" : "暂无" + labels[downloadFilter] + "任务",
                    downloadFilter == 0 ? "下载歌曲或专辑后，可在这里查看进度。" : "");
            return;
        }
        java.util.Map<String, List<DownloadTasks.Job>> albums = new java.util.LinkedHashMap<>();
        for (DownloadTasks.Job job : jobs) {
            if (job.batch.isEmpty()) continue;
            List<DownloadTasks.Job> items = albums.get(job.batch);
            if (items == null) {
                items = new ArrayList<>();
                albums.put(job.batch, items);
            }
            items.add(job);
        }
        if (!selectingDownloads) {
            for (List<DownloadTasks.Job> album : albums.values()) {
                List<DownloadTasks.Job> matches = new ArrayList<>();
                for (DownloadTasks.Job job : album) {
                    if (downloadFilter == 0 || downloadGroup(job) == downloadFilter) matches.add(job);
                }
                if (!matches.isEmpty()) downloadAlbum(album, matches, jobs);
            }
        }
        for (int group = 1; group < labels.length; group++) {
            if (downloadFilter != 0 && downloadFilter != group) continue;
            boolean hasRows = false;
            for (DownloadTasks.Job job : visible) {
                if (downloadGroup(job) == group && (selectingDownloads || job.batch.isEmpty())) hasRows = true;
            }
            if (!hasRows) continue;
            TextView heading = text(labels[group], 15, INK, true);
            heading.setPadding(dp(2), dp(8), 0, dp(8));
            downloadRows.addView(heading);
            for (DownloadTasks.Job job : visible) {
                if (downloadGroup(job) == group && (selectingDownloads || job.batch.isEmpty())) downloadRow(job, jobs);
            }
        }
        int total = visibleRemoteDownloads && remoteDownloadList != null ? remoteDownloadList.optInt("total", jobs.size()) : 0;
        if (total > jobs.size() && remoteDownloadLimit < RemoteTidal.MAX_LIST) {
            downloadRows.addView(menuRow("显示更多", "已显示最近 " + jobs.size() + " 项，共 " + total + " 项", () -> {
                remoteDownloadLimit = Math.min(RemoteTidal.MAX_LIST, remoteDownloadLimit + 300);
                downloadNextRefresh = 0;
                refreshDownloads();
            }));
        } else if (total > jobs.size()) {
            downloadRows.addView(text("仅显示最近 " + jobs.size() + " 项，共 " + total + " 项", 13, MUTED, false));
        }
    }

    private void downloadAlbum(List<DownloadTasks.Job> album, List<DownloadTasks.Job> matches,
                               List<DownloadTasks.Job> jobs) {
        DownloadTasks.Job first = album.get(0);
        int completed = 0;
        List<DownloadTasks.Job> failed = new ArrayList<>();
        for (DownloadTasks.Job job : album) {
            if (job.state == DownloadTasks.State.COMPLETE) completed++;
            if (job.state == DownloadTasks.State.FAILED) failed.add(job);
        }
        boolean expanded = expandedDownloads.contains(first.batch);
        String detail = "已完成 " + completed + "/" + album.size() + " 首";
        if (!failed.isEmpty()) detail += " · " + failed.size() + " 首失败";
        LinearLayout section = panel(downloadRows, first.albumTitle, detail);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(album.size());
        progress.setProgress(completed);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        section.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        LinearLayout actions = row();
        actions.addView(action(expanded ? "收起" : "展开", (expanded ? "收起 " : "展开 ") + first.albumTitle, () -> {
            if (!expandedDownloads.remove(first.batch)) expandedDownloads.add(first.batch);
            showDownloadRows(jobs);
        }));
        if (!failed.isEmpty()) actions.addView(action("只重试失败项", "重试 " + first.albumTitle + " 的失败下载", () -> {
            if (visibleRemoteDownloads) {
                for (DownloadTasks.Job job : failed) remoteDownloadAction("retry", job.id);
                return;
            }
            DownloadTasks.Resolve resolve = downloadResolver(first.source);
            if (resolve == null) {
                toast("请先连接 " + first.source);
                return;
            }
            worker.submit(() -> {
                for (DownloadTasks.Job job : failed) downloadTasks.retry(job.id, job.directory, resolve);
                handler.post(() -> { if (!isDestroyed()) refreshDownloads(); });
            });
        }));
        section.addView(actions);
        if (expanded) {
            java.util.Collections.sort(matches, (a, b) -> Integer.compare(a.order, b.order));
            for (DownloadTasks.Job job : matches) downloadRow(job, jobs);
        }
    }

    private int downloadGroup(DownloadTasks.Job job) {
        if (job.active()) return 1;
        return job.state == DownloadTasks.State.COMPLETE ? 2 : 3;
    }

    private void downloadRow(DownloadTasks.Job job, List<DownloadTasks.Job> jobs) {
        LinearLayout item = column();
        item.setPadding(dp(12), dp(9), dp(6), dp(9));
        item.setBackground(shape(selectedDownloads.contains(job.id) ? RAISED : SURFACE, 12));
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.bottomMargin = dp(6);
        downloadRows.addView(item, card);

        LinearLayout line = row();
        item.addView(line);
        item.setOnLongClickListener(view -> {
            selectingDownloads = true;
            selectedDownloads.add(job.id);
            showDownloadRows(jobs);
            return true;
        });
        if (selectingDownloads) item.setOnClickListener(view -> toggleDownloadSelection(job, jobs));
        boolean complete = job.state == DownloadTasks.State.COMPLETE;
        boolean failed = job.state == DownloadTasks.State.FAILED;
        int tone = failed ? 0xffffb2a8 : job.state == DownloadTasks.State.CANCELLED ? MUTED : ACCENT;
        TextView icon = text(complete ? "✓" : failed ? "!" : job.active() ? "↓" : "–", 20, tone, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(RAISED, 10));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (selectingDownloads) {
            android.widget.CheckBox select = new android.widget.CheckBox(this);
            select.setButtonTintList(ColorStateList.valueOf(ACCENT));
            select.setContentDescription("选择 " + job.title);
            select.setChecked(selectedDownloads.contains(job.id));
            select.setOnClickListener(view -> toggleDownloadSelection(job, jobs));
            line.addView(select, new LinearLayout.LayoutParams(dp(44), dp(48)));
        } else line.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout details = column();
        details.setPadding(dp(10), 0, dp(2), 0);
        line.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = text(job.title, 15, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(title);
        TextView metadata = text(job.source + (job.artist.isEmpty() ? "" : " · " + job.artist), 12, MUTED, false);
        metadata.setSingleLine();
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(metadata);
        if (!job.resolution.isEmpty()) details.addView(text(job.resolution, 11, MUTED, false));
        TextView status = text(downloadStatus(job), 12, tone, true);
        status.setPadding(0, dp(3), 0, 0);
        details.addView(status);

        if (visibleRemoteDownloads) {
            if (job.active()) line.addView(action("取消", "取消下载 " + job.title, () -> remoteDownloadAction("cancel", job.id)));
            else {
                if (complete && "TIDAL".equals(job.source) && job.track > 0) {
                    line.addView(action("播放", "在远程设备播放 " + job.title, () -> playRemoteDownload(job)));
                } else if (!complete) line.addView(action("重试", "重新下载 " + job.title, () -> remoteDownloadAction("retry", job.id)));
                line.addView(action("移除", "移除下载记录 " + job.title, () -> remoteDownloadAction("remove", job.id)));
            }
        } else if (!selectingDownloads && job.active()) {
            line.addView(action("取消", "取消下载 " + job.title, () -> {
                downloadTasks.cancel(job.id);
                refreshDownloads();
            }));
        } else if (!selectingDownloads) {
            if (complete) line.addView(action("播放", "播放已下载歌曲 " + job.title, () -> openDownload(job)));
            else line.addView(action("重试", "重新下载 " + job.title, () -> retryDownload(job)));
            Button more = iconButton("more", "下载选项 " + job.title, () -> downloadOptions(job));
            line.addView(more);
        }

        if (job.state == DownloadTasks.State.TRANSFERRING) {
            LinearLayout transfer = column();
            transfer.setPadding(dp(50), dp(5), dp(8), 0);
            android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(job.total <= 0);
            progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            if (job.total > 0) progress.setProgress(downloadPercent(job));
            transfer.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
            transfer.addView(text(downloadProgress(job), 11, MUTED, false));
            item.addView(transfer);
        } else if (failed && !job.error.isEmpty()) {
            TextView reason = text(job.error, 12, MUTED, false);
            reason.setPadding(dp(50), dp(4), dp(8), 0);
            item.addView(reason);
        }
    }

    private void toggleDownloadSelection(DownloadTasks.Job job, List<DownloadTasks.Job> jobs) {
        if (!selectedDownloads.remove(job.id)) selectedDownloads.add(job.id);
        showDownloadRows(jobs);
    }

    private void showDownloadSelection(List<DownloadTasks.Job> jobs, List<DownloadTasks.Job> visible) {
        LinearLayout bar = row();
        downloadRows.addView(bar);
        if (!selectingDownloads) {
            Button select = secondary("多选", () -> {
                selectingDownloads = true;
                showDownloadRows(jobs);
            });
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, dp(38));
            chip.topMargin = dp(4);
            chip.bottomMargin = dp(4);
            bar.addView(select, chip);
            return;
        }
        TextView summary = text("已选 " + selectedDownloads.size() + " 项", 13, INK, true);
        bar.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        boolean all = !visible.isEmpty() && selectedDownloads.size() == visible.size();
        Button selectAll = action(all ? "清空" : "全选", all ? "清空选择" : "全选当前列表", () -> {
            selectedDownloads.clear();
            if (!all) for (DownloadTasks.Job job : visible) selectedDownloads.add(job.id);
            showDownloadRows(jobs);
        });
        selectAll.setEnabled(!visible.isEmpty());
        bar.addView(selectAll);
        Button actions = action("管理", "批量管理下载", () -> showDownloadBatch(visible));
        actions.setEnabled(!selectedDownloads.isEmpty());
        bar.addView(actions);
        bar.addView(action("完成", "退出多选", () -> {
            selectingDownloads = false;
            selectedDownloads.clear();
            showDownloadRows(jobs);
        }));
    }

    private enum DownloadBatch { CANCEL, RETRY, REMOVE, DELETE }

    private boolean eligibleDownload(DownloadTasks.Job job, DownloadBatch action) {
        switch (action) {
            case CANCEL: return job.active();
            case RETRY: return job.state == DownloadTasks.State.FAILED || job.state == DownloadTasks.State.CANCELLED;
            case REMOVE: return !job.active();
            case DELETE: return job.state == DownloadTasks.State.COMPLETE;
            default: return false;
        }
    }

    private void showDownloadBatch(List<DownloadTasks.Job> visible) {
        List<String> labels = new ArrayList<>();
        List<Runnable> options = new ArrayList<>();
        String[] names = {"取消下载", "重试下载", "移除记录", "删除文件"};
        for (DownloadBatch action : DownloadBatch.values()) {
            // Files on the controlled device are deleted there only one by one, from its own list.
            if (visibleRemoteDownloads && action == DownloadBatch.DELETE) continue;
            List<DownloadTasks.Job> targets = new ArrayList<>();
            for (DownloadTasks.Job job : visible) {
                if (selectedDownloads.contains(job.id) && eligibleDownload(job, action)) targets.add(job);
            }
            if (targets.isEmpty()) continue;
            labels.add(names[action.ordinal()] + " · " + targets.size() + " 项");
            options.add(() -> {
                if (action == DownloadBatch.DELETE) {
                    new AlertDialog.Builder(this).setTitle("删除 " + targets.size() + " 个下载文件？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (dialog, button) -> runDownloadBatch(action, targets)).show();
                } else runDownloadBatch(action, targets);
            });
        }
        if (!labels.isEmpty()) new AlertDialog.Builder(this).setTitle("批量管理下载")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> options.get(index).run()).show();
    }

    private void runDownloadBatch(DownloadBatch action, List<DownloadTasks.Job> targets) {
        if (visibleRemoteDownloads) {
            remoteDownloadBatch(action, targets);
            return;
        }
        DownloadTasks.Resolve tidal = action == DownloadBatch.RETRY ? downloadResolver("TIDAL") : null;
        DownloadTasks.Resolve netease = action == DownloadBatch.RETRY ? downloadResolver("网易云") : null;
        Uri destination = MusicDownload.directory(this);
        for (DownloadTasks.Job job : targets) selectedDownloads.remove(job.id);
        worker.submit(() -> {
            int skipped = 0;
            for (DownloadTasks.Job job : targets) {
                switch (action) {
                    case CANCEL: downloadTasks.cancel(job.id); break;
                    case RETRY:
                        DownloadTasks.Resolve resolve = "网易云".equals(job.source) ? netease : tidal;
                        if (resolve == null) skipped++;
                        else downloadTasks.retry(job.id, destination, resolve);
                        break;
                    default:
                        if (!downloadTasks.remove(job.id, action == DownloadBatch.DELETE)) skipped++;
                }
            }
            int remaining = skipped;
            handler.post(() -> {
                if (isDestroyed()) return;
                if (remaining > 0) toast(remaining + " 项未处理" + (action == DownloadBatch.RETRY ? "，请先连接对应音源" : ""));
                refreshDownloads();
            });
        });
    }

    private String downloadProgress(DownloadTasks.Job job) {
        if (job.state != DownloadTasks.State.TRANSFERRING) return "";
        String transferred = android.text.format.Formatter.formatShortFileSize(this, Math.max(0, job.bytes));
        return job.total > 0 ? transferred + " / " + android.text.format.Formatter.formatShortFileSize(this, job.total) : transferred;
    }

    private int downloadPercent(DownloadTasks.Job job) {
        return (int) Math.max(0, Math.min(100, job.bytes * 100.0 / job.total));
    }

    private String downloadStatus(DownloadTasks.Job job) {
        switch (job.state) {
            case QUEUED: return "排队中";
            case PREPARING: return "获取下载地址…";
            case WAITING: return "等待系统下载";
            case TRANSFERRING: return job.total > 0 ? "下载中 · " + downloadPercent(job) + "%" : "下载中";
            case COMPLETE: return "已完成";
            case CANCELLED: return "已取消";
            default: return "下载失败";
        }
    }

    private void retryDownload(DownloadTasks.Job job) {
        DownloadTasks.Resolve resolve = downloadResolver(job.source);
        if (resolve == null) {
            toast("请先连接 " + job.source);
            return;
        }
        downloadTasks.retry(job.id, MusicDownload.directory(this), resolve);
        refreshDownloads();
    }

    private void downloadOptions(DownloadTasks.Job job) {
        List<String> labels = new ArrayList<>();
        List<Runnable> choices = new ArrayList<>();
        labels.add("移除记录");
        choices.add(() -> removeDownload(job, false));
        if (job.state == DownloadTasks.State.COMPLETE) {
            labels.add("删除文件");
            choices.add(() -> new AlertDialog.Builder(this).setTitle("删除已下载文件？")
                    .setMessage(job.title).setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, button) -> removeDownload(job, true)).show());
        } else if (job.state == DownloadTasks.State.FAILED && "TIDAL".equals(job.source)) {
            labels.add("打开 TIDAL");
            choices.add(() -> openTidalBrowser(Uri.parse("https://tidal.com/browse/track/" + job.track)));
        }
        new AlertDialog.Builder(this).setTitle(job.title)
                .setItems(labels.toArray(new String[0]), (dialog, index) -> choices.get(index).run()).show();
    }

    private void openDownload(DownloadTasks.Job job) {
        worker.submit(() -> {
            TidalClient.Track track = downloadTasks.playable(job.id);
            handler.post(() -> {
                if (isDestroyed() || page != Page.DOWNLOADS) return;
                if (track == null) toast("找不到已下载文件");
                else playOffline(java.util.Collections.singletonList(track), 0);
            });
        });
    }

    private void removeDownload(DownloadTasks.Job job, boolean deleteFile) {
        worker.submit(() -> {
            boolean removed = downloadTasks.remove(job.id, deleteFile);
            handler.post(() -> {
                if (isDestroyed()) return;
                if (!removed) toast("无法移除此项目");
                refreshDownloads();
            });
        });
    }

    private void localTrackMenu(TidalClient.Track track) {
        if (playback == null) return;
        String playlist = page == Page.LOCAL ? localPlaylist : null;
        String remove = playlist == null ? "从音乐库移除" : "从歌单移除";
        String[] options = {"下一首播放", "追加到队列", "加入本地歌单", "下载/匹配歌词", remove};
        new AlertDialog.Builder(this).setTitle(track.title)
                .setItems(options, (dialog, choice) -> {
                    if (playback == null) return;
                    if (choice == 0) playback.enqueue(track, true);
                    else if (choice == 1) playback.enqueue(track, false);
                    else if (choice == 2) {
                        List<String> names = playback.localLibrary.playlists();
                        names.add("新建歌单…");
                        new AlertDialog.Builder(this).setTitle("加入歌单").setItems(names.toArray(new String[0]),
                                (picker, index) -> {
                                    if (playback == null) return;
                                    if (index == names.size() - 1) newLocalPlaylist(track);
                                    else {
                                        playback.localLibrary.add(names.get(index), track);
                                        playback.libraryChanged();
                                        toast("已加入歌单");
                                    }
                                }).show();
                    } else if (choice == 3) {
                        downloadLyricsDialog(track);
                    } else {
                        playback.localLibrary.remove(playlist, track.id);
                        if (playlist == null) playback.fileDeleted(track.id);
                        else playback.libraryChanged();
                        toast("已从列表移除，文件仍保留");
                        return;
                    }
                    if (choice < 2) toast(remoteMode() ? "已加入本机队列，本地歌曲只能在本机播放" : "已加入播放队列");
                }).show();
    }

    private void downloadLyricsDialog(TidalClient.Track track) {
        if (playback == null || track == null) return;
        toast("正在检索歌词…");
        worker.submit(() -> {
            try {
                LyricsFill fill = LyricsFill.get(playback);
                List<LyricsFill.Candidate> candidates = fill.candidates(track);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (candidates.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("未找到歌词")
                                .setMessage("在 TIDAL 和网易云中未找到「" + track.title + " - " + track.artist + "」的歌词。\n\n请确认歌曲标题与艺人标签是否准确。")
                                .setPositiveButton("确定", null)
                                .show();
                        return;
                    }
                    List<String> labels = new ArrayList<>();
                    for (LyricsFill.Candidate c : candidates) {
                        String durStr = "";
                        long dur = DownloadTasks.length(c.track);
                        if (dur > 0) durStr = " · " + formatTime(dur * 1000);
                        String matchTag = c.match ? " ★精准匹配" : "";
                        labels.add("[" + c.source + "] " + c.track.title + " - " + c.track.artist + durStr + matchTag);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("选择要下载的歌词 (" + candidates.size() + ")")
                            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                                LyricsFill.Candidate picked = candidates.get(which);
                                savePickedLyrics(track, picked);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) toast("搜索歌词失败：" + readable(error));
                });
            }
        });
    }

    private void savePickedLyrics(TidalClient.Track track, LyricsFill.Candidate picked) {
        if (playback == null) return;
        toast("正在下载歌词…");
        worker.submit(() -> {
            try {
                LyricsFill fill = LyricsFill.get(playback);
                JSONObject words = fill.fetch(picked.source, picked.track.id);
                String synced = words.optString("synced");
                String plain = words.optString("plain");
                String lrc = !synced.isEmpty() ? synced : plain;
                if (lrc.trim().isEmpty()) throw new IOException("该候选没有歌词文本");
                fill.save(track, lrc);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    toast("歌词已保存为同名 LRC 文件");
                    if (playback != null && playback.current() != null && playback.current().id == track.id) {
                        if (lyricsMode) showLyrics();
                    }
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) toast("保存歌词失败：" + readable(error));
                });
            }
        });
    }

    private void showBatchLyricsFill() {
        if (playback == null) return;
        int without = playback.localLibrary.withoutLyrics().size();
        if (without == 0) {
            toast("所有本地歌曲都已有歌词文件");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量补全歌词")
                .setMessage("当前共有 " + without + " 首本地歌曲缺少同名 .lrc 歌词文件。\n\n是否从 TIDAL 与网易云自动搜索并下载保存为同名 .lrc？")
                .setPositiveButton("开始补全", (dialog, which) -> {
                    try {
                        LyricsFill.get(playback).start(null, false);
                        toast("已启动后台歌词补全，匹配成功后自动落盘保存");
                    } catch (IOException err) {
                        toast(err.getMessage());
                    }
                })
                .setNeutralButton("重试所有未找到", (dialog, which) -> {
                    try {
                        LyricsFill.get(playback).start(null, true);
                        toast("已重新尝试补全所有无歌词歌曲");
                    } catch (IOException err) {
                        toast(err.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void playRadio(TidalClient.Track track) {
        TidalClient api = tidalApi();
        if (api == null) return;
        toast("正在加载歌曲电台");
        worker.submit(() -> {
            try {
                List<TidalClient.Track> mix = api.radio(track.id);
                runOnUiThread(() -> {
                    if (isDestroyed() || playback == null || tidalApi() != api) return;
                    if (mix.isEmpty()) toast("暂无电台歌曲");
                    else playList(mix, 0, false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && playback != null && tidalApi() == api) toast(readable(error));
                });
            }
        });
    }

    /** Switch the Now Playing stage between the cover and full-width lyrics. */
    private void toggleLyrics() {
        if (lyricsMode) {
            closeLyrics();
            return;
        }
        lyricsMode = true;
        showLyrics();
        if (lyrics == null) lyricsMode = false;
    }

    private void closeLyrics() {
        lyricsMode = false;
        lyrics = null;
        if (lyricsPane != null) {
            lyricsPane.removeAllViews();
            lyricsPane.setVisibility(View.GONE);
        }
        if (cover != null) cover.setVisibility(View.VISIBLE);
    }

    /** Put lyrics for {@code track} in place of the cover; they reload when the track changes. */
    private LyricsDialog inlineLyrics(TidalClient.Track track, LyricsDialog.Seek seek) {
        LyricsDialog[] self = new LyricsDialog[1];
        LyricsDialog view = LyricsDialog.inline(this, track, seek, this::closeLyrics, () -> {
            // The track changed: stop syncing this view, then load the new track's lyrics once.
            if (lyrics != self[0]) return;
            lyrics = null;
            handler.post(() -> {
                if (!lyricsMode || lyrics != null) return;
                showLyrics();
                if (lyrics == null) closeLyrics();
            });
        });
        self[0] = view;
        lyrics = view;
        lyricsPane.removeAllViews();
        lyricsPane.addView(view.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        lyricsPane.setVisibility(View.VISIBLE);
        cover.setVisibility(View.INVISIBLE);
        return view;
    }

    private void showLyrics() {
        lyrics = null;
        if (lyricsPane == null) return;
        if (remoteMode()) {
            showRemoteLyrics();
            return;
        }
        if (playback == null || playback.current() == null) return;
        TidalClient.Track track = playback.current();
        TidalClient api = playback.client;
        LocalLibrary library = playback.localLibrary;
        if (track.needsTidal() && api == null) return;
        NeteaseClient netease = playback.netease();
        LyricsDialog view = inlineLyrics(track, millis -> {
            if (playback != null && playback.current() != null && playback.current().id == track.id
                    && !playback.loading && playback.player.isCurrentMediaItemSeekable()) {
                playback.player.seekTo(millis);
            }
        });
        worker.submit(() -> {
            try {
                org.json.JSONObject data = track.isLocal()
                        ? LocalMusicFiles.lyrics(getContentResolver(), library.latest(track)) : track.isNetease() ? netease.lyrics(track.id) : api.lyrics(track.id);
                runOnUiThread(() -> {
                    if (lyrics == view) view.display(data);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (lyrics == view) {
                        if (track.isLocal()) {
                            view.unavailable(readable(error), () -> downloadLyricsDialog(track));
                        } else {
                            view.unavailable(readable(error));
                        }
                    }
                });
            }
        });
    }

    private void share(TidalClient.Track track) {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, track.title + " · " + track.artist
                        + "\n" + (track.isNetease() ? NeteaseClient.songLink(track.id) : "https://tidal.com/browse/track/" + track.id));
        startActivity(Intent.createChooser(share, "分享歌曲"));
    }

    private void changeFavorite(TidalClient.Track track, boolean add) {
        TidalClient api = tidalApi();
        if (playback == null || !track.needsTidal() || api == null || favoriteBusy) return;
        favoriteBusy = true;
        updatePlayer();
        worker.submit(() -> {
            try {
                api.favorite(track.id, add);
                runOnUiThread(() -> {
                    if (isDestroyed() || playback == null || tidalApi() != api) return;
                    favoriteBusy = false;
                    if (add) favoriteIds.add(track.id); else favoriteIds.remove(track.id);
                    toast(add ? "已收藏" : "已取消收藏");
                    if (page == Page.LIBRARY && libraryFilter == 0) load(false);
                    else updatePlayer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || playback == null || tidalApi() != api) return;
                    favoriteBusy = false;
                    toast("收藏更新失败 · " + readable(e));
                    updatePlayer();
                });
            }
        });
    }

    private void queue() {
        if (remoteMode()) {
            remoteQueue();
            return;
        }
        if (devices != null) content.addView(menuRow("本机 · 切换播放设备", "切换播放设备", this::showDevicePicker));
        if (playback == null || playback.queue.isEmpty()) {
            empty("队列是空的", "从歌曲菜单加入歌曲，或直接播放一张专辑");
            return;
        }
        section(playback.queue.size() + " 首歌曲");
        showGrowing(playback.queue.size(), (from, to) -> {
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
                entry.addView(iconButton("more", "调整队列 " + track.title, () -> editQueue(at, track)));
                content.addView(entry);
            }
        });
        if (revealQueue && playback.index >= LOCAL_PAGE_SIZE) fillTo(playback.index);
    }

    // ───────────── remote control of this account's other devices ─────────────

    /** Create or drop the remote-control client to match the signed-in player account. */
    private void syncDevices() {
        android.content.SharedPreferences saved = getSharedPreferences("tidal", MODE_PRIVATE);
        String key = saved.contains("player_account") ? saved.getString("relay_key", "") : "";
        String origin;
        try {
            origin = key.isEmpty() ? "" : CentralRelay.normalizeOrigin(saved.getString("relay_origin", ""));
        } catch (IOException invalid) {
            origin = "";
        }
        String wanted = origin.isEmpty() ? "" : origin + "\n" + key;
        if (wanted.equals(devicesKey)) return;
        if (devices != null) devices.close();
        devices = null;
        devicesKey = wanted;
        if (wanted.isEmpty()) return;
        devices = new RemoteDevices(origin, key, this::remoteChanged);
        if (started) devices.resume();
    }

    private boolean remoteMode() {
        return devices != null && devices.active();
    }

    private TidalClient borrowedTidal;
    private String borrowedTidalDevice;

    /**
     * The TIDAL client pages browse with: this phone's login, or while controlling a device that is
     * signed in to TIDAL and this phone is not, that device's login (requests go through it).
     */
    private TidalClient tidalApi() {
        if (playback == null) return null;
        if (playback.client != null) return playback.client;
        if (!borrowsTidal()) return null;
        String device = devices.target;
        if (borrowedTidal == null || !device.equals(borrowedTidalDevice)) {
            RemoteDevices link = devices;
            borrowedTidal = new TidalClient((method, path, form) -> {
                JSONObject command = new JSONObject().put("op", "tidal").put("method", method).put("path", path)
                        .put("accept", "gzip");
                if (form != null) command.put("form", form);
                return RemoteTidal.body(link.call(device, command));
            });
            borrowedTidalDevice = device;
        }
        return borrowedTidal;
    }

    /**
     * Why this phone, signed out of TIDAL, cannot use the controlled device's login although
     * that device is signed in; null otherwise.
     */
    private String lendHint() {
        if (playback == null || playback.client != null || !remoteMode() || devices.state == null
                || !devices.state.tidalLoggedIn || devices.state.lendsTidal) return null;
        return "「" + devices.targetName() + "」已登录 TIDAL，但未开放借用。请在该设备上更新到最新版，"
                + "并在 设置 › 播放器账号 中打开“允许我的其他设备使用本机的 TIDAL 登录和下载”";
    }

    /** Browsing uses the controlled device's TIDAL login, since this phone has none. */
    private boolean borrowsTidal() {
        return playback != null && playback.client == null && remoteMode() && devices.state != null
                && devices.state.lendsTidal;
    }

    /** Downloads go to the controlled device, which lists them on the downloads page here. */
    private boolean remoteDownloads() {
        return remoteMode() && devices.state != null && devices.state.downloads;
    }

    private void remoteChanged() {
        if (isDestroyed() || devices == null) return;
        if (!devices.notice.isEmpty()) {
            toast(devices.notice);
            devices.notice = "";
        }
        if (!java.util.Objects.equals(visibleRemoteTarget, devices.target)) resetRemoteLibrary();
        boolean rebuild = !java.util.Objects.equals(visibleRemoteTarget, devices.target)
                || (page == Page.DEVICES && !visibleDevices.equals(devicesSignature()))
                || (page == Page.QUEUE && remoteMode() && !visibleRemoteQueue.equals(remoteQueueSignature()))
                || (page == Page.DOWNLOADS && visibleRemoteDownloads != remoteDownloads());
        if (!java.util.Objects.equals(visibleRemoteTarget, devices.target)) {
            remoteDownloadList = null;
            remoteMarks = null;
            remoteDownloadLimit = 300;
            remoteMarksKey = "";
            remoteMarksBusy = false;
        }
        syncRemoteMarks();
        if (rebuild) render();
        else updatePlayer();
    }

    private String devicesSignature() {
        if (devices == null) return "";
        StringBuilder signature = new StringBuilder(String.valueOf(devices.target))
                .append(devices.devicesLoaded).append(devices.devicesError);
        for (RemoteDevices.Device device : devices.devices) {
            signature.append('|').append(device.id).append(device.online).append(device.nowPlaying)
                    .append(device.nowTitle).append(device.version);
        }
        return signature.toString();
    }

    private String remoteQueueSignature() {
        if (!remoteMode()) return "";
        RemoteDevices.State state = devices.state;
        return state == null ? "none:" + devices.error : state.revision + ":" + state.index + ":" + state.queue.size();
    }

    /** Transport controls act on the selected device: this phone or a remote one. */
    private void control(String op) {
        if (remoteMode()) {
            if (!devices.send(op)) toast("遥控命令过多，请稍后再试");
            return;
        }
        if (playback == null) return;
        switch (op) {
            case "toggle": playback.toggle(); break;
            case "previous": playback.previous(); break;
            case "next": playback.next(); break;
            case "shuffle": playback.toggleShuffle(); break;
            case "repeat": playback.cycleRepeat(); break;
            default: break;
        }
    }

    /** The device looks tracks up by TIDAL id, so local and NetEase tracks stay on this phone. */
    private boolean remoteAdd(TidalClient.Track track, boolean next) {
        if (track.isLocal() || track.isNetease()) {
            toast("本地和网易云歌曲只能在本机播放");
            return false;
        }
        try {
            return devices.send(new JSONObject().put("op", "add").put("id", track.id).put("next", next));
        } catch (org.json.JSONException ignored) {
            return false;
        }
    }

    /**
     * Send a track list to the remote device in one command: "play" replaces its queue and starts
     * at {@code index}; "next" and "end" queue the tracks. Local and NetEase tracks stay here.
     */
    private void remoteTracks(List<TidalClient.Track> list, int index, boolean shuffled, String mode) {
        remoteTracks(list, index, shuffled, mode, 0);
    }

    private void remoteTracks(List<TidalClient.Track> list, int index, boolean shuffled, String mode, long position) {
        TidalClient.Track chosen = index >= 0 && index < list.size() ? list.get(index) : null;
        List<TidalClient.Track> tidal = new ArrayList<>();
        for (TidalClient.Track track : list) if (!track.isLocal() && !track.isNetease()) tidal.add(track);
        if (tidal.isEmpty()) {
            toast("本地和网易云歌曲只能在本机播放");
            return;
        }
        int at = Math.max(0, chosen == null ? 0 : tidal.indexOf(chosen));
        // One command carries at most 50 tracks: keep the chosen track inside the window.
        int start = Math.min(at, Math.max(0, tidal.size() - 50));
        org.json.JSONArray ids = new org.json.JSONArray();
        for (int i = start; i < Math.min(tidal.size(), start + 50); i++) ids.put(tidal.get(i).id);
        try {
            if (!devices.send(new JSONObject().put("op", "tracks").put("ids", ids).put("index", at - start)
                    .put("shuffle", shuffled).put("mode", mode).put("position", Math.max(0, position)))) {
                toast("遥控命令过多，请稍后再试");
                return;
            }
        } catch (org.json.JSONException ignored) {
            return;
        }
        if ("play".equals(mode)) toast("正在 " + devices.targetName() + " 播放：" + displayTitle(tidal.get(at)));
    }

    /** After opening an album or artist from Now Playing, scroll that song into view. */
    private void revealPlayingRow() {
        if (revealTrackId == Long.MIN_VALUE || listScroll == null) return;
        if (catalogueLoading && tracks.isEmpty() && collections.isEmpty()) return;
        long id = revealTrackId;
        ScrollView scroller = listScroll;
        scroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (listScroll != scroller || revealTrackId != id) return;
                android.view.ViewTreeObserver observer = scroller.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                revealTrackId = Long.MIN_VALUE;
                View row = findTagged(scroller, id);
                if (row == null) return;
                int y = 0;
                View contentRoot = scroller.getChildAt(0);
                for (View view = row; view != null && view != contentRoot; ) {
                    y += view.getTop();
                    view = view.getParent() instanceof View ? (View) view.getParent() : null;
                }
                if (y > dp(8)) scroller.scrollTo(0, y - dp(8));
            }
        });
    }

    /** Scroll a tagged row into view once the queue page has been laid out. */
    private void revealTag(Object tag) {
        if (listScroll == null) return;
        ScrollView scroller = listScroll;
        scroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (listScroll != scroller) return;
                android.view.ViewTreeObserver observer = scroller.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                View row = findTag(scroller, tag);
                if (row == null) return;
                int y = 0;
                View contentRoot = scroller.getChildAt(0);
                for (View view = row; view != null && view != contentRoot; ) {
                    y += view.getTop();
                    view = view.getParent() instanceof View ? (View) view.getParent() : null;
                }
                if (y > dp(8)) scroller.scrollTo(0, y - dp(8));
            }
        });
    }

    private static View findTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTag(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findTagged(View root, long id) {
        if (root.getTag() instanceof Long && (Long) root.getTag() == id) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** "专辑 · X ›" under the artist on Now Playing, when the track belongs to an album. */
    private void showPlayingAlbum(String album, boolean artistLine) {
        if (playerAlbum == null) return;
        boolean shown = !album.isEmpty() && artistLine;
        playerAlbum.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) playerAlbum.setText("专辑 · " + album);
        playerAlbum.setContentDescription(shown ? "查看专辑 " + album : null);
        subtitle.setTextColor(artistLine ? ACCENT : MUTED);
        subtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                artistLine ? new PlayerIcon("forward", ACCENT, dp(12)) : null, null);
        subtitle.setContentDescription(artistLine ? "查看艺人 " + subtitle.getText() : null);
    }

    private boolean hasPlayingArtist() {
        if (remoteMode()) {
            RemoteDevices.State state = devices.state;
            RemoteDevices.Track current = state == null ? null : state.current();
            return current != null && !current.artist.isEmpty();
        }
        return playback != null && playback.current() != null && !playback.current().artist.isEmpty();
    }

    private void openPlayingAlbum() {
        if (remoteMode()) openRemotePlaying(true);
        else if (playback != null && playback.current() != null) {
            revealTrackId = playback.current().id;
            openTrackAlbum(playback.current());
        }
    }

    private void openPlayingArtist() {
        if (remoteMode()) openRemotePlaying(false);
        else if (playback != null && playback.current() != null) {
            revealTrackId = playback.current().id;
            openTrackArtist(playback.current());
        }
    }

    /** All songs of the track's album: its album page, or the album group of a local file. */
    private void openTrackAlbum(TidalClient.Track track) {
        if (track.isLocal()) {
            openLocalGroup(LocalLibrary.groupFor(track, 1));
            return;
        }
        if (track.albumId <= 0) {
            toast("这首歌没有专辑信息");
            return;
        }
        try {
            openCollection(new TidalClient.Collection(new JSONObject().put("id", track.albumId)
                    .put("title", track.album).put("cover", track.cover), TidalClient.Collection.Kind.ALBUM));
        } catch (Exception ignored) {}
    }

    /** The track's artist: the artist page, or the artist group of a local file. */
    private void openTrackArtist(TidalClient.Track track) {
        if (track.isLocal()) {
            openLocalGroup(LocalLibrary.groupFor(track, 3));
            return;
        }
        if (track.artistId <= 0) {
            toast("这首歌没有艺人信息");
            return;
        }
        try {
            // NetEase artist ids arrive raw from song data; the catalogue uses its own id range.
            long id = track.isNetease() && !NeteaseClient.owns(track.artistId)
                    ? NeteaseClient.catalogueId(track.artistId) : track.artistId;
            openCollection(new TidalClient.Collection(new JSONObject().put("id", id).put("name", track.artist)
                    .put("picture", track.artistPicture), TidalClient.Collection.Kind.ARTIST));
        } catch (Exception ignored) {}
    }

    private void openLocalGroup(LocalGroup group) {
        localPlaylist = null;
        localGroup = group;
        localTab = group.category;
        if (page == Page.LOCAL) render();
        else open(Page.LOCAL);
    }

    /**
     * Album or artist of the controlled device's track. TIDAL tracks are looked up with this
     * phone's TIDAL login; local files of an Android device open its library filtered by name.
     */
    private void openRemotePlaying(boolean album) {
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        if (current == null) return;
        TidalClient api = tidalApi();
        if (current.tidalId > 0 && api != null) {
            long id = current.tidalId;
            worker.submit(() -> {
                try {
                    TidalClient.Track track = api.track(id);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        revealTrackId = track.id;
                        if (album) openTrackAlbum(track);
                        else openTrackArtist(track);
                    });
                } catch (Exception problem) {
                    runOnUiThread(() -> toast(readable(problem)));
                }
            });
            return;
        }
        if ("本地音乐".equals(current.source) && remoteHasLibrary()) {
            String name = album ? current.album : current.artist;
            if (name.isEmpty()) return;
            remoteLibTab = 0;
            remoteLibPlaylist = "";
            remoteLibGroup = "";
            remoteLibGroupTitle = "";
            remoteLibQuery = name;
            if (page == Page.LOCAL) render();
            else open(Page.LOCAL);
            return;
        }
        toast(album ? "无法打开这首歌的专辑" : "无法打开这首歌的艺人");
    }

    /** Shown under the header on every page while another device is controlled. */
    private void remoteBanner() {
        LinearLayout banner = row();
        banner.setPadding(dp(14), dp(6), dp(6), dp(6));
        banner.setBackground(shapeWithStroke(0x22d4a359, 0x44d4a359, 1, 10));
        RemoteDevices.Device device = devices.targetDevice();
        boolean library = device != null && device.android();
        TextView label = text("正在遥控 " + devices.targetName() + " · " + (library ? "播放、队列和音乐库" : "播放和队列")
                + "都来自这台设备" + (borrowsTidal() ? "，TIDAL 浏览和下载使用它的登录" : ""), 13, ACCENT, true);
        String hint = lendHint();
        if (hint != null) label.append("\n" + hint);
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon("devices", ACCENT, dp(18)), null, null, null);
        label.setCompoundDrawablePadding(dp(8));
        banner.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = secondary("切回本机", () -> selectDevice(null));
        back.setTextSize(13);
        banner.addView(back, new LinearLayout.LayoutParams(-2, dp(34)));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(dp(16), 0, dp(16), dp(6));
        root.addView(banner, layout);
    }

    private boolean remoteHasLibrary() {
        RemoteDevices.Device device = remoteMode() ? devices.targetDevice() : null;
        return device != null && device.android();
    }

    private void resetRemoteLibrary() {
        remoteLibTracks.clear();
        remoteLibPlaylists.clear();
        remoteLibKey = "";
        remoteLibLoading = false;
        remoteLibError = "";
        remoteLibNext = -1;
        remoteLibTab = 0;
        remoteLibQuery = "";
        remoteLibPlaylist = "";
        remoteLibGroups.clear();
        remoteLibGroup = "";
        remoteLibGroupTitle = "";
    }

    /** Sort names the device understands, in the order of {@link #LIBRARY_SORTS}. */
    private static final String[] REMOTE_SORTS = {"title", "artist", "album", "title", "added"};

    private void loadRemoteLibrary(boolean append) {
        boolean playlists = remoteLibTab == 2 && remoteLibPlaylist.isEmpty();
        int category = remoteLibTab == 1 || remoteLibTab >= 3 ? remoteLibTab : 0;
        String key = devices.target + "\n" + remoteLibTab + "\n" + remoteLibQuery + "\n" + remoteLibPlaylist
                + "\n" + remoteLibGroup + "\n" + remoteLibSort;
        if (remoteLibLoading || (!append && key.equals(remoteLibKey)) || (append && remoteLibNext < 0)) return;
        remoteLibLoading = true;
        remoteLibKey = key;
        if (!append) remoteLibError = "";
        try {
            JSONObject command = new JSONObject().put("op", "library")
                    .put("action", remoteLibTab == REMOTE_RECENT ? "recent" : "local")
                    .put("query", remoteLibQuery).put("playlist", remoteLibTab == 2 ? remoteLibPlaylist : "")
                    .put("category", playlists ? 0 : category).put("group", remoteLibGroup)
                    .put("sort", REMOTE_SORTS[Math.max(0, Math.min(REMOTE_SORTS.length - 1, remoteLibSort))])
                    .put("offset", append ? remoteLibNext : 0);
            devices.query(command, (result, error) -> {
                if (!key.equals(remoteLibKey)) return;
                remoteLibLoading = false;
                if (!error.isEmpty()) remoteLibError = error;
                else {
                    if (!append) {
                        remoteLibTracks.clear();
                        remoteLibGroups.clear();
                    }
                    org.json.JSONArray items = result.optJSONArray("tracks");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null) remoteLibTracks.add(item);
                    }
                    org.json.JSONArray groups = result.optJSONArray("groups");
                    for (int i = 0; groups != null && i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (group != null) remoteLibGroups.add(group);
                    }
                    remoteLibNext = result.optInt("next", -1);
                    org.json.JSONArray names = result.optJSONArray("playlists");
                    if (names != null) {
                        remoteLibPlaylists.clear();
                        for (int i = 0; i < names.length(); i++) remoteLibPlaylists.add(names.optString(i));
                    }
                }
                if (page == Page.LOCAL || page == Page.SEARCH) render();
            });
        } catch (org.json.JSONException ignored) {
            remoteLibLoading = false;
        }
    }

    /** Inside a group or playlist of the controlled device's library (the header shows a back button). */
    private boolean remoteLibInside() {
        return remoteMode() && (!remoteLibGroup.isEmpty() || (remoteLibTab == 2 && !remoteLibPlaylist.isEmpty()));
    }

    private void remoteLibLeave() {
        remoteLibGroup = "";
        remoteLibGroupTitle = "";
        remoteLibPlaylist = "";
        render();
    }

    /** The controlled device's music library, with the same layout as this phone's 音乐库. */
    private void remoteLibraryPage(boolean filters) {
        String name = devices.targetName();
        if (!remoteHasLibrary()) {
            empty(name + " 没有本地音乐库", "电脑端播放 TIDAL 和网易云。在 TIDAL 或网易云中选歌，就会在 " + name + " 播放。");
            content.addView(menuRow("浏览 TIDAL", "浏览 TIDAL", () -> enterSource(SearchSource.TIDAL)));
            content.addView(menuRow("浏览网易云", "浏览网易云", () -> enterSource(SearchSource.NETEASE)));
            return;
        }
        loadRemoteLibrary(false);
        boolean inside = remoteLibInside();
        if (filters && !inside) {
            TextView info = text(remoteLibTab == REMOTE_RECENT ? name + " 最近播放的歌曲" : name + " 上的本地音乐",
                    14, MUTED, false);
            info.setPadding(0, 0, 0, dp(8));
            content.addView(info);
            android.widget.HorizontalScrollView categories = new android.widget.HorizontalScrollView(this);
            categories.setHorizontalScrollBarEnabled(false);
            LinearLayout tabs = row();
            for (int i = 0; i < LIBRARY_TABS.length; i++) {
                int tab = i;
                tabs.addView(pill(LIBRARY_TABS[i], remoteLibTab == i, () -> {
                    remoteLibTab = tab;
                    remoteLibQuery = "";
                    render();
                }));
            }
            tabs.addView(pill("最近播放", remoteLibTab == REMOTE_RECENT, () -> {
                remoteLibTab = REMOTE_RECENT;
                render();
            }));
            categories.addView(tabs);
            content.addView(categories);
        }
        if (!remoteLibError.isEmpty()) {
            empty("读取 " + name + " 的音乐库失败", remoteLibError);
            content.addView(menuRow("重试", "重试", () -> { remoteLibKey = ""; render(); }));
            return;
        }
        boolean waiting = remoteLibLoading && remoteLibTracks.isEmpty() && remoteLibGroups.isEmpty();
        if (waiting) {
            empty("正在读取 " + name + " 的音乐库…", "");
            return;
        }
        if (remoteLibTab == 2 && !inside) {
            if (remoteLibPlaylists.isEmpty()) empty("暂无歌单", "在 " + name + " 上新建或导入歌单后会显示在这里。");
            for (String playlist : remoteLibPlaylists) {
                content.addView(entry(playlist, "歌单", "", () -> {
                    remoteLibPlaylist = playlist;
                    render();
                }));
            }
            return;
        }
        boolean grouped = (remoteLibTab == 1 || remoteLibTab >= 3) && remoteLibGroup.isEmpty() && filters;
        if (grouped) {
            if (remoteLibGroups.isEmpty()) {
                empty(name + " 还没有导入本地音乐", "");
                return;
            }
            for (JSONObject group : remoteLibGroups) {
                String detail = group.optInt("count") + " 首歌曲";
                if (!group.optString("detail").isEmpty()) detail = group.optString("detail") + " · " + detail;
                content.addView(entry(group.optString("title"), detail, "", () -> {
                    remoteLibGroup = group.optString("id");
                    remoteLibGroupTitle = group.optString("title");
                    render();
                }));
            }
            if (remoteLibNext >= 0) {
                content.addView(menuRow(remoteLibLoading ? "正在加载…" : "加载更多", "加载更多", () -> loadRemoteLibrary(true)));
            }
            return;
        }
        if (remoteLibTracks.isEmpty()) {
            if (remoteLibTab == REMOTE_RECENT) empty("还没有播放记录", "");
            else empty(remoteLibQuery.isEmpty() ? (inside ? "暂无歌曲" : name + " 还没有导入本地音乐") : "没有找到匹配的歌曲", "");
            return;
        }
        if (filters && remoteLibTab == 0 && !inside) {
            content.addView(toolbar(
                    withIcon(secondary("排序 · " + LIBRARY_SORTS[remoteLibSort], this::chooseRemoteSort), "sort", ACCENT),
                    withIcon(secondary("查找", () -> openSearch(SearchSource.LOCAL)), "search", ACCENT)));
        }
        boolean local = false;
        for (JSONObject track : remoteLibTracks) if (track.optLong("id") < 0) local = true;
        if (local && remoteLibTab != REMOTE_RECENT) {
            content.addView(toolbar(withIcon(primary("播放", () -> remoteLibraryPlayAll(false)), "play", BG),
                    withIcon(secondary("随机", () -> remoteLibraryPlayAll(true)), "shuffle", ACCENT)));
        }
        for (int i = 0; i < remoteLibTracks.size(); i++) {
            int at = i;
            JSONObject track = remoteLibTracks.get(i);
            String detail = track.optString("artist");
            String album = track.optString("album");
            if (!album.isEmpty()) detail += (detail.isEmpty() ? "" : " · ") + album;
            if (detail.isEmpty()) detail = track.optLong("id") < 0 ? "本地音频" : "TIDAL";
            String cover = track.optString("cover");
            LinearLayout entry = entry(track.optString("title"), detail, cover.startsWith("https://") ? cover : "",
                    () -> remoteLibraryPlay(at));
            entry.addView(iconButton("more", "更多 " + track.optString("title"), () -> remoteLibraryMenu(track)));
            content.addView(entry);
        }
        if (remoteLibNext >= 0) {
            content.addView(menuRow(remoteLibLoading ? "正在加载…" : "加载更多", "加载更多", () -> loadRemoteLibrary(true)));
        }
    }

    private void chooseRemoteSort() {
        new AlertDialog.Builder(this).setTitle("排序")
                .setSingleChoiceItems(LIBRARY_SORTS, remoteLibSort, (dialog, choice) -> {
                    dialog.dismiss();
                    remoteLibSort = choice;
                    render();
                }).setNegativeButton("取消", null).show();
    }

    private void remoteLibrarySend(JSONObject command, String message) {
        if (devices.send(command)) toast(message);
        else toast("遥控命令过多，请稍后再试");
    }

    /** Local files play as a list from the chosen song on, like this phone's lists. */
    private void remoteLibraryPlay(int at) {
        if (!remoteMode() || at >= remoteLibTracks.size()) return;
        JSONObject track = remoteLibTracks.get(at);
        try {
            JSONObject command;
            if (remoteLibTab != REMOTE_RECENT && track.optLong("id") < 0) {
                org.json.JSONArray ids = new org.json.JSONArray();
                for (int i = at; i < remoteLibTracks.size() && ids.length() < 50; i++) {
                    long id = remoteLibTracks.get(i).optLong("id");
                    if (id < 0) ids.put(id);
                }
                command = new JSONObject().put("op", "library").put("action", "play-list").put("ids", ids).put("shuffle", false);
            } else {
                command = new JSONObject().put("op", "library").put("action", "song").put("id", track.optLong("id")).put("mode", "play");
            }
            remoteLibrarySend(command, "正在 " + devices.targetName() + " 播放：" + track.optString("title"));
        } catch (org.json.JSONException ignored) {}
    }

    private void remoteLibraryPlayAll(boolean shuffled) {
        org.json.JSONArray ids = new org.json.JSONArray();
        for (JSONObject track : remoteLibTracks) {
            long id = track.optLong("id");
            if (id < 0 && ids.length() < 50) ids.put(id);
        }
        if (ids.length() == 0) return;
        try {
            remoteLibrarySend(new JSONObject().put("op", "library").put("action", "play-list").put("ids", ids)
                    .put("shuffle", shuffled), "正在 " + devices.targetName() + " 播放本页歌曲");
        } catch (org.json.JSONException ignored) {}
    }

    private void remoteLibraryMenu(JSONObject track) {
        String name = devices.targetName();
        new AlertDialog.Builder(this).setTitle(track.optString("title"))
                .setItems(new String[]{"下一首播放", "追加到队列"}, (dialog, choice) -> {
                    if (!remoteMode()) return;
                    try {
                        remoteLibrarySend(new JSONObject().put("op", "library").put("action", "song")
                                .put("id", track.optLong("id")).put("mode", choice == 0 ? "next" : "add"),
                                choice == 0 ? "已加入 " + name + " 的下一首" : "已加入 " + name + " 的队列");
                    } catch (org.json.JSONException ignored) {}
                }).show();
    }

    /** Home while controlling another device: what it plays and where its music is. */
    private void remoteHome() {
        String name = devices.targetName();
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        LinearLayout box = panel(current == null ? "正在遥控 " + name : current.title,
                current == null ? (state == null ? "正在连接…" : "尚未播放") :
                        (state.playing ? "正在 " : "已暂停 · ") + name + (current.artist.isEmpty() ? "" : " · " + current.artist));
        if (current != null && !current.cover.isEmpty()) {
            ImageView image = art(0);
            artwork.load(image, current.cover, 640, current.title);
            box.addView(image, 0, new LinearLayout.LayoutParams(-1, dp(220)));
        }
        box.addView(toolbar(withIcon(primary("打开播放器", () -> open(Page.PLAYER)), "play", BG),
                secondary("播放队列", () -> open(Page.QUEUE))));
        if (remoteHasLibrary()) {
            content.addView(menuRow(name + " 的音乐库", "浏览这台设备上的本地音乐", () -> {
                remoteLibTab = 0;
                open(Page.LOCAL);
            }));
            content.addView(menuRow(name + " 的最近播放", "这台设备最近播放的歌曲", () -> {
                remoteLibTab = REMOTE_RECENT;
                open(Page.LOCAL);
            }));
        } else {
            content.addView(text(name + " 播放 TIDAL 和网易云。在下方音源中选歌，就会在这台设备播放。", 14, MUTED, false));
        }
        content.addView(menuRow("浏览 TIDAL", "浏览 TIDAL", () -> enterSource(SearchSource.TIDAL)));
        content.addView(menuRow("浏览网易云", "浏览网易云", () -> enterSource(SearchSource.NETEASE)));
    }

    /** Move this phone's queue and position to {@code device}, pause here and control it. */
    private void transferTo(RemoteDevices.Device device) {
        if (playback == null || devices == null) return;
        TidalClient.Track current = playback.current();
        if (current == null || !current.needsTidal()) {
            toast("本地和网易云歌曲只能在本机播放");
            return;
        }
        long position = playback.loading ? 0 : playback.player.getCurrentPosition();
        List<TidalClient.Track> queue = new ArrayList<>(playback.queue);
        int index = playback.index;
        devices.select(device.id);
        remoteTracks(queue, index, false, "play", position);
        if (playback.player.getPlayWhenReady() || playback.loading && playback.intendedPlay) playback.toggle();
        toast("已转到 " + device.name + " 继续播放");
    }

    /** Continue the controlled device's queue on this phone and pause it there. */
    private void transferHere() {
        if (!remoteMode() || playback == null) return;
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        if (current == null || current.tidalId <= 0) {
            toast("当前歌曲是该设备的本地或网易云歌曲，无法在本机继续");
            return;
        }
        TidalClient api = playback.client;
        if (api == null) {
            toast(borrowsTidal() ? "本机未登录 TIDAL：借用的「" + devices.targetName() + "」登录只能浏览和下载，"
                    + "歌曲需在该设备上播放。在本机登录 TIDAL 后才能转到本机继续" : "请先在本机登录 TIDAL");
            return;
        }
        // Same 50-track window around the current track as when sending a list.
        List<Long> ids = new ArrayList<>();
        int currentAt = 0;
        for (int i = 0; i < state.queue.size(); i++) {
            long id = state.queue.get(i).tidalId;
            if (id <= 0) continue;
            if (i == state.index) currentAt = ids.size();
            ids.add(id);
        }
        int start = Math.min(currentAt, Math.max(0, ids.size() - 50));
        List<Long> window = new ArrayList<>(ids.subList(start, Math.min(ids.size(), start + 50)));
        long currentId = current.tidalId;
        long position = state.position();
        String name = devices.targetName();
        try {
            if (state.playing) devices.sendNow(devices.target, new JSONObject().put("op", "toggle"));
        } catch (org.json.JSONException ignored) {}
        devices.select(null);
        toast("正在把 " + name + " 的播放转到本机…");
        worker.submit(() -> {
            List<TidalClient.Track> tracks = new ArrayList<>();
            for (long id : window) {
                try {
                    tracks.add(api.track(id));
                } catch (Exception skipped) {
                    // A track this account cannot read is left out.
                }
            }
            int at = -1;
            for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == currentId) at = i;
            int index = at;
            runOnUiThread(() -> {
                if (isDestroyed() || playback == null) return;
                if (index < 0) {
                    toast("无法读取当前歌曲，未能在本机继续");
                    return;
                }
                playback.replaceQueue(tracks, index, false, position);
                toast("已在本机继续播放：" + displayTitle(tracks.get(index)));
            });
        });
    }

    /** While controlling another device, the volume keys change that device's media volume. */
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        boolean volumeKey = code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        RemoteDevices.State state = remoteMode() ? devices.state : null;
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

    private void remoteQueueCommand(String op, int index, int to) {
        RemoteDevices.State state = devices.state;
        if (state == null) return;
        try {
            JSONObject command = new JSONObject().put("op", op).put("index", index).put("revision", state.revision);
            if ("move".equals(op)) command.put("to", to);
            devices.send(command);
        } catch (org.json.JSONException ignored) {}
    }

    /** Fill the mini player and Now Playing from the remote device, using the same views. */
    private void updateRemotePlayer() {
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        String name = devices.targetName();
        String problem = !devices.error.isEmpty() ? devices.error : state != null ? state.error : "";
        boolean playing = state != null && state.playing;
        String art = current == null ? "" : current.cover;
        String artName = current == null ? name : current.album.isEmpty() ? current.title : current.album;
        if (mini != null) {
            applyMiniVisibility();
            GradientDrawable outline = shape(RAISED, 15);
            outline.setStroke(dp(1), ACCENT);
            mini.setBackground(outline);
            miniTitle.setText(current != null ? current.title : state == null ? "正在连接 " + name + "…" : "尚未播放");
            miniArtist.setText(!problem.isEmpty() ? problem : "遥控 · " + name
                    + (current == null || current.artist.isEmpty() ? "" : " · " + current.artist));
            miniArtist.setTextColor(problem.isEmpty() ? ACCENT : 0xffff9aa6);
            miniArtist.setVisibility(View.VISIBLE);
            artwork.load(miniArt, art, 160, artName);
            playIcon(miniPlay, playing);
        }
        if (seek == null) return;
        String coverKey = art + "\n" + artName;
        if (!coverKey.equals(visibleRemoteCover)) {
            visibleRemoteCover = coverKey;
            artwork.load(cover, art, 640, artName);
        }
        playerHeading.setText("遥控 · " + name);
        title.setText(current != null ? current.title : state == null ? "正在连接…" : "尚未播放");
        subtitle.setText(current == null ? "可从歌曲菜单发送到此设备"
                : !current.artist.isEmpty() ? current.artist : current.album);
        showPlayingAlbum(current == null ? "" : current.album, current != null && !current.artist.isEmpty());
        favorite.setVisibility(View.INVISIBLE);
        boolean quality = state != null && (!state.quality.isEmpty() || !state.requested.isEmpty());
        playerQuality.setVisibility(quality ? View.VISIBLE : View.GONE);
        if (quality) {
            int requested = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
            String label = !state.quality.isEmpty() ? state.quality
                    : requested >= 0 ? PlaybackService.QUALITY_NAMES[requested] : "";
            playerQuality.setText(label + (state.requested.isEmpty() ? "" : "  ▾"));
            playerQuality.setEnabled(!state.requested.isEmpty());
            playerQuality.setTextColor(label.startsWith("Max") ? 0xffe8c77a : ACCENT);
        }
        playerDetails.setEnabled(current != null);
        playerDetails.setVisibility(View.VISIBLE);
        playerLyrics.setVisibility(current == null ? View.GONE : View.VISIBLE);
        playerComments.setVisibility(View.GONE);
        playIcon(play, playing);
        boolean shuffled = state != null && state.shuffle;
        int mode = state == null ? 0 : state.repeat;
        shuffle.setImageDrawable(new PlayerIcon("shuffle", shuffled ? ACCENT : MUTED, dp(22)));
        shuffle.setContentDescription(shuffled ? "关闭随机播放" : "打开随机播放");
        repeat.setImageDrawable(new PlayerIcon(mode == 2 ? "repeat-one" : "repeat", mode == 0 ? MUTED : ACCENT, dp(22)));
        repeat.setContentDescription(mode == 2 ? "单曲循环" : mode == 1 ? "队列循环" : "关闭循环");
        message.setText(!problem.isEmpty() ? problem : state == null ? "正在连接 " + name + "…"
                : state.loading ? "设备正在获取音频流…" : devices.pending() > 0 ? "正在发送…" : "");
        updatePosition();
    }

    /** The remote device's queue, laid out like this phone's. */
    private void remoteQueue() {
        String name = devices.targetName();
        content.addView(menuRow("遥控 · " + name, "切换播放设备", this::showDevicePicker));
        RemoteDevices.State state = devices.state;
        if (state == null) {
            empty(devices.error.isEmpty() ? "正在读取队列…" : devices.error, "队列来自 " + name);
            return;
        }
        if (state.queue.isEmpty()) {
            empty("队列是空的", "从歌曲菜单发送歌曲到 " + name);
            return;
        }
        RemoteDevices.Track playing = state.current();
        if (playing != null && playing.tidalId > 0) {
            content.addView(menuRow("在本机继续播放", playback != null && playback.client == null
                    ? "需先在本机登录 TIDAL" : "把队列和进度转到本机", this::transferHere));
        }
        if (state.index >= 0 && state.index + 1 < state.queue.size()) {
            content.addView(menuRow("清空接下来", "清空该设备接下来的歌曲", () -> {
                if (remoteMode()) devices.send("clear");
            }));
        }
        section(state.queue.size() + " 首歌曲");
        showGrowing(state.queue.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                int at = i;
                RemoteDevices.Track track = state.queue.get(at);
                boolean playingNow = i == state.index;
                LinearLayout entry = entry(track.title,
                        playingNow ? "正在播放" + (track.artist.isEmpty() ? "" : " · " + track.artist) : track.artist,
                        track.cover, () -> remoteQueueCommand("play", at, 0));
                if (playingNow) {
                    entry.setTag(QUEUE_PLAYING);
                    entry.setBackground(shape(RAISED, 12));
                    LinearLayout info = (LinearLayout) entry.getChildAt(1);
                    ((TextView) info.getChildAt(0)).setTextColor(ACCENT);
                    ((TextView) info.getChildAt(1)).setTextColor(ACCENT);
                }
                entry.addView(iconButton("more", "调整队列 " + track.title, () -> editRemoteQueue(at, track)));
                content.addView(entry);
            }
        });
        if (revealQueue && state.index >= LOCAL_PAGE_SIZE) fillTo(state.index);
    }

    private void editRemoteQueue(int at, RemoteDevices.Track track) {
        new AlertDialog.Builder(this).setTitle(track.title)
                .setItems(new String[]{"移到上一位", "移到下一位", "移除"}, (dialog, choice) -> {
                    RemoteDevices.State state = remoteMode() ? devices.state : null;
                    if (state == null || at >= state.queue.size()) return;
                    if (choice == 0 && at > 0) remoteQueueCommand("move", at, at - 1);
                    else if (choice == 1 && at + 1 < state.queue.size()) remoteQueueCommand("move", at, at + 1);
                    else if (choice == 2) {
                        if (at == state.index) toast("正在播放的歌曲不能移除");
                        else remoteQueueCommand("remove", at, 0);
                    }
                }).show();
    }

    private static String deviceStatus(RemoteDevices.Device device) {
        if (!device.online) return "离线";
        if (!device.hasNow) return "在线 · 空闲";
        return (device.nowPlaying ? "正在播放 " : "已暂停 ") + device.nowTitle;
    }

    private void selectDevice(String id) {
        if (devices == null) return;
        boolean borrowed = borrowsTidal();
        String previous = devices.targetName();
        devices.select(id);
        if (id == null && borrowed) toast("已切回本机播放 · 本机未登录 TIDAL，不再使用「" + previous + "」的登录");
        else toast(id == null ? "已切回本机播放" : "正在遥控：" + devices.targetName());
    }

    /** Choose which device the player controls. */
    private void showDevicePicker() {
        if (devices == null) {
            toast("请先在 设置 → 设备与连接 登录播放器账号");
            return;
        }
        devices.refreshSoon();
        List<RemoteDevices.Device> others = new ArrayList<>();
        for (RemoteDevices.Device device : devices.devices) if (!device.self) others.add(device);
        List<String> labels = new ArrayList<>();
        labels.add((remoteMode() ? "○  " : "●  ") + "本机 · 此手机");
        RemoteDevices.Track remoteCurrent = remoteMode() && devices.state != null ? devices.state.current() : null;
        boolean canPull = remoteCurrent != null && remoteCurrent.tidalId > 0;
        if (canPull) labels.add("⇠  在本机继续播放");
        for (RemoteDevices.Device device : others) {
            boolean selected = device.id.equals(devices.target);
            labels.add((selected ? "●  " : "○  ") + device.name + "\n      " + deviceStatus(device));
        }
        if (others.isEmpty()) labels.add(devices.devicesLoaded ? "暂无其他设备" : "正在读取设备…");
        labels.add("管理设备…");
        new AlertDialog.Builder(this).setTitle("播放设备")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int offset = canPull ? 2 : 1;
                    if (which == 0) selectDevice(null);
                    else if (canPull && which == 1) transferHere();
                    else if (which == labels.size() - 1) open(Page.DEVICES);
                    else if (which - offset < others.size()) {
                        RemoteDevices.Device device = others.get(which - offset);
                        if (device.online) selectDevice(device.id);
                        else toast(device.name + " 离线");
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** Every player signed in to this player account. */
    private void devicesPage() {
        if (devices == null) {
            empty("尚未登录播放器账号", "在 设置 → 设备与连接 登录后，可查看并遥控同一账号的电脑和手机。");
            return;
        }
        String username = getSharedPreferences("tidal", MODE_PRIVATE).getString("player_account", "");
        content.addView(text("登录播放器账号「" + username + "」的电脑和手机。选择一台设备后，迷你播放器、正在播放和播放队列会改为控制它。",
                14, MUTED, false));
        if (!devices.devicesError.isEmpty()) content.addView(text(devices.devicesError, 14, 0xffff9aa6, false));
        if (!devices.devicesLoaded) {
            empty("正在读取设备…", "");
            return;
        }
        List<RemoteDevices.Device> sorted = new ArrayList<>(devices.devices);
        java.util.Collections.sort(sorted, (a, b) -> a.self != b.self ? (a.self ? -1 : 1) : Boolean.compare(b.online, a.online));
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));
        for (RemoteDevices.Device device : sorted) deviceCard(device);
        if (sorted.size() <= 1) {
            content.addView(text("其他电脑或手机登录同一个播放器账号后会出现在这里。", 14, MUTED, false));
        } else {
            content.addView(menuRow("退出其他所有设备", "其他电脑、手机和网页都需要重新登录", () ->
                    new AlertDialog.Builder(this).setTitle("退出其他所有设备？")
                            .setMessage("其他电脑、手机和网页遥控都需要重新登录播放器账号，本机保持登录。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("退出", (dialog, which) -> {
                                if (devices != null) devices.logoutOthers(this::render);
                            }).show()));
        }
    }

    private void deviceCard(RemoteDevices.Device device) {
        boolean selected = device.id.equals(devices.target);
        String state = device.self ? "本机" : selected ? "正在遥控" : device.online ? "在线" : "离线";
        String detail = device.platformLabel() + (device.version.isEmpty() ? "" : " · 版本 " + device.version) + " · " + state
                + (device.online || device.self ? "" : " · " + device.lastSeenLabel());
        LinearLayout box = panel(device.name, detail);
        if (selected) {
            GradientDrawable outline = shape(ListeningRoomColors.SURFACE, 16);
            outline.setStroke(dp(1), ACCENT);
            box.setBackground(outline);
        }
        ((TextView) box.getChildAt(0)).setCompoundDrawablesRelativeWithIntrinsicBounds(
                new PlayerIcon("devices", selected || device.self ? ACCENT : MUTED, dp(20)), null, null, null);
        ((TextView) box.getChildAt(0)).setCompoundDrawablePadding(dp(8));
        if (device.hasNow) {
            LinearLayout now = row();
            ImageView image = art(44);
            artwork.load(image, device.nowCover, 160, device.nowTitle);
            now.addView(image);
            LinearLayout info = column();
            info.setPadding(dp(12), 0, 0, 0);
            info.addView(single(device.nowPlaying ? "正在播放" : "已暂停", 12, device.nowPlaying ? ACCENT : MUTED, false));
            info.addView(single(device.nowTitle, 15, INK, true));
            if (!device.nowArtist.isEmpty()) info.addView(single(device.nowArtist, 12, MUTED, false));
            now.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.bottomMargin = dp(8);
            box.addView(now, spacing);
        }
        if (device.self) return;
        List<Button> buttons = new ArrayList<>();
        if (selected) buttons.add(secondary("切回本机", () -> selectDevice(null)));
        else {
            Button control = withIcon(primary("遥控", () -> selectDevice(device.id)), "devices", BG);
            control.setEnabled(device.online);
            control.setAlpha(device.online ? 1f : 0.45f);
            buttons.add(control);
            TidalClient.Track local = playback == null ? null : playback.current();
            if (!remoteMode() && device.online && local != null && local.needsTidal()) {
                buttons.add(secondary("转到此设备", () -> transferTo(device)));
            }
        }
        Button remove = secondary("移除", () -> new AlertDialog.Builder(this).setTitle("移除 " + device.name + "？")
                .setMessage("该设备需要重新登录播放器账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    if (devices != null) devices.remove(device.id, this::render);
                }).show());
        remove.setTextColor(0xffff8f8f);
        buttons.add(remove);
        box.addView(toolbar(buttons.toArray(new Button[0])));
    }

    private void editQueue(int at, TidalClient.Track track) {
        new AlertDialog.Builder(this).setTitle(track.title)
                .setItems(new String[]{"移到上一位", "移到下一位", "移除"}, (dialog, choice) -> {
                    if (playback == null || at >= playback.queue.size() || playback.queue.get(at) != track) return;
                    if (choice == 0) playback.move(at, at - 1);
                    else if (choice == 1) playback.move(at, at + 1);
                    else if (at == playback.index) toast("正在播放的歌曲不能移除");
                    else playback.remove(at);
                    render();
                }).show();
    }

    private void tidalSettings() {
        section("账号状态");
        empty(playback != null && playback.client != null ? "已登录" : "尚未登录",
                playback != null && !playback.error.isEmpty() ? playback.error : "用设备链接在 TIDAL 网页完成授权，无需 PC");
        content.addView(toolbar(withIcon(primary(playback != null && playback.client != null ? "重新登录" : "设备链接登录",
                this::login), "user", BG)));
        section("其他方式");
        content.addView(menuRow("浏览器登录", "浏览器登录", this::startBrowserLogin));
        content.addView(menuRow("设备授权配置", "设备授权配置", this::configureTidalDevice));
        content.addView(menuRow("导入已有会话", "导入已有会话", this::pickSession));
        if (playback != null && playback.client == null && playback.relay != null) {
            content.addView(menuRow("同步 PC 登录", "同步 PC 登录", () -> {
                playback.enableDesktopSessionSync();
                toast("等待 PC 同步 TIDAL 登录");
            }));
        }
        if (playback != null && playback.client != null) {
            content.addView(menuRow("退出账号", "退出账号", () -> new AlertDialog.Builder(this)
                    .setTitle("退出 TIDAL 账号？").setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        cancelLogin();
                        playback.signOutFromUi();
                        clearFavorites();
                        render();
                    }).show()));
        }
    }

    private void settings() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        LinearLayout updates = panel("应用更新", "当前版本 " + version);
        checkUpdateButton = primary("检查更新", this::checkUpdate);
        updates.addView(checkUpdateButton, new LinearLayout.LayoutParams(-2, dp(44)));
        updateStatus = text("", 13, MUTED, false);
        updateStatus.setPadding(0, dp(8), 0, 0);
        updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updates.addView(updateStatus);
        syncUpdateControls();
        Uri directory = MusicDownload.directory(this);
        LinearLayout download = panel("下载目录", directory == null ? "系统默认下载目录"
                : android.provider.DocumentsContract.getTreeDocumentId(directory));
        download.addView(menuRow("下载管理", "下载管理", () -> open(Page.DOWNLOADS)));
        download.addView(menuRow("同时下载 · " + downloadTasks.parallel() + " 首", "设置同时下载的歌曲数",
                this::chooseParallelDownloads));
        download.addView(menuRow("选择下载目录", "选择下载目录", () ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), DOWNLOAD_DIRECTORY)));
        if (directory != null) {
            download.addView(menuRow("恢复默认目录", "恢复默认下载目录", () -> {
                getSharedPreferences("tidal", MODE_PRIVATE).edit().remove(MusicDownload.DIRECTORY).apply();
                render();
            }));
        }
        LinearLayout storage = panel("音乐目录", "添加或重新扫描文件夹。");
        storage.addView(menuRow("管理扫描目录", "管理扫描目录", this::chooseFolders));
        LinearLayout connection = panel("设备与连接", "局域网遥控、文件管理和多端后台。");
        connection.addView(menuRow("设备与连接", "设备与连接", () -> open(Page.CONNECTIONS)));
        LinearLayout sound = panel("播放设置", "TIDAL 默认流媒体音质。");
        sound.addView(menuRow("TIDAL 音质 · " + (playback == null ? PlaybackService.QUALITY_NAMES[0]
                : PlaybackService.QUALITY_NAMES[playback.quality]), "选择 TIDAL 播放音质", this::chooseTidalQuality));
        sound.addView(menuRow("网易云音质 · " + (playback == null ? "无损" : NeteaseClient.levelName(playback.neteaseLevel)),
                "选择网易云播放音质", this::chooseNeteaseQuality));
    }

    private void connections() {
        section("播放器账号");
        android.content.SharedPreferences saved = getSharedPreferences("tidal", MODE_PRIVATE);
        String username = saved.getString("player_account", "");
        if (username.isEmpty()) {
            content.addView(menuRow("登录播放器账号", "登录播放器账号", this::playerAccountLogin));
        } else {
            content.addView(text(username, 17, INK, true));
            relayStatus = text(playback != null && playback.relay != null ? playback.relay.status : "正在连接…",
                    14, MUTED, false);
            content.addView(relayStatus);
            content.addView(menuRow("我的设备", "查看并遥控同一账号的所有设备", () -> open(Page.DEVICES)));
            if (!PlayerAccount.ORIGIN.isEmpty()) {
                content.addView(menuRow("网页遥控", "在浏览器打开多端遥控",
                        () -> openTidalBrowser(Uri.parse(PlayerAccount.ORIGIN))));
            }
            android.widget.Switch lend = new android.widget.Switch(this);
            lend.setText("允许我的其他设备使用本机的 TIDAL 登录和下载");
            lend.setTextColor(INK);
            lend.setChecked(playback != null && playback.lendTidal);
            lend.setOnCheckedChangeListener((view, enabled) -> {
                if (playback != null) playback.setLendTidal(enabled);
            });
            content.addView(lend);
            content.addView(text("开启后，同一播放器账号的设备遥控本机时，可以通过本机的 TIDAL 登录浏览、收藏，"
                    + "并在本机下载和管理下载。它们拿不到本机的登录凭据。", 13, MUTED, false));
            content.addView(menuRow("退出播放器账号", "退出播放器账号", this::playerAccountLogout));
        }
        section("局域网遥控");
        boolean remoteEnabled = playback != null && !playback.remoteLink().isEmpty();
        if (remoteEnabled) {
            TextView address = text(playback.remoteAddress(), 13, MUTED, false);
            address.setTextIsSelectable(true);
            content.addView(address);
            TextView code = text(playback.remoteCode(), 25, INK, true);
            code.setTextIsSelectable(true);
            content.addView(code);
            content.addView(text("认证码会保留。刷新认证码后，已配对的浏览器需要重新配对。", 13, MUTED, false));
            content.addView(menuRow("复制配对码", "复制配对码", () -> copyAuthorization(playback.remoteCode())));
            content.addView(menuRow("复制遥控链接", "复制遥控链接", () -> copyAuthorization(playback.remoteLink())));
            content.addView(menuRow("刷新认证码", "刷新认证码", () ->
                    new AlertDialog.Builder(this).setTitle("刷新认证码？")
                            .setMessage("已配对的浏览器需要用新码重新配对。")
                            .setNegativeButton("取消", null).setPositiveButton("刷新", (dialog, button) -> {
                                try {
                                    playback.refreshRemoteCode();
                                } catch (IOException problem) {
                                    toast(readable(problem));
                                }
                                render();
                            }).show()));
            android.widget.Switch routed = new android.widget.Switch(this);
            routed.setText("允许其他私有网段");
            routed.setTextColor(INK);
            routed.setChecked(playback.remoteRoutedLan);
            routed.setOnCheckedChangeListener((view, allowed) -> {
                if (playback != null) playback.setRemoteRoutedLan(allowed);
            });
            content.addView(routed);
            content.addView(text("电脑与手机在不同私有网段时，且网络允许互访，可打开此项。", 13, MUTED, false));
            content.addView(menuRow("关闭遥控", "关闭遥控", () -> {
                playback.disableRemote();
                render();
            }));
        } else if (playback != null && playback.remoteRequested()) {
            content.addView(text(playback.remoteIssue(), 14, MUTED, false));
            content.addView(menuRow("关闭遥控", "关闭遥控", () -> {
                playback.disableRemote();
                render();
            }));
        } else {
            content.addView(text("让同一 Wi-Fi 下的电脑通过浏览器登录、播放和编辑队列。", 14, MUTED, false));
            content.addView(menuRow("启用局域网遥控", "启用局域网遥控", () -> {
                if (playback == null) return;
                try {
                    playback.enableRemote();
                } catch (Exception problem) {
                    toast(readable(problem));
                }
                render();
            }));
        }
        section("远程文件管理");
        android.widget.Switch files = new android.widget.Switch(this);
        files.setText("允许已配对浏览器管理音乐文件");
        files.setTextColor(INK);
        files.setEnabled(remoteEnabled && playback.localLibrary.hasFolders());
        files.setChecked(playback != null && playback.remoteFiles);
        files.setOnCheckedChangeListener((view, enabled) -> {
            if (playback != null) playback.setRemoteFiles(enabled);
        });
        content.addView(files);
        content.addView(text("管理范围仅限已授权的音乐目录。重新启用遥控时会恢复此前的权限。", 13, MUTED, false));
        content.addView(menuRow("管理扫描目录", "管理扫描目录", this::chooseFolders));
    }

    private void playerAccountLogin() {
        if (playback == null) return;
        LinearLayout fields = column();
        fields.setPadding(dp(24), 0, dp(24), 0);
        EditText username = new EditText(this);
        username.setHint("用户名");
        username.setSingleLine(true);
        EditText password = new EditText(this);
        password.setHint("密码");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        fields.addView(username);
        fields.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录播放器账号")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("登录", null).show();
        Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            String name = username.getText().toString().trim();
            String pass = password.getText().toString();
            submit.setEnabled(false);
            worker.submit(() -> {
                try {
                    JSONObject account = PlayerAccount.login(name, pass);
                    runOnUiThread(() -> {
                        if (isDestroyed() || playback == null || !dialog.isShowing()) return;
                        try {
                            playback.connectPlayerAccount(account);
                            password.setText("");
                            dialog.dismiss();
                            render();
                        } catch (Exception problem) {
                            submit.setEnabled(true);
                            password.setError(CentralRelay.readable(problem));
                        }
                    });
                } catch (Exception problem) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        submit.setEnabled(true);
                        password.setError(CentralRelay.readable(problem));
                    });
                }
            });
        });
    }

    private void playerAccountLogout() {
        String key = getSharedPreferences("tidal", MODE_PRIVATE).getString("relay_key", "");
        new AlertDialog.Builder(this).setTitle("退出播放器账号？")
                .setNegativeButton("取消", null).setPositiveButton("退出", (dialog, button) ->
                    worker.submit(() -> {
                        try {
                            PlayerAccount.logout(key);
                            runOnUiThread(() -> {
                                if (playback == null || isDestroyed()) return;
                                playback.disconnectPlayerAccount();
                                render();
                            });
                        } catch (Exception problem) {
                            runOnUiThread(() -> toast(CentralRelay.readable(problem)));
                        }
                    })).show();
    }

    private void syncUpdateControls() {
        if (checkUpdateButton != null) {
            checkUpdateButton.setEnabled(!updateBusy);
            checkUpdateButton.setText(updateBusy ? "请稍候…" : "检查更新");
        }
        if (updateStatus != null) {
            updateStatus.setText(updateMessage);
            updateStatus.setVisibility(updateMessage.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void updateState(boolean busy, String message) {
        updateBusy = busy;
        updateMessage = message;
        syncUpdateControls();
    }

    private void checkUpdate() {
        if (updateBusy) return;
        updateState(true, "正在检查更新…");
        worker.submit(() -> {
            try {
                org.json.JSONObject update = AppUpdate.latest();
                long current = AppUpdate.version(getPackageManager().getPackageInfo(getPackageName(), 0));
                long latest = update.optLong("versionCode");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (latest <= current) {
                        updateState(false, "当前已是最新版本。");
                        return;
                    }
                    showUpgradePrompt(update);
                });
            } catch (Exception problem) {
                reportUpdateFailure(problem);
            }
        });
    }

    private void showUpgradePrompt(org.json.JSONObject update) {
        String version = update.optString("versionName", Long.toString(update.optLong("versionCode")));
        String notes = update.optString("notes").trim();
        updateState(false, "可升级至 " + version);
        if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        new AlertDialog.Builder(this).setTitle("升级到 " + version + "？")
                .setMessage(notes.isEmpty() ? "下载后由 Android 系统确认安装。" : notes)
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即升级", (dialog, which) -> downloadUpdate(update)).show();
    }

    private void downloadUpdate(org.json.JSONObject update) {
        if (updateBusy) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                installManifest = update;
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION);
            } catch (android.content.ActivityNotFoundException unavailable) {
                installManifest = null;
                updateState(false, "当前设备无法从应用内安装更新。");
            }
            return;
        }
        updateState(true, "正在下载和验证更新…");
        worker.submit(() -> {
            try {
                java.io.File apk = AppUpdate.download(this, update);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    updateState(false, "APK 已验证，请在系统界面确认安装。");
                    Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".updates", apk);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                contentUri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (android.content.ActivityNotFoundException unavailable) {
                        toast("此设备没有可用的 APK 安装程序");
                    }
                });
            } catch (Exception problem) {
                reportUpdateFailure(problem);
            }
        });
    }

    private void reportUpdateFailure(Exception problem) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            String message = "更新失败 · " + CentralRelay.readable(problem);
            updateState(false, message);
            if (page != Page.SETTINGS) toast(message);
        });
    }

    private void startBrowserLogin() {
        cancelLogin();
        if (playback == null) {
            toast("播放服务正在启动，请重试");
            return;
        }
        int version = ++importGeneration;
        loginDialog = new AlertDialog.Builder(this).setTitle("浏览器登录 TIDAL")
                .setMessage("正在准备 TIDAL 登录网页…")
                .setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setOnCancelListener(dialog -> cancelLogin()).show();
        loginTask = worker.submit(() -> {
            try {
                DeviceLogin.BrowserGrant grant = DeviceLogin.startBrowser();
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration) return;
                    loginDialog.dismiss();
                    loginDialog = new AlertDialog.Builder(this).setTitle("等待浏览器授权")
                            .setMessage("在 TIDAL 网页完成登录后，返回听音室。")
                            .setNegativeButton("取消", (dialog, which) -> cancelLogin())
                            .setPositiveButton("打开 TIDAL 网页", null)
                            .setOnCancelListener(dialog -> cancelLogin()).show();
                    Button browser = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    browser.setOnClickListener(view -> openTidalBrowser(grant.url));
                    if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        browser.performClick();
                    }
                });
                TidalClient.Session account = DeviceLogin.awaitBrowser(grant);
                String saved = account.toJson();
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration || playback == null) return;
                    cancelLogin();
                    finishSignIn(account, saved, "已登录 TIDAL");
                });
            } catch (Exception problem) {
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration) return;
                    cancelLogin();
                    new AlertDialog.Builder(this).setTitle("浏览器登录暂不可用")
                            .setMessage(readable(problem))
                            .setNegativeButton("关闭", null)
                            .setNeutralButton("账号设置", (dialog, which) -> open(Page.ACCOUNT)).show();
                });
            }
        });
    }

    private void openTidalBrowser(Uri address) {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, address);
            browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
            startActivity(browser);
        } catch (android.content.ActivityNotFoundException missing) {
            toast("当前设备没有可打开 TIDAL 的浏览器");
        }
    }

    private void login() {
        cancelLogin();
        if (playback == null) {
            toast("播放服务正在启动，请重试");
            return;
        }
        DeviceLogin device = playback.tidalDevice();
        startLogin(device == null ? DeviceLogin.brokered() : device);
    }

    private void configureTidalDevice() {
        cancelLogin();
        LinearLayout fields = column();
        fields.setPadding(dp(24), 0, dp(24), 0);
        EditText id = new EditText(this);
        id.setHint("Client ID");
        id.setSingleLine();
        if (playback != null && playback.tidalDevice() != null) id.setText(playback.tidalDevice().clientId);
        EditText secret = new EditText(this);
        secret.setHint("Client Secret");
        secret.setSingleLine();
        secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(id);
        fields.addView(secret);
        AlertDialog configuration = new AlertDialog.Builder(this).setTitle("设备授权配置")
                .setMessage("首次使用需要获准的 TIDAL 设备客户端。授权成功后，以后登录会直接显示设备码。账号密码在 TIDAL 网页输入。")
                .setView(fields).setNegativeButton("取消", null)
                .setPositiveButton("获取设备码", null).show();
        configuration.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                DeviceLogin device = new DeviceLogin(id.getText().toString(), secret.getText().toString());
                configuration.dismiss();
                startLogin(device);
            } catch (IOException error) {
                id.setError(error.getMessage());
            }
        });
    }

    private void startLogin(DeviceLogin device) {
        if (playback == null) {
            toast("播放服务正在启动，请重试");
            return;
        }
        playback.configureRemoteLogin(device);
        int version = ++importGeneration;
        loginDialog = new AlertDialog.Builder(this).setTitle("连接 TIDAL")
                .setMessage("正在申请设备授权码…").setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setOnCancelListener(dialog -> cancelLogin()).show();
        loginTask = worker.submit(() -> {
            try {
                DeviceLogin.Challenge challenge = device.start();
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration || playback == null) return;
                    playback.showRemoteAuthorization(challenge);
                    showDeviceChallenge(challenge);
                });
                TidalClient.Session session = device.await(challenge);
                String saved = session.toJson();
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration || playback == null) return;
                    cancelLogin();
                    finishSignIn(session, saved, "已登录 TIDAL");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || version != importGeneration) return;
                    cancelLogin();
                    AlertDialog.Builder failure = new AlertDialog.Builder(this).setTitle("登录失败")
                            .setMessage(readable(error)).setPositiveButton("确定", null);
                    if (device.clientId.isEmpty() && readable(error).contains("客户端")) {
                        failure.setNeutralButton("手动配置客户端", (dialog, which) -> configureTidalDevice());
                    }
                    failure.show();
                });
            }
        });
    }

    private void showDeviceChallenge(DeviceLogin.Challenge challenge) {
        loginDialog.dismiss();
        LinearLayout details = column();
        details.setPadding(dp(24), dp(8), dp(24), 0);
        details.addView(text("在 TIDAL 网页完成授权", 16, INK, true));
        details.addView(text("授权完成后返回听音室。也可以在另一台设备打开下方网址并输入设备码。", 14, MUTED, false));
        TextView address = text(challenge.verificationPage.toString(), 14, ACCENT, false);
        address.setTextIsSelectable(true);
        details.addView(address);
        TextView code = text(challenge.userCode, 25, INK, true);
        code.setTextIsSelectable(true);
        details.addView(code);
        LinearLayout copy = row();
        copy.addView(action("复制授权码", "复制授权码", () -> copyAuthorization(challenge.userCode)));
        copy.addView(action("复制直达链接", "复制直达链接", () -> copyAuthorization(challenge.verification.toString())));
        details.addView(copy);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        loginDialog = new AlertDialog.Builder(this).setTitle("TIDAL 设备链接")
                .setView(scroll).setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setPositiveButton("本机浏览器登录", null)
                .setOnCancelListener(dialog -> cancelLogin()).show();
        Button browser = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        browser.setOnClickListener(view -> openTidalBrowser(challenge.verification));
        if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            browser.performClick();
        }
    }

    private void copyAuthorization(String value) {
        android.content.ClipData clip = android.content.ClipData.newPlainText("TIDAL 设备授权", value);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        if (android.os.Build.VERSION.SDK_INT < 33) toast("已复制");
    }

    private void cancelLogin() {
        importGeneration++;
        if (loginTask != null) {
            loginTask.cancel(true);
            if (playback != null) playback.clearRemoteAuthorization();
        }
        if (loginDialog != null) loginDialog.dismiss();
        loginTask = null;
        loginDialog = null;
    }

    private void finishSignIn(TidalClient.Session session, String saved, String message) {
        playback.changeAccount(session, saved);
        clearFavorites();
        toast(message);
        libraryFilter = 0;
        navigate(Page.LIBRARY);
    }

    private void pickAudio() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, IMPORT_AUDIO);
    }

    private void pickFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
                IMPORT_FOLDER);
    }

    private void chooseFolders() {
        if (playback == null) return;
        List<Uri> folders = playback.localLibrary.folders();
        if (folders.isEmpty()) {
            pickFolder();
            return;
        }
        String[] display = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            display[i] = android.provider.DocumentsContract.getTreeDocumentId(folders.get(i));
        }
        new AlertDialog.Builder(this).setTitle("音乐文件夹").setItems(display, (dialog, selected) -> {
            Uri tree = folders.get(selected);
            new AlertDialog.Builder(this).setTitle(display[selected])
                    .setItems(new String[]{"重新扫描", "取消目录授权"}, (action, which) -> {
                        if (which == 0) scanDirectory(tree, false);
                        else new AlertDialog.Builder(this).setTitle("从音乐库移除此目录？")
                                .setMessage("文件会保留在原目录。").setNegativeButton("取消", null)
                                .setPositiveButton("移除目录", (confirm, choice) -> {
                                    if (playback == null) return;
                                    for (TidalClient.Track track : playback.localLibrary.managedTracks()) {
                                        if (tree.toString().equals(track.localRoot)) playback.fileDeleted(track.id);
                                    }
                                    playback.localLibrary.forgetFolder(tree);
                                    if (!playback.localLibrary.hasFolders()) playback.setRemoteFiles(false);
                                    for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                                        if (!permission.getUri().equals(tree)) continue;
                                        int modes = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                                                | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                                        try { getContentResolver().releasePersistableUriPermission(tree, modes); }
                                        catch (SecurityException ignored) {}
                                    }
                                    playback.libraryChanged();
                                    if (page == Page.CONNECTIONS) render();
                                }).show();
                    }).show();
        }).setPositiveButton("添加目录", (dialog, which) -> pickFolder()).setNegativeButton("关闭", null).show();
    }

    private void scanDirectory(Uri tree, boolean added) {
        if (playback == null || localBusy) {
            toast("音乐库正在处理文件");
            return;
        }
        if (added) playback.localLibrary.addFolder(tree);
        scanDirectories(java.util.Collections.singletonList(tree));
    }

    private void scanDirectories(List<Uri> trees) {
        if (playback == null || localBusy || trees.isEmpty()) return;
        PlaybackService service = playback;
        localBusy = true;
        localScanSummary = "";
        if (page != Page.LOCAL) open(Page.LOCAL);
        else render();
        worker.submit(() -> {
            LocalLibrary.ScanResult total = new LocalLibrary.ScanResult();
            int failures = 0;
            String lastError = "";
            for (Uri tree : trees) {
                try {
                    LocalLibrary.ScanResult scan = service.localLibrary.scan(tree);
                    total.total += scan.total;
                    total.changed += scan.changed;
                    total.reused += scan.reused;
                    total.removed += scan.removed;
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception problem) {
                    failures++;
                    lastError = readable(problem);
                }
            }
            String summary = failures == trees.size() ? "扫描失败 · " + lastError
                    : "本次扫描 " + total.total + " 首 · 更新 " + total.changed
                    + " · 复用 " + total.reused + " · 移除 " + total.removed
                    + (failures == 0 ? "" : " · " + failures + " 个目录读取失败");
            runOnUiThread(() -> {
                if (isDestroyed() || playback != service) return;
                localBusy = false;
                localScanSummary = summary;
                service.libraryChanged();
                if (page != Page.LOCAL) toast(summary);
            });
        });
    }

    private void importAudio(Intent data) {
        if (playback == null || localBusy) return;
        List<Uri> sources = new ArrayList<>();
        android.content.ClipData selection = data.getClipData();
        if (selection != null) {
            for (int i = 0; i < selection.getItemCount(); i++) {
                Uri uri = selection.getItemAt(i).getUri();
                if (uri != null) sources.add(uri);
            }
        } else if (data.getData() != null) sources.add(data.getData());
        if (sources.isEmpty()) return;
        PlaybackService service = playback;
        localBusy = true;
        if (page != Page.LOCAL) open(Page.LOCAL);
        else render();
        worker.submit(() -> {
            List<TidalClient.Track> loaded = new ArrayList<>();
            for (Uri uri : sources) {
                try {
                    TidalClient.Track track = LocalLibrary.inspect(this, uri, null);
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    loaded.add(track);
                } catch (Exception ignored) {
                    // Partial import still keeps valid tracks.
                }
            }
            runOnUiThread(() -> {
                if (isDestroyed() || playback != service) return;
                localBusy = false;
                int count = service.localLibrary.importTracks(loaded);
                service.libraryChanged();
                int failed = sources.size() - loaded.size();
                toast("已导入 " + count + " 首" + (failed > 0 ? "，" + failed + " 首未能读取" : ""));
            });
        });
    }

    private void pickSession() {
        cancelLogin();
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_SESSION);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (exportPlaylist != null) state.putString("export_playlist", exportPlaylist);
    }

    private void pickPlaylistSource() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_M3U);
    }

    private void pickPlaylistDestination() {
        if (localPlaylist == null) return;
        exportPlaylist = localPlaylist;
        String filename = localPlaylist.replaceAll("[/\\\\:*?\"<>|]", "_") + ".m3u8";
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/x-mpegurl")
                .putExtra(Intent.EXTRA_TITLE, filename), EXPORT_M3U);
    }

    private void importM3u(Uri source) {
        if (playback == null) return;
        PlaybackService service = playback;
        worker.submit(() -> {
            try {
                LocalMusicFiles.Playlist parsed = LocalMusicFiles.importM3u(
                        getContentResolver(), source, service.localLibrary.search("", null));
                if (parsed.ids.isEmpty()) throw new IOException("歌单没有匹配的音频，请先扫描音乐目录");
                String name = "导入歌单";
                try (android.database.Cursor cursor = getContentResolver().query(source,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                        name = cursor.getString(0).replaceFirst("(?i)\\.m3u8?$", "");
                    }
                }
                String saved = service.localLibrary.importPlaylist(name, parsed.ids);
                runOnUiThread(() -> {
                    if (isDestroyed() || playback != service) return;
                    localTab = 2;
                    localPlaylist = saved;
                    localGroup = null;
                    service.libraryChanged();
                    toast("已导入 " + parsed.ids.size() + " 首"
                            + (parsed.missing == 0 ? "" : "，跳过 " + parsed.missing + " 首未匹配"));
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("歌单导入失败 · " + readable(error)); });
            }
        });
    }

    private void exportM3u(Uri destination, String playlist) {
        if (playback == null || playlist == null) return;
        List<TidalClient.Track> tracks = playback.localLibrary.search("", playlist);
        worker.submit(() -> {
            try {
                LocalMusicFiles.exportM3u(getContentResolver(), destination, tracks);
                runOnUiThread(() -> { if (!isDestroyed()) toast("已导出 " + tracks.size() + " 首歌曲"); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("歌单导出失败 · " + readable(error)); });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_PERMISSION) {
            org.json.JSONObject offer = installManifest;
            installManifest = null;
            if (offer == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
                downloadUpdate(offer);
            } else {
                updateState(false, "安装权限尚未开启，可以稍后再更新。");
            }
            return;
        }
        if (request == EXPORT_M3U) {
            String playlist = exportPlaylist;
            exportPlaylist = null;
            if (result == RESULT_OK && data != null && data.getData() != null) exportM3u(data.getData(), playlist);
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
            downloadChoices(pendingDownloadTitle, pendingDownloadQuality, directory, callback);
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
            importM3u(data.getData());
            return;
        }
        if (request == IMPORT_AUDIO) {
            importAudio(data);
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
                scanDirectory(tree, true);
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
                    finishSignIn(session, saved, "已导入 TIDAL 会话");
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

    private void clearFavorites() {
        favoriteBusy = false;
        favoriteIds.clear();
    }

    private void buildMini() {
        mini = column();
        mini.setBackground(shapeWithStroke(RAISED, 0x1affffff, 1, 12));
        mini.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(8), 0, dp(8), dp(2));
        root.addView(mini, params);

        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(2), 0);
        bar.setMinimumHeight(dp(44));
        mini.addView(bar);

        miniArt = art(32);
        bar.addView(miniArt);
        LinearLayout info = column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(8), 0, 0, 0);
        miniTitle = single("", 13, INK, true);
        miniArtist = single("", 10, MUTED, false);
        info.addView(miniTitle);
        info.addView(miniArtist);
        info.setContentDescription("正在播放，左右滑动切歌");
        info.setOnClickListener(v -> open(Page.PLAYER));
        miniArt.setOnClickListener(v -> open(Page.PLAYER));
        bar.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams switchLayout = new LinearLayout.LayoutParams(-2, -2);
        switchLayout.setMarginStart(dp(4));
        bar.addView(playbackModeSwitch(false), switchLayout);

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                open(Page.PLAYER);
                return true;
            }
            @Override public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (start == null || end == null) return false;
                float dx = end.getX() - start.getX();
                if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(end.getY() - start.getY())
                        && Math.abs(velocityX) > dp(180)) {
                    control(dx < 0 ? "next" : "previous");
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener swipe = (view, event) -> {
            gestures.onTouchEvent(event);
            return true;
        };
        info.setOnTouchListener(swipe);
        miniArt.setOnTouchListener(swipe);

        if (devices != null) {
            bar.addView(glyph("devices", remoteMode() ? ACCENT : MUTED, 18, "切换播放设备", this::showDevicePicker),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        bar.addView(glyph("queue", MUTED, 18, "查看播放队列", () -> open(Page.QUEUE)),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        miniPlay = glyph("play", INK, 18, "播放", this::toggle);
        miniPlay.setBackground(shape(SURFACE, 20));
        bar.addView(miniPlay, new LinearLayout.LayoutParams(dp(40), dp(40)));

        miniTrack = new android.widget.FrameLayout(this);
        miniTrack.setBackgroundColor(0x22ffffff);
        miniProgress = new View(this);
        miniProgress.setBackgroundColor(ACCENT);
        miniTrack.addView(miniProgress, new android.widget.FrameLayout.LayoutParams(0, -1));
        mini.addView(miniTrack, new LinearLayout.LayoutParams(-1, dp(2)));
    }

    private void buildNowPlaying() {
        // 1. Top row: Down button (32dp) + 2-line header + More button (32dp)
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(2), dp(12), dp(2));
        top.addView(iconButton("down", "收起为迷你播放", () -> showPlaybackMode(false)),
                new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout headerCol = column();
        headerCol.setGravity(Gravity.CENTER_HORIZONTAL);
        headerCol.addView(playbackModeSwitch(true));
        playerHeading = text("正在播放", 13, INK, true);
        playerHeading.setGravity(Gravity.CENTER);
        playerHeading.setSingleLine();
        playerHeading.setEllipsize(TextUtils.TruncateAt.END);
        headerCol.addView(playerHeading);

        playerSubHeading = text("", 10, MUTED, false);
        playerSubHeading.setGravity(Gravity.CENTER);
        playerSubHeading.setSingleLine();
        playerSubHeading.setEllipsize(TextUtils.TruncateAt.END);
        headerCol.addView(playerSubHeading);
        top.addView(headerCol, new LinearLayout.LayoutParams(0, -2, 1));

        top.addView(iconButton("more", "歌曲选项", () -> {
            if (remoteMode()) showDevicePicker();
            else if (playback != null && playback.current() != null) trackMenu(playback.current(), false);
        }), new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(top);

        // 2. Stage FrameLayout with weight = 1f (Dynamically sized cover, no scroll overflow!)
        android.widget.FrameLayout artStage = new android.widget.FrameLayout(this);
        GradientDrawable stageBackdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x141822, BG});
        artStage.setBackground(stageBackdrop);

        android.widget.FrameLayout artFrame = new android.widget.FrameLayout(this);
        cover = art(0);
        cover.setImageResource(R.drawable.listening_room);
        cover.setContentDescription("显示歌词");
        artFrame.addView(cover, new android.widget.FrameLayout.LayoutParams(-1, -1));

        lyricsPane = new android.widget.FrameLayout(this);
        lyricsPane.setBackgroundColor(BG);
        lyricsPane.setVisibility(View.GONE);

        artStage.addView(artFrame, new android.widget.FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER));
        artStage.addView(lyricsPane, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Square cover fills the stage; lyrics use the stage's full width above the controls.
        artStage.addOnLayoutChangeListener((v, left, topPos, right, bottom, oldL, oldT, oldR, oldB) -> {
            int w = right - left - dp(24);
            int h = bottom - topPos - dp(8);
            int size = Math.max(dp(120), Math.min(w, h));
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) artFrame.getLayoutParams();
            if (lp.width != size || lp.height != size) {
                lp.width = size;
                lp.height = size;
                lp.gravity = Gravity.CENTER;
                artFrame.setLayoutParams(lp);
            }
        });

        // Gestures on stage: swipe down to dismiss, swipe left/right for next/prev, tap for lyrics
        GestureDetector stageGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleLyrics();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (dy > dp(50) && Math.abs(velocityY) > dp(200) && Math.abs(dy) > Math.abs(dx)) {
                    showPlaybackMode(false);
                    return true;
                }
                if (Math.abs(dx) > dp(50) && Math.abs(velocityX) > dp(200)) {
                    if (dx > 0) control("previous");
                    else control("next");
                    return true;
                }
                return false;
            }
        });
        artStage.setOnTouchListener((v, event) -> stageGesture.onTouchEvent(event));
        root.addView(artStage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 3. Lower Controls Container
        LinearLayout body = column();
        body.setPadding(dp(16), dp(2), dp(16), dp(8));

        // Title row: Track Title (17sp), Subtitle (12sp), Favorite Heart (32dp)
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(4), 0, 0);

        LinearLayout song = column();
        title = single("", 17, INK, true);
        subtitle = single("", 12, ACCENT, false);
        subtitle.setCompoundDrawablePadding(dp(4));
        subtitle.setOnClickListener(v -> {
            if (hasPlayingArtist()) openPlayingArtist();
        });
        playerAlbum = single("", 11, ACCENT, false);
        playerAlbum.setVisibility(View.GONE);
        playerAlbum.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                new PlayerIcon("forward", ACCENT, dp(12)), null);
        playerAlbum.setCompoundDrawablePadding(dp(4));
        playerAlbum.setOnClickListener(v -> openPlayingAlbum());

        song.addView(title);
        song.addView(subtitle);
        song.addView(playerAlbum);
        titleRow.addView(song, new LinearLayout.LayoutParams(0, -2, 1));

        favorite = glyph("heart", MUTED, 18, "收藏歌曲", () -> {
            if (playback != null && playback.current() != null) {
                TidalClient.Track cur = playback.current();
                if (cur.isLocal() || cur.isNetease()) {
                    playback.localLibrary.toggleLike(cur);
                    playback.libraryChanged();
                    updatePlayer();
                } else changeFavorite(cur, !favoriteIds.contains(cur.id));
            }
        });
        titleRow.addView(favorite, new LinearLayout.LayoutParams(dp(32), dp(32)));
        body.addView(titleRow);

        // 4. Hi-Fi Spec Capsule (Single-line Frosted Glass Capsule)
        LinearLayout specCard = row();
        specCard.setGravity(Gravity.CENTER_VERTICAL);
        specCard.setBackground(shapeWithStroke(0x18ffffff, 0x1affffff, 1, 10));
        specCard.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams specLp = new LinearLayout.LayoutParams(-1, dp(32));
        specLp.topMargin = dp(6);
        specLp.bottomMargin = dp(4);
        specCard.setLayoutParams(specLp);
        specCard.setOnClickListener(v -> {
            if (playback != null && playback.current() != null && (playback.current().isLocal() || playback.current().isWebdav() || playback.current().isSmb())) {
                showPlayerDetails();
            } else chooseQuality();
        });

        playerSpecTag = text("FLAC", 9, GOLD, true);
        playerSpecTag.setPadding(dp(4), dp(1), dp(4), dp(1));
        playerSpecTag.setBackground(shapeWithStroke(0x22d4a359, GOLD, 1, 4));
        specCard.addView(playerSpecTag);

        playerSpecFormat = text("FLAC · 24-bit / 192.0 kHz", 10, INK, true);
        playerSpecFormat.setPadding(dp(6), 0, 0, 0);
        playerSpecFormat.setSingleLine();
        playerSpecFormat.setEllipsize(TextUtils.TruncateAt.END);
        specCard.addView(playerSpecFormat, new LinearLayout.LayoutParams(0, -2, 1));

        playerSpecEngine = text("硬件直通", 9, MUTED, false);
        playerSpecEngine.setPadding(dp(4), 0, 0, 0);
        specCard.addView(playerSpecEngine);

        playerSpecBitrate = new TextView(this);
        playerSpecPort = new TextView(this);
        playerQuality = new Button(this);
        playerQuality.setVisibility(View.GONE);
        playerDetails = new Button(this);
        playerDetails.setVisibility(View.GONE);
        playerComments = new Button(this);
        playerComments.setVisibility(View.GONE);

        body.addView(specCard);

        // 5. Seekbar & Timing
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(GOLD));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x33ffffff));
        seek.setThumbTintList(ColorStateList.valueOf(GOLD));
        seek.setContentDescription("播放进度");
        seek.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams seekLayout = new LinearLayout.LayoutParams(-1, dp(24));
        seekLayout.topMargin = dp(4);
        body.addView(seek, seekLayout);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && !dragging && (remoteMode()
                        || playback != null && playback.player.isCurrentMediaItemSeekable())) {
                    seekTo(bar);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekTo(bar);
                dragging = false;
            }
        });

        LinearLayout timing = row();
        timing.setGravity(Gravity.CENTER_VERTICAL);
        timing.setPadding(dp(4), 0, dp(4), 0);
        time = text("0:00", 10, MUTED, false);
        timing.addView(time, new LinearLayout.LayoutParams(-2, -2));

        playerNextTrack = text("", 9, MUTED, false);
        playerNextTrack.setGravity(Gravity.CENTER);
        playerNextTrack.setSingleLine();
        playerNextTrack.setEllipsize(TextUtils.TruncateAt.END);
        playerNextTrack.setOnClickListener(v -> showQueueSheet());
        timing.addView(playerNextTrack, new LinearLayout.LayoutParams(0, -2, 1));

        total = text("0:00", 10, MUTED, false);
        total.setGravity(Gravity.END);
        timing.addView(total, new LinearLayout.LayoutParams(-2, -2));
        body.addView(timing);

        // 6. Transport Controls (Height ~48dp)
        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER);
        body.addView(transport, new LinearLayout.LayoutParams(-1, dp(48)));

        shuffle = glyph("shuffle", MUTED, 15, "随机播放", () -> control("shuffle"));
        repeat = glyph("repeat", MUTED, 15, "循环模式", () -> control("repeat"));
        play = glyph("play", BG, 18, "播放", this::toggle);
        play.setBackground(shape(GOLD, 24));

        transport.addView(shuffle, new LinearLayout.LayoutParams(0, dp(32), 1));
        transport.addView(glyph("previous", INK, 18, "上一首", () -> control("previous")),
                new LinearLayout.LayoutParams(0, dp(36), 1));
        transport.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        transport.addView(glyph("next", INK, 18, "下一首", () -> control("next")),
                new LinearLayout.LayoutParams(0, dp(36), 1));
        transport.addView(repeat, new LinearLayout.LayoutParams(0, dp(32), 1));

        // 7. Extras Bottom Row (Height ~32dp, 3 refined action capsules)
        LinearLayout extras = row();
        extras.setGravity(Gravity.CENTER);
        extras.setPadding(0, dp(4), 0, 0);

        playerLyrics = pillBtn("歌词", "lyrics", this::toggleLyrics);
        playerDevices = pillBtn(remoteMode() ? devices.targetName() : "输出", "devices", this::showDevicePicker);
        playerQueueBtn = pillBtn("队列", "queue", this::showQueueSheet);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp(32), 1);
        btnLp.setMargins(dp(3), 0, dp(3), 0);
        extras.addView(playerLyrics, btnLp);
        extras.addView(playerDevices, btnLp);
        extras.addView(playerQueueBtn, btnLp);
        body.addView(extras);

        message = text("", 11, MUTED, false);
        message.setGravity(Gravity.CENTER);
        body.addView(message);
        root.addView(body);
    }

    /** Cover-sized player, or the bottom bar. The same control is on both. */
    private LinearLayout playbackModeSwitch(boolean immersive) {
        LinearLayout pill = row();
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(2), dp(2), dp(2), dp(2));
        pill.setBackground(shape(SURFACE, 14));
        pill.setContentDescription(immersive ? "当前是沉浸播放" : "当前是迷你播放");
        pill.addView(playbackModeChip("沉浸", immersive, "切换到沉浸播放", () -> showPlaybackMode(true)));
        pill.addView(playbackModeChip("迷你", !immersive, "切换到迷你播放", () -> showPlaybackMode(false)));
        return pill;
    }

    private Button playbackModeChip(String label, boolean selected, String description, Runnable choose) {
        Button chip = action(label, description, choose);
        chip.setTextSize(11);
        chip.setTextColor(selected ? BG : MUTED);
        chip.setBackground(selected ? shape(GOLD, 12) : null);
        chip.setPadding(dp(8), 0, dp(8), 0);
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setMinHeight(0);
        chip.setMinimumHeight(0);
        chip.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(24)));
        return chip;
    }

    private void showPlaybackMode(boolean immersive) {
        if (immersive) {
            if (page != Page.PLAYER) open(Page.PLAYER);
            return;
        }
        if (page == Page.PLAYER) goBack();
    }

    private void showPlayerDetails() {
        if (remoteMode()) {
            showRemoteDetails();
            return;
        }
        PlaybackService service = playback;
        TidalClient.Track track = service == null ? null : service.current();
        if (track == null) return;
        androidx.media3.common.Format format = service.loading ? null : service.player.getAudioFormat();
        int rate = format != null && format.sampleRate > 0 ? format.sampleRate : service.sourceSampleRate;
        int depth = service.sourceBitDepth > 0 ? service.sourceBitDepth : audioBitDepth(format);
        int bitrate = format != null && format.bitrate > 0 ? format.bitrate : service.sourceBitrate;
        int channels = format == null ? 0 : format.channelCount;
        LinearLayout body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(18));
        body.addView(text(track.title, 17, INK, true));
        String sourceName = track.isLocal() ? "本地文件"
                : track.isWebdav() ? "WebDAV"
                : track.isSmb() ? "SMB"
                : track.isNetease() ? "网易云" : "TIDAL";
        body.addView(text(sourceName
                + (service.actualQuality.isEmpty() || track.isLocal() || track.isWebdav() || track.isSmb() ? "" : " · " + qualityLabel()), 13, MUTED, false));
        if (!track.isLocal() && !track.isWebdav() && !track.isSmb()) {
            body.addView(text("已选音质 · " + selectedQualityName(), 13, MUTED, false));
        }
        sourceRows(body, sourceName,
                track.isLocal() ? LocalMusicFiles.displayPath(track.localUri)
                : track.isWebdav() ? track.webdavUrl
                : track.isSmb() ? track.smbUrl : "");
        boolean isDsd = false;
        String dsdLabel = "";
        String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
        if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
            isDsd = true;
            dsdLabel = rate >= 11289600 ? "DSD256" : rate >= 5644800 ? "DSD128" : rate >= 2822400 ? "DSD64" : "DSD";
        }
        audioInfoRow(body, "音源采样率", isDsd ? (rate > 0 ? String.format(Locale.ROOT, "%.2f MHz", rate / 1000000.0) : "5.64 MHz") : audioRate(rate),
                "音源位深", isDsd ? "1 bit (Direct Stream)" : audioValue(depth, " bit"));
        if (track.needsTidal() && service.quality == 3 && !service.loading
                && !service.actualQuality.isEmpty() && !"HI_RES_LOSSLESS".equals(service.actualQuality) && depth <= 16) {
            TextView note = text(service.client != null && service.client.hasHiResSession()
                    ? "已选 Max，本次实际音质为 " + qualityLabel() + "。"
                    : "当前没有可用的 Max（HiRes FLAC）会话。请在「服务 → 登录 TIDAL」用设备链接登录。", 13, MUTED, false);
            note.setPadding(0, dp(6), 0, 0);
            body.addView(note);
        }
        audioInfoRow(body, "比特率", isDsd && bitrate == 0 ? "5645 kbps" : audioValue(bitrate / 1000, " kbps"), "声道",
                channels == 2 ? "立体声 · 2" : channels == 1 ? "单声道 · 1" : audioValue(channels, " 声道"));
        body.addView(text("音源格式 · " + (isDsd ? "DSD (" + dsdLabel + ")" : audioCodec(format)), 14, INK, true));
        androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig output = service.outputConfig;
        TextView heading = text("Android 输出", 15, INK, true);
        heading.setPadding(0, dp(18), 0, dp(4));
        body.addView(heading);
        body.addView(text("输出设备 · " + audioDevice(), 13, MUTED, false));
        body.addView(text("音轨采样率 · " + (output == null ? "未知" : audioRate(output.sampleRate)), 13, MUTED, false));
        body.addView(text("输出模式 · " + (output == null ? "未知" : output.offload ? "硬件卸载" : "Android 音轨"), 13, MUTED, false));
        body.addView(text("系统重采样 · 未知", 13, MUTED, false));
        float speed = service.player.getPlaybackParameters().speed;
        if (speed != 1f) body.addView(text("播放速度 · " + speed + "×", 13, MUTED, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("播放信息")
                .setView(scroll).setPositiveButton("关闭", null);
        if (!track.isLocal() && !track.isWebdav() && !track.isSmb()) dialog.setNeutralButton("选择音质", (view, choice) -> chooseQuality());
        dialog.show();
    }

    /** 来源 and, for local music, the file path (selectable so it can be copied). */
    private void sourceRows(LinearLayout body, String source, String path) {
        body.addView(text("来源 · " + source, 13, MUTED, false));
        if (path.isEmpty()) return;
        TextView file = text("文件路径 · " + path, 13, MUTED, false);
        file.setTextIsSelectable(true);
        body.addView(file);
    }

    /** 播放信息 of the controlled device, from what it reports. */
    private void showRemoteDetails() {
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track track = state == null ? null : state.current();
        if (track == null) return;
        String name = devices.targetName();
        LinearLayout body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(18));
        body.addView(text(track.title, 17, INK, true));
        if (!track.artist.isEmpty()) body.addView(text(track.artist, 13, MUTED, false));
        body.addView(text("播放设备 · " + name, 13, MUTED, false));
        boolean local = "本地音乐".equals(track.source);
        sourceRows(body, local ? "本地文件" : track.source, local ? state.sourcePath : "");
        if (!state.quality.isEmpty()) body.addView(text("实际音质 · " + state.quality, 13, MUTED, false));
        int requested = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
        if ("网易云".equals(track.source) && !state.requestedNetease.isEmpty()) {
            body.addView(text("已选音质 · " + NeteaseClient.levelName(state.requestedNetease), 13, MUTED, false));
        } else if (!local && requested >= 0) {
            body.addView(text("已选音质 · " + PlaybackService.QUALITY_NAMES[requested], 13, MUTED, false));
        }
        if (state.volume >= 0) body.addView(text("音量 · " + state.volume, 13, MUTED, false));
        if (!state.error.isEmpty()) body.addView(text(state.error, 13, 0xffff9aa6, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("播放信息 · " + name)
                .setView(scroll).setPositiveButton("关闭", null);
        if (!local && requested >= 0) dialog.setNeutralButton("选择音质", (view, choice) -> chooseQuality());
        dialog.show();
    }

    /**
     * Lyrics of the controlled device's track, following its position. TIDAL lyrics come from this
     * phone's own TIDAL login; other tracks are asked from the device, which can read its own files.
     */
    private void showRemoteLyrics() {
        RemoteDevices.State state = devices.state;
        RemoteDevices.Track track = state == null ? null : state.current();
        if (track == null) return;
        TidalClient.Track shown;
        try {
            shown = new TidalClient.Track(new JSONObject().put("id", track.id).put("title", track.title));
        } catch (Exception impossible) {
            return;
        }
        LyricsDialog view = inlineLyrics(shown, millis -> {
            try {
                if (remoteMode()) devices.send(new JSONObject().put("op", "seek").put("ms", millis));
            } catch (org.json.JSONException ignored) {}
        });
        TidalClient api = tidalApi();
        if (track.tidalId > 0 && api != null) {
            long id = track.tidalId;
            worker.submit(() -> {
                try {
                    JSONObject data = api.lyrics(id);
                    runOnUiThread(() -> { if (lyrics == view) view.display(data); });
                } catch (Exception error) {
                    runOnUiThread(() -> { if (lyrics == view) view.unavailable(readable(error)); });
                }
            });
            return;
        }
        if (!remoteHasLibrary()) {
            view.unavailable(devices.targetName() + " 上的这首歌暂时无法读取歌词");
            return;
        }
        try {
            devices.query(new JSONObject().put("op", "library").put("action", "lyrics").put("id", track.id),
                    (result, error) -> {
                        if (lyrics != view) return;
                        if (!error.isEmpty()) {
                            view.unavailable(error);
                            return;
                        }
                        // The device sends synced or plain lyrics as "lyrics"; the dialog parses either.
                        String words = result.optString("lyrics");
                        try {
                            view.display(new JSONObject().put("subtitles", words).put("lyrics", words));
                        } catch (org.json.JSONException ignored) {}
                    });
        } catch (org.json.JSONException ignored) {}
    }

    private void audioInfoRow(LinearLayout parent, String left, String leftValue, String right, String rightValue) {
        LinearLayout row = row();
        row.setPadding(0, dp(8), 0, dp(4));
        row.addView(audioInfoTile(left, leftValue), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(8);
        row.addView(audioInfoTile(right, rightValue), second);
        parent.addView(row);
    }

    private LinearLayout audioInfoTile(String label, String value) {
        LinearLayout tile = column();
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        tile.setBackground(shape(SURFACE, 9));
        tile.addView(text(label, 12, MUTED, false));
        tile.addView(text(value, 16, INK, true));
        return tile;
    }

    private String audioDevice() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build();
                List<android.media.AudioDeviceInfo> routes = getSystemService(android.media.AudioManager.class)
                        .getAudioDevicesForAttributes(attributes);
                if (!routes.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (android.media.AudioDeviceInfo device : routes) {
                        String name = device.getProductName().toString();
                        if (!name.isEmpty() && !names.contains(name)) names.add(name);
                    }
                    if (!names.isEmpty()) return TextUtils.join("、", names);
                }
            } catch (RuntimeException ignored) {}
        }
        return "未知";
    }

    private static String audioRate(int rate) {
        return rate > 0 ? String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0) : "未知";
    }

    private static String audioValue(int value, String unit) {
        return value > 0 ? value + unit : "未知";
    }

    private static String audioCodec(androidx.media3.common.Format format) {
        if (format == null || format.sampleMimeType == null) return "未知";
        switch (format.sampleMimeType) {
            case "audio/flac": return "FLAC";
            case "audio/raw": return "PCM";
            case "audio/mpeg": return "MP3";
            case "audio/mp4a-latm": return "AAC";
            case "audio/opus": return "Opus";
            case "audio/vorbis": return "Vorbis";
            default: return format.sampleMimeType;
        }
    }

    private static int audioBitDepth(androidx.media3.common.Format format) {
        if (format == null) return 0;
        switch (format.pcmEncoding) {
            case androidx.media3.common.C.ENCODING_PCM_8BIT: return 8;
            case androidx.media3.common.C.ENCODING_PCM_16BIT: return 16;
            case androidx.media3.common.C.ENCODING_PCM_24BIT: return 24;
            case androidx.media3.common.C.ENCODING_PCM_32BIT: return 32;
            default: break;
        }
        if ("audio/flac".equals(format.sampleMimeType) && !format.initializationData.isEmpty()) {
            byte[] data = format.initializationData.get(0);
            if (data.length >= 42 && data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C'
                    && (data[4] & 0x7f) == 0) {
                return (((data[20] & 1) << 4) | ((data[21] & 0xf0) >> 4)) + 1;
            }
        }
        return 0;
    }

    private void seekTo(SeekBar bar) {
        if (remoteMode()) {
            RemoteDevices.State state = devices.state;
            if (state == null || !state.seekable || state.durationMs <= 0) return;
            try {
                devices.send(new JSONObject().put("op", "seek").put("ms", state.durationMs * bar.getProgress() / bar.getMax()));
            } catch (org.json.JSONException ignored) {}
            return;
        }
        if (playback == null || playback.loading || !playback.player.isCurrentMediaItemSeekable()) return;
        long duration = playback.player.getDuration();
        if (duration > 0) playback.player.seekTo(duration * bar.getProgress() / bar.getMax());
    }

    private void toggle() {
        control("toggle");
    }

    /** Quality picker for the current track: 网易云's levels for NetEase tracks, TIDAL's otherwise. */
    private void chooseQuality() {
        if (remoteMode()) {
            RemoteDevices.State state = devices.state;
            if (state == null) return;
            RemoteDevices.Track current = state.current();
            if (current != null && "网易云".equals(current.source)) {
                int level = java.util.Arrays.asList(NeteaseClient.PLAY_LEVELS).indexOf(state.requestedNetease);
                new AlertDialog.Builder(this).setTitle(devices.targetName() + " 的网易云音质")
                        .setSingleChoiceItems(NeteaseClient.PLAY_CHOICES, level, (dialog, choice) -> {
                            dialog.dismiss();
                            try {
                                devices.send(new JSONObject().put("op", "quality")
                                        .put("value", "netease:" + NeteaseClient.PLAY_LEVELS[choice]));
                            } catch (org.json.JSONException ignored) {}
                        }).setNegativeButton("取消", null).show();
                return;
            }
            int selected = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
            new AlertDialog.Builder(this).setTitle(devices.targetName() + " 的播放音质")
                    .setSingleChoiceItems(PlaybackService.QUALITY_CHOICES, selected, (dialog, choice) -> {
                        dialog.dismiss();
                        try {
                            devices.send(new JSONObject().put("op", "quality").put("value", PlaybackService.QUALITIES[choice]));
                        } catch (org.json.JSONException ignored) {}
                    }).setNegativeButton("取消", null).show();
            return;
        }
        if (playback == null) return;
        TidalClient.Track track = playback.current();
        if (track != null && track.isNetease()) chooseNeteaseQuality();
        else chooseTidalQuality();
    }

    private void chooseTidalQuality() {
        if (playback == null) return;
        new AlertDialog.Builder(this).setTitle("TIDAL 播放音质")
                .setSingleChoiceItems(PlaybackService.QUALITY_CHOICES, playback.quality, (dialog, selected) -> {
                    dialog.dismiss();
                    playback.setQuality(selected);
                    if (page == Page.SETTINGS) render();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseNeteaseQuality() {
        if (playback == null) return;
        int selected = java.util.Arrays.asList(NeteaseClient.PLAY_LEVELS).indexOf(playback.neteaseLevel);
        new AlertDialog.Builder(this).setTitle("网易云播放音质")
                .setSingleChoiceItems(NeteaseClient.PLAY_CHOICES, selected, (dialog, choice) -> {
                    dialog.dismiss();
                    playback.setNeteaseLevel(NeteaseClient.PLAY_LEVELS[choice]);
                    if (page == Page.SETTINGS) render();
                }).setNegativeButton("取消", null).show();
    }

    /** The selected quality for the current track, by its official name. */
    private String selectedQualityName() {
        TidalClient.Track track = playback.current();
        return track != null && track.isNetease() ? NeteaseClient.levelName(playback.neteaseLevel)
                : PlaybackService.QUALITY_NAMES[playback.quality];
    }

    private String qualityLabel() {
        if (playback == null) return PlaybackService.QUALITY_NAMES[0];
        for (int i = 0; i < PlaybackService.QUALITIES.length; i++) {
            if (PlaybackService.QUALITIES[i].equals(playback.actualQuality)) return PlaybackService.QUALITY_NAMES[i];
        }
        if (!playback.actualQuality.isEmpty()) return playback.actualQuality;
        return selectedQualityName();
    }

    private void updatePlayer() {
        if (playback == null || isDestroyed()) return;
        if (visibleLocalRevision != playback.localRevision
                && (page == Page.HOME || page == Page.LOCAL || (page == Page.SEARCH && searchSource == SearchSource.LOCAL))) {
            render();
            return;
        }
        if (visibleAccount != tidalApi()) {
            visibleAccount = tidalApi();
            clearFavorites();
            if ((page == Page.SEARCH && searchSource != SearchSource.LOCAL) || page == Page.LIBRARY || page == Page.COLLECTION) load(false);
            else render();
            return;
        }
        if ((page == Page.CONNECTIONS && !visibleRemoteLink.equals(playback.remoteLink()))
                || (page == Page.QUEUE && (visibleQueueIndex != playback.index
                || visibleQueueRevision != playback.queueRevision))) {
            render();
            return;
        }
        if (remoteMode()) {
            updateRemotePlayer();
            return;
        }
        TidalClient.Track current = playback.current();
        boolean playing = playback.loading ? playback.intendedPlay
                : playback.player.getPlayWhenReady() && playback.player.getPlaybackState() != Player.STATE_ENDED;
        if (mini != null) {
            applyMiniVisibility();
            miniTitle.setText(current == null ? "队列已就绪" : displayTitle(current));
            miniArtist.setText(!playback.error.isEmpty() ? playback.error : current == null ? "点击播放" : current.artist);
            miniArtist.setVisibility(miniArtist.getText().length() == 0 ? View.GONE : View.VISIBLE);
            artwork.load(miniArt, current, 160);
            playIcon(miniPlay, playing);
        }
        if (seek == null) return;
        if (visibleTrack != (current == null ? Long.MIN_VALUE : current.id)) {
            visibleTrack = current == null ? Long.MIN_VALUE : current.id;
            artwork.load(cover, current, 640);
        }
        title.setText(current == null ? "尚未播放" : displayTitle(current));
        String byline = current == null ? "" : !current.artist.isEmpty() ? current.artist
                : !current.album.isEmpty() ? current.album : current.isLocal() ? "本地音频" : "";
        subtitle.setText(byline);
        showPlayingAlbum(current == null ? "" : current.album, current != null && !current.artist.isEmpty());
        boolean local = current != null && current.isLocal();
        boolean webdav = current != null && current.isWebdav();
        boolean smb = current != null && current.isSmb();

        if (current != null) {
            // 1. Top Bar Header & Subheader
            int qIndex = playback.index;
            int qTotal = playback.queue.size();

            // 3. Hi-Fi Spec Capsule - Authentic Audio Spec Resolution
            androidx.media3.common.Format format = playback.loading ? null : playback.player.getAudioFormat();
            int rate = format != null && format.sampleRate > 0 ? format.sampleRate : playback.sourceSampleRate;
            int depth = playback.sourceBitDepth > 0 ? playback.sourceBitDepth : audioBitDepth(format);
            int bitrate = format != null && format.bitrate > 0 ? format.bitrate : playback.sourceBitrate;
            int channels = format == null ? 0 : format.channelCount;
            String codec = audioCodec(format);

            boolean isDsd = false;
            String badgeText;
            boolean isHiRes = false;

            if (current.isLocal() || current.isWebdav() || current.isSmb()) {
                String filename = current.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
                if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
                    isDsd = true;
                    isHiRes = true;
                    if (rate >= 11289600) badgeText = "DSD256";
                    else if (rate >= 5644800) badgeText = "DSD128";
                    else if (rate >= 2822400) badgeText = "DSD64";
                    else badgeText = "DSD";
                } else if (rate >= 96000 || depth >= 24) {
                    isHiRes = true;
                    badgeText = "Hi-Res " + (rate > 0 ? (rate / 1000) + "k" : "24b");
                } else if (depth == 16) {
                    badgeText = "LOSSLESS";
                } else {
                    badgeText = codec.equals("未知") ? "FLAC" : codec;
                }
            } else if (current.isNetease()) {
                String level = playback.neteaseLevel;
                if (rate > 48000 || depth > 16 || "hires".equalsIgnoreCase(level) || "jymaster".equalsIgnoreCase(level)) {
                    isHiRes = true;
                    badgeText = "超清母带";
                } else if ("sky".equalsIgnoreCase(level) || "jyeffect".equalsIgnoreCase(level)) {
                    isHiRes = true;
                    badgeText = "沉浸声";
                } else if ("lossless".equalsIgnoreCase(level) || depth == 16) {
                    badgeText = "无损品质";
                } else {
                    badgeText = "极高品质";
                }
            } else { // TIDAL
                String act = playback.actualQuality;
                if (rate > 48000 || depth > 16 || "HI_RES_LOSSLESS".equalsIgnoreCase(act) || "HI_RES".equalsIgnoreCase(act)) {
                    isHiRes = true;
                    badgeText = "Hi-Res MAX";
                } else if ("MQA".equalsIgnoreCase(act)) {
                    isHiRes = true;
                    badgeText = "MQA";
                } else if ("LOSSLESS".equalsIgnoreCase(act) || depth == 16) {
                    badgeText = "LOSSLESS";
                } else {
                    badgeText = "HIGH";
                }
            }

            String sourceName = current.isLocal() ? (isDsd ? "本地 DSD" : "本地音频")
                    : current.isWebdav() ? "WebDAV"
                    : current.isSmb() ? "SMB"
                    : current.isNetease() ? "网易云母带" : (isHiRes ? "TIDAL MAX" : "TIDAL");
            if (playerHeading != null) {
                playerHeading.setText(sourceName + (qTotal > 0 ? " · " + String.format(Locale.ROOT, "%02d / %02d", qIndex + 1, qTotal) : ""));
                playerHeading.setTextColor(GOLD);
            }
            if (playerSubHeading != null) {
                String year = current.payload.optString("year", "");
                String albumText = current.album;
                if (!year.isEmpty()) albumText += " (" + year + ")";
                int disc = current.payload.optInt("disc_number", 0);
                if (disc > 0) albumText += " · Disc " + disc;
                playerSubHeading.setText(albumText);
                playerSubHeading.setVisibility(albumText.isEmpty() ? View.GONE : View.VISIBLE);
            }

            // 2. Next track glance
            if (playerNextTrack != null) {
                TidalClient.Track next = (qIndex >= 0 && qIndex + 1 < playback.queue.size())
                        ? playback.queue.get(qIndex + 1) : null;
                if (next != null) {
                    playerNextTrack.setText("下一首: " + next.title + " · " + next.artist);
                    playerNextTrack.setVisibility(View.VISIBLE);
                } else {
                    playerNextTrack.setText("");
                    playerNextTrack.setVisibility(View.GONE);
                }
            }

            if (playerSpecTag != null) {
                playerSpecTag.setText(badgeText);
                playerSpecTag.setTextColor(isHiRes ? GOLD : INK);
                playerSpecTag.setBackground(shapeWithStroke(isHiRes ? 0x22d4a359 : 0x18ffffff, isHiRes ? GOLD : 0x33ffffff, 1, 4));
            }

            if (playerSpecFormat != null) {
                String formatStr;
                if (isDsd) {
                    formatStr = "DSD · 1-bit / " + (rate > 0 ? String.format(Locale.ROOT, "%.2f MHz", rate / 1000000.0) : "5.64 MHz");
                } else {
                    formatStr = (codec.equals("未知") ? "FLAC" : codec)
                            + (depth > 0 ? " · " + depth + "-bit" : "")
                            + (rate > 0 ? " / " + audioRate(rate) : "");
                }
                if (bitrate > 0) {
                    formatStr += " · " + (bitrate / 1000) + " kbps";
                } else if (isDsd) {
                    formatStr += " · 5645 kbps";
                }
                playerSpecFormat.setText(formatStr);
            }

            if (playerSpecBitrate != null) {
                playerSpecBitrate.setText(bitrate > 0 ? (bitrate / 1000) + " kbps" : "");
            }

            if (playerSpecEngine != null) {
                String engine = (playback.outputConfig != null && playback.outputConfig.offload)
                        ? "硬件直通 · Bit-Perfect"
                        : (channels == 2 ? "2.0 声道 · 立体声" : channels > 0 ? channels + " 声道" : "Direct PCM");
                playerSpecEngine.setText(engine);
            }

            if (playerSpecPort != null) {
                String dev = audioDevice();
                if (dev.equals("未知")) dev = "Shanling DAC";
                playerSpecPort.setText(dev + " ▾");
            }

            if (playerQueueBtn != null) {
                playerQueueBtn.setText("队列 (" + qTotal + ")");
            }
        }

        playerLyrics.setVisibility(current == null ? View.GONE : View.VISIBLE);
        playerComments.setVisibility(View.GONE);
        favorite.setVisibility(View.VISIBLE);
        favorite.setEnabled(current != null && !favoriteBusy);
        boolean liked = current != null && (current.isLocal() || current.isNetease()
                ? playback.localLibrary.liked(current.id) : favoriteIds.contains(current.id));
        favorite.setImageDrawable(new PlayerIcon(liked ? "heart-filled" : "heart", liked ? 0xffff7a90 : MUTED, dp(18)));
        favorite.setContentDescription(liked ? "取消收藏" : "收藏歌曲");
        playIcon(play, playing);
        shuffle.setImageDrawable(new PlayerIcon("shuffle", playback.shuffle ? ACCENT : MUTED, dp(15)));
        shuffle.setContentDescription(playback.shuffle ? "关闭随机播放" : "打开随机播放");
        repeat.setImageDrawable(new PlayerIcon(playback.repeat == Player.REPEAT_MODE_ONE ? "repeat-one" : "repeat",
                playback.repeat == Player.REPEAT_MODE_OFF ? MUTED : ACCENT, dp(15)));
        repeat.setContentDescription(playback.repeat == Player.REPEAT_MODE_ONE ? "单曲循环"
                : playback.repeat == Player.REPEAT_MODE_ALL ? "队列循环" : "关闭循环");
        message.setText(!playback.error.isEmpty() ? playback.error : playback.loading ? "正在获取音频流…"
                : playback.player.getPlaybackState() == Player.STATE_BUFFERING ? "正在缓冲…" : "");
        updatePosition();
    }

    private void applyMiniVisibility() {
        if (mini != null) mini.setVisibility(keyboardHidden
                && (remoteMode() || playback != null && !playback.queue.isEmpty()) ? View.VISIBLE : View.GONE);
    }

    private void playIcon(android.widget.ImageButton button, boolean playing) {
        int color = button == play ? BG : INK;
        int size = 18;
        button.setImageDrawable(new PlayerIcon(playing ? "pause" : "play", color, dp(size)));
        button.setContentDescription(playing ? "暂停" : "播放");
    }

    private void updatePosition() {
        if (remoteMode()) {
            RemoteDevices.State state = devices.state;
            long duration = state == null ? 0 : state.durationMs;
            long elapsed = state == null ? 0 : state.position();
            setMiniProgress(elapsed, duration);
            if (seek == null) return;
            seek.setEnabled(state != null && state.seekable && duration > 0);
            if (!dragging) seek.setProgress(duration == 0 ? 0 : (int) (elapsed * 1000 / duration));
            RemoteDevices.Track playing = state == null ? null : state.current();
            if (lyrics != null) lyrics.sync(playing == null ? Long.MIN_VALUE : playing.id, elapsed);
            String elapsedText = formatTime(elapsed);
            String totalText = formatTime(duration);
            if (!TextUtils.equals(time.getText(), elapsedText)) time.setText(elapsedText);
            if (!TextUtils.equals(total.getText(), totalText)) total.setText(totalText);
            return;
        }
        if (playback == null) return;
        long duration = playback.loading ? 0 : Math.max(0, playback.player.getDuration());
        long elapsed = playback.loading ? 0 : playback.player.getCurrentPosition();
        setMiniProgress(elapsed, duration);
        if (lyrics != null && playback.current() != null) {
            lyrics.sync(playback.current().id, playback.loading ? 0 : elapsed);
        }
        if (seek == null) return;
        seek.setEnabled(duration > 0 && playback.player.isCurrentMediaItemSeekable());
        if (!dragging) {
            seek.setProgress(duration == 0 ? 0 : (int) (elapsed * 1000 / duration));
        }
        String elapsedText = formatTime(elapsed);
        String totalText = formatTime(duration);
        if (!TextUtils.equals(time.getText(), elapsedText)) time.setText(elapsedText);
        if (!TextUtils.equals(total.getText(), totalText)) total.setText(totalText);
    }

    private void setMiniProgress(long elapsed, long duration) {
        if (miniProgress == null || miniTrack == null || miniTrack.getWidth() <= 0) return;
        int width = miniTrack.getWidth();
        int fill = duration <= 0 ? 0 : (int) Math.min(width, width * (elapsed / (double) duration));
        ViewGroup.LayoutParams layout = miniProgress.getLayoutParams();
        if (layout.width == fill) return;
        layout.width = fill;
        miniProgress.setLayoutParams(layout);
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String readable(Exception problem) {
        if (problem instanceof java.net.UnknownHostException || problem instanceof java.net.ConnectException
                || problem instanceof java.net.NoRouteToHostException) {
            return "无法连接服务器，请检查网络后重试";
        }
        if (problem instanceof java.net.SocketTimeoutException) return "连接超时，请稍后重试";
        if (problem instanceof javax.net.ssl.SSLException) return "安全连接失败，请检查网络或系统时间";
        return problem instanceof IOException && problem.getMessage() != null ? problem.getMessage()
                : "数据格式无效或网络不可用";
    }

    private static final java.util.regex.Pattern AUDIO_EXTENSION = java.util.regex.Pattern.compile(
            "(?i)\\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|dsf|dff|wma)$");

    /** Title for display; untagged local files show their name without the extension. */
    private static String displayTitle(TidalClient.Track track) {
        if (!track.isLocal()) return track.title;
        String stripped = AUDIO_EXTENSION.matcher(track.title).replaceFirst("");
        return stripped.isEmpty() ? track.title : stripped;
    }

    /** Borderless icon-only button. */
    private android.widget.ImageButton glyph(String icon, int color, int size, String description, Runnable choose) {
        android.widget.ImageButton button = new android.widget.ImageButton(this, null, android.R.attr.borderlessButtonStyle);
        button.setImageDrawable(new PlayerIcon(icon, color, dp(size)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(v -> choose.run());
        return button;
    }

    /** Tonal button: the standard style for secondary actions. */
    private Button secondary(String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setBackground(shapeWithStroke(RAISED, 0x1affffff, 1, 12));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private Button withIcon(Button button, String icon, int color) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon, color, dp(14)), null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        return button;
    }

    /** A row of buttons with consistent height and spacing. */
    private android.widget.HorizontalScrollView toolbar(Button... buttons) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = row();
        for (Button button : buttons) {
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(-2, dp(32));
            cell.setMarginEnd(dp(6));
            bar.addView(button, cell);
        }
        scroll.addView(bar);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(2);
        layout.bottomMargin = dp(8);
        scroll.setLayoutParams(layout);
        return scroll;
    }

    /** Sleek compact pill button for player bottom bar. */
    private Button pillBtn(String label, String icon, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(0xccffffff);
        button.setTextSize(11);
        button.setBackground(shape(0x18ffffff, 8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon, 0x99ffffff, dp(14)), null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    /** Bottom sheet queue drawer that pops up over the player without navigating away. */
    private void showQueueSheet() {
        if (playback == null || playback.queue.isEmpty()) {
            toast("播放队列为空");
            return;
        }
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        LinearLayout sheet = column();
        sheet.setBackground(shapeWithStroke(0xf012171f, 0x1affffff, 1, 16));
        sheet.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));
        TextView title = text("播放队列 (" + playback.queue.size() + ")", 14, INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button clearBtn = action("清空", "清空", () -> {
            TidalClient.Track cur = playback.current();
            if (cur != null) {
                playback.replaceQueue(java.util.Collections.singletonList(cur), 0, false);
            }
            dialog.dismiss();
            updatePlayer();
        });
        clearBtn.setTextColor(MUTED);
        clearBtn.setTextSize(12);
        header.addView(clearBtn);
        sheet.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column();
        int currentIdx = playback.index;
        View playingRow = null;
        for (int i = 0; i < playback.queue.size(); i++) {
            int idx = i;
            TidalClient.Track track = playback.queue.get(i);
            boolean isCurrent = i == currentIdx;

            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (isCurrent) item.setBackground(shapeWithStroke(0x22d4a359, 0x33d4a359, 1, 8));

            TextView num = text(isCurrent ? "▶" : String.format(Locale.ROOT, "%02d", i + 1), 11, isCurrent ? GOLD : MUTED, isCurrent);
            num.setGravity(Gravity.CENTER);
            item.addView(num, new LinearLayout.LayoutParams(dp(24), -2));

            LinearLayout info = column();
            info.setPadding(dp(8), 0, dp(8), 0);
            info.addView(single(track.title, 13, isCurrent ? GOLD : INK, isCurrent));
            info.addView(single(track.artist, 10, MUTED, false));
            item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            String tag = trackTag(track);
            TextView tagView = text(tag, 8, MUTED, false);
            tagView.setPadding(dp(4), dp(1), dp(4), dp(1));
            tagView.setBackground(shape(0x14ffffff, 4));
            item.addView(tagView);

            long dur = DownloadTasks.length(track);
            if (dur > 0) {
                TextView durView = text(formatTime(dur * 1000), 10, MUTED, false);
                durView.setPadding(dp(6), 0, 0, 0);
                item.addView(durView);
            }

            item.setOnClickListener(v -> {
                playback.playAt(idx, 0, true);
                dialog.dismiss();
                updatePlayer();
            });
            if (isCurrent) playingRow = item;
            list.addView(item);
        }
        scroll.addView(list);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        dialog.setContentView(sheet);
        if (playingRow != null) {
            View row = playingRow;
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    android.view.ViewTreeObserver observer = scroll.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    int y = row.getTop();
                    if (y > dp(8)) scroll.scrollTo(0, y - dp(8));
                }
            });
        }
        dialog.show();
    }

    static final class AudioBadge {
        final String text;
        final int textColor;
        final int bgColor;
        final int borderColor;

        AudioBadge(String text, int textColor, int bgColor, int borderColor) {
            this.text = text;
            this.textColor = textColor;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
        }
    }

    private AudioBadge audioSpecBadge(TidalClient.Track track) {
        if (track == null) return null;
        if (track.isLocal() || track.isWebdav() || track.isSmb()) {
            String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
            if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
                int rate = track.payload.optInt("sample_rate", 0);
                String label = rate >= 11289600 ? "DSD256" : rate >= 5644800 ? "DSD128" : rate >= 2822400 ? "DSD64" : "DSD";
                return new AudioBadge(label, GOLD, 0x22d4a359, GOLD);
            }
            if (filename.endsWith(".flac") || filename.endsWith(".ape") || filename.endsWith(".wav")
                    || track.payload.optBoolean("lossless", false)) {
                int rate = track.payload.optInt("sample_rate", 0);
                int depth = track.payload.optInt("bit_depth", 0);
                if (rate >= 96000 || depth >= 24) {
                    String rStr = rate > 0 ? (rate / 1000) + "k" : "24b";
                    return new AudioBadge("Hi-Res " + rStr, GOLD, 0x22d4a359, GOLD);
                }
                return new AudioBadge("FLAC", INK, 0x18ffffff, 0x33ffffff);
            }
            if (filename.endsWith(".mp3")) return new AudioBadge("MP3", MUTED, 0x10ffffff, 0x20ffffff);
            if (filename.endsWith(".m4a") || filename.endsWith(".aac")) return new AudioBadge("AAC", MUTED, 0x10ffffff, 0x20ffffff);
            return new AudioBadge("无损", INK, 0x18ffffff, 0x33ffffff);
        }
        if (track.isNetease()) {
            String level = track.payload.optString("level", "");
            if ("hires".equalsIgnoreCase(level) || "jymaster".equalsIgnoreCase(level)) {
                return new AudioBadge("超清母带", GOLD, 0x22d4a359, GOLD);
            }
            if ("sky".equalsIgnoreCase(level) || "jyeffect".equalsIgnoreCase(level)) {
                return new AudioBadge("沉浸声", GOLD, 0x22d4a359, GOLD);
            }
            if ("lossless".equalsIgnoreCase(level) || track.payload.optBoolean("lossless", true)) {
                return new AudioBadge("无损", INK, 0x18ffffff, 0x33ffffff);
            }
            return new AudioBadge("极高", MUTED, 0x10ffffff, 0x20ffffff);
        }
        // TIDAL
        String q = track.payload.optString("audioQuality", "");
        if ("HI_RES_LOSSLESS".equalsIgnoreCase(q) || "HI_RES".equalsIgnoreCase(q)) {
            return new AudioBadge("Hi-Res MAX", GOLD, 0x22d4a359, GOLD);
        }
        if ("MQA".equalsIgnoreCase(q)) {
            return new AudioBadge("MQA", GOLD, 0x22d4a359, GOLD);
        }
        if ("LOSSLESS".equalsIgnoreCase(q)) {
            return new AudioBadge("LOSSLESS", INK, 0x18ffffff, 0x33ffffff);
        }
        return new AudioBadge("HIGH", MUTED, 0x10ffffff, 0x20ffffff);
    }

    private static String trackTag(TidalClient.Track track) {
        if (track == null) return "";
        if (track.isLocal()) {
            String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
            if (filename.endsWith(".flac")) return "FLAC";
            if (filename.endsWith(".dsf") || filename.endsWith(".dff")) return "DSD";
            if (filename.endsWith(".wav")) return "WAV";
            if (filename.endsWith(".ape")) return "APE";
            if (filename.endsWith(".mp3")) return "MP3";
            if (filename.endsWith(".m4a")) return "AAC";
            return track.payload.optBoolean("lossless", false) ? "FLAC" : "本地";
        }
        if (track.isNetease()) return "网易云";
        if (track.isWebdav()) return "WebDAV";
        if (track.isSmb()) return "SMB";
        return "TIDAL";
    }

    /** Icon above a short label, for secondary player actions. */
    private Button labeledIcon(String icon, String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(MUTED);
        button.setTextSize(11);
        button.setCompoundDrawablesWithIntrinsicBounds(null, new PlayerIcon(icon, MUTED, dp(18)), null, null);
        button.setCompoundDrawablePadding(dp(3));
        button.setPadding(0, dp(6), 0, dp(4));
        return button;
    }

    /** Full-width list row with a chevron, for settings-style navigation and commands. */
    private Button menuRow(String label, String description, Runnable choose) {
        Button button = action(label, description, choose);
        boolean destructive = label.startsWith("退出") || label.startsWith("关闭");
        button.setTextColor(destructive ? 0xffff8f8f : INK);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setBackground(shape(0x14ffffff, 10));
        button.setPadding(dp(12), 0, dp(10), 0);
        if (!destructive) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                    new PlayerIcon("forward", MUTED, dp(14)), null);
        }
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(44));
        layout.bottomMargin = dp(5);
        button.setLayoutParams(layout);
        return button;
    }

    /** Square-ish shortcut tile with an icon over its label. */
    private Button tile(String icon, String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(INK);
        button.setTextSize(12);
        button.setBackground(shape(SURFACE, 12));
        button.setCompoundDrawablesWithIntrinsicBounds(null, new PlayerIcon(icon, ACCENT, dp(18)), null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(0, dp(8), 0, dp(6));
        return button;
    }

    private void empty(String heading, String detail) {
        section(heading);
        if (!detail.isEmpty()) content.addView(text(detail, 13, MUTED, false));
    }

    private void section(String value) {
        TextView heading = text(value, 16, INK, true);
        heading.setPadding(0, dp(10), 0, dp(6));
        content.addView(heading);
    }

    private LinearLayout entry(String title, String detail, String art, Runnable choose) {
        LinearLayout entry = row();
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(6), dp(4), dp(4), dp(4));
        entry.setBackground(shape(SURFACE, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        entry.setLayoutParams(params);
        ImageView image = art(38);
        artwork.load(image, art, 160, title);
        image.setOnClickListener(v -> choose.run());
        entry.addView(image);
        LinearLayout info = column();
        info.setPadding(dp(10), 0, dp(4), 0);
        info.addView(single(title, 14, INK, true));
        info.addView(single(detail, 11, MUTED, false));
        info.setOnClickListener(v -> choose.run());
        entry.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return entry;
    }

    private ImageView art(int size) {
        ImageView image = new ImageView(this);
        image.setBackground(shape(RAISED, 8));
        image.setClipToOutline(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (size > 0) image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return image;
    }

    private TextView single(String value, int size, int color, boolean strong) {
        TextView label = text(value, size, color, strong);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private TextView text(String value, int size, int color, boolean strong) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (strong) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private Button action(String value, String description, Runnable choose) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(value);
        button.setTextColor(ACCENT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(v -> choose.run());
        return button;
    }

    private Button iconButton(String icon, String description, Runnable choose) {
        Button button = action("", description, choose);
        PlayerIcon image = new PlayerIcon(icon, ACCENT, dp(16));
        button.setCompoundDrawablesWithIntrinsicBounds(null, image, null, null);
        button.setPadding(dp(4), dp(4), dp(4), dp(4));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private Button primary(String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(BG);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(shape(GOLD, 14));
        return button;
    }

    private Button pill(String label, boolean active, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(active ? GOLD : MUTED);
        button.setTextSize(11);
        if (active) button.setBackground(shapeWithStroke(0x22d4a359, GOLD, 1, 14));
        else button.setBackground(shape(SURFACE, 14));
        button.setPadding(dp(11), 0, dp(11), 0);
        return button;
    }

    private LinearLayout panel(String heading, String description) {
        return panel(content, heading, description);
    }

    private LinearLayout panel(LinearLayout parent, String heading, String description) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.setBackground(shape(SURFACE, 16));
        box.addView(text(heading, 20, INK, true));
        TextView detail = text(description, 14, MUTED, false);
        detail.setPadding(0, dp(5), 0, dp(12));
        box.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(box, params);
        return box;
    }

    private GradientDrawable shape(int color, int corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(corner));
        return background;
    }

    private GradientDrawable shapeWithStroke(int color, int strokeColor, int strokeWidthDp, int corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(corner));
        if (strokeWidthDp > 0) background.setStroke(dp(strokeWidthDp), strokeColor);
        return background;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
