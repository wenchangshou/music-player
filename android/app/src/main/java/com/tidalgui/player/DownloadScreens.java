package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: DownloadScreens. */
@UnstableApi
final class DownloadScreens {
    final MainActivity ui;

    DownloadScreens(MainActivity ui) {
        this.ui = ui;
    }

    final java.util.Set<String> expandedDownloads = new java.util.HashSet<>();
    LinearLayout downloadRows;
    boolean downloadRefreshPending;
    String downloadSignature;
    int downloadFilter;

    void showNeteaseComments(TidalClient.Track track) {
        if (ui.playback == null || track == null || !track.isNetease()) return;
        new NeteaseCommentsDialog(ui, ui.playback.netease(), track, ui.worker).show();
    }

    void downloadNetease(TidalClient.Track track) {
        DownloadTasks.Resolve resolve = downloadResolver("网易云");
        if (resolve == null) {
            ui.toast("请先配置网易云接口");
            return;
        }
        downloadChoices("下载 · " + track.title, DownloadTasks.HIGHEST, MusicDownload.directory(ui),
                (quality, directory) -> queueDownloads(java.util.Collections.singletonList(track), "网易云",
                        quality, directory, resolve));
    }

    /** "下载歌曲", or "已下载（Max）· 重新检查" when it is on the phone; downloading again checks and repairs it. */
    String downloadLabel(TidalClient.Track track) {
        String saved = ui.downloaded == null ? null : ui.downloaded.label(track);
        if (saved == null) return "下载歌曲";
        if (saved.startsWith("已下载 · ")) return "已下载（" + saved.substring("已下载 · ".length()) + "）· 重新检查";
        return saved + " · 重新检查";
    }

        void downloadTidal(TidalClient.Track track) {
        chooseTidalDownload(null, java.util.Collections.singletonList(track));
    }

    void selectTidalDownloads(List<TidalClient.Track> options) {
        String[] labels = new String[options.size()];
        boolean[] selected = new boolean[options.size()];
        for (int i = 0; i < options.size(); i++) {
            TidalClient.Track track = options.get(i);
            labels[i] = track.artist.isEmpty() ? track.title : track.title + " · " + track.artist;
            if (ui.downloaded != null && ui.downloaded.label(track) != null) labels[i] += "（已下载）";
        }
        AlertDialog dialogue = new AlertDialog.Builder(ui).setTitle("选择下载歌曲")
                .setMultiChoiceItems(labels, selected, (dialog, index, checked) -> {
                    selected[index] = checked;
                    boolean any = false;
                    for (boolean item : selected) any |= item;
                    ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                }).setPositiveButton("继续", (dialog, button) -> {
                    List<TidalClient.Track> chosen = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) {
                        if (selected[i]) chosen.add(options.get(i));
                    }
                    chooseTidalDownload(null, chosen);
                }).setNegativeButton("取消", null).show();
        dialogue.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    void chooseTidalDownload(TidalClient.Collection album, List<TidalClient.Track> selected) {
        if (ui.remoteScreens.remoteDownloads()) {
            remoteDownload(album, selected);
            return;
        }
        TidalClient api = ui.playback == null ? null : ui.playback.client;
        if (api == null) {
            ui.toast("请先登录 TIDAL");
            return;
        }
        String title = album == null ? "下载 " + selected.size() + " 首" : "下载专辑";
        DownloadTasks.Resolve resolve = downloadResolver("TIDAL");
        downloadChoices(title, DownloadTasks.HIGHEST, MusicDownload.directory(ui), (quality, directory) -> {
            ui.toast("正在读取曲目信息");
            MainActivity.downloads.submit(() -> {
                try {
                    List<TidalClient.Track> items;
                    TidalClient.Collection metadata = album;
                    if (album != null) {
                        metadata = DownloadTasks.album(api, album.id, album);
                        items = api.albumTracks(metadata);
                    } else items = DownloadTasks.withAlbums(api, selected);
                    if (items.isEmpty()) throw new IOException("暂无可下载的曲目");
                    int skipped = ui.downloadTasks.add(items, "TIDAL", quality, directory, resolve, metadata);
                    ui.handler.post(() -> showQueuedDownloads(skipped));
                } catch (Exception error) {
                    ui.handler.post(() -> {
                        if (!ui.isDestroyed()) ui.toast("下载失败 · " + UiKit.readable(error));
                    });
                }
            });
        });
    }

    static final String[] REMOTE_DOWNLOAD_QUALITIES = {"BEST", "HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"};

