package com.tidalgui.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.content.ContentResolver;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashSet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LocalLibrary {
    private final SharedPreferences storage;
    private final Context context;
    final Object fileOperations = new Object();
    private final List<Uri> roots = new ArrayList<>();
    private final Map<Long, TidalClient.Track> tracks = new LinkedHashMap<>();
    private final Map<String, List<Long>> playlists = new LinkedHashMap<>();

    LocalLibrary(Context context) {
        this.context = context.getApplicationContext();
        storage = context.getSharedPreferences("local_music", Context.MODE_PRIVATE);
        try {
            JSONArray folders = new JSONArray(storage.getString("folders", "[]"));
            for (int i = 0; i < folders.length(); i++) roots.add(Uri.parse(folders.getString(i)));
            JSONArray saved = new JSONArray(storage.getString("tracks", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                TidalClient.Track track = new TidalClient.Track(saved.getJSONObject(i));
                if (track.isLocal()) tracks.put(track.id, track);
            }
            JSONObject lists = new JSONObject(storage.getString("playlists", "{}"));
            java.util.Iterator<String> names = lists.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONArray ids = lists.getJSONArray(name);
                List<Long> items = new ArrayList<>();
                for (int i = 0; i < ids.length(); i++) {
                    long id = ids.getLong(i);
                    if (tracks.containsKey(id)) items.add(id);
                }
                playlists.put(name, items);
            }
        } catch (Exception ignored) {
            // Keep records read successfully before an incompatible entry.
        }
    }

    static final class LocalGroup {
        final String key;
        final String label;
        final String detail;
        final int category;
        final List<TidalClient.Track> members = new ArrayList<>();

        LocalGroup(String key, String label, String detail, int category) {
            this.key = key;
            this.label = label;
            this.detail = detail;
            this.category = category;
        }
    }

    static LocalGroup groupFor(TidalClient.Track track, int category) {
        String albumArtist = track.payload.optString("album_artist");
        String label;
        String detail = "";
        switch (category) {
            case 1:
                label = unknown(track.album, "未知专辑");
                detail = albumArtist.isEmpty() ? track.artist : albumArtist;
                break;
            case 3: label = unknown(track.artist, "未知艺人"); break;
            case 4: label = unknown(albumArtist, "未知专辑艺人"); break;
            case 5: label = unknown(track.payload.optString("genre"), "未知流派"); break;
            default: label = unknown(track.payload.optString("year"), "未知年份"); break;
        }
        return new LocalGroup((label + "\u0000" + detail).toLowerCase(Locale.ROOT), label, detail, category);
    }

    private static String unknown(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    synchronized List<TidalClient.Track> search(String filter, String playlist) {
        List<TidalClient.Track> result = new ArrayList<>();
        if (playlist == null) result.addAll(tracks.values());
        else if (playlists.containsKey(playlist)) {
            for (Long id : playlists.get(playlist)) {
                TidalClient.Track track = tracks.get(id);
                if (track != null) result.add(track);
            }
        }
        String query = filter.trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            for (int i = result.size() - 1; i >= 0; i--) {
                TidalClient.Track track = result.get(i);
                String searchable = track.title + " " + track.artist + " " + track.album + " " + track.payload.optString("album_artist")
                        + " " + track.payload.optString("genre") + " " + track.payload.optString("year");
                if (!searchable.toLowerCase(Locale.ROOT).contains(query)) result.remove(i);
            }
        }
        return result;
    }

    synchronized String importPlaylist(String preferred, List<Long> ids) {
        String base = preferred.trim();
        if (base.isEmpty()) base = "导入歌单";
        if (base.length() > 56) base = base.substring(0, 56);
        String name = base;
        for (int index = 2; playlists.containsKey(name); index++) name = base + " (" + index + ")";
        List<Long> present = new ArrayList<>();
        for (Long id : ids) if (tracks.containsKey(id)) present.add(id);
        playlists.put(name, present);
        save();
        return name;
    }

    synchronized List<String> playlists() { return new ArrayList<>(playlists.keySet()); }

    synchronized int size() { return tracks.size(); }

    synchronized boolean create(String name) {
        if (name.isEmpty() || name.length() > 64 || playlists.containsKey(name)) return false;
        playlists.put(name, new ArrayList<>());
        save();
        return true;
    }

    synchronized void add(String playlist, TidalClient.Track track) {
        List<Long> items = playlists.get(playlist);
        if (items != null && tracks.containsKey(track.id) && !items.contains(track.id)) {
            items.add(track.id);
            save();
        }
    }

    synchronized boolean liked(long id) {
        List<Long> favorites = playlists.get("我喜欢的");
        return favorites != null && favorites.contains(id);
    }

    synchronized void toggleLike(TidalClient.Track track) {
        if (!playlists.containsKey("我喜欢的")) create("我喜欢的");
        if (liked(track.id)) remove("我喜欢的", track.id);
        else add("我喜欢的", track);
    }

    synchronized void remove(String playlist, long id) {
        if (playlist != null) {
            List<Long> items = playlists.get(playlist);
            if (items != null) items.removeAll(java.util.Collections.singleton(id));
        } else {
            tracks.remove(id);
            for (List<Long> items : playlists.values()) items.removeAll(java.util.Collections.singleton(id));
        }
        save();
    }

    synchronized void deletePlaylist(String playlist) {
        playlists.remove(playlist);
        save();
    }

    synchronized int importTracks(List<TidalClient.Track> imported) {
        int before = size();
        for (TidalClient.Track track : imported) {
            TidalClient.Track existing = tracks.get(track.id);
            if (existing == null || existing.localRoot.isEmpty() || !track.localRoot.isEmpty()) tracks.put(track.id, track);
        }
        save();
        return size() - before;
    }

    synchronized List<Uri> folders() { return new ArrayList<>(roots); }

    synchronized boolean hasFolders() { return !roots.isEmpty(); }

    synchronized void addFolder(Uri tree) {
        if (!roots.contains(tree)) {
            roots.add(tree);
            save();
        }
    }

    synchronized void forgetFolder(Uri tree) {
        if (!roots.remove(tree)) return;
        List<Long> removed = new ArrayList<>();
        for (TidalClient.Track track : tracks.values()) {
            if (tree.toString().equals(track.localRoot)) removed.add(track.id);
        }
        for (Long id : removed) {
            tracks.remove(id);
            for (List<Long> items : playlists.values()) items.remove(id);
        }
        save();
    }

    static final class ScanResult {
        int total;
        int changed;
        int reused;
        int removed;
    }

    private static final class Entry {
        final String id;
        final String name;
        final long size;
        final long modified;

        Entry(Cursor cursor) {
            id = cursor.getString(0);
            name = cursor.getString(1);
            size = cursor.isNull(3) ? -1 : cursor.getLong(3);
            modified = cursor.isNull(4) ? -1 : cursor.getLong(4);
        }
    }

    ScanResult scan(Uri tree) throws Exception {
        synchronized (fileOperations) {
            return scanDirectory(tree);
        }
    }

    private ScanResult scanDirectory(Uri tree) throws Exception {
        Map<Long, TidalClient.Track> previous = new LinkedHashMap<>();
        synchronized (this) {
            if (!roots.contains(tree)) throw new IOException("目录未授权");
            for (TidalClient.Track track : tracks.values()) {
                if (tree.toString().equals(track.localRoot)) previous.put(track.id, track);
            }
        }
        ScanResult result = new ScanResult();
        ContentResolver resolver = context.getContentResolver();
        ArrayDeque<String> pending = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        HashSet<Long> seen = new HashSet<>();
        List<TidalClient.Track> found = new ArrayList<>();
        pending.add(DocumentsContract.getTreeDocumentId(tree));
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String next = pending.remove();
            if (!visited.add(next)) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, next);
            List<Entry> audio = new ArrayList<>();
            Map<String, String> lyrics = new LinkedHashMap<>();
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null)) {
                if (cursor == null) throw new IOException("无法读取已授权目录");
                while (cursor.moveToNext()) {
                    Entry entry = new Entry(cursor);
                    String mime = cursor.getString(2);
                    if (entry.id == null) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) pending.add(entry.id);
                    else if (entry.name != null && entry.name.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                        lyrics.put(baseName(entry.name).toLowerCase(Locale.ROOT), entry.id);
                    } else if ((mime != null && mime.startsWith("audio/")) || audioName(entry.name)) audio.add(entry);
                }
            }
            for (Entry entry : audio) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long id = localId(tree.getAuthority() + ":" + entry.id);
                seen.add(id);
                TidalClient.Track old = previous.get(id);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id);
                String lyricId = entry.name == null ? null : lyrics.get(baseName(entry.name).toLowerCase(Locale.ROOT));
                String lrc = lyricId == null ? "" : DocumentsContract.buildDocumentUriUsingTree(tree, lyricId).toString();
                boolean reusable = old != null && entry.modified > 0 && entry.size >= 0
                        && entry.size == old.payload.optLong("scan_size", -1)
                        && entry.modified == old.payload.optLong("scan_modified", -1)
                        && uri.equals(old.localUri) && java.util.Objects.equals(entry.name, old.payload.optString("file_name"))
                        // Scans from before lengths and formats were recorded are read once more.
                        && old.payload.has("lossless");
                if (reusable && lrc.equals(old.payload.optString("lrc_uri"))) {
                    result.reused++;
                    result.total++;
                    continue;
                }
                try {
                    JSONObject data = new JSONObject((reusable ? old : inspect(context, uri, tree)).payload.toString());
                    found.add(new TidalClient.Track(data.put("scan_size", entry.size)
                            .put("scan_modified", entry.modified).put("lrc_uri", lrc)));
                    if (reusable) result.reused++; else result.changed++;
                    result.total++;
                } catch (Exception unreadable) {
                    if (old != null) result.total++;
                }
            }
        }
        synchronized (this) {
            if (!roots.contains(tree)) throw new IOException("目录授权已移除");
            for (Map.Entry<Long, TidalClient.Track> entry : previous.entrySet()) {
                long id = entry.getKey();
                if (!seen.contains(id) && tracks.get(id) == entry.getValue()) {
                    tracks.remove(id);
                    for (List<Long> items : playlists.values()) items.removeAll(java.util.Collections.singleton(id));
                    result.removed++;
                }
            }
            for (TidalClient.Track track : found) {
                TidalClient.Track current = tracks.get(track.id);
                if (current == null || current == previous.get(track.id) || current.localRoot.isEmpty()) {
                    tracks.put(track.id, track);
                }
            }
            save();
        }
        return result;
    }

    synchronized TidalClient.Track latest(TidalClient.Track track) {
        TidalClient.Track current = tracks.get(track.id);
        return current == null ? track : current;
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    synchronized List<TidalClient.Track> managedTracks() {
        List<TidalClient.Track> result = new ArrayList<>();
        for (TidalClient.Track track : tracks.values()) {
            if (isManaged(track)) result.add(track);
        }
        return result;
    }

    synchronized TidalClient.Track track(long id) throws IOException {
        TidalClient.Track track = tracks.get(id);
        if (track == null) throw new IOException("找不到本地歌曲");
        return track;
    }

    synchronized TidalClient.Track managed(long id) throws IOException {
        TidalClient.Track track = tracks.get(id);
        if (!isManaged(track)) throw new IOException("此文件不在已授权的音乐目录");
        return track;
    }

    private synchronized boolean isManaged(TidalClient.Track track) {
        return track != null && track.isLocal() && !track.localRoot.isEmpty() && roots.contains(Uri.parse(track.localRoot));
    }

    static final class Document {
        final Uri uri;
        final String name;
        final boolean directory;

        Document(Uri uri, String name, boolean directory) {
            this.uri = uri;
            this.name = name;
            this.directory = directory;
        }
    }

    private Uri authorizedRoot(String id) throws IOException {
        List<Uri> folders = folders();
        if (id.isEmpty() && !folders.isEmpty()) return folders.get(0);
        for (Uri root : folders) if (id.equals(root.toString())) return root;
        throw new IOException("请选择手机上已授权的音乐目录");
    }

    private List<Document> children(Uri root, Uri parent) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent));
        String[] fields = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<Document> result = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children, fields, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取目标目录");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id == null || name == null) continue;
                result.add(new Document(DocumentsContract.buildDocumentUriUsingTree(root, id), name,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
            }
        }
        return result;
    }

    private Document find(Uri root, Uri parent, String name) throws IOException {
        for (Document document : children(root, parent)) if (name.equals(document.name)) return document;
        return null;
    }

    private static boolean validDirectoryName(String name) {
        return !name.trim().isEmpty() && !name.equals(".") && !name.equals("..") && name.length() <= 180
                && !name.contains("/") && !name.contains("\\") && !containsControl(name);
    }

    private Uri directory(Uri root, String path, boolean create) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root));
        if (path.isEmpty()) return parent;
        String[] parts = path.split("/", -1);
        if (parts.length > 24 || path.length() > 1600) throw new IOException("文件夹路径过长");
        for (String part : parts) {
            if (!validDirectoryName(part)) throw new IOException("无效的文件夹路径");
        }
        for (String part : parts) {
            Document found = find(root, parent, part);
            if (found != null && !found.directory) throw new IOException("同名文件占用了目录位置");
            if (found == null && !create) return null;
            parent = found != null ? found.uri : DocumentsContract.createDocument(
                    context.getContentResolver(), parent, DocumentsContract.Document.MIME_TYPE_DIR, part);
            if (parent == null) throw new IOException("无法创建目录");
        }
        return parent;
    }

    JSONObject fileDirectory(String rootId, String path) throws Exception {
        Uri root = authorizedRoot(rootId);
        Uri parent = directory(root, path, false);
        if (parent == null) return new JSONObject().put("missing", true);
        JSONArray directories = new JSONArray();
        JSONArray files = new JSONArray();
        for (Document child : children(root, parent)) {
            if (child.directory) {
                if (validDirectoryName(child.name)) {
                    directories.put(new JSONObject().put("name", child.name)
                            .put("id", DocumentsContract.getDocumentId(child.uri)));
                }
            } else {
                TidalClient.Track track = indexed(child.uri);
                if (track != null && isManaged(track)) files.put(new JSONObject().put("id", track.id).put("name", child.name));
                else if (attachmentType(child.name) != null) files.put(new JSONObject().put("name", child.name)
                        .put("attachment", true));
            }
        }
        return new JSONObject().put("directories", directories).put("files", files);
    }

    Uri attachment(String rootId, String path, String name) throws IOException {
        if (!validUploadName(name) || attachmentType(name) == null) throw new IOException("附件名称无效");
        Uri root = authorizedRoot(rootId);
        Uri parent = directory(root, path, false);
        Document found = parent == null ? null : uploadTarget(root, parent, name);
        if (found == null) throw new IOException("附件已移除");
        return found.uri;
    }

    private synchronized TidalClient.Track indexed(Uri uri) throws Exception {
        return tracks.get(documentTrackId(uri));
    }

    private static long documentTrackId(Uri uri) throws Exception {
        return localId(uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri));
    }

    boolean uploadConflict(String rootId, String path, String name) throws IOException {
        if (!validUploadName(name)) throw new IOException("仅支持音频、封面图片和 LRC 歌词");
        Uri root = authorizedRoot(rootId);
        Uri parent = directory(root, path, false);
        return parent != null && uploadTarget(root, parent, name) != null;
    }

    private Document uploadTarget(Uri root, Uri parent, String name) throws IOException {
        Document target = find(root, parent, name);
        if (target != null && target.directory) throw new IOException("同名文件夹占用了文件位置");
        return target;
    }

    static final class UploadResult {
        final String status;
        final TidalClient.Track track;
        final Long previousId;

        UploadResult(String status, TidalClient.Track track, Long previousId) {
            this.status = status;
            this.track = track;
            this.previousId = previousId;
        }
    }

    UploadResult upload(String rootId, String path, String name, String policy, InputStream source, long length)
            throws Exception {
        String attachment = attachmentType(name);
        long limit = attachment == null ? 200L * 1024 * 1024
                : "application/x-lrc".equals(attachment) ? 2L * 1024 * 1024 : 20L * 1024 * 1024;
        if (!validUploadName(name) || length <= 0 || length > limit) {
            throw new IOException("文件类型不支持、文件为空或超过大小限制");
        }
        if (!"prompt".equals(policy) && !"skip".equals(policy) && !"replace".equals(policy)) {
            throw new IOException("无效的同名文件处理方式");
        }
        Uri root = authorizedRoot(rootId);
        Uri parent = directory(root, path, true);
        Document existing = uploadTarget(root, parent, name);
        if (existing != null && !"replace".equals(policy)) {
            transferUpload(source, null, length);
            return new UploadResult("skip".equals(policy) ? "skipped" : "conflict", null, null);
        }
        ContentResolver resolver = context.getContentResolver();
        String mime = attachment != null ? attachment : java.net.URLConnection.guessContentTypeFromName(name);
        String temporaryName = "tidal-upload-" + java.util.UUID.randomUUID() + name.substring(name.lastIndexOf('.'));
        Uri staged = DocumentsContract.createDocument(resolver, parent,
                mime == null ? "application/octet-stream" : mime, temporaryName);
        if (staged == null) throw new IOException("该目录不可写");
        Uri backup = null;
        Uri uploaded = null;
        boolean committed = false;
        try {
            try (OutputStream out = resolver.openOutputStream(staged, "w")) {
                if (out == null) throw new IOException("无法写入音频文件");
                transferUpload(source, out, length);
            }
            TidalClient.Track verified = attachment == null ? inspect(context, staged, root) : null;
            if (attachment != null) verifyAttachment(resolver, staged, attachment);
            synchronized (this) {
                if (!roots.contains(root)) throw new IOException("目录授权已移除");
                Document current = uploadTarget(root, parent, name);
                if (existing == null ? current != null : current == null || !existing.uri.equals(current.uri)) {
                    return new UploadResult("conflict", null, null);
                }
                if (current != null) {
                    backup = DocumentsContract.renameDocument(resolver, current.uri,
                            "tidal-backup-" + java.util.UUID.randomUUID() + ".bak");
                    if (backup == null) throw new IOException("原文件无法移动，已保留");
                }
                uploaded = DocumentsContract.renameDocument(resolver, staged, name);
                if (uploaded == null || !name.equals(displayName(resolver, uploaded))) {
                    throw new IOException("无法按原名称保存音频");
                }
                if (verified == null) {
                    committed = true;
                    return new UploadResult(current == null ? "uploaded" : "replaced", null, null);
                }
                long id = documentTrackId(uploaded);
                Long previousId = current == null ? null : documentTrackId(current.uri);
                TidalClient.Track previous = previousId == null ? null : tracks.get(previousId);
                JSONObject data = new JSONObject(verified.payload.toString())
                        .put("id", id).put("local_uri", uploaded.toString()).put("file_name", name);
                if (temporaryName.equals(verified.title)) data.put("title", name);
                if (previous != null) data.put("lrc_uri", previous.payload.optString("lrc_uri"));
                TidalClient.Track track = new TidalClient.Track(data);
                if (previousId != null && previousId != id) {
                    tracks.remove(previousId);
                    for (List<Long> items : playlists.values()) {
                        for (int i = 0; i < items.size(); i++) if (items.get(i).equals(previousId)) items.set(i, id);
                    }
                }
                tracks.put(id, track);
                save();
                committed = true;
                return new UploadResult(current == null ? "uploaded" : "replaced", track, previousId);
            }
        } finally {
            if (committed) {
                deleteTemporary(resolver, backup);
            } else {
                deleteTemporary(resolver, uploaded == null ? staged : uploaded);
                if (backup != null) {
                    try {
                        Uri restored = DocumentsContract.renameDocument(resolver, backup, name);
                        if (restored == null || !name.equals(displayName(resolver, restored))) {
                            throw new IOException("原文件保存在备份文件中");
                        }
                    } catch (Exception error) {
                        throw new IOException("替换失败。原文件仍保留在目标目录的 tidal-backup 文件中", error);
                    }
                }
            }
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private static void deleteTemporary(ContentResolver resolver, Uri uri) {
        if (uri == null) return;
        try { DocumentsContract.deleteDocument(resolver, uri); } catch (Exception ignored) {}
    }

    private static void transferUpload(InputStream source, OutputStream output, long remaining) throws Exception {
        byte[] buffer = new byte[65536];
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int read = source.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (read < 0) throw new IOException("文件上传不完整");
            if (output != null) output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    HashSet<Long> deleteDirectory(String rootId, String path, String expectedId) throws Exception {
        if (path.isEmpty()) throw new IOException("不能删除已授权的根目录");
        Uri root = authorizedRoot(rootId);
        Uri target = directory(root, path, false);
        if (target == null || !expectedId.equals(DocumentsContract.getDocumentId(target))) {
            throw new IOException("文件夹已变更，请刷新后重试");
        }
        HashSet<String> visited = new HashSet<>();
        HashSet<Long> removed = new HashSet<>();
        ArrayDeque<Uri> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            Uri next = pending.remove();
            String id = DocumentsContract.getDocumentId(next);
            if (!visited.add(id)) continue;
            for (Document child : children(root, next)) {
                if (child.directory) pending.add(child.uri);
                else removed.add(documentTrackId(child.uri));
            }
        }
        synchronized (this) {
            if (!roots.contains(root)) throw new IOException("目录授权已移除");
            for (Uri granted : roots) {
                if (java.util.Objects.equals(root.getAuthority(), granted.getAuthority())
                        && visited.contains(DocumentsContract.getTreeDocumentId(granted))) {
                    throw new IOException("该文件夹包含已授权的扫描根目录");
                }
            }
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), target)) {
                throw new IOException("无法删除此文件夹");
            }
            tracks.keySet().removeAll(removed);
            for (List<Long> items : playlists.values()) items.removeAll(removed);
            save();
        }
        return removed;
    }

    void deleteFile(long id) throws Exception {
        TidalClient.Track track = managed(id);
        if (!DocumentsContract.deleteDocument(context.getContentResolver(), track.localUri)) {
            throw new IOException("此文件无法删除");
        }
        remove(null, id);
    }

    synchronized TidalClient.Track renameFile(long id, String name) throws Exception {
        if (!validFileName(name)) throw new IOException("请输入有效的音频文件名（含扩展名）");
        TidalClient.Track track = managed(id);
        Uri root = Uri.parse(track.localRoot);
        Uri updated = DocumentsContract.renameDocument(context.getContentResolver(), track.localUri, name);
        if (updated == null) throw new IOException("此文件无法改名");
        TidalClient.Track replacement;
        try {
            replacement = inspect(context, updated, root);
        } catch (Exception unavailable) {
            String identity = updated.getAuthority() + ":" + DocumentsContract.getDocumentId(updated);
            JSONObject payload = new JSONObject(track.payload.toString()).put("id", localId(identity))
                    .put("local_uri", updated.toString()).put("file_name", name);
            replacement = new TidalClient.Track(payload);
        }
        tracks.remove(track.id);
        tracks.put(replacement.id, replacement);
        for (List<Long> items : playlists.values()) {
            for (int i = 0; i < items.size(); i++) if (items.get(i) == track.id) items.set(i, replacement.id);
        }
        save();
        return replacement;
    }

    private static String attachmentType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "lrc": return "application/x-lrc";
            default: return null;
        }
    }

    private static boolean validUploadName(String name) {
        return !name.startsWith(".") && validDirectoryName(name)
                && (audioName(name) || attachmentType(name) != null);
    }

    private static void verifyAttachment(ContentResolver resolver, Uri uri, String type) throws Exception {
        if ("application/x-lrc".equals(type)) {
            if (LocalMusicFiles.read(resolver, uri).trim().isEmpty()) throw new IOException("歌词文件为空");
            return;
        }
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取封面图片");
            android.graphics.BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !type.equals(bounds.outMimeType)) {
            throw new IOException("图片内容与文件格式不符");
        }
    }

    private static boolean validFileName(String name) {
        return audioName(name) && name.length() <= 180 && !name.contains("/") && !name.contains("\\")
                && !name.startsWith(".") && !containsControl(name);
    }

    private static long localId(String identity) throws Exception {
        long bits = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).getLong();
        return -1 - (bits & ((1L << 52) - 1));
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean audioName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches("(?s).+\\.(aac|aif|aiff|alac|ape|flac|m4a|mp3|oga|ogg|opus|wav|wma|wv)");
    }

    private void save() {
        JSONArray items = new JSONArray();
        for (TidalClient.Track track : tracks.values()) items.put(track.payload);
        JSONArray folders = new JSONArray();
        for (Uri root : roots) folders.put(root.toString());
        storage.edit().putString("tracks", items.toString()).putString("folders", folders.toString())
                .putString("playlists", new JSONObject(playlists).toString()).apply();
    }

    static TidalClient.Track downloaded(Context context, Uri uri, String title, String artist, String album)
            throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("音频文件地址无效");
        String identity = DocumentsContract.isDocumentUri(context, uri)
                ? uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri) : uri.toString();
        return new TidalClient.Track(new JSONObject().put("id", localId(identity)).put("local_uri", uri.toString())
                .put("title", title).put("artist", new JSONObject().put("name", artist))
                .put("album", new JSONObject().put("title", album)));
    }

    static TidalClient.Track inspect(Context context, Uri uri, Uri root) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("音频文件地址无效");
        String filename = "";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) filename = cursor.getString(0);
        }
        if (filename == null || filename.isEmpty()) filename = "本地音频";
        String identity = DocumentsContract.isDocumentUri(context, uri)
                ? uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri) : uri.toString();
        long id = localId(identity);
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(context, uri);
            String audio = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (!"yes".equals(audio)) throw new IOException("未找到音轨");
            String title = field(metadata, MediaMetadataRetriever.METADATA_KEY_TITLE);
            return new TidalClient.Track(new JSONObject().put("id", id)
                    .put("local_uri", uri.toString()).put("local_root", root == null ? "" : root.toString())
                    .put("file_name", filename)
                    .put("album_artist", field(metadata, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    .put("genre", field(metadata, MediaMetadataRetriever.METADATA_KEY_GENRE))
                    .put("year", trackYear(metadata))
                    .put("track_number", field(metadata, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
                    .put("disc_number", field(metadata, MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
                    .put("title", title.isEmpty() ? filename : title)
                    .put("duration", length(metadata))
                    .put("lossless", lossless(metadata, filename))
                    .put("artist", new JSONObject().put("name", field(metadata, MediaMetadataRetriever.METADATA_KEY_ARTIST)))
                    .put("album", new JSONObject().put("title", field(metadata, MediaMetadataRetriever.METADATA_KEY_ALBUM))));
        } finally {
            metadata.release();
        }
    }

    /** Length in whole seconds, as TIDAL and NetEase songs give it; 0 when unknown. */
    private static long length(MediaMetadataRetriever metadata) {
        try {
            return Math.round(Long.parseLong(field(metadata, MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0);
        } catch (NumberFormatException unknown) {
            return 0;
        }
    }

    private static final java.util.regex.Pattern LOSSLESS_NAME =
            java.util.regex.Pattern.compile("(?i).*\\.(flac|wav|ape|wv|aiff?|alac|dsf|dff)$");

    /**
     * Whether the file is lossless: FLAC, WAV and the like, or an M4A at a lossless bit rate
     * (ALAC; AAC stays far below 500 kbps).
     */
    static boolean lossless(MediaMetadataRetriever metadata, String filename) {
        String mime = field(metadata, MediaMetadataRetriever.METADATA_KEY_MIMETYPE).toLowerCase(Locale.ROOT);
        if (mime.contains("flac") || mime.contains("wav") || mime.contains("aiff") || mime.contains("ape")) return true;
        if (mime.contains("mpeg") || mime.contains("ogg") || mime.contains("opus") || mime.contains("aac")) return false;
        if (mime.contains("mp4") || filename.toLowerCase(Locale.ROOT).endsWith(".m4a")) {
            try {
                return Long.parseLong(field(metadata, MediaMetadataRetriever.METADATA_KEY_BITRATE)) >= 500_000;
            } catch (NumberFormatException unknown) {
                return false;
            }
        }
        return LOSSLESS_NAME.matcher(filename).matches();
    }

    private static String trackYear(MediaMetadataRetriever metadata) {
        String year = field(metadata, MediaMetadataRetriever.METADATA_KEY_YEAR);
        if (year.matches("[0-9]{4}")) return year;
        String date = field(metadata, MediaMetadataRetriever.METADATA_KEY_DATE);
        return date.matches("[0-9]{4}.*") ? date.substring(0, 4) : "";
    }

    private static String field(MediaMetadataRetriever metadata, int key) {
        String value = metadata.extractMetadata(key);
        return value == null ? "" : value.trim();
    }
}
