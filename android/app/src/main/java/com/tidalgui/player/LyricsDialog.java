package com.tidalgui.player;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics that follow playback, either as a dialog or inline in place of the cover on Now Playing.
 * Inline: tapping goes back to the cover, long-pressing a line seeks to it.
 */
final class LyricsDialog {
    interface Seek { void at(long millis); }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,8})]");
    private static final int ACTIVE = 0xffffffff;
    private static final int INACTIVE = 0x55ffffff;
    private final AlertDialog dialog;
    /** Inline only: tap (back to the cover) and the track changing. */
    private final Runnable tap;
    private final Runnable changed;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final long trackId;
    private final Seek seek;
    private final List<Line> lines = new ArrayList<>();
    private int active = -1;

    private static final class Line {
        final long at;
        final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    LyricsDialog(Context context, TidalClient.Track track, Seek seek) {
        this(context, track, seek, false, null, null);
    }

    /** Lyrics shown in place of the cover; add {@link #view()} to the page. */
    static LyricsDialog inline(Context context, TidalClient.Track track, Seek seek, Runnable tap, Runnable changed) {
        return new LyricsDialog(context, track, seek, true, tap, changed);
    }

    private LyricsDialog(Context context, TidalClient.Track track, Seek seek, boolean inline,
            Runnable tap, Runnable changed) {
        trackId = track.id;
        this.seek = seek;
        this.tap = tap;
        this.changed = changed;
        scroll = new ScrollView(context);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.setMinimumHeight(dp(170));
        scroll.addView(body);
        if (inline) {
            body.setPadding(dp(28), dp(120), dp(28), dp(120));
            body.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            scroll.setContentDescription("歌词，轻点跳转，点击空白返回封面");
            scroll.setOnClickListener(ignored -> tap.run());
            body.setOnClickListener(ignored -> tap.run());
            dialog = null;
        } else {
            body.setPadding(dp(24), dp(8), dp(24), dp(12));
            dialog = new AlertDialog.Builder(context).setTitle(track.title).setView(scroll)
                    .setPositiveButton("关闭", null).create();
        }
        body.addView(text("正在加载歌词…"));
    }

    View view() {
        return scroll;
    }

    void show(Runnable dismissed) {
        if (dialog == null) return;
        dialog.setOnDismissListener(ignored -> dismissed.run());
        dialog.show();
    }

    void display(JSONObject data) {
        String subtitles = data.isNull("subtitles") ? "" : data.optString("subtitles");
        lines.clear();
        active = -1;
        Matcher shift = OFFSET.matcher(subtitles);
        long adjustment = shift.find() ? Long.parseLong(shift.group(1)) : 0;
        for (String raw : subtitles.split("\\r?\\n")) {
            String row = raw.trim();
            Matcher matcher = TIME.matcher(row);
            List<Long> timestamps = new ArrayList<>();
            int offset = 0;
            while (matcher.find() && matcher.start() == offset) {
                String fraction = matcher.group(3);
                long millis = Long.parseLong(matcher.group(1)) * 60000
                        + Long.parseLong(matcher.group(2)) * 1000;
                if (fraction != null) millis += Long.parseLong((fraction + "00").substring(0, 3));
                timestamps.add(Math.max(0, millis + adjustment));
                offset = matcher.end();
            }
            String value = row.substring(offset).trim();
            if (!value.isEmpty()) for (long stamp : timestamps) lines.add(new Line(stamp, value));
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.at, b.at));
        body.removeAllViews();
        if (lines.isEmpty()) {
            String plain = data.isNull("lyrics") ? "" : data.optString("lyrics");
            body.addView(text(plain.isEmpty() ? "此歌曲暂无歌词" : plain));
        } else {
            for (Line line : lines) {
                TextView view = text(line.text);
                view.setOnClickListener(ignored -> seek.at(line.at));
                body.addView(view);
            }
        }
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    void unavailable(String reason) {
        unavailable(reason, null);
    }

    void unavailable(String reason, Runnable action) {
        body.removeAllViews();
        body.addView(text(reason));
        if (action != null) {
            android.widget.Button btn = new android.widget.Button(body.getContext(), null, android.R.attr.borderlessButtonStyle);
            btn.setText("在线搜索 / 下载歌词");
            btn.setTextColor(ListeningRoomColors.GOLD);
            btn.setTextSize(12);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x22d4a359);
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1), ListeningRoomColors.GOLD);
            btn.setBackground(bg);
            btn.setPadding(dp(16), dp(4), dp(16), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.topMargin = dp(14);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> action.run());
            body.addView(btn);
        }
    }

    void sync(long currentTrack, long position) {
        if (currentTrack != trackId) {
            if (dialog != null) dialog.dismiss();
            else if (changed != null) changed.run();
            return;
        }
        int next = -1;
        while (next + 1 < lines.size() && lines.get(next + 1).at <= position) next++;
        // Hysteresis: prevent jittering back to previous line due to small polling/clock jitter.
        // Only allow stepping backward if position rewound significantly (> 450ms before active line).
        if (active >= 0 && next < active && active < lines.size()) {
            long activeLineAt = lines.get(active).at;
            if (position >= activeLineAt - 450) {
                return;
            }
        }
        if (next == active) return;
        boolean opening = active < 0;
        if (active >= 0 && active < body.getChildCount()) {
            TextView prev = (TextView) body.getChildAt(active);
            prev.setTextColor(INACTIVE);
            prev.setTextSize(idleSize());
            prev.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        active = next;
        if (active >= 0 && active < body.getChildCount()) {
            TextView current = (TextView) body.getChildAt(active);
            current.setTextColor(ACTIVE);
            current.setTextSize(focusSize());
            current.setTypeface(null, android.graphics.Typeface.BOLD);
            center(current, opening);
        }
    }

    /** Put the current line in the middle. The first placement waits until the lines have height. */
    private void center(TextView current, boolean jump) {
        Runnable place = () -> {
            int y = Math.max(0, current.getTop() - scroll.getHeight() / 2 + current.getHeight() / 2);
            if (jump) scroll.scrollTo(0, y);
            else scroll.smoothScrollTo(0, y);
        };
        if (current.getHeight() > 0 && scroll.getHeight() > 0) {
            place.run();
            return;
        }
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                android.view.ViewTreeObserver observer = scroll.getViewTreeObserver();
                if (!scroll.isAttachedToWindow()) {
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    return;
                }
                if (current.getHeight() == 0 || scroll.getHeight() == 0) return;
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                if (body.indexOfChild(current) == active) place.run();
            }
        });
    }

    private TextView text(String value) {
        TextView view = new TextView(body.getContext());
        view.setText(value);
        if (dialog == null) {
            view.setGravity(android.view.Gravity.CENTER);
        }
        view.setTextSize(idleSize());
        view.setTextColor(INACTIVE);
        view.setPadding(0, dp(8), 0, dp(8));
        if (dialog == null) view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    /** Inline lyrics use a larger current line across the player stage. */
    private int idleSize() {
        return dialog == null ? 16 : 14;
    }

    private int focusSize() {
        return dialog == null ? 24 : 17;
    }

    private int dp(int size) {
        return Math.round(size * body.getResources().getDisplayMetrics().density);
    }
}
