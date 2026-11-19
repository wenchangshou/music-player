package com.tidalgui.player;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lyrics for songs of the local music library: saved as a .lrc named after the song, next to it,
 * where players such as Shanling's look. Found on TIDAL or NetEase by title, artist and length,
 * either for all songs without lyrics ("补全歌词") or picked by hand from the search results.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class LyricsFill {
    private static final java.util.regex.Pattern SYNCED = java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d{1,4}:\\d{2}");
    private static LyricsFill instance;

    private final PlaybackService playback;
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private Future<?> task;
    // Progress of the running or last fill, read by the phone and by remotes.
    private volatile boolean running;
    private volatile int total, done, saved, missing;
    private volatile String current = "";

    private LyricsFill(PlaybackService playback) {
        this.playback = playback;
    }

    static synchronized LyricsFill get(PlaybackService playback) {
        if (instance == null || instance.playback != playback) instance = new LyricsFill(playback);
        return instance;
    }

    /** One search result that may have the song's lyrics. */
    static final class Candidate {
        final String source;
        final TidalClient.Track track;
        final boolean match;

        Candidate(String source, TidalClient.Track track, boolean match) {
            this.source = source;
            this.track = track;
            this.match = match;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("source", source).put("id", track.id).put("title", track.title)
                    .put("artist", track.artist).put("album", track.album)
                    .put("duration", DownloadTasks.length(track)).put("match", match);
        }
    }

    JSONObject status() throws Exception {
        return new JSONObject().put("running", running).put("total", total).put("done", done)
                .put("saved", saved).put("missing", missing).put("current", current)
                .put("without", playback.localLibrary.withoutLyrics().size());
    }

    /** Fill in the lyrics of these songs, or of every song without them (ids null), in the background. */
    synchronized void start(List<Long> ids, boolean retry) throws IOException {
        if (running) throw new IOException("正在补全歌词");
        List<TidalClient.Track> songs = new ArrayList<>();
        if (ids == null) {
            for (TidalClient.Track track : playback.localLibrary.withoutLyrics()) {
                if (retry || !track.payload.optBoolean("lyrics_missing")) songs.add(track);
            }
        } else {
            for (long id : ids) songs.add(playback.localLibrary.track(id));
        }
        if (songs.isEmpty()) throw new IOException(ids == null ? "所有歌曲都已有歌词" : "请选择歌曲");
        total = songs.size();
        done = saved = missing = 0;
        current = "";
        running = true;
        task = runner.submit(() -> {
            try {
                for (TidalClient.Track song : songs) {
                    if (Thread.currentThread().isInterrupted()) break;
                    current = song.title;
                    try {
                        if (fill(song)) saved++; else missing++;
                    } catch (Exception failed) {
                        missing++;
                    }
                    done++;
                }
            } finally {
                running = false;
                current = "";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(playback::libraryChanged);
            }
        });
    }

    synchronized void stop() {
        if (task != null) task.cancel(true);
    }

    /** Find and save the lyrics of one song; false when none of the matching results has synced lyrics. */
    boolean fill(TidalClient.Track song) throws Exception {
        if (!song.payload.optString("lrc_uri").isEmpty()) return true;
        for (Candidate candidate : candidates(song)) {
            if (!candidate.match) continue;
            String lyrics = synced(fetch(candidate.source, candidate.track.id));
            if (lyrics.isEmpty()) continue;
            save(song, lyrics);
            return true;
        }
        playback.localLibrary.lyricsFound(song.id, null);
        return false;
    }

    /** TIDAL and NetEase results for the song, matching ones (same title, artist and length) first. */
    List<Candidate> candidates(TidalClient.Track song) throws Exception {
        String title = DownloadTasks.fileTitle(song.title);
        String query = (title + " " + song.artist).trim();
        List<String> keys = new ArrayList<>();
        String key = DownloadTasks.nameKey(title, song.artist);
        if (key != null) keys.add(simplified(key));
        long length = DownloadTasks.length(song);
        List<Candidate> matching = new ArrayList<>();
        List<Candidate> others = new ArrayList<>();
        TidalClient tidal = playback.client;
        if (tidal != null) {
            try {
                add(tidal.search(query, 0), "TIDAL", keys, length, matching, others);
            } catch (Exception unavailable) {
                // NetEase may still have it.
            }
        }
        try {
            NeteaseClient netease = playback.netease();
            if (netease != null) add(netease.search(query, 0), "网易云", keys, length, matching, others);
        } catch (Exception unavailable) {
            // TIDAL may have had it.
        }
        // Same album first: a studio recording before its live version when lengths are unknown.
        String album = simplified(DownloadTasks.nameKey(song.album, "-"));
        if (album != null) {
            java.util.Collections.sort(matching, (left, right) -> Boolean.compare(
                    !album.equals(simplified(DownloadTasks.nameKey(left.track.album, "-"))),
                    !album.equals(simplified(DownloadTasks.nameKey(right.track.album, "-")))));
        }
        matching.addAll(others);
        return matching;
    }

    private static Object toSimplified;

    /** Traditional Chinese as simplified, so 謝謝你 matches 谢谢你 (Android 10 and later). */
    static synchronized String simplified(String text) {
        if (text == null || android.os.Build.VERSION.SDK_INT < 29) return text;
        try {
            if (toSimplified == null) toSimplified = android.icu.text.Transliterator.getInstance("Traditional-Simplified");
            return ((android.icu.text.Transliterator) toSimplified).transliterate(text);
        } catch (Exception unavailable) {
            return text;
        }
    }

    private static void add(List<TidalClient.Track> results, String source, List<String> keys, long length,
                            List<Candidate> matching, List<Candidate> others) {
        int count = 0;
        for (TidalClient.Track result : results) {
            if (count++ >= 10) break;
            boolean named = false;
            for (String found : DownloadTasks.nameKeys(result)) named |= keys.contains(simplified(found));
            boolean match = named && DownloadTasks.sameLength(length, DownloadTasks.length(result));
            (match ? matching : others).add(new Candidate(source, result, match));
        }
    }

    /** {"synced": LRC or "", "plain": text or ""} of one TIDAL or NetEase song. */
    JSONObject fetch(String source, long id) throws Exception {
        JSONObject words;
        if ("网易云".equals(source) || NeteaseClient.owns(id)) {
            words = playback.netease().lyrics(id);
        } else {
            TidalClient tidal = playback.client;
            if (tidal == null) throw new IOException("此设备尚未登录 TIDAL");
            try {
                words = tidal.lyrics(id);
            } catch (IOException none) {
                // TIDAL answers 404 for songs without lyrics.
                words = new JSONObject();
            }
        }
        String subtitles = words.isNull("subtitles") ? "" : words.optString("subtitles");
        String plain = words.isNull("lyrics") ? "" : words.optString("lyrics");
        return new JSONObject().put("synced", synced(words)).put("plain", plain.isEmpty() ? subtitles : plain);
    }

    private static String synced(JSONObject words) {
        String text = words.isNull("subtitles") ? "" : words.optString("subtitles");
        if (text.isEmpty()) text = words.optString("synced");
        return SYNCED.matcher(text).find() ? text.trim() : "";
    }

    /** Save lyrics as the song's .lrc, replacing an earlier one. */
    void save(TidalClient.Track song, String lyrics) throws Exception {
        Uri audio = song.localUri;
        if (audio == null || !DocumentsContract.isDocumentUri(playback, audio)
                || !"tree".equals(audio.getPathSegments().get(0))) {
            throw new IOException("这首歌不在已授权的音乐目录中，无法在旁边保存歌词");
        }
        ContentResolver resolver = playback.getContentResolver();
        byte[] data = lyrics.getBytes(StandardCharsets.UTF_8);
        String existing = song.payload.optString("lrc_uri");
        Uri target = existing.isEmpty() ? null : Uri.parse(existing);
        if (target == null) {
            String tree = DocumentsContract.getTreeDocumentId(audio);
            String document = DocumentsContract.getDocumentId(audio);
            int slash = document.lastIndexOf('/');
            String parent = slash > 0 ? document.substring(0, slash) : tree;
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(audio, parent);
            String name = song.payload.optString("file_name", song.title);
            int dot = name.lastIndexOf('.');
            String lrcName = (dot > 0 ? name.substring(0, dot) : name) + ".lrc";
            target = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", lrcName);
            if (target == null) throw new IOException("无法创建歌词文件");
        }
        try (OutputStream output = resolver.openOutputStream(target, "wt")) {
            if (output == null) throw new IOException("无法写入歌词文件");
            output.write(data);
        }
        playback.localLibrary.lyricsFound(song.id, target.toString());
    }

    static JSONArray json(List<Candidate> candidates) throws Exception {
        JSONArray items = new JSONArray();
        for (Candidate candidate : candidates) items.put(candidate.json());
        return items;
    }
}
