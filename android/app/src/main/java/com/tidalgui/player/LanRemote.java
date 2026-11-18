package com.tidalgui.player;

import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@androidx.media3.common.util.UnstableApi
final class LanRemote implements AutoCloseable {
    private final PlaybackService playback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SharedPreferences settings;
    private final String key;
    private final String code;
    private static final long RETRY_WINDOW = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_ATTEMPTS = 5;
    private final String host;
    private final byte[] page;
    private final byte[] script;
    private final Thread acceptor;
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback networkWatch;
    private final Network network;
    private final int subnetMask;
    private final java.util.Set<Socket> active = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final long FILE_LIMIT = 200L * 1024 * 1024;

    LanRemote(PlaybackService playback, SharedPreferences settings) throws IOException {
        this.playback = playback;
        this.settings = settings;
        connectivity = playback.getSystemService(ConnectivityManager.class);
        InetAddress address = null;
        Network selected = null;
        int prefix = 0;
        for (Network candidate : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
            LinkProperties properties = connectivity.getLinkProperties(candidate);
            if (caps == null || properties == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                InetAddress local = link.getAddress();
                if (local instanceof Inet4Address && local.isSiteLocalAddress() && link.getPrefixLength() > 0) {
                    address = local;
                    selected = candidate;
                    prefix = link.getPrefixLength();
                    break;
                }
            }
            if (address != null) break;
        }
        if (address == null) throw new IOException("请先连接有局域网 IPv4 地址的 Wi-Fi 或以太网");
        network = selected;
        subnetMask = -1 << (32 - prefix);
        if (!settings.contains("code") || !settings.contains("key")) refreshCredentials(settings);
        code = settings.getString("code", "");
        key = settings.getString("key", "");
        try (java.io.InputStream input = playback.getAssets().open("remote/index.html")) {
            page = TidalClient.readText(input, 65536).getBytes(StandardCharsets.UTF_8);
        }
        try (java.io.InputStream input = playback.getAssets().open("remote/app.js")) {
            script = TidalClient.readText(input, 65536).getBytes(StandardCharsets.UTF_8);
        }
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address, settings.getInt("port", 0)), 12);
        } catch (IOException error) {
            server.close();
            throw error;
        }
        host = address.getHostAddress() + ":" + server.getLocalPort();
        networkWatch = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network lost) {
                if (network.equals(lost)) main.post(() -> playback.disconnectRemote(LanRemote.this));
            }

            @Override public void onLinkPropertiesChanged(Network changed, LinkProperties properties) {
                if (!network.equals(changed)) return;
                for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                    if (server.getInetAddress().equals(link.getAddress())) return;
                }
                main.post(() -> playback.disconnectRemote(LanRemote.this));
            }
        };
        android.net.NetworkRequest localNetwork = new android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build();
        try {
            connectivity.registerNetworkCallback(localNetwork, networkWatch);
        } catch (RuntimeException error) {
            server.close();
            throw error;
        }
        settings.edit().putInt("port", server.getLocalPort()).commit();
        acceptor = new Thread(this::listen, "tidal-lan-remote");
        acceptor.start();
    }

    String link() {
        return address() + "#" + code;
    }

    String address() {
        return "http://" + host + "/";
    }

    String code() {
        return code;
    }

    static void refreshCredentials(SharedPreferences settings) {
        String previous = settings.getString("code", "");
        String next;
        do {
            next = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        } while (next.equals(previous));
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        settings.edit().putString("code", next)
                .putString("key", Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP))
                .remove("failed_pairs").remove("pair_window").commit();
    }

    private synchronized boolean acceptCode(String candidate) {
        if (server.isClosed()) return false;
        long now = System.currentTimeMillis();
        long start = settings.getLong("pair_window", 0);
        int failed = now >= start && now - start < RETRY_WINDOW ? settings.getInt("failed_pairs", 0) : 0;
        if (failed >= MAX_ATTEMPTS) return false;
        if (MessageDigest.isEqual(bytes(code), bytes(candidate))) {
            settings.edit().remove("failed_pairs").remove("pair_window").commit();
            return true;
        }
        settings.edit().putInt("failed_pairs", failed + 1)
                .putLong("pair_window", failed == 0 ? now : start).commit();
        return false;
    }

    private void listen() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                try {
                    active.add(socket);
                    workers.execute(() -> {
                        try { serve(socket); }
                        finally { active.remove(socket); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    active.remove(socket);
                    socket.close();
                }
            } catch (IOException closed) {
                if (!server.isClosed()) main.post(() -> playback.disconnectRemote(this));
                return;
            }
        }
    }

    @android.annotation.SuppressLint("ResourceType") // The packaged WebP drawable is served as its original bytes.
    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(2500);
            BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
            long deadline = android.os.SystemClock.elapsedRealtime() + 3500;
            String[] request = line(in, deadline, 2048).split(" ", -1);
            if (request.length != 3 || !"HTTP/1.1".equals(request[2])) return;
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; ; i++) {
                String header = line(in, deadline, 16 * 1024);
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (i >= 32 || colon <= 0) return;
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(name, header.substring(colon + 1).trim()) != null) return;
            }
            InetAddress peer = socket.getInetAddress();
            boolean privatePeer = peer instanceof Inet4Address && peer.isSiteLocalAddress();
            boolean sameSubnet = privatePeer && ((ipv4(peer) ^ ipv4(server.getInetAddress())) & subnetMask) == 0;
            if (!privatePeer || (!sameSubnet && !playback.remoteRoutedLan)) {
                respond(connection, 403, "text/plain; charset=utf-8",
                        bytes("此设备来自另一个网段。请连接手机所在子网，或在手机局域网遥控设置中允许其他私有网段。"));
                return;
            }
            if (!host.equals(headers.get("host")) || headers.containsKey("transfer-encoding")) {
                respond(connection, 403, "text/plain", bytes("Invalid host or request"));
                return;
            }
            String origin = headers.get("origin");
            if (origin != null && !origin.equals("http://" + host)) {
                respond(connection, 403, "text/plain", bytes("Invalid origin"));
                return;
            }
            if ("GET".equals(request[0]) && "/".equals(request[1])) {
                respond(connection, 200, "text/html; charset=utf-8", page);
                return;
            }
            if ("GET".equals(request[0]) && "/room.webp".equals(request[1])) {
                try (java.io.InputStream room = playback.getResources().openRawResource(R.drawable.listening_room)) {
                    java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int count; (count = room.read(buffer)) != -1;) data.write(buffer, 0, count);
                    respond(connection, 200, "image/webp", data.toByteArray());
                }
                return;
            }
            if ("GET".equals(request[0]) && "/app.js".equals(request[1])) {
                respond(connection, 200, "text/javascript; charset=utf-8", script);
                return;
            }
            if ("POST".equals(request[0]) && "/api/pair".equals(request[1])) {
                JSONObject payload = readBody(headers, in, deadline, 4096);
                if (!acceptCode(payload.optString("code"))) {
                    respond(connection, 401, "application/json; charset=utf-8",
                            bytes("{\"error\":\"配对码无效或尝试过多，请核对手机上的认证码，稍后重试\"}"));
                    return;
                }
                respond(connection, 200, "application/json; charset=utf-8",
                        bytes(new JSONObject().put("key", key).toString()));
                return;
            }
            String auth = headers.get("authorization");
            if (auth == null || !MessageDigest.isEqual(bytes("Bearer " + key), bytes(auth))) {
                respond(connection, 401, "application/json", bytes("{\"error\":\"配对链接无效或已过期\"}"));
                return;
            }

            try {
                if ("POST".equals(request[0]) && "/api/upload".equals(request[1])) {
                    requireFiles();
                    if (!"application/octet-stream".equals(headers.get("content-type"))) throw new IOException("文件请求格式无效");
                    String length = headers.get("content-length");
                    long size = length == null ? 0 : Long.parseLong(length);
                    String raw = headers.get("x-file-name");
                    if (raw == null) throw new IOException("缺少文件名");
                    String name = java.net.URLDecoder.decode(raw, "UTF-8");
                    connection.setSoTimeout(15000);
                    java.io.InputStream checked = new java.io.FilterInputStream(in) {
                        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                            requireFiles();
                            return super.read(buffer, offset, length);
                        }
                    };
                    String folder = decodeHeader(headers, "x-upload-folder");
                    String directory = decodeHeader(headers, "x-upload-directory");
                    String policy = decodeHeader(headers, "x-upload-conflict");
                    LocalLibrary.UploadResult result;
                    synchronized (playback.localLibrary.fileOperations) {
                        requireFiles();
                        result = playback.localLibrary.upload(folder, directory, name,
                                policy.isEmpty() ? "prompt" : policy, checked, size);
                        if (result.previousId != null) main.post(() -> playback.fileRenamed(result.previousId, result.track));
                        else if (result.track != null) main.post(playback::libraryChanged);
                    }
                    JSONObject payload = result.track == null ? new JSONObject() : fileInfo(result.track);
                    payload.put("status", result.status);
                    respond(connection, 200, "application/json; charset=utf-8", bytes(payload.toString()));
                    return;
                }
                if ("GET".equals(request[0]) && request[1].startsWith("/api/file/")) {
                    requireFiles();
                    download(connection, Long.parseLong(request[1].substring("/api/file/".length())));
                    return;
                }
                if ("POST".equals(request[0]) && "/api/attachment".equals(request[1])) {
                    requireFiles();
                    JSONObject input = readBody(headers, in, deadline, 16 * 1024);
                    download(connection, attachment(input), null);
                    return;
                }
                JSONObject response;
                if ("GET".equals(request[0]) && "/api/state".equals(request[1])) {
                    response = onMain(playback::remoteSnapshot);
                } else if ("POST".equals(request[0]) && "/api/playlist-import".equals(request[1])) {
                    requireFiles();
                    JSONObject playlist = readBody(headers, in, deadline, 512 * 1024);
                    String name = playlist.getString("name").trim();
                    LocalMusicFiles.Playlist parsed = LocalMusicFiles.importM3u(
                            playlist.getString("text"), null, playback.localLibrary.search("", null));
                    requireFiles();
                    if (parsed.ids.isEmpty()) throw new IOException("歌单没有匹配到已入库的音频");
                    String saved = playback.localLibrary.importPlaylist(name, parsed.ids);
                    main.post(playback::libraryChanged);
                    response = new JSONObject().put("name", saved).put("missing", parsed.missing);
                } else if ("POST".equals(request[0]) && "/api/control".equals(request[1])) {
                    response = command(readBody(headers, in, deadline, 16 * 1024));
                } else {
                    respond(connection, 404, "text/plain", bytes("Not found"));
                    return;
                }
                respond(connection, 200, "application/json; charset=utf-8", bytes(response.toString()));
            } catch (Exception error) {
                String message = error instanceof IOException ? error.getMessage() : "遥控请求失败";
                JSONObject failure = new JSONObject().put("error", message == null ? "遥控请求失败" : message);
                respond(connection, 400, "application/json; charset=utf-8", bytes(failure.toString()));
            }
        } catch (Exception ignored) {
            // Disconnected clients and malformed requests cannot affect playback.
        }
    }

    private static String decodeHeader(Map<String, String> headers, String name) throws IOException {
        String value = headers.get(name);
        return value == null ? "" : java.net.URLDecoder.decode(value, "UTF-8");
    }

    private static JSONObject readBody(Map<String, String> headers, BufferedInputStream in, long deadline, int limit)
            throws Exception {
        String length = headers.get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        if (size <= 0 || size > limit || !"application/json".equals(headers.get("content-type"))) {
            throw new IOException("请求格式无效");
        }
        byte[] body = new byte[size];
        int offset = 0;
        while (offset < size) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) throw new EOFException();
            int got = in.read(body, offset, size - offset);
            if (got < 0) throw new EOFException();
            offset += got;
        }
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    private JSONObject command(JSONObject input) throws Exception {
        String operation = input.getString("op");
        if ("library".equals(operation)) return RemoteLibrary.execute(playback, this::onMain, input);
        if ("library-export".equals(operation)) {
            requireFiles();
            String name = input.getString("playlist");
            if (!playback.localLibrary.playlists().contains(name)) throw new IOException("请先选择歌单");
            return new JSONObject().put("text", LocalMusicFiles.exportM3u(playback.localLibrary.search("", name)));
        }
        if ("library-scan".equals(operation)) {
            requireFiles();
            JSONObject result = RemoteLibrary.scan(playback);
            main.post(playback::libraryChanged);
            return result;
        }
        if ("files".equals(operation) || operation.startsWith("file-")) {
            requireFiles();
            if ("files".equals(operation)) {
                org.json.JSONArray files = new org.json.JSONArray();
                if (!input.optBoolean("rootsOnly")) {
                    for (TidalClient.Track track : playback.localLibrary.managedTracks()) files.put(fileInfo(track));
                }
                org.json.JSONArray folders = new org.json.JSONArray();
                for (android.net.Uri root : playback.localLibrary.folders()) {
                    folders.put(new JSONObject().put("id", root.toString())
                            .put("name", android.provider.DocumentsContract.getTreeDocumentId(root)));
                }
                return new JSONObject().put("files", files).put("folders", folders);
            }
            synchronized (playback.localLibrary.fileOperations) {
                requireFiles();
                return fileCommand(operation, input);
            }
        }
        if ("search".equals(operation) || "add".equals(operation)) {
            TidalClient account = onMain(() -> playback.client);
            if (account == null) throw new IOException("请先登录 TIDAL");
            if ("search".equals(operation)) {
                String query = input.getString("query").trim();
                if (query.isEmpty() || query.length() > 160) throw new IOException("请输入 1–160 个字符");
                org.json.JSONArray results = new org.json.JSONArray();
                for (TidalClient.Track track : account.search(query, 0)) {
                    results.put(PlaybackService.remoteTrack(track));
                }
                return new JSONObject().put("tracks", results);
            }
            TidalClient.Track track = account.track(input.getLong("id"));
            return onMain(() -> {
                if (playback.client != account) throw new IOException("账号已切换，请重新搜索");
                playback.enqueue(track, input.optBoolean("next"));
                return playback.remoteSnapshot();
            });
        }
        return onMain(() -> {
            playback.remoteControl(operation, input);
            return playback.remoteSnapshot();
        });
    }

    private JSONObject fileCommand(String operation, JSONObject input) throws Exception {
        LocalLibrary library = playback.localLibrary;
        if ("file-directory".equals(operation)) {
            return library.fileDirectory(input.getString("root"), input.getString("path"));
        }
        if ("file-upload-check".equals(operation)) {
            return new JSONObject().put("conflict", library.uploadConflict(
                    input.getString("root"), input.getString("path"), input.getString("name")));
        }
        if ("file-delete-directory".equals(operation) && input.optBoolean("confirmed")) {
            java.util.Set<Long> removed = library.deleteDirectory(input.getString("root"),
                    input.getString("path"), input.getString("directoryId"));
            main.post(() -> playback.filesDeleted(removed));
            return new JSONObject().put("ok", true);
        }
        if ("file-delete-attachment".equals(operation) && input.optBoolean("confirmed")) {
            if (!android.provider.DocumentsContract.deleteDocument(playback.getContentResolver(), attachment(input))) {
                throw new IOException("无法删除附件");
            }
            return new JSONObject().put("ok", true);
        }
        long id = input.getLong("id");
        if ("file-delete".equals(operation) && input.optBoolean("confirmed")) {
            library.deleteFile(id);
            main.post(() -> playback.fileDeleted(id));
            return new JSONObject().put("ok", true);
        }
        if ("file-rename".equals(operation)) {
            TidalClient.Track replacement = library.renameFile(id, input.getString("name"));
            main.post(() -> playback.fileRenamed(id, replacement));
            return fileInfo(replacement);
        }
        throw new IOException("文件操作无效");
    }

    private void requireFiles() throws IOException {
        if (server.isClosed() || !playback.remoteFiles) throw new IOException("请先在手机允许远程文件管理");
    }

    private static JSONObject fileInfo(TidalClient.Track track) throws org.json.JSONException {
        String file = android.provider.DocumentsContract.getDocumentId(track.localUri);
        String folder = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(track.localRoot));
        String relative = file.startsWith(folder + "/") ? file.substring(folder.length() + 1) : "";
        return PlaybackService.remoteTrack(track).put("name", track.payload.optString("file_name", track.title))
                .put("folder", folder).put("root", track.localRoot).put("relativePath", relative);
    }

    private void download(Socket connection, long id) throws Exception {
        TidalClient.Track track = playback.localLibrary.managed(id);
        download(connection, track.localUri, id);
    }

    private android.net.Uri attachment(JSONObject input) throws Exception {
        return playback.localLibrary.attachment(input.getString("root"), input.getString("path"), input.getString("name"));
    }

    private void download(Socket connection, android.net.Uri uri, Long id) throws Exception {
        android.content.ContentResolver resolver = playback.getContentResolver();
        try (android.content.res.AssetFileDescriptor file = resolver.openAssetFileDescriptor(uri, "r")) {
            if (file == null) throw new IOException("无法打开文件");
            long size = file.getLength();
            if (size > FILE_LIMIT) throw new IOException("文件超过 200 MB");
            connection.getOutputStream().write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream"
                    + "\r\nContent-Disposition: attachment\r\nCache-Control: no-store"
                    + "\r\nX-Content-Type-Options: nosniff\r\nConnection: close"
                    + (size >= 0 ? "\r\nContent-Length: " + size : "") + "\r\n\r\n"));
            try (java.io.InputStream in = file.createInputStream()) {
                byte[] buffer = new byte[65536];
                long written = 0;
                for (int count; (count = in.read(buffer)) >= 0; ) {
                    if (!playback.remoteFiles || written + count > FILE_LIMIT) return;
                    if (id != null) playback.localLibrary.managed(id);
                    connection.getOutputStream().write(buffer, 0, count);
                    written += count;
                }
            } catch (IOException ignored) {
                // A partial transfer is incomplete; do not add a second HTTP response.
            }
        }
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> {
            if (server.isClosed()) throw new IOException("遥控已关闭");
            return action.call();
        });
        main.post(task);
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw error;
        } finally {
            task.cancel(false);
        }
    }

    private static String line(BufferedInputStream in, long deadline, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < limit && android.os.SystemClock.elapsedRealtime() <= deadline) {
            int value = in.read();
            if (value == '\r') {
                if (in.read() != '\n') throw new IOException("Malformed request");
                return line.toString("US-ASCII");
            }
            if (value < 32 || value > 126) throw new IOException("Malformed request");
            line.write(value);
        }
        throw new IOException("Request line too long");
    }

    private static int ipv4(InetAddress address) {
        return java.nio.ByteBuffer.wrap(address.getAddress()).getInt();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(Socket socket, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : "Bad Request";
        socket.getOutputStream().write(bytes("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
                + "\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY"
                + "\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; connect-src 'self'; img-src 'self' https://resources.tidal.com; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'\r\n\r\n"));
        socket.getOutputStream().write(body);
    }

    @Override public synchronized void close() {
        connectivity.unregisterNetworkCallback(networkWatch);
        try { server.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        synchronized (active) {
            for (Socket socket : active) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
