package com.tidalgui.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 智能无尽续播：队列快播完时，从本地曲库挑出同频曲目追加到队尾。
 *
 * <p>先按艺人、流派、年代给整个本地曲库打分，取出约 60 首候选；再让 AI 从候选里挑选并排好顺序，
 * 只会推荐机内真实存在的歌。AI 不可用或失败时，直接按打分结果续播。纯逻辑，便于单元测试。
 */
final class AutoDj {
    static final int BATCH = 6;
    static final int POOL = 60;
    /** 剩余曲目不多于此数时触发。 */
    static final int TRIGGER_REMAINING = 2;
    private static final int TOP = POOL - 15;
    private static final int PER_ALBUM = 3;
    private static final int PER_ARTIST = 2;

    private AutoDj() {}

    static final class Candidate {
        final TidalClient.Track track;
        final double score;

        Candidate(TidalClient.Track track, double score) {
            this.track = track;
            this.score = score;
        }
    }

    static final class Plan {
        final String theme;
        final List<TidalClient.Track> tracks;
        final boolean byAi;

        Plan(String theme, List<TidalClient.Track> tracks, boolean byAi) {
            this.theme = theme;
            this.tracks = tracks;
            this.byAi = byAi;
        }
    }

    static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String genre(TidalClient.Track track) {
        return track.payload.optString("genre").trim();
    }

    /** 流派大类："古典 · 协奏曲" → "古典"。 */
    static String primaryGenre(TidalClient.Track track) {
        String g = genre(track);
        int dot = g.indexOf('·');
        return norm(dot < 0 ? g : g.substring(0, dot));
    }

    static String albumArtist(TidalClient.Track track) {
        String value = track.payload.optString("album_artist").trim();
        return value.isEmpty() ? track.artist : value;
    }

