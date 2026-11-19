package com.tidalgui.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class WebDavClient {
    private static final String PREFS_NAME = "webdav_servers";
    private static final String KEY_SERVERS = "servers";
    private static final MediaType XML_MEDIA_TYPE = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_XML = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
            + "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    static final class Server {
        final String id;
        String name;
        String url;
        String user;
        String pass;

        Server(String id, String name, String url, String user, String pass) {
            this.id = id != null && !id.isEmpty() ? id : UUID.randomUUID().toString();
            this.name = name != null && !name.isEmpty() ? name : "WebDAV";
            this.url = normalizeBaseUrl(url);
            this.user = user != null ? user.trim() : "";
            this.pass = pass != null ? pass : "";
        }

        Server(JSONObject json) {
            this(json.optString("id"), json.optString("name"), json.optString("url"),
                    json.optString("user"), json.optString("pass"));
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("name", name);
                json.put("url", url);
                json.put("user", user);
                json.put("pass", pass);
            } catch (Exception ignored) {}
            return json;
        }

        String authHeader() {
            if (user == null || user.isEmpty()) return "";
            return okhttp3.Credentials.basic(user, pass, StandardCharsets.UTF_8);
        }

        private static String normalizeBaseUrl(String raw) {
            if (raw == null) return "";
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) return "";
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                trimmed = "http://" + trimmed;
            }
            if (!trimmed.endsWith("/")) {
                trimmed += "/";
            }
            return trimmed;
        }
    }

    static final class Item {
        final String name;
        final String path;
        final String url;
        final boolean isDirectory;
        final long size;
        final boolean isAudio;

        Item(String name, String path, String url, boolean isDirectory, long size) {
            this.name = name;
            this.path = path;
            this.url = url;
            this.isDirectory = isDirectory;
            this.size = size;
            this.isAudio = !isDirectory && isAudioFile(name);
        }
    }

    interface ProgressListener {
        void onProgress(long transferred, long total);
    }

    static boolean isAudioFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.matches("(?s).+\\.(flac|wav|dsf|dff|ape|mp3|m4a|aac|ogg|opus|alac|aif|aiff|wma|wv)");
    }

    static List<Server> loadServers(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<Server> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                list.add(new Server(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    static void saveServers(Context context, List<Server> servers) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray array = new JSONArray();
        for (Server s : servers) {
            array.put(s.toJson());
        }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply();
    }

    static void saveServer(Context context, Server server) {
        List<Server> servers = loadServers(context);
        boolean found = false;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(server.id)) {
                servers.set(i, server);
                found = true;
                break;
            }
        }
        if (!found) servers.add(server);
        saveServers(context, servers);
    }

    static void deleteServer(Context context, String serverId) {
        List<Server> servers = loadServers(context);
        List<Server> remaining = new ArrayList<>();
        for (Server s : servers) {
            if (!s.id.equals(serverId)) remaining.add(s);
        }
        saveServers(context, remaining);
    }

    static void testConnection(Server server) throws IOException {
        if (server.url == null || server.url.isEmpty()) throw new IOException("服务器地址不能为空");
        Request.Builder builder = new Request.Builder()
                .url(server.url)
                .method("PROPFIND", RequestBody.create(PROPFIND_XML, XML_MEDIA_TYPE))
                .header("Depth", "0");
        String auth = server.authHeader();
        if (!auth.isEmpty()) builder.header("Authorization", auth);

        try (Response response = HTTP.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 200 || code == 207) return;
            if (code == 401) throw new IOException("认证失败，请检查用户名或密码");
            if (code == 404) throw new IOException("服务器路径不存在");
            throw new IOException("连接失败 (HTTP " + code + ")");
        }
    }

    static List<Item> list(Server server, String subPath) throws Exception {
        String targetUrl = resolveUrl(server.url, subPath);
        Request.Builder builder = new Request.Builder()
                .url(targetUrl)
                .method("PROPFIND", RequestBody.create(PROPFIND_XML, XML_MEDIA_TYPE))
                .header("Depth", "1");
        String auth = server.authHeader();
        if (!auth.isEmpty()) builder.header("Authorization", auth);

        try (Response response = HTTP.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 401) throw new IOException("认证失败，请检查用户名或密码");
            if (code != 200 && code != 207) throw new IOException("读取目录失败 (HTTP " + code + ")");
            String body = response.body() != null ? response.body().string() : "";
            return parseMultiStatus(server, targetUrl, body);
        }
    }

    static List<Item> parseMultiStatus(Server server, String targetUrl, String xml) throws Exception {
        List<Item> items = new ArrayList<>();
        URI baseUri = new URI(server.url);
        URI requestUri = new URI(targetUrl);
        String requestPath = requestUri.getPath();
        if (!requestPath.endsWith("/")) requestPath += "/";

        XmlPullParser parser;
        try {
            parser = Xml.newPullParser();
        } catch (Throwable t) {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            parser = factory.newPullParser();
        }
        parser.setInput(new StringReader(xml));

        int eventType = parser.getEventType();
        String currentHref = null;
        String currentDisplayName = null;
        boolean currentIsDir = false;
        long currentSize = 0;
        boolean hasSuccessStatus = false;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG) {
                String localName = localTag(tagName);
                if ("response".equals(localName)) {
                    currentHref = null;
                    currentDisplayName = null;
                    currentIsDir = false;
                    currentSize = 0;
                    hasSuccessStatus = false;
                } else if ("href".equals(localName)) {
                    currentHref = parser.nextText().trim();
                } else if ("collection".equals(localName)) {
                    currentIsDir = true;
                } else if ("displayname".equals(localName)) {
                    currentDisplayName = parser.nextText().trim();
                } else if ("getcontentlength".equals(localName)) {
                    try {
                        currentSize = Long.parseLong(parser.nextText().trim());
                    } catch (Exception ignored) {}
                } else if ("status".equals(localName)) {
                    String statusLine = parser.nextText().trim();
                    String[] parts = statusLine.split(" ");
                    if (parts.length >= 2) {
                        try {
                            int code = Integer.parseInt(parts[1]);
                            if (code >= 200 && code < 300) hasSuccessStatus = true;
                        } catch (Exception ignored) {}
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                String localName = localTag(tagName);
                if ("response".equals(localName) && currentHref != null && hasSuccessStatus) {
                    URI itemUri = baseUri.resolve(currentHref);
                    String itemPath = itemUri.getPath();
                    if (itemPath == null) itemPath = "";

                    // Normalize to compare with requestPath (filter out self-node)
                    String normalizedItemPath = itemPath.endsWith("/") ? itemPath : itemPath + "/";
                    if (!normalizedItemPath.equalsIgnoreCase(requestPath)) {
                        String name = currentDisplayName;
                        if (name == null || name.isEmpty()) {
                            String p = itemPath.endsWith("/") ? itemPath.substring(0, itemPath.length() - 1) : itemPath;
                            int lastSlash = p.lastIndexOf('/');
                            name = lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
                            try {
                                name = URLDecoder.decode(name, "UTF-8");
                            } catch (Exception ignored) {}
                        }
                        if (!name.isEmpty()) {
                            items.add(new Item(name, itemPath, itemUri.toString(), currentIsDir, currentSize));
                        }
                    }
                }
            }
            eventType = parser.next();
        }

        Collections.sort(items, (a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        return items;
    }

    private static String localTag(String tag) {
        if (tag == null) return "";
        int colon = tag.lastIndexOf(':');
        return (colon >= 0 ? tag.substring(colon + 1) : tag).toLowerCase(Locale.ROOT);
    }

    static String resolveUrl(String baseUrl, String subPath) {
        if (subPath == null || subPath.isEmpty() || "/".equals(subPath)) {
            return baseUrl;
        }
        if (subPath.startsWith("http://") || subPath.startsWith("https://")) {
            return subPath;
        }
        try {
            URI base = new URI(baseUrl);
            return base.resolve(subPath).toString();
        } catch (Exception ignored) {}
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cleanSub = subPath.startsWith("/") ? subPath : "/" + subPath;
        return cleanBase + cleanSub;
    }

    static TidalClient.Track toTrack(Server server, Item item, String albumName) throws Exception {
        String identity = "webdav:" + server.id + ":" + item.url;
        long id = trackId(identity);
        String title = stripExtension(item.name);
        String album = (albumName != null && !albumName.isEmpty()) ? albumName : server.name;

        JSONObject payload = new JSONObject();
        payload.put("id", id);
        payload.put("title", title);
        payload.put("artist", new JSONObject().put("name", server.name));
        payload.put("album", new JSONObject().put("title", album));
        payload.put("webdav_url", item.url);
        payload.put("webdav_auth", server.authHeader());
        payload.put("file_name", item.name);
        payload.put("file_size", item.size);

        return new TidalClient.Track(payload);
    }

    static long trackId(String identity) throws Exception {
        long bits = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).getLong();
        return -1 - (bits & ((1L << 52) - 1));
    }

    static String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static void downloadFile(Context context, Server server, Item item, Uri targetFolderUri,
                             ProgressListener listener) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String mime = getMimeType(item.name);
        Uri doc = DocumentsContract.createDocument(resolver, targetFolderUri, mime, item.name);
        if (doc == null) throw new IOException("无法在目标目录创建文件");

        Request.Builder builder = new Request.Builder().url(item.url);
        String auth = server.authHeader();
        if (!auth.isEmpty()) builder.header("Authorization", auth);

        try (Response response = HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                DocumentsContract.deleteDocument(resolver, doc);
                throw new IOException("下载失败 (HTTP " + response.code() + ")");
            }
            long total = response.body() != null ? response.body().contentLength() : item.size;
            try (InputStream in = response.body().byteStream();
                 OutputStream out = resolver.openOutputStream(doc, "w")) {
                if (out == null) {
                    DocumentsContract.deleteDocument(resolver, doc);
                    throw new IOException("无法写入文件");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                long transferred = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    transferred += read;
                    if (listener != null) listener.onProgress(transferred, total);
                }
                out.flush();
            }
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(resolver, doc); } catch (Exception ignored) {}
            throw e;
        }
    }

    static Uri findOrCreateDirectory(Context context, Uri root, String dirName) {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root));
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root));
        try (android.database.Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (dirName.equalsIgnoreCase(name) && DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        return DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            Uri created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, dirName);
            return created != null ? created : parent;
        } catch (Exception e) {
            return parent;
        }
    }

    private static String getMimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".lrc")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private WebDavClient() {}
}
