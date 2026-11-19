package com.tidalgui.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

final class SmbClient {
    private static final String PREFS_NAME = "smb_servers";
    private static final String KEY_SERVERS = "servers";

    static final class Server {
        final String id;
        String name;
        String host;
        int port;
        String share;
        String user;
        String pass;
        String domain;

        Server(String id, String name, String host, int port, String share, String user, String pass, String domain) {
            this.id = id != null && !id.isEmpty() ? id : UUID.randomUUID().toString();
            this.name = name != null && !name.isEmpty() ? name : "SMB 共享";
            this.host = cleanHost(host);
            this.port = port > 0 ? port : 445;
            this.share = cleanShare(share);
            this.user = user != null ? user.trim() : "";
            this.pass = pass != null ? pass : "";
            this.domain = domain != null ? domain.trim() : "";
        }

        Server(JSONObject json) {
            this(json.optString("id"), json.optString("name"), json.optString("host"),
                    json.optInt("port", 445), json.optString("share"), json.optString("user"),
                    json.optString("pass"), json.optString("domain"));
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("name", name);
                json.put("host", host);
                json.put("port", port);
                json.put("share", share);
                json.put("user", user);
                json.put("pass", pass);
                json.put("domain", domain);
            } catch (Exception ignored) {}
            return json;
        }

