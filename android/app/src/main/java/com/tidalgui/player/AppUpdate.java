package com.tidalgui.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

final class AppUpdate {
    private static final long MAX_BYTES = 150 * 1024 * 1024;

    static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static HttpURLConnection connect(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(PlayerAccount.requireOrigin() + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        return connection;
    }

    static JSONObject latest() throws Exception {
        HttpURLConnection connection = connect("/updates/android/latest.json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("更新服务器 HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                JSONObject manifest = new JSONObject(TidalClient.readText(input, 65536));
                if (manifest.optLong("versionCode") <= 0) throw new IOException("更新清单无效");
                return manifest;
            }
        } finally {
            connection.disconnect();
        }
    }

    static File download(Context context, JSONObject manifest) throws Exception {
        long expected = manifest.getLong("size");
        long version = manifest.getLong("versionCode");
        String digest = manifest.getString("sha256");
        if (expected <= 0 || expected > MAX_BYTES || !digest.matches("[a-f0-9]{64}")) {
            throw new IOException("后台更新数据无效");
        }
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
        File[] obsolete = directory.listFiles((dir, name) -> name.startsWith("tidal-") && name.endsWith(".apk"));
        if (obsolete != null) {
            long cutoff = System.currentTimeMillis() - 86400000;
            for (File previous : obsolete) {
                if (previous.lastModified() < cutoff) previous.delete();
            }
        }
        File file = File.createTempFile("tidal-", ".apk", directory);
        HttpURLConnection connection = connect("/updates/android/apk?version=" + version + "&sha256=" + digest);
        boolean verified = false;
        try {
            int status = connection.getResponseCode();
            if (status == 409) throw new IOException("服务器已发布更新，请重新检查版本");
            if (status != 200) throw new IOException("APK 下载失败（HTTP " + status + "）");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[32768];
                long bytes = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    bytes += read;
                    if (bytes > expected) throw new IOException("APK 大小不匹配");
                    sha.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                if (bytes != expected) throw new IOException("APK 下载不完整");
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : sha.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (!digest.contentEquals(actual)) throw new IOException("APK SHA-256 校验失败");

            validateUpgrade(context, file, version);
            verified = true;
            return file;
        } finally {
            connection.disconnect();
            if (!verified) file.delete();
        }
    }

    private static void validateUpgrade(Context context, File file, long expectedVersion) throws Exception {
        PackageManager packages = context.getPackageManager();
        String name = context.getPackageName();
        PackageInfo installed = packages.getPackageInfo(name, 0);
        if (expectedVersion <= version(installed)) {
            throw new IOException("当前版本已更新，请重新检查");
        }

        // Read identity separately. Some Android builds do not expose archive signing information.
        PackageInfo archive = packages.getPackageArchiveInfo(file.toString(), 0);
        if (archive == null) throw new IOException("系统无法读取下载 APK 的包信息");
        if (!name.equals(archive.packageName)) throw new IOException("下载 APK 的应用包名与当前应用不同");
        if (version(archive) != expectedVersion) {
            throw new IOException("下载 APK 的版本与服务器清单不同，请重新检查");
        }

        Signature[] installedSigners = signingInfo(packages, name, null);
        Signature[] archiveSigners = signingInfo(packages, name, file);
        if (installedSigners.length > 0 && archiveSigners.length > 0
                && !new HashSet<>(Arrays.asList(installedSigners)).equals(new HashSet<>(Arrays.asList(archiveSigners)))) {
            throw new IOException("当前应用和更新包的签名不同，需要与当前应用同签名的安装包");
        }
        // The system installer always verifies upgrade signatures, including when readback is unavailable.
    }

    private static Signature[] signingInfo(PackageManager packages, String name, File file) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo modern = querySignatures(packages, name, file, PackageManager.GET_SIGNING_CERTIFICATES);
                if (modern != null && modern.signingInfo != null) {
                    Signature[] signers = modern.signingInfo.getApkContentsSigners();
                    if (signers != null && signers.length > 0) return signers;
                }
            }
            PackageInfo legacy = querySignatures(packages, name, file, PackageManager.GET_SIGNATURES);
            if (legacy != null && legacy.signatures != null) return legacy.signatures;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Package identity was checked independently.
        }
        return new Signature[0];
    }

    private static PackageInfo querySignatures(PackageManager packages, String name, File file, int flags)
            throws PackageManager.NameNotFoundException {
        return file == null ? packages.getPackageInfo(name, flags)
                : packages.getPackageArchiveInfo(file.toString(), flags);
    }
}
