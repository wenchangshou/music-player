package com.tidalgui.player;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.util.Locale;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** View-building helpers shared by every screen of {@link MainActivity}. */
@UnstableApi
abstract class UiKit extends ComponentActivity {
    ArtworkLoader artwork;
    LinearLayout content;

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String readable(Exception problem) {
        if (problem instanceof java.net.UnknownHostException || problem instanceof java.net.ConnectException
                || problem instanceof java.net.NoRouteToHostException) {
            return "无法连接服务器，请检查网络后重试";
        }
        if (problem instanceof java.net.SocketTimeoutException) return "连接超时，请稍后重试";
        if (problem instanceof javax.net.ssl.SSLException) return "安全连接失败，请检查网络或系统时间";
        return problem instanceof IOException && problem.getMessage() != null ? problem.getMessage()
                : "数据格式无效或网络不可用";
    }

    static final java.util.regex.Pattern AUDIO_EXTENSION = java.util.regex.Pattern.compile(
            "(?i)\\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|dsf|dff|wma)$");

    /** Title for display; untagged local files show their name without the extension. */
    static String displayTitle(TidalClient.Track track) {
        if (!track.isLocal()) return track.title;
        String stripped = AUDIO_EXTENSION.matcher(track.title).replaceFirst("");
        return stripped.isEmpty() ? track.title : stripped;
    }

    /** Borderless icon-only button. */
    android.widget.ImageButton glyph(String icon, int color, int size, String description, Runnable choose) {
        android.widget.ImageButton button = new android.widget.ImageButton(this, null, android.R.attr.borderlessButtonStyle);
        button.setImageDrawable(new PlayerIcon(icon, color, dp(size)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(v -> choose.run());
        return button;
    }

    /** Tonal button: the standard style for secondary actions. */
    Button secondary(String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setBackground(shapeWithStroke(RAISED, 0x1affffff, 1, 12));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    Button withIcon(Button button, String icon, int color) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon, color, dp(14)), null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        return button;
    }

    /** A row of buttons with consistent height and spacing. */
    android.widget.HorizontalScrollView toolbar(Button... buttons) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = row();
        for (Button button : buttons) {
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(-2, dp(32));
            cell.setMarginEnd(dp(6));
            bar.addView(button, cell);
        }
        scroll.addView(bar);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(2);
        layout.bottomMargin = dp(8);
        scroll.setLayoutParams(layout);
        return scroll;
    }

    /** Sleek compact pill button for player bottom bar. */
    Button pillBtn(String label, String icon, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(0xccffffff);
        button.setTextSize(11);
        button.setBackground(shape(0x18ffffff, 8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new PlayerIcon(icon, 0x99ffffff, dp(14)), null, null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    AudioBadge audioSpecBadge(TidalClient.Track track) {
        if (track == null) return null;
        if (track.isLocal() || track.isWebdav() || track.isSmb()) {
            String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
            if (filename.endsWith(".dsf") || filename.endsWith(".dff")) {
                int rate = track.payload.optInt("sample_rate", 0);
                String label = rate >= 11289600 ? "DSD256" : rate >= 5644800 ? "DSD128" : rate >= 2822400 ? "DSD64" : "DSD";
                return new AudioBadge(label, GOLD, 0x22d4a359, GOLD);
            }
            if (filename.endsWith(".flac") || filename.endsWith(".ape") || filename.endsWith(".wav")
                    || track.payload.optBoolean("lossless", false)) {
                int rate = track.payload.optInt("sample_rate", 0);
                int depth = track.payload.optInt("bit_depth", 0);
                if (rate >= 96000 || depth >= 24) {
                    String rStr = rate > 0 ? (rate / 1000) + "k" : "24b";
                    return new AudioBadge("Hi-Res " + rStr, GOLD, 0x22d4a359, GOLD);
                }
                return new AudioBadge("FLAC", INK, 0x18ffffff, 0x33ffffff);
            }
            if (filename.endsWith(".mp3")) return new AudioBadge("MP3", MUTED, 0x10ffffff, 0x20ffffff);
            if (filename.endsWith(".m4a") || filename.endsWith(".aac")) return new AudioBadge("AAC", MUTED, 0x10ffffff, 0x20ffffff);
            return new AudioBadge("无损", INK, 0x18ffffff, 0x33ffffff);
        }
        if (track.isNetease()) {
            String level = track.payload.optString("level", "");
            if ("hires".equalsIgnoreCase(level) || "jymaster".equalsIgnoreCase(level)) {
                return new AudioBadge("超清母带", GOLD, 0x22d4a359, GOLD);
            }
            if ("sky".equalsIgnoreCase(level) || "jyeffect".equalsIgnoreCase(level)) {
                return new AudioBadge("沉浸声", GOLD, 0x22d4a359, GOLD);
            }
            if ("lossless".equalsIgnoreCase(level) || track.payload.optBoolean("lossless", true)) {
                return new AudioBadge("无损", INK, 0x18ffffff, 0x33ffffff);
            }
            return new AudioBadge("极高", MUTED, 0x10ffffff, 0x20ffffff);
        }
        // TIDAL
        String q = track.payload.optString("audioQuality", "");
        if ("HI_RES_LOSSLESS".equalsIgnoreCase(q) || "HI_RES".equalsIgnoreCase(q)) {
            return new AudioBadge("Hi-Res MAX", GOLD, 0x22d4a359, GOLD);
        }
        if ("MQA".equalsIgnoreCase(q)) {
            return new AudioBadge("MQA", GOLD, 0x22d4a359, GOLD);
        }
        if ("LOSSLESS".equalsIgnoreCase(q)) {
            return new AudioBadge("LOSSLESS", INK, 0x18ffffff, 0x33ffffff);
        }
        return new AudioBadge("HIGH", MUTED, 0x10ffffff, 0x20ffffff);
    }

    static String trackTag(TidalClient.Track track) {
        if (track == null) return "";
        if (track.isLocal()) {
            String filename = track.payload.optString("file_name", "").toLowerCase(Locale.ROOT);
            if (filename.endsWith(".flac")) return "FLAC";
            if (filename.endsWith(".dsf") || filename.endsWith(".dff")) return "DSD";
            if (filename.endsWith(".wav")) return "WAV";
            if (filename.endsWith(".ape")) return "APE";
            if (filename.endsWith(".mp3")) return "MP3";
            if (filename.endsWith(".m4a")) return "AAC";
            return track.payload.optBoolean("lossless", false) ? "FLAC" : "本地";
        }
        if (track.isNetease()) return "网易云";
        if (track.isWebdav()) return "WebDAV";
        if (track.isSmb()) return "SMB";
        return "TIDAL";
    }

    /** Icon above a short label, for secondary player actions. */
    Button labeledIcon(String icon, String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(MUTED);
        button.setTextSize(11);
        button.setCompoundDrawablesWithIntrinsicBounds(null, new PlayerIcon(icon, MUTED, dp(18)), null, null);
        button.setCompoundDrawablePadding(dp(3));
        button.setPadding(0, dp(6), 0, dp(4));
        return button;
    }

    /** Full-width list row with a chevron, for settings-style navigation and commands. */
    Button menuRow(String label, String description, Runnable choose) {
        Button button = action(label, description, choose);
        boolean destructive = label.startsWith("退出") || label.startsWith("关闭");
        button.setTextColor(destructive ? 0xffff8f8f : INK);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setBackground(shape(0x14ffffff, 10));
        button.setPadding(dp(12), 0, dp(10), 0);
        if (!destructive) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                    new PlayerIcon("forward", MUTED, dp(14)), null);
        }
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(44));
        layout.bottomMargin = dp(5);
        button.setLayoutParams(layout);
        return button;
    }

