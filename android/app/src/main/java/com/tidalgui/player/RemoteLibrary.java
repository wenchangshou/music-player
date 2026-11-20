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
                String artist = input.optString("artist");
                if (!artist.isEmpty()) {
                    for (int i = tracks.size() - 1; i >= 0; i--) {
                        TidalClient.Track track = tracks.get(i);
                        String a = LocalLibrary.groupFor(track, 3).label;
                        String aa = track.payload.optString("album_artist");
                        if (!a.equalsIgnoreCase(artist) && !aa.equalsIgnoreCase(artist)) tracks.remove(i);
                    }
                }
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
                } else if (category == 1 && !group.isEmpty()) {
                    sortAlbum(tracks);
                }
                int limit = Math.max(1, Math.min(input.optInt("limit", 50), 500));
                int start = Math.min(offset, tracks.size());
                int end = Math.min(tracks.size(), start + limit);
                return page(tracks.subList(start, end), end < tracks.size() ? end : -1)
                        .put("total", tracks.size())
                        .put("playlists", new JSONArray(library.playlists()));
            }
            case "genre-update": {
                JSONObject albumGenres = input.optJSONObject("genres");
                JSONObject trackGenres = input.optJSONObject("tracks");
                int count = library.updateGenres(albumGenres, trackGenres);
                return changed(player, main).put("updated", count);
            }
            case "lyrics-status":
                return LyricsFill.get(player).status();
            case "lyrics-fill": {
                JSONArray chosen = input.optJSONArray("ids");
                List<Long> ids = null;
                if (chosen != null) {
                    if (chosen.length() > 2000) throw new IOException("每次最多 2000 首");
                    ids = new ArrayList<>();
                    for (int i = 0; i < chosen.length(); i++) ids.add(chosen.getLong(i));
                }
                LyricsFill.get(player).start(ids, input.optBoolean("retry"));
                return LyricsFill.get(player).status();
            }
            case "lyrics-stop":
                LyricsFill.get(player).stop();
                return LyricsFill.get(player).status();
            case "lyrics-missing": {
                List<TidalClient.Track> without = new ArrayList<>();
                String term = query.toLowerCase(java.util.Locale.ROOT);
                for (TidalClient.Track track : library.withoutLyrics()) {
                    if (term.isEmpty() || (track.title + " " + track.artist + " " + track.album)
                            .toLowerCase(java.util.Locale.ROOT).contains(term)) without.add(track);
                }
                int start = Math.min(offset, without.size());
                int end = Math.min(without.size(), start + 50);
                return page(without.subList(start, end), end < without.size() ? end : -1).put("total", without.size());
            }
            case "lyrics-candidates": {
                TidalClient.Track song = library.track(id);
                return new JSONObject().put("candidates", LyricsFill.json(LyricsFill.get(player).candidates(song)));
            }
            case "lyrics-preview":
                return LyricsFill.get(player).fetch(input.optString("source"), input.getLong("track"));
            case "lyrics-save": {
                TidalClient.Track song = library.track(id);
                JSONObject words = LyricsFill.get(player).fetch(input.optString("source"), input.getLong("track"));
                String text = words.optString("synced");
                if (text.isEmpty() && input.optBoolean("plain")) text = words.optString("plain");
                if (text.trim().isEmpty()) throw new IOException("这个版本没有带时间轴的歌词");
                LyricsFill.get(player).save(song, text);
                return changed(player, main).put("synced", !words.optString("synced").isEmpty());
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
                TidalClient.Track track = resolve(player, main, id, input.optJSONObject("track"));
                return main.run(() -> {
                    if ("play".equals(mode)) player.replaceQueue(Collections.singletonList(track), 0, false);
                    else player.enqueue(track, "next".equals(mode));
                    return player.remoteSnapshot();
                });
            }
            case "play-list": {
                List<TidalClient.Track> tracks = new ArrayList<>();
                if (input.has("tracks")) {
                    JSONArray arr = input.getJSONArray("tracks");
                    if (arr.length() < 1 || arr.length() > 500) throw new IOException("每次可播放 1–500 首歌曲");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject sent = arr.getJSONObject(i);
                        TidalClient.Track resolved = resolve(player, main, sent.getLong("id"), sent);
                        if (resolved != null) tracks.add(resolved);
                    }
                } else {
                    JSONArray ids = input.getJSONArray("ids");
                    if (ids.length() < 1 || ids.length() > 500) throw new IOException("每次可播放 1–500 首歌曲");
                    for (int i = 0; i < ids.length(); i++) {
                        TidalClient.Track resolved = resolve(player, main, ids.getLong(i), null);
                        if (resolved != null) tracks.add(resolved);
                    }
                }
                if (tracks.isEmpty()) throw new IOException("未找到可播放曲目");
                int start = Math.max(0, Math.min(tracks.size() - 1, input.optInt("index", input.optInt("start", 0))));
                return main.run(() -> {
                    player.replaceQueue(tracks, start, input.optBoolean("shuffle"));
                    return player.remoteSnapshot();
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
                case "netease-artist-albums": {
                    String artist = bounded(input, "collection", 100);
                    if (!artist.matches("[0-9]+")) throw new IOException("无效艺人标识");
                    return collections(netease.artistAlbums(artist), -1);
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

    private static int trackNumber(TidalClient.Track track, String field, int fallback) {
        String value = track.payload.optString(field).split("/", 2)[0].trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException absent) { return fallback; }
    }

    private static void sortAlbum(List<TidalClient.Track> tracks) {
        Collections.sort(tracks, (a, b) -> {
            int disc = Integer.compare(trackNumber(a, "disc_number", 1), trackNumber(b, "disc_number", 1));
            if (disc != 0) return disc;
            int track = Integer.compare(trackNumber(a, "track_number", Integer.MAX_VALUE),
                    trackNumber(b, "track_number", Integer.MAX_VALUE));
            return track != 0 ? track : a.title.compareToIgnoreCase(b.title);
        });
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
            JSONObject item = new JSONObject().put("id", group.key).put("title", group.label)
                    .put("detail", group.detail).put("count", group.members.size());
            if (!group.members.isEmpty()) {
                TidalClient.Track first = group.members.get(0);
                item.put("artwork_id", first.id);
                item.put("cover", "/api/artwork/" + first.id);
                String year = "";
                for (TidalClient.Track m : group.members) {
                    String y = m.payload.optString("year");
                    if (!y.isEmpty()) { year = y; break; }
                }
                item.put("year", year);
            }
            result.put(item);
        }
        return new JSONObject().put("groups", result)
                .put("next", end < sorted.size() ? end : -1)
                .put("total", sorted.size());
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

    /**
     * The song a remote asks to play, looked up by its id on this device: a browser only sends
     * the shortened form of PlaybackService.remoteTrack, without the local file or the artist and
     * album records. NetEase songs, which cannot be looked up by id, are rebuilt from that form.
     */
    private static TidalClient.Track resolve(PlaybackService player, Dispatcher main, long id, JSONObject sent)
            throws Exception {
        if (id < 0) return player.localLibrary.track(id);
        if (NeteaseClient.owns(id)) {
            if (sent == null || sent.optString("title").isEmpty()) throw new IOException("缺少网易云曲目信息");
            JSONObject data = new JSONObject().put("id", id).put("title", sent.optString("title"))
                    .put("artist", new JSONObject().put("name", sent.optString("artist")))
                    .put("album", new JSONObject().put("title", sent.optString("album"))
                            .put("cover", sent.optString("cover")));
            return new TidalClient.Track(data);
        }
        if (id <= 0) throw new IOException("无效曲目");
        return account(player, main).track(id);
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
