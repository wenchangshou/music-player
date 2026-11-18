package com.tidalgui.player;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class PlayerAccount {
    static final String ORIGIN = BuildConfig.TIDAL_RELAY_ORIGIN;

    static String requireOrigin() throws IOException {
        if (ORIGIN.isEmpty()) throw new IOException("此安装包未配置中继地址");
        return ORIGIN;
    }

    static JSONObject login(String username, String password) throws Exception {
        String model = android.os.Build.MODEL.trim();
        String name = model.isEmpty() ? "Android 播放器" : model.substring(0, Math.min(80, model.length()));
        return request("/account/login", new JSONObject().put("username", username)
                .put("password", password).put("platform", "android").put("name", name), null);
    }

    static void logout(String key) throws Exception {
        request("/account/logout", new JSONObject(), key);
    }

    private static JSONObject request(String path, JSONObject body, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(requireOrigin() + path).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (key != null) connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setDoOutput(true);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(data);
            }
            int status = connection.getResponseCode();
            if (status == 401 && key != null) return new JSONObject();
            if (status != 200) {
                try (InputStream in = connection.getErrorStream()) {
                    if (in != null) {
                        JSONObject error = new JSONObject(TidalClient.readText(in, 4096));
                        throw new IOException(error.optString("error", "登录请求失败"));
                    }
                }
                throw new IOException("账号服务 HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(TidalClient.readText(in, 4096));
            }
        } finally {
            connection.disconnect();
        }
    }
}
