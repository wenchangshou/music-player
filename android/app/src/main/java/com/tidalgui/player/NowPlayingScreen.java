package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: NowPlayingScreen. */
@UnstableApi
final class NowPlayingScreen {
    final MainActivity ui;

    NowPlayingScreen(MainActivity ui) {
        this.ui = ui;
    }

    TextView time;
    Button playerDevices;

    void buildMini() {
        ui.mini = ui.column();
        ui.mini.setBackground(ui.shapeWithStroke(RAISED, 0x1affffff, 1, 12));
        ui.mini.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(2));
        ui.root.addView(ui.mini, params);

        LinearLayout bar = ui.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(6), 0, ui.dp(2), 0);
        bar.setMinimumHeight(ui.dp(44));
        ui.mini.addView(bar);

        ui.miniArt = ui.art(32);
        bar.addView(ui.miniArt);
        LinearLayout info = ui.column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(ui.dp(8), 0, 0, 0);
        ui.miniTitle = ui.single("", 13, INK, true);
        ui.miniArtist = ui.single("", 10, MUTED, false);
        info.addView(ui.miniTitle);
        info.addView(ui.miniArtist);
        info.setContentDescription("正在播放，左右滑动切歌");
        info.setOnClickListener(v -> ui.open(Page.PLAYER));
        ui.miniArt.setOnClickListener(v -> ui.open(Page.PLAYER));
        bar.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams switchLayout = new LinearLayout.LayoutParams(-2, -2);
        switchLayout.setMarginStart(ui.dp(4));
        bar.addView(playbackModeSwitch(false), switchLayout);

        GestureDetector gestures = new GestureDetector(ui, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                ui.open(Page.PLAYER);
                return true;
            }
            @Override public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (start == null || end == null) return false;
                float dx = end.getX() - start.getX();
                if (Math.abs(dx) > ui.dp(48) && Math.abs(dx) > Math.abs(end.getY() - start.getY())
                        && Math.abs(velocityX) > ui.dp(180)) {
                    ui.remoteScreens.control(dx < 0 ? "next" : "previous");
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener swipe = (view, event) -> {
            gestures.onTouchEvent(event);
            return true;
        };
        info.setOnTouchListener(swipe);
        ui.miniArt.setOnTouchListener(swipe);

        if (ui.devices != null) {
            bar.addView(ui.glyph("devices", ui.remoteScreens.remoteMode() ? ACCENT : MUTED, 18, "切换播放设备", ui.remoteScreens::showDevicePicker),
                    new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        }
        bar.addView(ui.glyph("queue", MUTED, 18, "查看播放队列", () -> ui.open(Page.QUEUE)),
                new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ui.miniPlay = ui.glyph("play", INK, 18, "播放", this::toggle);
        ui.miniPlay.setBackground(ui.shape(SURFACE, 20));
        bar.addView(ui.miniPlay, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        ui.miniTrack = new android.widget.FrameLayout(ui);
        ui.miniTrack.setBackgroundColor(0x22ffffff);
        ui.miniProgress = new View(ui);
        ui.miniProgress.setBackgroundColor(ACCENT);
        ui.miniTrack.addView(ui.miniProgress, new android.widget.FrameLayout.LayoutParams(0, -1));
        ui.mini.addView(ui.miniTrack, new LinearLayout.LayoutParams(-1, ui.dp(2)));
    }

    void buildNowPlaying() {
        // 1. Top row: Down button (32dp) + 2-line header + More button (32dp)
        LinearLayout top = ui.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        top.addView(ui.iconButton("down", "收起为迷你播放", () -> showPlaybackMode(false)),
                new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));

        LinearLayout headerCol = ui.column();
        headerCol.setGravity(Gravity.CENTER_HORIZONTAL);
        headerCol.addView(playbackModeSwitch(true));
        ui.playerHeading = ui.text("正在播放", 13, INK, true);
        ui.playerHeading.setGravity(Gravity.CENTER);
        ui.playerHeading.setSingleLine();
        ui.playerHeading.setEllipsize(TextUtils.TruncateAt.END);
        headerCol.addView(ui.playerHeading);

        ui.playerSubHeading = ui.text("", 10, MUTED, false);
        ui.playerSubHeading.setGravity(Gravity.CENTER);
        ui.playerSubHeading.setSingleLine();
        ui.playerSubHeading.setEllipsize(TextUtils.TruncateAt.END);
        headerCol.addView(ui.playerSubHeading);
        top.addView(headerCol, new LinearLayout.LayoutParams(0, -2, 1));

        top.addView(ui.iconButton("more", "歌曲选项", () -> {
            if (ui.remoteScreens.remoteMode()) ui.remoteScreens.showDevicePicker();
            else if (ui.playback != null && ui.playback.current() != null) ui.catalogueScreens.trackMenu(ui.playback.current(), false);
        }), new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        ui.root.addView(top);

        // 2. Stage FrameLayout with weight = 1f (Dynamically sized cover, no scroll overflow!)
        android.widget.FrameLayout artStage = new android.widget.FrameLayout(ui);
        GradientDrawable stageBackdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x141822, BG});
        artStage.setBackground(stageBackdrop);

        android.widget.FrameLayout artFrame = new android.widget.FrameLayout(ui);
        ui.cover = ui.art(0);
        ui.cover.setImageResource(R.drawable.listening_room);
        ui.cover.setContentDescription("显示歌词");
        artFrame.addView(ui.cover, new android.widget.FrameLayout.LayoutParams(-1, -1));

        ui.lyricsPane = new android.widget.FrameLayout(ui);
        ui.lyricsPane.setBackgroundColor(BG);
        ui.lyricsPane.setVisibility(View.GONE);

        artStage.addView(artFrame, new android.widget.FrameLayout.LayoutParams(ui.dp(190), ui.dp(190), Gravity.CENTER));
        artStage.addView(ui.lyricsPane, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Square cover fills the stage; lyrics use the stage's full width above the controls.
        artStage.addOnLayoutChangeListener((v, left, topPos, right, bottom, oldL, oldT, oldR, oldB) -> {
            int w = right - left - ui.dp(24);
            int h = bottom - topPos - ui.dp(8);
            int size = Math.max(ui.dp(120), Math.min(w, h));
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) artFrame.getLayoutParams();
            if (lp.width != size || lp.height != size) {
                lp.width = size;
                lp.height = size;
                lp.gravity = Gravity.CENTER;
                artFrame.setLayoutParams(lp);
            }
        });

        // Gestures on stage: swipe down to dismiss, swipe left/right for next/prev, tap for lyrics
        GestureDetector stageGesture = new GestureDetector(ui, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                ui.trackExtras.toggleLyrics();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (dy > ui.dp(50) && Math.abs(velocityY) > ui.dp(200) && Math.abs(dy) > Math.abs(dx)) {
                    showPlaybackMode(false);
                    return true;
                }
                if (Math.abs(dx) > ui.dp(50) && Math.abs(velocityX) > ui.dp(200)) {
                    if (dx > 0) ui.remoteScreens.control("previous");
                    else ui.remoteScreens.control("next");
                    return true;
                }
                return false;
            }
        });
        artStage.setOnTouchListener((v, event) -> stageGesture.onTouchEvent(event));
        ui.root.addView(artStage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 3. Lower Controls Container
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(8));

        // Title row: Track Title (17sp), Subtitle (12sp), Favorite Heart (32dp)
        LinearLayout titleRow = ui.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, ui.dp(4), 0, 0);

        LinearLayout song = ui.column();
        ui.title = ui.single("", 17, INK, true);
        ui.subtitle = ui.single("", 12, ACCENT, false);
        ui.subtitle.setCompoundDrawablePadding(ui.dp(4));
        ui.subtitle.setOnClickListener(v -> {
            if (ui.remoteScreens.hasPlayingArtist()) ui.remoteScreens.openPlayingArtist();
        });
        ui.playerAlbum = ui.single("", 11, ACCENT, false);
        ui.playerAlbum.setVisibility(View.GONE);
        ui.playerAlbum.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                new PlayerIcon("forward", ACCENT, ui.dp(12)), null);
        ui.playerAlbum.setCompoundDrawablePadding(ui.dp(4));
        ui.playerAlbum.setOnClickListener(v -> ui.remoteScreens.openPlayingAlbum());

        song.addView(ui.title);
        song.addView(ui.subtitle);
        song.addView(ui.playerAlbum);
        titleRow.addView(song, new LinearLayout.LayoutParams(0, -2, 1));

        ui.favorite = ui.glyph("heart", MUTED, 18, "收藏歌曲", () -> {
            if (ui.playback != null && ui.playback.current() != null) {
                TidalClient.Track cur = ui.playback.current();
                if (cur.isLocal() || cur.isNetease()) {
                    ui.playback.localLibrary.toggleLike(cur);
                    ui.playback.libraryChanged();
                    updatePlayer();
                } else ui.changeFavorite(cur, !ui.favoriteIds.contains(cur.id));
            }
        });
        titleRow.addView(ui.favorite, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        body.addView(titleRow);

        // 4. Hi-Fi Spec Capsule (Single-line Frosted Glass Capsule)
        LinearLayout specCard = ui.row();
        specCard.setGravity(Gravity.CENTER_VERTICAL);
        specCard.setBackground(ui.shapeWithStroke(0x18ffffff, 0x1affffff, 1, 10));
        specCard.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        LinearLayout.LayoutParams specLp = new LinearLayout.LayoutParams(-1, ui.dp(32));
        specLp.topMargin = ui.dp(6);
        specLp.bottomMargin = ui.dp(4);
        specCard.setLayoutParams(specLp);
        specCard.setOnClickListener(v -> {
            if (ui.playback != null && ui.playback.current() != null && (ui.playback.current().isLocal() || ui.playback.current().isWebdav() || ui.playback.current().isSmb())) {
                showPlayerDetails();
            } else chooseQuality();
        });

        ui.playerSpecTag = ui.text("FLAC", 9, GOLD, true);
        ui.playerSpecTag.setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1));
        ui.playerSpecTag.setBackground(ui.shapeWithStroke(0x22d4a359, GOLD, 1, 4));
        specCard.addView(ui.playerSpecTag);

        ui.playerSpecFormat = ui.text("FLAC · 24-bit / 192.0 kHz", 10, INK, true);
        ui.playerSpecFormat.setPadding(ui.dp(6), 0, 0, 0);
        ui.playerSpecFormat.setSingleLine();
        ui.playerSpecFormat.setEllipsize(TextUtils.TruncateAt.END);
        specCard.addView(ui.playerSpecFormat, new LinearLayout.LayoutParams(0, -2, 1));

        ui.playerSpecEngine = ui.text("硬件直通", 9, MUTED, false);
        ui.playerSpecEngine.setPadding(ui.dp(4), 0, 0, 0);
        specCard.addView(ui.playerSpecEngine);

        ui.playerSpecBitrate = new TextView(ui);
        ui.playerSpecPort = new TextView(ui);
        ui.playerQuality = new Button(ui);
        ui.playerQuality.setVisibility(View.GONE);
        ui.playerDetails = new Button(ui);
        ui.playerDetails.setVisibility(View.GONE);
        ui.playerComments = new Button(ui);
        ui.playerComments.setVisibility(View.GONE);

        body.addView(specCard);

        // 5. Seekbar & Timing
        ui.seek = new SeekBar(ui);
        ui.seek.setMax(1000);
        ui.seek.setProgressTintList(ColorStateList.valueOf(GOLD));
        ui.seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x33ffffff));
        ui.seek.setThumbTintList(ColorStateList.valueOf(GOLD));
        ui.seek.setContentDescription("播放进度");
        ui.seek.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        LinearLayout.LayoutParams seekLayout = new LinearLayout.LayoutParams(-1, ui.dp(24));
        seekLayout.topMargin = ui.dp(4);
        body.addView(ui.seek, seekLayout);
        ui.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && !ui.dragging && (ui.remoteScreens.remoteMode()
                        || ui.playback != null && ui.playback.player.isCurrentMediaItemSeekable())) {
                    seekTo(bar);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { ui.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekTo(bar);
                ui.dragging = false;
            }
        });

        LinearLayout timing = ui.row();
        timing.setGravity(Gravity.CENTER_VERTICAL);
        timing.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        time = ui.text("0:00", 10, MUTED, false);
        timing.addView(time, new LinearLayout.LayoutParams(-2, -2));

        ui.playerNextTrack = ui.text("", 9, MUTED, false);
        ui.playerNextTrack.setGravity(Gravity.CENTER);
        ui.playerNextTrack.setSingleLine();
        ui.playerNextTrack.setEllipsize(TextUtils.TruncateAt.END);
        ui.playerNextTrack.setOnClickListener(v -> showQueueSheet());
        timing.addView(ui.playerNextTrack, new LinearLayout.LayoutParams(0, -2, 1));

        ui.total = ui.text("0:00", 10, MUTED, false);
        ui.total.setGravity(Gravity.END);
        timing.addView(ui.total, new LinearLayout.LayoutParams(-2, -2));
        body.addView(timing);

        // 6. Transport Controls (Height ~48dp)
        LinearLayout transport = ui.row();
        transport.setGravity(Gravity.CENTER);
        body.addView(transport, new LinearLayout.LayoutParams(-1, ui.dp(48)));

        ui.shuffle = ui.glyph("shuffle", MUTED, 15, "随机播放", () -> ui.remoteScreens.control("shuffle"));
        ui.repeat = ui.glyph("repeat", MUTED, 15, "循环模式", () -> ui.remoteScreens.control("repeat"));
        ui.play = ui.glyph("play", BG, 18, "播放", this::toggle);
        ui.play.setBackground(ui.shape(GOLD, 24));

        transport.addView(ui.shuffle, new LinearLayout.LayoutParams(0, ui.dp(32), 1));
        transport.addView(ui.glyph("previous", INK, 18, "上一首", () -> ui.remoteScreens.control("previous")),
                new LinearLayout.LayoutParams(0, ui.dp(36), 1));
        transport.addView(ui.play, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        transport.addView(ui.glyph("next", INK, 18, "下一首", () -> ui.remoteScreens.control("next")),
                new LinearLayout.LayoutParams(0, ui.dp(36), 1));
        transport.addView(ui.repeat, new LinearLayout.LayoutParams(0, ui.dp(32), 1));

        // 7. Extras Bottom Row (Height ~32dp, 3 refined action capsules)
        LinearLayout extras = ui.row();
        extras.setGravity(Gravity.CENTER);
        extras.setPadding(0, ui.dp(4), 0, 0);

        ui.playerLyrics = ui.pillBtn("歌词", "lyrics", ui.trackExtras::toggleLyrics);
        playerDevices = ui.pillBtn(ui.remoteScreens.remoteMode() ? ui.devices.targetName() : "输出", "devices", ui.remoteScreens::showDevicePicker);
        ui.playerQueueBtn = ui.pillBtn("队列", "queue", this::showQueueSheet);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ui.dp(32), 1);
        btnLp.setMargins(ui.dp(3), 0, ui.dp(3), 0);
        extras.addView(ui.playerLyrics, btnLp);
        extras.addView(playerDevices, btnLp);
        extras.addView(ui.playerQueueBtn, btnLp);
        body.addView(extras);

        ui.message = ui.text("", 11, MUTED, false);
        ui.message.setGravity(Gravity.CENTER);
        body.addView(ui.message);
        ui.root.addView(body);
    }

    /** Cover-sized player, or the bottom bar. The same control is on both. */
    LinearLayout playbackModeSwitch(boolean immersive) {
        LinearLayout pill = ui.row();
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        pill.setBackground(ui.shape(SURFACE, 14));
        pill.setContentDescription(immersive ? "当前是沉浸播放" : "当前是迷你播放");
        pill.addView(playbackModeChip("沉浸", immersive, "切换到沉浸播放", () -> showPlaybackMode(true)));
        pill.addView(playbackModeChip("迷你", !immersive, "切换到迷你播放", () -> showPlaybackMode(false)));
        return pill;
    }

    Button playbackModeChip(String label, boolean selected, String description, Runnable choose) {
        Button chip = ui.action(label, description, choose);
        chip.setTextSize(11);
        chip.setTextColor(selected ? BG : MUTED);
        chip.setBackground(selected ? ui.shape(GOLD, 12) : null);
        chip.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setMinHeight(0);
        chip.setMinimumHeight(0);
        chip.setLayoutParams(new LinearLayout.LayoutParams(-2, ui.dp(24)));
        return chip;
    }

    void showPlaybackMode(boolean immersive) {
        if (immersive) {
            if (ui.page != Page.PLAYER) ui.open(Page.PLAYER);
            return;
        }
        if (ui.page == Page.PLAYER) ui.goBack();
    }

    void showPlayerDetails() {
        if (ui.remoteScreens.remoteMode()) {
            showRemoteDetails();
            return;
        }
        PlaybackService service = ui.playback;
        TidalClient.Track track = service == null ? null : service.current();
        if (track == null) return;
        androidx.media3.common.Format format = service.loading ? null : service.player.getAudioFormat();
        int rate = format != null && format.sampleRate > 0 ? format.sampleRate : service.sourceSampleRate;
        int depth = service.sourceBitDepth > 0 ? service.sourceBitDepth : audioBitDepth(format);
        int bitrate = format != null && format.bitrate > 0 ? format.bitrate : service.sourceBitrate;
        int channels = format == null ? 0 : format.channelCount;
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(18));
        body.addView(ui.text(track.title, 17, INK, true));
        String sourceName = track.isLocal() ? "本地文件"
                : track.isWebdav() ? "WebDAV"
                : track.isSmb() ? "SMB"
                : track.isNetease() ? "网易云" : "TIDAL";
        body.addView(ui.text(sourceName
                + (service.actualQuality.isEmpty() || track.isLocal() || track.isWebdav() || track.isSmb() ? "" : " · " + qualityLabel()), 13, MUTED, false));
        if (!track.isLocal() && !track.isWebdav() && !track.isSmb()) {
            body.addView(ui.text("已选音质 · " + selectedQualityName(), 13, MUTED, false));
        }
        sourceRows(body, sourceName,
                track.isLocal() ? LocalMusicFiles.displayPath(track.localUri)
                : track.isWebdav() ? track.webdavUrl
                : track.isSmb() ? track.smbUrl : "");
        boolean isDsd = false;
        String dsdLabel = "";
        String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
        if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
            isDsd = true;
            dsdLabel = rate >= 11289600 ? "DSD256" : rate >= 5644800 ? "DSD128" : rate >= 2822400 ? "DSD64" : "DSD";
        }
        audioInfoRow(body, "音源采样率", isDsd ? (rate > 0 ? String.format(Locale.ROOT, "%.2f MHz", rate / 1000000.0) : "5.64 MHz") : audioRate(rate),
                "音源位深", isDsd ? "1 bit (Direct Stream)" : audioValue(depth, " bit"));
        if (track.needsTidal() && service.quality == 3 && !service.loading
                && !service.actualQuality.isEmpty() && !"HI_RES_LOSSLESS".equals(service.actualQuality) && depth <= 16) {
            TextView note = ui.text(service.client != null && service.client.hasHiResSession()
                    ? "已选 Max，本次实际音质为 " + qualityLabel() + "。"
                    : "当前没有可用的 Max（HiRes FLAC）会话。请在「服务 → 登录 TIDAL」用设备链接登录。", 13, MUTED, false);
            note.setPadding(0, ui.dp(6), 0, 0);
            body.addView(note);
        }
        audioInfoRow(body, "比特率", isDsd && bitrate == 0 ? "5645 kbps" : audioValue(bitrate / 1000, " kbps"), "声道",
                channels == 2 ? "立体声 · 2" : channels == 1 ? "单声道 · 1" : audioValue(channels, " 声道"));
        body.addView(ui.text("音源格式 · " + (isDsd ? "DSD (" + dsdLabel + ")" : audioCodec(format)), 14, INK, true));
        androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig output = service.outputConfig;
        TextView heading = ui.text("Android 输出", 15, INK, true);
        heading.setPadding(0, ui.dp(18), 0, ui.dp(4));
        body.addView(heading);
        body.addView(ui.text("输出设备 · " + audioDevice(), 13, MUTED, false));
        body.addView(ui.text("音轨采样率 · " + (output == null ? "未知" : audioRate(output.sampleRate)), 13, MUTED, false));
        body.addView(ui.text("输出模式 · " + (output == null ? "未知" : output.offload ? "硬件卸载" : "Android 音轨"), 13, MUTED, false));
        body.addView(ui.text("系统重采样 · 未知", 13, MUTED, false));
        float speed = service.player.getPlaybackParameters().speed;
        if (speed != 1f) body.addView(ui.text("播放速度 · " + speed + "×", 13, MUTED, false));
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(ui).setTitle("播放信息")
                .setView(scroll).setPositiveButton("关闭", null);
        if (!track.isLocal() && !track.isWebdav() && !track.isSmb()) dialog.setNeutralButton("选择音质", (view, choice) -> chooseQuality());
        dialog.show();
    }

    /** 来源 and, for local music, the file path (selectable so it can be copied). */
    void sourceRows(LinearLayout body, String source, String path) {
        body.addView(ui.text("来源 · " + source, 13, MUTED, false));
        if (path.isEmpty()) return;
        TextView file = ui.text("文件路径 · " + path, 13, MUTED, false);
        file.setTextIsSelectable(true);
        body.addView(file);
    }

    /** 播放信息 of the controlled device, from what it reports. */
    void showRemoteDetails() {
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track track = state == null ? null : state.current();
        if (track == null) return;
        String name = ui.devices.targetName();
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(18));
        body.addView(ui.text(track.title, 17, INK, true));
        if (!track.artist.isEmpty()) body.addView(ui.text(track.artist, 13, MUTED, false));
        body.addView(ui.text("播放设备 · " + name, 13, MUTED, false));
        boolean local = "本地音乐".equals(track.source);
        sourceRows(body, local ? "本地文件" : track.source, local ? state.sourcePath : "");
        if (!state.quality.isEmpty()) body.addView(ui.text("实际音质 · " + state.quality, 13, MUTED, false));
        int requested = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
        if ("网易云".equals(track.source) && !state.requestedNetease.isEmpty()) {
            body.addView(ui.text("已选音质 · " + NeteaseClient.levelName(state.requestedNetease), 13, MUTED, false));
        } else if (!local && requested >= 0) {
            body.addView(ui.text("已选音质 · " + PlaybackService.QUALITY_NAMES[requested], 13, MUTED, false));
        }
        if (state.volume >= 0) body.addView(ui.text("音量 · " + state.volume, 13, MUTED, false));
        if (!state.error.isEmpty()) body.addView(ui.text(state.error, 13, 0xffff9aa6, false));
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(ui).setTitle("播放信息 · " + name)
                .setView(scroll).setPositiveButton("关闭", null);
        if (!local && requested >= 0) dialog.setNeutralButton("选择音质", (view, choice) -> chooseQuality());
        dialog.show();
    }

    /**
     * Lyrics of the controlled device's track, following its position. TIDAL lyrics come from this
     * phone's own TIDAL login; other tracks are asked from the device, which can read its own files.
     */
    void showRemoteLyrics() {
        RemoteDevices.State state = ui.devices.state;
        RemoteDevices.Track track = state == null ? null : state.current();
        if (track == null) return;
        TidalClient.Track shown;
        try {
            shown = new TidalClient.Track(new JSONObject().put("id", track.id).put("title", track.title));
        } catch (Exception impossible) {
            return;
        }
        LyricsDialog view = ui.trackExtras.inlineLyrics(shown, millis -> {
            try {
                if (ui.remoteScreens.remoteMode()) ui.devices.send(new JSONObject().put("op", "seek").put("ms", millis));
            } catch (org.json.JSONException ignored) {}
        });
        TidalClient api = ui.remoteScreens.tidalApi();
        if (track.tidalId > 0 && api != null) {
            long id = track.tidalId;
            ui.worker.submit(() -> {
                try {
                    JSONObject data = api.lyrics(id);
                    ui.runOnUiThread(() -> { if (ui.lyrics == view) view.display(data); });
                } catch (Exception error) {
                    ui.runOnUiThread(() -> { if (ui.lyrics == view) view.unavailable(UiKit.readable(error)); });
                }
            });
            return;
        }
        if (!ui.remoteScreens.remoteHasLibrary()) {
            view.unavailable(ui.devices.targetName() + " 上的这首歌暂时无法读取歌词");
            return;
        }
        try {
            ui.devices.query(new JSONObject().put("op", "library").put("action", "lyrics").put("id", track.id),
                    (result, error) -> {
                        if (ui.lyrics != view) return;
                        if (!error.isEmpty()) {
                            view.unavailable(error);
                            return;
                        }
                        // The device sends synced or plain lyrics as "lyrics"; the dialog parses either.
                        String words = result.optString("lyrics");
                        try {
                            view.display(new JSONObject().put("subtitles", words).put("lyrics", words));
                        } catch (org.json.JSONException ignored) {}
                    });
        } catch (org.json.JSONException ignored) {}
    }

    void audioInfoRow(LinearLayout parent, String left, String leftValue, String right, String rightValue) {
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(8), 0, ui.dp(4));
        row.addView(audioInfoTile(left, leftValue), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = ui.dp(8);
        row.addView(audioInfoTile(right, rightValue), second);
        parent.addView(row);
    }

    LinearLayout audioInfoTile(String label, String value) {
        LinearLayout tile = ui.column();
        tile.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        tile.setBackground(ui.shape(SURFACE, 9));
        tile.addView(ui.text(label, 12, MUTED, false));
        tile.addView(ui.text(value, 16, INK, true));
        return tile;
    }

    String audioDevice() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build();
                List<android.media.AudioDeviceInfo> routes = ui.getSystemService(android.media.AudioManager.class)
                        .getAudioDevicesForAttributes(attributes);
                if (!routes.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (android.media.AudioDeviceInfo device : routes) {
                        String name = device.getProductName().toString();
                        if (!name.isEmpty() && !names.contains(name)) names.add(name);
                    }
                    if (!names.isEmpty()) return TextUtils.join("、", names);
                }
            } catch (RuntimeException ignored) {}
        }
        return "未知";
    }

    static String audioRate(int rate) {
        return rate > 0 ? String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0) : "未知";
    }

    static String audioValue(int value, String unit) {
        return value > 0 ? value + unit : "未知";
    }

    static String audioCodec(androidx.media3.common.Format format) {
        if (format == null || format.sampleMimeType == null) return "未知";
        switch (format.sampleMimeType) {
            case "audio/flac": return "FLAC";
            case "audio/raw": return "PCM";
            case "audio/mpeg": return "MP3";
            case "audio/mp4a-latm": return "AAC";
            case "audio/opus": return "Opus";
            case "audio/vorbis": return "Vorbis";
            default: return format.sampleMimeType;
        }
    }

    static int audioBitDepth(androidx.media3.common.Format format) {
        if (format == null) return 0;
        switch (format.pcmEncoding) {
            case androidx.media3.common.C.ENCODING_PCM_8BIT: return 8;
            case androidx.media3.common.C.ENCODING_PCM_16BIT: return 16;
            case androidx.media3.common.C.ENCODING_PCM_24BIT: return 24;
            case androidx.media3.common.C.ENCODING_PCM_32BIT: return 32;
            default: break;
        }
        if ("audio/flac".equals(format.sampleMimeType) && !format.initializationData.isEmpty()) {
            byte[] data = format.initializationData.get(0);
            if (data.length >= 42 && data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C'
                    && (data[4] & 0x7f) == 0) {
                return (((data[20] & 1) << 4) | ((data[21] & 0xf0) >> 4)) + 1;
            }
        }
        return 0;
    }

    void seekTo(SeekBar bar) {
        if (ui.remoteScreens.remoteMode()) {
            RemoteDevices.State state = ui.devices.state;
            if (state == null || !state.seekable || state.durationMs <= 0) return;
            try {
                ui.devices.send(new JSONObject().put("op", "seek").put("ms", state.durationMs * bar.getProgress() / bar.getMax()));
            } catch (org.json.JSONException ignored) {}
            return;
        }
        if (ui.playback == null || ui.playback.loading || !ui.playback.player.isCurrentMediaItemSeekable()) return;
        long duration = ui.playback.player.getDuration();
        if (duration > 0) ui.playback.player.seekTo(duration * bar.getProgress() / bar.getMax());
    }

    void toggle() {
        ui.remoteScreens.control("toggle");
    }

    /** Quality picker for the current track: 网易云's levels for NetEase tracks, TIDAL's otherwise. */
    void chooseQuality() {
        if (ui.remoteScreens.remoteMode()) {
            RemoteDevices.State state = ui.devices.state;
            if (state == null) return;
            RemoteDevices.Track current = state.current();
            if (current != null && "网易云".equals(current.source)) {
                int level = java.util.Arrays.asList(NeteaseClient.PLAY_LEVELS).indexOf(state.requestedNetease);
                new AlertDialog.Builder(ui).setTitle(ui.devices.targetName() + " 的网易云音质")
                        .setSingleChoiceItems(NeteaseClient.PLAY_CHOICES, level, (dialog, choice) -> {
                            dialog.dismiss();
                            try {
                                ui.devices.send(new JSONObject().put("op", "quality")
                                        .put("value", "netease:" + NeteaseClient.PLAY_LEVELS[choice]));
                            } catch (org.json.JSONException ignored) {}
                        }).setNegativeButton("取消", null).show();
                return;
            }
            int selected = java.util.Arrays.asList(PlaybackService.QUALITIES).indexOf(state.requested);
            new AlertDialog.Builder(ui).setTitle(ui.devices.targetName() + " 的播放音质")
                    .setSingleChoiceItems(PlaybackService.QUALITY_CHOICES, selected, (dialog, choice) -> {
                        dialog.dismiss();
                        try {
                            ui.devices.send(new JSONObject().put("op", "quality").put("value", PlaybackService.QUALITIES[choice]));
                        } catch (org.json.JSONException ignored) {}
                    }).setNegativeButton("取消", null).show();
            return;
        }
        if (ui.playback == null) return;
        TidalClient.Track track = ui.playback.current();
        if (track != null && track.isNetease()) chooseNeteaseQuality();
        else chooseTidalQuality();
    }

    void chooseTidalQuality() {
        if (ui.playback == null) return;
        new AlertDialog.Builder(ui).setTitle("TIDAL 播放音质")
                .setSingleChoiceItems(PlaybackService.QUALITY_CHOICES, ui.playback.quality, (dialog, selected) -> {
                    dialog.dismiss();
                    ui.playback.setQuality(selected);
                    if (ui.page == Page.SETTINGS) ui.render();
                }).setNegativeButton("取消", null).show();
    }

    void chooseNeteaseQuality() {
        if (ui.playback == null) return;
        int selected = java.util.Arrays.asList(NeteaseClient.PLAY_LEVELS).indexOf(ui.playback.neteaseLevel);
        new AlertDialog.Builder(ui).setTitle("网易云播放音质")
                .setSingleChoiceItems(NeteaseClient.PLAY_CHOICES, selected, (dialog, choice) -> {
                    dialog.dismiss();
                    ui.playback.setNeteaseLevel(NeteaseClient.PLAY_LEVELS[choice]);
                    if (ui.page == Page.SETTINGS) ui.render();
                }).setNegativeButton("取消", null).show();
    }

    /** The selected quality for the current track, by its official name. */
    String selectedQualityName() {
        TidalClient.Track track = ui.playback.current();
        return track != null && track.isNetease() ? NeteaseClient.levelName(ui.playback.neteaseLevel)
                : PlaybackService.QUALITY_NAMES[ui.playback.quality];
    }

    String qualityLabel() {
        if (ui.playback == null) return PlaybackService.QUALITY_NAMES[0];
        for (int i = 0; i < PlaybackService.QUALITIES.length; i++) {
            if (PlaybackService.QUALITIES[i].equals(ui.playback.actualQuality)) return PlaybackService.QUALITY_NAMES[i];
        }
        if (!ui.playback.actualQuality.isEmpty()) return ui.playback.actualQuality;
        return selectedQualityName();
    }

    void updatePlayer() {
        if (ui.playback == null || ui.isDestroyed()) return;
        if (ui.visibleLocalRevision != ui.playback.localRevision
                && (ui.page == Page.HOME || ui.page == Page.LOCAL || (ui.page == Page.SEARCH && ui.searchSource == SearchSource.LOCAL))) {
            ui.render();
            return;
        }
        if (ui.visibleAccount != ui.remoteScreens.tidalApi()) {
            ui.visibleAccount = ui.remoteScreens.tidalApi();
            ui.importExport.clearFavorites();
            if ((ui.page == Page.SEARCH && ui.searchSource != SearchSource.LOCAL) || ui.page == Page.LIBRARY || ui.page == Page.COLLECTION) ui.catalogueScreens.load(false);
            else ui.render();
            return;
        }
        if ((ui.page == Page.CONNECTIONS && !ui.visibleRemoteLink.equals(ui.playback.remoteLink()))
                || (ui.page == Page.QUEUE && (ui.visibleQueueIndex != ui.playback.index
                || ui.visibleQueueRevision != ui.playback.queueRevision))) {
            ui.render();
            return;
        }
        if (ui.remoteScreens.remoteMode()) {
            ui.remoteScreens.updateRemotePlayer();
            return;
        }
        TidalClient.Track current = ui.playback.current();
        boolean playing = ui.playback.loading ? ui.playback.intendedPlay
                : ui.playback.player.getPlayWhenReady() && ui.playback.player.getPlaybackState() != Player.STATE_ENDED;
        if (ui.mini != null) {
            applyMiniVisibility();
            ui.miniTitle.setText(current == null ? "队列已就绪" : UiKit.displayTitle(current));
            ui.miniArtist.setText(!ui.playback.error.isEmpty() ? ui.playback.error : current == null ? "点击播放" : current.artist);
            ui.miniArtist.setVisibility(ui.miniArtist.getText().length() == 0 ? View.GONE : View.VISIBLE);
            ui.artwork.load(ui.miniArt, current, 160);
            playIcon(ui.miniPlay, playing);
        }
        if (ui.seek == null) return;
        if (ui.visibleTrack != (current == null ? Long.MIN_VALUE : current.id)) {
            ui.visibleTrack = current == null ? Long.MIN_VALUE : current.id;
            ui.artwork.load(ui.cover, current, 640);
        }
        ui.title.setText(current == null ? "尚未播放" : UiKit.displayTitle(current));
        String byline = current == null ? "" : !current.artist.isEmpty() ? current.artist
                : !current.album.isEmpty() ? current.album : current.isLocal() ? "本地音频" : "";
        ui.subtitle.setText(byline);
        ui.remoteScreens.showPlayingAlbum(current == null ? "" : current.album, current != null && !current.artist.isEmpty());
        boolean local = current != null && current.isLocal();
        boolean webdav = current != null && current.isWebdav();
        boolean smb = current != null && current.isSmb();

        if (current != null) {
            // 1. Top Bar Header & Subheader
            int qIndex = ui.playback.index;
            int qTotal = ui.playback.queue.size();

            // 3. Hi-Fi Spec Capsule - Authentic Audio Spec Resolution
            androidx.media3.common.Format format = ui.playback.loading ? null : ui.playback.player.getAudioFormat();
            int rate = format != null && format.sampleRate > 0 ? format.sampleRate : ui.playback.sourceSampleRate;
            int depth = ui.playback.sourceBitDepth > 0 ? ui.playback.sourceBitDepth : audioBitDepth(format);
            int bitrate = format != null && format.bitrate > 0 ? format.bitrate : ui.playback.sourceBitrate;
            int channels = format == null ? 0 : format.channelCount;
            String codec = audioCodec(format);

            boolean isDsd = false;
            String badgeText;
            boolean isHiRes = false;

            if (current.isLocal() || current.isWebdav() || current.isSmb()) {
                String filename = current.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
                if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
                    isDsd = true;
                    isHiRes = true;
                    if (rate >= 11289600) badgeText = "DSD256";
                    else if (rate >= 5644800) badgeText = "DSD128";
                    else if (rate >= 2822400) badgeText = "DSD64";
                    else badgeText = "DSD";
                } else if (rate >= 96000 || depth >= 24) {
                    isHiRes = true;
                    badgeText = "Hi-Res " + (rate > 0 ? (rate / 1000) + "k" : "24b");
                } else if (depth == 16) {
                    badgeText = "LOSSLESS";
                } else {
                    badgeText = codec.equals("未知") ? "FLAC" : codec;
                }
            } else if (current.isNetease()) {
                String level = ui.playback.neteaseLevel;
                if (rate > 48000 || depth > 16 || "hires".equalsIgnoreCase(level) || "jymaster".equalsIgnoreCase(level)) {
                    isHiRes = true;
                    badgeText = "超清母带";
                } else if ("sky".equalsIgnoreCase(level) || "jyeffect".equalsIgnoreCase(level)) {
                    isHiRes = true;
                    badgeText = "沉浸声";
                } else if ("lossless".equalsIgnoreCase(level) || depth == 16) {
                    badgeText = "无损品质";
                } else {
                    badgeText = "极高品质";
                }
            } else { // TIDAL
                String act = ui.playback.actualQuality;
                if (rate > 48000 || depth > 16 || "HI_RES_LOSSLESS".equalsIgnoreCase(act) || "HI_RES".equalsIgnoreCase(act)) {
                    isHiRes = true;
                    badgeText = "Hi-Res MAX";
                } else if ("MQA".equalsIgnoreCase(act)) {
                    isHiRes = true;
                    badgeText = "MQA";
                } else if ("LOSSLESS".equalsIgnoreCase(act) || depth == 16) {
                    badgeText = "LOSSLESS";
                } else {
                    badgeText = "HIGH";
                }
            }

            String sourceName = current.isLocal() ? (isDsd ? "本地 DSD" : "本地音频")
                    : current.isWebdav() ? "WebDAV"
                    : current.isSmb() ? "SMB"
                    : current.isNetease() ? "网易云母带" : (isHiRes ? "TIDAL MAX" : "TIDAL");
            if (ui.playerHeading != null) {
                ui.playerHeading.setText(sourceName + (qTotal > 0 ? " · " + String.format(Locale.ROOT, "%02d / %02d", qIndex + 1, qTotal) : ""));
                ui.playerHeading.setTextColor(GOLD);
            }
            if (ui.playerSubHeading != null) {
                String year = current.payload.optString("year", "");
                String albumText = current.album;
                if (!year.isEmpty()) albumText += " (" + year + ")";
                int disc = current.payload.optInt("disc_number", 0);
                if (disc > 0) albumText += " · Disc " + disc;
                ui.playerSubHeading.setText(albumText);
                ui.playerSubHeading.setVisibility(albumText.isEmpty() ? View.GONE : View.VISIBLE);
            }

            // 2. Next track glance
            if (ui.playerNextTrack != null) {
                TidalClient.Track next = (qIndex >= 0 && qIndex + 1 < ui.playback.queue.size())
                        ? ui.playback.queue.get(qIndex + 1) : null;
                if (next != null) {
                    ui.playerNextTrack.setText("下一首: " + next.title + " · " + next.artist);
                    ui.playerNextTrack.setVisibility(View.VISIBLE);
                } else {
                    ui.playerNextTrack.setText("");
                    ui.playerNextTrack.setVisibility(View.GONE);
                }
            }

            if (ui.playerSpecTag != null) {
                ui.playerSpecTag.setText(badgeText);
                ui.playerSpecTag.setTextColor(isHiRes ? GOLD : INK);
                ui.playerSpecTag.setBackground(ui.shapeWithStroke(isHiRes ? 0x22d4a359 : 0x18ffffff, isHiRes ? GOLD : 0x33ffffff, 1, 4));
            }

            if (ui.playerSpecFormat != null) {
                String formatStr;
                if (isDsd) {
                    formatStr = "DSD · 1-bit / " + (rate > 0 ? String.format(Locale.ROOT, "%.2f MHz", rate / 1000000.0) : "5.64 MHz");
                } else {
                    formatStr = (codec.equals("未知") ? "FLAC" : codec)
                            + (depth > 0 ? " · " + depth + "-bit" : "")
                            + (rate > 0 ? " / " + audioRate(rate) : "");
                }
                if (bitrate > 0) {
                    formatStr += " · " + (bitrate / 1000) + " kbps";
                } else if (isDsd) {
                    formatStr += " · 5645 kbps";
                }
                ui.playerSpecFormat.setText(formatStr);
            }

            if (ui.playerSpecBitrate != null) {
                ui.playerSpecBitrate.setText(bitrate > 0 ? (bitrate / 1000) + " kbps" : "");
            }

            if (ui.playerSpecEngine != null) {
                String engine = (ui.playback.outputConfig != null && ui.playback.outputConfig.offload)
                        ? "硬件直通 · Bit-Perfect"
                        : (channels == 2 ? "2.0 声道 · 立体声" : channels > 0 ? channels + " 声道" : "Direct PCM");
                ui.playerSpecEngine.setText(engine);
            }

            if (ui.playerSpecPort != null) {
                String dev = audioDevice();
                if (dev.equals("未知")) dev = "Shanling DAC";
                ui.playerSpecPort.setText(dev + " ▾");
            }

            if (ui.playerQueueBtn != null) {
                ui.playerQueueBtn.setText("队列 (" + qTotal + ")");
            }
        }

        ui.playerLyrics.setVisibility(current == null ? View.GONE : View.VISIBLE);
        ui.playerComments.setVisibility(View.GONE);
        ui.favorite.setVisibility(View.VISIBLE);
        ui.favorite.setEnabled(current != null && !ui.favoriteBusy);
        boolean liked = current != null && (current.isLocal() || current.isNetease()
                ? ui.playback.localLibrary.liked(current.id) : ui.favoriteIds.contains(current.id));
        ui.favorite.setImageDrawable(new PlayerIcon(liked ? "heart-filled" : "heart", liked ? 0xffff7a90 : MUTED, ui.dp(18)));
        ui.favorite.setContentDescription(liked ? "取消收藏" : "收藏歌曲");
        playIcon(ui.play, playing);
        ui.shuffle.setImageDrawable(new PlayerIcon("shuffle", ui.playback.shuffle ? ACCENT : MUTED, ui.dp(15)));
        ui.shuffle.setContentDescription(ui.playback.shuffle ? "关闭随机播放" : "打开随机播放");
        ui.repeat.setImageDrawable(new PlayerIcon(ui.playback.repeat == Player.REPEAT_MODE_ONE ? "repeat-one" : "repeat",
                ui.playback.repeat == Player.REPEAT_MODE_OFF ? MUTED : ACCENT, ui.dp(15)));
        ui.repeat.setContentDescription(ui.playback.repeat == Player.REPEAT_MODE_ONE ? "单曲循环"
                : ui.playback.repeat == Player.REPEAT_MODE_ALL ? "队列循环" : "关闭循环");
        ui.message.setText(!ui.playback.error.isEmpty() ? ui.playback.error : ui.playback.loading ? "正在获取音频流…"
                : ui.playback.player.getPlaybackState() == Player.STATE_BUFFERING ? "正在缓冲…" : "");
        updatePosition();
    }

    void applyMiniVisibility() {
        if (ui.mini != null) ui.mini.setVisibility(ui.keyboardHidden
                && (ui.remoteScreens.remoteMode() || ui.playback != null && !ui.playback.queue.isEmpty()) ? View.VISIBLE : View.GONE);
    }

    void playIcon(android.widget.ImageButton button, boolean playing) {
        int color = button == ui.play ? BG : INK;
        int size = 18;
        button.setImageDrawable(new PlayerIcon(playing ? "pause" : "play", color, ui.dp(size)));
        button.setContentDescription(playing ? "暂停" : "播放");
    }

    void updatePosition() {
        if (ui.remoteScreens.remoteMode()) {
            RemoteDevices.State state = ui.devices.state;
            long duration = state == null ? 0 : state.durationMs;
            long elapsed = state == null ? 0 : state.position();
            setMiniProgress(elapsed, duration);
            if (ui.seek == null) return;
            ui.seek.setEnabled(state != null && state.seekable && duration > 0);
            if (!ui.dragging) ui.seek.setProgress(duration == 0 ? 0 : (int) (elapsed * 1000 / duration));
            RemoteDevices.Track playing = state == null ? null : state.current();
            if (ui.lyrics != null) ui.lyrics.sync(playing == null ? Long.MIN_VALUE : playing.id, elapsed);
            String elapsedText = UiKit.formatTime(elapsed);
            String totalText = UiKit.formatTime(duration);
            if (!TextUtils.equals(time.getText(), elapsedText)) time.setText(elapsedText);
            if (!TextUtils.equals(ui.total.getText(), totalText)) ui.total.setText(totalText);
            return;
        }
        if (ui.playback == null) return;
        long duration = ui.playback.loading ? 0 : Math.max(0, ui.playback.player.getDuration());
        long elapsed = ui.playback.loading ? 0 : ui.playback.player.getCurrentPosition();
        setMiniProgress(elapsed, duration);
        if (ui.lyrics != null && ui.playback.current() != null) {
            ui.lyrics.sync(ui.playback.current().id, ui.playback.loading ? 0 : elapsed);
        }
        if (ui.seek == null) return;
        ui.seek.setEnabled(duration > 0 && ui.playback.player.isCurrentMediaItemSeekable());
        if (!ui.dragging) {
            ui.seek.setProgress(duration == 0 ? 0 : (int) (elapsed * 1000 / duration));
        }
        String elapsedText = UiKit.formatTime(elapsed);
        String totalText = UiKit.formatTime(duration);
        if (!TextUtils.equals(time.getText(), elapsedText)) time.setText(elapsedText);
        if (!TextUtils.equals(ui.total.getText(), totalText)) ui.total.setText(totalText);
    }

    void setMiniProgress(long elapsed, long duration) {
        if (ui.miniProgress == null || ui.miniTrack == null || ui.miniTrack.getWidth() <= 0) return;
        int width = ui.miniTrack.getWidth();
        int fill = duration <= 0 ? 0 : (int) Math.min(width, width * (elapsed / (double) duration));
        ViewGroup.LayoutParams layout = ui.miniProgress.getLayoutParams();
        if (layout.width == fill) return;
        layout.width = fill;
        ui.miniProgress.setLayoutParams(layout);
    }

    /** Bottom sheet queue drawer that pops up over the player without navigating away. */
    void showQueueSheet() {
        if (ui.playback == null || ui.playback.queue.isEmpty()) {
            ui.toast("播放队列为空");
            return;
        }
        android.app.Dialog dialog = new android.app.Dialog(ui, android.R.style.Theme_Translucent_NoTitleBar);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        LinearLayout sheet = ui.column();
        sheet.setBackground(ui.shapeWithStroke(0xf012171f, 0x1affffff, 1, 16));
        sheet.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16));

        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, ui.dp(8));
        TextView title = ui.text("播放队列 (" + ui.playback.queue.size() + ")", 14, INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button clearBtn = ui.action("清空", "清空", () -> {
            TidalClient.Track cur = ui.playback.current();
            if (cur != null) {
                ui.playback.replaceQueue(java.util.Collections.singletonList(cur), 0, false);
            }
            dialog.dismiss();
            updatePlayer();
        });
        clearBtn.setTextColor(MUTED);
        clearBtn.setTextSize(12);
        header.addView(clearBtn);
        sheet.addView(header);

        ScrollView scroll = new ScrollView(ui);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = ui.column();
        int currentIdx = ui.playback.index;
        View playingRow = null;
        for (int i = 0; i < ui.playback.queue.size(); i++) {
            int idx = i;
            TidalClient.Track track = ui.playback.queue.get(i);
            boolean isCurrent = i == currentIdx;

            LinearLayout item = ui.row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
            if (isCurrent) item.setBackground(ui.shapeWithStroke(0x22d4a359, 0x33d4a359, 1, 8));

            TextView num = ui.text(isCurrent ? "▶" : String.format(Locale.ROOT, "%02d", i + 1), 11, isCurrent ? GOLD : MUTED, isCurrent);
            num.setGravity(Gravity.CENTER);
            item.addView(num, new LinearLayout.LayoutParams(ui.dp(24), -2));

            LinearLayout info = ui.column();
            info.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            info.addView(ui.single(track.title, 13, isCurrent ? GOLD : INK, isCurrent));
            info.addView(ui.single(track.artist, 10, MUTED, false));
            item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            String tag = UiKit.trackTag(track);
            TextView tagView = ui.text(tag, 8, MUTED, false);
            tagView.setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1));
            tagView.setBackground(ui.shape(0x14ffffff, 4));
            item.addView(tagView);

            long dur = DownloadTasks.length(track);
            if (dur > 0) {
                TextView durView = ui.text(UiKit.formatTime(dur * 1000), 10, MUTED, false);
                durView.setPadding(ui.dp(6), 0, 0, 0);
                item.addView(durView);
            }

            item.setOnClickListener(v -> {
                ui.playback.playAt(idx, 0, true);
                dialog.dismiss();
                updatePlayer();
            });
            if (isCurrent) playingRow = item;
            list.addView(item);
        }
        scroll.addView(list);
        int maxHeight = (int) (ui.getResources().getDisplayMetrics().heightPixels * 0.58f);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        dialog.setContentView(sheet);
        if (playingRow != null) {
            View row = playingRow;
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    android.view.ViewTreeObserver observer = scroll.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    int y = row.getTop();
                    if (y > ui.dp(8)) scroll.scrollTo(0, y - ui.dp(8));
                }
            });
        }
        dialog.show();
    }
}
