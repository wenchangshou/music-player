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
        if (audio.dash != null) return new Transfer(0, saveDash(context, directory, audio.dash, path, progress));
        if (directory != null) {
            return new Transfer(0, saveToDirectory(context.getContentResolver(), directory, audio.uri, path, extension, progress));
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

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static Uri saveDash(Context context, Uri directory, byte[] manifest, String[] path,
                                Progress progress) throws IOException {
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
            try (OutputStream output = resolver.openOutputStream(target, "w")) {
                if (output == null) throw new IOException("无法写入 FLAC 文件");
                DashFlacDownload.save(context.getCacheDir(), manifest, output, progress);
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

    private static Uri folder(ContentResolver resolver, Uri tree, Uri parent, String name) throws IOException {
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
                                       String[] path, String extension, Progress progress) throws IOException {
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
                 OutputStream output = resolver.openOutputStream(document, "w")) {
                if (output == null) throw new IOException("无法写入选定目录");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                    output.write(buffer, 0, read);
                    bytes += read;
                    progress.transferred(bytes, total);
                }
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
