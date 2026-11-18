package com.tidalgui.player;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LocalMusicFiles {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    static JSONObject lyrics(ContentResolver resolver, TidalClient.Track track) throws Exception {
        Uri uri = Uri.parse(track.payload.optString("lrc_uri"));
        if (!"content".equals(uri.getScheme()) || track.localRoot.isEmpty()) {
            throw new IOException("未找到同名 LRC。把歌词放在音频旁边，再重新扫描目录。");
        }
        String content = read(resolver, uri).trim();
        if (content.isEmpty()) throw new IOException("LRC 文件为空");
        return new JSONObject().put("subtitles", content).put("lyrics", content);
    }

    static final class Playlist {
        final List<Long> ids = new ArrayList<>();
        int missing;
    }

    static Playlist importM3u(ContentResolver resolver, Uri source, List<TidalClient.Track> library) throws IOException {
        String sourcePath = documentPath(source);
        String directory = sourcePath == null ? null : sourcePath.substring(0, sourcePath.lastIndexOf('/') + 1);
        return importM3u(read(resolver, source), directory, library);
    }

    static Playlist importM3u(String text, String directory, List<TidalClient.Track> library) throws IOException {
        Map<String, Long> paths = new HashMap<>();
        Map<String, Long> names = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (TidalClient.Track track : library) {
            paths.put(track.localUri.toString(), track.id);
            String path = documentPath(track.localUri);
            if (path != null) paths.put(path, track.id);
            String name = track.payload.optString("file_name").toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                Long previous = names.put(name, track.id);
                if (previous != null && previous != track.id) ambiguous.add(name);
            }
        }
        Playlist result = new Playlist();
        int entries = 0;
        for (String value : text.split("\\r?\\n")) {
            String line = value.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (++entries > 20000) throw new IOException("歌单超过 20,000 个条目");
            Long id = paths.get(line);
            if (id == null && !line.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                String path = line.replace('\\', '/');
                String absolute = path.startsWith("/") ? path : directory == null ? "" : directory + path;
                id = paths.get(normalizePath(absolute));
                String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (id == null && (!path.contains("/") || directory == null
                        || path.matches("(?i)^[a-z]:/.*")) && !ambiguous.contains(name)) {
                    id = names.get(name);
                }
            } else if (id == null && line.startsWith("file://")) {
                String path = Uri.parse(line).getPath();
                if (path != null) id = paths.get(normalizePath(path));
            }
            if (id == null) result.missing++;
            else result.ids.add(id);
        }
        return result;
    }

    static void exportM3u(ContentResolver resolver, Uri destination, List<TidalClient.Track> tracks) throws IOException {
        try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("无法写入歌单");
            output.write(exportM3u(tracks).getBytes(StandardCharsets.UTF_8));
        }
    }

    static String exportM3u(List<TidalClient.Track> tracks) {
        StringBuilder body = new StringBuilder("#EXTM3U\n");
        for (TidalClient.Track track : tracks) {
            String title = (track.artist.isEmpty() ? track.title : track.artist + " - " + track.title)
                    .replace('\r', ' ').replace('\n', ' ');
            body.append("#EXTINF:-1,").append(title).append('\n');
            String path = documentPath(track.localUri);
            // URI-encode filenames that would otherwise break a line-oriented playlist.
            if (path == null) body.append(track.localUri);
            else if (path.contains("\n") || path.contains("\r")) body.append(Uri.fromFile(new java.io.File(path)));
            else body.append(path);
            body.append('\n');
        }
        return body.toString();
    }

    /** Where a local file lives, as a storage path when the provider has one, for display. */
    static String displayPath(Uri uri) {
        if (uri == null) return "";
        String path = documentPath(uri);
        if (path != null && !path.isEmpty()) return path;
        try {
            String document = DocumentsContract.getDocumentId(uri);
            int colon = document.indexOf(':');
            return colon >= 0 ? document.substring(colon + 1) : document;
        } catch (IllegalArgumentException notDocument) {
            String name = uri.getLastPathSegment();
            return name == null ? uri.toString() : name;
        }
    }

    private static String documentPath(Uri uri) {
        if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        String document;
        try { document = DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException missing) { return null; }
        int colon = document.indexOf(':');
        if (colon < 1) return null;
        String volume = document.substring(0, colon);
        if (!volume.matches("[A-Za-z0-9_-]+")) return null;
        return normalizePath(("primary".equalsIgnoreCase(volume) ? "/storage/emulated/0/" : "/storage/" + volume + "/")
                + document.substring(colon + 1));
    }

    private static String normalizePath(String path) {
        if (path.startsWith("/sdcard/")) path = "/storage/emulated/0/" + path.substring(8);
        if (path.startsWith("/storage/self/primary/")) path = "/storage/emulated/0/" + path.substring(22);
        if (!path.startsWith("/")) return "";
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return "/" + android.text.TextUtils.join("/", parts);
    }

    static String read(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开文件");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (bytes.size() + count > MAX_TEXT_BYTES) throw new IOException("歌词或歌单超过 2 MB");
                bytes.write(buffer, 0, count);
            }
            byte[] data = bytes.toByteArray();
            if (data.length >= 2 && ((data[0] == (byte) 0xff && data[1] == (byte) 0xfe)
                    || (data[0] == (byte) 0xfe && data[1] == (byte) 0xff))) {
                return new String(data, StandardCharsets.UTF_16);
            }
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
                return text.startsWith("\ufeff") ? text.substring(1) : text;
            } catch (CharacterCodingException invalidUtf8) {
                return new String(data, Charset.forName("GB18030"));
            }
        }
    }
}
