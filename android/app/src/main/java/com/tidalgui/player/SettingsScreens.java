package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.io.IOException;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;

/** Part of {@link MainActivity}: SettingsScreens. */
@UnstableApi
final class SettingsScreens {
    final MainActivity ui;

    SettingsScreens(MainActivity ui) {
        this.ui = ui;
    }

    boolean updateBusy;
    String updateMessage = "";

    void tidalSettings() {
        ui.section("账号状态");
        ui.empty(ui.playback != null && ui.playback.client != null ? "已登录" : "尚未登录",
                ui.playback != null && !ui.playback.error.isEmpty() ? ui.playback.error : "用设备链接在 TIDAL 网页完成授权，无需 PC");
        ui.content.addView(ui.toolbar(ui.withIcon(ui.primary(ui.playback != null && ui.playback.client != null ? "重新登录" : "设备链接登录",
                ui.loginFlow::login), "user", BG)));
        ui.section("其他方式");
        ui.content.addView(ui.menuRow("浏览器登录", "浏览器登录", ui.loginFlow::startBrowserLogin));
        ui.content.addView(ui.menuRow("设备授权配置", "设备授权配置", ui.loginFlow::configureTidalDevice));
        ui.content.addView(ui.menuRow("导入已有会话", "导入已有会话", ui.importExport::pickSession));
        if (ui.playback != null && ui.playback.client == null && ui.playback.relay != null) {
            ui.content.addView(ui.menuRow("同步 PC 登录", "同步 PC 登录", () -> {
                ui.playback.enableDesktopSessionSync();
                ui.toast("等待 PC 同步 TIDAL 登录");
            }));
        }
        if (ui.playback != null && ui.playback.client != null) {
            ui.content.addView(ui.menuRow("退出账号", "退出账号", () -> new AlertDialog.Builder(ui)
                    .setTitle("退出 TIDAL 账号？").setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        ui.loginFlow.cancelLogin();
                        ui.playback.signOutFromUi();
                        ui.importExport.clearFavorites();
                        ui.render();
                    }).show()));
        }
    }

    void settings() {
        String version = "";
        try {
            version = ui.getPackageManager().getPackageInfo(ui.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        LinearLayout updates = ui.panel("应用更新", "当前版本 " + version);
        ui.checkUpdateButton = ui.primary("检查更新", this::checkUpdate);
        updates.addView(ui.checkUpdateButton, new LinearLayout.LayoutParams(-2, ui.dp(44)));
        ui.updateStatus = ui.text("", 13, MUTED, false);
        ui.updateStatus.setPadding(0, ui.dp(8), 0, 0);
        ui.updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updates.addView(ui.updateStatus);
        syncUpdateControls();
        Uri directory = MusicDownload.directory(ui);
        LinearLayout download = ui.panel("下载目录", directory == null ? "系统默认下载目录"
                : android.provider.DocumentsContract.getTreeDocumentId(directory));
        download.addView(ui.menuRow("下载管理", "下载管理", () -> ui.open(Page.DOWNLOADS)));
        download.addView(ui.menuRow("同时下载 · " + ui.downloadTasks.parallel() + " 首", "设置同时下载的歌曲数",
                ui.downloadScreens::chooseParallelDownloads));
        download.addView(ui.menuRow("选择下载目录", "选择下载目录", () ->
                ui.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), MainActivity.DOWNLOAD_DIRECTORY)));
        if (directory != null) {
            download.addView(ui.menuRow("恢复默认目录", "恢复默认下载目录", () -> {
                ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE).edit().remove(MusicDownload.DIRECTORY).apply();
                ui.render();
            }));
        }
        LinearLayout storage = ui.panel("音乐目录", "添加或重新扫描文件夹。");
        storage.addView(ui.menuRow("管理扫描目录", "管理扫描目录", ui.importExport::chooseFolders));
        LinearLayout connection = ui.panel("设备与连接", "局域网遥控、文件管理和多端后台。");
        connection.addView(ui.menuRow("设备与连接", "设备与连接", () -> ui.open(Page.CONNECTIONS)));
        LinearLayout sound = ui.panel("播放设置", "TIDAL 默认流媒体音质。");
        sound.addView(ui.menuRow("TIDAL 音质 · " + (ui.playback == null ? PlaybackService.QUALITY_NAMES[0]
                : PlaybackService.QUALITY_NAMES[ui.playback.quality]), "选择 TIDAL 播放音质", ui.nowPlaying::chooseTidalQuality));
        sound.addView(ui.menuRow("网易云音质 · " + (ui.playback == null ? "无损" : NeteaseClient.levelName(ui.playback.neteaseLevel)),
                "选择网易云播放音质", ui.nowPlaying::chooseNeteaseQuality));
        ui.aiScreens.settingsPanel();
    }

    void connections() {
        ui.section("播放器账号");
        android.content.SharedPreferences saved = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        String username = saved.getString("player_account", "");
        if (username.isEmpty()) {
            ui.content.addView(ui.menuRow("登录播放器账号", "登录播放器账号", this::playerAccountLogin));
        } else {
            ui.content.addView(ui.text(username, 17, INK, true));
            ui.relayStatus = ui.text(ui.playback != null && ui.playback.relay != null ? ui.playback.relay.status : "正在连接…",
                    14, MUTED, false);
            ui.content.addView(ui.relayStatus);
            ui.content.addView(ui.menuRow("我的设备", "查看并遥控同一账号的所有设备", () -> ui.open(Page.DEVICES)));
            if (!PlayerAccount.ORIGIN.isEmpty()) {
                ui.content.addView(ui.menuRow("网页遥控", "在浏览器打开多端遥控",
                        () -> ui.loginFlow.openTidalBrowser(Uri.parse(PlayerAccount.ORIGIN))));
            }
            android.widget.Switch lend = new android.widget.Switch(ui);
            lend.setText("允许我的其他设备使用本机的 TIDAL 登录和下载");
            lend.setTextColor(INK);
            lend.setChecked(ui.playback != null && ui.playback.lendTidal);
            lend.setOnCheckedChangeListener((view, enabled) -> {
                if (ui.playback != null) ui.playback.setLendTidal(enabled);
            });
            ui.content.addView(lend);
            ui.content.addView(ui.text("开启后，同一播放器账号的设备遥控本机时，可以通过本机的 TIDAL 登录浏览、收藏，"
                    + "并在本机下载和管理下载。它们拿不到本机的登录凭据。", 13, MUTED, false));
            ui.content.addView(ui.menuRow("退出播放器账号", "退出播放器账号", this::playerAccountLogout));
        }
        ui.section("局域网遥控");
        boolean remoteEnabled = ui.playback != null && !ui.playback.remoteLink().isEmpty();
        if (remoteEnabled) {
            TextView address = ui.text(ui.playback.remoteAddress(), 13, MUTED, false);
            address.setTextIsSelectable(true);
            ui.content.addView(address);
            TextView code = ui.text(ui.playback.remoteCode(), 25, INK, true);
            code.setTextIsSelectable(true);
            ui.content.addView(code);
            ui.content.addView(ui.text("认证码会保留。刷新认证码后，已配对的浏览器需要重新配对。", 13, MUTED, false));
            ui.content.addView(ui.menuRow("复制配对码", "复制配对码", () -> ui.loginFlow.copyAuthorization(ui.playback.remoteCode())));
            ui.content.addView(ui.menuRow("复制遥控链接", "复制遥控链接", () -> ui.loginFlow.copyAuthorization(ui.playback.remoteLink())));
            ui.content.addView(ui.menuRow("刷新认证码", "刷新认证码", () ->
                    new AlertDialog.Builder(ui).setTitle("刷新认证码？")
                            .setMessage("已配对的浏览器需要用新码重新配对。")
                            .setNegativeButton("取消", null).setPositiveButton("刷新", (dialog, button) -> {
                                try {
                                    ui.playback.refreshRemoteCode();
                                } catch (IOException problem) {
                                    ui.toast(UiKit.readable(problem));
                                }
                                ui.render();
                            }).show()));
            android.widget.Switch routed = new android.widget.Switch(ui);
            routed.setText("允许其他私有网段");
            routed.setTextColor(INK);
            routed.setChecked(ui.playback.remoteRoutedLan);
            routed.setOnCheckedChangeListener((view, allowed) -> {
                if (ui.playback != null) ui.playback.setRemoteRoutedLan(allowed);
            });
            ui.content.addView(routed);
            ui.content.addView(ui.text("电脑与手机在不同私有网段时，且网络允许互访，可打开此项。", 13, MUTED, false));
            ui.content.addView(ui.menuRow("关闭遥控", "关闭遥控", () -> {
                ui.playback.disableRemote();
                ui.render();
            }));
        } else if (ui.playback != null && ui.playback.remoteRequested()) {
            ui.content.addView(ui.text(ui.playback.remoteIssue(), 14, MUTED, false));
            ui.content.addView(ui.menuRow("关闭遥控", "关闭遥控", () -> {
                ui.playback.disableRemote();
                ui.render();
            }));
        } else {
            ui.content.addView(ui.text("让同一 Wi-Fi 下的电脑通过浏览器登录、播放和编辑队列。", 14, MUTED, false));
            ui.content.addView(ui.menuRow("启用局域网遥控", "启用局域网遥控", () -> {
                if (ui.playback == null) return;
                try {
                    ui.playback.enableRemote();
                } catch (Exception problem) {
                    ui.toast(UiKit.readable(problem));
                }
                ui.render();
            }));
        }
        ui.section("远程文件管理");
        android.widget.Switch files = new android.widget.Switch(ui);
        files.setText("允许已配对浏览器管理音乐文件");
        files.setTextColor(INK);
        files.setEnabled(remoteEnabled && ui.playback.localLibrary.hasFolders());
        files.setChecked(ui.playback != null && ui.playback.remoteFiles);
        files.setOnCheckedChangeListener((view, enabled) -> {
            if (ui.playback != null) ui.playback.setRemoteFiles(enabled);
        });
        ui.content.addView(files);
        ui.content.addView(ui.text("管理范围仅限已授权的音乐目录。重新启用遥控时会恢复此前的权限。", 13, MUTED, false));
        ui.content.addView(ui.menuRow("管理扫描目录", "管理扫描目录", ui.importExport::chooseFolders));
    }

    void playerAccountLogin() {
        if (ui.playback == null) return;
        LinearLayout fields = ui.column();
        fields.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        EditText username = new EditText(ui);
        username.setHint("用户名");
        username.setSingleLine(true);
        EditText password = new EditText(ui);
        password.setHint("密码");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        fields.addView(username);
        fields.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("登录播放器账号")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("登录", null).show();
        Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            String name = username.getText().toString().trim();
            String pass = password.getText().toString();
            submit.setEnabled(false);
            ui.worker.submit(() -> {
                try {
                    JSONObject account = PlayerAccount.login(name, pass);
                    ui.runOnUiThread(() -> {
                        if (ui.isDestroyed() || ui.playback == null || !dialog.isShowing()) return;
                        try {
                            ui.playback.connectPlayerAccount(account);
                            password.setText("");
                            dialog.dismiss();
                            ui.render();
                        } catch (Exception problem) {
                            submit.setEnabled(true);
                            password.setError(CentralRelay.readable(problem));
                        }
                    });
                } catch (Exception problem) {
                    ui.runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        submit.setEnabled(true);
                        password.setError(CentralRelay.readable(problem));
                    });
                }
            });
        });
    }

    void playerAccountLogout() {
        String key = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE).getString("relay_key", "");
        new AlertDialog.Builder(ui).setTitle("退出播放器账号？")
                .setNegativeButton("取消", null).setPositiveButton("退出", (dialog, button) ->
                    ui.worker.submit(() -> {
                        try {
                            PlayerAccount.logout(key);
                            ui.runOnUiThread(() -> {
                                if (ui.playback == null || ui.isDestroyed()) return;
                                ui.playback.disconnectPlayerAccount();
                                ui.render();
                            });
                        } catch (Exception problem) {
                            ui.runOnUiThread(() -> ui.toast(CentralRelay.readable(problem)));
                        }
                    })).show();
    }

    void syncUpdateControls() {
        if (ui.checkUpdateButton != null) {
            ui.checkUpdateButton.setEnabled(!updateBusy);
            ui.checkUpdateButton.setText(updateBusy ? "请稍候…" : "检查更新");
        }
        if (ui.updateStatus != null) {
            ui.updateStatus.setText(updateMessage);
            ui.updateStatus.setVisibility(updateMessage.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    void updateState(boolean busy, String message) {
        updateBusy = busy;
        updateMessage = message;
        syncUpdateControls();
    }

    void checkUpdate() {
        if (updateBusy) return;
        updateState(true, "正在检查更新…");
        ui.worker.submit(() -> {
            try {
                org.json.JSONObject update = AppUpdate.latest();
                long current = AppUpdate.version(ui.getPackageManager().getPackageInfo(ui.getPackageName(), 0));
                long latest = update.optLong("versionCode");
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed()) return;
                    if (latest <= current) {
                        updateState(false, "当前已是最新版本。");
                        return;
                    }
                    showUpgradePrompt(update);
                });
            } catch (Exception problem) {
                reportUpdateFailure(problem);
            }
        });
    }

    void showUpgradePrompt(org.json.JSONObject update) {
        String version = update.optString("versionName", Long.toString(update.optLong("versionCode")));
        String notes = update.optString("notes").trim();
        updateState(false, "可升级至 " + version);
        if (!ui.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        new AlertDialog.Builder(ui).setTitle("升级到 " + version + "？")
                .setMessage(notes.isEmpty() ? "下载后由 Android 系统确认安装。" : notes)
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即升级", (dialog, which) -> downloadUpdate(update)).show();
    }

    void downloadUpdate(org.json.JSONObject update) {
        if (updateBusy) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ui.getPackageManager().canRequestPackageInstalls()) {
            try {
                ui.installManifest = update;
                ui.startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ui.getPackageName())), MainActivity.INSTALL_PERMISSION);
            } catch (android.content.ActivityNotFoundException unavailable) {
                ui.installManifest = null;
                updateState(false, "当前设备无法从应用内安装更新。");
            }
            return;
        }
        updateState(true, "正在下载和验证更新…");
        ui.worker.submit(() -> {
            try {
                java.io.File apk = AppUpdate.download(ui, update);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed()) return;
                    updateState(false, "APK 已验证，请在系统界面确认安装。");
                    Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                            ui, ui.getPackageName() + ".updates", apk);
                    try {
                        ui.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                contentUri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (android.content.ActivityNotFoundException unavailable) {
                        ui.toast("此设备没有可用的 APK 安装程序");
                    }
                });
            } catch (Exception problem) {
                reportUpdateFailure(problem);
            }
        });
    }

    void reportUpdateFailure(Exception problem) {
        ui.runOnUiThread(() -> {
            if (ui.isDestroyed()) return;
            String message = "更新失败 · " + CentralRelay.readable(problem);
            updateState(false, message);
            if (ui.page != Page.SETTINGS) ui.toast(message);
        });
    }
}
