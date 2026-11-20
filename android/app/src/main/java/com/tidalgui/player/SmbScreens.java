package com.tidalgui.player;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;

/** Part of {@link MainActivity}: SmbScreens. */
@UnstableApi
final class SmbScreens {
    final MainActivity ui;

    SmbScreens(MainActivity ui) {
        this.ui = ui;
    }

    boolean smbLoading;
    String smbError = "";

    String smbTitle() {
        if (ui.smbCurrentServer == null) return "SMB / Windows 共享";
        if (ui.smbCurrentPath == null || ui.smbCurrentPath.isEmpty() || "/".equals(ui.smbCurrentPath)
                || ui.smbCurrentPath.equalsIgnoreCase(ui.smbCurrentServer.rootUrl())) {
            return ui.smbCurrentServer.name;
        }
        String p = ui.smbCurrentPath.endsWith("/")
                ? ui.smbCurrentPath.substring(0, ui.smbCurrentPath.length() - 1) : ui.smbCurrentPath;
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    void smbPage() {
        if (ui.smbCurrentServer == null) {
            smbServerList();
        } else {
            smbDirectoryBrowser();
        }
    }

    void smbServerList() {
        List<SmbClient.Server> servers = SmbClient.loadServers(ui);
        LinearLayout topBar = ui.row();
        topBar.addView(ui.text("已配置的 SMB 共享服务器", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(ui.withIcon(ui.secondary("添加共享", () -> showEditSmbServer(null)), "plus", ACCENT));
        ui.content.addView(topBar);

        if (servers.isEmpty()) {
            ui.empty("尚未添加 SMB 共享", "连接 Windows 电脑、NAS、路由器等局域网共享文件夹，直接流式播放网络音频与下载。");
            ui.content.addView(ui.toolbar(ui.withIcon(ui.primary("立即添加 SMB 共享", () -> showEditSmbServer(null)), "plus", BG)));
            return;
        }

        for (SmbClient.Server server : servers) {
            LinearLayout card = ui.panel(server.name, server.displayAddress());
            Button browse = ui.withIcon(ui.primary("进入浏览", () -> {
                ui.smbCurrentServer = server;
                ui.smbCurrentPath = server.rootUrl();
                loadSmbDirectory();
            }), "folder", BG);
            Button edit = ui.secondary("设置 / 删除", () -> showEditSmbServer(server));
            card.addView(ui.toolbar(browse, edit));
        }
    }

    void smbDirectoryBrowser() {
        LinearLayout navBar = ui.row();
        navBar.setPadding(0, 0, 0, ui.dp(8));
        String rootRaw = ui.smbCurrentServer != null ? ui.smbCurrentServer.rootUrl() : "";
        final String root = rootRaw.endsWith("/") ? rootRaw : rootRaw + "/";
        String curRaw = ui.smbCurrentPath != null ? ui.smbCurrentPath : "";
        final String cur = curRaw.endsWith("/") ? curRaw : curRaw + "/";
        boolean canGoUp = !cur.isEmpty() && !cur.equalsIgnoreCase(root) && !"/".equals(cur);

        Button backBtn = ui.withIcon(ui.secondary(canGoUp ? "上一级" : "服务器列表", () -> {
            if (canGoUp) {
                String p = cur.endsWith("/") ? cur.substring(0, cur.length() - 1) : cur;
                int lastSlash = p.lastIndexOf('/');
                ui.smbCurrentPath = lastSlash > 0 ? p.substring(0, lastSlash + 1) : root;
                loadSmbDirectory();
            } else {
                ui.smbCurrentServer = null;
                ui.smbCurrentPath = "";
                ui.smbItems.clear();
                ui.render();
            }
        }), "back", ACCENT);
        navBar.addView(backBtn);

        String pathDisplay = smbDisplayPath(ui.smbCurrentPath);
        TextView pathView = ui.single(pathDisplay, 13, MUTED, false);
        pathView.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        navBar.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));
        navBar.addView(ui.iconButton("refresh", "刷新", this::loadSmbDirectory));
        ui.content.addView(navBar);

        List<SmbClient.Item> audioItems = new ArrayList<>();
        List<SmbClient.Item> dirItems = new ArrayList<>();
        for (SmbClient.Item item : ui.smbItems) {
            if (item.isDirectory) dirItems.add(item);
            else if (item.isAudio) audioItems.add(item);
        }

        if (!audioItems.isEmpty()) {
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            String folderName = smbTitle();
            for (SmbClient.Item item : audioItems) {
                try {
                    audioTracks.add(SmbClient.toTrack(ui.smbCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }
            Button playAll = ui.withIcon(ui.primary("播放全部 (" + audioTracks.size() + ")", () -> ui.catalogueScreens.playList(audioTracks, 0, false)), "play", BG);
            Button random = ui.withIcon(ui.secondary("随机", () -> ui.catalogueScreens.playList(audioTracks, 0, true)), "shuffle", ACCENT);
            Button downloadAll = ui.withIcon(ui.secondary("下载本目录", () -> downloadSmbDirectory(ui.smbCurrentServer, folderName, audioItems)), "download", ACCENT);
            ui.content.addView(ui.toolbar(playAll, random, downloadAll));
        }

        if (smbLoading) {
            ui.empty("正在加载目录…", "");
            return;
        }

        if (!smbError.isEmpty()) {
            ui.empty("加载失败 · " + smbError, "");
            ui.content.addView(ui.toolbar(ui.withIcon(ui.secondary("重试", this::loadSmbDirectory), "refresh", ACCENT)));
            return;
        }

        if (ui.smbItems.isEmpty()) {
            ui.empty("目录为空", "此路径下未发现文件或文件夹。");
            return;
        }

        if (!dirItems.isEmpty()) {
            ui.section("文件夹 (" + dirItems.size() + ")");
            for (SmbClient.Item dir : dirItems) {
                LinearLayout row = ui.entry(dir.name, "文件夹", "", () -> {
                    ui.smbCurrentPath = dir.url;
                    loadSmbDirectory();
                });
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("folder", ACCENT, ui.dp(22)));
                ui.content.addView(row);
            }
        }

        if (!audioItems.isEmpty()) {
            ui.section("音频文件 (" + audioItems.size() + ")");
            String folderName = smbTitle();
            List<TidalClient.Track> audioTracks = new ArrayList<>();
            for (SmbClient.Item item : audioItems) {
                try {
                    audioTracks.add(SmbClient.toTrack(ui.smbCurrentServer, item, folderName));
                } catch (Exception ignored) {}
            }

            for (int i = 0; i < audioTracks.size(); i++) {
                int at = i;
                TidalClient.Track track = audioTracks.get(i);
                SmbClient.Item item = audioItems.get(i);
                String sizeLabel = UiKit.formatBytes(item.size);
                String sub = sizeLabel.isEmpty() ? "SMB 音频" : sizeLabel + " · SMB";
                LinearLayout row = ui.entry(track.title, sub, "", () -> ui.catalogueScreens.playList(audioTracks, at, false));
                ((ImageView) row.getChildAt(0)).setImageDrawable(new PlayerIcon("note", ACCENT, ui.dp(22)));
                row.addView(ui.iconButton("more", "更多 " + track.title, () -> smbTrackMenu(track, item)));
                ui.content.addView(row);
            }
        }
    }

    void loadSmbDirectory() {
        if (ui.smbCurrentServer == null) return;
        smbLoading = true;
        smbError = "";
        ui.smbItems.clear();
        ui.render();

        SmbClient.Server targetServer = ui.smbCurrentServer;
        String targetPath = ui.smbCurrentPath;
        ui.worker.submit(() -> {
            try {
                List<SmbClient.Item> result = SmbClient.list(targetServer, targetPath);
                ui.handler.post(() -> {
                    if (ui.smbCurrentServer != targetServer || !java.util.Objects.equals(ui.smbCurrentPath, targetPath)) return;
                    ui.smbItems.clear();
                    ui.smbItems.addAll(result);
                    smbLoading = false;
                    smbError = "";
                    ui.render();
                });
            } catch (Exception e) {
                ui.handler.post(() -> {
                    if (ui.smbCurrentServer != targetServer || !java.util.Objects.equals(ui.smbCurrentPath, targetPath)) return;
                    smbLoading = false;
                    smbError = e.getMessage() != null ? e.getMessage() : "读取共享目录失败";
                    ui.render();
                });
            }
        });
    }

    void showEditSmbServer(SmbClient.Server existing) {
        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));

        TextView nameLabel = ui.text("服务器备注", 12, MUTED, false);
        EditText nameInput = new EditText(ui);
        nameInput.setSingleLine(true);
        nameInput.setHint("如：家庭 NAS / 电脑共享");
        if (existing != null) nameInput.setText(existing.name);

        TextView hostLabel = ui.text("主机地址 / IP", 12, MUTED, false);
        hostLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText hostInput = new EditText(ui);
        hostInput.setSingleLine(true);
        hostInput.setHint("如：192.168.1.100 或 nas.local");
        if (existing != null) hostInput.setText(existing.host);

        TextView portLabel = ui.text("端口 (默认 445)", 12, MUTED, false);
        portLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText portInput = new EditText(ui);
        portInput.setSingleLine(true);
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setHint("445");
        if (existing != null) portInput.setText(String.valueOf(existing.port));

        TextView shareLabel = ui.text("共享文件夹名称 (可选，留空自动列出所有共享)", 12, MUTED, false);
        shareLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText shareInput = new EditText(ui);
        shareInput.setSingleLine(true);
        shareInput.setHint("如：music / share (留空则列出所有共享)");
        if (existing != null) shareInput.setText(existing.share);

        TextView userLabel = ui.text("用户名 (匿名访问可留空)", 12, MUTED, false);
        userLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText userInput = new EditText(ui);
        userInput.setSingleLine(true);
        userInput.setHint("如：guest 或 username");
        if (existing != null) userInput.setText(existing.user);

        TextView passLabel = ui.text("密码", 12, MUTED, false);
        passLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText passInput = new EditText(ui);
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) passInput.setText(existing.pass);

        TextView domainLabel = ui.text("域名 / 工作组 (可选，默认 WORKGROUP)", 12, MUTED, false);
        domainLabel.setPadding(0, ui.dp(8), 0, 0);
        EditText domainInput = new EditText(ui);
        domainInput.setSingleLine(true);
        domainInput.setHint("留空使用默认工作组");
        if (existing != null) domainInput.setText(existing.domain);

        body.addView(nameLabel);
        body.addView(nameInput);
        body.addView(hostLabel);
        body.addView(hostInput);
        body.addView(portLabel);
        body.addView(portInput);
        body.addView(shareLabel);
        body.addView(shareInput);
        body.addView(userLabel);
        body.addView(userInput);
        body.addView(passLabel);
        body.addView(passInput);
        body.addView(domainLabel);
        body.addView(domainInput);

        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(ui)
                .setTitle(existing == null ? "添加 SMB 共享" : "编辑 SMB 共享")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .setNeutralButton("测试连接", null);

        if (existing != null) {
            builder.setNegativeButton("删除", (dialog, which) -> {
                new AlertDialog.Builder(ui)
                        .setTitle("确认删除")
                        .setMessage("确定从列表中移除此 SMB 共享吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d2, w2) -> {
                            List<SmbClient.Server> list = SmbClient.loadServers(ui);
                            for (java.util.Iterator<SmbClient.Server> iterator = list.iterator(); iterator.hasNext();) {
                                if (iterator.next().id.equals(existing.id)) iterator.remove();
                            }
                            SmbClient.saveServers(ui, list);
                            if (ui.smbCurrentServer != null && ui.smbCurrentServer.id.equals(existing.id)) {
                                ui.smbCurrentServer = null;
                                ui.smbCurrentPath = "";
                                ui.smbItems.clear();
                            }
                            ui.render();
                        })
                        .show();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                ui.toast("请输入主机地址 / IP");
                return;
            }
            int port = 445;
            try {
                String pStr = portInput.getText().toString().trim();
                if (!pStr.isEmpty()) port = Integer.parseInt(pStr);
            } catch (Exception ignored) {}

            SmbClient.Server testServer = new SmbClient.Server(
                    existing != null ? existing.id : null,
                    nameInput.getText().toString().trim(),
                    host,
                    port,
                    shareInput.getText().toString().trim(),
                    userInput.getText().toString().trim(),
                    passInput.getText().toString(),
                    domainInput.getText().toString().trim()
            );

            ui.toast("正在测试连接 SMB…");
            ui.worker.submit(() -> {
                try {
                    SmbClient.testConnection(testServer);
                    ui.handler.post(() -> ui.toast("SMB 连接成功！认证通过。"));
                } catch (Exception e) {
                    ui.handler.post(() -> ui.toast("连接失败: " + (e.getMessage() != null ? e.getMessage() : "网络超时")));
                }
            });
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                ui.toast("请输入主机地址 / IP");
                return;
            }
            int port = 445;
            try {
                String pStr = portInput.getText().toString().trim();
                if (!pStr.isEmpty()) port = Integer.parseInt(pStr);
            } catch (Exception ignored) {}

            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) name = host;

