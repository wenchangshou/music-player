package com.tidalgui.player;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class TidalClient {
    private static final String API = "https://api.tidal.com/v1/";
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    static final class Session {
        final String token;
        final String country;
        final String userId;
        final long expiresAt;
        final String clientId;
        final String clientSecret;
        final String refreshToken;
        final boolean brokered;
        final boolean browserAuth;
        final String hiresToken;
        final long hiresExpiresAt;

        Session(String json) throws Exception {
            JSONObject data = new JSONObject(json);
            token = data.isNull("token") ? "" : data.optString("token").trim();
            country = data.isNull("country_code") ? "US" : data.optString("country_code");
            userId = data.optString("user_id", "");
            expiresAt = data.optLong("expires_at", 0);
            clientId = data.optString("client_id", "");
            clientSecret = data.optString("client_secret", "");
            refreshToken = data.optString("refresh_token", "");
            brokered = data.optBoolean("brokered");
            browserAuth = brokered && data.optBoolean("browser_auth");
            hiresToken = data.isNull("hires_token") ? "" : data.optString("hires_token").trim();
            hiresExpiresAt = data.optLong("hires_expires_at", 0);
            if (token.isEmpty() || !country.matches("[A-Za-z]{2}") || !userId.matches("[0-9]+")) {
                throw new IOException("auth.json 缺少有效的 token、user_id 或地区");
            }
            if (!renewable() && expiresAt > 0 && expiresAt <= System.currentTimeMillis() / 1000) {
                throw new IOException("会话已过期，请重新登录");
            }
        }

        boolean hasDirectClient() {
            return !brokered && !clientId.isEmpty() && !clientSecret.isEmpty();
        }

        boolean renewable() {
            return !refreshToken.isEmpty() && (brokered || hasDirectClient());
        }

        String toJson() throws Exception {
            JSONObject stored = new JSONObject().put("token", token).put("country_code", country)
                    .put("user_id", userId).put("expires_at", expiresAt)
                    .put("hires_token", hiresToken).put("hires_expires_at", hiresExpiresAt);
            if (renewable()) {
                stored.put("refresh_token", refreshToken);
                if (brokered) stored.put("brokered", true).put("browser_auth", browserAuth);
                else stored.put("client_id", clientId).put("client_secret", clientSecret);
            }
            return stored.toString();
        }
    }

    static final class Track {
        final long id;
        final String title;
        final String artist;
        final long artistId;
        final String artistPicture;
        final String album;
        final String cover;
        final long albumId;
        final JSONObject payload;
        final Uri localUri;
        final String localRoot;
        final String webdavUrl;
        final String webdavAuth;
        final String smbUrl;
        final String smbServerId;

        Track(JSONObject data) throws Exception {
            payload = data;
            id = data.getLong("id");
            String local = data.optString("local_uri", "");
            Uri candidate = id < 0 && !local.isEmpty() ? Uri.parse(local) : null;
            localRoot = data.optString("local_root", "");
            localUri = candidate != null && "content".equals(candidate.getScheme()) ? candidate : null;
            webdavUrl = data.optString("webdav_url", "");
            webdavAuth = data.optString("webdav_auth", "");
            smbUrl = data.optString("smb_url", "");
            smbServerId = data.optString("smb_server_id", "");
            title = data.getString("title");
            JSONObject primary = data.optJSONObject("artist");
            JSONArray artists = data.optJSONArray("artists");
            if (primary == null && artists != null) {
                primary = artists.optJSONObject(0);
            }
            artist = primary == null ? "" : primary.optString("name");
            artistId = primary == null ? 0 : primary.optLong("id");
            artistPicture = primary == null ? "" : primary.optString("picture");
            JSONObject release = data.optJSONObject("album");
            album = release == null ? "" : release.optString("title");
            albumId = release == null ? 0 : release.optLong("id");
            cover = release == null ? "" : release.optString("cover");
        }

        boolean isLocal() { return localUri != null; }
        boolean isWebdav() { return !webdavUrl.isEmpty(); }
        boolean isSmb() { return !smbUrl.isEmpty(); }
        boolean isNetease() { return NeteaseClient.owns(id); }
        boolean needsTidal() { return !isLocal() && !isNetease() && !isWebdav() && !isSmb(); }

    }

    static Uri artwork(String cover, int size) {
        if (!cover.matches("[0-9a-fA-F-]{36}")) return NeteaseClient.sizedMedia(cover, size);
        return Uri.parse("https://resources.tidal.com/images/" + cover.replace('-', '/')
                + "/" + size + "x" + size + ".jpg");
    }

    static final class Collection {
        final String id;
        final String title;
        final String cover;
        final Kind kind;
        final String subtitle;
        final String albumArtist;
        final int volumes;

        enum Kind { ALBUM, PLAYLIST, ARTIST, MIX }

        boolean isNetease() {
            try {
                return NeteaseClient.owns(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        Collection(JSONObject data, Kind kind) throws JSONException {
            this.kind = kind;
            if (kind == Kind.MIX) {
                id = data.getString("id");
                String name = data.optString("title");
                title = name.isEmpty() ? "Mix" : name;
                subtitle = data.optString("subTitle", data.optString("subtitle"));
                albumArtist = "";
                volumes = 1;
                cover = mixImage(data);
                return;
            }
            id = data.getString(kind == Kind.PLAYLIST ? "uuid" : "id");
            title = data.getString(kind == Kind.ARTIST ? "name" : "title");
            String date = data.optString("releaseDate");
            JSONObject artist = data.optJSONObject("artist");
            JSONArray artists = data.optJSONArray("artists");
            if (artist == null && artists != null) artist = artists.optJSONObject(0);
            String author = artist == null ? "" : artist.optString("name");
            albumArtist = author;
            volumes = data.optInt("numberOfVolumes", 1);
            String year = date.matches("[0-9]{4}.*") ? date.substring(0, 4) : "";
            subtitle = author.isEmpty() ? year : year.isEmpty() ? author : author + " · " + year;
            if (kind == Kind.PLAYLIST) {
                cover = data.isNull("squareImage") ? data.optString("image") : data.optString("squareImage");
            } else {
                cover = kind == Kind.ARTIST && data.isNull("picture")
                        ? data.optString("selectedAlbumCoverFallback") : data.optString(kind == Kind.ARTIST ? "picture" : "cover");
            }
        }
    }

    static final class Stream {
        final Uri uri;
        final byte[] dash;
        final String quality;
        final int bitDepth;
        final int sampleRate;
        final int bitrate;

        Stream(Uri uri, byte[] dash, JSONObject data, String requested) {
            this.uri = uri;
            this.dash = dash;
            quality = data.optString("audioQuality", requested);
            bitDepth = data.optInt("bitDepth", 0);
            sampleRate = data.optInt("sampleRate", 0);
            bitrate = data.optInt("bitRate", 0);
        }
    }

    private volatile Session session;
    private final android.content.SharedPreferences preferences;

    /** Sends TIDAL API requests to another device of the player account, which is signed in. */
    interface Proxy {
        String request(String method, String path, String form) throws Exception;
    }

    private final Proxy proxy;

    TidalClient(Session session, android.content.SharedPreferences preferences) {
        this.session = session;
        this.preferences = preferences;
        proxy = null;
    }

    /** A client without a TIDAL login of its own: every request is answered by the controlled device. */
    TidalClient(Proxy proxy) {
        session = null;
        preferences = null;
        this.proxy = proxy;
    }

    boolean proxied() { return proxy != null; }

    /** The signed-in user in API paths; "{user}" when proxied, which the answering device fills in. */
    private String user() {
        return proxy != null ? "{user}" : session.userId;
    }

    private static final java.util.regex.Pattern FORWARDED = java.util.regex.Pattern.compile(
            "(search|tracks|albums|artists|playlists|mixes|pages|users/\\{user\\})(/[A-Za-z0-9_./-]*)?(\\?[^#\\s]*)?");

    /**
     * Answer a TIDAL API request made by another device of the player account that has no TIDAL
     * login. Only catalogue and library reads, and favourite changes, are allowed: never stream or
     * download URLs, which this device fetches itself.
     */
    String forward(String method, String path, String form) throws Exception {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (path.length() > 800 || !FORWARDED.matcher(path).matches() || path.contains("..")
                || lower.contains("playbackinfo") || lower.contains("streamurl") || lower.contains("urlpostpaywall")
                || lower.contains("countrycode=")) {
            throw new IOException("不支持的 TIDAL 请求");
        }
        boolean favourite = path.startsWith("users/{user}/favorites/");
        if (!"GET".equals(method) && !(favourite && ("POST".equals(method) || "DELETE".equals(method)))) {
            throw new IOException("不支持的 TIDAL 请求");
        }
        if (form != null && form.length() > 400) throw new IOException("请求内容过长");
        return request(method, path.replace("{user}", session.userId), "POST".equals(method) ? form : null);
    }

    synchronized boolean updateDesktopSession(Session account) throws Exception {
        if (!session.userId.equals(account.userId)) return false;
        if (!session.token.equals(account.token) || session.expiresAt != account.expiresAt
                || !session.country.equals(account.country) || !session.hiresToken.equals(account.hiresToken)
                || session.hiresExpiresAt != account.hiresExpiresAt) {
            synchronized (preferences) {
                preferences.edit().putString("session", account.toJson()).apply();
            }
            session = account;
        }
        return true;
    }

    boolean hasHiResSession() {
        Session account = session;
        return account != null && !account.hiresToken.isEmpty() && account.hiresExpiresAt > System.currentTimeMillis() / 1000;
    }

    synchronized void syncHiResOnly(Session account) throws Exception {
        Session current = session;
        if (!current.userId.equals(account.userId) || !current.country.equalsIgnoreCase(account.country)
                || account.hiresToken.isEmpty() || account.hiresExpiresAt <= System.currentTimeMillis() / 1000
                || (account.hiresToken.equals(current.hiresToken) && account.hiresExpiresAt == current.hiresExpiresAt)) {
            return;
        }
        session = withHiRes(current, account);
        synchronized (preferences) {
            preferences.edit().putString("session", session.toJson()).apply();
        }
    }

    /** Failed Hi-Res exchanges are not retried for a while (e.g. backend not configured). */
    private long hiresRetryAt;

    /**
     * A valid Hi-Res token, deriving one through the backend for brokered device logins, or "" when
     * none is available (playback then falls back to the main session).
     */
    private synchronized String hiresAccess(boolean force) {
        Session current = session;
        long now = System.currentTimeMillis() / 1000;
        if (!force && !current.hiresToken.isEmpty() && current.hiresExpiresAt > now + 60) return current.hiresToken;
        if (!current.brokered || current.browserAuth || current.refreshToken.isEmpty() || now < hiresRetryAt) {
            return current.hiresExpiresAt > now ? current.hiresToken : "";
        }
        try {
            Session renewed = DeviceLogin.hires(current);
            synchronized (preferences) {
                JSONObject stored = new JSONObject(preferences.getString("session", "{}"));
                if (current.token.equals(stored.optString("token"))) {
                    preferences.edit().putString("session", renewed.toJson()).apply();
                }
            }
            session = renewed;
            return renewed.hiresToken;
        } catch (Exception failed) {
            hiresRetryAt = now + 600;
            return current.hiresExpiresAt > now ? current.hiresToken : "";
        }
    }

    private static Session withHiRes(Session primary, Session capability) throws Exception {
        JSONObject merged = new JSONObject(primary.toJson());
        merged.put("hires_token", capability.hiresToken).put("hires_expires_at", capability.hiresExpiresAt);
        return new Session(merged.toString());
    }

    private synchronized String accessToken(String rejected) throws Exception {
        Session current = session;
        if (!current.renewable()) return current.token;
        boolean expiresSoon = current.expiresAt > 0
                && current.expiresAt <= System.currentTimeMillis() / 1000 + 60;
        if (!expiresSoon && !current.token.equals(rejected)) return current.token;
        DeviceLogin login = current.brokered ? DeviceLogin.brokered()
                : new DeviceLogin(current.clientId, current.clientSecret);
        Session renewed = current.browserAuth ? DeviceLogin.refreshBrowser(current) : login.refresh(current);
        if (hasHiResSession() && current.userId.equals(renewed.userId)
                && current.country.equalsIgnoreCase(renewed.country)) renewed = withHiRes(renewed, current);
        synchronized (preferences) {
            JSONObject stored = new JSONObject(preferences.getString("session", "{}"));
            if (current.token.equals(stored.optString("token"))) {
                preferences.edit().putString("session", renewed.toJson()).apply();
            }
        }
        session = renewed;
        return renewed.token;
    }

    Track track(long id) throws Exception {
        if (id <= 0) throw new IOException("无效的曲目编号");
        return new Track(get("tracks/" + id));
    }

    List<Track> search(String query, int offset) throws Exception {
        JSONObject data = get("search?types=TRACKS&limit=50&offset=" + offset + "&query=" + Uri.encode(query));
        return tracks(data.getJSONObject("tracks").getJSONArray("items"));
    }

    /** One horizontal row from TIDAL's home or explore page. */
    static final class HomeShelf {
        final String title;
        final List<Track> tracks;
        final List<Collection> collections;

        HomeShelf(String title, List<Track> tracks, List<Collection> collections) {
            this.title = title == null || title.isEmpty() ? "为你推荐" : title;
            this.tracks = tracks;
            this.collections = collections;
        }
    }

    /** Editorial rows such as My Mix, new albums and recommended radio. */
    List<HomeShelf> home() throws Exception {
        JSONObject body;
        try {
            body = get("pages/home?deviceType=BROWSER&locale=zh_CN");
        } catch (Exception chinese) {
            body = get("pages/home?deviceType=BROWSER&locale=en_US");
        }
        List<HomeShelf> shelves = new ArrayList<>();
        int fetches = shelvesFrom(body, shelves, 8);
        if (shelves.size() < 2 && fetches < 6) {
            try {
                shelvesFrom(get("pages/explore?deviceType=BROWSER&locale=en_US"), shelves, 8);
            } catch (Exception ignored) {
                // The home rows already loaded stay visible.
            }
        }
        return shelves;
    }

    private int shelvesFrom(JSONObject page, List<HomeShelf> shelves, int limit) throws Exception {
        JSONArray rows = page.optJSONArray("rows");
        int fetches = 0;
        if (rows == null) return fetches;
        for (int r = 0; r < rows.length() && shelves.size() < limit; r++) {
            JSONObject row = rows.optJSONObject(r);
            JSONArray modules = row == null ? null : row.optJSONArray("modules");
            if (modules == null) continue;
            for (int m = 0; m < modules.length() && shelves.size() < limit; m++) {
                JSONObject module = modules.optJSONObject(m);
                if (module == null) continue;
                JSONArray items = moduleItems(module);
                if ((items == null || items.length() == 0) && fetches < 4) {
                    String path = dataPath(module);
                    if (path != null) {
                        fetches++;
                        JSONObject more = get(path + (path.contains("?") ? "&" : "?")
                                + "deviceType=BROWSER&limit=12&offset=0");
                        items = more.optJSONArray("items");
                        if (items == null) {
                            JSONObject paged = more.optJSONObject("pagedList");
                            items = paged == null ? null : paged.optJSONArray("items");
                        }
                    }
                }
                HomeShelf shelf = readShelf(module.optString("title"), module.optString("type"), items);
                if (shelf != null) shelves.add(shelf);
            }
        }
        return fetches;
    }

    private static String dataPath(JSONObject module) {
        JSONObject paged = module.optJSONObject("pagedList");
        String path = paged == null ? "" : paged.optString("dataApiPath");
        if (path.isEmpty() || path.length() > 300 || path.contains("..")
                || path.toLowerCase(java.util.Locale.ROOT).contains("countrycode")
                || !(path.startsWith("pages/") || path.startsWith("mixes/"))) return null;
        return path;
    }

    private static JSONArray moduleItems(JSONObject module) {
        String type = module.optString("type");
        if ("HIGHLIGHT_MODULE".equals(type)) {
            JSONArray highlights = module.optJSONArray("highlights");
            JSONArray items = new JSONArray();
            if (highlights == null) return items;
            for (int i = 0; i < highlights.length(); i++) {
                JSONObject highlight = highlights.optJSONObject(i);
                if (highlight != null) items.put(highlight);
            }
            return items;
        }
        JSONObject paged = module.optJSONObject("pagedList");
        return paged == null ? null : paged.optJSONArray("items");
    }

    private static HomeShelf readShelf(String title, String type, JSONArray items) {
        if (items == null || items.length() == 0) return null;
        try {
            switch (type) {
                case "TRACK_LIST":
                    List<Track> tracks = tracks(unwrapAll(items));
                    return tracks.isEmpty() ? null : new HomeShelf(title, tracks, new ArrayList<>());
                case "ALBUM_LIST":
                    return collectionShelf(title, items, Collection.Kind.ALBUM);
                case "ARTIST_LIST":
                    return collectionShelf(title, items, Collection.Kind.ARTIST);
                case "PLAYLIST_LIST":
                    return collectionShelf(title, items, Collection.Kind.PLAYLIST);
                case "MIX_LIST":
                    return collectionShelf(title, items, Collection.Kind.MIX);
                case "MIXED_TYPES_LIST":
                case "HIGHLIGHT_MODULE":
                    return mixedShelf(title, items);
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static HomeShelf collectionShelf(String title, JSONArray items, Collection.Kind kind) throws Exception {
        List<Collection> collections = new ArrayList<>();
        int count = Math.min(items.length(), 12);
        for (int i = 0; i < count; i++) {
            JSONObject item = unwrap(items.optJSONObject(i));
            if (item == null) continue;
            try {
                collections.add(new Collection(item, kind));
            } catch (Exception ignored) {
                // Skip a row the catalogue shape does not match.
            }
        }
        return collections.isEmpty() ? null : new HomeShelf(title, new ArrayList<>(), collections);
    }

    private static HomeShelf mixedShelf(String title, JSONArray items) throws Exception {
        List<Track> tracks = new ArrayList<>();
        List<Collection> collections = new ArrayList<>();
        int count = Math.min(items.length(), 12);
        for (int i = 0; i < count; i++) {
            JSONObject raw = items.optJSONObject(i);
            if (raw == null) continue;
            String kind = raw.optString("type").toUpperCase(java.util.Locale.ROOT);
            JSONObject item = unwrap(raw);
            try {
                if ("TRACK".equals(kind)) tracks.add(new Track(item));
                else if ("ALBUM".equals(kind)) collections.add(new Collection(item, Collection.Kind.ALBUM));
                else if ("ARTIST".equals(kind)) collections.add(new Collection(item, Collection.Kind.ARTIST));
                else if ("PLAYLIST".equals(kind)) collections.add(new Collection(item, Collection.Kind.PLAYLIST));
                else if ("MIX".equals(kind)) collections.add(new Collection(item, Collection.Kind.MIX));
            } catch (Exception ignored) {
                // One unreadable item does not drop the rest of the row.
            }
        }
        if (!tracks.isEmpty() && collections.isEmpty()) return new HomeShelf(title, tracks, collections);
        if (!collections.isEmpty()) return new HomeShelf(title, new ArrayList<>(), collections);
        return null;
    }

    private static JSONArray unwrapAll(JSONArray items) throws org.json.JSONException {
        JSONArray unwrapped = new JSONArray();
        int count = Math.min(items.length(), 20);
        for (int i = 0; i < count; i++) {
            JSONObject item = unwrap(items.optJSONObject(i));
            if (item != null) unwrapped.put(item);
        }
        return unwrapped;
    }

    private static JSONObject unwrap(JSONObject item) {
        if (item == null) return null;
        JSONObject nested = item.optJSONObject("item");
        if (nested == null) nested = item.optJSONObject("data");
        return nested == null ? item : nested;
    }

    private static String imageList(JSONArray images) {
        if (images == null || images.length() == 0) return "";
        int index = images.length() > 1 ? 1 : 0;
        JSONObject image = images.optJSONObject(index);
        if (image == null) return "";
        String url = image.optString("url");
        return url.isEmpty() ? image.optString("id") : url;
    }

    private static String mixImage(JSONObject item) {
        String listed = imageList(item.optJSONArray("mixImages"));
        if (listed.isEmpty()) listed = imageList(item.optJSONArray("detailMixImages"));
        if (!listed.isEmpty()) return listed;
        JSONObject images = item.optJSONObject("images");
        if (images != null) {
            for (String key : new String[]{"MEDIUM", "SMALL", "LARGE"}) {
                JSONObject size = images.optJSONObject(key);
                if (size == null) continue;
                String url = size.optString("url");
                if (!url.isEmpty()) return url;
                String id = size.optString("id");
                if (!id.isEmpty()) return id;
            }
        }
        JSONObject graphic = item.optJSONObject("graphic");
        JSONArray pictures = graphic == null ? null : graphic.optJSONArray("images");
        if (pictures != null && pictures.length() > 0) {
            JSONObject picture = pictures.optJSONObject(0);
            if (picture != null && !picture.optString("id").isEmpty()) return picture.optString("id");
        }
        String square = item.optString("squareImage");
        return square.isEmpty() ? item.optString("image", item.optString("cover")) : square;
    }

    List<Track> favorites(int offset) throws Exception {
        JSONObject data = get("users/" + user()
                + "/favorites/tracks?order=DATE&orderDirection=DESC&limit=50&offset=" + offset);
        return tracks(data.getJSONArray("items"));
    }

    List<Collection> collections(String term, Collection.Kind kind, int offset, boolean own) throws Exception {
        String type;
        switch (kind) {
            case ALBUM: type = "albums"; break;
            case ARTIST: type = "artists"; break;
            case MIX: throw new IOException("混音请从首页打开");
            default: type = "playlists";
        }
        String page = "?limit=40&offset=" + offset;
        JSONObject data;
        if (own) {
            data = get("users/" + user() + "/playlists" + page);
        } else if (term.isEmpty()) {
            data = get("users/" + user() + "/favorites/" + type + page + "&order=DATE&orderDirection=DESC");
        } else {
            data = get("search" + page + "&types=" + type.toUpperCase(java.util.Locale.ROOT)
                    + "&query=" + Uri.encode(term)).getJSONObject(type);
        }
        return readCollections(data, kind);
    }

    List<Collection> artistAlbums(String id) throws Exception {
        return readCollections(get("artists/" + Uri.encode(id) + "/albums?limit=30"), Collection.Kind.ALBUM);
    }

    Collection artist(String id) throws Exception {
        return new Collection(get("artists/" + Uri.encode(id)), Collection.Kind.ARTIST);
    }

    String artistBiography(String id) throws Exception {
        JSONObject bio = get("artists/" + Uri.encode(id) + "/bio");
        return bio.optString("text", bio.optString("summary")).trim();
    }

    List<Collection> artistReleases(String id, boolean singles) throws Exception {
        String path = "artists/" + Uri.encode(id) + "/albums?limit=50"
                + (singles ? "&filter=EPSANDSINGLES" : "");
        List<Collection> releases = new ArrayList<>();
        for (int offset = 0; offset < 300 && !Thread.currentThread().isInterrupted(); offset += 50) {
            List<Collection> page = readCollections(get(path + "&offset=" + offset), Collection.Kind.ALBUM);
            releases.addAll(page);
            if (page.size() < 50) break;
        }
        return releases;
    }

    List<Collection> similarArtists(String id) throws Exception {
        return readCollections(get("artists/" + Uri.encode(id) + "/similar?limit=12"), Collection.Kind.ARTIST);
    }

    static final class DownloadAudio {
        final Uri uri;
        final String extension;
        final String resolution;
        final byte[] dash;

        DownloadAudio(Uri uri, String extension, String resolution, byte[] dash) {
            this.uri = uri;
            this.extension = extension;
            this.resolution = resolution;
            this.dash = dash;
        }
    }

    static final class DownloadAccessDenied extends IOException {
        final boolean sessionDenied;

        DownloadAccessDenied(int status, IOException cause) {
            super("TIDAL 拒绝了当前会话的完整音源请求，请确认账号和曲目权限。", cause);
            sessionDenied = status == 401;
        }
    }

    DownloadAudio downloadAudio(long id, String quality) throws Exception {
        JSONObject data;
        try {
            String path = "tracks/" + id
                    + "/playbackinfopostpaywall?playbackmode=STREAM&assetpresentation=FULL&audioquality=" + quality;
            String hiresToken = losslessRequest(quality) ? hiresAccess(false) : "";
            if (!hiresToken.isEmpty()) {
                try {
                    data = new JSONObject(request("GET", path, null, hiresToken));
                } catch (HttpError error) {
                    if (error.status != 401 && error.status != 403) throw error;
                    data = get(path);
                }
            } else data = get(path);
        } catch (HttpError error) {
            if (error.status == 401 || error.status == 403) {
                throw new DownloadAccessDenied(error.status, error);
            }
            if (error.status == 400 || error.status == 404) {
                throw new DownloadTasks.QualityUnavailable(error.getMessage());
            }
            throw error;
        }
        return decodeDownload(data, quality);
    }

    private static boolean losslessRequest(String quality) {
        return "LOSSLESS".equals(quality) || "HI_RES_LOSSLESS".equals(quality);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static DownloadAudio decodeDownload(JSONObject data, String quality) throws Exception {
        if (!"FULL".equalsIgnoreCase(data.optString("assetPresentation"))) {
            throw new IOException("TIDAL 未返回完整音源");
        }
        String actual = data.optString("audioQuality");
        boolean max = "HI_RES_LOSSLESS".equals(quality);
        int depth = data.optInt("bitDepth");
        int rate = data.optInt("sampleRate");
        boolean hiRes = "HI_RES_LOSSLESS".equals(actual)
                || (("LOSSLESS".equals(actual) || actual.isEmpty()) && (depth > 16 || rate > 48000));
        if (max && !hiRes) {
            throw new DownloadTasks.QualityUnavailable("该会话只返回 " + (actual.isEmpty() ? "未知音质" : actual)
                    + "，无法确认 Max。可选择 High 或检查 TIDAL 会话");
        }
        if ("application/dash+xml".equals(data.optString("manifestMimeType"))) {
            if (losslessRequest(quality) && !actual.isEmpty() && !losslessRequest(actual)) {
                throw new DownloadTasks.QualityUnavailable("TIDAL 返回 " + actual + "，未满足所选音质（High 或 Max）");
            }
            byte[] dash = Base64.decode(data.getString("manifest"), Base64.DEFAULT);
            DashFlacDownload.select(dash);
            String resolution = hiRes ? "Max · HiRes FLAC" : "High · FLAC";
            if (depth > 0 && rate > 0) resolution += " · " + depth + " bit / " + (rate / 1000.0) + " kHz";
            return new DownloadAudio(null, "flac", resolution, dash);
        }
        if (!"application/vnd.tidal.bts".equals(data.optString("manifestMimeType"))) {
            throw new DownloadTasks.QualityUnavailable("该曲目使用分段音源，暂不能保存为单个文件");
        }
        JSONObject manifest = new JSONObject(new String(Base64.decode(data.getString("manifest"), Base64.DEFAULT),
                StandardCharsets.UTF_8));
        if (!"NONE".equals(manifest.optString("encryptionType"))) {
            throw new DownloadTasks.QualityUnavailable("该曲目返回的是加密音源，无法保存为可播放文件");
        }
        String mime = manifest.optString("mimeType");
        String extension;
        switch (mime) {
            case "audio/flac":
            case "audio/x-flac": extension = "flac"; break;
            case "audio/mp4": extension = "m4a"; break;
            case "audio/aac": extension = "aac"; break;
            case "audio/mpeg": extension = "mp3"; break;
            default: throw new DownloadTasks.QualityUnavailable("暂不支持此下载音频格式");
        }
        Uri uri = Uri.parse(manifest.getJSONArray("urls").getString(0));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("无效的下载地址");
        }
        if (losslessRequest(quality) && (!"flac".equals(extension)
                || (!actual.isEmpty() && !losslessRequest(actual)))) {
            throw new DownloadTasks.QualityUnavailable("TIDAL 返回 " + (actual.isEmpty() ? extension : actual + " / " + extension)
                    + "，未满足所选音质（High 或 Max）");
        }
        String resolution = hiRes ? "Max · HiRes FLAC"
                : "flac".equals(extension) ? "High · FLAC" : actual;
        if (depth > 0 && rate > 0) resolution += " · " + depth + " bit / " + (rate / 1000.0) + " kHz";
        return new DownloadAudio(uri, extension, resolution, null);
    }

    private static List<Collection> readCollections(JSONObject data, Collection.Kind kind) throws Exception {
        JSONArray items = data.getJSONArray("items");
        List<Collection> collections = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject favorite = item.optJSONObject("item");
            collections.add(new Collection(favorite == null ? item : favorite, kind));
        }
        return collections;
    }

    List<Track> collectionTracks(Collection collection, int offset) throws Exception {
        String path;
        switch (collection.kind) {
            case ALBUM: path = "albums/" + Uri.encode(collection.id) + "/tracks"; break;
            case ARTIST: path = "artists/" + Uri.encode(collection.id) + "/toptracks"; break;
            case MIX: path = "mixes/" + Uri.encode(collection.id) + "/items"; break;
            default: path = "playlists/" + Uri.encode(collection.id) + "/tracks"; break;
        }
        JSONArray items = get(path + "?limit=50&offset=" + offset).getJSONArray("items");
        if (collection.kind == Collection.Kind.ALBUM && artwork(collection.cover, 160) != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject release = item.optJSONObject("album");
                if (release == null) {
                    release = new JSONObject().put("id", collection.id).put("title", collection.title);
                    item.put("album", release);
                }
                if (release.isNull("cover")) release.put("cover", collection.cover);
            }
        }
        return tracks(items);
    }

    Collection downloadAlbum(String id) throws Exception {
        return new Collection(get("albums/" + Uri.encode(id)), Collection.Kind.ALBUM);
    }

    List<Track> albumTracks(Collection album) throws Exception {
        int offset = 0;
        java.util.Map<Long, Track> tracks = new java.util.LinkedHashMap<>();
        while (!Thread.currentThread().isInterrupted()) {
            List<Track> page = collectionTracks(album, offset);
            int previousSize = tracks.size();
            for (Track track : page) {
                if (!tracks.containsKey(track.id)) tracks.put(track.id, track);
            }
            if (page.size() < 50) return new ArrayList<>(tracks.values());
            if (tracks.size() == previousSize) throw new IOException("TIDAL 返回了重复的专辑曲目页");
            offset += page.size();
        }
        throw new InterruptedException();
    }

    JSONObject lyrics(long id) throws Exception {
        return get("tracks/" + id + "/lyrics");
    }

    List<Track> radio(long id) throws Exception {
        return tracks(get("tracks/" + id + "/radio?limit=50").getJSONArray("items"));
    }

    void favorite(long id, boolean add) throws Exception {
        String path = "users/" + user() + "/favorites/tracks";
        request(add ? "POST" : "DELETE", add ? path : path + "/" + id,
                add ? "trackIds=" + id + "&onArtifactNotFound=FAIL" : null);
    }

    private static List<Track> tracks(JSONArray items) throws Exception {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject favorite = item.optJSONObject("item");
            tracks.add(new Track(favorite == null ? item : favorite));
        }
        return tracks;
    }

    Stream stream(long id, String quality) throws Exception {
        String path = "tracks/" + id + "/playbackinfopostpaywall?playbackmode=STREAM&assetpresentation=FULL";
        String hiresToken = hiresAccess(false);
        boolean hires = !hiresToken.isEmpty();
        JSONObject data;
        String streamPath = path + "&audioquality=" + quality;
        try {
            if (hires) {
                try {
                    data = new JSONObject(request("GET", streamPath, null, hiresToken));
                } catch (HttpError rejected) {
                    if (rejected.status != 401) throw rejected;
                    String retry = hiresAccess(true);
                    if (retry.isEmpty() || retry.equals(hiresToken)) throw rejected;
                    data = new JSONObject(request("GET", streamPath, null, retry));
                }
            } else data = get(streamPath);
        } catch (HttpError error) {
            if (error.status != 400 && error.status != 401 && error.status != 403 && error.status != 404) throw error;
            String fallback = "HI_RES_LOSSLESS".equals(quality) ? "LOSSLESS" : quality;
            if (fallback.equals(quality) && !hires) throw error;
            quality = fallback;
            data = get(path + "&audioquality=" + quality);
        }
        byte[] manifest = Base64.decode(data.getString("manifest"), Base64.DEFAULT);
        String body = new String(manifest, StandardCharsets.UTF_8);
        switch (data.getString("manifestMimeType")) {
            case "application/vnd.tidal.bts":
                JSONObject bts = new JSONObject(body);
                if (!"NONE".equals(bts.optString("encryptionType", "NONE"))) {
                    throw new IOException("该音频流已加密");
                }
                Uri uri = Uri.parse(bts.getJSONArray("urls").getString(0));
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw new IOException("无效的音频地址");
                }
                return new Stream(uri, null, data, quality);
            case "application/dash+xml":
                if (body.contains("ContentProtection")) {
                    throw new IOException("该音频流已加密");
                }
                return new Stream(null, manifest, data, quality);
            default:
                throw new IOException("暂不支持该音频流格式");
        }
    }

    private JSONObject get(String path) throws Exception {
        return new JSONObject(request("GET", path, null));
    }

    private String request(String method, String path, String form) throws Exception {
        if (proxy != null) return proxy.request(method, path, form);
        String token = accessToken(null);
        try {
            return request(method, path, form, token);
        } catch (HttpError error) {
            if (error.status != 401 || !session.renewable()) throw error;
            return request(method, path, form, accessToken(token));
        }
    }

    private static final class HttpError extends IOException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private String request(String method, String path, String form, String token) throws Exception {
        URL url = new URL(API + path + (path.contains("?") ? "&" : "?") + "countryCode=" + session.country);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (form != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status == 401) {
                throw new HttpError(status, "TIDAL 会话已失效，请重新登录");
            }
            if (status == 404) {
                throw new HttpError(status, "当前地区或曲目暂无此内容");
            }
            if (status == 403) {
                throw new HttpError(status, "当前 TIDAL 账号无权访问此内容");
            }
            if (status < 200 || status >= 300) {
                throw new HttpError(status, "TIDAL HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return "GET".equals(method) ? readText(input, MAX_RESPONSE) : "";
            }
        } finally {
            connection.disconnect();
        }
    }

    static String readText(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) {
                throw new IOException("文件或响应过大");
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
