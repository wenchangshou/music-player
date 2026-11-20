package com.tidalgui.player;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;

/** Part of {@link MainActivity}: WebDavScreens. */
@UnstableApi
final class WebDavScreens {
    final MainActivity ui;

    WebDavScreens(MainActivity ui) {
        this.ui = ui;
    }

    boolean webdavLoading;
    String webdavError = "";

    String webdavTitle() {
        if (ui.webdavCurrentServer == null) return "WebDAV 网络存储";
        if (ui.webdavCurrentPath == null || ui.webdavCurrentPath.isEmpty() || "/".equals(ui.webdavCurrentPath)) {
            return ui.webdavCurrentServer.name;
        }
        String p = ui.webdavCurrentPath.endsWith("/")
                ? ui.webdavCurrentPath.substring(0, ui.webdavCurrentPath.length() - 1) : ui.webdavCurrentPath;
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    void webdavPage() {
        if (ui.webdavCurrentServer == null) {
            webdavServerList();
        } else {
            webdavDirectoryBrowser();
        }
    }

    void webdavServerList() {
        List<WebDavClient.Server> servers = WebDavClient.loadServers(ui);
        LinearLayout topBar = ui.row();
        topBar.addView(ui.text("已配置的 WebDAV 服务器", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(ui.withIcon(ui.secondary("添加服务器", () -> showEditWebDavServer(null)), "plus", ACCENT));
        ui.content.addView(topBar);

        if (servers.isEmpty()) {
            ui.empty("尚未添加 WebDAV 服务器", "支持连接群晖、威联通、AList、坚果云等任意 WebDAV 服务，直接流式播放网络音频。");
            ui.content.addView(ui.toolbar(ui.withIcon(ui.primary("立即添加 WebDAV 服务器", () -> showEditWebDavServer(null)), "plus", BG)));
            return;
        }

        for (WebDavClient.Server server : servers) {
            LinearLayout card = ui.panel(server.name, server.url + (server.user.isEmpty() ? "" : " · " + server.user));
            Button browse = ui.withIcon(ui.primary("进入浏览", () -> {
                ui.webdavCurrentServer = server;
                ui.webdavCurrentPath = "/";
                loadWebDavDirectory();
            }), "folder", BG);
            Button edit = ui.secondary("设置 / 删除", () -> showEditWebDavServer(server));
            card.addView(ui.toolbar(browse, edit));
        }
    }

    void webdavDirectoryBrowser() {
        LinearLayout navBar = ui.row();
        navBar.setPadding(0, 0, 0, ui.dp(8));
        boolean canGoUp = ui.webdavCurrentPath != null && !ui.webdavCurrentPath.isEmpty() && !"/".equals(ui.webdavCurrentPath);
        Button backBtn = ui.withIcon(ui.secondary(canGoUp ? "上一级" : "服务器列表", () -> {
            if (canGoUp) {
                String p = ui.webdavCurrentPath.endsWith("/")
                        ? ui.webdavCurrentPath.substring(0, ui.webdavCurrentPath.length() - 1) : ui.webdavCurrentPath;
                int lastSlash = p.lastIndexOf('/');
                ui.webdavCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : "/";
                loadWebDavDirectory();
            } else {
                ui.webdavCurrentServer = null;
                ui.webdavCurrentPath = "";
                ui.webdavItems.clear();
                ui.render();
            }
        }), "back", ACCENT);
        navBar.addView(backBtn);

        String pathDisplay = (ui.webdavCurrentPath == null || ui.webdavCurrentPath.isEmpty() || "/".equals(ui.webdavCurrentPath))
                ? "/" : ui.webdavCurrentPath;
        TextView pathView = ui.single(pathDisplay, 13, MUTED, false);
        pathView.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        navBar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        navBar.addView(ui.iconButton("refresh", "刷新", this::loadWebDavDirectory));
        ui.content.addView(navBar);

        List<WebDavClient.Item> audioItems = new ArrayList<>();
        List<WebDavClient.Item> dirItems = new ArrayList<>();
        for (WebDavClient.Item item : ui.webdavItems) {
            if (item.isDirectory) dirItems.add(item);
            else if (item.isAudio) audioItems.add(item);
        }

        if (!audioItems.isEmpty()) {
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            String folderName = webdavTitle();
            for (WebDavClient.Item item : audioItems) {
                try {
                    audioTracks.add(WebDavClient.toTrack(ui.webdavCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }
            Button playAll = ui.withIcon(ui.primary("播放全部 (" + audioTracks.size() + ")", () -> ui.catalogueScreens.playList(audioTracks, 0, false)), "play", BG);
            Button random = ui.withIcon(ui.secondary("随机", () -> ui.catalogueScreens.playList(audioTracks, 0, true)), "shuffle", ACCENT);
            Button downloadAll = ui.withIcon(ui.secondary("下载本目录", () -> downloadWebDavDirectory(ui.webdavCurrentServer, folderName, audioItems)), "download", ACCENT);
            ui.content.addView(ui.toolbar(playAll, random, downloadAll));
        }

        if (webdavLoading) {
            ui.empty("正在加载目录…", "");
            return;
        }

        if (!webdavError.isEmpty()) {
            ui.empty("加载失败 · " + webdavError, "");
            ui.content.addView(ui.toolbar(ui.withIcon(ui.secondary("重试", this::loadWebDavDirectory), "refresh", ACCENT)));
            return;
        }

        if (ui.webdavItems.isEmpty()) {
            ui.empty("目录为空", "此路径下未发现文件或文件夹。");
            return;
        }

        if (!dirItems.isEmpty()) {
            ui.section("文件夹 (" + dirItems.size() + ")");
            for (WebDavClient.Item dir : dirItems) {
                LinearLayout row = ui.entry(dir.name, "文件夹", "", () -> {
                    ui.webdavCurrentPath = dir.path;
                    loadWebDavDirectory();
                });
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("folder", ACCENT, ui.dp(22)));
                ui.content.addView(row);
            }
        }

        if (!audioItems.isEmpty()) {
            ui.section("音频文件 (" + audioItems.size() + ")");
            String folderName = webdavTitle();
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            for (WebDavClient.Item item : audioItems) {
                try {
                    audioTracks.add(WebDavClient.toTrack(ui.webdavCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }

            for (int i = 0; i < audioTracks.size(); i++) {
                int at = i;
                TidalClient.Track track = audioTracks.get(i);
                WebDavClient.Item item = audioItems.get(i);
                String sizeLabel = UiKit.formatBytes(item.size);
                String sub = sizeLabel.isEmpty() ? "WebDAV 音频" : sizeLabel + " · WebDAV";
                LinearLayout row = ui.entry(track.title, sub, "", () -> ui.catalogueScreens.playList(audioTracks, at, false));
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("note", ACCENT, ui.dp(22)));
                row.addView(ui.iconButton("more", "更多 " + track.title, () -> webdavTrackMenu(track, item)));
                ui.content.addView(row);
            }
        }
    }

    void loadWebDavDirectory() {
        if (ui.webdavCurrentServer == null) return;
        webdavLoading = true;
        webdavError = "";
        ui.webdavItems.clear();
        ui.render();

        WebDavClient.Server targetServer = ui.webdavCurrentServer;
        String targetPath = ui.webdavCurrentPath;
        ui.worker.submit(() -> {
            try {
                List<WebDavClient.Item> result = WebDavClient.list(targetServer, targetPath);
                ui.handler.post(() -> {
                    if (ui.webdavCurrentServer != targetServer || !java.util.Objects.equals(ui.webdavCurrentPath, targetPath)) return;
                    ui.webdavItems.clear();
                    ui.webdavItems.addAll(result);
                    webdavLoading = false;
                    webdavError = "";
                    ui.render();
                });
            } catch (Exception e) {
                ui.handler.post(() -> {
                    if (ui.webdavCurrentServer != targetServer || !java.util.Objects.equals(ui.webdavCurrentPath, targetPath)) return;
                    webdavLoading = false;
                    webdavError = e.getMessage() != null ? e.getMessage() : "读取目录失败";
                    ui.render();
                });
            }
        });
    }

    void showEditWebDavServer(WebDavClient.Server existing) {
        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));

        TextView nameLabel = ui.text("服务器名称", 12, MUTED, false);
        body.addView(nameLabel);
        EditText nameInput = new EditText(ui);
        nameInput.setSingleLine(true);
        nameInput.setHint("如：我的群晖 NAS / AList");
        if (existing != null) nameInput.setText(existing.name);
        body.addView(nameInput);

        TextView urlLabel = ui.text("WebDAV 地址 (URL)", 12, MUTED, false);
        urlLabel.setPadding(0, ui.dp(8), 0, 0);
        body.addView(urlLabel);
        EditText urlInput = new EditText(ui);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://192.168.1.100:5005/music/");
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) urlInput.setText(existing.url);
        body.addView(urlInput);

        TextView userLabel = ui.text("用户名 (可选)", 12, MUTED, false);
        userLabel.setPadding(0, ui.dp(8), 0, 0);
        body.addView(userLabel);
        EditText userInput = new EditText(ui);
        userInput.setSingleLine(true);
        userInput.setHint("如：admin");
        if (existing != null) userInput.setText(existing.user);
        body.addView(userInput);

        TextView passLabel = ui.text("密码 (可选)", 12, MUTED, false);
        passLabel.setPadding(0, ui.dp(8), 0, 0);
        body.addView(passLabel);
        EditText passInput = new EditText(ui);
        passInput.setSingleLine(true);
        passInput.setHint("密码");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) passInput.setText(existing.pass);
        body.addView(passInput);

        TextView status = ui.text("", 12, 0xffff6b6b, false);
        status.setPadding(0, ui.dp(8), 0, 0);
        body.addView(status);

        AlertDialog.Builder builder = new AlertDialog.Builder(ui)
                .setTitle(existing == null ? "添加 WebDAV 服务器" : "编辑 WebDAV 服务器")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("测试并保存", null);

        if (existing != null) {
            builder.setNeutralButton("删除服务器", (d, which) -> {
                new AlertDialog.Builder(ui)
                        .setTitle("删除「" + existing.name + "」？")
                        .setMessage("确定从列表中移除此 WebDAV 服务器吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d2, which2) -> {
                            WebDavClient.deleteServer(ui, existing.id);
                            if (ui.webdavCurrentServer != null && ui.webdavCurrentServer.id.equals(existing.id)) {
                                ui.webdavCurrentServer = null;
                                ui.webdavCurrentPath = "";
                                ui.webdavItems.clear();
                            }
                            ui.render();
                        }).show();
            });
        }

        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            String user = userInput.getText().toString().trim();
            String pass = passInput.getText().toString();

            if (url.isEmpty()) {
                urlInput.setError("请输入服务器地址");
                return;
            }
            if (name.isEmpty()) name = "WebDAV";

            String id = existing != null ? existing.id : UUID.randomUUID().toString();
            WebDavClient.Server candidate = new WebDavClient.Server(id, name, url, user, pass);

            btn.setEnabled(false);
            status.setText("正在测试连接…");
            status.setTextColor(ACCENT);

            ui.worker.submit(() -> {
                try {
                    WebDavClient.testConnection(candidate);
                    ui.handler.post(() -> {
                        WebDavClient.saveServer(ui, candidate);
                        dialog.dismiss();
                        ui.toast("连接成功并已保存");
                        ui.render();
                    });
                } catch (Exception ex) {
                    ui.handler.post(() -> {
                        btn.setEnabled(true);
                        status.setTextColor(0xffff6b6b);
                        status.setText("测试连接失败: " + (ex.getMessage() != null ? ex.getMessage() : "无法连接"));
                    });
                }
            });
        });
    }

