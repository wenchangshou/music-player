package com.tidalgui.player;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import com.tidalgui.player.LocalLibrary.LocalGroup;
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

/** Part of {@link MainActivity}: RemoteScreens. */
@UnstableApi
final class RemoteScreens {
    final MainActivity ui;

    RemoteScreens(MainActivity ui) {
        this.ui = ui;
    }

    String devicesKey = "";
    /** The controlled device's music library, shown in place of this phone's while controlling it. */
    final List<JSONObject> remoteLibTracks = new ArrayList<>();
    final List<String> remoteLibPlaylists = new ArrayList<>();
    String remoteLibKey = "";
    boolean remoteLibLoading;
    String remoteLibError = "";
    int remoteLibNext = -1;
    static final int REMOTE_RECENT = -1;
    final List<JSONObject> remoteLibGroups = new ArrayList<>();
    int remoteLibSort;

    // ───────────── remote control of this account's other devices ─────────────

    /** Create or drop the remote-control client to match the signed-in player account. */
    void syncDevices() {
        android.content.SharedPreferences saved = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        String key = saved.contains("player_account") ? saved.getString("relay_key", "") : "";
        String origin;
        try {
            origin = key.isEmpty() ? "" : CentralRelay.normalizeOrigin(saved.getString("relay_origin", ""));
        } catch (IOException invalid) {
            origin = "";
        }
        String wanted = origin.isEmpty() ? "" : origin + "\n" + key;
        if (wanted.equals(devicesKey)) return;
        if (ui.devices != null) ui.devices.close();
        ui.devices = null;
        devicesKey = wanted;
        if (wanted.isEmpty()) return;
        ui.devices = new RemoteDevices(origin, key, this::remoteChanged);
        if (ui.started) ui.devices.resume();
    }

    boolean remoteMode() {
        return ui.devices != null && ui.devices.active();
    }

    TidalClient borrowedTidal;
    String borrowedTidalDevice;

    /**
     * The TIDAL client pages browse with: this phone's login, or while controlling a device that is
     * signed in to TIDAL and this phone is not, that device's login (requests go through it).
     */
    TidalClient tidalApi() {
        if (ui.playback == null) return null;
        if (ui.playback.client != null) return ui.playback.client;
        if (!borrowsTidal()) return null;
        String device = ui.devices.target;
        if (borrowedTidal == null || !device.equals(borrowedTidalDevice)) {
            RemoteDevices link = ui.devices;
            borrowedTidal = new TidalClient((method, path, form) -> {
                JSONObject command = new JSONObject().put("op", "tidal").put("method", method).put("path", path)
                        .put("accept", "gzip");
                if (form != null) command.put("form", form);
                return RemoteTidal.body(link.call(device, command));
            });
            borrowedTidalDevice = device;
        }
        return borrowedTidal;
    }

    /**
     * Why this phone, signed out of TIDAL, cannot use the controlled device's login although
     * that device is signed in; null otherwise.
     */
    String lendHint() {
        if (ui.playback == null || ui.playback.client != null || !remoteMode() || ui.devices.state == null
                || !ui.devices.state.tidalLoggedIn || ui.devices.state.lendsTidal) return null;
        return "「" + ui.devices.targetName() + "」已登录 TIDAL，但未开放借用。请在该设备上更新到最新版，"
                + "并在 设置 › 播放器账号 中打开“允许我的其他设备使用本机的 TIDAL 登录和下载”";
    }

    /** Browsing uses the controlled device's TIDAL login, since this phone has none. */
    boolean borrowsTidal() {
        return ui.playback != null && ui.playback.client == null && remoteMode() && ui.devices.state != null
                && ui.devices.state.lendsTidal;
    }

    /** Downloads go to the controlled device, which lists them on the downloads page here. */
    boolean remoteDownloads() {
        return remoteMode() && ui.devices.state != null && ui.devices.state.downloads;
    }

    void remoteChanged() {
        if (ui.isDestroyed() || ui.devices == null) return;
        if (!ui.devices.notice.isEmpty()) {
            ui.toast(ui.devices.notice);
            ui.devices.notice = "";
        }
        if (!java.util.Objects.equals(ui.visibleRemoteTarget, ui.devices.target)) resetRemoteLibrary();
        boolean rebuild = !java.util.Objects.equals(ui.visibleRemoteTarget, ui.devices.target)
                || (ui.page == Page.DEVICES && !ui.visibleDevices.equals(devicesSignature()))
                || (ui.page == Page.QUEUE && remoteMode() && !ui.visibleRemoteQueue.equals(remoteQueueSignature()))
                || (ui.page == Page.DOWNLOADS && ui.visibleRemoteDownloads != remoteDownloads());
        if (!java.util.Objects.equals(ui.visibleRemoteTarget, ui.devices.target)) {
            ui.remoteDownloadList = null;
            ui.remoteMarks = null;
            ui.remoteDownloadLimit = 300;
            ui.remoteMarksKey = "";
            ui.remoteMarksBusy = false;
        }
        ui.downloadScreens.syncRemoteMarks();
        if (rebuild) ui.render();
        else ui.nowPlaying.updatePlayer();
    }

    String devicesSignature() {
        if (ui.devices == null) return "";
        StringBuilder signature = new StringBuilder(String.valueOf(ui.devices.target))
                .append(ui.devices.devicesLoaded).append(ui.devices.devicesError);
        for (RemoteDevices.Device device : ui.devices.devices) {
            signature.append('|').append(device.id).append(device.online).append(device.nowPlaying)
                    .append(device.nowTitle).append(device.version);
        }
        return signature.toString();
    }

    String remoteQueueSignature() {
        if (!remoteMode()) return "";
        RemoteDevices.State state = ui.devices.state;
        return state == null ? "none:" + ui.devices.error : state.revision + ":" + state.index + ":" + state.queue.size();
    }

    /** Transport controls act on the selected device: this phone or a remote one. */
    void control(String op) {
        if (remoteMode()) {
            if (!ui.devices.send(op)) ui.toast("遥控命令过多，请稍后再试");
            return;
        }
        if (ui.playback == null) return;
        switch (op) {
            case "toggle": ui.playback.toggle(); break;
            case "previous": ui.playback.previous(); break;
            case "next": ui.playback.next(); break;
            case "shuffle": ui.playback.toggleShuffle(); break;
            case "repeat": ui.playback.cycleRepeat(); break;
            default: break;
        }
    }

