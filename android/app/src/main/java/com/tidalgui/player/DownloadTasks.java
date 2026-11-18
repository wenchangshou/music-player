package com.tidalgui.player;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

final class DownloadTasks {
    static final int HIGHEST = 4;
    static final String[] QUALITY_NAMES = {"High", "Low", "Low 96k", "Max", "最高可用（Max 或 High）"};

    static final class QualityUnavailable extends IOException {
        QualityUnavailable(String reason) { super(reason); }
    }

    static Audio resolveQuality(Resolve source, long track, int quality) throws Exception {
        if (quality != HIGHEST) return source.resolve(track, quality);
        Audio audio;
        try {
            audio = source.resolve(track, 3);
        } catch (QualityUnavailable | TidalClient.DownloadAccessDenied unavailable) {
            try {
                audio = source.resolve(track, 0);
            } catch (QualityUnavailable failure) {
                throw new QualityUnavailable("没有可保存的无损音源 · " + failure.getMessage());
            }
        }
        return new Audio(audio.uri, audio.format, "最高可用 · " + audio.resolution, audio.dash);
    }

    private static boolean requiresLossless(int quality) {
        return quality == 0 || quality == 3 || quality == HIGHEST;
    }

    enum State { QUEUED, PREPARING, TRANSFERRING, WAITING, COMPLETE, FAILED, CANCELLED }

    interface Resolve {
        Audio resolve(long track, int quality) throws Exception;

        /** Lyrics in the TIDAL shape ("subtitles" holds synced LRC), or null when unavailable. */
        default JSONObject lyrics(long track) throws Exception { return null; }
    }

    static final class Audio {
        final Uri uri;
        final String format;
        final String resolution;
        final byte[] dash;
        Audio(Uri uri, String format, String resolution) {
            this(uri, format, resolution, null);
        }

        Audio(Uri uri, String format, String resolution, byte[] dash) {
            this.dash = dash;
            this.uri = uri;
            this.format = format;
            this.resolution = resolution;
        }
    }

    static final class Job {
        final String id;
        final long track;
        final String title;
        final String artist;
        final String source;
        final int quality;
        String albumId = "";
        String albumTitle = "";
        String albumArtist = "";
        /** Album cover: a TIDAL image id or a NetEase image URL. */
        String cover = "";
        String resolution = "";
        boolean verifiedLossless;
        boolean checkedDash;
        int number;
        int disc;
        int volumes;
        /** Tracks on this disc of the album (0 when unknown) and the release date, for tags. */
        int totalTracks;
        String date = "";
        String batch = "";
        int order = Integer.MAX_VALUE;
        boolean hidden;
        Uri directory;
        State state = State.QUEUED;
        String error = "";
        long systemId;
        long bytes;
        long total = -1;
        Uri document;
        Future<?> worker;
        int attempt;

        Job(String id, long track, String title, String artist, String source, int quality, Uri directory) {
            this.id = id;
            this.track = track;
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.quality = quality;
            this.directory = directory;
        }

        Job(JSONObject saved) {
            this(saved.optString("id"), saved.optLong("track"), saved.optString("title"),
                    saved.optString("artist"), saved.optString("source"), saved.optInt("quality"),
                    parse(saved.optString("directory")));
            state = State.valueOf(saved.optString("state"));
            error = saved.optString("error");
            systemId = saved.optLong("systemId");
            document = parse(saved.optString("document"));
            albumId = saved.optString("albumId");
            albumTitle = saved.optString("albumTitle");
            albumArtist = saved.optString("albumArtist");
            cover = saved.optString("cover");
            totalTracks = saved.optInt("totalTracks");
            date = saved.optString("date");
            resolution = saved.optString("resolution");
            verifiedLossless = saved.optBoolean("verifiedLossless");
            checkedDash = saved.optBoolean("checkedDash");
            number = saved.optInt("number");
            disc = saved.optInt("disc");
            volumes = saved.optInt("volumes");
            batch = saved.optString("batch");
            order = saved.optInt("order", Integer.MAX_VALUE);
            hidden = saved.optBoolean("hidden");
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("track", track).put("title", title).put("artist", artist)
                    .put("source", source).put("quality", quality).put("directory", directory == null ? "" : directory.toString())
                    .put("state", state.name()).put("error", error).put("systemId", systemId)
                    .put("document", document == null ? "" : document.toString())
                    .put("albumId", albumId).put("albumTitle", albumTitle).put("batch", batch)
                    .put("albumArtist", albumArtist).put("cover", cover).put("totalTracks", totalTracks).put("date", date).put("resolution", resolution).put("verifiedLossless", verifiedLossless).put("checkedDash", checkedDash).put("number", number)
                    .put("disc", disc).put("volumes", volumes)
                    .put("order", order).put("hidden", hidden);
        }