    void pickDownloadRoot(FolderPickerCallback callback) {
        if (ui.playback == null || ui.playback.localLibrary == null) return;
        List<Uri> roots = ui.playback.localLibrary.folders();
        if (roots.isEmpty()) {
            ui.toast("请先在【本地文件】中扫描或授权一个存储目录");
            ui.importExport.chooseFolders();
            return;
        }
        if (roots.size() == 1) {
            callback.onFolder(roots.get(0));
            return;
        }
        String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            labels[i] = LocalMusicFiles.displayPath(roots.get(i));
        }
        new AlertDialog.Builder(ui)
                .setTitle("选择存储目录")
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < roots.size()) {
                        callback.onFolder(roots.get(which));
                    }
                })
                .show();
    }

    void webdavTrackMenu(TidalClient.Track track, WebDavClient.Item item) {
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add("下一首播放");
        actions.add(() -> { if (ui.playback != null) { ui.playback.enqueue(track, true); ui.toast("已加入下一首"); } });
        choices.add("追加到队列");
        actions.add(() -> { if (ui.playback != null) { ui.playback.enqueue(track, false); ui.toast("已加入队列"); } });

        WebDavClient.Server targetServer = ui.webdavCurrentServer;
        if (targetServer == null) {
            for (WebDavClient.Server s : WebDavClient.loadServers(ui)) {
                if (track.webdavUrl.startsWith(s.url) || s.authHeader().equals(track.webdavAuth)) {
                    targetServer = s;
                    break;
                }
            }
        }
        if (targetServer != null) {
            final WebDavClient.Server s = targetServer;
            choices.add("下载到本地");
            actions.add(() -> downloadWebDavTrack(s, track, item));
        }

        new AlertDialog.Builder(ui)
                .setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    void downloadWebDavTrack(WebDavClient.Server server, TidalClient.Track track, WebDavClient.Item item) {
        pickDownloadRoot(targetRoot -> {
            ui.toast("开始下载: " + track.title);
            ui.worker.submit(() -> {
                try {
                    WebDavClient.Item targetItem = item;
                    if (targetItem == null) {
                        targetItem = new WebDavClient.Item(track.title + ".flac", "", track.webdavUrl, false, 0);
                    }
                    WebDavClient.downloadFile(ui, server, targetItem, targetRoot, null);
                    ui.handler.post(() -> {
                        ui.toast("下载完成: " + track.title);
                        ui.importExport.scanDirectories(java.util.Collections.singletonList(targetRoot));
                    });
                } catch (Exception e) {
                    ui.handler.post(() -> ui.toast("下载失败: " + (e.getMessage() != null ? e.getMessage() : "网络错误")));
                }
            });
        });
    }

    void downloadWebDavDirectory(WebDavClient.Server server, String folderName, List<WebDavClient.Item> items) {
        pickDownloadRoot(targetRoot -> {
            List<WebDavClient.Item> toDownload = new ArrayList<>();
            for (WebDavClient.Item item : items) {
                if (item.isAudio || item.name.toLowerCase(Locale.ROOT).endsWith(".lrc")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".jpg")
                        || item.name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    toDownload.add(item);
                }
            }

            if (toDownload.isEmpty()) {
                ui.toast("此目录下没有可下载的文件");
                return;
            }

            new AlertDialog.Builder(ui)
                    .setTitle("下载整目录")
                    .setMessage("即将下载「" + folderName + "」共 " + toDownload.size() + " 个文件到本地存储。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始下载", (dialog, which) -> {
                        ui.toast("开始下载 " + toDownload.size() + " 个文件…");
                        ui.worker.submit(() -> {
                            int success = 0;
                            int failed = 0;
                            Uri folderUri = WebDavClient.findOrCreateDirectory(ui, targetRoot, folderName);
                            for (WebDavClient.Item item : toDownload) {
                                try {
                                    WebDavClient.downloadFile(ui, server, item, folderUri, null);
                                    success++;
                                } catch (Exception e) {
                                    failed++;
                                }
                            }
                            int totalSuccess = success;
                            int totalFailed = failed;
                            ui.handler.post(() -> {
                                ui.toast("目录下载完成：成功 " + totalSuccess + " 个文件" + (totalFailed > 0 ? "，失败 " + totalFailed + " 个" : ""));
                                ui.importExport.scanDirectories(java.util.Collections.singletonList(targetRoot));
                            });
                        });
                    })
                    .show();
        });
    }
}