    static int year(TidalClient.Track track) {
        String raw = track.payload.optString("year");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1[89]|20)\\d{2}").matcher(raw);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /** 候选池：高分段每张专辑最多 3 首，再随机补一些远一点的曲目，给 AI 留出拓展空间。 */
    static List<Candidate> pool(List<TidalClient.Track> seeds, Collection<Long> exclude,
                                List<TidalClient.Track> library, Random random) {
        Set<String> artists = new HashSet<>();
        Set<String> albums = new HashSet<>();
        Set<String> genres = new HashSet<>();
        Set<String> primaries = new HashSet<>();
        List<Integer> years = new ArrayList<>();
        for (TidalClient.Track seed : seeds) {
            if (!norm(seed.artist).isEmpty()) artists.add(norm(seed.artist));
            if (!norm(albumArtist(seed)).isEmpty()) artists.add(norm(albumArtist(seed)));
            if (!norm(seed.album).isEmpty()) albums.add(norm(seed.album));
            if (!genre(seed).isEmpty()) genres.add(norm(genre(seed)));
            if (!primaryGenre(seed).isEmpty()) primaries.add(primaryGenre(seed));
            if (year(seed) > 0) years.add(year(seed));
        }
        Set<Long> skip = new HashSet<>(exclude);
        List<Candidate> scored = new ArrayList<>();
        for (TidalClient.Track track : library) {
            if (track == null || track.title.isEmpty() || skip.contains(track.id)) continue;
            double score = random.nextDouble() * 1.5;
            if (artists.contains(norm(track.artist)) || artists.contains(norm(albumArtist(track)))) score += 4;
            if (albums.contains(norm(track.album))) score += 2;
            if (!genre(track).isEmpty() && genres.contains(norm(genre(track)))) score += 3;
            else if (!primaryGenre(track).isEmpty() && primaries.contains(primaryGenre(track))) score += 2;
            int y = year(track);
            if (y > 0) {
                for (int seedYear : years) {
                    if (Math.abs(seedYear - y) <= 5) {
                        score += 1;
                        break;
                    }
                }
            }
            if (track.payload.optBoolean("lossless")) score += 0.5;
            scored.add(new Candidate(track, score));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Candidate> pool = new ArrayList<>();
        Set<Candidate> taken = new HashSet<>();
        Map<String, Integer> perAlbum = new HashMap<>();
        for (Candidate item : scored) {
            if (pool.size() >= TOP) break;
            String key = norm(item.track.album) + "|" + norm(albumArtist(item.track));
            int count = perAlbum.getOrDefault(key, 0);
            if (count >= PER_ALBUM) continue;
            perAlbum.put(key, count + 1);
            pool.add(item);
            taken.add(item);
        }
        List<Candidate> rest = new ArrayList<>();
        for (Candidate item : scored) if (!taken.contains(item)) rest.add(item);
        while (pool.size() < POOL && !rest.isEmpty()) pool.add(rest.remove(random.nextInt(rest.size())));
        return pool;
    }

    /** 不用 AI 时：按得分挑选，同一艺人最多 2 首。 */
    static List<TidalClient.Track> heuristic(List<Candidate> pool, int count) {
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        Map<String, Integer> perArtist = new HashMap<>();
        List<TidalClient.Track> picks = new ArrayList<>();
        for (Candidate item : sorted) {
            if (picks.size() >= count) break;
            String artist = norm(item.track.artist);
            int n = perArtist.getOrDefault(artist, 0);
            if (n >= PER_ARTIST) continue;
            perArtist.put(artist, n + 1);
            picks.add(item.track);
        }
        return picks;
    }

    private static String cut(String value, int max) {
        String clean = value == null ? "" : value.replace('|', ' ').replace('\n', ' ');
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    static String prompt(List<TidalClient.Track> seeds, List<Candidate> pool, int count) {
        StringBuilder text = new StringBuilder("你是一位懂发烧录音的私人电台 DJ。听众正在连续聆听（最后一首是正在播放的）：\n");
        for (int i = 0; i < seeds.size(); i++) {
            TidalClient.Track t = seeds.get(i);
            StringBuilder info = new StringBuilder();
            for (String part : new String[] {t.album, genre(t), year(t) > 0 ? String.valueOf(year(t)) : ""}) {
                if (part.isEmpty()) continue;
                if (info.length() > 0) info.append("，");
                info.append(part);
            }
            text.append(i + 1).append(". 《").append(t.title).append("》— ")
                    .append(t.artist.isEmpty() ? "未知" : t.artist).append("（").append(info).append("）\n");
        }
        text.append("\n以下是听众本地曲库中的候选曲目（编号|曲名|艺人|专辑|流派|年份）：\n");
        for (int i = 0; i < pool.size(); i++) {
            TidalClient.Track t = pool.get(i).track;
            text.append('c').append(i + 1).append('|').append(cut(t.title, 40)).append('|').append(cut(t.artist, 40))
                    .append('|').append(cut(t.album, 40)).append('|').append(cut(genre(t), 20)).append('|')
                    .append(year(t) > 0 ? year(t) : "").append('\n');
        }
        text.append("\n请挑选 ").append(count).append(" 首最适合接在后面播放的曲目：\n")
                .append("- 与正在播放的曲风、情绪、能量自然衔接，按播放顺序排列，整体起伏平滑\n")
                .append("- 同一艺人最多 2 首，不要连续两首出自同一张专辑\n")
                .append("- picks 只能填写上面候选中的编号，不得编造\n\n")
                .append("仅输出 JSON：{\"theme\":\"不超过12字的续播主题\",\"picks\":[\"c3\",\"c17\"]}");
        return text.toString();
    }

    /** 从模型回复里取出第一个 JSON 对象。 */
    static JSONObject json(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(reply.substring(start, end + 1));
        } catch (JSONException error) {
            return null;
        }
    }

    /** 解析 AI 挑选结果。模型不一定守规矩：在这里强制同艺人最多 2 首、相邻两首不同专辑。 */
    static Plan parse(String reply, List<Candidate> pool, TidalClient.Track lastSeed, int count) {
        JSONObject data = json(reply);
        JSONArray picks = data == null ? null : data.optJSONArray("picks");
        if (picks == null) return null;
        Set<Integer> seen = new HashSet<>();
        Map<String, Integer> perArtist = new HashMap<>();
        String previousAlbum = lastSeed == null ? "" : norm(lastSeed.album);
        List<TidalClient.Track> tracks = new ArrayList<>();
        for (int i = 0; i < picks.length() && tracks.size() < count; i++) {
            String code = picks.optString(i).trim();
            if (!code.matches("[cC]?\\d{1,4}")) continue;
            int index = Integer.parseInt(code.replaceFirst("^[cC]", "")) - 1;
            if (index < 0 || index >= pool.size() || seen.contains(index)) continue;
            TidalClient.Track track = pool.get(index).track;
            String artist = norm(track.artist);
            String album = norm(track.album);
            if (perArtist.getOrDefault(artist, 0) >= PER_ARTIST) continue;
            if (!album.isEmpty() && album.equals(previousAlbum)) continue;
            seen.add(index);
            perArtist.put(artist, perArtist.getOrDefault(artist, 0) + 1);
            previousAlbum = album;
            tracks.add(track);
        }
        String theme = data.optString("theme").trim();
        if (theme.length() > 16) theme = theme.substring(0, 16);
        return new Plan(theme, tracks, true);
    }

    /**
     * AI 挑出的合规曲目不足时，用打分最高的候选补齐，规则与 AI 结果合并计算
     * （同艺人最多 2 首、不与前一首同专辑）。
     */
    /** 补位门槛：得分低于此值说明与正在听的毫无关联（只有随机分），宁缺毋滥。 */
    static final double TOP_UP_MIN_SCORE = 2;

    static List<TidalClient.Track> topUp(List<TidalClient.Track> chosen, List<Candidate> pool, int count) {
        List<TidalClient.Track> result = new ArrayList<>(chosen);
        if (result.size() >= count) return result;
        Set<Long> used = new HashSet<>();
        Map<String, Integer> perArtist = new HashMap<>();
        for (TidalClient.Track t : result) {
            used.add(t.id);
            perArtist.merge(norm(t.artist), 1, Integer::sum);
        }
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        for (Candidate item : sorted) {
            if (result.size() >= count) break;
            TidalClient.Track t = item.track;
            String artist = norm(t.artist);
            String previous = result.isEmpty() ? "" : norm(result.get(result.size() - 1).album);
            if (item.score < TOP_UP_MIN_SCORE) break;
            if (used.contains(t.id) || perArtist.getOrDefault(artist, 0) >= PER_ARTIST
                    || (!norm(t.album).isEmpty() && norm(t.album).equals(previous))) continue;
            used.add(t.id);
            perArtist.merge(artist, 1, Integer::sum);
            result.add(t);
        }
        return result;
    }

    /** Messages for {@link AiProxy#chat}. */
    static JSONObject request(String prompt) throws JSONException {
        return new JSONObject()
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.6)
                .put("max_tokens", 800);
    }
}
