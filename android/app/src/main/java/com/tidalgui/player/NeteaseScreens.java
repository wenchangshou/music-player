package com.tidalgui.player;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: NeteaseScreens. */
@UnstableApi
final class NeteaseScreens {
    final MainActivity ui;

    NeteaseScreens(MainActivity ui) {
        this.ui = ui;
    }

    final List<TidalClient.Collection> neteaseCreated = new ArrayList<>();
    final List<TidalClient.Collection> neteaseCollected = new ArrayList<>();
    String squareCategory = MainActivity.SQUARE_CATEGORIES[0];
    boolean showCollected;
    boolean neteasePlaylistLoading;
    boolean neteasePlaylistMore;
    int neteasePlaylistOffset;
    long neteasePlaylistUser;
    int neteasePlaylistGeneration;
    String neteasePlaylistNotice = "";

    void openNeteasePlaylists(Page target) {
        if (ui.page == target) return;
        neteasePlaylistGeneration++;
        neteasePlaylistLoading = false;
        neteasePlaylistMore = false;
        neteasePlaylistNotice = "";
        neteaseCreated.clear();
        neteaseCollected.clear();
        ui.open(target);
        loadNeteasePlaylists(false);
    }

    void neteasePlaylistPage() {
        boolean mine = ui.page == Page.NETEASE_PLAYLISTS;
        if (mine) {
            LinearLayout filters = ui.row();
            filters.addView(ui.pill("我创建的", !showCollected, () -> {
                showCollected = false;
                ui.render();
            }));
            filters.addView(ui.pill("我收藏的", showCollected, () -> {
                showCollected = true;
                ui.render();
            }));
            ui.content.addView(filters);
        } else {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ui);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout filters = ui.row();
            for (String category : MainActivity.SQUARE_CATEGORIES) {
                filters.addView(ui.pill(category, squareCategory.equals(category), () -> {
                    if (squareCategory.equals(category)) return;
                    squareCategory = category;
                    loadNeteasePlaylists(false);
                }));
            }
            scroll.addView(filters);
            ui.content.addView(scroll);
        }
        NeteaseClient account = new NeteaseClient(ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE));
        if (mine && !account.signedIn()) {
            ui.empty("登录后查看个人歌单", "查看你创建和收藏的网易云歌单");
            if (account.usesDefaultBackend()) ui.content.addView(ui.primary("登录网易云", () -> showNeteaseLogin(account)));
            return;
        }
        List<TidalClient.Collection> items = mine && showCollected ? neteaseCollected : neteaseCreated;
        for (TidalClient.Collection item : items) ui.catalogueScreens.collectionRow(item);
        if (neteasePlaylistLoading) {
            if (items.isEmpty()) ui.empty("正在加载歌单…", "");
        } else if (!neteasePlaylistNotice.isEmpty()) {
            ui.empty(neteasePlaylistNotice, "");
            ui.content.addView(ui.action("重试", "重新加载歌单", () -> loadNeteasePlaylists(neteasePlaylistOffset > 0)));
        } else if (items.isEmpty() && !neteasePlaylistMore) {
            ui.empty("暂无歌单", "");
        }
        if (!neteasePlaylistLoading && neteasePlaylistMore && neteasePlaylistNotice.isEmpty()) {
            ui.content.addView(ui.action("加载更多", "加载更多歌单", () -> loadNeteasePlaylists(true)));
        }
    }

    void loadNeteasePlaylists(boolean append) {
        Page target = ui.page;
        NeteaseClient account = new NeteaseClient(ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE));
        int request = ++neteasePlaylistGeneration;
        if (!append) {
            neteaseCreated.clear();
            neteaseCollected.clear();
            neteasePlaylistOffset = 0;
            neteasePlaylistUser = 0;
            neteasePlaylistMore = false;
        }
        neteasePlaylistNotice = "";
        neteasePlaylistLoading = target == Page.NETEASE_SQUARE || account.signedIn();
        ui.render();
        if (!neteasePlaylistLoading) return;
        int offset = neteasePlaylistOffset;
        long user = neteasePlaylistUser;
        String category = squareCategory;
        ui.worker.submit(() -> {
            try {
                NeteaseClient.PlaylistPage result = target == Page.NETEASE_SQUARE
                        ? account.playlistSquare(category, offset) : account.myPlaylists(user, offset);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || neteasePlaylistGeneration != request) return;
                    neteaseCreated.addAll(result.created);
                    neteaseCollected.addAll(result.collected);
                    neteasePlaylistOffset = result.nextOffset;
                    neteasePlaylistUser = result.user;
                    neteasePlaylistMore = result.more;
                    neteasePlaylistLoading = false;
                    if (ui.page == target) ui.render();
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || neteasePlaylistGeneration != request) return;
                    neteasePlaylistNotice = "歌单加载失败 · " + UiKit.readable(error);
                    neteasePlaylistLoading = false;
                    if (ui.page == target) ui.render();
                });
            }
        });
    }

    void addShortcut(LinearLayout parent, String title, String subtitle, String icon, Runnable choose) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(13), ui.dp(14), ui.dp(10), ui.dp(13));
        card.setBackground(ui.shape(SURFACE, 15));
        TextView name = ui.text(title, 16, INK, true);
        ImageView symbol = new ImageView(ui);
        symbol.setImageDrawable(new PlayerIcon(icon, ACCENT, ui.dp(23)));
        card.addView(symbol, new LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)));
        name.setPadding(0, ui.dp(9), 0, ui.dp(2));
        card.addView(name);
        card.addView(ui.text(subtitle, 13, MUTED, false));
        card.setOnClickListener(view -> choose.run());
        card.setContentDescription(title + "，" + subtitle);
        LinearLayout.LayoutParams style = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) style.leftMargin = ui.dp(10);
        parent.addView(card, style);
    }

    boolean hasPlayerAccount() {
        return !ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE).getString("player_account", "").isEmpty();
    }

    void sources() {
        boolean loggedIn = ui.remoteScreens.tidalApi() != null;
        String hint = ui.remoteScreens.lendHint();
        LinearLayout tidal = ui.panel("TIDAL", ui.remoteScreens.borrowsTidal() ? "使用「" + ui.devices.targetName() + "」的登录 · 浏览、收藏和下载都经由该设备，"
                + "歌曲在该设备上播放" : hint != null ? hint : loggedIn ? "已连接 · Hi-Res 无损、收藏与歌单" : "在 TIDAL 网页授权一次即可，无需电脑。");
        Button enterTidal = ui.withIcon(loggedIn ? ui.primary("进入 TIDAL", () -> ui.enterSource(SearchSource.TIDAL))
                : ui.secondary("浏览", () -> ui.enterSource(SearchSource.TIDAL)), "forward", loggedIn ? BG : ACCENT);
        tidal.addView(loggedIn ? ui.toolbar(enterTidal, ui.secondary("账号设置", () -> ui.open(Page.ACCOUNT)))
                : ui.toolbar(ui.withIcon(ui.primary("登录 TIDAL", ui.loginFlow::login), "user", BG), enterTidal,
                ui.secondary("账号设置", () -> ui.open(Page.ACCOUNT))));
        android.content.SharedPreferences sourcePrefs = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        NeteaseClient neteaseAccount = new NeteaseClient(sourcePrefs);
        boolean neteaseIn = neteaseAccount.signedIn();
        LinearLayout netease = ui.panel("网易云音乐", neteaseIn
                ? "已登录 · 使用网易云账号播放" : "登录后可播放账号有权访问的曲目，访客可浏览推荐歌单。");
        List<Button> neteaseActions = new ArrayList<>();
        boolean offerLogin = neteaseAccount.usesDefaultBackend() && !neteaseIn;
        if (offerLogin) neteaseActions.add(ui.withIcon(ui.primary("扫码登录", () -> showNeteaseLogin(neteaseAccount)), "user", BG));
        neteaseActions.add(ui.withIcon(offerLogin ? ui.secondary("浏览", () -> ui.enterSource(SearchSource.NETEASE))
                : ui.primary("进入网易云", () -> ui.enterSource(SearchSource.NETEASE)), "forward", offerLogin ? ACCENT : BG));
        if (neteaseAccount.usesDefaultBackend() && neteaseIn) {
            neteaseActions.add(ui.secondary("退出登录", () -> new AlertDialog.Builder(ui)
                    .setTitle("退出网易云登录？").setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        sourcePrefs.edit().remove(NeteaseClient.SESSION).apply();
                        ui.render();
                    }).show()));
        }
        neteaseActions.add(ui.secondary("接口设置", this::configureNetease));
        netease.addView(ui.toolbar(neteaseActions.toArray(new Button[0])));
        LinearLayout local = ui.panel("本地文件", "音乐保存在手机或已授权的外接存储中。");
        local.addView(ui.toolbar(ui.withIcon(ui.primary("打开音乐库", () -> ui.open(Page.LOCAL)), "library", BG),
                ui.withIcon(ui.secondary("扫描目录", ui.importExport::chooseFolders), "folder", ACCENT)));
        List<WebDavClient.Server> davServers = WebDavClient.loadServers(ui);
        LinearLayout webdav = ui.panel("WebDAV 网络存储", davServers.isEmpty()
                ? "连接 NAS、AList、坚果云等网络存储，在线流式播放与下载。"
                : "已添加 " + davServers.size() + " 个服务器 · 支持直接串流与整目录播放。");
        List<Button> davActions = new ArrayList<>();
        if (!davServers.isEmpty()) {
            davActions.add(ui.withIcon(ui.primary("浏览存储", () -> {
                ui.webdavBack = Page.SOURCES;
                ui.webdavCurrentServer = null;
                ui.webdavCurrentPath = "";
                ui.open(Page.WEBDAV);
            }), "folder", BG));
        }
        davActions.add(ui.withIcon(ui.secondary("添加服务器", () -> ui.webdavScreens.showEditWebDavServer(null)), "plus", ACCENT));
        webdav.addView(ui.toolbar(davActions.toArray(new Button[0])));
        List<SmbClient.Server> smbServers = SmbClient.loadServers(ui);
        LinearLayout smb = ui.panel("SMB / Windows 共享", smbServers.isEmpty()
                ? "连接 Windows 电脑、NAS、Samba 共享文件夹，直接流式播放网络音频与下载。"
                : "已添加 " + smbServers.size() + " 个服务器 · 支持直接串流与整目录播放。");
        List<Button> smbActions = new ArrayList<>();
        if (!smbServers.isEmpty()) {
            smbActions.add(ui.withIcon(ui.primary("浏览共享", () -> {
                ui.smbBack = Page.SOURCES;
                ui.smbCurrentServer = null;
                ui.smbCurrentPath = "";
                ui.open(Page.SMB);
            }), "folder", BG));
        }
        smbActions.add(ui.withIcon(ui.secondary("添加共享", () -> ui.smbScreens.showEditSmbServer(null)), "plus", ACCENT));
        smb.addView(ui.toolbar(smbActions.toArray(new Button[0])));
        if (!hasPlayerAccount()) {
            LinearLayout account = ui.panel("播放器账号（可选）", "在电脑和手机之间同步登录、遥控播放。");
            account.addView(ui.toolbar(ui.withIcon(ui.secondary("登录播放器账号", ui.settingsScreens::playerAccountLogin), "user", ACCENT)));
        }
    }

    void showNeteaseLogin(NeteaseClient api) {
        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        TextView status = new TextView(ui);
        status.setTextColor(MUTED);
        body.addView(status);
        ImageView qr = new ImageView(ui);
        qr.setContentDescription("网易云登录二维码");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setVisibility(View.GONE);
        body.addView(qr, new LinearLayout.LayoutParams(-1, ui.dp(220)));
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("登录网易云")
                .setView(body).setNegativeButton("关闭", null).setNeutralButton("刷新二维码", null).show();
        Button retry = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

        final class Login {
            private Future<?> request;
            private int attempt;
            private long deadline;

            void cancel() {
                attempt++;
                if (request != null) request.cancel(true);
            }

            boolean active(int id) {
                return id == attempt && dialog.isShowing() && !ui.isDestroyed();
            }

            void start() {
                cancel();
                int id = attempt;
                status.setText("正在生成二维码…");
                qr.setVisibility(View.GONE);
                request = ui.worker.submit(() -> {
                    try {
                        org.json.JSONObject data = api.startLogin();
                        String image = data.getString("qr");
                        if (!image.startsWith("data:image/png;base64,") || image.length() > 200000) {
                            throw new java.io.IOException("二维码格式无效");
                        }
                        byte[] bytes = android.util.Base64.decode(image.substring(22), android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bitmap == null) throw new java.io.IOException("无法读取二维码");
                        String proof = data.getString("proof");
                        ui.handler.post(() -> {
                            if (!active(id)) return;
                            qr.setImageBitmap(bitmap);
                            qr.setVisibility(View.VISIBLE);
                            status.setText("请用另一台设备上的网易云音乐 App 扫码，并确认登录。");
                            deadline = android.os.SystemClock.elapsedRealtime() + 170000;
                            schedule(id, proof);
                        });
                    } catch (Exception error) {
                        ui.handler.post(() -> { if (active(id)) status.setText("登录失败 · " + UiKit.readable(error)); });
                    }
                });
            }

            void schedule(int id, String proof) {
                ui.handler.postDelayed(() -> {
                    if (!active(id)) return;
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        expire();
                        return;
                    }
                    request = ui.worker.submit(() -> {
                        try {
                            org.json.JSONObject result = api.pollLogin(proof);
                            ui.handler.post(() -> update(id, proof, result));
                        } catch (Exception error) {
                            ui.handler.post(() -> {
                                if (!active(id)) return;
                                status.setText("正在重新连接网易云…");
                                schedule(id, proof);
                            });
                        }
                    });
                }, 2500);
            }

            void expire() {
                qr.setVisibility(View.GONE);
                status.setText("二维码已过期，请刷新后重新扫码。");
            }

            void update(int id, String proof, org.json.JSONObject result) {
                if (!active(id)) return;
                switch (result.optInt("code")) {
                    case 800:
                        expire();
                        return;
                    case 801:
                        status.setText("请用另一台设备上的网易云音乐 App 扫码，并确认登录。");
                        break;
                    case 802:
                        status.setText("已扫码，请在网易云音乐 App 中确认。");
                        break;
                    case 803:
                        String session = result.optString("session");
                        if (!session.matches("[A-Za-z0-9._~%+-]{16,4096}")) {
                            status.setText("未能取得登录会话，请刷新后再试。");
                            return;
                        }
                        ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE).edit()
                                .putString(NeteaseClient.SESSION, session).apply();
                        dialog.dismiss();
                        if (ui.page == Page.NETEASE_PLAYLISTS) loadNeteasePlaylists(false);
                        else ui.render();
                        ui.toast("已登录网易云");
                        return;
                    default:
                        status.setText("网易云未能完成授权，请刷新二维码重试。");
                        return;
                }
                schedule(id, proof);
            }
        }

        Login login = new Login();
        retry.setOnClickListener(view -> login.start());
        dialog.setOnDismissListener(ignored -> login.cancel());
        login.start();
    }

    void configureNetease() {
        android.content.SharedPreferences prefs = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        EditText address = new EditText(ui);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint(NeteaseClient.DEFAULT_BASE);
        address.setText(prefs.getString(NeteaseClient.SETTING, ""));
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("网易云接口")
                .setMessage("留空使用播放器默认后台。").setView(address)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String value = address.getText().toString().trim();
                String endpoint = value.isEmpty() ? "" : NeteaseClient.validateBase(value);
                prefs.edit().putString(NeteaseClient.SETTING, endpoint).apply();
                dialog.dismiss();
                if ((ui.page == Page.SEARCH && ui.searchSource == SearchSource.NETEASE)
                        || (ui.page == Page.COLLECTION && ui.collection.isNetease())) ui.catalogueScreens.load(false);
                else ui.render();
            } catch (java.io.IOException invalid) {
                address.setError(invalid.getMessage());
            }
        });
    }
}
