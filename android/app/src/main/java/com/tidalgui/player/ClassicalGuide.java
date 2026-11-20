package com.tidalgui.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 古典音乐「聆听导览」：AI 按时间段解析一首作品（引子、主题、发展、再现……），输出为 LRC 歌词格式，
 * 交给歌词视图同步高亮、滚动与点按跳转。时间点由 AI 按常见演奏估算，生成后缓存，离线可看。
 */
final class ClassicalGuide {
    private ClassicalGuide() {}

    static final int MIN_SEGMENTS = 2;
    static final int MAX_SEGMENTS = 14;
    private static final int MAX_TEXT = 48;

    private static final Pattern GENRE = Pattern.compile(
            "古典|歌剧|室内乐|协奏曲|交响|奏鸣曲|巴洛克|浪漫主义|印象主义|新古典|classical|baroque|opera|symphon|concerto|chamber",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "\\b(Op\\.|BWV|K\\.\\s?\\d|KV\\s?\\d|Hob\\.|RV\\s?\\d|D\\.\\s?\\d|B\\.\\s?\\d|S\\.\\s?\\d|Symphon|Concerto|Sonata|Nocturne|"
                    + "Prelude|Fugue|[ÉE]tude|Mazurka|Ballade|Scherzo|Polonaise|Rhapsody|Allegro|Adagio|Andante|Largo|Presto|Requiem)"
                    + "|协奏曲|交响曲|奏鸣曲|夜曲|前奏曲|赋格|练习曲|圆舞曲|幻想曲|安魂曲",
            Pattern.CASE_INSENSITIVE);

    /** Whether a track looks like classical music (genre first, then typical title markers). */
    static boolean isClassical(TidalClient.Track track) {
        if (track == null) return false;
        String genre = track.payload.optString("genre");
        if (!genre.isEmpty()) return GENRE.matcher(genre).find();
        return TITLE.matcher(track.title).find() || TITLE.matcher(track.album).find();
    }

    static String key(TidalClient.Track track) {
        return AiNotes.cacheKey("guide", track.artist, track.album + "|" + track.title);
    }

    static String cached(Context context, TidalClient.Track track) {
        return AiNotes.read(context, key(track));
    }

    static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String prompt(TidalClient.Track track, long seconds) {
        return "你是一位古典音乐导赏人，擅长用通俗而专业的语言陪伴听众逐段聆听。\n"
                + "曲目：" + track.title + "\n演奏者：" + (track.artist.isEmpty() ? "未知" : track.artist)
                + "\n专辑：" + track.album
                + (track.payload.optString("genre").isEmpty() ? "" : "\n流派：" + track.payload.optString("genre"))
                + "\n这条录音时长：" + clock(seconds) + "（" + seconds + " 秒）\n\n"
                + "请按时间顺序把这首作品（若是乐章，则只讲这一乐章）分成 5-12 段聆听导览：\n"
                + "- 依据作品的曲式结构（如引子、呈示部第一/第二主题、发展部、再现部、尾声；或变奏、回旋段落），"
                + "按本录音的总时长估算每段开始时间\n"
                + "- 每段 text 不超过 40 字：告诉听众此刻该听什么（哪件乐器、什么主题、情绪与和声的变化）\n"
                + "- 时间必须从 0:00 开始、严格递增，且小于总时长\n"
                + "- 不确定的细节宁可写得概括，也不要编造具体小节或演奏者轶事\n\n"
                + "仅输出 JSON：{\"work\":\"作品全名（作曲家）\",\"overview\":\"一句话介绍，不超过40字\","
                + "\"segments\":[{\"t\":\"0:00\",\"title\":\"引子\",\"text\":\"……\"}]}";
    }

    /** "1:32", "01:32", "92" or 92 → seconds; -1 if unreadable. */
    static long seconds(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        String raw = String.valueOf(value).trim();
        Matcher m = Pattern.compile("^(\\d{1,3}):(\\d{1,2})(?:[.:]\\d+)?$").matcher(raw);
        if (m.find()) return Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2));
        if (raw.matches("\\d{1,5}")) return Long.parseLong(raw);
        return -1;
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\r\\n\\[\\]]", " ").trim();
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /**
     * The model's JSON as LRC lines; null when unusable. Segments must start in time order inside the
     * recording; out-of-order or out-of-range ones are dropped.
     */
    static String toLrc(String reply, long totalSeconds) {
        JSONObject data = AutoDj.json(reply);
        JSONArray segments = data == null ? null : data.optJSONArray("segments");
        if (segments == null) return null;
        List<String> lines = new ArrayList<>();
        long last = -1;
        for (int i = 0; i < segments.length() && lines.size() < MAX_SEGMENTS; i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            long at = seconds(seg.opt("t"));
            if (at < 0 || at <= last || (totalSeconds > 0 && at >= totalSeconds)) continue;
            String title = clean(seg.optString("title"), 16);
            String text = clean(seg.optString("text"), MAX_TEXT);
            if (title.isEmpty() && text.isEmpty()) continue;
            lines.add(String.format(Locale.ROOT, "[%02d:%02d.00]%s%s", at / 60, at % 60,
                    title.isEmpty() ? "" : "【" + title + "】", text));
            last = at;
        }
        if (lines.size() < MIN_SEGMENTS) return null;
        String work = clean(data.optString("work"), 60);
        String overview = clean(data.optString("overview"), 60);
        StringBuilder lrc = new StringBuilder();
        // Header line at 0:00, listed before the first segment; it stays dim as an introduction.
        lrc.append("[00:00.00]✨ AI 聆听导览").append(work.isEmpty() ? "" : " · " + work)
                .append(overview.isEmpty() ? "" : "\n").append(overview.isEmpty() ? "" : "[00:00.00]" + overview)
                .append("\n[00:00.00]（时间点按常见演奏估算，与本录音可能略有出入）\n");
        for (String line : lines) lrc.append(line).append('\n');
        return lrc.toString();
    }

    /** Generates (blocking) and caches; call off the main thread. */
    static String generate(Context context, TidalClient.Track track, long totalSeconds) throws IOException, JSONException {
        JSONObject request = new JSONObject()
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt(track, totalSeconds))))
                .put("temperature", 0.5).put("max_tokens", 1500);
        String reply = AiProxy.chat(context, request).optString("content");
        String lrc = toLrc(reply, totalSeconds);
        if (lrc == null) throw new IOException("AI 返回的导览格式无效");
        AiNotes.write(context, key(track), lrc);
        return lrc;
    }
}
