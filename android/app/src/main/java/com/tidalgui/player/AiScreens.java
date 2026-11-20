package com.tidalgui.player;

import android.app.AlertDialog;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: AI 音乐助理的设置、唱片手札与专辑鉴赏。 */
@UnstableApi
final class AiScreens {
    private static final String[][] PRESETS = {
            {"火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "deepseek-v4-1-flash-261017"},
            {"DeepSeek", "https://api.deepseek.com", "deepseek-chat"},
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
    };

    final MainActivity ui;
    /** Albums being generated now, and ones that failed this session (not retried on every render). */
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    AiScreens(MainActivity ui) {
        this.ui = ui;
    }

    // --- 设置 ---

    /** Settings page panel. */
    void settingsPanel() {
        boolean configured = AiProxy.configured(ui);
        String model = "";
        try {
            model = AiProxy.status(ui).optString("model");
        } catch (org.json.JSONException ignored) {}
        LinearLayout panel = ui.panel("AI 音乐助理", configured ? "已启用 · 模型 " + model
                : "填写大模型服务后，启用唱片手札、专辑鉴赏和 AI 智能续播。密钥只保存在本机。");
        panel.addView(ui.menuRow(configured ? "AI 服务设置" : "配置 AI 服务", "AI 服务设置", this::editSettings));
        if (ui.playback != null) {
            boolean on = ui.playback.autoDj;
            panel.addView(ui.menuRow("智能续播 · " + (on ? "已开启" : "已关闭"),
                    "队列快播完时自动续上同频曲目", () -> {
                        ui.playback.setAutoDj(!on);
                        ui.toast(!on ? "已开启智能续播" : "已关闭智能续播");
                        ui.render();
                    }));
        }
    }

    private EditText field(LinearLayout body, String label, String hint, String value, int type) {
        TextView title = ui.text(label, 12, MUTED, false);
        title.setPadding(0, ui.dp(8), 0, 0);
        body.addView(title);
        EditText input = new EditText(ui);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(type);
        input.setText(value);
        body.addView(input);
        return input;
    }

    void editSettings() {
        JSONObject status;
        try {
            status = AiProxy.status(ui);
        } catch (org.json.JSONException impossible) {
            return;
        }
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));
        body.addView(ui.text("支持任意 OpenAI 兼容服务。密钥只保存在本机，网页遥控端也共用这份设置。", 12, MUTED, false));
        EditText endpoint = field(body, "接口地址 (Base URL)", "https://…",
                status.optString("endpoint"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field(body, "模型名称", "如 deepseek-chat", status.optString("model"), InputType.TYPE_CLASS_TEXT);
        EditText key = field(body, "API Key", status.optBoolean("hasKey") ? "已保存（留空保持不变）" : "sk-…", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String[] provider = {status.optString("provider", "custom")};
        LinearLayout presets = ui.row();
        presets.setPadding(0, ui.dp(8), 0, 0);
        for (String[] preset : PRESETS) {
            Button chip = ui.action(preset[0], "使用 " + preset[0] + " 预设", () -> {
                endpoint.setText(preset[1]);
                model.setText(preset[2]);
                provider[0] = preset[0];
            });
            presets.addView(chip);
        }
        body.addView(presets, 1);
        TextView result = ui.text("", 12, MUTED, false);
        result.setPadding(0, ui.dp(8), 0, 0);
        body.addView(result);
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);

        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("AI 音乐助理")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("测试并保存", null).show();
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(view -> {
            JSONObject input = new JSONObject();
            try {
                input.put("provider", provider[0]).put("endpoint", endpoint.getText().toString().trim())
                        .put("model", model.getText().toString().trim()).put("apiKey", key.getText().toString().trim());
                AiProxy.save(ui, input);
            } catch (Exception problem) {
                result.setTextColor(0xffff6b6b);
                result.setText(problem.getMessage());
                return;
            }
            save.setEnabled(false);
            result.setTextColor(MUTED);
            result.setText("已保存，正在测试连接…");
            long start = System.currentTimeMillis();
            ui.worker.execute(() -> {
                String outcome;
                boolean ok;
                try {
                    JSONObject request = new JSONObject().put("messages", new JSONArray().put(new JSONObject()
                            .put("role", "user").put("content", "请用不超过10个字回答：连接成功"))).put("max_tokens", 30);
                    String reply = AiProxy.chat(ui, request).optString("content");
                    outcome = "✓ 连接成功（" + (System.currentTimeMillis() - start) + " ms）：" + reply;
                    ok = true;
                } catch (Exception problem) {
                    outcome = "✕ " + problem.getMessage();
                    ok = false;
                }
                String text = outcome;
                boolean success = ok;
                ui.handler.post(() -> {
                    if (ui.isDestroyed()) return;
                    save.setEnabled(true);
                    result.setTextColor(success ? ACCENT : 0xffff6b6b);
                    result.setText(text);
                    failed.clear();
                    if (ui.page == Page.SETTINGS) ui.render();
                });
            });
        });
    }

    // --- 唱片手札 ---

    void showNotes() {
        TidalClient.Track track = ui.playback == null ? null : ui.playback.current();
        if (track == null) {
            ui.toast("先播放一首歌，再打开唱片手札");
            return;
        }
        String cached = AiNotes.cachedNotes(ui, track);
        if (cached == null && !AiProxy.configured(ui)) {
            new AlertDialog.Builder(ui).setTitle("唱片手札")
                    .setMessage("唱片手札由 AI 生成，需要先配置 AI 服务。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去配置", (d, w) -> editSettings()).show();
            return;
        }
        if (cached == null && !AiProxy.reachable(ui)) {
            new AlertDialog.Builder(ui).setTitle("唱片手札 · " + albumOf(track))
                    .setMessage("当前未联网，联网后可生成这张唱片的手札。已生成过的手札离线也能随时查看。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        TextView body = ui.text(cached != null ? cached : "AI 正在研读《" + albumOf(track) + "》的录音与背景…", 14,
                cached != null ? INK : MUTED, false);
        body.setLineSpacing(ui.dp(4), 1f);
        body.setTextIsSelectable(true);
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui)
                .setTitle("唱片手札 · " + albumOf(track))
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("重新生成", null)
                .show();
        Button regenerate = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        regenerate.setEnabled(AiProxy.available(ui));
        regenerate.setOnClickListener(view -> generateNotes(track, body, regenerate));
        if (cached == null) generateNotes(track, body, regenerate);
    }

    private void generateNotes(TidalClient.Track track, TextView body, Button regenerate) {
        String key = AiNotes.notesKey(track);
        if (!inFlight.add(key)) return;
        regenerate.setEnabled(false);
        body.setTextColor(MUTED);
        body.setText("AI 正在研读《" + albumOf(track) + "》的录音与背景…");
        ui.worker.execute(() -> {
            String text;
            boolean ok;
            try {
                text = AiNotes.notes(ui, track);
                ok = true;
            } catch (Exception problem) {
                text = "生成失败：" + problem.getMessage();
                ok = false;
            }
            String shown = text;
            boolean success = ok;
            ui.handler.post(() -> {
                inFlight.remove(key);
                if (ui.isDestroyed() || !body.isAttachedToWindow()) return;
                body.setTextColor(success ? INK : 0xffff6b6b);
                body.setText(shown);
                regenerate.setEnabled(true);
            });
        });
    }

    private static String albumOf(TidalClient.Track track) {
        return track.album.isEmpty() ? track.title : track.album;
    }

    // --- 专辑 AI 鉴赏卡片 ---

    /**
     * Adds the album's AI card to the current page: cached cards appear at once; otherwise, when AI
     * is configured, a placeholder fills in once generated. Nothing is shown when AI is unavailable.
     */
    void albumInsight(String artist, String album, List<TidalClient.Track> tracks) {
        if (album == null || album.trim().isEmpty()) return;
        JSONObject cached = AiNotes.cachedInsight(ui, artist, album);
        String key = AiNotes.insightKey(artist, album);
        // 离线或未配置时不显示任何占位，浏览不受影响；缓存过的卡片离线照常显示。
        if (cached == null && (!AiProxy.available(ui) || failed.contains(key))) return;
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.shape(SURFACE, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(8);
        params.bottomMargin = ui.dp(4);
        ui.content.addView(card, params);
        if (cached != null) {
            fillInsight(card, cached, tracks);
            return;
        }
        card.addView(ui.text("✨ AI 正在研读这张唱片的录音与编曲…", 13, MUTED, false));
        if (!inFlight.add(key)) return;
        List<String> titles = new ArrayList<>();
        for (TidalClient.Track track : tracks) titles.add(track.title);
        ui.worker.execute(() -> {
            JSONObject data = null;
            boolean networkDown = false;
            try {
                data = AiNotes.insight(ui, artist, album, titles);
            } catch (Exception problem) {
                networkDown = !AiProxy.reachable(ui);
                android.util.Log.i("AiScreens", "album insight unavailable: " + problem.getMessage());
            }
            JSONObject result = data;
            // A network failure is retried once back online; other failures not again this session.
            boolean retryLater = networkDown;
            ui.handler.post(() -> {
                inFlight.remove(key);
                if (result == null && !retryLater) failed.add(key);
                if (ui.isDestroyed() || !card.isAttachedToWindow()) return;
                if (result == null) card.setVisibility(View.GONE);
                else fillInsight(card, result, tracks);
            });
        });
    }

    private void fillInsight(LinearLayout card, JSONObject data, List<TidalClient.Track> tracks) {
        card.removeAllViews();
        String tag = data.optString("acoustic_highlight", data.optString("genre"));
        TextView heading = ui.text("✨ AI 鉴赏" + (tag.isEmpty() ? "" : " · " + tag), 13, GOLD, true);
        card.addView(heading);
        TextView review = ui.text(data.optString("review"), 13, INK, false);
        review.setLineSpacing(ui.dp(3), 1f);
        review.setPadding(0, ui.dp(6), 0, 0);
        review.setMaxLines(4);
        review.setEllipsize(TextUtils.TruncateAt.END);
        review.setOnClickListener(view -> {
            boolean collapsed = review.getMaxLines() == 4;
            review.setMaxLines(collapsed ? Integer.MAX_VALUE : 4);
        });
        card.addView(review);
        String gear = data.optString("gear_tip");
        if (!gear.isEmpty()) {
            TextView tip = ui.text("🎧 " + gear, 12, MUTED, false);
            tip.setPadding(0, ui.dp(6), 0, 0);
            card.addView(tip);
        }
        JSONArray keyTracks = data.optJSONArray("key_tracks");
        if (keyTracks == null || keyTracks.length() == 0) return;
        LinearLayout chips = ui.row();
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, ui.dp(6), 0, 0);
        chips.addView(ui.text("必听：", 12, MUTED, false));
        for (int i = 0; i < keyTracks.length() && i < 3; i++) {
            String name = keyTracks.optString(i);
            int index = matchTrack(tracks, name);
            if (index < 0) continue;
            chips.addView(ui.action("▶ " + tracks.get(index).title, "播放 " + name,
                    () -> ui.catalogueScreens.playList(tracks, index, false)));
        }
        if (chips.getChildCount() > 1) {
            android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(ui);
            strip.setHorizontalScrollBarEnabled(false);
            strip.addView(chips);
            card.addView(strip);
        }
    }

    static int matchTrack(List<TidalClient.Track> tracks, String name) {
        String wanted = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            String title = tracks.get(i).title.toLowerCase(Locale.ROOT);
            if (title.equals(wanted)) return i;
        }
        for (int i = 0; i < tracks.size(); i++) {
            String title = tracks.get(i).title.toLowerCase(Locale.ROOT);
            if (title.length() >= 2 && (title.contains(wanted) || wanted.contains(title))) return i;
        }
        return -1;
    }
}
