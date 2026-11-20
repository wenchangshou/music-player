package com.tidalgui.player;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * OpenAI-compatible chat proxy for the paired web remote. The endpoint, model and key live only in
 * this phone's private preferences; the browser sends the conversation and never reads the key back.
 */
final class AiProxy {
    private static final String PREFS = "ai_config";
    static final int MAX_MESSAGES = 32;
    static final int MAX_CHARS = 48_000;
    static final int MAX_TOKENS = 4096;
    private static final int HOURLY_LIMIT = 200;
    private static final Semaphore SLOTS = new Semaphore(4);
    private static final ArrayDeque<Long> CALLS = new ArrayDeque<>();

    private AiProxy() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** After a network-level failure, skip AI calls for a while so offline use never waits on timeouts. */
    private static final long OFFLINE_BACKOFF_MS = 120_000;
    private static volatile long offlineUntil;

    /** Configured, the phone has a usable network, and no recent connection failure: worth calling now. */
    static boolean available(Context context) {
        return configured(context) && reachable(context);
    }

    /**
     * Whether a request could get through right now. A public endpoint needs a network Android has
     * validated as reaching the internet; a LAN endpoint (such as Ollama on a computer) any network.
     */
    static boolean reachable(Context context) {
        if (System.currentTimeMillis() < offlineUntil) return false;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        android.net.Network network = cm.getActiveNetwork();
        android.net.NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true;
        return isLanEndpoint(prefs(context).getString("endpoint", ""));
    }

    static boolean isLanEndpoint(String endpoint) {
        String host;
        try {
            host = new URL(endpoint).getHost();
        } catch (java.net.MalformedURLException error) {
            return false;
        }
        return host.equals("localhost") || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.")
                || host.startsWith("127.") || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }

    static boolean isNetworkFailure(IOException error) {
        return error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException
                || error instanceof java.net.SocketTimeoutException || error instanceof java.net.NoRouteToHostException
                || error instanceof javax.net.ssl.SSLException;
    }

    static boolean configured(Context context) {
        SharedPreferences prefs = prefs(context);
        return !prefs.getString("endpoint", "").isEmpty() && !prefs.getString("model", "").isEmpty();
    }

    /** Settings the remote may show; the key itself is never included. */
    static JSONObject status(Context context) throws JSONException {
        SharedPreferences prefs = prefs(context);
        String endpoint = prefs.getString("endpoint", "");
        String model = prefs.getString("model", "");
        return new JSONObject()
                .put("configured", !endpoint.isEmpty() && !model.isEmpty())
                .put("managed", false)
                .put("provider", prefs.getString("provider", "custom"))
                .put("endpoint", endpoint)
                .put("model", model)
                .put("hasKey", !prefs.getString("key", "").isEmpty())
                .put("online", reachable(context));
    }

    /** Saves settings. An absent or empty apiKey keeps the saved key unless clearKey is set. */
    static JSONObject save(Context context, JSONObject input) throws IOException, JSONException {
        String endpoint = input.optString("endpoint").trim().replaceAll("/+$", "");
        String model = input.optString("model").trim();
        String provider = input.optString("provider", "custom").trim();
        if (!endpoint.isEmpty() && !endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw new IOException("接口地址需以 http:// 或 https:// 开头");
        }
        if (endpoint.length() > 512 || model.length() > 128 || provider.length() > 32) {
            throw new IOException("AI 设置过长");
        }
        if (!endpoint.isEmpty()) new URL(endpoint);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString("endpoint", endpoint).putString("model", model).putString("provider", provider);
        String apiKey = input.optString("apiKey").trim();
        if (apiKey.length() > 512 || apiKey.matches(".*\\s.*")) throw new IOException("API Key 格式无效");
        if (!apiKey.isEmpty()) editor.putString("key", apiKey);
        else if (input.optBoolean("clearKey")) editor.remove("key");
        editor.apply();
        return status(context);
    }

    static String completionsUrl(String endpoint) {
        String url = endpoint.trim().replaceAll("/+$", "");
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }

