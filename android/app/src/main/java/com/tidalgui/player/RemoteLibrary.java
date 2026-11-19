package com.tidalgui.player;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

@androidx.media3.common.util.UnstableApi
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class RemoteLibrary {
    interface Dispatcher {
        <T> T run(Callable<T> action) throws Exception;
    }

    static JSONObject execute(PlaybackService player, Dispatcher main, JSONObject input) throws Exception {
        LocalLibrary library = player.localLibrary;
        String action = input.getString("action");
        String name = bounded(input, "playlist", 64);
        int offset = input.optInt("offset", 0);
        if (offset < 0 || offset > 100_000) throw new IOException("分页超出范围");
        String query = bounded(input, "query", 160);
        long id = input.optLong("id");
        switch (action) {
            case "local": {
                List<TidalClient.Track> tracks = library.search(query, name.isEmpty() ? null : name);
                int category = input.optInt("category", 0);
                String group = input.optString("group");
                if (category == 1 || (category >= 3 && category <= 6)) {
                    if (group.isEmpty()) return groups(tracks, category, offset)
                            .put("playlists", new JSONArray(library.playlists()));
                    List<TidalClient.Track> members = new ArrayList<>();
                    for (TidalClient.Track track : tracks) {
                        if (group.equals(LocalLibrary.groupFor(track, category).key)) members.add(track);
                    }
                    tracks = members;
                }
                String sort = input.optString("sort");
                if (!sort.isEmpty() && !"added".equals(sort)) {
                    java.text.Collator collator = java.text.Collator.getInstance();
                    Collections.sort(tracks, (left, right) -> {
                        int result = 0;
                        if ("artist".equals(sort)) result = collator.compare(left.artist, right.artist);
                        if ("album".equals(sort)) result = collator.compare(left.album, right.album);
                        return result == 0 ? collator.compare(left.title, right.title) : result;
                    });
                }
                int start = Math.min(offset, tracks.size());
                int end = Math.min(tracks.size(), start + 50);
                return page(tracks.subList(start, end), end < tracks.size() ? end : -1)
                        .put("playlists", new JSONArray(library.playlists()));
            }
            case "recent":
                return page(main.run(() -> new ArrayList<>(player.history)), -1);
            case "create":
                if (!library.create(name)) throw new IOException("请输入未使用的歌单名称");
                return changed(player, main);
            case "delete":
                if (name.isEmpty() || !input.optBoolean("confirmed")) throw new IOException("请确认删除歌单");
                library.deletePlaylist(name);
                return changed(player, main);
            case "local-favorite":
                library.toggleLike(library.track(id));
                return changed(player, main);
            case "playlist-add":
                if (!library.playlists().contains(name)) throw new IOException("找不到歌单");
                library.add(name, library.track(id));
                return changed(player, main);
            case "playlist-remove":
                if (name.isEmpty()) throw new IOException("请选择歌单");
                library.track(id);
                library.remove(name, id);
                return changed(player, main);
            case "quality":
                int selected = input.getInt("quality");
                if (selected < 0 || selected >= PlaybackService.QUALITIES.length) throw new IOException("无效音质");
                return main.run(() -> {
                    player.setQuality(selected);
                    return new JSONObject().put("ok", true);
                });
            case "lyrics": {
                TidalClient.Track track = main.run(player::current);
                if (track == null || track.id != id) throw new IOException("当前歌曲已切换");
                JSONObject words = track.isLocal()
                        ? LocalMusicFiles.lyrics(player.getContentResolver(), library.latest(track))
                        : track.isNetease()
                        ? player.netease().lyrics(id)
                        : account(player, main).lyrics(id);
                String lyrics = words.optString("subtitles");
                if (lyrics.isEmpty()) lyrics = words.optString("lyrics");
                return new JSONObject().put("lyrics", lyrics.substring(0, Math.min(lyrics.length(), 32000)));
            }
            case "song": {
                String mode = input.getString("mode");
                if (!"play".equals(mode) && !"next".equals(mode) && !"add".equals(mode)) throw new IOException("无效播放操作");
                TidalClient.Track track;
                if (input.has("track")) {
                    track = new TidalClient.Track(input.getJSONObject("track"));
                } else if (id < 0) {
                    track = library.track(id);
                } else {
                    TidalClient api = account(player, main);
                    track = api.track(id);
                }
                return main.run(() -> {
                    if ("play".equals(mode)) player.replaceQueue(Collections.singletonList(track), 0, false);
                    else player.enqueue(track, "next".equals(mode));
                    return new JSONObject().put("ok", true);
                });
            }
            case "play-list": {
                List<TidalClient.Track> tracks = new ArrayList<>();
                if (input.has("tracks")) {
                    JSONArray arr = input.getJSONArray("tracks");
                    for (int i = 0; i < arr.length(); i++) tracks.add(new TidalClient.Track(arr.getJSONObject(i)));
                } else {
                    JSONArray ids = input.getJSONArray("ids");
                    if (ids.length() < 1 || ids.length() > 50) throw new IOException("每次可播放 1–50 首歌曲");
                    for (int i = 0; i < ids.length(); i++) tracks.add(library.track(ids.getLong(i)));
                }
                return main.run(() -> {
                    player.replaceQueue(tracks, 0, input.optBoolean("shuffle"));
                    return new JSONObject().put("ok", true);
                });
            }
            default:
                return tidal(player, main, input, action, query, offset, id);
        }
    }

    private static JSONObject tidal(PlaybackService player, Dispatcher main, JSONObject input,
            String action, String query, int offset, long id) throws Exception {
        if (action.startsWith("netease-")) {
            NeteaseClient netease = player.netease();
            switch (action) {
                case "netease-search":
                    if (query.isEmpty()) throw new IOException("请输入搜索内容");
                    return tidalPage(netease.search(query, offset), offset);
                case "netease-collections": {
                    TidalClient.Collection.Kind kind = TidalClient.Collection.Kind.valueOf(input.getString("kind"));
                    List<TidalClient.Collection> collections = netease.collections(query, kind, offset);
                    return collections(collections, collections.size() >= 30 ? offset + collections.size() : -1);
                }
                case "netease-collection": {
                    TidalClient.Collection.Kind kind = TidalClient.Collection.Kind.valueOf(input.getString("kind"));
                    String collectionId = bounded(input, "collection", 100);
                    JSONObject data = new JSONObject().put("id", collectionId).put("uuid", collectionId)
                            .put("name", "").put("title", "");
                    return tidalPage(netease.collectionTracks(new TidalClient.Collection(data, kind), offset), offset);
                }
                case "netease-recommended": {
                    List<TidalClient.Collection> collections = netease.recommendedPlaylists(netease.signedIn());
                    return collections(collections, -1);
                }
            }
        }
        TidalClient api = account(player, main);
        switch (action) {
            case "tidal-favorites":
                return tidalPage(api.favorites(offset), offset);
            case "tidal-search":
                if (query.isEmpty()) throw new IOException("请输入搜索内容");
                return tidalPage(api.search(query, offset), offset);
            case "tidal-favorite":
                if (id <= 0) throw new IOException("无效 TIDAL 曲目");
                api.favorite(id, input.getBoolean("enabled"));
                return new JSONObject().put("ok", true);
            case "tidal-radio":
                return tidalPage(api.radio(id), 0).put("next", -1);
            case "tidal-artist-albums": {
                String artist = bounded(input, "collection", 100);
                if (!artist.matches("[0-9]+")) throw new IOException("无效艺人标识");
                return collections(api.artistAlbums(artist), -1);
            }
            case "tidal-collections":
            case "tidal-collection": {
                TidalClient.Collection.Kind kind = TidalClient.Collection.Kind.valueOf(input.getString("kind"));
                if ("tidal-collections".equals(action)) {
                    List<TidalClient.Collection> collections = api.collections(query, kind, offset,
                            kind == TidalClient.Collection.Kind.PLAYLIST && query.isEmpty());
                    return collections(collections, collections.size() == 40 ? offset + 40 : -1);
                }
                String collectionId = bounded(input, "collection", 100);
                if (!collectionId.matches("[A-Za-z0-9-]+")) throw new IOException("无效收藏标识");
                JSONObject data = new JSONObject().put("id", collectionId).put("uuid", collectionId)
                        .put("name", "").put("title", "");
                return tidalPage(api.collectionTracks(new TidalClient.Collection(data, kind), offset), offset);
            }
            default:
                throw new IOException("无效音乐库操作");
        }
    }

    private static JSONObject groups(List<TidalClient.Track> tracks, int category, int offset) throws Exception {
        java.util.Map<String, LocalLibrary.LocalGroup> groups = new java.util.LinkedHashMap<>();
        for (TidalClient.Track track : tracks) {
            LocalLibrary.LocalGroup candidate = LocalLibrary.groupFor(track, category);
            LocalLibrary.LocalGroup group = groups.get(candidate.key);
            if (group == null) {
                group = candidate;
                groups.put(group.key, group);
            }
            group.members.add(track);
        }
        List<LocalLibrary.LocalGroup> sorted = new ArrayList<>(groups.values());
        java.text.Collator collator = java.text.Collator.getInstance();
        Collections.sort(sorted, (left, right) -> collator.compare(left.label, right.label));
        JSONArray result = new JSONArray();
        int end = Math.min(offset + 50, sorted.size());
        for (int i = offset; i < end; i++) {
            LocalLibrary.LocalGroup group = sorted.get(i);
            result.put(new JSONObject().put("id", group.key).put("title", group.label)
                    .put("detail", group.detail).put("count", group.members.size()));
        }
        return new JSONObject().put("groups", result).put("next", end < sorted.size() ? end : -1);
    }

    private static JSONObject collections(List<TidalClient.Collection> result, int next) throws Exception {
        JSONArray items = new JSONArray();
        for (TidalClient.Collection item : result) {
            android.net.Uri cover = item.cover.isEmpty() ? null : TidalClient.artwork(item.cover, 320);
            items.put(new JSONObject().put("id", item.id).put("title", item.title).put("kind", item.kind.name())
                    .put("subtitle", item.subtitle).put("cover", cover == null ? "" : cover.toString()));
        }
        return new JSONObject().put("collections", items).put("next", next);
    }

    private static JSONObject tidalPage(List<TidalClient.Track> tracks, int offset) throws Exception {
        return page(tracks, tracks.size() >= 50 ? offset + 50 : -1);
    }

    private static JSONObject page(List<TidalClient.Track> tracks, int next) throws Exception {
        JSONArray items = new JSONArray();
        for (TidalClient.Track track : tracks) items.put(PlaybackService.remoteTrack(track));
        return new JSONObject().put("tracks", items).put("next", next);
    }

    private static JSONObject changed(PlaybackService player, Dispatcher main) throws Exception {
        return main.run(() -> {
            player.libraryChanged();
            return new JSONObject().put("ok", true);
        });
    }

    private static TidalClient account(PlaybackService player, Dispatcher main) throws Exception {
        TidalClient api = main.run(() -> player.client);
        if (api == null) throw new IOException("请先在设备登录 TIDAL");
        return api;
    }

    private static String bounded(JSONObject input, String key, int max) throws IOException {
        String value = input.optString(key).trim();
        if (value.length() > max) throw new IOException("输入内容过长");
        return value;
    }

    static JSONObject scan(PlaybackService player) throws Exception {
        List<Uri> folders = player.localLibrary.folders();
        if (folders.isEmpty()) throw new IOException("请先在手机授权音乐目录");
        int tracks = 0;
        for (Uri folder : folders) tracks += player.localLibrary.scan(folder).total;
        return new JSONObject().put("scanned", tracks);
    }
}