            SmbClient.Server server = new SmbClient.Server(
                    existing != null ? existing.id : null,
                    name,
                    host,
                    port,
                    shareInput.getText().toString().trim(),
                    userInput.getText().toString().trim(),
                    passInput.getText().toString(),
                    domainInput.getText().toString().trim()
            );

            List<SmbClient.Server> list = SmbClient.loadServers(ui);
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(server.id)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) list.set(idx, server);
            else list.add(server);

            SmbClient.saveServers(ui, list);
            if (ui.smbCurrentServer != null && ui.smbCurrentServer.id.equals(server.id)) {
                ui.smbCurrentServer = server;
            }
            dialog.dismiss();
            ui.render();
        });
    }

    void smbTrackMenu(TidalClient.Track track, SmbClient.Item item) {
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add("下一首播放");
        actions.add(() -> { if (ui.playback != null) { ui.playback.enqueue(track, true); ui.toast("已加入下一首"); } });
        choices.add("追加到队列");
        actions.add(() -> { if (ui.playback != null) { ui.playback.enqueue(track, false); ui.toast("已加入队列"); } });

        SmbClient.Server targetServer = ui.smbCurrentServer;
        if (targetServer == null) {
            for (SmbClient.Server s : SmbClient.loadServers(ui)) {
                if (track.smbUrl.startsWith(s.rootUrl()) || (!s.host.isEmpty() && track.smbUrl.contains(s.host))) {
                    targetServer = s;
                    break;
                }
            }
        }
        if (targetServer != null) {
            final SmbClient.Server s = targetServer;
            choices.add("下载到本地");
            actions.add(() -> downloadSmbTrack(s, track, item));
        }

        new AlertDialog.Builder(ui)
                .setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    void downloadSmbTrack(SmbClient.Server server, TidalClient.Track track, SmbClient.Item item) {
        ui.webdavScreens.pickDownloadRoot(targetRoot -> {
            ui.toast("开始下载: " + track.title);
            ui.worker.submit(() -> {
                try {
                    SmbClient.Item targetItem = item;
                    if (targetItem == null) {
                        targetItem = new SmbClient.Item(track.title + ".flac", "", track.smbUrl, false, 0);
                    }
                    SmbClient.downloadFile(ui, server, targetItem, targetRoot, null);
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

    void downloadSmbDirectory(SmbClient.Server server, String folderName, List<SmbClient.Item> items) {
        ui.webdavScreens.pickDownloadRoot(targetRoot -> {
            List<SmbClient.Item> toDownload = new ArrayList<>();
            for (SmbClient.Item item : items) {
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
                            Uri folderUri = SmbClient.findOrCreateDirectory(ui, targetRoot, folderName);
                            for (SmbClient.Item item : toDownload) {
                                try {
                                    SmbClient.downloadFile(ui, server, item, folderUri, null);
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

    String smbDisplayPath(String path) {
        if (path == null || path.isEmpty()) return "/";
        try {
            java.net.URI uri = new java.net.URI(path);
            String p = uri.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Exception ignored) {
            return path;
        }
    }
}
