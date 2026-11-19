package com.tidalgui.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public final class PlaybackService extends MediaSessionService {
    static final String LOCAL_BIND = "com.tidalgui.player.LOCAL";
    static final String[] QUALITIES = {"LOSSLESS", "HIGH", "LOW", "HI_RES_LOSSLESS"};
    /** TIDAL's names for {@link #QUALITIES}, as in the TIDAL apps; the API's 96 kbps level is "Low 96k". */
    static final String[] QUALITY_NAMES = {"High", "Low", "Low 96k", "Max"};
    /** Names with what each level delivers, for quality pickers. */
    static final String[] QUALITY_CHOICES = {"High · FLAC 16bit/44.1kHz", "Low · AAC 最高 320kbps",
            "Low 96k · AAC 96kbps", "Max · HiRes FLAC 最高 24bit/192kHz"};

    final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    final List<TidalClient.Track> queue = new ArrayList<>();
    final List<TidalClient.Track> history = new ArrayList<>();
    private SharedPreferences settings;
    private MediaSession session;
    private Future<?> request;
    private int generation;
    private long resumePosition;
    /** Quality for the next {@link #playAt} only, when recovering from a stream this phone cannot decode. */
    private String qualityOverride;
    /** Automatic retries left for the current track; reset whenever a track is chosen. */
    private int retriesLeft;
    private boolean retrying;
    private LanRemote remote;
    private android.content.SharedPreferences remoteSettings;
    private boolean remoteRequested;
    private String remoteIssue = "";
    private final Runnable restoreRemote = () -> {
        try {
            startRemote();
        } catch (IOException ignored) {
            // Retry when Wi-Fi returns or the previous listener releases the port.
        }
    };
    CentralRelay relay;
    long queueRevision;
    private DeviceLogin remoteLoginDevice;
    private DeviceLogin.Challenge remoteChallenge;
    private Future<?> remoteLoginRequest;
    private long loginRevision;
    private boolean remoteLoginPending;
    private String remoteLoginError = "";
    private final Runnable saveCheckpoint = new Runnable() {
        @Override public void run() {
            saveProgress();
            if (player.isPlaying()) main.postDelayed(this, 5000);
        }
    };
    ExoPlayer player;
    TidalClient client;
    LocalLibrary localLibrary;
    volatile boolean remoteFiles;
    /** Whether devices of the player account may use this device's TIDAL login and downloads. */
    volatile boolean lendTidal;
    volatile boolean remoteRoutedLan;
    long localRevision;
    int index = -1;
    int quality;
    /** 网易云 level for NetEase tracks, one of {@link NeteaseClient#PLAY_LEVELS}. */
    String neteaseLevel = "lossless";
    int repeat;
    boolean shuffle;
    boolean loading;
    boolean intendedPlay;
    String actualQuality = "";
    String resolution = "";
    int sourceBitDepth;
    int sourceSampleRate;
    int sourceBitrate;
    androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig outputConfig;
    String error = "";
    Runnable listener;

    @Override public void onCreate() {
        super.onCreate();
        settings = getSharedPreferences("tidal", MODE_PRIVATE);
        localLibrary = new LocalLibrary(this);
        DownloadTasks.get(this).attach(localLibrary);
        remoteSettings = getSharedPreferences("lan_remote", MODE_PRIVATE);
        remoteRequested = remoteSettings.getBoolean("enabled", false);
        remoteFiles = remoteSettings.getBoolean("files", false) && localLibrary.hasFolders();
        lendTidal = remoteSettings.getBoolean("lend_tidal", false);
        remoteRoutedLan = remoteSettings.getBoolean("routed", false);
        quality = Math.max(0, Math.min(QUALITIES.length - 1, settings.getInt("quality", 0)));
        String level = settings.getString("netease_level", "lossless");
        neteaseLevel = NeteaseClient.knownLevel(level) ? level : "lossless";
        repeat = settings.getInt("repeat", Player.REPEAT_MODE_OFF);
        shuffle = settings.getBoolean("shuffle", false);
        try {
            String saved = settings.getString("session", null);
            if (saved != null) {
                TidalClient.Session stored = new TidalClient.Session(saved);
                client = new TidalClient(stored, settings);
                if (stored.hasDirectClient()) remoteLoginDevice = new DeviceLogin(stored.clientId, stored.clientSecret);
            }
        } catch (Exception problem) {
            error = problem.getMessage();
        }
        String deviceId = settings.getString("tidal_device_id", "");
        String deviceSecret = settings.getString("tidal_device_secret", "");
        if (!deviceId.isEmpty() && !deviceSecret.isEmpty()) {
            try {
                remoteLoginDevice = new DeviceLogin(deviceId, deviceSecret);
            } catch (IOException ignored) {}
        }
        restoreState();
        if (client == null) retainNonTidalState();

        player = new ExoPlayer.Builder(this).build();
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onAudioTrackInitialized(EventTime time,
                    androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig config) {
                outputConfig = config;
                changed();
            }

            @Override public void onAudioTrackReleased(EventTime time,
                    androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig config) {
                if (outputConfig == config) outputConfig = null;
            }
        });
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) advance();
            }

            @Override public void onPlayerError(PlaybackException problem) {
                if (recover(problem)) return;
                error = "播放失败 · " + describe(problem);
                changed();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                main.removeCallbacks(saveCheckpoint);
                saveProgress();
                if (playing) {
                    TidalClient.Track track = current();
                    MediaItem item = player.getCurrentMediaItem();
                    if (!loading && track != null && item != null
                            && item.mediaId.equals(Long.toString(track.id))) remember(track);
                    main.postDelayed(saveCheckpoint, 5000);
                }
            }

            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                TidalClient.Track current = current();
                if (current == null || current.needsTidal() || loading) return;
                for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSelected(i)) continue;
                        androidx.media3.common.Format format = group.getTrackFormat(i);
                        if (format.sampleRate > 0) {
                            resolution = String.format(java.util.Locale.ROOT, " · %.1f kHz",
                                    format.sampleRate / 1000.0);
                        }
                        if (format.channelCount > 0) resolution += " · " + format.channelCount + " 声道";
                        changed();
                        return;
                    }
                }
            }

            @Override public void onPositionDiscontinuity(
                    Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) saveProgress();
            }

            @Override public void onEvents(Player ignored, Player.Events events) { changed(); }
        });
        Player controls = new ForwardingPlayer(player) {
            @Override public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_PREVIOUS)
                        .remove(COMMAND_SET_MEDIA_ITEM).remove(COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(COMMAND_SET_REPEAT_MODE).remove(COMMAND_SET_SHUFFLE_MODE).build();
            }

            @Override public boolean isCommandAvailable(int command) {
                return getAvailableCommands().contains(command);
            }

            @Override public void play() {
                if (loading || player.getPlaybackState() == Player.STATE_ENDED
                        || player.getPlayerError() != null) {
                    if (!loading || !intendedPlay) toggle();
                } else {
                    super.play();
                }
            }

            @Override public void pause() {
                if (loading) {
                    intendedPlay = false;
                    changed();
                }
                super.pause();
            }

            @Override public void seekToNext() { next(); }
            @Override public void seekToPrevious() { previous(); }
            @Override public void seekToNextMediaItem() { next(); }
            @Override public void seekToPreviousMediaItem() { previous(); }
        };
        PendingIntent activity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.OPEN_PLAYER, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, controls).setSessionActivity(activity).build();
        addSession(session);
        String origin = settings.getString("relay_origin", "");
        if (!origin.isEmpty() && settings.contains("player_account")) {
            try {
                relay = new CentralRelay(this, origin, settings.getString("relay_id", ""), settings.getString("relay_key", ""));
            } catch (Exception problem) {
                error = "后台配置无效";
            }
        }
        if (remoteRequested) main.post(restoreRemote);
    }

    @Override public IBinder onBind(Intent intent) {
        return LOCAL_BIND.equals(intent.getAction()) ? binder : super.onBind(intent);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
        return session;
    }

    boolean canSyncDesktopSession() {
        return settings.getBoolean("sync_desktop_session", true);
    }

    void enableDesktopSessionSync() {
        settings.edit().putBoolean("sync_desktop_session", true).apply();
    }

    void syncDesktopSession(JSONObject data) throws Exception {
        if (!canSyncDesktopSession()) return;
        TidalClient.Session account = new TidalClient.Session(data.toString());
        TidalClient currentClient = client;
        if (currentClient == null) {
            changeAccount(account, account.toJson(), true);
            return;
        }
        boolean hadHiRes = currentClient.hasHiResSession();
        if (settings.getBoolean("session_from_desktop", false)) {
            if (!currentClient.updateDesktopSession(account)) {
                changeAccount(account, account.toJson(), true);
                return;
            }
        } else currentClient.syncHiResOnly(account);
        TidalClient.Track track = current();
        if (!hadHiRes && currentClient.hasHiResSession() && track != null && track.needsTidal()
                && quality == 3 && !"HI_RES_LOSSLESS".equals(actualQuality)
                && (loading || player.getMediaItemCount() > 0)) {
            playAt(index, loading ? resumePosition : player.getCurrentPosition(),
                    loading ? intendedPlay : player.getPlayWhenReady());
        }
        if (hadHiRes != currentClient.hasHiResSession()) changed();
    }

    void changeAccount(TidalClient.Session account, String saved) {
        changeAccount(account, saved, false);
    }

    private void changeAccount(TidalClient.Session account, String saved, boolean fromDesktop) {
        try {
            String previous = settings.getString("session", "{}");
            if (!account.userId.equals(new JSONObject(previous).optString("user_id"))) {
                signOut();
            }
        } catch (org.json.JSONException ignored) {
            signOut();
        }
        synchronized (settings) {
            settings.edit().putString("session", saved).putBoolean("session_from_desktop", fromDesktop).apply();
        }
        client = new TidalClient(account, settings);
        if (account.hasDirectClient()) {
            try {
                remoteLoginDevice = new DeviceLogin(account.clientId, account.clientSecret);
                settings.edit().putString("tidal_device_id", remoteLoginDevice.clientId)
                        .putString("tidal_device_secret", remoteLoginDevice.clientSecret).apply();
            } catch (IOException ignored) {}
        }
        error = "";
        changed();
    }

    void signOutFromUi() {
        settings.edit().putBoolean("sync_desktop_session", false).apply();
        signOut();
    }

    private void signOut() {
        clearRemoteAuthorization();
        boolean playingTidal = current() != null && current().needsTidal();
        if (playingTidal) {
            cancelResolve();
            loading = false;
            player.stop();
            player.clearMediaItems();
            error = "";
            actualQuality = "";
            resolution = "";
        }
        client = null;
        retainNonTidalState();
        queueRevision++;
        synchronized (settings) {
            settings.edit().remove("session").remove("session_from_desktop").apply();
        }
        saveHistory();
        saveQueue();
        changed();
    }

    private void retainNonTidalState() {
        TidalClient.Track current = current();
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (queue.get(i).needsTidal()) {
                queue.remove(i);
                if (i < index) index--;
            }
        }
        if (current == null || current.needsTidal()) {
            index = -1;
            resumePosition = 0;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).needsTidal()) history.remove(i);
        }
    }

    TidalClient.Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    void replaceQueue(List<TidalClient.Track> tracks, int start, boolean shuffled) {
        replaceQueue(tracks, start, shuffled, 0);
    }

    /** Replace the queue and start at {@code position} ms, e.g. when playback moves here from another device. */
    void replaceQueue(List<TidalClient.Track> tracks, int start, boolean shuffled, long position) {
        queue.clear();
        queue.addAll(tracks);
        queueRevision++;
        if (shuffled && !queue.isEmpty()) {
            Collections.shuffle(queue);
            start = 0;
        } else if (shuffle && start >= 0 && start < queue.size()) {
            TidalClient.Track first = queue.remove(start);
            Collections.shuffle(queue);
            queue.add(0, first);
            start = 0;
        }
        playAt(start, Math.max(0, position), true);
    }

    void enqueue(TidalClient.Track track, boolean playNext) {
        enqueueTracks(Collections.singletonList(track), playNext);
    }

    /** Remove everything after the current track. */
    void clearUpcoming() {
        if (index < 0 || index + 1 >= queue.size()) return;
        queue.subList(index + 1, queue.size()).clear();
        queueRevision++;
        saveQueue();
        changed();
    }

    /** Insert after the current track, or append. An empty queue just receives the tracks. */
    void enqueueTracks(List<TidalClient.Track> tracks, boolean playNext) {
        if (tracks.isEmpty()) return;
        int at = playNext && index >= 0 && index < queue.size() ? index + 1 : queue.size();
        queue.addAll(at, tracks);
        queueRevision++;
        saveQueue();
        changed();
    }

    void enqueueAll(List<TidalClient.Track> tracks) {
        if (tracks.isEmpty()) return;
        queue.addAll(tracks);
        queueRevision++;
        saveQueue();
        changed();
    }

    void remove(int at) {
        if (at == index) return;
        queue.remove(at);
        queueRevision++;
        if (at < index) index--;
        saveQueue();
        changed();
    }

    void move(int from, int to) {
        if (to < 0 || to >= queue.size()) return;
        TidalClient.Track track = queue.remove(from);
        queueRevision++;
        queue.add(to, track);
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        saveQueue();
        changed();
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        settings.edit().putBoolean("shuffle", shuffle).apply();
        if (shuffle && index + 1 < queue.size()) {
            Collections.shuffle(queue.subList(index + 1, queue.size()));
            queueRevision++;
            saveQueue();
        }
        changed();
    }

    void cycleRepeat() {
        switch (repeat) {
            case Player.REPEAT_MODE_OFF: repeat = Player.REPEAT_MODE_ALL; break;
            case Player.REPEAT_MODE_ALL: repeat = Player.REPEAT_MODE_ONE; break;
            default: repeat = Player.REPEAT_MODE_OFF;
        }
        settings.edit().putInt("repeat", repeat).apply();
        changed();
    }

    void setQuality(int selected) {
        if (selected < 0 || selected >= QUALITIES.length) return;
        if (selected != quality) {
            quality = selected;
            settings.edit().putInt("quality", quality).apply();
        }
        TidalClient.Track track = current();
        boolean hasMedia = player.getMediaItemCount() > 0;
        if (track != null && !track.isLocal() && (loading || hasMedia || !error.isEmpty())) {
            long position = loading || !hasMedia ? resumePosition : player.getCurrentPosition();
            boolean play = loading || !hasMedia ? intendedPlay : player.getPlayWhenReady();
            playAt(index, position, play);
        } else changed();
    }

    void setNeteaseLevel(String level) {
        if (!NeteaseClient.knownLevel(level)) return;
        if (!level.equals(neteaseLevel)) {
            neteaseLevel = level;
            settings.edit().putString("netease_level", level).apply();
        }
        TidalClient.Track track = current();
        boolean hasMedia = player.getMediaItemCount() > 0;
        if (track != null && track.isNetease() && (loading || hasMedia || !error.isEmpty())) {
            long position = loading || !hasMedia ? resumePosition : player.getCurrentPosition();
            boolean play = loading || !hasMedia ? intendedPlay : player.getPlayWhenReady();
            playAt(index, position, play);
        } else changed();
    }

    void advance() {
        if (loading || current() == null) return;
        if (repeat == Player.REPEAT_MODE_ONE) {
            player.seekTo(0);
            player.play();
        } else {
            next();
        }
    }

    void next() {
        if (index + 1 < queue.size()) {
            playAt(index + 1, 0, true);
        } else if (repeat == Player.REPEAT_MODE_ALL && !queue.isEmpty()) {
            playAt(0, 0, true);
        }
    }

    void previous() {
        if (!loading && player.getCurrentPosition() > 3000) player.seekTo(0);
        else if (index > 0) playAt(index - 1, 0, true);
    }

    void toggle() {
        if (loading) {
            intendedPlay = !intendedPlay;
            changed();
        } else if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.getMediaItemCount() == 0 || player.getPlayerError() != null) {
            playAt(Math.max(0, index), resumePosition, true);
        } else {
            player.setPlayWhenReady(!player.getPlayWhenReady());
        }
    }

    NeteaseClient netease() {
        return new NeteaseClient(settings);
    }

    void playAt(int at, long position, boolean autoPlay) {
        if (at < 0 || at >= queue.size()) return;
        TidalClient.Track track = queue.get(at);
        if (track.needsTidal() && client == null) {
            error = "此曲目需要登录 TIDAL";
            changed();
            return;
        }
        cancelResolve();
        int version = generation;
        index = at;
        resumePosition = position;
        saveQueue();
        TidalClient api = client;
        sourceBitDepth = 0;
        sourceSampleRate = 0;
        sourceBitrate = 0;
        outputConfig = null;
        int selectedQuality = quality;
        NeteaseClient netease = netease();
        String level = neteaseLevel;
        String selected = qualityOverride != null ? qualityOverride : QUALITIES[selectedQuality];
        qualityOverride = null;
        if (retrying) retrying = false;
        else retriesLeft = 1;
        actualQuality = "";
        resolution = "";
        error = "";
        loading = true;
        intendedPlay = autoPlay;
        player.pause();
        changed();
        if (track.isLocal()) {
            player.setMediaItem(mediaItem(track).setUri(track.localUri).build(), position);
            actualQuality = "本地文件";
            loading = false;
            player.setPlayWhenReady(intendedPlay);
            player.prepare();
            request = network.submit(() -> {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this, track.localUri);
                    int bitrate = Integer.parseInt(retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE));
                    main.post(() -> {
                        if (version != generation) return;
                        sourceBitrate = bitrate;
                        changed();
                    });
                } catch (Exception ignored) {
                    // Some document providers do not report file bitrate.
                } finally {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            });
            changed();
            return;
        }
        request = network.submit(() -> {
            try {
                TidalClient.Stream stream = track.isNetease()
                        ? netease.audio(track.id, level).stream : api.stream(track.id, selected);
                DashManifest dash = stream.dash == null ? null : new DashManifestParser().parse(
                        Uri.EMPTY, new ByteArrayInputStream(stream.dash));
                if (dash != null && dash.dynamic) throw new IOException("暂不支持动态 DASH 清单");
                main.post(() -> {
                    if (version != generation) return;
                    MediaItem.Builder item = mediaItem(track);
                    if (dash == null) player.setMediaItem(item.setUri(stream.uri).build(), position);
                    else player.setMediaSource(new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
                            .createMediaSource(dash, item.setUri(Uri.EMPTY).build()), position);
                    actualQuality = stream.quality;
                    sourceBitDepth = stream.bitDepth;
                    sourceSampleRate = stream.sampleRate;
                    sourceBitrate = stream.bitrate;
                    error = "";
                    if (stream.bitDepth > 0 && stream.sampleRate > 0) {
                        resolution = String.format(java.util.Locale.ROOT, " · %d bit / %.1f kHz",
                                stream.bitDepth, stream.sampleRate / 1000.0);
                    }
                    loading = false;
                    player.setPlayWhenReady(intendedPlay);
                    player.prepare();
                    changed();
                });
            } catch (Exception problem) {
                main.post(() -> {
                    if (version != generation) return;
                    loading = false;
                    player.stop();
                    player.clearMediaItems();
                    error = "音频流不可用 · " + (problem instanceof IOException
                            ? problem.getMessage() : "响应格式无效");
                    changed();
                });
            }
        });
    }

    private static MediaItem.Builder mediaItem(TidalClient.Track track) {
        return new MediaItem.Builder().setMediaId(Long.toString(track.id))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                        .setAlbumTitle(track.album).setArtworkUri(TidalClient.artwork(track.cover, 640)).build());
    }

    private void remember(TidalClient.Track track) {
        if (!history.isEmpty() && history.get(0).id == track.id) return;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).id == track.id) {
                history.remove(i);
                break;
            }
        }
        history.add(0, track);
        if (history.size() > 50) history.remove(history.size() - 1);
        saveHistory();
    }

    private void saveHistory() {
        JSONArray saved = new JSONArray();
        for (TidalClient.Track item : history) saved.put(item.payload);
        settings.edit().putString("history", saved.toString()).apply();
    }

    private void restoreState() {
        restoreTracks("history", history);
        restoreTracks("queue", queue);
        int savedIndex = settings.getInt("queueIndex", -1);
        index = savedIndex >= 0 && savedIndex < queue.size() ? savedIndex : -1;
        resumePosition = index < 0 ? 0 : Math.max(0, settings.getLong("position", 0));
    }

    private void restoreTracks(String key, List<TidalClient.Track> target) {
        try {
            JSONArray saved = new JSONArray(settings.getString(key, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                target.add(new TidalClient.Track(saved.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            target.clear();
        }
    }

    private void saveQueue() {
        JSONArray saved = new JSONArray();
        for (TidalClient.Track track : queue) saved.put(track.payload);
        settings.edit().putString("queue", saved.toString()).putInt("queueIndex", index)
                .putLong("position", resumePosition).apply();
    }

    void saveProgress() {
        TidalClient.Track track = current();
        MediaItem item = player.getCurrentMediaItem();
        if (loading || track == null || item == null
                || !item.mediaId.equals(Long.toString(track.id))) return;
        long position = player.getPlaybackState() == Player.STATE_ENDED ? 0 : player.getCurrentPosition();
        if (position == resumePosition) return;
        resumePosition = position;
        settings.edit().putLong("position", position).apply();
    }

    private void cancelResolve() {
        generation++;
        if (request != null) request.cancel(true);
    }

    void libraryChanged() {
        localRevision++;
        changed();
    }

    void fileDeleted(long id) {
        filesDeleted(Collections.singleton(id));
    }

    void filesDeleted(java.util.Set<Long> ids) {
        if (current() != null && ids.contains(current().id)) {
            cancelResolve();
            loading = false;
            player.stop();
            player.clearMediaItems();
            index = -1;
            resumePosition = 0;
        }
        boolean queueChanged = false;
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (!ids.contains(queue.get(i).id)) continue;
            queue.remove(i);
            if (i < index) index--;
            queueChanged = true;
        }
        if (queueChanged) {
            queueRevision++;
            saveQueue();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ids.contains(history.get(i).id)) history.remove(i);
        }
        saveHistory();
        libraryChanged();
    }

    void fileRenamed(long id, TidalClient.Track replacement) {
        boolean active = current() != null && current().id == id;
        long position = player.getCurrentPosition();
        boolean playing = player.getPlayWhenReady();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id == id) queue.set(i, replacement);
        }
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).id == id) history.set(i, replacement);
        }
        queueRevision++;
        saveHistory();
        saveQueue();
        if (active) playAt(index, position, playing);
        libraryChanged();
    }

    void connectPlayerAccount(JSONObject account) throws Exception {
        String username = account.getString("username");
        String id = account.getString("id");
        String key = account.getString("key");
        if (settings.getBoolean("session_from_desktop", false)) signOut();
        if (relay != null) relay.close();
        relay = new CentralRelay(this, PlayerAccount.ORIGIN, id, key);
        settings.edit().putString("player_account", username)
                .putString("relay_origin", PlayerAccount.ORIGIN).putString("relay_id", id)
                .putString("relay_key", key).putBoolean("sync_desktop_session", true).apply();
    }

    void disconnectPlayerAccount() {
        if (relay != null) relay.close();
        relay = null;
        if (settings.getBoolean("session_from_desktop", false)) signOut();
        settings.edit().remove("player_account").remove("relay_origin")
                .remove("relay_id").remove("relay_key").apply();
    }

    String remoteLink() {
        return remote == null ? "" : remote.link();
    }

    String remoteAddress() {
        return remote == null ? "" : remote.address();
    }

    String remoteCode() {
        return remote == null ? "" : remote.code();
    }

    boolean remoteRequested() {
        return remoteRequested;
    }

    String remoteIssue() {
        return remoteIssue;
    }

    void setRemoteFiles(boolean enabled) {
        remoteFiles = enabled && localLibrary.hasFolders();
        remoteSettings.edit().putBoolean("files", remoteFiles).commit();
    }

    void setLendTidal(boolean enabled) {
        lendTidal = enabled;
        remoteSettings.edit().putBoolean("lend_tidal", enabled).commit();
    }

    void setRemoteRoutedLan(boolean enabled) {
        remoteRoutedLan = enabled;
        remoteSettings.edit().putBoolean("routed", enabled).commit();
    }

    void enableRemote() throws IOException {
        remoteRequested = true;
        remoteSettings.edit().putBoolean("enabled", true).commit();
        startRemote();
    }

    private void startRemote() throws IOException {
        main.removeCallbacks(restoreRemote);
        if (!remoteRequested || remote != null) return;
        try {
            remote = new LanRemote(this, remoteSettings);
            remoteIssue = "";
        } catch (IOException | RuntimeException problem) {
            remoteIssue = problem instanceof java.net.BindException
                    ? "正在等待原遥控端口恢复" : "正在等待 Wi-Fi 或以太网连接";
            main.postDelayed(restoreRemote, 5000);
            changed();
            if (problem instanceof IOException) throw (IOException) problem;
            throw new IOException(remoteIssue, problem);
        }
        changed();
    }

    void refreshRemoteCode() throws IOException {
        closeRemote();
        LanRemote.refreshCredentials(remoteSettings);
        startRemote();
    }

    void disconnectRemote(LanRemote active) {
        if (remote != active) return;
        closeRemote();
        remoteIssue = "正在恢复局域网遥控";
        changed();
        if (remoteRequested) main.post(restoreRemote);
    }

    void disableRemote() {
        remoteRequested = false;
        remoteSettings.edit().putBoolean("enabled", false).commit();
        closeRemote();
        remoteIssue = "";
        changed();
    }

    private void closeRemote() {
        main.removeCallbacks(restoreRemote);
        if (remote != null) remote.close();
        remote = null;
        if (remoteLoginPending) clearRemoteAuthorization();
    }

    DeviceLogin tidalDevice() { return remoteLoginDevice; }

    void configureRemoteLogin(DeviceLogin device) {
        clearRemoteAuthorization();
        if (!device.clientId.isEmpty()) remoteLoginDevice = device;
    }

    void showRemoteAuthorization(DeviceLogin.Challenge challenge) {
        remoteChallenge = challenge;
    }

    void clearRemoteAuthorization() {
        loginRevision++;
        if (remoteLoginRequest != null) remoteLoginRequest.cancel(true);
        remoteLoginRequest = null;
        remoteLoginPending = false;
        remoteChallenge = null;
        remoteLoginError = "";
    }

    private void beginRemoteLogin() throws IOException {
        if (remoteLoginPending || remoteChallenge != null) return;
        DeviceLogin device = remoteLoginDevice == null ? DeviceLogin.brokered() : remoteLoginDevice;
        long revision = ++loginRevision;
        remoteLoginPending = true;
        remoteLoginError = "";
        remoteLoginRequest = network.submit(() -> {
            try {
                DeviceLogin.Challenge challenge = device.start();
                main.post(() -> {
                    if (revision == loginRevision) remoteChallenge = challenge;
                });
                TidalClient.Session account = device.await(challenge);
                String saved = account.toJson();
                main.post(() -> {
                    if (revision != loginRevision) return;
                    clearRemoteAuthorization();
                    changeAccount(account, saved);
                });
            } catch (Exception problem) {
                main.post(() -> {
                    if (revision != loginRevision) return;
                    clearRemoteAuthorization();
                    remoteLoginError = problem instanceof IOException ? problem.getMessage() : "TIDAL 授权失败";
                });
            }
        });
    }

    static JSONObject remoteTrack(TidalClient.Track track) throws org.json.JSONException {
        Uri cover = TidalClient.artwork(track.cover, 640);
        String coverUrl = cover == null ? "" : cover.toString();
        if (coverUrl.isEmpty() && track.isLocal()) {
            coverUrl = "/api/artwork/" + track.id;
        }
        return new JSONObject().put("id", track.id).put("title", track.title)
                .put("artist", track.artist).put("album", track.album)
                .put("source", track.isLocal() ? "本地音乐" : track.isNetease() ? "网易云" : "TIDAL")
                .put("kind", track.isLocal() ? "local" : track.isNetease() ? "netease" : "tidal")
                .put("cover", coverUrl);
    }

    JSONObject remoteSnapshot() throws org.json.JSONException {
        JSONArray items = new JSONArray();
        for (TidalClient.Track track : queue) items.put(remoteTrack(track));
        JSONObject state = new JSONObject().put("queue", items).put("revision", queueRevision)
                .put("index", index).put("playing", loading ? intendedPlay : player.isPlaying())
                .put("loading", loading).put("position", loading ? 0 : player.getCurrentPosition())
                .put("duration", loading ? 0 : Math.max(0, player.getDuration()))
                .put("seekable", !loading && player.isCurrentMediaItemSeekable())
                .put("remoteFiles", remoteFiles).put("fullMode", true).put("quality", quality)
                .put("loggedIn", client != null).put("loginPending", remoteLoginPending)
                .put("shuffle", shuffle).put("repeat", remoteRepeat()).put("volume", mediaVolume()).put("requestedQuality", QUALITIES[quality]).put("requestedNetease", neteaseLevel)
                // Changes when the music library or a download adds or removes files.
                .put("filesRevision", localRevision + "." + DownloadTasks.get(this).revision())
                .put("sourcePath", current() != null && current().isLocal() ? LocalMusicFiles.displayPath(current().localUri) : "").put("error", error)
                .put("loginError", remoteLoginError)
                .put("actualQuality", loading ? "" : actualQuality)
                .put("resolution", loading ? "" : resolution.replaceFirst("^ · ", ""));
        if (remoteChallenge != null) {
            state.put("login", new JSONObject().put("code", remoteChallenge.userCode)
                    .put("url", remoteChallenge.verification.toString())
                    .put("page", remoteChallenge.verificationPage.toString()));
        }
        return state;
    }

    /** Media stream volume as 0–100, which is what the phone's volume keys change. */
    private int mediaVolume() {
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private void setMediaVolume(double percent) throws IOException {
        if (!(percent >= 0 && percent <= 100)) throw new IOException("音量无效");
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (int) Math.round(percent * max / 100), 0);
    }

    /**
     * Retry a TIDAL stream once when playback fails for a reason a new stream can fix: Hi-Res audio
     * this phone cannot decode plays again as lossless, an expired or dropped stream is fetched again.
     * Continues from the same position. Returns false when the error should be shown instead.
     */
    private boolean recover(PlaybackException problem) {
        TidalClient.Track track = current();
        if (track == null || !track.needsTidal() || retriesLeft <= 0 || loading) return false;
        boolean decoding = undecodable(problem.errorCode);
        boolean stream = problem.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || problem.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || problem.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
        boolean hiRes = "HI_RES_LOSSLESS".equals(actualQuality);
        if (!(decoding && hiRes) && !stream) return false;
        retriesLeft--;
        retrying = true;
        if (decoding) qualityOverride = "LOSSLESS";
        long position = Math.max(0, player.getCurrentPosition());
        main.post(() -> playAt(index, position, true));
        return true;
    }

    private static boolean undecodable(int code) {
        return code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
    }

    /** Readable cause, keeping the player's code for reference. */
    private static String describe(PlaybackException problem) {
        int code = problem.errorCode;
        String reason;
        if (undecodable(code)) reason = "此设备无法解码该音频，可在播放音质中改用 High 或更低音质";
        else if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) reason = "音频地址已失效或无权访问";
        else if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) reason = "网络连接失败";
        else if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) reason = "音频文件或清单已损坏";
        else if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                || code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) reason = "无法读取本地文件";
        else return problem.getErrorCodeName();
        return reason + "（" + problem.getErrorCodeName() + "）";
    }

    /** Repeat mode in the relay's convention (0 off, 1 queue, 2 single track), shared by every client. */
    private int remoteRepeat() {
        return repeat == Player.REPEAT_MODE_ONE ? 2 : repeat == Player.REPEAT_MODE_ALL ? 1 : 0;
    }

        void remoteControl(String operation, JSONObject input) throws Exception {
        switch (operation) {
            case "login": beginRemoteLogin(); break;
            case "cancel-login":
                if (remoteLoginPending) clearRemoteAuthorization();
                break;
            case "toggle": toggle(); break;
            case "previous": previous(); break;
            case "next": next(); break;
            case "shuffle": toggleShuffle(); break;
            case "repeat": cycleRepeat(); break;
            case "volume": setMediaVolume(input.getDouble("value")); break;
            case "clear": clearUpcoming(); break;
            case "quality": {
                String value = input.getString("value");
                if (value.startsWith("netease:")) {
                    if (!NeteaseClient.knownLevel(value.substring(8))) throw new IOException("音质无效");
                    setNeteaseLevel(value.substring(8));
                    break;
                }
                int selected = java.util.Arrays.asList(QUALITIES).indexOf(value);
                if (selected < 0) throw new IOException("音质无效");
                setQuality(selected);
                break;
            }
            case "seek":
                if (!loading && player.isCurrentMediaItemSeekable() && player.getDuration() > 0) {
                    player.seekTo(Math.max(0, Math.min(player.getDuration(), input.getLong("ms"))));
                }
                break;
            case "play":
            case "remove":
            case "move":
                if (input.getLong("revision") != queueRevision) throw new IOException("队列已更新，请重试");
                int at = input.getInt("index");
                if (at < 0 || at >= queue.size()) throw new IOException("队列项不存在");
                if ("play".equals(operation)) playAt(at, 0, true);
                else if ("move".equals(operation)) move(at, input.getInt("to"));
                else {
                    if (at == index) throw new IOException("正在播放的歌曲不能移除");
                    remove(at);
                }
                break;
            default: throw new IOException("未知遥控操作");
        }
    }

    void changed() {
        if (listener != null) listener.run();
    }

    @Override public void onDestroy() {
        if (relay != null) relay.close();
        closeRemote();
        clearRemoteAuthorization();
        saveProgress();
        main.removeCallbacks(saveCheckpoint);
        cancelResolve();
        network.shutdownNow();
        removeSession(session);
        session.release();
        player.release();
        super.onDestroy();
    }
}