    /** Download on the controlled device, with its TIDAL login and into its download folder. */
    void remoteDownload(TidalClient.Collection album, List<TidalClient.Track> selected) {
        JSONArray ids = new JSONArray();
        if (album == null) {
            for (TidalClient.Track track : selected) {
                if (track.needsTidal() && !track.isNetease() && track.id > 0 && ids.length() < 100) ids.put(track.id);
            }
            if (ids.length() == 0) {
                ui.toast("没有可在远程设备下载的 TIDAL 歌曲");
                return;
            }
        }
        String device = ui.devices.targetName();
        String[] names = {"最高可用（Max 或 High）", "Max", "High", "Low", "Low 96k"};
        int[] choice = {0};
        String what = album == null ? ids.length() + " 首歌曲" : "专辑「" + album.title + "」";
        new AlertDialog.Builder(ui).setTitle("在「" + device + "」下载" + what)
                .setSingleChoiceItems(names, 0, (dialog, which) -> choice[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", (dialog, button) -> {
                    try {
                        JSONObject command = new JSONObject().put("op", "download")
                                .put("quality", REMOTE_DOWNLOAD_QUALITIES[choice[0]]);
                        if (album != null) command.put("album", album.id);
                        else command.put("ids", ids);
                        ui.toast("正在发送到 " + device);
                        ui.devices.query(command, (result, error) -> {
                            if (ui.isDestroyed()) return;
                            if (!error.isEmpty()) {
                                ui.toast("下载失败 · " + error);
                                return;
                            }
                            int skipped = result.optInt("skipped");
                            int queued = result.optInt("queued", -1);
                            ui.toast(queued < 0 ? "已在 " + device + " 加入下载"
                                    : "已在 " + device + " 加入 " + queued + " 首" + (skipped > 0 ? "，" + skipped + " 首已下载" : ""));
                            ui.remoteDownloadList = result;
                            ui.open(Page.DOWNLOADS);
                        });
                    } catch (org.json.JSONException ignored) {}
                }).show();
    }
    String localMarksStamp = "";

    /** Songs this phone finishes downloading get marked on the page shown, without rebuilding it. */
    void refreshLocalMarks() {
        if (ui.playback == null || ui.remoteScreens.remoteDownloads() || ui.markUpdates.isEmpty()) return;
        String stamp = ui.downloadTasks.revision() + ":" + ui.playback.localRevision;
        if (stamp.equals(localMarksStamp)) return;
        boolean first = localMarksStamp.isEmpty();
        localMarksStamp = stamp;
        if (first) return;
        ui.downloaded = shownDownloads();
        for (Runnable update : new ArrayList<>(ui.markUpdates)) update.run();
    }

    void mark(Runnable update) {
        update.run();
        ui.markUpdates.add(update);
    }
    long remoteMarksAt;

    DownloadTasks.Downloaded shownDownloads() {
        if (ui.remoteScreens.remoteDownloads()) return ui.remoteMarks;
        return ui.downloadTasks.downloaded(ui.playback == null ? -1 : ui.playback.localRevision);
    }

    /** Fetch the controlled device's saved songs when they may have changed. */
    void syncRemoteMarks() {
        if (!ui.remoteScreens.remoteDownloads() || ui.remoteMarksBusy) return;
        String key = ui.devices.target + ":" + ui.devices.state.filesRevision;
        long now = android.os.SystemClock.elapsedRealtime();
        // Desktop devices report no files revision, so refresh theirs now and then.
        if (key.equals(ui.remoteMarksKey) && (ui.remoteMarks != null && (!ui.devices.state.filesRevision.isEmpty()
                || now - remoteMarksAt < 60_000))) return;
        ui.remoteMarksBusy = true;
        String target = ui.devices.target;
        try {
            ui.devices.query(new JSONObject().put("op", "downloads").put("action", "marks"), (result, error) -> {
                ui.remoteMarksBusy = false;
                remoteMarksAt = android.os.SystemClock.elapsedRealtime();
                ui.remoteMarksKey = key;
                if (!error.isEmpty() || ui.devices == null || !target.equals(ui.devices.target)) return;
                ui.remoteMarks = DownloadTasks.Downloaded.fromJson(result.optJSONObject("marks"));
                ui.downloaded = ui.remoteMarks;
                // Only the marks change, so browsing is not interrupted while the device downloads.
                for (Runnable update : new ArrayList<>(ui.markUpdates)) update.run();
            });
        } catch (org.json.JSONException ignored) {
            ui.remoteMarksBusy = false;
        }
    }

    void remoteDownloadsArrived(JSONObject result) {
        ui.remoteDownloadList = result;
        if (ui.page != Page.DOWNLOADS || ui.isDestroyed() || !ui.visibleRemoteDownloads) return;
        if (remoteParallelRow != null) {
            String label = remoteParallelLabel();
            if (!TextUtils.equals(remoteParallelRow.getText(), label)) remoteParallelRow.setText(label);
        }
        showDownloadRows(RemoteTidal.parse(result));
    }

    TextView remoteParallelRow;

    String remoteParallelLabel() {
        int parallel = ui.remoteDownloadList == null ? 0 : ui.remoteDownloadList.optInt("parallel");
        return parallel > 0 ? "同时下载 · " + parallel + " 首" : "同时下载";
    }

    void remoteDownloadAction(String action, String id) {
        if (!ui.remoteScreens.remoteDownloads()) return;
        try {
            JSONObject command = new JSONObject().put("op", "downloads").put("action", action)
                    .put("limit", ui.remoteDownloadLimit);
            if (id != null) command.put("id", id);
            ui.devices.query(command, (result, error) -> {
                if (!error.isEmpty()) ui.toast(error);
                else remoteDownloadsArrived(result);
            });
        } catch (org.json.JSONException ignored) {}
    }

