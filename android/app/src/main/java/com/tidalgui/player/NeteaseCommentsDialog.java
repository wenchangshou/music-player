package com.tidalgui.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

final class NeteaseCommentsDialog {
    private final Activity activity;
    private final NeteaseClient api;
    private final long trackId;
    private final ExecutorService worker;
    private final Dialog dialog;
    private final TextView summary;
    private final View popularIndicator;
    private final View latestIndicator;
    private final TextView status;
    private final LinearLayout latest;
    private final LinearLayout popular;
    private final ScrollView latestScroll;
    private final ScrollView popularScroll;
    private final Button latestTab;
    private final Button popularTab;
    private final Button more;
    private final Set<Long> displayed = new HashSet<>();
    private final java.text.DateFormat dateFormat;
    private Future<?> pageRequest;
    private Future<?> likesRequest;
    private int offset;
    private long before;
    private boolean showingPopular = true;
    private boolean loaded;
    private boolean loading;
    private boolean failed;
    private boolean hasMore;
    private long total = -1;
    private long likes = -1;

    NeteaseCommentsDialog(Activity activity, NeteaseClient api, TidalClient.Track track,
                          ExecutorService worker) {
        this.activity = activity;
        this.api = api;
        this.trackId = track.id;
        this.worker = worker;
        dateFormat = DateFormat.getMediumDateFormat(activity);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        GradientDrawable surface = rounded(SURFACE, 0);
        float corner = dp(20);
        surface.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        sheet.setBackground(surface);
        sheet.setClipToOutline(true);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(8), dp(8), 0);
        LinearLayout title = column();
        title.addView(text("评论", 19, INK, true));
        String song = track.artist.isEmpty() ? track.title : track.title + " · " + track.artist;
        title.addView(singleLine(song, 12, MUTED, false));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("×", Color.TRANSPARENT);
        close.setTextSize(24);
        close.setContentDescription("关闭评论");
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        sheet.addView(header);

        summary = text("", 12, MUTED, false);
        summary.setPadding(dp(16), dp(5), dp(16), 0);
        sheet.addView(summary);

        LinearLayout tabs = row();
        tabs.setPadding(dp(12), dp(1), dp(12), 0);
        popularTab = button("热门", Color.TRANSPARENT);
        latestTab = button("最新", Color.TRANSPARENT);
        popularTab.setOnClickListener(view -> selectTab(true));
        latestTab.setOnClickListener(view -> selectTab(false));
        popularIndicator = new View(activity);
        latestIndicator = new View(activity);
        tabs.addView(tab(popularTab, popularIndicator), new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(tab(latestTab, latestIndicator), new LinearLayout.LayoutParams(0, -2, 1));
        sheet.addView(tabs);
        sheet.addView(divider());

        popular = column();
        latest = column();
        popularScroll = scroll(popular);
        latestScroll = scroll(latest);
        status = text("", 13, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(20), dp(10), dp(20), dp(7));
        more = button("加载更多", RAISED);
        more.setOnClickListener(view -> load());
        // Keep the loading and retry controls visible whichever tab is selected.
        sheet.addView(popularScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.addView(latestScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.addView(status);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(-1, dp(46));
        moreParams.setMargins(dp(20), 0, dp(20), dp(12));
        sheet.addView(more, moreParams);
        selectTab(true);
        dialog.setContentView(sheet);
        dialog.setOnDismissListener(ignored -> {
            if (pageRequest != null) pageRequest.cancel(true);
            if (likesRequest != null) likesRequest.cancel(true);
        });
    }

    private void selectTab(boolean hot) {
        showingPopular = hot;
        popularTab.setSelected(hot);
        latestTab.setSelected(!hot);
        popularIndicator.setVisibility(hot ? View.VISIBLE : View.INVISIBLE);
        latestIndicator.setVisibility(hot ? View.INVISIBLE : View.VISIBLE);
        popularTab.setTextColor(hot ? ACCENT : MUTED);
        latestTab.setTextColor(hot ? MUTED : ACCENT);
        popularScroll.setVisibility(hot ? View.VISIBLE : View.GONE);
        latestScroll.setVisibility(hot ? View.GONE : View.VISIBLE);
        updateFooter();
    }

    void show() {
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        int available = dp(activity.getResources().getConfiguration().screenHeightDp);
        window.setLayout(-1, Math.round(available * 0.84f));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.43f);
        updateCounts();
        load();
        likesRequest = worker.submit(() -> {
            long result;
            try {
                result = api.likes(trackId);
            } catch (Exception unavailable) {
                result = -1;
            }
            long count = result;
            activity.runOnUiThread(() -> {
                if (!active()) return;
                likes = count;
                updateCounts();
            });
        });
    }

    private boolean active() {
        return dialog.isShowing() && !activity.isDestroyed();
    }

    private void load() {
        loading = true;
        failed = false;
        updateFooter();
        int start = offset;
        long cursor = offset >= 5000 ? before : 0;
        pageRequest = worker.submit(() -> {
            try {
                JSONObject page = api.comments(trackId, Math.min(start, 5000), cursor);
                activity.runOnUiThread(() -> {
                    if (active()) display(page);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!active()) return;
                    loading = false;
                    failed = true;
                    updateFooter();
                });
            }
        });
    }

