package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Part of {@link MainActivity}: ImportExport. */
@UnstableApi
final class ImportExport {
    final MainActivity ui;

    ImportExport(MainActivity ui) {
        this.ui = ui;
    }


    void pickAudio() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        ui.startActivityForResult(picker, MainActivity.IMPORT_AUDIO);
    }

    void pickFolder() {
        ui.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
                MainActivity.IMPORT_FOLDER);
    }

    void chooseFolders() {
        if (ui.playback == null) return;
        List<Uri> folders = ui.playback.localLibrary.folders();
        if (folders.isEmpty()) {
            pickFolder();
            return;
        }
        String[] display = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            display[i] = android.provider.DocumentsContract.getTreeDocumentId(folders.get(i));
        }
        new AlertDialog.Builder(ui).setTitle("音乐文件夹").setItems(display, (dialog, selected) -> {
            Uri tree = folders.get(selected);
            new AlertDialog.Builder(ui).setTitle(display[selected])
                    .setItems(new String[]{"重新扫描", "取消目录授权"}, (action, which) -> {
                        if (which == 0) scanDirectory(tree, false);
                        else new AlertDialog.Builder(ui).setTitle("从音乐库移除此目录？")
                                .setMessage("文件会保留在原目录。").setNegativeButton("取消", null)
                                .setPositiveButton("移除目录", (confirm, choice) -> {
                                    if (ui.playback == null) return;
                                    for (TidalClient.Track track : ui.playback.localLibrary.managedTracks()) {
                                        if (tree.toString().equals(track.localRoot)) ui.playback.fileDeleted(track.id);
                                    }
                                    ui.playback.localLibrary.forgetFolder(tree);
                                    if (!ui.playback.localLibrary.hasFolders()) ui.playback.setRemoteFiles(false);
                                    for (android.content.UriPermission permission : ui.getContentResolver().getPersistedUriPermissions()) {
                                        if (!permission.getUri().equals(tree)) continue;
                                        int modes = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                                                | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                                        try { ui.getContentResolver().releasePersistableUriPermission(tree, modes); }
                                        catch (SecurityException ignored) {}
                                    }
                                    ui.playback.libraryChanged();
                                    if (ui.page == Page.CONNECTIONS) ui.render();
                                }).show();
                    }).show();
        }).setPositiveButton("添加目录", (dialog, which) -> pickFolder()).setNegativeButton("关闭", null).show();
    }

    void scanDirectory(Uri tree, boolean added) {
        if (ui.playback == null || ui.localBusy) {
            ui.toast("音乐库正在处理文件");
            return;
        }
        if (added) ui.playback.localLibrary.addFolder(tree);
        scanDirectories(java.util.Collections.singletonList(tree));
    }

    void scanDirectories(List<Uri> trees) {
        if (ui.playback == null || ui.localBusy || trees.isEmpty()) return;
        PlaybackService service = ui.playback;
        ui.localBusy = true;
        ui.localScanSummary = "";
        if (ui.page != Page.LOCAL) ui.open(Page.LOCAL);
        else ui.render();
        ui.worker.submit(() -> {
            LocalLibrary.ScanResult total = new LocalLibrary.ScanResult();
            int failures = 0;
            String lastError = "";
            for (Uri tree : trees) {
                try {
                    LocalLibrary.ScanResult scan = service.localLibrary.scan(tree);
                    total.total += scan.total;
                    total.changed += scan.changed;
                    total.reused += scan.reused;
                    total.removed += scan.removed;
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception problem) {
                    failures++;
                    lastError = UiKit.readable(problem);
                }
            }
            String summary = failures == trees.size() ? "扫描失败 · " + lastError
                    : "本次扫描 " + total.total + " 首 · 更新 " + total.changed
                    + " · 复用 " + total.reused + " · 移除 " + total.removed
                    + (failures == 0 ? "" : " · " + failures + " 个目录读取失败");
            ui.runOnUiThread(() -> {
                if (ui.isDestroyed() || ui.playback != service) return;
                ui.localBusy = false;
                ui.localScanSummary = summary;
                service.libraryChanged();
                if (ui.page != Page.LOCAL) ui.toast(summary);
            });
        });
    }

    void importAudio(Intent data) {
        if (ui.playback == null || ui.localBusy) return;
        List<Uri> sources = new ArrayList<>();
        android.content.ClipData selection = data.getClipData();
        if (selection != null) {
            for (int i = 0; i < selection.getItemCount(); i++) {
                Uri uri = selection.getItemAt(i).getUri();
                if (uri != null) sources.add(uri);
            }
        } else if (data.getData() != null) sources.add(data.getData());
        if (sources.isEmpty()) return;
        PlaybackService service = ui.playback;
        ui.localBusy = true;
        if (ui.page != Page.LOCAL) ui.open(Page.LOCAL);
        else ui.render();
        ui.worker.submit(() -> {
            List<TidalClient.Track> loaded = new ArrayList<>();
            for (Uri uri : sources) {
                try {
                    TidalClient.Track track = LocalLibrary.inspect(ui, uri, null);
                    ui.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    loaded.add(track);
                } catch (Exception ignored) {
                    // Partial import still keeps valid tracks.
                }
            }
            ui.runOnUiThread(() -> {
                if (ui.isDestroyed() || ui.playback != service) return;
                ui.localBusy = false;
                int count = service.localLibrary.importTracks(loaded);
                service.libraryChanged();
                int failed = sources.size() - loaded.size();
                ui.toast("已导入 " + count + " 首" + (failed > 0 ? "，" + failed + " 首未能读取" : ""));
            });
        });
    }

    void pickSession() {
        ui.loginFlow.cancelLogin();
        ui.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), MainActivity.IMPORT_SESSION);
    }

    void pickPlaylistSource() {
        ui.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), MainActivity.IMPORT_M3U);
    }

    void pickPlaylistDestination() {
        if (ui.localPlaylist == null) return;
        ui.exportPlaylist = ui.localPlaylist;
        String filename = ui.localPlaylist.replaceAll("[/\\\\:*?\"<>|]", "_") + ".m3u8";
        ui.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/x-mpegurl")
                .putExtra(Intent.EXTRA_TITLE, filename), MainActivity.EXPORT_M3U);
    }

    void importM3u(Uri source) {
        if (ui.playback == null) return;
        PlaybackService service = ui.playback;
        ui.worker.submit(() -> {
            try {
                LocalMusicFiles.Playlist parsed = LocalMusicFiles.importM3u(
                        ui.getContentResolver(), source, service.localLibrary.search("", null));
                if (parsed.ids.isEmpty()) throw new IOException("歌单没有匹配的音频，请先扫描音乐目录");
                String name = "导入歌单";
                try (android.database.Cursor cursor = ui.getContentResolver().query(source,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                        name = cursor.getString(0).replaceFirst("(?i)\\.m3u8?$", "");
                    }
                }
                String saved = service.localLibrary.importPlaylist(name, parsed.ids);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || ui.playback != service) return;
                    ui.localTab = 2;
                    ui.localPlaylist = saved;
                    ui.localGroup = null;
                    service.libraryChanged();
                    ui.toast("已导入 " + parsed.ids.size() + " 首"
                            + (parsed.missing == 0 ? "" : "，跳过 " + parsed.missing + " 首未匹配"));
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> { if (!ui.isDestroyed()) ui.toast("歌单导入失败 · " + UiKit.readable(error)); });
            }
        });
    }

    void exportM3u(Uri destination, String playlist) {
        if (ui.playback == null || playlist == null) return;
        List<TidalClient.Track> tracks = ui.playback.localLibrary.search("", playlist);
        ui.worker.submit(() -> {
            try {
                LocalMusicFiles.exportM3u(ui.getContentResolver(), destination, tracks);
                ui.runOnUiThread(() -> { if (!ui.isDestroyed()) ui.toast("已导出 " + tracks.size() + " 首歌曲"); });
            } catch (Exception error) {
                ui.runOnUiThread(() -> { if (!ui.isDestroyed()) ui.toast("歌单导出失败 · " + UiKit.readable(error)); });
            }
        });
    }

    void clearFavorites() {
        ui.favoriteBusy = false;
        ui.favoriteIds.clear();
    }
}