    /** Selected rows of the controlled device's list, cancelled, retried or removed there at once. */
    void remoteDownloadBatch(DownloadBatch action, List<DownloadTasks.Job> targets) {
        if (!ui.remoteScreens.remoteDownloads()) return;
        String name = action == DownloadBatch.CANCEL ? "cancel" : action == DownloadBatch.RETRY ? "retry" : "remove";
        JSONArray ids = new JSONArray();
        for (DownloadTasks.Job job : targets) {
            ids.put(job.id);
            ui.selectedDownloads.remove(job.id);
        }
        try {
            ui.devices.query(new JSONObject().put("op", "downloads").put("action", name).put("ids", ids)
                    .put("limit", ui.remoteDownloadLimit), (result, error) -> {
                if (!error.isEmpty()) {
                    ui.toast(error);
                    return;
                }
                int skipped = result.optInt("skipped");
                if (skipped > 0) ui.toast(skipped + " 项未处理" + (action == DownloadBatch.RETRY
                        ? "，" + ui.devices.targetName() + " 可能未连接对应音源" : ""));
                remoteDownloadsArrived(result);
            });
        } catch (org.json.JSONException ignored) {}
    }

    void playRemoteDownload(DownloadTasks.Job job) {
        try {
            if (ui.devices.send(new JSONObject().put("op", "tracks").put("ids", new JSONArray().put(job.track))
                    .put("index", 0).put("mode", "play"))) ui.toast("正在 " + ui.devices.targetName() + " 播放：" + job.title);
        } catch (org.json.JSONException ignored) {}
    }

    void chooseRemoteParallel() {
        int max = ui.remoteDownloadList == null ? DownloadTasks.MAX_PARALLEL
                : Math.max(1, ui.remoteDownloadList.optInt("maxParallel", DownloadTasks.MAX_PARALLEL));
        int current = ui.remoteDownloadList == null ? 0 : ui.remoteDownloadList.optInt("parallel");
        String[] choices = new String[max];
        for (int i = 0; i < max; i++) choices[i] = (i + 1) + " 首" + (i == 0 ? "（逐首下载）" : "");
        new AlertDialog.Builder(ui).setTitle(ui.devices.targetName() + " · 同时下载")
                .setSingleChoiceItems(choices, Math.max(-1, current - 1), (dialog, choice) -> {
                    dialog.dismiss();
                    try {
                        ui.devices.query(new JSONObject().put("op", "downloads").put("action", "parallel").put("count", choice + 1),
                                (result, error) -> {
                                    if (!error.isEmpty()) ui.toast(error);
                                    else remoteDownloadsArrived(result);
                                });
                    } catch (org.json.JSONException ignored) {}
                }).setNegativeButton("取消", null).show();
    }