    private void display(JSONObject page) {
        total = page.optLong("total", total);
        updateCounts();
        if (!loaded) {
            loaded = true;
            JSONArray hot = page.optJSONArray("hotComments");
            if (hot != null) addComments(popular, hot, new HashSet<>());
        }
        JSONArray entries = page.optJSONArray("comments");
        int count = entries == null ? 0 : entries.length();
        long last = entries == null ? 0 : addComments(latest, entries, displayed);
        boolean advanced = count > 0 && (offset < 5000 || (last > 0 && last < before));
        offset += count;
        if (last > 0) before = last;
        hasMore = page.optBoolean("more") && advanced && (offset < 5000 || before > 0);
        loading = false;
        updateFooter();
    }

    private void updateFooter() {
        String notice = "";
        if (loading) notice = "正在加载评论…";
        else if (failed) notice = "评论加载失败";
        else if (loaded && (showingPopular ? popular : latest).getChildCount() == 0) {
            notice = showingPopular ? "暂无热门评论" : "暂无评论";
        }
        status.setText(notice);
        status.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        more.setText(failed ? "重试" : "加载更多");
        more.setVisibility(!loading && (failed || (!showingPopular && hasMore)) ? View.VISIBLE : View.GONE);
    }

    private long addComments(LinearLayout list, JSONArray entries, Set<Long> seen) {
        long last = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            long id = entry.optLong("commentId");
            long time = entry.optLong("time");
            if (time > 0) last = time;
            if (id > 0 && !seen.add(id)) continue;
            JSONObject user = entry.optJSONObject("user");
            String nickname = user == null || user.isNull("nickname") ? "网易云用户" : user.optString("nickname");
            LinearLayout card = column();
            card.setPadding(dp(16), dp(10), dp(16), dp(9));

            LinearLayout meta = row();
            LinearLayout author = column();
            author.addView(singleLine(nickname, 13, INK, true));
            if (time > 0) author.addView(text(dateFormat.format(new Date(time)), 11, MUTED, false));
            meta.addView(author, new LinearLayout.LayoutParams(0, -2, 1));
            long stars = entry.optLong("likedCount", -1);
            if (stars >= 0) {
                TextView votes = text("赞 " + count(stars), 12, MUTED, false);
                votes.setPadding(dp(12), 0, 0, 0);
                meta.addView(votes);
            }
            card.addView(meta);
            if (!entry.isNull("content")) {
                TextView content = text(entry.optString("content"), 14, INK, false);
                content.setPadding(0, dp(4), 0, 0);
                content.setLineSpacing(dp(2), 1f);
                card.addView(content);
                Button toggle = button("展开全文", Color.TRANSPARENT);
                toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                toggle.setTextColor(ACCENT);
                toggle.setVisibility(View.GONE);
                toggle.setOnClickListener(view -> {
                    boolean expand = content.getMaxLines() == 5;
                    content.setMaxLines(expand ? Integer.MAX_VALUE : 5);
                    toggle.setText(expand ? "收起" : "展开全文");
                });
                card.addView(toggle, new LinearLayout.LayoutParams(dp(88), dp(40)));
                content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                         int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (right <= left || content.getLayout() == null) return;
                        content.removeOnLayoutChangeListener(this);
                        content.post(() -> {
                            if (!active() || content.getLineCount() <= 5) return;
                            content.setMaxLines(5);
                            content.setEllipsize(TextUtils.TruncateAt.END);
                            toggle.setVisibility(View.VISIBLE);
                        });
                    }
                });
            }
            list.addView(card);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
            line.setMargins(dp(16), 0, dp(16), 0);
            list.addView(divider(), line);
        }
        return last;
    }

    private void updateCounts() {
        summary.setText("评论 " + count(total) + "  ·  红心 " + count(likes));
    }

    private LinearLayout tab(Button label, View indicator) {
        LinearLayout tab = column();
        tab.addView(label, new LinearLayout.LayoutParams(-1, dp(44)));
        indicator.setBackground(rounded(ACCENT, 1));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(22), dp(2));
        line.gravity = Gravity.CENTER_HORIZONTAL;
        tab.addView(indicator, line);
        return tab;
    }

    private ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private View divider() {
        View line = new View(activity);
        line.setBackgroundColor(RAISED);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return line;
    }

    private Button button(String label, int color) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(INK);
        styleButton(button, color);
        return button;
    }

    private void styleButton(Button button, int color) {
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28aaf0d8),
                rounded(color, 12), rounded(Color.WHITE, 12)));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView singleLine(String value, int size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private static String count(long count) {
        return count < 0 ? "—" : NumberFormat.getIntegerInstance().format(count);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