        CIFSContext createContext() {
            Properties prop = new Properties();
            prop.put("jcifs.smb.client.enableSMB2", "true");
            prop.put("jcifs.smb.client.disableSMB1", "true");
            prop.put("jcifs.smb.client.responseTimeout", "10000");
            prop.put("jcifs.smb.client.soTimeout", "10000");
            prop.put("jcifs.smb.client.connTimeout", "6000");
            PropertyConfiguration cfg;
            try {
                cfg = new PropertyConfiguration(prop);
            } catch (Exception e) {
                throw new RuntimeException("Failed to configure SMB context", e);
            }
            CIFSContext base = new BaseContext(cfg);
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                    domain != null ? domain : "",
                    user != null ? user : "",
                    pass != null ? pass : "");
            return base.withCredentials(auth);
        }

        String rootUrl() {
            StringBuilder sb = new StringBuilder("smb://");
            sb.append(host);
            if (port != 445 && port > 0) {
                sb.append(":").append(port);
            }
            sb.append("/");
            if (share != null && !share.isEmpty()) {
                sb.append(share);
                if (!share.endsWith("/")) sb.append("/");
            }
            return sb.toString();
        }

        String displayAddress() {
            StringBuilder sb = new StringBuilder("smb://").append(host);
            if (port != 445 && port > 0) sb.append(":").append(port);
            if (!share.isEmpty()) sb.append("/").append(share);
            if (!user.isEmpty()) sb.append(" · ").append(user);
            return sb.toString();
        }

        private static String cleanHost(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.toLowerCase(Locale.ROOT).startsWith("smb://")) {
                s = s.substring(6);
            }
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(0, colon);
            return s.trim();
        }

        private static String cleanShare(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
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

    interface ProgressCallback {
        void onProgress(long bytesWritten, long totalBytes);
    }

    static List<Server> loadServers(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SERVERS, "[]");
        List<Server> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(new Server(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    static void saveServers(Context context, List<Server> servers) {
        JSONArray arr = new JSONArray();
        for (Server s : servers) {
            arr.put(s.toJson());
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVERS, arr.toString())
                .apply();
    }

    static Server findServer(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Server s : loadServers(context)) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    static void testConnection(Server server) throws Exception {
        CIFSContext ctx = server.createContext();
        SmbFile file = new SmbFile(server.rootUrl(), ctx);
        // Attempt to check existence or list first entry to verify credentials
        file.exists();
        file.listFiles();
    }

    static List<Item> list(Server server, String targetUrl) throws Exception {
        CIFSContext ctx = server.createContext();
        String url = (targetUrl == null || targetUrl.isEmpty() || "/".equals(targetUrl))
                ? server.rootUrl() : targetUrl;
        if (!url.endsWith("/")) url += "/";

        SmbFile dir = new SmbFile(url, ctx);
        SmbFile[] files = dir.listFiles();
        List<Item> items = new ArrayList<>();
        if (files == null) return items;

        for (SmbFile f : files) {
            String rawName = f.getName();
            // Skip self and parent
            if (rawName.equals("./") || rawName.equals("../") || rawName.equals(".") || rawName.equals("..")) {
                continue;
            }
            // Strip trailing slash for display name
            String displayName = rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName;
            // Skip IPC$ and admin shares if at root
            if (displayName.equalsIgnoreCase("IPC$") || displayName.endsWith("$")) {
                continue;
            }

            boolean isDir = false;
            long size = 0;
            try {
                isDir = f.isDirectory();
                if (!isDir) {
                    size = f.length();
                }
            } catch (Exception ignored) {}

            String itemUrl = f.getURL().toString();
            items.add(new Item(displayName, itemUrl, itemUrl, isDir, size));
        }

        Collections.sort(items, (a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        return items;
    }

    static TidalClient.Track toTrack(Server server, Item item, String albumName) throws Exception {
        String identity = "smb:" + server.id + ":" + item.url;
        long id = trackId(identity);
        String title = stripExtension(item.name);
        String album = (albumName != null && !albumName.isEmpty()) ? albumName : server.name;

        JSONObject payload = new JSONObject();
        payload.put("id", id);
        payload.put("title", title);
        payload.put("artist", new JSONObject().put("name", server.name));
        payload.put("album", new JSONObject().put("title", album));
        payload.put("smb_url", item.url);
        payload.put("smb_server_id", server.id);
        payload.put("file_name", item.name);
        payload.put("file_size", item.size);

        return new TidalClient.Track(payload);
    }

    static long trackId(String identity) throws Exception {
        long bits = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).getLong();
        // Negative ID space reserved for non-TIDAL tracks
        return bits >= 0 ? -(bits + 1) : bits;
    }

    static Uri downloadFile(Context context, Server server, Item item, Uri destFolderUri, ProgressCallback callback) throws Exception {
        CIFSContext ctx = server.createContext();
        SmbFile smbFile = new SmbFile(item.url, ctx);

        ContentResolver resolver = context.getContentResolver();
        Uri targetFileUri = null;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    destFolderUri, DocumentsContract.getDocumentId(destFolderUri));
            try (android.database.Cursor c = resolver.query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (item.name.equalsIgnoreCase(name)) {
                            targetFileUri = DocumentsContract.buildDocumentUriUsingTree(destFolderUri, c.getString(0));
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (targetFileUri == null) {
            String mime = mimeTypeFor(item.name);
            targetFileUri = DocumentsContract.createDocument(resolver, destFolderUri, mime, item.name);
        }
        if (targetFileUri == null) {
            throw new IOException("无法在存储目录中创建文件: " + item.name);
        }

        long total = item.size > 0 ? item.size : smbFile.length();
        long written = 0;
        try (InputStream in = smbFile.getInputStream();
             OutputStream out = resolver.openOutputStream(targetFileUri, "wt")) {
            if (out == null) throw new IOException("无法打开本地写入流: " + targetFileUri);
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                written += read;
                if (callback != null) callback.onProgress(written, total);
            }
            out.flush();
        }
        return targetFileUri;
    }

    static Uri findOrCreateDirectory(Context context, Uri rootTreeUri, String dirName) {
        ContentResolver resolver = context.getContentResolver();
        String rootDocId = DocumentsContract.getTreeDocumentId(rootTreeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, rootDocId);

        try (android.database.Cursor c = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    if (dirName.equalsIgnoreCase(name)) {
                        return DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, c.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Uri rootDocUri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, rootDocId);
            return DocumentsContract.createDocument(resolver, rootDocUri, DocumentsContract.Document.MIME_TYPE_DIR, dirName);
        } catch (Exception e) {
            return rootTreeUri;
        }
    }

    static boolean isAudioFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".mp3")
                || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg")
                || lower.endsWith(".dsf") || lower.endsWith(".dff") || lower.endsWith(".aiff")
                || lower.endsWith(".aif") || lower.endsWith(".ape") || lower.endsWith(".opus")
                || lower.endsWith(".wma");
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mimeTypeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".lrc")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
