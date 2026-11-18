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
    private static final int ACTIVE = 0xffaaf0d8;
    private static final int INACTIVE = 0xffa6b9bd;
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
            body.setPadding(dp(12), dp(16), dp(12), dp(16));
            body.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            scroll.setContentDescription("歌词，轻点返回封面");
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
                if (dialog == null) {
                    view.setOnClickListener(ignored -> tap.run());
                    view.setOnLongClickListener(ignored -> {
                        seek.at(line.at);
                        return true;
                    });
                } else {
                    view.setOnClickListener(ignored -> seek.at(line.at));
                }
                body.addView(view);
            }
        }
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    void unavailable(String reason) {
        body.removeAllViews();
        body.addView(text(reason));
    }

    void sync(long currentTrack, long position) {
        if (currentTrack != trackId) {
            if (dialog != null) dialog.dismiss();
            else if (changed != null) changed.run();
            return;
        }
        int next = -1;
        while (next + 1 < lines.size() && lines.get(next + 1).at <= position) next++;
        if (next == active) return;
        if (active >= 0) ((TextView) body.getChildAt(active)).setTextColor(INACTIVE);
        active = next;
        if (active >= 0) {
            View current = body.getChildAt(active);
            ((TextView) current).setTextColor(ACTIVE);
            scroll.smoothScrollTo(0, Math.max(0, current.getTop() - scroll.getHeight() / 3));
        }
    }

    private TextView text(String value) {
        TextView view = new TextView(body.getContext());
        view.setText(value);
        if (dialog == null) {
            view.setGravity(android.view.Gravity.CENTER);
            if (tap != null) view.setOnClickListener(ignored -> tap.run());
        }
        view.setTextSize(17);
        view.setTextColor(INACTIVE);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private int dp(int size) {
        return Math.round(size * body.getResources().getDisplayMetrics().density);
    }
}
