package com.tidalgui.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote control of the other players signed in to the same player account.
 * While a device is selected, the mini player, Now Playing and the queue show and control it.
 * All public members are used on the main thread; network calls run on one worker thread, so
 * commands reach the device in the order they were made.
 */
@androidx.media3.common.util.UnstableApi
final class RemoteDevices {
    static final class Device {
        String id = "", name = "", platform = "", version = "";
        boolean online, self;
        String nowTitle = "", nowArtist = "", nowCover = "";
        boolean hasNow, nowPlaying;
        /** Seconds since the device last reached the relay, or -1 if not since the relay started. */
        long lastSeenAgo = -1;

        boolean android() { return "android".equals(platform); }

        String lastSeenLabel() {
            if (lastSeenAgo < 0) return "最近未连接";
            if (lastSeenAgo < 60) return "刚刚在线";
            if (lastSeenAgo < 3600) return lastSeenAgo / 60 + " 分钟前在线";
            if (lastSeenAgo < 86400) return lastSeenAgo / 3600 + " 小时前在线";
            return lastSeenAgo / 86400 + " 天前在线";
        }
        String platformLabel() { return android() ? "Android" : "Linux 桌面"; }
    }

    static final class Track {
        String title = "", artist = "", album = "", cover = "";
        /** TIDAL track id, or 0 for local and NetEase tracks, which only play on their device. */
        long tidalId;
        /** Where the audio comes from: "TIDAL", "网易云" or "本地音乐". */
        String source = "";
        /** The id the device knows this track by (negative for its local files). */
        long id;
    }

    static final class State {
        final List<Track> queue = new ArrayList<>();
        Object revision = JSONObject.NULL;
        int index = -1;
        boolean playing, loading, seekable, shuffle;
        /** 0 = off, 1 = queue, 2 = single track (the relay's convention). */
        int repeat;
        long positionMs, durationMs;
        long received = SystemClock.elapsedRealtime();
        String error = "", quality = "";
        /** Media volume 0–100, or -1 when the device does not report one. */
        int volume = -1;
        /** Quality the device asks for, e.g. "LOSSLESS"; empty when unknown. */
        String requested = "";
        /** Storage path of the current track when it is a local file on the device. */
        String sourcePath = "";
        /** 网易云 level the device asks for, e.g. "jymaster"; empty when unknown. */
        String requestedNetease = "";

        Track current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }

        long position() {
            long position = positionMs;
            if (playing && !loading) position += SystemClock.elapsedRealtime() - received;
            return durationMs > 0 ? Math.min(position, durationMs) : position;
        }

        boolean hasNext() { return (index >= 0 && index + 1 < queue.size()) || repeat != 0 || shuffle; }

        void predict(JSONObject command) {
            String op = command.optString("op");
            long now = position();
            if ("toggle".equals(op)) {
                positionMs = now;
                received = SystemClock.elapsedRealtime();
                playing = !playing;
            } else if ("seek".equals(op)) {
                positionMs = command.optLong("ms", now);
                received = SystemClock.elapsedRealtime();
            } else if ("shuffle".equals(op)) shuffle = !shuffle;
            else if ("repeat".equals(op)) repeat = (repeat + 1) % 3;
            else if ("volume".equals(op)) volume = command.optInt("value", volume);
            else if ("quality".equals(op)) {
                String value = command.optString("value", requested);
                if (value.startsWith("netease:")) requestedNetease = value.substring(8);
                else requested = value;
            }
            else if ("clear".equals(op) && index >= 0 && index + 1 < queue.size()) {
                queue.subList(index + 1, queue.size()).clear();
            }
        }

