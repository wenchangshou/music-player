package com.tidalgui.player;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NeteaseClient {
    static final String DEFAULT_BASE = PlayerAccount.ORIGIN.isEmpty() ? "" : PlayerAccount.ORIGIN + "/netease";
    static final String SESSION = "netease_session";
    static final String SETTING = "netease_api_url";
    static final String[] QUALITY_NAMES = {"无损", "极高", "标准", "Hi-Res"};
    /** 网易云's playback levels, best first, with their official names and what they deliver. */
    static final String[] PLAY_LEVELS = {"jymaster", "sky", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard"};
    static final String[] PLAY_NAMES = {"超清母带", "沉浸环绕声", "高清环绕声", "Hi-Res", "无损", "极高", "较高", "标准"};
    static final String[] PLAY_CHOICES = {"超清母带 · 母带音质，最高 24bit/192kHz", "沉浸环绕声 · 空间音频",
            "高清环绕声 · 环绕声", "Hi-Res · 最高 24bit/192kHz", "无损 · FLAC 16bit/44.1kHz", "极高 · 最高 320kbps",
            "较高 · 192kbps", "标准 · 128kbps"};

    /** Official name of a 网易云 level code such as "jymaster". */
    static String levelName(String level) {
        int at = java.util.Arrays.asList(PLAY_LEVELS).indexOf(level);
        return at >= 0 ? PLAY_NAMES[at] : level;
    }

    static boolean knownLevel(String level) {
        return java.util.Arrays.asList(PLAY_LEVELS).contains(level);
    }
    private static final String[] LEVELS = {"lossless", "exhigh", "standard", "hires"};
    private static final long ID_BASE = 1L << 52;
    private final String base;
    private final String session;

    NeteaseClient(android.content.SharedPreferences settings) {
        String custom = settings.getString(SETTING, "");
        base = custom.isEmpty() ? DEFAULT_BASE : custom;
        session = usesDefaultBackend() ? settings.getString(SESSION, "") : "";
    }

    boolean usesDefaultBackend() { return !DEFAULT_BASE.isEmpty() && DEFAULT_BASE.equals(base); }
    boolean signedIn() { return !session.isEmpty(); }

    JSONObject startLogin() throws Exception {
        return get("login/qr/start");
    }

    JSONObject pollLogin(String proof) throws Exception {
        return request("login/qr/poll", "X-Netease-QR-Proof", proof);
    }

    static String validateBase(String value) throws IOException {
        try {
            URI uri = new URI(value.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return uri.toASCIIString().replaceFirst("/+$", "");
            }
        } catch (java.net.URISyntaxException ignored) {}
        throw new IOException("请输入 HTTPS 接口地址，可包含路径");
    }

    static boolean owns(long id) { return id > ID_BASE && id < 2 * ID_BASE; }

    static String songLink(long id) { return "https://music.163.com/song?id=" + nativeId(id); }

    private static long nativeId(long id) { return id - ID_BASE; }

    static long catalogueId(long id) throws IOException {
        if (id <= 0 || id >= ID_BASE) throw new IOException("网易云曲目或目录编号无效");
        return ID_BASE + id;
    }

    private JSONObject get(String path) throws Exception {
        boolean authorized = path.startsWith("song/url/v1?") || path.startsWith("recommend/") || path.startsWith("user/")
                || path.startsWith("playlist/track/all?");
        JSONObject body = request(path, "X-Netease-Session", authorized ? session : "");
        int code = body.optInt("code", 200);
        if (code != 200) throw new IOException("网易云接口错误 " + code);
        return body;
    }

    private JSONObject request(String path, String header, String value) throws Exception {
        if (base.isEmpty()) throw new IOException("请在音源设置中填写网易云接口地址");
        HttpURLConnection connection = (HttpURLConnection) new URI(base + "/" + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!value.isEmpty()) connection.setRequestProperty(header, value);
            int status = connection.getResponseCode();
            if (status == 503) throw new IOException("网易云服务暂不可用，请稍后重试");
            if (status == 404 && DEFAULT_BASE.equals(base)) throw new IOException("默认后台尚未启用网易云");
            if (status != 200) throw new IOException("网易云接口 HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(TidalClient.readText(input, 8 * 1024 * 1024));
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject search(String query, int type, int offset, int limit) throws Exception {
        JSONObject result = get("cloudsearch?type=" + type + "&offset=" + offset
                + "&limit=" + limit + "&keywords=" + Uri.encode(query)).optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    List<TidalClient.Track> search(String query, int offset) throws Exception {
        return tracks(search(query, 1, offset, 50).optJSONArray("songs"));
    }

    List<TidalClient.Collection> collections(String query, TidalClient.Collection.Kind kind, int offset) throws Exception {
        int type = kind == TidalClient.Collection.Kind.ALBUM ? 10
                : kind == TidalClient.Collection.Kind.ARTIST ? 100 : 1000;
        String field = kind == TidalClient.Collection.Kind.ALBUM ? "albums"
                : kind == TidalClient.Collection.Kind.ARTIST ? "artists" : "playlists";
        return collections(search(query, type, offset, 40).optJSONArray(field), kind);
    }

    List<TidalClient.Track> collectionTracks(TidalClient.Collection item, int offset) throws Exception {
        long id = nativeId(Long.parseLong(item.id));
        switch (item.kind) {
            case ALBUM:
                return offset == 0 ? tracks(get("album?id=" + id).optJSONArray("songs")) : new ArrayList<>();
            case ARTIST:
                return offset == 0 ? tracks(get("artists?id=" + id).optJSONArray("hotSongs")) : new ArrayList<>();
            default:
                return tracks(get("playlist/track/all?id=" + id + "&limit=50&offset=" + offset).optJSONArray("songs"));
        }
    }

    List<TidalClient.Collection> artistAlbums(String id) throws Exception {
        return collections(get("artist/album?limit=50&id=" + nativeId(Long.parseLong(id))).optJSONArray("hotAlbums"),
                TidalClient.Collection.Kind.ALBUM);
    }

    private static List<TidalClient.Track> tracks(JSONArray data) throws Exception {
        List<TidalClient.Track> result = new ArrayList<>();
        if (data == null) return result;
        for (int i = 0; i < data.length(); i++) {
            JSONObject song = data.getJSONObject(i);
            JSONObject track = new JSONObject().put("id", catalogueId(song.getLong("id")))
                    .put("title", song.getString("name")).put("duration", song.optLong("dt") / 1000)
                    .put("artists", song.optJSONArray("ar"));
            JSONObject album = song.optJSONObject("al");
            if (album != null) {
                long id = album.optLong("id");
                track.put("album", new JSONObject().put("id", id > 0 ? catalogueId(id) : 0)
                        .put("title", album.optString("name")).put("cover", album.optString("picUrl")));
            }
            result.add(new TidalClient.Track(track));
        }
        return result;
    }

    private static List<TidalClient.Collection> collections(JSONArray data, TidalClient.Collection.Kind kind) throws Exception {
        List<TidalClient.Collection> result = new ArrayList<>();
        if (data == null) return result;
        for (int i = 0; i < data.length(); i++) {
            result.add(collection(data.getJSONObject(i), kind));
        }
        return result;
    }

    private static TidalClient.Collection collection(JSONObject item, TidalClient.Collection.Kind kind)
            throws Exception {
        JSONObject entry = new JSONObject();
        long id = catalogueId(item.getLong("id"));
        String name = item.getString("name");
        switch (kind) {
            case ARTIST:
                entry.put("id", id).put("name", name)
                        .put("picture", item.optString("img1v1Url", item.optString("picUrl")));
                break;
            case PLAYLIST:
                entry.put("uuid", Long.toString(id)).put("title", name)
                        .put("image", item.optString("coverImgUrl", item.optString("picUrl")));
                break;
            default:
                entry.put("id", id).put("title", name).put("cover", item.optString("picUrl"));
        }
        return new TidalClient.Collection(entry, kind);
    }

    static final class Audio {
        final TidalClient.Stream stream;
        final String format;
        final String actualLevel;
        final int bitrate;

        Audio(TidalClient.Stream stream, String format, String actualLevel, int bitrate) {
            this.stream = stream;
            this.format = format;
            this.actualLevel = actualLevel;
            this.bitrate = bitrate;
        }
    }

    Audio audio(long trackId, int quality) throws Exception {
        return audio(trackId, LEVELS[quality]);
    }

    /** Audio at one of {@link #PLAY_LEVELS}. */
    Audio audio(long trackId, String requested) throws Exception {
        long id = nativeId(trackId);
        JSONArray items = get("song/url/v1?id=" + id + "&level=" + requested).optJSONArray("data");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optLong("id") != id) continue;
            if (!item.isNull("freeTrialInfo")) throw new IOException("只提供试听片段，完整音频需要相应权限");
            Uri uri = httpsMedia(item.optString("url"));
            if (uri == null) break;
            String actual = item.optString("level", requested);
            return new Audio(new TidalClient.Stream(uri, null, new JSONObject().put("bitRate", item.optInt("br")), "网易云 · " + levelName(actual)),
                    item.optString("type").toLowerCase(Locale.ROOT), item.optString("level"), item.optInt("br"));
        }
        throw new DownloadTasks.QualityUnavailable("网易云曲目不可用，可能需要登录、会员或地区授权");
    }

    static void verifyDownloadQuality(Audio audio, int requested) throws IOException {
        if (requested != 0 && requested != 3) return;
        boolean losslessFormat = "flac".equals(audio.format) || "wav".equals(audio.format);
        boolean hiRes = "hires".equals(audio.actualLevel) || "jymaster".equals(audio.actualLevel);
        boolean lossless = hiRes || "lossless".equals(audio.actualLevel) || audio.actualLevel.isEmpty();
        if (!losslessFormat || !lossless || (requested == 3 && !hiRes)) {
            String level = audio.actualLevel.isEmpty() ? "未知音质" : audio.actualLevel;
            throw new DownloadTasks.QualityUnavailable("网易云返回 " + level + " / " + audio.format
                    + "，未满足所选" + (requested == 3 ? " Hi-Res" : "无损") + "音质");
        }
    }

    static Uri httpsMedia(String value) {
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getUserInfo() != null) return null;
        if ("https".equals(uri.getScheme())) return uri;
        if ("http".equals(uri.getScheme()) && host.toLowerCase(Locale.ROOT).endsWith(".music.126.net")) {
            return uri.buildUpon().scheme("https").build();
        }
        return null;
    }

    List<TidalClient.Track> dailyTracks() throws Exception {
        JSONObject data = get("recommend/songs").optJSONObject("data");
        return tracks(data == null ? null : data.optJSONArray("dailySongs"));
    }

    List<TidalClient.Collection> recommendedPlaylists(boolean personal) throws Exception {
        JSONObject body = get(personal ? "recommend/resource" : "personalized?limit=10");
        return collections(body.optJSONArray(personal ? "recommend" : "result"), TidalClient.Collection.Kind.PLAYLIST);
    }

    static final class PlaylistPage {
        final List<TidalClient.Collection> created = new ArrayList<>();
        final List<TidalClient.Collection> collected = new ArrayList<>();
        final boolean more;
        final int nextOffset;
        final long user;

        PlaylistPage(JSONObject body, String key, int offset, long user) throws Exception {
            JSONArray rows = body.optJSONArray(key);
            int length = rows == null ? 0 : rows.length();
            for (int i = 0; i < length; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.optLong("id") <= 0) continue;
                JSONObject creator = row.optJSONObject("creator");
                long owner = row.optLong("userId", creator == null ? 0 : creator.optLong("userId"));
                boolean saved = row.optBoolean("subscribed", owner > 0 && owner != user);
                List<TidalClient.Collection> target = user > 0 && saved ? collected : created;
                target.add(collection(row, TidalClient.Collection.Kind.PLAYLIST));
            }
            more = length > 0 && body.optBoolean("more");
            nextOffset = offset + length;
            this.user = user;
        }
    }

    PlaylistPage playlistSquare(String category, int offset) throws Exception {
        JSONObject body = get("top/playlist?cat=" + Uri.encode(category) + "&limit=30&offset=" + offset);
        return new PlaylistPage(body, "playlists", offset, 0);
    }

    PlaylistPage myPlaylists(long user, int offset) throws Exception {
        if (!signedIn()) throw new IOException("请先登录网易云");
        if (user <= 0) {
            JSONObject account = get("user/account").optJSONObject("account");
            user = account == null ? 0 : account.optLong("id");
            if (user <= 0) throw new IOException("网易云登录已失效，请重新登录");
        }
        JSONObject body = get("user/playlist?uid=" + user + "&limit=30&offset=" + offset);
        return new PlaylistPage(body, "playlist", offset, user);
    }

    JSONObject comments(long trackId, int offset, long before) throws Exception {
        String cursor = before > 0 ? "&before=" + before : "";
        return get("comment/music?id=" + nativeId(trackId) + "&limit=20&offset=" + offset + cursor);
    }

    long likes(long trackId) throws Exception {
        JSONObject data = get("song/red/count?id=" + nativeId(trackId)).optJSONObject("data");
        return data == null ? -1 : data.optLong("count", -1);
    }

    JSONObject lyrics(long trackId) throws Exception {
        JSONObject lrc = get("lyric?id=" + nativeId(trackId)).optJSONObject("lrc");
        return new JSONObject().put("subtitles", lrc == null ? "" : lrc.optString("lyric"));
    }
}