    /** The upstream request: only the conversation comes from the browser. */
    static JSONObject requestBody(JSONObject input, String model, String endpoint) throws IOException, JSONException {
        JSONArray messages = input.optJSONArray("messages");
        if (messages == null || messages.length() == 0 || messages.length() > MAX_MESSAGES) {
            throw new IOException("AI 请求内容无效");
        }
        JSONArray clean = new JSONArray();
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            String role = item == null ? "" : item.optString("role");
            Object content = item == null ? null : item.opt("content");
            if (!(content instanceof String)
                    || !("system".equals(role) || "user".equals(role) || "assistant".equals(role))) {
                throw new IOException("AI 请求内容无效");
            }
            total += ((String) content).length();
            clean.put(new JSONObject().put("role", role).put("content", content));
        }
        if (total > MAX_CHARS) throw new IOException("AI 请求内容过长");
        String lower = model.toLowerCase(Locale.ROOT);
        boolean reasoner = lower.contains("reasoner") || lower.contains("r1") || lower.contains("o1") || lower.contains("o3");
        int tokens = input.optInt("max_tokens", 2048);
        JSONObject body = new JSONObject().put("model", model).put("messages", clean)
                .put("max_tokens", tokens > 0 ? Math.min(tokens, MAX_TOKENS) : 2048);
        Object temperature = input.opt("temperature");
        if (temperature instanceof Number && !reasoner) {
            body.put("temperature", Math.max(0, Math.min(1.5, ((Number) temperature).doubleValue())));
        }
        if (endpoint.contains("volces.com") && !reasoner) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        return body;
    }

    /** Assistant text of an OpenAI-compatible completion, or "". */
    static String replyText(JSONObject data) {
        JSONArray choices = data.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        if (choice == null) return "";
        JSONObject message = choice.optJSONObject("message");
        String text = "";
        if (message != null) {
            Object content = message.opt("content");
            if (content instanceof String) {
                text = ((String) content).trim();
            } else if (content instanceof JSONArray) {
                StringBuilder joined = new StringBuilder();
                JSONArray parts = (JSONArray) content;
                for (int i = 0; i < parts.length(); i++) {
                    Object part = parts.opt(i);
                    joined.append(part instanceof JSONObject ? ((JSONObject) part).optString("text") : String.valueOf(part));
                }
                text = joined.toString().trim();
            }
            if (text.isEmpty()) text = message.optString("reasoning_content").trim();
        }
        if (text.isEmpty()) text = choice.optString("text").trim();
        return text;
    }

    private static void take() throws IOException {
        long now = System.currentTimeMillis();
        synchronized (CALLS) {
            while (!CALLS.isEmpty() && now - CALLS.peekFirst() > 3_600_000L) CALLS.pollFirst();
            if (CALLS.size() >= HOURLY_LIMIT) throw new IOException("AI 调用过于频繁，请稍后再试");
            CALLS.addLast(now);
        }
    }

    static JSONObject chat(Context context, JSONObject input) throws IOException, JSONException {
        SharedPreferences prefs = prefs(context);
        String endpoint = prefs.getString("endpoint", "");
        String model = prefs.getString("model", "");
        String key = prefs.getString("key", "");
        if (endpoint.isEmpty() || model.isEmpty()) throw new IOException("尚未配置 AI 服务");
        if (!reachable(context)) throw new IOException("当前未联网，AI 功能暂不可用");
        JSONObject body = requestBody(input, model, endpoint);
        take();
        if (!SLOTS.tryAcquire()) throw new IOException("AI 服务繁忙，请稍后再试");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(completionsUrl(endpoint)).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(45_000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String raw = stream == null ? "" : read(stream, 2 * 1024 * 1024);
            if (status < 200 || status >= 300) {
                String detail = "";
                try {
                    JSONObject error = new JSONObject(raw).optJSONObject("error");
                    if (error != null) detail = " " + error.optString("message");
                } catch (JSONException ignored) {}
                throw new IOException(("AI 服务返回错误 (" + status + ")" + detail).trim());
            }
            JSONObject data;
            try {
                data = new JSONObject(raw);
            } catch (JSONException error) {
                throw new IOException("AI 服务返回格式无效");
            }
            String text = replyText(data);
            if (text.isEmpty()) throw new IOException("AI 返回内容为空");
            JSONArray choices = data.optJSONArray("choices");
            JSONObject first = choices == null ? null : choices.optJSONObject(0);
            return new JSONObject().put("content", text).put("finish", first == null ? "" : first.optString("finish_reason"));
        } catch (IOException error) {
            if (!isNetworkFailure(error)) throw error;
            offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS;
            throw new IOException(error instanceof java.net.SocketTimeoutException
                    ? "AI 请求超时，请检查网络" : "AI 服务连接失败，可能未联网");
        } finally {
            if (conn != null) conn.disconnect();
            SLOTS.release();
        }
    }

    private static String read(InputStream in, int limit) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > limit) throw new IOException("AI 返回内容过大");
            }
            return out.toString("UTF-8");
        }
    }
}