        static State parse(JSONObject value) {
            JSONArray items = value == null ? null : value.optJSONArray("queue");
            if (items == null) return null;
            State state = new State();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                Track track = new Track();
                track.title = item.optString("title");
                track.artist = item.optString("artist");
                track.album = item.optString("album");
                String cover = item.optString("cover");
                track.cover = cover.startsWith("https://") ? cover : "";
                long id = item.optLong("id");
                track.id = id;
                String kind = item.optString("kind", "本地音乐".equals(item.optString("source")) ? "local" : "tidal");
                track.tidalId = id > 0 && "tidal".equals(kind) && !NeteaseClient.owns(id) ? id : 0;
                track.source = item.optString("source", "local".equals(kind) ? "本地音乐" : "netease".equals(kind) ? "网易云" : "TIDAL");
                state.queue.add(track);
            }
            state.revision = value.opt("revision") == null ? JSONObject.NULL : value.opt("revision");
            state.index = value.isNull("index") ? -1 : value.optInt("index", -1);
            state.playing = value.optBoolean("playing");
            state.loading = value.optBoolean("loading");
            state.positionMs = value.optLong("position");
            state.durationMs = value.optLong("duration");
            state.seekable = value.optBoolean("seekable");
            state.shuffle = value.optBoolean("shuffle");
            state.repeat = Math.max(0, Math.min(2, value.optInt("repeat")));
            state.error = value.isNull("error") ? "" : value.optString("error");
            state.requested = value.optString("requestedQuality");
            state.sourcePath = value.optString("sourcePath");
            state.requestedNetease = value.optString("requestedNetease");
            state.volume = value.has("volume") ? Math.max(0, Math.min(100, (int) Math.round(value.optDouble("volume")))) : -1;
            String actual = value.optString("actualQuality");
            // NetEase devices report "网易云 · 超清母带" etc. already.
            String quality = actual.startsWith("网易云") ? actual : qualityLabel(actual);
            String resolution = value.optString("resolution");
            state.quality = quality.isEmpty() ? "" : resolution.isEmpty() ? quality : quality + " · " + resolution;
            return state;
        }

        private static String qualityLabel(String quality) {
            switch (quality) {
                case "HI_RES_LOSSLESS": return "Max";
                case "LOSSLESS": return "High";
                case "HIGH": return "Low";
                case "LOW": return "Low 96k";
                default: return "";
            }
        }
    }

    private final String origin;
    private final String key;
    private final Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayDeque<JSONObject> commands = new ArrayDeque<>();
    private boolean running, commandBusy, stateBusy, devicesBusy, closed;
    private long lastDevices, lastState;
    private int generation;

    List<Device> devices = Collections.emptyList();
    boolean devicesLoaded;
    String devicesError = "";
    String target;
    State state;
    String error = "";
    /** Last command failure, shown once by the activity. */
    String notice = "";

    RemoteDevices(String origin, String key, Runnable listener) {
        this.origin = origin;
        this.key = key;
        this.listener = listener;
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running || closed) return;
            long now = SystemClock.elapsedRealtime();
            if (!devicesBusy && now - lastDevices >= (target != null ? 3000 : 6000)) fetchDevices();
            if (target != null) {
                if (!commandBusy && !stateBusy && !commands.isEmpty()) dispatch();
                else if (!commandBusy && !stateBusy && commands.isEmpty() && now - lastState >= 1000) fetchState();
            }
            main.postDelayed(this, 250);
        }
    };

    /** Poll while the activity is visible. */
    void resume() {
        if (closed || running) return;
        running = true;
        lastDevices = 0;
        main.post(loop);
    }

    void pause() {
        running = false;
        main.removeCallbacks(loop);
    }

    void close() {
        closed = true;
        pause();
        network.shutdownNow();
    }

    void refreshSoon() { lastDevices = 0; }

    boolean active() { return target != null; }

    Device targetDevice() {
        for (Device device : devices) if (device.id.equals(target)) return device;
        return null;
    }

    String targetName() {
        Device device = targetDevice();
        return device == null ? "远程设备" : device.name;
    }

    void select(String id) {
        if (id != null && id.equals(target) || id == null && target == null) return;
        target = id;
        state = null;
        error = "";
        commands.clear();
        generation++;
        commandBusy = false;
        stateBusy = false;
        lastState = 0;
        listener.run();
    }

    int pending() { return commands.size() + (commandBusy ? 1 : 0); }

    boolean send(JSONObject command) {
        if (target == null || pending() >= 60) return false;
        if (state != null) state.predict(command);
        commands.add(command);
        listener.run();
        return true;
    }

    boolean send(String op) {
        try {
            return send(new JSONObject().put("op", op));
        } catch (org.json.JSONException impossible) {
            return false;
        }
    }

    private void fetchDevices() {
        devicesBusy = true;
        lastDevices = SystemClock.elapsedRealtime();
        network.execute(() -> {
            List<Device> result = new ArrayList<>();
            String problem = "";
            try {
                JSONArray items = request("GET", "/api/devices", null).optJSONArray("devices");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    Device device = new Device();
                    device.id = item.optString("id");
                    device.name = item.optString("name");
                    device.platform = item.optString("platform");
                    device.version = item.optString("version");
                    device.online = item.optBoolean("online");
                    device.self = item.optBoolean("self");
                    device.lastSeenAgo = item.isNull("lastSeenAgo") ? -1 : item.optLong("lastSeenAgo", -1);
                    JSONObject now = item.optJSONObject("nowPlaying");
                    if (now != null) {
                        device.hasNow = true;
                        device.nowTitle = now.optString("title");
                        device.nowArtist = now.optString("artist");
                        String cover = now.optString("cover");
                        device.nowCover = cover.startsWith("https://") ? cover : "";
                        device.nowPlaying = now.optBoolean("playing");
                    }
                    result.add(device);
                }
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            String message = problem;
            main.post(() -> {
                devicesBusy = false;
                if (closed) return;
                if (message.isEmpty()) {
                    devices = result;
                    devicesLoaded = true;
                    devicesError = "";
                    Device selected = targetDevice();
                    if (target != null && (selected == null || !selected.online)) {
                        notice = targetName() + " 已离线，已切回本机";
                        select(null);
                    }
                } else devicesError = message;
                listener.run();
            });
        });
    }

    private void fetchState() {
        stateBusy = true;
        lastState = SystemClock.elapsedRealtime();
        int expected = generation;
        String device = target;
        network.execute(() -> {
            State result = null;
            String problem = "";
            try {
                result = State.parse(request("GET", "/api/state?device=" + encode(device), null));
                if (result == null) problem = "设备状态无效";
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            State parsed = result;
            String message = problem;
            main.post(() -> {
                if (closed || expected != generation) return;
                stateBusy = false;
                if (parsed != null) {
                    state = parsed;
                    error = "";
                } else error = message;
                listener.run();
            });
        });
    }

    private void dispatch() {
        JSONObject command = commands.poll();
        if (command == null) return;
        commandBusy = true;
        int expected = generation;
        String device = target;
        JSONObject body = command;
        network.execute(() -> {
            State result = null;
            String problem = "";
            try {
                result = control(device, body);
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            State parsed = result;
            String message = problem;
            main.post(() -> {
                if (closed || expected != generation) return;
                commandBusy = false;
                lastState = SystemClock.elapsedRealtime();
                if (parsed != null) state = parsed;
                if (!message.isEmpty()) {
                    notice = message;
                    lastState = 0;
                }
                listener.run();
            });
        });
    }

    private State control(String device, JSONObject command) throws Exception {
        JSONObject result = controlRaw(device, command);
        return result == null ? null : State.parse(result);
    }

    interface Reply { void done(JSONObject result, String error); }

    /** Ask the controlled device something, e.g. a page of its music library, and get its raw reply. */
    void query(JSONObject command, Reply reply) {
        if (target == null) return;
        int expected = generation;
        String device = target;
        network.execute(() -> {
            JSONObject result = null;
            String problem = "";
            try {
                result = controlRaw(device, command);
                if (result == null) problem = "设备没有返回内容";
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            JSONObject answer = result;
            String message = problem;
            main.post(() -> {
                if (!closed && expected == generation) reply.done(answer, message);
            });
        });
    }

    private JSONObject controlRaw(String device, JSONObject command) throws Exception {
        String suffix = "?device=" + encode(device);
        String id = request("POST", "/api/control" + suffix, command).optString("command");
        if (id.isEmpty()) throw new IOException("后台未返回命令编号");
        // wait=1 lets the relay hold the request until the device answers; older relays answer at
        // once without "held", so pause between requests for them.
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject receipt = request("GET", "/api/receipt" + suffix + "&command=" + encode(id) + "&wait=1", null);
            if (receipt.optBoolean("pending")) {
                if (!receipt.optBoolean("held")) Thread.sleep(500);
                continue;
            }
            JSONObject result = receipt.optJSONObject("result");
            if (result == null) return null;
            String failure = result.optString("error");
            if (!failure.isEmpty()) throw new IOException(failure);
            return result;
        }
        throw new IOException("设备未及时响应");
    }

    /**
     * Send one command outside the queue, without waiting for the reply. Used to pause a device
     * whose playback moves here, since switching back to this phone drops queued commands.
     */
    void sendNow(String device, JSONObject command) {
        Thread sender = new Thread(() -> {
            try {
                control(device, command);
            } catch (Exception ignored) {}
        }, "remote-device-command");
        sender.setDaemon(true);
        sender.start();
    }

    /** End every other session of this player account, including web sessions. */
    void logoutOthers(Runnable done) {
        network.execute(() -> {
            String problem = "";
            try {
                request("POST", "/account/logout-others", new JSONObject());
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            String message = problem;
            main.post(() -> {
                if (closed) return;
                if (message.isEmpty()) {
                    select(null);
                    List<Device> remaining = new ArrayList<>();
                    for (Device device : devices) if (device.self) remaining.add(device);
                    devices = remaining;
                    notice = "已退出其他所有设备，它们需要重新登录播放器账号";
                } else notice = message;
                lastDevices = 0;
                done.run();
            });
        });
    }

    void remove(String device, Runnable done) {
        network.execute(() -> {
            String problem = "";
            try {
                request("POST", "/account/remove-device", new JSONObject().put("id", device));
            } catch (Exception failure) {
                problem = CentralRelay.readable(failure);
            }
            String message = problem;
            main.post(() -> {
                if (closed) return;
                if (message.isEmpty()) {
                    if (device.equals(target)) select(null);
                    List<Device> remaining = new ArrayList<>();
                    for (Device item : devices) if (!item.id.equals(device)) remaining.add(item);
                    devices = remaining;
                    notice = "设备已移除，该设备需要重新登录播放器账号";
                } else notice = message;
                lastDevices = 0;
                done.run();
            });
        });
    }

    private static String encode(String value) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int status = connection.getResponseCode();
            if (status == 401) throw new IOException("播放器账号登录已失效，请重新登录");
            if (status == 409) throw new IOException("设备离线");
            if (status != 200) {
                String message = "后台请求失败（HTTP " + status + "）";
                try (InputStream in = connection.getErrorStream()) {
                    if (in != null) message = new JSONObject(TidalClient.readText(in, 4096)).optString("error", message);
                } catch (Exception ignored) {}
                throw new IOException(message);
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(TidalClient.readText(in, 1024 * 1024));
            }
        } finally {
            connection.disconnect();
        }
    }
}
