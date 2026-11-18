package com.tidalgui.player;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@UnstableApi
final class CentralRelay implements AutoCloseable {
    private final PlaybackService playback;
    private final String origin;
    private final String id;
    private final String key;
    private final String version;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Thread loop;
    private volatile boolean closed;
    volatile String status = "正在连接…";

    private static final class SessionRejected extends IOException {
        SessionRejected() { super("播放器账号登录已失效，请重新登录"); }
    }

    static String normalizeOrigin(String input) throws IOException {
        String address = cleanInput(input);
        if (!address.contains("://") && !address.startsWith("//")) address = "https://" + address;
        try {
            URI endpoint = new URI(address);
            if (endpoint.getRawFragment() != null) {
                throw new IOException("请填后台地址，如 https://relay.example.com，设备配对密钥请填在第三栏");
            }
            String path = endpoint.getRawPath();
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || (path != null && !path.isEmpty() && !"/".equals(path))
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
                throw new IOException("请输入 HTTPS 后台地址，如 https://relay.example.com");
            }
            return "https://" + endpoint.getRawAuthority();
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("请输入 HTTPS 后台地址，如 https://relay.example.com");
        }
    }

    static String cleanInput(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && padding(input.charAt(start))) start++;
        while (end > start && padding(input.charAt(end - 1))) end--;
        return input.substring(start, end);
    }

    private static boolean padding(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || character == '`' || character == '\ufeff';
    }

    CentralRelay(PlaybackService playback, String url, String id, String key) throws Exception {
        origin = normalizeOrigin(url);
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("设备标识格式无效");
        if (!key.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("设备配对密钥格式无效");
        this.playback = playback;
        this.id = id;
        this.key = key;
        version = playback.getPackageManager().getPackageInfo(playback.getPackageName(), 0).versionName;
        loop = new Thread(this::run, "tidal-central-relay");
        loop.start();
    }

    private void run() {
        JSONObject receipt = null;
        long nextSessionSync = 0;
        while (!closed) {
            try {
                JSONObject state = onMain(this::snapshot);
                JSONObject body = new JSONObject().put("state", state);
                if (receipt != null) body.put("result", receipt);
                long now = android.os.SystemClock.elapsedRealtime();
                boolean sync = now >= nextSessionSync && onMain(playback::canSyncDesktopSession);
                if (sync) body.put("sync_desktop_session", true);
                JSONObject reply = json("/device/poll", body);
                if (sync) {
                    nextSessionSync = now + 10_000;
                    JSONObject account = reply.optJSONObject("desktop_session");
                    if (account != null) {
                        onMain(() -> {
                            playback.syncDesktopSession(account);
                            return null;
                        });
                    }
                }
                receipt = null;
                status = "已连接";
                JSONObject command = reply.optJSONObject("command");
                if (command != null) {
                    JSONObject payload;
                    try {
                        payload = execute(command);
                    } catch (Exception error) {
                        payload = new JSONObject().put("error", readable(error));
                    }
                    receipt = new JSONObject().put("id", reply.getString("id")).put("payload", payload);
                    continue;
                }
            } catch (SessionRejected rejected) {
                status = rejected.getMessage();
                main.post(() -> {
                    if (playback.relay == this) playback.disconnectPlayerAccount();
                });
                return;
            } catch (Exception error) {
                status = "连接失败 · " + readable(error);
            }
            try {
                Thread.sleep("已连接".equals(status) ? 1500 : 4500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private JSONObject execute(JSONObject command) throws Exception {
        String operation = command.getString("op");
        if ("library".equals(operation)) return RemoteLibrary.execute(playback, this::onMain, command);
        if ("search".equals(operation) || "add".equals(operation)) {
            TidalClient api = onMain(() -> playback.client);
            if (api == null) throw new IOException("请先在此设备登录 TIDAL");
            if ("search".equals(operation)) {
                String query = command.getString("query").trim();
                if (query.isEmpty() || query.length() > 160) throw new IOException("请输入 1–160 个字符");
                JSONArray tracks = new JSONArray();
                for (TidalClient.Track track : api.search(query, 0)) tracks.put(PlaybackService.remoteTrack(track));
                return new JSONObject().put("tracks", tracks);
            }
            TidalClient.Track track = api.track(command.getLong("id"));
            return onMain(() -> {
                if (playback.client != api) throw new IOException("账号已切换");
                playback.enqueue(track, command.optBoolean("next"));
                return snapshot();
            });
        }
        if ("login".equals(operation) || "cancel-login".equals(operation)) {
            throw new IOException("请在此设备登录 TIDAL");
        }
        return onMain(() -> {
            playback.remoteControl(operation, command);
            return snapshot();
        });
    }

    private JSONObject snapshot() throws Exception {
        JSONObject result = playback.remoteSnapshot();
        result.remove("login");
        result.remove("loginError");
        result.remove("loginPending");
        result.remove("remoteFiles");
        result.put("version", version);
        return result;
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> {
            if (closed) throw new IOException("后台连接已关闭");
            return action.call();
        });
        main.post(task);
        try {
            return task.get(4, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw error;
        } finally {
            task.cancel(false);
        }
    }

    JSONObject json(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = connection(path);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int code = connection.getResponseCode();
            if (code == 404 && path.endsWith("/latest")) return new JSONObject();
            if (code == 401) throw new SessionRejected();
            if (code != 200) throw new IOException("后台 HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(TidalClient.readText(in, 1024 * 1024));
            }
        } finally {
            connection.disconnect();
        }
    }

    HttpURLConnection connection(String path) throws IOException {
        if (closed) throw new IOException("后台连接已关闭");
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("X-Device-ID", id);
        return connection;
    }

    static String pair(String origin, String device, String code) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/device/pair").openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] data = new JSONObject().put("device", device).put("code", code)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            int status = connection.getResponseCode();
            if (status == 401) throw new IOException("配对码错误、过期或已用过。请在后台重新生成");
            if (status == 404) throw new IOException("设备标识不存在，或后台还不支持六位码");
            if (status != 200) throw new IOException("后台配对失败（HTTP " + status + "）");
            try (InputStream in = connection.getInputStream()) {
                String token = new JSONObject(TidalClient.readText(in, 4096)).getString("key");
                if (!token.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("后台返回了无效凭据");
                return token;
            }
        } finally {
            connection.disconnect();
        }
    }

    static String readable(Exception error) {
        return error instanceof IOException && error.getMessage() != null
                ? error.getMessage() : "设备请求失败";
    }

    @Override public void close() {
        closed = true;
        loop.interrupt();
    }
}