    void downloadChoices(String title, int quality, Uri directory, DownloadChoice callback) {
        LinearLayout dialogContent = ui.column();
        dialogContent.setPadding(ui.dp(22), ui.dp(5), ui.dp(22), ui.dp(8));
        TextView destination = ui.text(directory == null ? "系统下载目录"
                : android.provider.DocumentsContract.getTreeDocumentId(directory), 14, MUTED, false);
        dialogContent.addView(ui.text("保存位置", 14, INK, true));
        dialogContent.addView(destination);
        android.widget.Spinner selector = new android.widget.Spinner(ui);
        android.widget.ArrayAdapter<String> options = new android.widget.ArrayAdapter<>(ui,
                android.R.layout.simple_spinner_dropdown_item, DownloadTasks.QUALITY_NAMES);
        selector.setAdapter(options);
        selector.setSelection(quality);
        dialogContent.addView(ui.text("下载音质", 14, INK, true));
        dialogContent.addView(selector, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        new AlertDialog.Builder(ui).setTitle(title).setView(dialogContent)
                .setNeutralButton("更改目录", (dialog, button) -> {
                    ui.pendingDownloadChoice = callback;
                    ui.pendingDownloadTitle = title;
                    ui.pendingDownloadQuality = selector.getSelectedItemPosition();
                    ui.pendingDownloadDirectory = directory;
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    if (directory != null && android.os.Build.VERSION.SDK_INT >= 26) {
                        picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, directory);
                    }
                    ui.startActivityForResult(picker, MainActivity.JOB_DOWNLOAD_DIRECTORY);
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", (dialog, button) -> callback.chosen(selector.getSelectedItemPosition(), directory))
                .show();
    }

    void queueDownloads(List<TidalClient.Track> tracks, String source, int quality, Uri directory,
                                DownloadTasks.Resolve resolve) {
        MainActivity.downloads.submit(() -> {
            int skipped = ui.downloadTasks.add(tracks, source, quality, directory, resolve);
            ui.handler.post(() -> showQueuedDownloads(skipped));
        });
    }

    void showQueuedDownloads(int skipped) {
        if (ui.isDestroyed()) return;
        if (skipped > 0) ui.toast(skipped + " 首已下载或正在下载");
        ui.open(Page.DOWNLOADS);
        refreshDownloads();
    }

    void offlinePage() {
        if (ui.offlineAlbum != null) {
            DownloadTasks.OfflineAlbum album = ui.offlineAlbum;
            ui.content.addView(ui.primary("播放全部", () -> playOffline(album.tracks, 0)));
            for (int i = 0; i < album.tracks.size(); i++) {
                int index = i;
                TidalClient.Track track = album.tracks.get(i);
                ui.content.addView(ui.entry(track.title, track.artist, "", () -> playOffline(album.tracks, index)));
            }
            return;
        }
        LinearLayout destination = ui.content;
        destination.addView(ui.text("正在读取已下载音乐…", 14, MUTED, false));
        ui.worker.submit(() -> {
            List<DownloadTasks.OfflineAlbum> albums = ui.downloadTasks.offlineAlbums();
            ui.handler.post(() -> {
                if (ui.isDestroyed() || ui.page != Page.OFFLINE || ui.content != destination || ui.offlineAlbum != null) return;
                destination.removeAllViews();
                if (albums.isEmpty()) {
                    ui.panel(destination, "暂无已下载音乐", "完成下载后，可以在这里离线播放。");
                    return;
                }
                for (DownloadTasks.OfflineAlbum album : albums) {
                    destination.addView(ui.entry(album.title, album.source + " · " + album.tracks.size() + " 首", "", () -> {
                        ui.offlineAlbum = album;
                        ui.render();
                    }));
                }
            });
        });
    }

    void playOffline(List<TidalClient.Track> tracks, int index) {
        if (ui.playback == null) return;
        ui.catalogueScreens.playList(tracks, index, false);
        ui.open(Page.PLAYER);
    }

    DownloadTasks.Resolve downloadResolver(String source) {
        if (ui.playback == null) return null;
        if ("网易云".equals(source)) return DownloadTasks.netease(ui.playback.netease());
        return ui.playback.client == null ? null : DownloadTasks.tidal(ui.playback.client);
    }

    void downloadsPage() {
        ui.visibleRemoteDownloads = ui.remoteScreens.remoteDownloads();
        remoteParallelRow = null;
        if (ui.visibleRemoteDownloads) {
            TextView where = ui.text("正在查看「" + ui.devices.targetName() + "」上的下载，文件保存在该设备", 13, ACCENT, true);
            where.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(8));
            ui.content.addView(where);
            Button row = ui.menuRow(remoteParallelLabel(), "设置该设备同时下载的歌曲数", this::chooseRemoteParallel);
            ui.content.addView(row);
            remoteParallelRow = row;
            downloadRows = ui.column();
            ui.content.addView(downloadRows);
            downloadSignature = null;
            if (ui.remoteDownloadList != null) showDownloadRows(RemoteTidal.parse(ui.remoteDownloadList));
            refreshDownloads();
            return;
        }
        ui.content.addView(ui.menuRow("同时下载 · " + ui.downloadTasks.parallel() + " 首", "设置同时下载的歌曲数",
                this::chooseParallelDownloads));
        downloadRows = ui.column();
        ui.content.addView(downloadRows);
        downloadSignature = null;
        refreshDownloads();
    }

    /** How many songs download at the same time (1–5). */
    void chooseParallelDownloads() {
        String[] choices = new String[DownloadTasks.MAX_PARALLEL];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = (i + 1) + " 首" + (i == 0 ? "（逐首下载）" : i == 2 ? "（推荐）" : "");
        }
        new AlertDialog.Builder(ui).setTitle("同时下载")
                .setSingleChoiceItems(choices, ui.downloadTasks.parallel() - 1, (dialog, choice) -> {
                    dialog.dismiss();
                    ui.downloadTasks.setParallel(choice + 1);
                    ui.toast("同时下载 " + (choice + 1) + " 首，正在下载的歌曲不受影响");
                    ui.render();
                }).setNegativeButton("取消", null).show();
    }

    void refreshDownloads() {
        if (downloadRefreshPending || ui.page != Page.DOWNLOADS) return;
        if (ui.visibleRemoteDownloads) {
            if (!ui.remoteScreens.remoteDownloads()) return;
            downloadRefreshPending = true;
            try {
                ui.devices.query(new JSONObject().put("op", "downloads").put("action", "list")
                        .put("limit", ui.remoteDownloadLimit), (result, error) -> {
                    downloadRefreshPending = false;
                    if (error.isEmpty()) remoteDownloadsArrived(result);
                });
            } catch (org.json.JSONException ignored) {
                downloadRefreshPending = false;
            }
            return;
        }
        downloadRefreshPending = true;
        ui.worker.submit(() -> {
            List<DownloadTasks.Job> jobs = ui.downloadTasks.snapshot();
            ui.handler.post(() -> {
                downloadRefreshPending = false;
                if (ui.page == Page.DOWNLOADS && !ui.isDestroyed()) showDownloadRows(jobs);
            });
        });
    }