        boolean active() {
            return state == State.QUEUED || state == State.PREPARING || state == State.TRANSFERRING || state == State.WAITING;
        }

        static Uri parse(String uri) { return uri.isEmpty() ? null : Uri.parse(uri); }
    }

    private static DownloadTasks instance;
    static synchronized DownloadTasks get(Context context) {
        if (instance == null) instance = new DownloadTasks(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final DownloadManager system;
    /** Songs downloaded at the same time; the user picks 1–{@link #MAX_PARALLEL} in settings. */
    static final int MAX_PARALLEL = 5;
    private static final String PARALLEL = "parallel_downloads";
    private final java.util.concurrent.ThreadPoolExecutor queue;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    private DownloadTasks(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("music_downloads", Context.MODE_PRIVATE);
        int parallel = Math.max(1, Math.min(MAX_PARALLEL, preferences.getInt(PARALLEL, 3)));
        queue = new java.util.concurrent.ThreadPoolExecutor(parallel, parallel, 30, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
        queue.allowCoreThreadTimeOut(true);
        system = context.getSystemService(DownloadManager.class);
        boolean interrupted = false;
        try {
            JSONArray saved = new JSONArray(preferences.getString("jobs", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                Job job = new Job(saved.getJSONObject(i));
                if (job.active() && job.systemId == 0) {
                    deleteDocument(job.document);
                    job.document = null;
                    job.state = State.FAILED;
                    job.error = "下载中断，可重新下载";
                    interrupted = true;
                }
                jobs.put(job.id, job);
            }
        } catch (Exception ignored) { /* Keep previously decoded jobs. */ }
        if (interrupted) save();
    }

    /** What has been downloaded, for marking songs and albums in lists so they are not fetched twice. */
    static final class Downloaded {
        private final Map<String, String> tracks = new java.util.HashMap<>();
        private final Map<String, Integer> albums = new java.util.HashMap<>();

        private static String source(TidalClient.Track track) {
            return track.isNetease() ? "网易云" : "TIDAL";
        }

        /** "已下载 · Max", or null when the song has not been downloaded. */
        String label(TidalClient.Track track) {
            if (track.isLocal()) return null;
            String quality = tracks.get(source(track) + ":" + track.id);
            return quality == null ? null : quality.isEmpty() ? "已下载" : "已下载 · " + quality;
        }

        /** Songs of this album already downloaded. */
        int album(TidalClient.Collection album) {
            Integer count = albums.get((album.isNetease() ? "网易云" : "TIDAL") + ":" + album.id);
            return count == null ? 0 : count;
        }
    }

    synchronized Downloaded downloaded() {
        Downloaded result = new Downloaded();
        for (Job job : jobs.values()) {
            if (job.state != State.COMPLETE) continue;
            // The quality actually saved, e.g. "Max" from "Max · HiRes FLAC · 24 bit / 96 kHz".
            String quality = job.resolution.isEmpty() ? (job.quality < QUALITY_NAMES.length && job.quality != HIGHEST
                    ? QUALITY_NAMES[job.quality] : "") : job.resolution.split(" · ")[0];
            if (result.tracks.put(job.source + ":" + job.track, quality) == null && !job.albumId.isEmpty()) {
                String albumKey = job.source + ":" + job.albumId;
                Integer count = result.albums.get(albumKey);
                result.albums.put(albumKey, count == null ? 1 : count + 1);
            }
        }
        return result;
    }

    /** Goes up whenever a download adds or removes a file, so file browsers know to reload. */
    private long revision;

    int parallel() {
        return queue.getCorePoolSize();
    }

    /** Change how many songs download at once; running downloads continue, the queue adapts. */
    synchronized void setParallel(int count) {
        int parallel = Math.max(1, Math.min(MAX_PARALLEL, count));
        preferences.edit().putInt(PARALLEL, parallel).apply();
        if (parallel > queue.getMaximumPoolSize()) {
            queue.setMaximumPoolSize(parallel);
            queue.setCorePoolSize(parallel);
        } else {
            queue.setCorePoolSize(parallel);
            queue.setMaximumPoolSize(parallel);
        }
    }

    synchronized long revision() {
        refreshActive();
        return revision;
    }

    synchronized int add(List<TidalClient.Track> tracks, String source, int quality, Uri directory, Resolve resolve) {
        return add(tracks, source, quality, directory, resolve, null);
    }

    synchronized int add(List<TidalClient.Track> tracks, String source, int quality, Uri directory, Resolve resolve,
                        TidalClient.Collection album) {
        refreshActive();
        String batchId = album == null ? "" : source + ":" + album.id + ":" + quality + ":" + directory;
        int skipped = 0;
        // Tracks per disc of the album, for TOTALTRACKS tags.
        Map<Integer, Integer> perDisc = new java.util.HashMap<>();
        if (album != null) {
            for (TidalClient.Track track : tracks) {
                int disc = track.payload.optInt("volumeNumber", 1);
                Integer count = perDisc.get(disc);
                perDisc.put(disc, count == null ? 1 : count + 1);
            }
        }
        Resolve batch = new Resolve() {
            private volatile TidalClient.DownloadAccessDenied denied;

            @Override public Audio resolve(long track, int quality) throws Exception {
                if (denied != null) throw denied;
                try {
                    return resolve.resolve(track, quality);
                } catch (TidalClient.DownloadAccessDenied error) {
                    if (error.sessionDenied) denied = error;
                    throw error;
                }
            }

            @Override public JSONObject lyrics(long track) throws Exception {
                return resolve.lyrics(track);
            }
        };
        for (int index = 0; index < tracks.size(); index++) {
            TidalClient.Track track = tracks.get(index);
            Job reusable = null;
            Job failed = null;
            for (Job job : jobs.values()) {
                if (job.track != track.id || !job.source.equals(source) || job.quality != quality
                        || !java.util.Objects.equals(job.directory, directory)) continue;
                if (job.active() || (job.state == State.COMPLETE && (job.verifiedLossless || !requiresLossless(quality))
                        && (quality != HIGHEST || !"TIDAL".equals(source) || job.checkedDash)
                        && available(job) != null)) {
                    reusable = job;
                    break;
                }
                if (job.state == State.FAILED || job.state == State.CANCELLED) failed = job;
            }
            if (reusable != null) {
                if (album != null) {
                    assignAlbum(reusable, track, album, batchId, index, perDisc);
                }
                reusable.hidden = false;
                // Already downloaded: check its tags, cover and lyrics, and repair what is missing.
                if (reusable.state == State.COMPLETE) verify(reusable, batch);
                skipped++;
                continue;
            }
            if (failed != null) {
                if (album != null) assignAlbum(failed, track, album, batchId, index, perDisc);
                reset(failed, directory, batch);
                continue;
            }
            Job job = new Job(UUID.randomUUID().toString(), track.id, track.title, track.artist, source, quality, directory);
            assignAlbum(job, track, album, batchId, index, perDisc);
            jobs.put(job.id, job);
            submit(job, batch);
        }
        save();
        return skipped;
    }

    private void assignAlbum(Job job, TidalClient.Track track, TidalClient.Collection album, String batch, int index,
                             Map<Integer, Integer> perDisc) {
        job.albumId = album == null ? (track.albumId == 0 ? "" : Long.toString(track.albumId)) : album.id;
        job.albumTitle = album == null ? track.album : album.title;
        job.cover = album != null && !album.cover.isEmpty() ? album.cover : track.cover;
        JSONObject release = track.payload.optJSONObject("album");
        JSONObject author = release == null ? null : release.optJSONObject("artist");
        job.albumArtist = album != null && !album.albumArtist.isEmpty() ? album.albumArtist
                : author == null ? track.artist : author.optString("name", track.artist);
        job.number = track.payload.optInt("trackNumber", album == null ? 0 : index + 1);
        job.disc = track.payload.optInt("volumeNumber", 1);
        job.volumes = album == null ? (release == null ? 1 : release.optInt("numberOfVolumes", 1)) : album.volumes;
        Integer count = perDisc.get(job.disc);
        job.totalTracks = count == null ? 0 : count;
        job.date = releaseDate(track, album);
        job.batch = batch;
        job.order = album == null ? track.payload.optInt("trackNumber", Integer.MAX_VALUE) : index;
    }

    synchronized void retry(String id, Uri directory, Resolve resolve) {
        Job job = jobs.get(id);
        if (job == null || (job.state != State.FAILED && job.state != State.CANCELLED)) return;
        reset(job, directory, resolve);
        save();
    }

    /** Release date from the album or the track's album, e.g. "2004-01-01"; "" when unknown. */
    private static String releaseDate(TidalClient.Track track, TidalClient.Collection album) {
        JSONObject release = track.payload.optJSONObject("album");
        String date = release == null ? "" : release.optString("releaseDate");
        if (date.isEmpty()) date = track.payload.optString("releaseDate");
        if (date.isEmpty()) date = track.payload.optString("streamStartDate");
        if (date.isEmpty() && track.payload.optLong("publishTime") > 0) {
            date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                    .format(new java.util.Date(track.payload.optLong("publishTime")));
        }
        return date.length() >= 10 ? date.substring(0, 10) : date;
    }

    /**
     * For a song already downloaded: when it lacks tags or an embedded cover (as downloads from
     * before tagging do), delete it and download it again; otherwise add a missing cover.jpg or
     * .lrc. Runs on the download queue.
     */
    private void verify(Job job, Resolve resolve) {
        Uri file = available(job);
        if (file == null) return;
        int attempt = job.attempt;
        queue.submit(() -> {
            boolean tagged;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(file)) {
                tagged = input == null || AudioTags.complete(input);
            } catch (Exception unreadable) {
                tagged = true;
            }
            if (tagged) {
                MusicDownload.saveExtras(context, job, MusicDownload.extension(context, file), job.directory, job.document, resolve);
                synchronized (this) { revision++; }
                return;
            }
            synchronized (this) {
                if (job.attempt != attempt || job.state != State.COMPLETE) return;
                if (job.systemId == 0) deleteDocument(job.document);
                job.document = null;
                revision++;
                reset(job, job.directory, resolve);
                save();
            }
        });
    }

    private void reset(Job job, Uri directory, Resolve resolve) {
        if (job.systemId > 0) system.remove(job.systemId);
        job.systemId = 0;
        job.directory = directory;
        job.error = "";
        job.resolution = "";
        job.verifiedLossless = false;
        job.total = -1;
        job.bytes = 0;
        job.state = State.QUEUED;
        submit(job, resolve);
    }

    private void submit(Job job, Resolve resolve) {
        int attempt = ++job.attempt;
        job.worker = queue.submit(() -> run(job, attempt, resolve));
    }

    private void run(Job job, int attempt, Resolve resolve) {
        final Uri directory;
        synchronized (this) {
            if (!current(job, attempt)) return;
            directory = job.directory;
            job.state = State.PREPARING;
            save();
        }
        try {
            Audio audio = resolveQuality(resolve, job.track, job.quality);
            if (!audio.format.matches("mp3|flac|m4a|aac|wav|ogg|opus")) throw new IOException("未知音频格式");
            synchronized (this) {
                if (!current(job, attempt)) return;
                job.state = State.TRANSFERRING;
                job.resolution = audio.resolution;
                job.verifiedLossless = requiresLossless(job.quality);
                job.checkedDash = true;
                save();
            }
            final MusicDownload.Transfer result = MusicDownload.save(context, job, audio, directory, new MusicDownload.Progress() {
                        @Override public void created(Uri document) throws IOException {
                            synchronized (DownloadTasks.this) {
                                if (!current(job, attempt)) throw new IOException("下载已取消");
                                job.document = document;
                                save();
                            }
                        }

                        @Override public void transferred(long bytes, long total) throws IOException {
                            synchronized (DownloadTasks.this) {
                                if (!current(job, attempt)) throw new IOException("下载已取消");
                                job.bytes = bytes;
                                job.total = total;
                            }
                        }
                    });
            synchronized (this) {
                if (!current(job, attempt)) {
                    if (result.systemId > 0) system.remove(result.systemId);
                    deleteDocument(result.document);
                    return;
                }
                job.systemId = result.systemId;
                job.document = result.document;
                job.state = result.document == null ? State.WAITING : State.COMPLETE;
                if (job.state == State.COMPLETE) revision++;
                save();
            }
            // Cover and synced lyrics next to the song, as Shanling and most players expect.
            // Best effort: the song counts as downloaded either way.
            MusicDownload.saveExtras(context, job, audio.format, directory, result.document, resolve);
            synchronized (this) { revision++; }
        } catch (Exception error) {
            synchronized (this) {
                if (!current(job, attempt)) return;
                job.document = null;
                job.state = State.FAILED;
                job.error = error.getMessage() == null ? "下载失败" : error.getMessage();
                save();
            }
        }
    }

    private boolean current(Job job, int attempt) {
        return job.attempt == attempt && job.active();
    }

    synchronized void cancel(String id) {
        Job job = jobs.get(id);
        if (job == null || !job.active()) return;
        job.attempt++;
        if (job.worker != null) job.worker.cancel(true);
        if (job.systemId > 0) system.remove(job.systemId);
        job.systemId = 0;
        deleteDocument(job.document);
        job.document = null;
        job.state = State.CANCELLED;
        save();
    }

    synchronized boolean remove(String id, boolean deleteFile) {
        Job job = jobs.get(id);
        if (job == null || job.active()) return false;
        if (deleteFile && job.state == State.COMPLETE) {
            if (job.systemId > 0 && system.remove(job.systemId) == 0) return false;
            if (job.document != null && !deleteDocument(job.document)) return false;
            revision++;
        }
        if (!deleteFile && job.state == State.COMPLETE) job.hidden = true;
        else jobs.remove(id);
        save();
        return true;
    }

    synchronized TidalClient.Track playable(String id) {
        Job job = jobs.get(id);
        return job == null ? null : playable(job);
    }

    synchronized List<OfflineAlbum> offlineAlbums() {
        refreshActive();
        Map<String, OfflineAlbum> albums = new LinkedHashMap<>();
        List<Job> completed = new ArrayList<>(jobs.values());
        java.util.Collections.sort(completed, (a, b) -> Integer.compare(a.order, b.order));
        for (Job job : completed) {
            TidalClient.Track track = playable(job);
            if (track == null) continue;
            String key = job.source + ":" + (job.albumId.isEmpty() ? job.albumTitle : job.albumId);
            OfflineAlbum album = albums.get(key);
            if (album == null) {
                album = new OfflineAlbum(job.albumTitle.isEmpty() ? "未归档歌曲" : job.albumTitle, job.source);
                albums.put(key, album);
            }
            if (album.ids.add(job.track)) album.tracks.add(track);
        }
        return new ArrayList<>(albums.values());
    }

    static final class OfflineAlbum {
        final String title;
        final String source;
        final List<TidalClient.Track> tracks = new ArrayList<>();
        private final java.util.Set<Long> ids = new java.util.HashSet<>();

        OfflineAlbum(String title, String source) {
            this.title = title;
            this.source = source;
        }
    }

    private TidalClient.Track playable(Job job) {
        if (job.state != State.COMPLETE) return null;
        Uri uri = available(job);
        if (uri == null) return null;
        try {
            return LocalLibrary.downloaded(context, uri, job.title, job.artist, job.albumTitle);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Uri available(Job job) {
        try {
            Uri uri = job.document != null ? job.document
                    : job.systemId > 0 ? system.getUriForDownloadedFile(job.systemId) : null;
            if (uri == null) return null;
            try (android.content.res.AssetFileDescriptor file = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                return file == null ? null : uri;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized List<Job> snapshot() {
        refreshActive();
        List<Job> copy = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (job.hidden) continue;
            Job item = new Job(job.id, job.track, job.title, job.artist, job.source, job.quality, job.directory);
            item.state = job.state;
            item.error = job.error;
            item.bytes = job.bytes;
            item.total = job.total;
            item.albumId = job.albumId;
            item.albumTitle = job.albumTitle;
            item.resolution = job.resolution;
            item.batch = job.batch;
            item.order = job.order;
            copy.add(0, item);
        }
        return copy;
    }

    private void refreshActive() {
        List<Long> ids = new ArrayList<>();
        for (Job job : jobs.values()) if (job.systemId > 0 && job.active()) ids.add(job.systemId);
        if (!ids.isEmpty()) refreshSystem(ids);
    }

    private void refreshSystem(List<Long> ids) {
        long[] queryIds = new long[ids.size()];
        Map<Long, Job> byId = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) queryIds[i] = ids.get(i);
        for (Job job : jobs.values()) if (job.systemId > 0 && job.active()) byId.put(job.systemId, job);
        boolean changed = false;
        try (Cursor rows = system.query(new DownloadManager.Query().setFilterById(queryIds))) {
            if (rows == null) return;
            while (rows.moveToNext()) {
                Job job = byId.remove(rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                if (job == null) continue;
                job.bytes = rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                job.total = rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int status = rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                State previous = job.state;
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (job.state != State.COMPLETE) revision++;
                    job.state = State.COMPLETE;
                }
                else if (status == DownloadManager.STATUS_RUNNING) job.state = State.TRANSFERRING;
                else if (status == DownloadManager.STATUS_FAILED) {
                    job.state = State.FAILED;
                    job.error = "系统下载失败（" + rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) + "）";
                } else job.state = State.WAITING;
                changed |= previous != job.state;
            }
            for (Job job : byId.values()) {
                job.state = State.FAILED;
                job.error = "系统下载记录已移除";
                changed = true;
            }
        } catch (RuntimeException ignored) { }
        if (changed) save();
    }

    private boolean deleteDocument(Uri document) {
        if (document == null) return true;
        try {
            if (DocumentsContract.isDocumentUri(context, document)) {
                return DocumentsContract.deleteDocument(context.getContentResolver(), document);
            }
            return context.getContentResolver().delete(document, null, null) > 0;
        }
        catch (Exception error) { return false; }
    }

    private void save() {
        JSONArray data = new JSONArray();
        try {
            for (Job job : jobs.values()) data.put(job.json());
            preferences.edit().putString("jobs", data.toString()).apply();
        } catch (Exception ignored) { }
    }
}