    /** The device looks tracks up by TIDAL id, so local and NetEase tracks stay on this phone. */
    boolean remoteAdd(TidalClient.Track track, boolean next) {
        if (track.isLocal() || track.isNetease()) {
            ui.toast("本地和网易云歌曲只能在本机播放");
            return false;
        }
        try {
            return ui.devices.send(new JSONObject().put("op", "add").put("id", track.id).put("next", next));
        } catch (org.json.JSONException ignored) {
            return false;
        }
    }

    /**
     * Send a track list to the remote device in one command: "play" replaces its queue and starts
     * at {@code index}; "next" and "end" queue the tracks. Local and NetEase tracks stay here.
     */
    void remoteTracks(List<TidalClient.Track> list, int index, boolean shuffled, String mode) {
        remoteTracks(list, index, shuffled, mode, 0);
    }

    void remoteTracks(List<TidalClient.Track> list, int index, boolean shuffled, String mode, long position) {
        TidalClient.Track chosen = index >= 0 && index < list.size() ? list.get(index) : null;
        List<TidalClient.Track> tidal = new ArrayList<>();
        for (TidalClient.Track track : list) if (!track.isLocal() && !track.isNetease()) tidal.add(track);
        if (tidal.isEmpty()) {
            ui.toast("本地和网易云歌曲只能在本机播放");
            return;
        }
        int at = Math.max(0, chosen == null ? 0 : tidal.indexOf(chosen));
        // One command carries at most 50 tracks: keep the chosen track inside the window.
        int start = Math.min(at, Math.max(0, tidal.size() - 50));
        org.json.JSONArray ids = new org.json.JSONArray();
        for (int i = start; i < Math.min(tidal.size(), start + 50); i++) ids.put(tidal.get(i).id);
        try {
            if (!ui.devices.send(new JSONObject().put("op", "tracks").put("ids", ids).put("index", at - start)
                    .put("shuffle", shuffled).put("mode", mode).put("position", Math.max(0, position)))) {
                ui.toast("遥控命令过多，请稍后再试");
                return;
            }
        } catch (org.json.JSONException ignored) {
            return;
        }
        if ("play".equals(mode)) ui.toast("正在 " + ui.devices.targetName() + " 播放：" + UiKit.displayTitle(tidal.get(at)));
    }