    void showDownloadRows(List<DownloadTasks.Job> jobs) {
        List<DownloadTasks.Job> visible = new ArrayList<>();
        java.util.Set<String> visibleIds = new java.util.HashSet<>();
        for (DownloadTasks.Job job : jobs) {
            if (downloadFilter == 0 || downloadGroup(job) == downloadFilter) {
                visible.add(job);
                visibleIds.add(job.id);
            }
        }
        ui.selectedDownloads.retainAll(visibleIds);
        StringBuilder signature = new StringBuilder().append(expandedDownloads).append(':').append(downloadFilter)
                .append(':').append(ui.selectingDownloads).append(':').append(ui.selectedDownloads)
                .append(':').append(ui.visibleRemoteDownloads && ui.remoteDownloadList != null ? ui.remoteDownloadList.optInt("total") : 0);
        for (DownloadTasks.Job job : jobs) {
            signature.append('|').append(job.id).append(':').append(downloadStatus(job))
                    .append(':').append(downloadProgress(job)).append(':').append(job.error).append(':').append(job.resolution);
        }
        if (signature.toString().equals(downloadSignature)) return;
        downloadSignature = signature.toString();
        downloadRows.removeAllViews();

        int[] counts = new int[4];
        counts[0] = jobs.size();
        for (DownloadTasks.Job job : jobs) counts[downloadGroup(job)]++;
        String[] labels = {"全部", "进行中", "已完成", "失败和取消"};
        LinearLayout filter = ui.row();
        filter.setPadding(0, ui.dp(2), 0, ui.dp(12));
        downloadRows.addView(filter);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = ui.text(labels[i] + "\n" + counts[i], 13, i == downloadFilter ? BG : MUTED, true);
            tab.setGravity(Gravity.CENTER);
            tab.setSelected(i == downloadFilter);
            tab.setBackground(ui.shape(i == downloadFilter ? ACCENT : SURFACE, 10));
            tab.setContentDescription(labels[i] + " " + counts[i] + " 项");
            tab.setFocusable(true);
            tab.setOnClickListener(view -> {
                if (downloadFilter != index) ui.selectedDownloads.clear();
                downloadFilter = index;
                showDownloadRows(jobs);
                refreshDownloads();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ui.dp(54), 1);
            if (i > 0) params.leftMargin = ui.dp(5);
            filter.addView(tab, params);
        }

        if (!visible.isEmpty() || ui.selectingDownloads) showDownloadSelection(jobs, visible);
        if (counts[downloadFilter] == 0) {
            ui.panel(downloadRows, downloadFilter == 0 ? "暂无下载任务" : "暂无" + labels[downloadFilter] + "任务",
                    downloadFilter == 0 ? "下载歌曲或专辑后，可在这里查看进度。" : "");
            return;
        }
        java.util.Map<String, List<DownloadTasks.Job>> albums = new java.util.LinkedHashMap<>();
        for (DownloadTasks.Job job : jobs) {
            if (job.batch.isEmpty()) continue;
            List<DownloadTasks.Job> items = albums.get(job.batch);
            if (items == null) {
                items = new ArrayList<>();
                albums.put(job.batch, items);
            }
            items.add(job);
        }
        if (!ui.selectingDownloads) {
            for (List<DownloadTasks.Job> album : albums.values()) {
                List<DownloadTasks.Job> matches = new ArrayList<>();
                for (DownloadTasks.Job job : album) {
                    if (downloadFilter == 0 || downloadGroup(job) == downloadFilter) matches.add(job);
                }
                if (!matches.isEmpty()) downloadAlbum(album, matches, jobs);
            }
        }
        for (int group = 1; group < labels.length; group++) {
            if (downloadFilter != 0 && downloadFilter != group) continue;
            boolean hasRows = false;
            for (DownloadTasks.Job job : visible) {
                if (downloadGroup(job) == group && (ui.selectingDownloads || job.batch.isEmpty())) hasRows = true;
            }
            if (!hasRows) continue;
            TextView heading = ui.text(labels[group], 15, INK, true);
            heading.setPadding(ui.dp(2), ui.dp(8), 0, ui.dp(8));
            downloadRows.addView(heading);
            for (DownloadTasks.Job job : visible) {
                if (downloadGroup(job) == group && (ui.selectingDownloads || job.batch.isEmpty())) downloadRow(job, jobs);
            }
        }
        int total = ui.visibleRemoteDownloads && ui.remoteDownloadList != null ? ui.remoteDownloadList.optInt("total", jobs.size()) : 0;
        if (total > jobs.size() && ui.remoteDownloadLimit < RemoteTidal.MAX_LIST) {
            downloadRows.addView(ui.menuRow("显示更多", "已显示最近 " + jobs.size() + " 项，共 " + total + " 项", () -> {
                ui.remoteDownloadLimit = Math.min(RemoteTidal.MAX_LIST, ui.remoteDownloadLimit + 300);
                ui.downloadNextRefresh = 0;
                refreshDownloads();
            }));
        } else if (total > jobs.size()) {
            downloadRows.addView(ui.text("仅显示最近 " + jobs.size() + " 项，共 " + total + " 项", 13, MUTED, false));
        }
    }

