package com.tidalgui.player;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.io.File;
import android.database.Cursor;

final class MusicDownload {
    static final String DIRECTORY = "download_directory";

    private MusicDownload() {}

    static Uri directory(Context context) {
        String saved = context.getSharedPreferences("tidal", Context.MODE_PRIVATE).getString(DIRECTORY, "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    interface Progress {
        void created(Uri document) throws IOException;
        void transferred(long bytes, long total) throws IOException;
    }

    static final class Transfer {
        final long systemId;
        final Uri document;

        Transfer(long systemId, Uri document) {
            this.systemId = systemId;
            this.document = document;
        }
    }

    static String[] location(DownloadTasks.Job job, String extension) {
        String artist = component(job.albumArtist.isEmpty() ? job.artist : job.albumArtist, "未知艺人");
        String album = job.albumTitle;
        if (!album.isEmpty() && !album.regionMatches(true, 0, artist + " - ", 0, artist.length() + 3)) {
            album = artist + " - " + album;
        }
        String number = job.number > 0 ? String.format(Locale.ROOT, "%02d. ", job.number) : "";
        if (job.volumes > 1 || job.disc > 1) number = Math.max(1, job.disc) + "-" + number;
        return new String[]{artist, component(album, "单曲"), component(number + job.title, "未知歌曲") + "." + extension};
    }

    private static String component(String value, String fallback) {
        String safe = value.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim().replaceAll("[ .]+$", "");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return fallback;
        int length = safe.length();
        while (safe.substring(0, length).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200) {
            length = safe.offsetByCodePoints(length, -1);
        }
        return safe.substring(0, length);
    }

    static Transfer save(Context context, DownloadTasks.Job job, DownloadTasks.Audio audio,
                         Uri directory, Progress progress) throws IOException {
        String extension = audio.format;
        String[] path = location(job, extension);
        // Title, artist, album, numbers, year and cover go into the file as it is written.
        AudioTags tags = new AudioTags(job, job.cover.isEmpty() ? null : coverFor(job.cover));
        if (audio.dash != null) return new Transfer(0, saveDash(context, directory, audio.dash, path, progress, tags));
        if (directory != null) {
            return new Transfer(0, saveToDirectory(context.getContentResolver(), directory, audio.uri, path, extension, progress, tags));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Saved by the app rather than the system downloader, so the file can be tagged.
            return new Transfer(0, saveToDownloads(context.getContentResolver(), audio.uri, path, extension, progress, tags));
        }
        DownloadManager.Request request = new DownloadManager.Request(audio.uri)
                .setTitle(job.title).setDescription(job.source)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String folder = path[0] + "/" + path[1];
        File base = Build.VERSION.SDK_INT >= 29
                ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                : context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File parent = new File(base, folder);
        if (Build.VERSION.SDK_INT < 29 && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建下载目录");
        }
        String name = path[2];
        for (int index = 2; new File(parent, name).exists(); index++) {
            name = path[2].substring(0, path[2].lastIndexOf('.')) + " (" + index + ")." + extension;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, folder + "/" + name);
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, folder + "/" + name);
        }
        return new Transfer(context.getSystemService(DownloadManager.class).enqueue(request), null);
    }

    /** Copy the download into {@code output} (closing it, which writes any held-back tags). */
    private static long copy(InputStream input, OutputStream output, long total, Progress progress) throws IOException {
        long bytes = 0;
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                output.write(buffer, 0, read);
                bytes += read;
                progress.transferred(bytes, total);
            }
        } finally {
            output.close();
        }
        return bytes;
    }

    /** Download into Download/艺人/专辑/ through MediaStore, tagging it on the way. */
    @android.annotation.TargetApi(29)
    private static Uri saveToDownloads(ContentResolver resolver, Uri audio, String[] path, String extension,
                                       Progress progress, AudioTags tags) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(audio.toString()).openConnection();
        Uri target = null;
        boolean complete = false;
        try {
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("下载服务器返回 HTTP " + status);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, path[2]);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + path[0] + "/" + path[1]);
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            target = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("无法在下载目录创建文件");
            progress.created(target);
            String length = connection.getHeaderField("Content-Length");
            long total = length == null ? -1 : Long.parseLong(length);
            long bytes;
            try (InputStream input = connection.getInputStream();
                 OutputStream file = resolver.openOutputStream(target, "w")) {
                if (file == null) throw new IOException("无法写入下载目录");
                bytes = copy(input, tags.wrap(file, extension), total, progress);
            }
            if (bytes == 0 || (total >= 0 && total != bytes)) throw new IOException("下载文件不完整");
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(target, done, null, null) != 1) throw new IOException("无法提交下载文件");
            complete = true;
            return target;
        } finally {
            connection.disconnect();
            if (!complete && target != null) try { resolver.delete(target, null, null); } catch (Exception ignored) {}
        }
    }

    /** Recently used covers, so an album's songs share one download of its cover. */
    private static final java.util.Map<String, byte[]> COVERS = new java.util.LinkedHashMap<String, byte[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> eldest) { return size() > 6; }
    };

    /** The cover as a 1000×1000 JPEG, or null when it cannot be fetched. */
    static byte[] coverFor(String cover) {
        synchronized (COVERS) {
            if (COVERS.containsKey(cover)) return COVERS.get(cover);
        }
        byte[] jpeg;
        try {
            jpeg = coverJpeg(cover);
        } catch (Exception unavailable) {
            jpeg = null;
        }
        synchronized (COVERS) {
            if (jpeg != null) COVERS.put(cover, jpeg);
        }
        return jpeg;
    }

    /** Extension of a saved file, from its name. */
    static String extension(Context context, Uri file) {
        String name = displayName(context.getContentResolver(), file, "");
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "flac" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final java.util.regex.Pattern SYNCED = java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d{1,4}:\\d{2}");

    /**
     * Save the album cover as cover.jpg (1000×1000 JPEG, once per album folder) and, when the
     * service has synced lyrics, a .lrc named after the song, next to the downloaded song.
     * Plain lyrics get no .lrc: invented timestamps would mislead players. Never throws.
     */
    static void saveExtras(Context context, DownloadTasks.Job job, String extension, Uri directory,
                           Uri audioDocument, DownloadTasks.Resolve resolve) {
        String[] path = location(job, extension);
        String audioName = displayName(context.getContentResolver(), audioDocument, path[2]);
        String lrcName = audioName.substring(0, Math.max(0, audioName.lastIndexOf('.'))) + ".lrc";
        // Songs of one album download in parallel: check and write the cover one at a time, so the
        // folder gets a single cover.jpg. Loose singles share a folder, so they get no cover.jpg.
        if (!job.cover.isEmpty() && !job.albumTitle.isEmpty()) synchronized (MusicDownload.class) {
            try {
                if (!exists(context, directory, path, "cover.jpg")) {
                    byte[] cover = coverFor(job.cover);
                    if (cover != null) write(context, directory, path, "cover.jpg", "image/jpeg", cover);
                }
            } catch (Exception ignored) {
                // The song is saved; a missing cover is not worth failing it for.
            }
        }
        try {
            org.json.JSONObject words = resolve == null ? null : resolve.lyrics(job.track);
            String synced = words == null || words.isNull("subtitles") ? "" : words.optString("subtitles");
            if (SYNCED.matcher(synced).find() && !exists(context, directory, path, lrcName)) {
                write(context, directory, path, lrcName, "application/octet-stream",
                        synced.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Lyrics are optional.
        }
    }

    private static String displayName(ContentResolver resolver, Uri document, String fallback) {
        if (document == null) return fallback;
        try (Cursor cursor = resolver.query(document, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (Exception ignored) {}
        return fallback;
    }

    /** The cover as a square 1000×1000 JPEG (quality 95), cropped to the centre if needed. */
    private static byte[] coverJpeg(String cover) throws Exception {
        Uri source = TidalClient.artwork(cover, 1280);
        if (source == null) return null;
        HttpURLConnection connection = (HttpURLConnection) new URL(source.toString()).openConnection();
        android.graphics.Bitmap image;
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                image = android.graphics.BitmapFactory.decodeStream(input);
            }
        } finally {
            connection.disconnect();
        }
        if (image == null) return null;
        int edge = Math.min(image.getWidth(), image.getHeight());
        android.graphics.Bitmap square = android.graphics.Bitmap.createBitmap(image,
                (image.getWidth() - edge) / 2, (image.getHeight() - edge) / 2, edge, edge);
        android.graphics.Bitmap sized = edge == 1000 ? square
                : android.graphics.Bitmap.createScaledBitmap(square, 1000, 1000, true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out);
        return out.toByteArray();
    }

    /**
     * The song's file when it is already where this download saves it (as FLAC, M4A or MP3), or
     * null. Looks only: creates no folders.
     */
    static Uri saved(Context context, DownloadTasks.Job job, Uri directory) throws IOException {
        String[] path = location(job, "flac");
        String base = path[2].substring(0, path[2].length() - ".flac".length());
        String[] names = {base + ".flac", base + ".m4a", base + ".mp3"};
        ContentResolver resolver = context.getContentResolver();
        if (directory != null) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory));
            for (int i = 0; i < 2 && parent != null; i++) parent = child(resolver, directory, parent, path[i], true);
            if (parent == null) return null;
            for (String name : names) {
                Uri file = child(resolver, directory, parent, name, false);
                if (file != null) return file;
            }
            return null;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            String relative = Environment.DIRECTORY_DOWNLOADS + "/" + path[0] + "/" + path[1] + "/";
            for (String name : names) {
                try (Cursor cursor = resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{android.provider.MediaStore.MediaColumns._ID},
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                                + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + android.provider.MediaStore.MediaColumns.IS_PENDING + "=0",
                        new String[]{relative, name}, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        return android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                    }
                }
            }
            return null;
        }
        File base29 = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base29 == null) return null;
        for (String name : names) {
            File file = new File(base29, path[0] + "/" + path[1] + "/" + name);
            if (file.isFile()) return Uri.fromFile(file);
        }
        return null;
    }

    /** A child of {@code parent} named {@code name} (a folder when {@code folder}), or null. */
    private static Uri child(ContentResolver resolver, Uri tree, Uri parent, String name, boolean folder) {
        Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(listing, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (!name.equals(cursor.getString(1))) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)) != folder) continue;
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /** Whether the album folder already has {@code name}, wherever this download is stored. */
    private static boolean exists(Context context, Uri directory, String[] path, String name) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (directory != null) {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory));
            Uri parent = folder(resolver, directory, folder(resolver, directory, root, path[0]), path[1]);
            Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(parent));
            try (Cursor cursor = resolver.query(listing, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                while (cursor != null && cursor.moveToNext()) if (name.equals(cursor.getString(0))) return true;
            }
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            String relative = Environment.DIRECTORY_DOWNLOADS + "/" + path[0] + "/" + path[1] + "/";
            try (Cursor cursor = resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relative, name}, null)) {
                return cursor != null && cursor.moveToFirst();
            }
        }
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return base != null && new File(base, path[0] + "/" + path[1] + "/" + name).exists();
    }

    /** Write {@code data} as {@code name} into the album folder of this download. */
    private static void write(Context context, Uri directory, String[] path, String name, String mime,
                              byte[] data) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (directory != null) {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory));
            Uri parent = folder(resolver, directory, folder(resolver, directory, root, path[0]), path[1]);
            Uri document = DocumentsContract.createDocument(resolver, parent, mime, name);
            if (document == null) throw new IOException("无法创建 " + name);
            try (OutputStream output = resolver.openOutputStream(document, "w")) {
                if (output == null) throw new IOException("无法写入 " + name);
                output.write(data);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + path[0] + "/" + path[1]);
            Uri target = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("无法创建 " + name);
            try (OutputStream output = resolver.openOutputStream(target, "w")) {
                if (output == null) throw new IOException("无法写入 " + name);
                output.write(data);
            }
            return;
        }
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) throw new IOException("无法访问下载目录");
        File parent = new File(base, path[0] + "/" + path[1]);
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建下载目录");
        try (OutputStream output = new java.io.FileOutputStream(new File(parent, name))) {
            output.write(data);
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static Uri saveDash(Context context, Uri directory, byte[] manifest, String[] path,
                                Progress progress, AudioTags tags) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri target;
        boolean mediaStore = directory == null;
        if (mediaStore) {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("请先选择下载目录以保存分段 FLAC");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, path[2]);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/flac");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + path[0] + "/" + path[1]);
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            target = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } else {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory));
            Uri parent = folder(resolver, directory, folder(resolver, directory, root, path[0]), path[1]);
            target = DocumentsContract.createDocument(resolver, parent, "audio/flac", path[2]);
        }
        if (target == null) throw new IOException("无法创建 FLAC 文件");
        boolean complete = false;
        try {
            progress.created(target);
            try (OutputStream file = resolver.openOutputStream(target, "w")) {
                if (file == null) throw new IOException("无法写入 FLAC 文件");
                OutputStream output = tags.wrap(file, "flac");
                DashFlacDownload.save(context.getCacheDir(), manifest, output, progress);
                output.flush();
                if (output != file) output.close();
            }
            if (mediaStore && Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(target, values, null, null) != 1) throw new IOException("无法提交 FLAC 文件");
            }
            complete = true;
            return target;
        } finally {
            if (!complete) try {
                if (mediaStore) resolver.delete(target, null, null);
                else DocumentsContract.deleteDocument(resolver, target);
            } catch (Exception ignored) {}
        }
    }

    /** Parallel downloads of one album must not each create its folder. */
    private static final Object FOLDERS = new Object();

    private static Uri folder(ContentResolver resolver, Uri tree, Uri parent, String name) throws IOException {
        synchronized (FOLDERS) {
            return findOrCreateFolder(resolver, tree, parent, name);
        }
    }

    private static Uri findOrCreateFolder(ContentResolver resolver, Uri tree, Uri parent, String name) throws IOException {
        Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(listing, columns, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取下载目录");
            while (cursor.moveToNext()) {
                if (!name.equals(cursor.getString(1))) continue;
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) throw new IOException("同名文件占用目录 " + name);
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        }
        Uri created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("无法创建下载目录 " + name);
        return created;
    }

    private static Uri saveToDirectory(ContentResolver resolver, Uri directory, Uri audio,
                                       String[] path, String extension, Progress progress, AudioTags tags) throws IOException {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory));
        HttpURLConnection connection = (HttpURLConnection) new URL(audio.toString()).openConnection();
        Uri document = null;
        boolean complete = false;
        try {
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("下载服务器返回 HTTP " + status);
            Uri parent = folder(resolver, directory, folder(resolver, directory, root, path[0]), path[1]);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            document = DocumentsContract.createDocument(resolver, parent,
                    mime == null ? "application/octet-stream" : mime, path[2]);
            if (document == null) throw new IOException("无法在选定目录创建文件");
            progress.created(document);
            String length = connection.getHeaderField("Content-Length");
            long total = length == null ? -1 : Long.parseLong(length);
            long bytes = 0;
            try (InputStream input = connection.getInputStream();
                 OutputStream file = resolver.openOutputStream(document, "w")) {
                if (file == null) throw new IOException("无法写入选定目录");
                bytes = copy(input, tags.wrap(file, extension), total, progress);
            }
            if (bytes == 0 || (total >= 0 && total != bytes)) {
                throw new IOException("下载文件不完整");
            }
            complete = true;
            return document;
        } catch (SecurityException error) {
            throw new IOException("下载目录权限已失效，请在设置中重新选择", error);
        } finally {
            connection.disconnect();
            if (!complete && document != null) {
                try {
                    DocumentsContract.deleteDocument(resolver, document);
                } catch (Exception ignored) {}
            }
        }
    }
}
