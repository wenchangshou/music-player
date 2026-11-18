package com.tidalgui.player;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class DeviceLogin {
    private static final String AUTH = "https://auth.tidal.com/v1/oauth2/";
    private static final String BROKER_PATH = "/auth/tidal/";
    private static final String SCOPE = "r_usr+w_usr+w_sub";
    final String clientId;
    final String clientSecret;
    private final boolean useBroker;

    static DeviceLogin brokered() { return new DeviceLogin(); }

    private DeviceLogin() {
        clientId = "";
        clientSecret = "";
        useBroker = true;
    }

    DeviceLogin(String clientId, String clientSecret) throws IOException {
        useBroker = false;
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        if (!this.clientId.matches("[A-Za-z0-9_-]{1,100}") || this.clientSecret.isEmpty()
                || this.clientSecret.length() > 512) {
            throw new IOException("请输入已获 TIDAL 设备授权的 Client ID 和 Client Secret");
        }
    }

    static final class Challenge {
        final String deviceCode;
        final String userCode;
        final Uri verification;
        final Uri verificationPage;
        final long deadline;
        long interval;

        Challenge(JSONObject data) throws Exception {
            deviceCode = field(data, "device_code", "deviceCode");
            userCode = field(data, "user_code", "userCode");
            String page = field(data, "verification_uri", "verificationUri");
            String direct = field(data, "verification_uri_complete", "verificationUriComplete");
            long expires = data.optLong("expires_in", data.optLong("expiresIn", 0));
            if (deviceCode.isEmpty() || userCode.isEmpty() || expires <= 0 || expires > 86400) {
                throw new IOException("TIDAL 返回了无效的设备授权信息");
            }
            verificationPage = trustedVerification(page.isEmpty() ? direct : page);
            verification = direct.isEmpty() ? verificationPage : trustedVerification(direct);
            interval = Math.max(1, data.optLong("interval", 5));
            deadline = android.os.SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(expires);
        }

        private static String field(JSONObject data, String standard, String legacy) {
            if (!data.isNull(standard)) return data.optString(standard).trim();
            return data.isNull(legacy) ? "" : data.optString(legacy).trim();
        }

        private static Uri trustedVerification(String address) throws IOException {
            try {
                java.net.URI uri = new java.net.URI(address.contains("://") ? address : "https://" + address);
                String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                        || (!"link.tidal.com".equalsIgnoreCase(host) && !"login.tidal.com".equalsIgnoreCase(host)
                        && !"activate.tidal.com".equalsIgnoreCase(host))
                        || !host.equalsIgnoreCase(uri.getRawAuthority())) {
                    throw new IOException("TIDAL 返回了无效的授权地址");
                }
                return Uri.parse(uri.toASCIIString());
            } catch (java.net.URISyntaxException invalid) {
                throw new IOException("TIDAL 返回了无效的授权地址");
            }
        }
    }

    Challenge start() throws Exception {
        return new Challenge(useBroker ? broker("start", new JSONObject())
                : post("device_authorization", false, "client_id", clientId, "scope", SCOPE));
    }

    TidalClient.Session await(Challenge challenge) throws Exception {
        while (android.os.SystemClock.elapsedRealtime() < challenge.deadline) {
            long remaining = challenge.deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(remaining, TimeUnit.SECONDS.toMillis(challenge.interval)));
            if (android.os.SystemClock.elapsedRealtime() >= challenge.deadline) break;
            try {
                JSONObject token = useBroker ? broker("poll", new JSONObject().put("device_code", challenge.deviceCode))
                        : post("token", true, "client_id", clientId,
                        "device_code", challenge.deviceCode,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "scope", SCOPE);
                JSONObject user = token.getJSONObject("user");
                JSONObject session = new JSONObject().put("user_id", user.getString("userId"))
                        .put("country_code", user.getString("countryCode").toUpperCase(Locale.ROOT))
                        .put("brokered", useBroker);
                if (!useBroker) session.put("client_id", clientId).put("client_secret", clientSecret);
                return storeTokens(session, token, "");
            } catch (AuthError error) {
                switch (error.code) {
                    case "authorization_pending": break;
                    case "slow_down": challenge.interval += 5; break;
                    case "upstream_timeout": challenge.interval = Math.max(5, challenge.interval * 2); break;
                    case "access_denied": throw new IOException("你已拒绝这次 TIDAL 授权");
                    case "expired_token": throw new IOException("授权码已过期，请重新登录");
                    default:
                        throw error;
                }
            } catch (java.net.SocketTimeoutException timeout) {
                challenge.interval = Math.max(5, challenge.interval * 2);
            }
        }
        throw new IOException("授权码已过期，请重新登录");
    }

    TidalClient.Session refresh(TidalClient.Session before) throws Exception {
        JSONObject result = useBroker ? broker("refresh", new JSONObject().put("refresh_token", before.refreshToken))
                : post("token", true, "client_id", clientId,
                "refresh_token", before.refreshToken, "grant_type", "refresh_token", "scope", SCOPE);
        return storeTokens(new JSONObject(before.toJson()), result, before.refreshToken);
    }

    /** Hi-Res profile token derived from a device-login refresh token (rotated refresh token kept). */
    static TidalClient.Session hires(TidalClient.Session before) throws Exception {
        JSONObject token = brokered().broker("hires", new JSONObject().put("refresh_token", before.refreshToken));
        String refresh = token.isNull("refresh_token") ? before.refreshToken : token.getString("refresh_token");
        return new TidalClient.Session(new JSONObject(before.toJson())
                .put("refresh_token", refresh)
                .put("hires_token", token.getString("access_token"))
                .put("hires_expires_at", System.currentTimeMillis() / 1000 + token.getLong("expires_in")).toString());
    }

    static final class BrowserGrant {
        final Uri url;
        final String ticket;
        final long deadline;

        BrowserGrant(JSONObject reply) throws Exception {
            java.net.URI address = new java.net.URI(reply.getString("authorization_url"));
            if (!"https".equals(address.getScheme()) || !"login.tidal.com".equals(address.getRawAuthority())
                    || !"/authorize".equals(address.getPath()) || address.getFragment() != null) {
                throw new IOException("后台返回了无效的 TIDAL 登录网址");
            }
            url = Uri.parse(address.toASCIIString());
            ticket = reply.getString("ticket");
            long expires = reply.getLong("expires_in");
            if (!ticket.matches("[A-Za-z0-9_-]{43}") || expires <= 0 || expires > 900) {
                throw new IOException("后台返回了无效的浏览器授权信息");
            }
            deadline = android.os.SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(expires);
        }
    }

    static BrowserGrant startBrowser() throws Exception {
        return new BrowserGrant(browser("start", new JSONObject()));
    }

    static TidalClient.Session awaitBrowser(BrowserGrant grant) throws Exception {
        JSONObject request = new JSONObject().put("ticket", grant.ticket);
        long pause = 1500;
        while (android.os.SystemClock.elapsedRealtime() < grant.deadline) {
            try {
                JSONObject token = browser("claim", request);
                if (!token.optBoolean("pending")) {
                    JSONObject user = token.getJSONObject("user");
                    JSONObject saved = new JSONObject().put("user_id", user.getString("userId"))
                            .put("country_code", user.getString("countryCode")).put("brokered", true)
                            .put("browser_auth", true);
                    return storeTokens(saved, token, "");
                }
                pause = 1500;
            } catch (java.net.SocketTimeoutException timeout) {
                pause = Math.min(12000, pause * 2);
            }
            Thread.sleep(pause);
        }
        throw new IOException("浏览器授权已过期，请重新登录");
    }

    static TidalClient.Session refreshBrowser(TidalClient.Session before) throws Exception {
        JSONObject token = browser("refresh", new JSONObject().put("refresh_token", before.refreshToken));
        return storeTokens(new JSONObject(before.toJson()), token, before.refreshToken);
    }

    private static JSONObject browser(String path, JSONObject payload) throws Exception {
        return brokered().request(PlayerAccount.requireOrigin() + BROKER_PATH + "browser/" + path, "application/json",
                payload.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private static TidalClient.Session storeTokens(JSONObject session, JSONObject token, String previousRefresh)
            throws Exception {
        String refresh = token.isNull("refresh_token") ? previousRefresh : token.getString("refresh_token");
        return new TidalClient.Session(session.put("token", token.getString("access_token"))
                .put("refresh_token", refresh)
                .put("expires_at", System.currentTimeMillis() / 1000 + token.getLong("expires_in")).toString());
    }

    private static final class AuthError extends IOException {
        final String code;

        AuthError(int status, JSONObject body) {
            super(body.optString("message", status == 401 || "invalid_client".equals(body.optString("error"))
                    ? "TIDAL 拒绝了此客户端，请检查设备授权配置"
                    : "TIDAL 授权失败（HTTP " + status + "）"));
            code = body.optString("error");
        }
    }

    private JSONObject broker(String path, JSONObject payload) throws Exception {
        return request(PlayerAccount.requireOrigin() + BROKER_PATH + path, "application/json", payload.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private JSONObject post(String path, boolean authenticate, String... fields) throws Exception {
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (i > 0) form.append('&');
            form.append(fields[i]).append('=').append(java.net.URLEncoder.encode(fields[i + 1], "UTF-8"));
        }
        return request(AUTH + path, "application/x-www-form-urlencoded",
                form.toString().getBytes(StandardCharsets.UTF_8), authenticate);
    }

    private JSONObject request(String url, String type, byte[] payload, boolean authenticate) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", type);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Accept", "application/json");
            if (authenticate) {
                String pair = clientId + ":" + clientSecret;
                connection.setRequestProperty("Authorization", "Basic "
                        + Base64.encodeToString(pair.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                JSONObject body = new JSONObject();
                try (InputStream input = connection.getErrorStream()) {
                    if (input != null) body = new JSONObject(TidalClient.readText(input, 65536));
                } catch (org.json.JSONException ignored) {}
                throw new AuthError(status, body);
            }
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(TidalClient.readText(input, 65536));
            }
        } finally {
            connection.disconnect();
        }
    }
}
