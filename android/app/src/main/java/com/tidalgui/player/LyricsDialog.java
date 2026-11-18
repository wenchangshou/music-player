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

final class LyricsDialog {
    interface Seek { void at(long millis); }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,8})]");
    private static final int ACTIVE = 0xffaaf0d8;
    private static final int INACTIVE = 0xffa6b9bd;
    private final AlertDialog dialog;
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
        trackId = track.id;
        this.seek = seek;
        scroll = new ScrollView(context);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(12));
        scroll.setMinimumHeight(dp(170));
        scroll.addView(body);
        dialog = new AlertDialog.Builder(context).setTitle(track.title).setView(scroll)
                .setPositiveButton("关闭", null).create();
        body.addView(text("正在加载歌词…"));
    }

    void show(Runnable dismissed) {
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
        dialog.dismiss();
    }

    void unavailable(String reason) {
        body.removeAllViews();
        body.addView(text(reason));
    }

    void sync(long currentTrack, long position) {
        if (currentTrack != trackId) {
            dialog.dismiss();
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
        view.setTextSize(17);
        view.setTextColor(INACTIVE);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private int dp(int size) {
        return Math.round(size * body.getResources().getDisplayMetrics().density);
    }
}