    void downloadAlbum(List<DownloadTasks.Job> album, List<DownloadTasks.Job> matches,
                               List<DownloadTasks.Job> jobs) {
        DownloadTasks.Job first = album.get(0);
        int completed = 0;
        List<DownloadTasks.Job> failed = new ArrayList<>();
        for (DownloadTasks.Job job : album) {
            if (job.state == DownloadTasks.State.COMPLETE) completed++;
            if (job.state == DownloadTasks.State.FAILED) failed.add(job);
        }
        boolean expanded = expandedDownloads.contains(first.batch);
        String detail = "已完成 " + completed + "/" + album.size() + " 首";
        if (!failed.isEmpty()) detail += " · " + failed.size() + " 首失败";
        LinearLayout section = ui.panel(downloadRows, first.albumTitle, detail);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(album.size());
        progress.setProgress(completed);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        section.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(6)));
        LinearLayout actions = ui.row();
        actions.addView(ui.action(expanded ? "收起" : "展开", (expanded ? "收起 " : "展开 ") + first.albumTitle, () -> {
            if (!expandedDownloads.remove(first.batch)) expandedDownloads.add(first.batch);
            showDownloadRows(jobs);
        }));
        if (!failed.isEmpty()) actions.addView(ui.action("只重试失败项", "重试 " + first.albumTitle + " 的失败下载", () -> {
            if (ui.visibleRemoteDownloads) {
                for (DownloadTasks.Job job : failed) remoteDownloadAction("retry", job.id);
                return;
            }
            DownloadTasks.Resolve resolve = downloadResolver(first.source);
            if (resolve == null) {
                ui.toast("请先连接 " + first.source);
                return;
            }
            ui.worker.submit(() -> {
                for (DownloadTasks.Job job : failed) ui.downloadTasks.retry(job.id, job.directory, resolve);
                ui.handler.post(() -> { if (!ui.isDestroyed()) refreshDownloads(); });
            });
        }));
        section.addView(actions);
        if (expanded) {
            java.util.Collections.sort(matches, (a, b) -> Integer.compare(a.order, b.order));
            for (DownloadTasks.Job job : matches) downloadRow(job, jobs);
        }
    }

    int downloadGroup(DownloadTasks.Job job) {
        if (job.active()) return 1;
        return job.state == DownloadTasks.State.COMPLETE ? 2 : 3;
    }

    void downloadRow(DownloadTasks.Job job, List<DownloadTasks.Job> jobs) {
        LinearLayout item = ui.column();
        item.setPadding(ui.dp(12), ui.dp(9), ui.dp(6), ui.dp(9));
        item.setBackground(ui.shape(ui.selectedDownloads.contains(job.id) ? RAISED : SURFACE, 12));
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.bottomMargin = ui.dp(6);
        downloadRows.addView(item, card);

        LinearLayout line = ui.row();
        item.addView(line);
        item.setOnLongClickListener(view -> {
            ui.selectingDownloads = true;
            ui.selectedDownloads.add(job.id);
            showDownloadRows(jobs);
            return true;
        });
        if (ui.selectingDownloads) item.setOnClickListener(view -> toggleDownloadSelection(job, jobs));
        boolean complete = job.state == DownloadTasks.State.COMPLETE;
        boolean failed = job.state == DownloadTasks.State.FAILED;
        int tone = failed ? 0xffffb2a8 : job.state == DownloadTasks.State.CANCELLED ? MUTED : ACCENT;
        TextView icon = ui.text(complete ? "✓" : failed ? "!" : job.active() ? "↓" : "–", 20, tone, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(ui.shape(RAISED, 10));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (ui.selectingDownloads) {
            android.widget.CheckBox select = new android.widget.CheckBox(ui);
            select.setButtonTintList(ColorStateList.valueOf(ACCENT));
            select.setContentDescription("选择 " + job.title);
            select.setChecked(ui.selectedDownloads.contains(job.id));
            select.setOnClickListener(view -> toggleDownloadSelection(job, jobs));
            line.addView(select, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        } else line.addView(icon, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        LinearLayout details = ui.column();
        details.setPadding(ui.dp(10), 0, ui.dp(2), 0);
        line.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = ui.text(job.title, 15, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(title);
        TextView metadata = ui.text(job.source + (job.artist.isEmpty() ? "" : " · " + job.artist), 12, MUTED, false);
        metadata.setSingleLine();
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(metadata);
        if (!job.resolution.isEmpty()) details.addView(ui.text(job.resolution, 11, MUTED, false));
        TextView status = ui.text(downloadStatus(job), 12, tone, true);
        status.setPadding(0, ui.dp(3), 0, 0);
        details.addView(status);

        if (ui.visibleRemoteDownloads) {
            if (job.active()) line.addView(ui.action("取消", "取消下载 " + job.title, () -> remoteDownloadAction("cancel", job.id)));
            else {
                if (complete && "TIDAL".equals(job.source) && job.track > 0) {
                    line.addView(ui.action("播放", "在远程设备播放 " + job.title, () -> playRemoteDownload(job)));
                } else if (!complete) line.addView(ui.action("重试", "重新下载 " + job.title, () -> remoteDownloadAction("retry", job.id)));
                line.addView(ui.action("移除", "移除下载记录 " + job.title, () -> remoteDownloadAction("remove", job.id)));
            }
        } else if (!ui.selectingDownloads && job.active()) {
            line.addView(ui.action("取消", "取消下载 " + job.title, () -> {
                ui.downloadTasks.cancel(job.id);
                refreshDownloads();
            }));
        } else if (!ui.selectingDownloads) {
            if (complete) line.addView(ui.action("播放", "播放已下载歌曲 " + job.title, () -> openDownload(job)));
            else line.addView(ui.action("重试", "重新下载 " + job.title, () -> retryDownload(job)));
            Button more = ui.iconButton("more", "下载选项 " + job.title, () -> downloadOptions(job));
            line.addView(more);
        }

        if (job.state == DownloadTasks.State.TRANSFERRING) {
            LinearLayout transfer = ui.column();
            transfer.setPadding(ui.dp(50), ui.dp(5), ui.dp(8), 0);
            android.widget.ProgressBar progress = new android.widget.ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(job.total <= 0);
            progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            if (job.total > 0) progress.setProgress(downloadPercent(job));
            transfer.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(6)));
            transfer.addView(ui.text(downloadProgress(job), 11, MUTED, false));
            item.addView(transfer);
        } else if (failed && !job.error.isEmpty()) {
            TextView reason = ui.text(job.error, 12, MUTED, false);
            reason.setPadding(ui.dp(50), ui.dp(4), ui.dp(8), 0);
            item.addView(reason);
        }
    }

    void toggleDownloadSelection(DownloadTasks.Job job, List<DownloadTasks.Job> jobs) {
        if (!ui.selectedDownloads.remove(job.id)) ui.selectedDownloads.add(job.id);
        showDownloadRows(jobs);
    }

    void showDownloadSelection(List<DownloadTasks.Job> jobs, List<DownloadTasks.Job> visible) {
        LinearLayout bar = ui.row();
        downloadRows.addView(bar);
        if (!ui.selectingDownloads) {
            Button select = ui.secondary("多选", () -> {
                ui.selectingDownloads = true;
                showDownloadRows(jobs);
            });
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, ui.dp(38));
            chip.topMargin = ui.dp(4);
            chip.bottomMargin = ui.dp(4);
            bar.addView(select, chip);
            return;
        }
        TextView summary = ui.text("已选 " + ui.selectedDownloads.size() + " 项", 13, INK, true);
        bar.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        boolean all = !visible.isEmpty() && ui.selectedDownloads.size() == visible.size();
        Button selectAll = ui.action(all ? "清空" : "全选", all ? "清空选择" : "全选当前列表", () -> {
            ui.selectedDownloads.clear();
            if (!all) for (DownloadTasks.Job job : visible) ui.selectedDownloads.add(job.id);
            showDownloadRows(jobs);
        });
        selectAll.setEnabled(!visible.isEmpty());
        bar.addView(selectAll);
        Button actions = ui.action("管理", "批量管理下载", () -> showDownloadBatch(visible));
        actions.setEnabled(!ui.selectedDownloads.isEmpty());
        bar.addView(actions);
        bar.addView(ui.action("完成", "退出多选", () -> {
            ui.selectingDownloads = false;
            ui.selectedDownloads.clear();
            showDownloadRows(jobs);
        }));
    }

    boolean eligibleDownload(DownloadTasks.Job job, DownloadBatch action) {
        switch (action) {
            case CANCEL: return job.active();
            case RETRY: return job.state == DownloadTasks.State.FAILED || job.state == DownloadTasks.State.CANCELLED;
            case REMOVE: return !job.active();
            case DELETE: return job.state == DownloadTasks.State.COMPLETE;
            default: return false;
        }
    }

    void showDownloadBatch(List<DownloadTasks.Job> visible) {
        List<String> labels = new ArrayList<>();
        List<Runnable> options = new ArrayList<>();
        String[] names = {"取消下载", "重试下载", "移除记录", "删除文件"};
        for (DownloadBatch action : DownloadBatch.values()) {
            // Files on the controlled device are deleted there only one by one, from its own list.
            if (ui.visibleRemoteDownloads && action == DownloadBatch.DELETE) continue;
            List<DownloadTasks.Job> targets = new ArrayList<>();
            for (DownloadTasks.Job job : visible) {
                if (ui.selectedDownloads.contains(job.id) && eligibleDownload(job, action)) targets.add(job);
            }
            if (targets.isEmpty()) continue;
            labels.add(names[action.ordinal()] + " · " + targets.size() + " 项");
            options.add(() -> {
                if (action == DownloadBatch.DELETE) {
                    new AlertDialog.Builder(ui).setTitle("删除 " + targets.size() + " 个下载文件？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (dialog, button) -> runDownloadBatch(action, targets)).show();
                } else runDownloadBatch(action, targets);
            });
        }
        if (!labels.isEmpty()) new AlertDialog.Builder(ui).setTitle("批量管理下载")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> options.get(index).run()).show();
    }

    void runDownloadBatch(DownloadBatch action, List<DownloadTasks.Job> targets) {
        if (ui.visibleRemoteDownloads) {
            remoteDownloadBatch(action, targets);
            return;
        }
        DownloadTasks.Resolve tidal = action == DownloadBatch.RETRY ? downloadResolver("TIDAL") : null;
        DownloadTasks.Resolve netease = action == DownloadBatch.RETRY ? downloadResolver("网易云") : null;
        Uri destination = MusicDownload.directory(ui);
        for (DownloadTasks.Job job : targets) ui.selectedDownloads.remove(job.id);
        ui.worker.submit(() -> {
            int skipped = 0;
            for (DownloadTasks.Job job : targets) {
                switch (action) {
                    case CANCEL: ui.downloadTasks.cancel(job.id); break;
                    case RETRY:
                        DownloadTasks.Resolve resolve = "网易云".equals(job.source) ? netease : tidal;
                        if (resolve == null) skipped++;
                        else ui.downloadTasks.retry(job.id, destination, resolve);
                        break;
                    default:
                        if (!ui.downloadTasks.remove(job.id, action == DownloadBatch.DELETE)) skipped++;
                }
            }
            int remaining = skipped;
            ui.handler.post(() -> {
                if (ui.isDestroyed()) return;
                if (remaining > 0) ui.toast(remaining + " 项未处理" + (action == DownloadBatch.RETRY ? "，请先连接对应音源" : ""));
                refreshDownloads();
            });
        });
    }

    String downloadProgress(DownloadTasks.Job job) {
        if (job.state != DownloadTasks.State.TRANSFERRING) return "";
        String transferred = android.text.format.Formatter.formatShortFileSize(ui, Math.max(0, job.bytes));
        return job.total > 0 ? transferred + " / " + android.text.format.Formatter.formatShortFileSize(ui, job.total) : transferred;
    }

    int downloadPercent(DownloadTasks.Job job) {
        return (int) Math.max(0, Math.min(100, job.bytes * 100.0 / job.total));
    }

    String downloadStatus(DownloadTasks.Job job) {
        switch (job.state) {
            case QUEUED: return "排队中";
            case PREPARING: return "获取下载地址…";
            case WAITING: return "等待系统下载";
            case TRANSFERRING: return job.total > 0 ? "下载中 · " + downloadPercent(job) + "%" : "下载中";
            case COMPLETE: return "已完成";
            case CANCELLED: return "已取消";
            default: return "下载失败";
        }
    }

    void retryDownload(DownloadTasks.Job job) {
        DownloadTasks.Resolve resolve = downloadResolver(job.source);
        if (resolve == null) {
            ui.toast("请先连接 " + job.source);
            return;
        }
        ui.downloadTasks.retry(job.id, MusicDownload.directory(ui), resolve);
        refreshDownloads();
    }

    void downloadOptions(DownloadTasks.Job job) {
        List<String> labels = new ArrayList<>();
        List<Runnable> choices = new ArrayList<>();
        labels.add("移除记录");
        choices.add(() -> removeDownload(job, false));
        if (job.state == DownloadTasks.State.COMPLETE) {
            labels.add("删除文件");
            choices.add(() -> new AlertDialog.Builder(ui).setTitle("删除已下载文件？")
                    .setMessage(job.title).setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, button) -> removeDownload(job, true)).show());
        } else if (job.state == DownloadTasks.State.FAILED && "TIDAL".equals(job.source)) {
            labels.add("打开 TIDAL");
            choices.add(() -> ui.loginFlow.openTidalBrowser(Uri.parse("https://tidal.com/browse/track/" + job.track)));
        }
        new AlertDialog.Builder(ui).setTitle(job.title)
                .setItems(labels.toArray(new String[0]), (dialog, index) -> choices.get(index).run()).show();
    }

    void openDownload(DownloadTasks.Job job) {
        ui.worker.submit(() -> {
            TidalClient.Track track = ui.downloadTasks.playable(job.id);
            ui.handler.post(() -> {
                if (ui.isDestroyed() || ui.page != Page.DOWNLOADS) return;
                if (track == null) ui.toast("找不到已下载文件");
                else playOffline(java.util.Collections.singletonList(track), 0);
            });
        });
    }

    void removeDownload(DownloadTasks.Job job, boolean deleteFile) {
        ui.worker.submit(() -> {
            boolean removed = ui.downloadTasks.remove(job.id, deleteFile);
            ui.handler.post(() -> {
                if (ui.isDestroyed()) return;
                if (!removed) ui.toast("无法移除此项目");
                refreshDownloads();
            });
        });
    }
}
