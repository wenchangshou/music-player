package com.tidalgui.player;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Commands that let another device of the player account use this device's TIDAL login: browse
 * TIDAL through it ("tidal"), download on it ("download") and manage its downloads ("downloads").
 * The controlling device then needs no TIDAL login of its own.
 */
@androidx.media3.common.util.UnstableApi
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class RemoteTidal {
    /** Replies travel through the relay, which accepts device requests of up to 1 MB. */
    private static final int MAX_REPLY = 900 * 1024;

    private RemoteTidal() {}

    static boolean handles(String operation) {
        return "tidal".equals(operation) || "download".equals(operation) || "downloads".equals(operation);
    }

    static JSONObject execute(PlaybackService playback, RemoteLibrary.Dispatcher main, JSONObject command) throws Exception {
        String operation = command.getString("op");
        if (!main.run(() -> playback.lendTidal)) {
            throw new IOException("对方未允许其他设备使用它的 TIDAL 登录和下载（在对方的设置 › 播放器账号中开启）");
        }
        if ("downloads".equals(operation)) {
            String action = command.optString("action", "list");
            if ("download".equals(action)) {
                TidalClient api = main.run(() -> playback.client);
                if (api == null) throw new IOException("此设备尚未登录 TIDAL");
                String type = command.optString("type");
                if ("album".equals(type) && command.has("id")) {
                    command.put("album", String.valueOf(command.get("id")));
                } else if ("track".equals(type) && command.has("id")) {
                    command.put("ids", new JSONArray().put(command.getLong("id")));
                }
                return download(playback, api, command);
            }
            return downloads(playback, main, command);
        }
        TidalClient api = main.run(() -> playback.client);
        if (api == null) throw new IOException("此设备尚未登录 TIDAL");
        if ("download".equals(operation)) return download(playback, api, command);
        String body = api.forward(command.optString("method", "GET"), command.getString("path"),
                command.has("form") ? command.getString("form") : null);
        if (body.length() > MAX_REPLY) throw new IOException("TIDAL 返回的内容过多，无法通过远程连接传送");
        return new JSONObject().put("body", body);
    }

    /** Download quality: a TIDAL API name, or "BEST" for Max falling back to High. */
    static int quality(String name) throws IOException {
        switch (name) {
            case "BEST": return DownloadTasks.HIGHEST;
            case "HI_RES_LOSSLESS": return 3;
            case "LOSSLESS": return 0;
            case "HIGH": return 1;
            case "LOW": return 2;
            default: throw new IOException("无效的下载音质");
        }
    }

    static String qualityName(int quality) {
        switch (quality) {
            case 3: return "HI_RES_LOSSLESS";
            case 0: return "LOSSLESS";
            case 1: return "HIGH";
            case 2: return "LOW";
            default: return "BEST";
        }
    }

    private static JSONObject download(PlaybackService playback, TidalClient api, JSONObject command) throws Exception {
        int quality = quality(command.optString("quality", "BEST"));
        String album = command.optString("album");
        List<TidalClient.Track> items;
        TidalClient.Collection metadata = null;
        if (!album.isEmpty()) {
            if (!album.matches("[0-9]{1,20}")) throw new IOException("无效的专辑");
            metadata = api.downloadAlbum(album);
            items = api.albumTracks(metadata);
        } else items = DownloadTasks.withAlbums(api, lookup(api, command.getJSONArray("ids")));
        if (items.isEmpty()) throw new IOException("暂无可下载的曲目");
        int skipped = DownloadTasks.get(playback).add(items, "TIDAL", quality, MusicDownload.directory(playback),
                DownloadTasks.tidal(api), metadata);
        return list(DownloadTasks.get(playback)).put("queued", items.size() - skipped).put("skipped", skipped);
    }

    private static List<TidalClient.Track> lookup(TidalClient api, JSONArray ids) throws Exception {
        if (ids.length() < 1 || ids.length() > 100) throw new IOException("每次可下载 1–100 首歌曲");
        java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<TidalClient.Track> tracks = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<TidalClient.Track>> pending = new ArrayList<>();
            for (int i = 0; i < ids.length(); i++) {
                long id = ids.getLong(i);
                pending.add(lookups.submit(() -> api.track(id)));
            }
            for (java.util.concurrent.Future<TidalClient.Track> track : pending) {
                try {
                    tracks.add(track.get(15, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
                    throw new IOException("读取曲目失败");
                }
            }
        } finally {
            lookups.shutdownNow();
        }
        return tracks;
    }

    private static JSONObject downloads(PlaybackService playback, RemoteLibrary.Dispatcher main, JSONObject command)
            throws Exception {
        DownloadTasks tasks = DownloadTasks.get(playback);
        String action = command.optString("action", "list");
        String id = command.optString("id");
        switch (action) {
            case "list":
                break;
            case "marks": {
                long libraryRevision = main.run(() -> playback.localRevision);
                return new JSONObject().put("marks", tasks.downloaded(libraryRevision).toJson());
            }
            case "cancel":
                tasks.cancel(id);
                break;
            case "remove":
                tasks.remove(id, false);
                break;
            case "clear":
                for (DownloadTasks.Job job : tasks.snapshot()) if (!job.active()) tasks.remove(job.id, false);
                break;
            case "parallel":
                tasks.setParallel(command.getInt("count"));
                break;
            case "retry": {
                DownloadTasks.Job found = null;
                for (DownloadTasks.Job job : tasks.snapshot()) if (job.id.equals(id)) found = job;
                if (found == null) throw new IOException("下载记录已不存在");
                DownloadTasks.Resolve resolve;
                if ("网易云".equals(found.source)) resolve = DownloadTasks.netease(main.run(playback::netease));
                else {
                    TidalClient api = main.run(() -> playback.client);
                    resolve = api == null ? null : DownloadTasks.tidal(api);
                }
                if (resolve == null) throw new IOException("此设备尚未登录 " + found.source);
                tasks.retry(id, found.directory, resolve);
                break;
            }
            default:
                throw new IOException("无效的下载操作");
        }
        return list(tasks);
    }

    /** The download list as the controlling device shows it, newest first. */
    static JSONObject list(DownloadTasks tasks) throws Exception {
        JSONArray jobs = new JSONArray();
        for (DownloadTasks.Job job : tasks.snapshot()) {
            if (jobs.length() >= 300) break;
            jobs.put(new JSONObject().put("id", job.id).put("track", job.track).put("title", job.title)
                    .put("artist", job.artist).put("album", job.albumTitle).put("source", job.source)
                    .put("state", job.state.name().toLowerCase(java.util.Locale.ROOT))
                    .put("bytes", job.bytes).put("total", job.total).put("resolution", job.resolution)
                    .put("error", job.error).put("batch", job.batch).put("order", job.order)
                    .put("quality", qualityName(job.quality)));
        }
        return new JSONObject().put("jobs", jobs).put("parallel", tasks.parallel())
                .put("maxParallel", DownloadTasks.MAX_PARALLEL).put("revision", tasks.revision())
                .put("perTrack", true);
    }

    /** Download rows from a "downloads" reply, in the shape the downloads page shows. */
    static List<DownloadTasks.Job> parse(JSONObject reply) {
        List<DownloadTasks.Job> jobs = new ArrayList<>();
        JSONArray items = reply == null ? null : reply.optJSONArray("jobs");
        if (items == null) return jobs;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            DownloadTasks.Job job = new DownloadTasks.Job(item.optString("id"), item.optLong("track"),
                    item.optString("title"), item.optString("artist"), item.optString("source"), 0, (Uri) null);
            try {
                job.state = DownloadTasks.State.valueOf(item.optString("state").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                job.state = DownloadTasks.State.QUEUED;
            }
            job.bytes = item.optLong("bytes");
            job.total = item.optLong("total", -1);
            job.albumTitle = item.optString("album");
            job.resolution = item.optString("resolution");
            job.error = item.optString("error");
            job.batch = item.optString("batch");
            job.order = item.optInt("order", Integer.MAX_VALUE);
            jobs.add(job);
        }
        return jobs;
    }
}