    /** Square-ish shortcut tile with an icon over its label. */
    Button tile(String icon, String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(INK);
        button.setTextSize(12);
        button.setBackground(shape(SURFACE, 12));
        button.setCompoundDrawablesWithIntrinsicBounds(null, new PlayerIcon(icon, ACCENT, dp(18)), null, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(0, dp(8), 0, dp(6));
        return button;
    }

    void empty(String heading, String detail) {
        section(heading);
        if (!detail.isEmpty()) content.addView(text(detail, 13, MUTED, false));
    }

    void section(String value) {
        TextView heading = text(value, 16, INK, true);
        heading.setPadding(0, dp(10), 0, dp(6));
        content.addView(heading);
    }

    LinearLayout entry(String title, String detail, String art, Runnable choose) {
        LinearLayout entry = row();
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(6), dp(4), dp(4), dp(4));
        entry.setBackground(shape(SURFACE, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        entry.setLayoutParams(params);
        ImageView image = art(38);
        artwork.load(image, art, 160, title);
        image.setOnClickListener(v -> choose.run());
        entry.addView(image);
        LinearLayout info = column();
        info.setPadding(dp(10), 0, dp(4), 0);
        info.addView(single(title, 14, INK, true));
        info.addView(single(detail, 11, MUTED, false));
        info.setOnClickListener(v -> choose.run());
        entry.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return entry;
    }

    ImageView art(int size) {
        ImageView image = new ImageView(this);
        image.setBackground(shape(RAISED, 8));
        image.setClipToOutline(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (size > 0) image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return image;
    }

    TextView single(String value, int size, int color, boolean strong) {
        TextView label = text(value, size, color, strong);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    TextView text(String value, int size, int color, boolean strong) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (strong) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    Button action(String value, String description, Runnable choose) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(value);
        button.setTextColor(ACCENT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(v -> choose.run());
        return button;
    }

    Button iconButton(String icon, String description, Runnable choose) {
        Button button = action("", description, choose);
        PlayerIcon image = new PlayerIcon(icon, ACCENT, dp(16));
        button.setCompoundDrawablesWithIntrinsicBounds(null, image, null, null);
        button.setPadding(dp(4), dp(4), dp(4), dp(4));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    Button primary(String label, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(BG);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(shape(GOLD, 14));
        return button;
    }

    Button pill(String label, boolean active, Runnable choose) {
        Button button = action(label, label, choose);
        button.setTextColor(active ? GOLD : MUTED);
        button.setTextSize(11);
        if (active) button.setBackground(shapeWithStroke(0x22d4a359, GOLD, 1, 14));
        else button.setBackground(shape(SURFACE, 14));
        button.setPadding(dp(11), 0, dp(11), 0);
        return button;
    }

    LinearLayout panel(String heading, String description) {
        return panel(content, heading, description);
    }

    LinearLayout panel(LinearLayout parent, String heading, String description) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.setBackground(shape(SURFACE, 16));
        box.addView(text(heading, 20, INK, true));
        TextView detail = text(description, 14, MUTED, false);
        detail.setPadding(0, dp(5), 0, dp(12));
        box.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(box, params);
        return box;
    }

    GradientDrawable shape(int color, int corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(corner));
        return background;
    }

    GradientDrawable shapeWithStroke(int color, int strokeColor, int strokeWidthDp, int corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(corner));
        if (strokeWidthDp > 0) background.setStroke(dp(strokeWidthDp), strokeColor);
        return background;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
