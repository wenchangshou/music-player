package com.tidalgui.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * 唱片手札与专辑 AI 鉴赏：调用 {@link AiProxy} 生成，按「艺人 + 专辑」缓存在手机私有目录，
 * 同一张专辑只生成一次，App 各处共用。
 */
final class AiNotes {
    private AiNotes() {}

    static String cacheKey(String kind, String artist, String album) {
        String raw = kind + "|" + norm(artist) + "|" + norm(album);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return kind + "-" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static File file(Context context, String key) {
        File dir = new File(context.getFilesDir(), "ai");
        if (!dir.isDirectory() && !dir.mkdirs()) return new File(context.getFilesDir(), key + ".txt");
        return new File(dir, key + ".txt");
    }

    static String read(Context context, String key) {
        File f = file(context, key);
        if (!f.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    static void write(Context context, String key, String value) {
        File f = file(context, key);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static String chat(Context context, String prompt, double temperature, int maxTokens)
            throws IOException, JSONException {
        JSONObject request = new JSONObject()
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", temperature).put("max_tokens", maxTokens);
        return AiProxy.chat(context, request).optString("content").trim();
    }

    // --- 唱片手札（纯文本，【】小标题 + · 列表，便于在 App 里直接显示）---

    static String notesKey(TidalClient.Track track) {
        return cacheKey("notes", track.artist, track.album.isEmpty() ? track.title : track.album);
    }

    static String cachedNotes(Context context, TidalClient.Track track) {
        return read(context, notesKey(track));
    }

    static String notesPrompt(TidalClient.Track track) {
        String album = track.album.isEmpty() ? track.title : track.album;
        return "你是一位精通高保真音响工程、母带制作与唱片艺术的音乐鉴赏家。\n"
                + "请为专辑《" + album + "》（艺人：" + (track.artist.isEmpty() ? "群星" : track.artist)
                + "，当前在听：" + track.title + "）写一份唱片手札，分三部分：\n"
                + "【唱片档案】时代与流派渊源、录音与母带制作看点\n"
                + "【听音指南】声场、三频结像、动态与器乐分离度\n"
                + "【亮点曲目】2-3 首值得细听的曲目与聆听要点\n"
                + "用纯文本输出：每部分以【】小标题开头，要点以「· 」开头，不要使用 Markdown 符号（# * 等）。"
                + "行文沉稳专业，总长度 400-600 字。若对这张专辑不了解，请如实说明并只写能确定的内容。";
    }

    /** Generates (blocking) and caches; call off the main thread. */
    static String notes(Context context, TidalClient.Track track) throws IOException, JSONException {
        String text = chat(context, notesPrompt(track), 0.65, 1500)
                .replaceAll("(?m)^#+\\s*", "").replace("**", "");
        if (text.isEmpty()) throw new IOException("AI 返回内容为空");
        write(context, notesKey(track), text);
        return text;
    }

    // --- 专辑 AI 鉴赏（JSON：流派、声学亮点、点评、器材建议、必听曲目）---

    static String insightKey(String artist, String album) {
        return cacheKey("insight", artist, album);
    }

    static JSONObject cachedInsight(Context context, String artist, String album) {
        String raw = read(context, insightKey(artist, album));
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException error) {
            return null;
        }
    }

    static String insightPrompt(String artist, String album, List<String> titles) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < titles.size() && i < 12; i++) {
            if (i > 0) names.append("、");
            names.append(titles.get(i));
        }
        return "你是一位资深发烧唱片策展人与母带声学工程师。请为专辑《" + album + "》（艺人："
                + (artist.isEmpty() ? "未知" : artist) + (names.length() > 0 ? "，收录曲目：" + names : "")
                + "）写一段精炼的鉴赏导读。仅输出 JSON：\n"
                + "{\"genre\":\"细分流派\",\"acoustic_highlight\":\"声学亮点标签（10 字内）\","
                + "\"review\":\"80-140 字点评：录音质感、声场、器乐层次与情感\","
                + "\"gear_tip\":\"20-35 字回放器材建议\","
                + "\"key_tracks\":[\"必须是上面收录曲目中的真实曲名\"]}\n"
                + "若对这张专辑不了解，review 中如实说明，不要编造。";
    }

    static JSONObject parseInsight(String reply) {
        JSONObject data = AutoDj.json(reply);
        if (data == null || data.optString("review").trim().isEmpty()) return null;
        return data;
    }

    /** Generates (blocking) and caches; call off the main thread. */
    static JSONObject insight(Context context, String artist, String album, List<String> titles)
            throws IOException, JSONException {
        JSONObject data = parseInsight(chat(context, insightPrompt(artist, album, titles), 0.6, 900));
        if (data == null) throw new IOException("AI 返回格式无效");
        write(context, insightKey(artist, album), data.toString());
        return data;
    }
}
