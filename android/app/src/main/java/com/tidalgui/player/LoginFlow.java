package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.util.concurrent.Future;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;

/** Part of {@link MainActivity}: LoginFlow. */
@UnstableApi
final class LoginFlow {
    final MainActivity ui;

    LoginFlow(MainActivity ui) {
        this.ui = ui;
    }

    Future<?> loginTask;
    AlertDialog loginDialog;

    void startBrowserLogin() {
        cancelLogin();
        if (ui.playback == null) {
            ui.toast("播放服务正在启动，请重试");
            return;
        }
        int version = ++ui.importGeneration;
        loginDialog = new AlertDialog.Builder(ui).setTitle("浏览器登录 TIDAL")
                .setMessage("正在准备 TIDAL 登录网页…")
                .setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setOnCancelListener(dialog -> cancelLogin()).show();
        loginTask = ui.worker.submit(() -> {
            try {
                DeviceLogin.BrowserGrant grant = DeviceLogin.startBrowser();
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration) return;
                    loginDialog.dismiss();
                    loginDialog = new AlertDialog.Builder(ui).setTitle("等待浏览器授权")
                            .setMessage("在 TIDAL 网页完成登录后，返回听音室。")
                            .setNegativeButton("取消", (dialog, which) -> cancelLogin())
                            .setPositiveButton("打开 TIDAL 网页", null)
                            .setOnCancelListener(dialog -> cancelLogin()).show();
                    Button browser = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    browser.setOnClickListener(view -> openTidalBrowser(grant.url));
                    if (ui.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        browser.performClick();
                    }
                });
                TidalClient.Session account = DeviceLogin.awaitBrowser(grant);
                String saved = account.toJson();
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration || ui.playback == null) return;
                    cancelLogin();
                    finishSignIn(account, saved, "已登录 TIDAL");
                });
            } catch (Exception problem) {
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration) return;
                    cancelLogin();
                    new AlertDialog.Builder(ui).setTitle("浏览器登录暂不可用")
                            .setMessage(UiKit.readable(problem))
                            .setNegativeButton("关闭", null)
                            .setNeutralButton("账号设置", (dialog, which) -> ui.open(Page.ACCOUNT)).show();
                });
            }
        });
    }

    void openTidalBrowser(Uri address) {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, address);
            browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
            ui.startActivity(browser);
        } catch (android.content.ActivityNotFoundException missing) {
            ui.toast("当前设备没有可打开 TIDAL 的浏览器");
        }
    }

    void login() {
        cancelLogin();
        if (ui.playback == null) {
            ui.toast("播放服务正在启动，请重试");
            return;
        }
        DeviceLogin device = ui.playback.tidalDevice();
        startLogin(device == null ? DeviceLogin.brokered() : device);
    }

    void configureTidalDevice() {
        cancelLogin();
        LinearLayout fields = ui.column();
        fields.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        EditText id = new EditText(ui);
        id.setHint("Client ID");
        id.setSingleLine();
        if (ui.playback != null && ui.playback.tidalDevice() != null) id.setText(ui.playback.tidalDevice().clientId);
        EditText secret = new EditText(ui);
        secret.setHint("Client Secret");
        secret.setSingleLine();
        secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(id);
        fields.addView(secret);
        AlertDialog configuration = new AlertDialog.Builder(ui).setTitle("设备授权配置")
                .setMessage("首次使用需要获准的 TIDAL 设备客户端。授权成功后，以后登录会直接显示设备码。账号密码在 TIDAL 网页输入。")
                .setView(fields).setNegativeButton("取消", null)
                .setPositiveButton("获取设备码", null).show();
        configuration.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                DeviceLogin device = new DeviceLogin(id.getText().toString(), secret.getText().toString());
                configuration.dismiss();
                startLogin(device);
            } catch (IOException error) {
                id.setError(error.getMessage());
            }
        });
    }

    void startLogin(DeviceLogin device) {
        if (ui.playback == null) {
            ui.toast("播放服务正在启动，请重试");
            return;
        }
        ui.playback.configureRemoteLogin(device);
        int version = ++ui.importGeneration;
        loginDialog = new AlertDialog.Builder(ui).setTitle("连接 TIDAL")
                .setMessage("正在申请设备授权码…").setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setOnCancelListener(dialog -> cancelLogin()).show();
        loginTask = ui.worker.submit(() -> {
            try {
                DeviceLogin.Challenge challenge = device.start();
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration || ui.playback == null) return;
                    ui.playback.showRemoteAuthorization(challenge);
                    showDeviceChallenge(challenge);
                });
                TidalClient.Session session = device.await(challenge);
                String saved = session.toJson();
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration || ui.playback == null) return;
                    cancelLogin();
                    finishSignIn(session, saved, "已登录 TIDAL");
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || version != ui.importGeneration) return;
                    cancelLogin();
                    AlertDialog.Builder failure = new AlertDialog.Builder(ui).setTitle("登录失败")
                            .setMessage(UiKit.readable(error)).setPositiveButton("确定", null);
                    if (device.clientId.isEmpty() && UiKit.readable(error).contains("客户端")) {
                        failure.setNeutralButton("手动配置客户端", (dialog, which) -> configureTidalDevice());
                    }
                    failure.show();
                });
            }
        });
    }

    void showDeviceChallenge(DeviceLogin.Challenge challenge) {
        loginDialog.dismiss();
        LinearLayout details = ui.column();
        details.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        details.addView(ui.text("在 TIDAL 网页完成授权", 16, INK, true));
        details.addView(ui.text("授权完成后返回听音室。也可以在另一台设备打开下方网址并输入设备码。", 14, MUTED, false));
        TextView address = ui.text(challenge.verificationPage.toString(), 14, ACCENT, false);
        address.setTextIsSelectable(true);
        details.addView(address);
        TextView code = ui.text(challenge.userCode, 25, INK, true);
        code.setTextIsSelectable(true);
        details.addView(code);
        LinearLayout copy = ui.row();
        copy.addView(ui.action("复制授权码", "复制授权码", () -> copyAuthorization(challenge.userCode)));
        copy.addView(ui.action("复制直达链接", "复制直达链接", () -> copyAuthorization(challenge.verification.toString())));
        details.addView(copy);

        ScrollView scroll = new ScrollView(ui);
        scroll.addView(details);
        loginDialog = new AlertDialog.Builder(ui).setTitle("TIDAL 设备链接")
                .setView(scroll).setNegativeButton("取消", (dialog, which) -> cancelLogin())
                .setPositiveButton("本机浏览器登录", null)
                .setOnCancelListener(dialog -> cancelLogin()).show();
        Button browser = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        browser.setOnClickListener(view -> openTidalBrowser(challenge.verification));
        if (ui.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            browser.performClick();
        }
    }

    void copyAuthorization(String value) {
        android.content.ClipData clip = android.content.ClipData.newPlainText("TIDAL 设备授权", value);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        ui.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        if (android.os.Build.VERSION.SDK_INT < 33) ui.toast("已复制");
    }

    void cancelLogin() {
        ui.importGeneration++;
        if (loginTask != null) {
            loginTask.cancel(true);
            if (ui.playback != null) ui.playback.clearRemoteAuthorization();
        }
        if (loginDialog != null) loginDialog.dismiss();
        loginTask = null;
        loginDialog = null;
    }

    void finishSignIn(TidalClient.Session session, String saved, String message) {
        ui.playback.changeAccount(session, saved);
        ui.importExport.clearFavorites();
        ui.toast(message);
        ui.libraryFilter = 0;
        ui.navigate(Page.LIBRARY);
    }
}