    /** After opening an album or artist from Now Playing, scroll that song into view. */
    void revealPlayingRow() {
        if (ui.revealTrackId == Long.MIN_VALUE || ui.listScroll == null) return;
        if (ui.catalogueLoading && ui.tracks.isEmpty() && ui.collections.isEmpty()) return;
        long id = ui.revealTrackId;
        ScrollView scroller = ui.listScroll;
        scroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (ui.listScroll != scroller || ui.revealTrackId != id) return;
                android.view.ViewTreeObserver observer = scroller.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                ui.revealTrackId = Long.MIN_VALUE;
                View row = findTagged(scroller, id);
                if (row == null) return;
                int y = 0;
                View contentRoot = scroller.getChildAt(0);
                for (View view = row; view != null && view != contentRoot; ) {
                    y += view.getTop();
                    view = view.getParent() instanceof View ? (View) view.getParent() : null;
                }
                if (y > ui.dp(8)) scroller.scrollTo(0, y - ui.dp(8));
            }
        });
    }

    /** Scroll a tagged row into view once the queue page has been laid out. */
    void revealTag(Object tag) {
        if (ui.listScroll == null) return;
        ScrollView scroller = ui.listScroll;
        scroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (ui.listScroll != scroller) return;
                android.view.ViewTreeObserver observer = scroller.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                View row = findTag(scroller, tag);
                if (row == null) return;
                int y = 0;
                View contentRoot = scroller.getChildAt(0);
                for (View view = row; view != null && view != contentRoot; ) {
                    y += view.getTop();
                    view = view.getParent() instanceof View ? (View) view.getParent() : null;
                }
                if (y > ui.dp(8)) scroller.scrollTo(0, y - ui.dp(8));
            }
        });
    }

    static View findTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTag(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    static View findTagged(View root, long id) {
        if (root.getTag() instanceof Long && (Long) root.getTag() == id) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** "专辑 · X ›" under the artist on Now Playing, when the track belongs to an album. */
    void showPlayingAlbum(String album, boolean artistLine) {
        if (ui.playerAlbum == null) return;
        boolean shown = !album.isEmpty() && artistLine;
        ui.playerAlbum.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) ui.playerAlbum.setText("专辑 · " + album);
        ui.playerAlbum.setContentDescription(shown ? "查看专辑 " + album : null);
        ui.subtitle.setTextColor(artistLine ? ACCENT : MUTED);
        ui.subtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                artistLine ? new PlayerIcon("forward", ACCENT, ui.dp(12)) : null, null);
        ui.subtitle.setContentDescription(artistLine ? "查看艺人 " + ui.subtitle.getText() : null);
    }

    boolean hasPlayingArtist() {
        if (remoteMode()) {
            RemoteDevices.State state = ui.devices.state;
            RemoteDevices.Track current = state == null ? null : state.current();
            return current != null && !current.artist.isEmpty();
        }
        return ui.playback != null && ui.playback.current() != null && !ui.playback.current().artist.isEmpty();
    }

    void openPlayingAlbum() {
        if (remoteMode()) openRemotePlaying(true);
        else if (ui.playback != null && ui.playback.current() != null) {
            ui.revealTrackId = ui.playback.current().id;
            openTrackAlbum(ui.playback.current());
        }
    }

    void openPlayingArtist() {
        if (remoteMode()) openRemotePlaying(false);
        else if (ui.playback != null && ui.playback.current() != null) {
            ui.revealTrackId = ui.playback.current().id;
            openTrackArtist(ui.playback.current());
        }
    }

    /** All songs of the track's album: its album page, or the album group of a local file. */
    void openTrackAlbum(TidalClient.Track track) {
        if (track.isLocal()) {
            openLocalGroup(LocalLibrary.groupFor(track, 1));
            return;
        }
        if (track.albumId <= 0) {
            ui.toast("这首歌没有专辑信息");
            return;
        }
        try {
            ui.catalogueScreens.openCollection(new TidalClient.Collection(new JSONObject().put("id", track.albumId)
                    .put("title", track.album).put("cover", track.cover), TidalClient.Collection.Kind.ALBUM));
        } catch (Exception ignored) {}
    }

    /** The track's artist: the artist page, or the artist group of a local file. */
    void openTrackArtist(TidalClient.Track track) {
        if (track.isLocal()) {
            openLocalGroup(LocalLibrary.groupFor(track, 3));
            return;
        }
        if (track.artistId <= 0) {
            ui.toast("这首歌没有艺人信息");
            return;
        }
        try {
            // NetEase artist ids arrive raw from song data; the catalogue uses its own id range.
            long id = track.isNetease() && !NeteaseClient.owns(track.artistId)
                    ? NeteaseClient.catalogueId(track.artistId) : track.artistId;
            ui.catalogueScreens.openCollection(new TidalClient.Collection(new JSONObject().put("id", id).put("name", track.artist)
                    .put("picture", track.artistPicture), TidalClient.Collection.Kind.ARTIST));
        } catch (Exception ignored) {}
    }

    void openLocalGroup(LocalGroup group) {
        ui.localPlaylist = null;
        ui.localGroup = group;
        ui.localTab = group.category;
        if (ui.page == Page.LOCAL) ui.render();
        else ui.open(Page.LOCAL);
    }

    /**
     * Album or artist of the controlled device's track. TIDAL tracks are looked up with this
     * phone's TIDAL login; local files of an Android device open its library filtered by name.
     */
    void openRemotePlaying(boolean album) {
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        if (current == null) return;
        TidalClient api = tidalApi();
        if (current.tidalId > 0 && api != null) {
            long id = current.tidalId;
            ui.worker.submit(() -> {
                try {
                    TidalClient.Track track = api.track(id);
                    ui.runOnUiThread(() -> {
                        if (ui.isDestroyed()) return;
                        ui.revealTrackId = track.id;
                        if (album) openTrackAlbum(track);
                        else openTrackArtist(track);
                    });
                } catch (Exception problem) {
                    ui.runOnUiThread(() -> ui.toast(UiKit.readable(problem)));
                }
            });
            return;
        }
        if ("本地音乐".equals(current.source) && remoteHasLibrary()) {
            String name = album ? current.album : current.artist;
            if (name.isEmpty()) return;
            ui.remoteLibTab = 0;
            ui.remoteLibPlaylist = "";
            ui.remoteLibGroup = "";
            ui.remoteLibGroupTitle = "";
            ui.remoteLibQuery = name;
            if (ui.page == Page.LOCAL) ui.render();
            else ui.open(Page.LOCAL);
            return;
        }
        ui.toast(album ? "无法打开这首歌的专辑" : "无法打开这首歌的艺人");
    }

    /** Shown under the header on every page while another device is controlled. */
    void remoteBanner() {
        LinearLayout banner = ui.row();
        banner.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6));
        banner.setBackground(ui.shapeWithStroke(0x22d4a359, 0x44d4a359, 1, 10));
        RemoteDevices.Device device = ui.devices.targetDevice();
        boolean library = device != null && device.android();
        TextView label = ui.text("正在遥控 " + ui.devices.targetName() + " · " + (library ? "播放、队列和音乐库" : "播放和队列")
                + "都来自这台设备" + (borrowsTidal() ? "，TIDAL 浏览和下载使用它的登录" : ""), 13, ACCENT, true);
        String hint = lendHint();
        if (hint != null) label.append("\n" + hint);
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon("devices", ACCENT, ui.dp(18)), null, null, null);
        label.setCompoundDrawablePadding(ui.dp(8));
        banner.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = ui.secondary("切回本机", () -> selectDevice(null));
        back.setTextSize(13);
        banner.addView(back, new LinearLayout.LayoutParams(-2, ui.dp(34)));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(6));
        ui.root.addView(banner, layout);
    }

    boolean remoteHasLibrary() {
        RemoteDevices.Device device = remoteMode() ? ui.devices.targetDevice() : null;
        return device != null && device.android();
    }

    void resetRemoteLibrary() {
        remoteLibTracks.clear();
        remoteLibPlaylists.clear();
        remoteLibKey = "";
        remoteLibLoading = false;
        remoteLibError = "";
        remoteLibNext = -1;
        ui.remoteLibTab = 0;
        ui.remoteLibQuery = "";
        ui.remoteLibPlaylist = "";
        remoteLibGroups.clear();
        ui.remoteLibGroup = "";
        ui.remoteLibGroupTitle = "";
    }

    /** Sort names the device understands, in the order of {@link #LIBRARY_SORTS}. */
    static final String[] REMOTE_SORTS = {"title", "artist", "album", "title", "added"};

    void loadRemoteLibrary(boolean append) {
        boolean playlists = ui.remoteLibTab == 2 && ui.remoteLibPlaylist.isEmpty();
        int category = ui.remoteLibTab == 1 || ui.remoteLibTab >= 3 ? ui.remoteLibTab : 0;
        String key = ui.devices.target + "\n" + ui.remoteLibTab + "\n" + ui.remoteLibQuery + "\n" + ui.remoteLibPlaylist
                + "\n" + ui.remoteLibGroup + "\n" + remoteLibSort;
        if (remoteLibLoading || (!append && key.equals(remoteLibKey)) || (append && remoteLibNext < 0)) return;
        remoteLibLoading = true;
        remoteLibKey = key;
        if (!append) remoteLibError = "";
        try {
            JSONObject command = new JSONObject().put("op", "library")
                    .put("action", ui.remoteLibTab == REMOTE_RECENT ? "recent" : "local")
                    .put("query", ui.remoteLibQuery).put("playlist", ui.remoteLibTab == 2 ? ui.remoteLibPlaylist : "")
                    .put("category", playlists ? 0 : category).put("group", ui.remoteLibGroup)
                    .put("sort", REMOTE_SORTS[Math.max(0, Math.min(REMOTE_SORTS.length - 1, remoteLibSort))])
                    .put("offset", append ? remoteLibNext : 0);
            ui.devices.query(command, (result, error) -> {
                if (!key.equals(remoteLibKey)) return;
                remoteLibLoading = false;
                if (!error.isEmpty()) remoteLibError = error;
                else {
                    if (!append) {
                        remoteLibTracks.clear();
                        remoteLibGroups.clear();
                    }
                    org.json.JSONArray items = result.optJSONArray("tracks");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null) remoteLibTracks.add(item);
                    }
                    org.json.JSONArray groups = result.optJSONArray("groups");
                    for (int i = 0; groups != null && i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (group != null) remoteLibGroups.add(group);
                    }
                    remoteLibNext = result.optInt("next", -1);
                    org.json.JSONArray names = result.optJSONArray("playlists");
                    if (names != null) {
                        remoteLibPlaylists.clear();
                        for (int i = 0; i < names.length(); i++) remoteLibPlaylists.add(names.optString(i));
                    }
                }
                if (ui.page == Page.LOCAL || ui.page == Page.SEARCH) ui.render();
            });
        } catch (org.json.JSONException ignored) {
            remoteLibLoading = false;
        }
    }

    /** Inside a group or playlist of the controlled device's library (the header shows a back button). */
    boolean remoteLibInside() {
        return remoteMode() && (!ui.remoteLibGroup.isEmpty() || (ui.remoteLibTab == 2 && !ui.remoteLibPlaylist.isEmpty()));
    }

    void remoteLibLeave() {
        ui.remoteLibGroup = "";
        ui.remoteLibGroupTitle = "";
        ui.remoteLibPlaylist = "";
        ui.render();
    }

    /** The controlled device's music library, with the same layout as this phone's 音乐库. */
    void remoteLibraryPage(boolean filters) {
        String name = ui.devices.targetName();
        if (!remoteHasLibrary()) {
            ui.empty(name + " 没有本地音乐库", "电脑端播放 TIDAL 和网易云。在 TIDAL 或网易云中选歌，就会在 " + name + " 播放。");
            ui.content.addView(ui.menuRow("浏览 TIDAL", "浏览 TIDAL", () -> ui.enterSource(SearchSource.TIDAL)));
            ui.content.addView(ui.menuRow("浏览网易云", "浏览网易云", () -> ui.enterSource(SearchSource.NETEASE)));
            return;
        }
        loadRemoteLibrary(false);
        boolean inside = remoteLibInside();
        if (filters && !inside) {
            TextView info = ui.text(ui.remoteLibTab == REMOTE_RECENT ? name + " 最近播放的歌曲" : name + " 上的本地音乐",
                    14, MUTED, false);
            info.setPadding(0, 0, 0, ui.dp(8));
            ui.content.addView(info);
            android.widget.HorizontalScrollView categories = new android.widget.HorizontalScrollView(ui);
            categories.setHorizontalScrollBarEnabled(false);
            LinearLayout tabs = ui.row();
            for (int i = 0; i < MainActivity.LIBRARY_TABS.length; i++) {
                int tab = i;
                tabs.addView(ui.pill(MainActivity.LIBRARY_TABS[i], ui.remoteLibTab == i, () -> {
                    ui.remoteLibTab = tab;
                    ui.remoteLibQuery = "";
                    ui.render();
                }));
            }
            tabs.addView(ui.pill("最近播放", ui.remoteLibTab == REMOTE_RECENT, () -> {
                ui.remoteLibTab = REMOTE_RECENT;
                ui.render();
            }));
            categories.addView(tabs);
            ui.content.addView(categories);
        }
        if (!remoteLibError.isEmpty()) {
            ui.empty("读取 " + name + " 的音乐库失败", remoteLibError);
            ui.content.addView(ui.menuRow("重试", "重试", () -> { remoteLibKey = ""; ui.render(); }));
            return;
        }
        boolean waiting = remoteLibLoading && remoteLibTracks.isEmpty() && remoteLibGroups.isEmpty();
        if (waiting) {
            ui.empty("正在读取 " + name + " 的音乐库…", "");
            return;
        }
        if (ui.remoteLibTab == 2 && !inside) {
            if (remoteLibPlaylists.isEmpty()) ui.empty("暂无歌单", "在 " + name + " 上新建或导入歌单后会显示在这里。");
            for (String playlist : remoteLibPlaylists) {
                ui.content.addView(ui.entry(playlist, "歌单", "", () -> {
                    ui.remoteLibPlaylist = playlist;
                    ui.render();
                }));
            }
            return;
        }
        boolean grouped = (ui.remoteLibTab == 1 || ui.remoteLibTab >= 3) && ui.remoteLibGroup.isEmpty() && filters;
        if (grouped) {
            if (remoteLibGroups.isEmpty()) {
                ui.empty(name + " 还没有导入本地音乐", "");
                return;
            }
            for (JSONObject group : remoteLibGroups) {
                String detail = group.optInt("count") + " 首歌曲";
                if (!group.optString("detail").isEmpty()) detail = group.optString("detail") + " · " + detail;
                ui.content.addView(ui.entry(group.optString("title"), detail, "", () -> {
                    ui.remoteLibGroup = group.optString("id");
                    ui.remoteLibGroupTitle = group.optString("title");
                    ui.render();
                }));
            }
            if (remoteLibNext >= 0) {
                ui.content.addView(ui.menuRow(remoteLibLoading ? "正在加载…" : "加载更多", "加载更多", () -> loadRemoteLibrary(true)));
            }
            return;
        }
        if (remoteLibTracks.isEmpty()) {
            if (ui.remoteLibTab == REMOTE_RECENT) ui.empty("还没有播放记录", "");
            else ui.empty(ui.remoteLibQuery.isEmpty() ? (inside ? "暂无歌曲" : name + " 还没有导入本地音乐") : "没有找到匹配的歌曲", "");
            return;
        }
        if (filters && ui.remoteLibTab == 0 && !inside) {
            ui.content.addView(ui.toolbar(
                    ui.withIcon(ui.secondary("排序 · " + MainActivity.LIBRARY_SORTS[remoteLibSort], this::chooseRemoteSort), "sort", ACCENT),
                    ui.withIcon(ui.secondary("查找", () -> ui.openSearch(SearchSource.LOCAL)), "search", ACCENT)));
        }
        boolean local = false;
        for (JSONObject track : remoteLibTracks) if (track.optLong("id") < 0) local = true;
        if (local && ui.remoteLibTab != REMOTE_RECENT) {
            ui.content.addView(ui.toolbar(ui.withIcon(ui.primary("播放", () -> remoteLibraryPlayAll(false)), "play", BG),
                    ui.withIcon(ui.secondary("随机", () -> remoteLibraryPlayAll(true)), "shuffle", ACCENT)));
        }
        for (int i = 0; i < remoteLibTracks.size(); i++) {
            int at = i;
            JSONObject track = remoteLibTracks.get(i);
            String detail = track.optString("artist");
            String album = track.optString("album");
            if (!album.isEmpty()) detail += (detail.isEmpty() ? "" : " · ") + album;
            if (detail.isEmpty()) detail = track.optLong("id") < 0 ? "本地音频" : "TIDAL";
            String cover = track.optString("cover");
            LinearLayout entry = ui.entry(track.optString("title"), detail, cover.startsWith("https://") ? cover : "",
                    () -> remoteLibraryPlay(at));
            entry.addView(ui.iconButton("more", "更多 " + track.optString("title"), () -> remoteLibraryMenu(track)));
            ui.content.addView(entry);
        }
        if (remoteLibNext >= 0) {
            ui.content.addView(ui.menuRow(remoteLibLoading ? "正在加载…" : "加载更多", "加载更多", () -> loadRemoteLibrary(true)));
        }
    }

    void chooseRemoteSort() {
        new AlertDialog.Builder(ui).setTitle("排序")
                .setSingleChoiceItems(MainActivity.LIBRARY_SORTS, remoteLibSort, (dialog, choice) -> {
                    dialog.dismiss();
                    remoteLibSort = choice;
                    ui.render();
                }).setNegativeButton("取消", null).show();
    }

    void remoteLibrarySend(JSONObject command, String message) {
        if (ui.devices.send(command)) ui.toast(message);
        else ui.toast("遥控命令过多，请稍后再试");
    }

    /** Local files play as a list from the chosen song on, like this phone's lists. */
    void remoteLibraryPlay(int at) {
        if (!remoteMode() || at >= remoteLibTracks.size()) return;
        JSONObject track = remoteLibTracks.get(at);
        try {
            JSONObject command;
            if (ui.remoteLibTab != REMOTE_RECENT && track.optLong("id") < 0) {
                org.json.JSONArray ids = new org.json.JSONArray();
                for (int i = at; i < remoteLibTracks.size() && ids.length() < 50; i++) {
                    long id = remoteLibTracks.get(i).optLong("id");
                    if (id < 0) ids.put(id);
                }
                command = new JSONObject().put("op", "library").put("action", "play-list").put("ids", ids).put("shuffle", false);
            } else {
                command = new JSONObject().put("op", "library").put("action", "song").put("id", track.optLong("id")).put("mode", "play");
            }
            remoteLibrarySend(command, "正在 " + ui.devices.targetName() + " 播放：" + track.optString("title"));
        } catch (org.json.JSONException ignored) {}
    }

    void remoteLibraryPlayAll(boolean shuffled) {
        org.json.JSONArray ids = new org.json.JSONArray();
        for (JSONObject track : remoteLibTracks) {
            long id = track.optLong("id");
            if (id < 0 && ids.length() < 50) ids.put(id);
        }
        if (ids.length() == 0) return;
        try {
            remoteLibrarySend(new JSONObject().put("op", "library").put("action", "play-list").put("ids", ids)
                    .put("shuffle", shuffled), "正在 " + ui.devices.targetName() + " 播放本页歌曲");
        } catch (org.json.JSONException ignored) {}
    }

    void remoteLibraryMenu(JSONObject track) {
        String name = ui.devices.targetName();
        new AlertDialog.Builder(ui).setTitle(track.optString("title"))
                .setItems(new String[]{"下一首播放", "追加到队列"}, (dialog, choice) -> {
                    if (!remoteMode()) return;
                    try {
                        remoteLibrarySend(new JSONObject().put("op", "library").put("action", "song")
                                .put("id", track.optLong("id")).put("mode", choice == 0 ? "next" : "add"),
                                choice == 0 ? "已加入 " + name + " 的下一首" : "已加入 " + name + " 的队列");
                    } catch (org.json.JSONException ignored) {}
                }).show();
    }

    /** Home while controlling another device: what it plays and where its music is. */
    void remoteHome() {
        String name = ui.devices.targetName();
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        LinearLayout box = ui.panel(current == null ? "正在遥控 " + name : current.title,
                current == null ? (state == null ? "正在连接…" : "尚未播放") :
                        (state.playing ? "正在 " : "已暂停 · ") + name + (current.artist.isEmpty() ? "" : " · " + current.artist));
        if (current != null && !current.cover.isEmpty()) {
            ImageView image = ui.art(0);
            ui.artwork.load(image, current.cover, 640, current.title);
            box.addView(image, 0, new LinearLayout.LayoutParams(-1, ui.dp(220)));
        }
        box.addView(ui.toolbar(ui.withIcon(ui.primary("打开播放器", () -> ui.open(Page.PLAYER)), "play", BG),
                ui.secondary("播放队列", () -> ui.open(Page.QUEUE))));
        if (remoteHasLibrary()) {
            ui.content.addView(ui.menuRow(name + " 的音乐库", "浏览这台设备上的本地音乐", () -> {
                ui.remoteLibTab = 0;
                ui.open(Page.LOCAL);
            }));
            ui.content.addView(ui.menuRow(name + " 的最近播放", "这台设备最近播放的歌曲", () -> {
                ui.remoteLibTab = REMOTE_RECENT;
                ui.open(Page.LOCAL);
            }));
        } else {
            ui.content.addView(ui.text(name + " 播放 TIDAL 和网易云。在下方音源中选歌，就会在这台设备播放。", 14, MUTED, false));
        }
        ui.content.addView(ui.menuRow("浏览 TIDAL", "浏览 TIDAL", () -> ui.enterSource(SearchSource.TIDAL)));
        ui.content.addView(ui.menuRow("浏览网易云", "浏览网易云", () -> ui.enterSource(SearchSource.NETEASE)));
    }

    /** Move this phone's queue and position to {@code device}, pause here and control it. */
    void transferTo(RemoteDevices.Device device) {
        if (ui.playback == null || ui.devices == null) return;
        TidalClient.Track current = ui.playback.current();
        if (current == null || !current.needsTidal()) {
            ui.toast("本地和网易云歌曲只能在本机播放");
            return;
        }
        long position = ui.playback.loading ? 0 : ui.playback.player.getCurrentPosition();
        List<TidalClient.Track> queue = new ArrayList<>(ui.playback.queue);
        int index = ui.playback.index;
        ui.devices.select(device.id);
        remoteTracks(queue, index, false, "play", position);
        if (ui.playback.player.getPlayWhenReady() || ui.playback.loading && ui.playback.intendedPlay) ui.playback.toggle();
        ui.toast("已转到 " + device.name + " 继续播放");
    }

    /** Continue the controlled device's queue on this phone and pause it there. */
    void transferHere() {
        if (!remoteMode() || ui.playback == null) return;
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        if (current == null || current.tidalId <= 0) {
            ui.toast("当前歌曲是该设备的本地或网易云歌曲，无法在本机继续");
            return;
        }
        TidalClient api = ui.playback.client;
        if (api == null) {
            ui.toast(borrowsTidal() ? "本机未登录 TIDAL：借用的「" + ui.devices.targetName() + "」登录只能浏览和下载，"
                    + "歌曲需在该设备上播放。在本机登录 TIDAL 后才能转到本机继续" : "请先在本机登录 TIDAL");
            return;
        }
        // Same 50-track window around the current track as when sending a list.
        List<Long> ids = new ArrayList<>();
        int currentAt = 0;
        for (int i = 0; i < state.queue.size(); i++) {
            long id = state.queue.get(i).tidalId;
            if (id <= 0) continue;
            if (i == state.index) currentAt = ids.size();
            ids.add(id);
        }
        int start = Math.min(currentAt, Math.max(0, ids.size() - 50));
        List<Long> window = new ArrayList<>(ids.subList(start, Math.min(ids.size(), start + 50)));
        long currentId = current.tidalId;
        long position = state.position();
        String name = ui.devices.targetName();
        try {
            if (state.playing) ui.devices.sendNow(ui.devices.target, new JSONObject().put("op", "toggle"));
        } catch (org.json.JSONException ignored) {}
        ui.devices.select(null);
        ui.toast("正在把 " + name + " 的播放转到本机…");
        ui.worker.submit(() -> {
            List<TidalClient.Track> tracks = new ArrayList<>();
            for (long id : window) {
                try {
                    tracks.add(api.track(id));
                } catch (Exception skipped) {
                    // A track this account cannot read is left out.
                }
            }
            int at = -1;
            for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == currentId) at = i;
            int index = at;
            ui.runOnUiThread(() -> {
                if (ui.isDestroyed() || ui.playback == null) return;
                if (index < 0) {
                    ui.toast("无法读取当前歌曲，未能在本机继续");
                    return;
                }
                ui.playback.replaceQueue(tracks, index, false, position);
                ui.toast("已在本机继续播放：" + UiKit.displayTitle(tracks.get(index)));
            });
        });
    }

    void remoteQueueCommand(String op, int index, int to) {
        RemoteDevices.State state = ui.devices.state;
        if (state == null) return;
        try {
            JSONObject command = new JSONObject().put("op", op).put("index", index).put("revision", state.revision);
            if ("move".equals(op)) command.put("to", to);
            ui.devices.send(command);
        } catch (org.json.JSONException ignored) {}
    }

    /** Fill the mini player and Now Playing from the remote device, using the same views. */
    void updateRemotePlayer() {
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track current = state == null ? null : state.current();
        String name = ui.devices.targetName();
        String problem = !ui.devices.error.isEmpty() ? ui.devices.error : state != null ? state.error : "";
        boolean playing = state != null && state.playing;
        String art = current == null ? "" : current.cover;
        String artName = current == null ? name : current.album.isEmpty() ? current.title : current.album;
        if (ui.mini != null) {
            ui.nowPlaying.applyMiniVisibility();
            GradientDrawable outline = ui.shape(RAISED, 15);
            outline.setStroke(ui.dp(1), ACCENT);
            ui.mini.setBackground(outline);
            ui.miniTitle.setText(current != null ? current.title : state == null ? "正在连接 " + name + "…" : "尚未播放");
            ui.miniArtist.setText(!problem.isEmpty() ? problem : "遥控 · " + name
                    + (current == null || current.artist.isEmpty() ? "" : " · " + current.artist));
            ui.miniArtist.setTextColor(problem.isEmpty() ? ACCENT : 0xffff9aa6);
            ui.miniArtist.setVisibility(View.VISIBLE);
            ui.artwork.load(ui.miniArt, art, 160, artName);
            ui.nowPlaying.playIcon(ui.miniPlay, playing);
        }
        if (ui.seek == null) return;
        String coverKey = art + "\n" + artName;
        if (!coverKey.equals(ui.visibleRemoteCover)) {
            ui.visibleRemoteCover = coverKey;
            ui.artwork.load(ui.cover, art, 640, artName);
        }
        ui.playerHeading.setText("遥控 · " + name);
        ui.title.setText(current != null ? current.title : state == null ? "正在连接…" : "尚未播放");
        ui.subtitle.setText(current == null ? "可从歌曲菜单发送到此设备"
                : !current.artist.isEmpty() ? current.artist : current.album);
        showPlayingAlbum(current == null ? "" : current.album, current != null && !current.artist.isEmpty());
        ui.favorite.setVisibility(View.INVISIBLE);
        boolean quality = state != null && (!state.quality.isEmpty() || !state.requested.isEmpty());
        ui.playerQuality.setVisibility(quality ? View.VISIBLE : View.GONE);
        if (quality) {
            int requested = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
            String label = !state.quality.isEmpty() ? state.quality
                    : requested >= 0 ? PlaybackService.QUALITY_NAMES[requested] : "";
            ui.playerQuality.setText(label + (state.requested.isEmpty() ? "" : "  ▾"));
            ui.playerQuality.setEnabled(!state.requested.isEmpty());
            ui.playerQuality.setTextColor(label.startsWith("Max") ? 0xffe8c77a : ACCENT);
        }
        ui.playerDetails.setEnabled(current != null);
        ui.playerDetails.setVisibility(View.VISIBLE);
        ui.playerLyrics.setVisibility(current == null ? View.GONE : View.VISIBLE);
        ui.playerComments.setVisibility(View.GONE);
        ui.nowPlaying.playIcon(ui.play, playing);
        boolean shuffled = state != null && state.shuffle;
        int mode = state == null ? 0 : state.repeat;
        ui.shuffle.setImageDrawable(new PlayerIcon("shuffle", shuffled ? ACCENT : MUTED, ui.dp(22)));
        ui.shuffle.setContentDescription(shuffled ? "关闭随机播放" : "打开随机播放");
        ui.repeat.setImageDrawable(new PlayerIcon(mode == 2 ? "repeat-one" : "repeat", mode == 0 ? MUTED : ACCENT, ui.dp(22)));
        ui.repeat.setContentDescription(mode == 2 ? "单曲循环" : mode == 1 ? "队列循环" : "关闭循环");
        ui.message.setText(!problem.isEmpty() ? problem : state == null ? "正在连接 " + name + "…"
                : state.loading ? "设备正在获取音频流…" : ui.devices.pending() > 0 ? "正在发送…" : "");
        ui.nowPlaying.updatePosition();
    }

    /** The remote device's queue, laid out like this phone's. */
    void remoteQueue() {
        String name = ui.devices.targetName();
        ui.content.addView(ui.menuRow("遥控 · " + name, "切换播放设备", this::showDevicePicker));
        RemoteDevices.State state = ui.devices.state;
        if (state == null) {
            ui.empty(ui.devices.error.isEmpty() ? "正在读取队列…" : ui.devices.error, "队列来自 " + name);
            return;
        }
        if (state.queue.isEmpty()) {
            ui.empty("队列是空的", "从歌曲菜单发送歌曲到 " + name);
            return;
        }
        RemoteDevices.Track playing = state.current();
        if (playing != null && playing.tidalId > 0) {
            ui.content.addView(ui.menuRow("在本机继续播放", ui.playback != null && ui.playback.client == null
                    ? "需先在本机登录 TIDAL" : "把队列和进度转到本机", this::transferHere));
        }
        if (state.index >= 0 && state.index + 1 < state.queue.size()) {
            ui.content.addView(ui.menuRow("清空接下来", "清空该设备接下来的歌曲", () -> {
                if (remoteMode()) ui.devices.send("clear");
            }));
        }
        ui.section(state.queue.size() + " 首歌曲");
        ui.catalogueScreens.showGrowing(state.queue.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                int at = i;
                RemoteDevices.Track track = state.queue.get(at);
                boolean playingNow = i == state.index;
                LinearLayout entry = ui.entry(track.title,
                        playingNow ? "正在播放" + (track.artist.isEmpty() ? "" : " · " + track.artist) : track.artist,
                        track.cover, () -> remoteQueueCommand("play", at, 0));
                if (playingNow) {
                    entry.setTag(MainActivity.QUEUE_PLAYING);
                    entry.setBackground(ui.shape(RAISED, 12));
                    LinearLayout info = (LinearLayout) entry.getChildAt(1);
                    ((TextView) info.getChildAt(0)).setTextColor(ACCENT);
                    ((TextView) info.getChildAt(1)).setTextColor(ACCENT);
                }
                entry.addView(ui.iconButton("more", "调整队列 " + track.title, () -> editRemoteQueue(at, track)));
                ui.content.addView(entry);
            }
        });
        if (ui.revealQueue && state.index >= MainActivity.LOCAL_PAGE_SIZE) ui.catalogueScreens.fillTo(state.index);
    }

    void editRemoteQueue(int at, RemoteDevices.Track track) {
        new AlertDialog.Builder(ui).setTitle(track.title)
                .setItems(new String[]{"移到上一位", "移到下一位", "移除"}, (dialog, choice) -> {
                    RemoteDevices.State state = remoteMode() ? ui.devices.state : null;
                    if (state == null || at >= state.queue.size()) return;
                    if (choice == 0 && at > 0) remoteQueueCommand("move", at, at - 1);
                    else if (choice == 1 && at + 1 < state.queue.size()) remoteQueueCommand("move", at, at + 1);
                    else if (choice == 2) {
                        if (at == state.index) ui.toast("正在播放的歌曲不能移除");
                        else remoteQueueCommand("remove", at, 0);
                    }
                }).show();
    }

    static String deviceStatus(RemoteDevices.Device device) {
        if (!device.online) return "离线";
        if (!device.hasNow) return "在线 · 空闲";
        return (device.nowPlaying ? "正在播放 " : "已暂停 ") + device.nowTitle;
    }

    void selectDevice(String id) {
        if (ui.devices == null) return;
        boolean borrowed = borrowsTidal();
        String previous = ui.devices.targetName();
        ui.devices.select(id);
        if (id == null && borrowed) ui.toast("已切回本机播放 · 本机未登录 TIDAL，不再使用「" + previous + "」的登录");
        else ui.toast(id == null ? "已切回本机播放" : "正在遥控：" + ui.devices.targetName());
    }

    /** Choose which device the player controls. */
    void showDevicePicker() {
        if (ui.devices == null) {
            ui.toast("请先在 设置 → 设备与连接 登录播放器账号");
            return;
        }
        ui.devices.refreshSoon();
        List<RemoteDevices.Device> others = new ArrayList<>();
        for (RemoteDevices.Device device : ui.devices.devices) if (!device.self) others.add(device);
        List<String> labels = new ArrayList<>();
        labels.add((remoteMode() ? "○  " : "●  ") + "本机 · 此手机");
        RemoteDevices.Track remoteCurrent = remoteMode() && ui.devices.state != null ? ui.devices.state.current() : null;
        boolean canPull = remoteCurrent != null && remoteCurrent.tidalId > 0;
        if (canPull) labels.add("⇠  在本机继续播放");
        for (RemoteDevices.Device device : others) {
            boolean selected = device.id.equals(ui.devices.target);
            labels.add((selected ? "●  " : "○  ") + device.name + "\n      " + deviceStatus(device));
        }
        if (others.isEmpty()) labels.add(ui.devices.devicesLoaded ? "暂无其他设备" : "正在读取设备…");
        labels.add("管理设备…");
        new AlertDialog.Builder(ui).setTitle("播放设备")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int offset = canPull ? 2 : 1;
                    if (which == 0) selectDevice(null);
                    else if (canPull && which == 1) transferHere();
                    else if (which == labels.size() - 1) ui.open(Page.DEVICES);
                    else if (which - offset < others.size()) {
                        RemoteDevices.Device device = others.get(which - offset);
                        if (device.online) selectDevice(device.id);
                        else ui.toast(device.name + " 离线");
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** Every player signed in to this player account. */
    void devicesPage() {
        if (ui.devices == null) {
            ui.empty("尚未登录播放器账号", "在 设置 → 设备与连接 登录后，可查看并遥控同一账号的电脑和手机。");
            return;
        }
        String username = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE).getString("player_account", "");
        ui.content.addView(ui.text("登录播放器账号「" + username + "」的电脑和手机。选择一台设备后，迷你播放器、正在播放和播放队列会改为控制它。",
                14, MUTED, false));
        if (!ui.devices.devicesError.isEmpty()) ui.content.addView(ui.text(ui.devices.devicesError, 14, 0xffff9aa6, false));
        if (!ui.devices.devicesLoaded) {
            ui.empty("正在读取设备…", "");
            return;
        }
        List<RemoteDevices.Device> sorted = new ArrayList<>(ui.devices.devices);
        java.util.Collections.sort(sorted, (a, b) -> a.self != b.self ? (a.self ? -1 : 1) : Boolean.compare(b.online, a.online));
        ui.content.addView(new View(ui), new LinearLayout.LayoutParams(-1, ui.dp(12)));
        for (RemoteDevices.Device device : sorted) deviceCard(device);
        if (sorted.size() <= 1) {
            ui.content.addView(ui.text("其他电脑或手机登录同一个播放器账号后会出现在这里。", 14, MUTED, false));
        } else {
            ui.content.addView(ui.menuRow("退出其他所有设备", "其他电脑、手机和网页都需要重新登录", () ->
                    new AlertDialog.Builder(ui).setTitle("退出其他所有设备？")
                            .setMessage("其他电脑、手机和网页遥控都需要重新登录播放器账号，本机保持登录。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("退出", (dialog, which) -> {
                                if (ui.devices != null) ui.devices.logoutOthers(ui::render);
                            }).show()));
        }
    }

    void deviceCard(RemoteDevices.Device device) {
        boolean selected = device.id.equals(ui.devices.target);
        String state = device.self ? "本机" : selected ? "正在遥控" : device.online ? "在线" : "离线";
        String detail = device.platformLabel() + (device.version.isEmpty() ? "" : " · 版本 " + device.version) + " · " + state
                + (device.online || device.self ? "" : " · " + device.lastSeenLabel());
        LinearLayout box = ui.panel(device.name, detail);
        if (selected) {
            GradientDrawable outline = ui.shape(ListeningRoomColors.SURFACE, 16);
            outline.setStroke(ui.dp(1), ACCENT);
            box.setBackground(outline);
        }
        ((TextView) box.getChildAt(0)).setCompoundDrawablesRelativeWithIntrinsicBounds(
                new PlayerIcon("devices", selected || device.self ? ACCENT : MUTED, ui.dp(20)), null, null, null);
        ((TextView) box.getChildAt(0)).setCompoundDrawablePadding(ui.dp(8));
        if (device.hasNow) {
            LinearLayout now = ui.row();
            ImageView image = ui.art(44);
            ui.artwork.load(image, device.nowCover, 160, device.nowTitle);
            now.addView(image);
            LinearLayout info = ui.column();
            info.setPadding(ui.dp(12), 0, 0, 0);
            info.addView(ui.single(device.nowPlaying ? "正在播放" : "已暂停", 12, device.nowPlaying ? ACCENT : MUTED, false));
            info.addView(ui.single(device.nowTitle, 15, INK, true));
            if (!device.nowArtist.isEmpty()) info.addView(ui.single(device.nowArtist, 12, MUTED, false));
            now.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.bottomMargin = ui.dp(8);
            box.addView(now, spacing);
        }
        if (device.self) return;
        List<Button> buttons = new ArrayList<>();
        if (selected) buttons.add(ui.secondary("切回本机", () -> selectDevice(null)));
        else {
            Button control = ui.withIcon(ui.primary("遥控", () -> selectDevice(device.id)), "devices", BG);
            control.setEnabled(device.online);
            control.setAlpha(device.online ? 1f : 0.45f);
            buttons.add(control);
            TidalClient.Track local = ui.playback == null ? null : ui.playback.current();
            if (!remoteMode() && device.online && local != null && local.needsTidal()) {
                buttons.add(ui.secondary("转到此设备", () -> transferTo(device)));
            }
        }
        Button remove = ui.secondary("移除", () -> new AlertDialog.Builder(ui).setTitle("移除 " + device.name + "？")
                .setMessage("该设备需要重新登录播放器账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    if (ui.devices != null) ui.devices.remove(device.id, ui::render);
                }).show());
        remove.setTextColor(0xffff8f8f);
        buttons.add(remove);
        box.addView(ui.toolbar(buttons.toArray(new Button[0])));
    }

    void editQueue(int at, TidalClient.Track track) {
        new AlertDialog.Builder(ui).setTitle(track.title)
                .setItems(new String[]{"移到上一位", "移到下一位", "移除"}, (dialog, choice) -> {
                    if (ui.playback == null || at >= ui.playback.queue.size() || ui.playback.queue.get(at) != track) return;
                    if (choice == 0) ui.playback.move(at, at - 1);
                    else if (choice == 1) ui.playback.move(at, at + 1);
                    else if (at == ui.playback.index) ui.toast("正在播放的歌曲不能移除");
                    else ui.playback.remove(at);
                    ui.render();
                }).show();
    }
}
