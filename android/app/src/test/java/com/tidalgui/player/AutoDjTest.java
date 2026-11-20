package com.tidalgui.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class AutoDjTest {
    private static TidalClient.Track track(long id, String title, String artist, String album, String genre, String year)
            throws Exception {
        return new TidalClient.Track(new JSONObject().put("id", id).put("title", title)
                .put("artist", new JSONObject().put("name", artist))
                .put("album", new JSONObject().put("title", album))
                .put("genre", genre).put("year", year));
    }

    private static List<TidalClient.Track> library() throws Exception {
        List<TidalClient.Track> all = new ArrayList<>();
        long id = -1;
        for (int i = 0; i < 6; i++) all.add(track(id--, "Sigur " + i, "Sigur Rós", "Takk", "后摇", "2005"));
        for (int i = 0; i < 6; i++) all.add(track(id--, "Mogwai " + i, "Mogwai", "Hardcore", "后摇", "2011"));
        for (int i = 0; i < 6; i++) all.add(track(id--, "Nocturne " + i, "Tom Hicks", "Chopin", "古典", "2025"));
        for (int i = 0; i < 40; i++) all.add(track(id--, "Pop " + i, "Artist " + i, "Album " + i, "流行", "2020"));
        return all;
    }

    @Test public void poolPrefersSeedArtistAndGenreAndSkipsQueued() throws Exception {
        List<TidalClient.Track> lib = library();
        TidalClient.Track seed = lib.get(0);
        List<AutoDj.Candidate> pool = AutoDj.pool(Collections.singletonList(seed), Collections.singleton(seed.id),
                lib, new Random(1));
        assertTrue(pool.size() <= AutoDj.POOL);
        for (AutoDj.Candidate c : pool) assertFalse(c.track.id == seed.id);
        // The top of the pool is the same artist or genre (3 per album: Takk and Hardcore), never unrelated pop.
        for (int i = 0; i < 6; i++) assertEquals("后摇", AutoDj.genre(pool.get(i).track));
        // At most three songs of one album in the scored part.
        Map<String, Integer> perAlbum = new HashMap<>();
        for (AutoDj.Candidate c : pool.subList(0, 20)) perAlbum.merge(c.track.album, 1, Integer::sum);
        for (int n : perAlbum.values()) assertTrue(n <= 3);
    }

    @Test public void heuristicCapsTwoPerArtist() throws Exception {
        List<TidalClient.Track> lib = library();
        List<AutoDj.Candidate> pool = AutoDj.pool(Collections.singletonList(lib.get(0)),
                Collections.singleton(lib.get(0).id), lib, new Random(2));
        List<TidalClient.Track> picks = AutoDj.heuristic(pool, 6);
        assertEquals(6, picks.size());
        Map<String, Integer> perArtist = new HashMap<>();
        for (TidalClient.Track t : picks) perArtist.merge(t.artist, 1, Integer::sum);
        for (int n : perArtist.values()) assertTrue(n <= 2);
    }

    @Test public void parseEnforcesRulesAndIgnoresInventedCodes() throws Exception {
        List<AutoDj.Candidate> pool = new ArrayList<>();
        for (TidalClient.Track t : Arrays.asList(
                track(-1, "A1", "A", "AlbumA", "", ""), track(-2, "A2", "A", "AlbumA2", "", ""),
                track(-3, "A3", "A", "AlbumA3", "", ""), track(-4, "B1", "B", "Seed Album", "", ""),
                track(-5, "C1", "C", "AlbumC", "", ""), track(-6, "C2", "C", "AlbumC", "", ""))) {
            pool.add(new AutoDj.Candidate(t, 1));
        }
        TidalClient.Track seed = track(-9, "S", "B", "Seed Album", "", "");
        String reply = "好的：```json\n{\"theme\":\"深夜后摇漫游与回声之旅特别特别特别长的主题\",\"picks\":[\"c1\",\"c2\",\"c3\",\"c4\",\"c99\",\"x\",\"c5\",\"c6\",\"c1\"]}\n```";
        AutoDj.Plan plan = AutoDj.parse(reply, pool, seed, 6);
        List<String> titles = new ArrayList<>();
        for (TidalClient.Track t : plan.tracks) titles.add(t.title);
        // A3: third song by A; C2: same album as C1 right before it; c99 and x are not candidates.
        assertEquals(Arrays.asList("A1", "A2", "B1", "C1"), titles);
        assertEquals(16, plan.theme.length());
        assertTrue(plan.byAi);
    }

    @Test public void parseSkipsFirstPickFromTheSeedAlbum() throws Exception {
        List<AutoDj.Candidate> pool = Arrays.asList(
                new AutoDj.Candidate(track(-4, "B1", "B", "Seed Album", "", ""), 1),
                new AutoDj.Candidate(track(-5, "C1", "C", "AlbumC", "", ""), 1));
        TidalClient.Track seed = track(-9, "S", "B", "Seed Album", "", "");
        AutoDj.Plan plan = AutoDj.parse("{\"picks\":[\"c1\",\"c2\"]}", pool, seed, 6);
        assertEquals(1, plan.tracks.size());
        assertEquals("C1", plan.tracks.get(0).title);
    }

    @Test public void topUpFillsToBatchWithinRules() throws Exception {
        List<TidalClient.Track> lib = library();
        List<AutoDj.Candidate> pool = AutoDj.pool(Collections.singletonList(lib.get(0)),
                Collections.singleton(lib.get(0).id), lib, new Random(4));
        List<TidalClient.Track> chosen = new ArrayList<>(Arrays.asList(lib.get(1), lib.get(2)));
        List<TidalClient.Track> full = AutoDj.topUp(chosen, pool, 6);
        // Sigur Rós is at its cap; only same-genre Mogwai qualifies, and its songs share one album,
        // so just one is added. Unrelated pop and classical are never used as filler.
        assertEquals(3, full.size());
        assertEquals(lib.get(1).id, full.get(0).id);
        assertEquals("Mogwai", full.get(2).artist);
    }

    @Test public void sameGenreFamilyOutranksUnrelatedAndTopUpSkipsUnrelated() throws Exception {
        List<TidalClient.Track> lib = new ArrayList<>();
        TidalClient.Track seed = track(-1, "Nocturne", "Tom Hicks", "Chopin", "古典 · 独奏键盘音乐", "2025");
        lib.add(track(-2, "Concerto", "Chloe Chua", "Butterfly", "古典 · 协奏曲", "2024"));
        for (int i = 0; i < 10; i++) lib.add(track(-10 - i, "Rock " + i, "Band " + i, "Loud " + i, "摇滚 · 后朋克", "1990"));
        List<AutoDj.Candidate> pool = AutoDj.pool(Collections.singletonList(seed), Collections.singleton(seed.id),
                lib, new Random(5));
        AutoDj.Candidate best = pool.get(0);
        for (AutoDj.Candidate c : pool) if (c.score > best.score) best = c;
        assertEquals("Concerto", best.track.title);
        List<TidalClient.Track> full = AutoDj.topUp(new ArrayList<>(), pool, 6);
        assertEquals(1, full.size());
        assertEquals("Concerto", full.get(0).title);
    }

    @Test public void parseRejectsNonJson() {
        assertNull(AutoDj.parse("抱歉，我无法完成", new ArrayList<>(), null, 6));
    }

    @Test public void promptListsCandidatesByCode() throws Exception {
        List<TidalClient.Track> lib = library();
        List<AutoDj.Candidate> pool = AutoDj.pool(Collections.singletonList(lib.get(0)),
                Collections.singleton(lib.get(0).id), lib, new Random(3));
        String prompt = AutoDj.prompt(Collections.singletonList(lib.get(0)), pool, 6);
        assertTrue(prompt.contains("c1|"));
        assertTrue(prompt.contains("c" + pool.size() + "|"));
        assertTrue(prompt.contains("《Sigur 0》"));
    }

    @Test public void albumCardMatchesKeyTracksLoosely() throws Exception {
        List<TidalClient.Track> tracks = Arrays.asList(track(-1, "Hoppípolla", "Sigur Rós", "Takk", "", ""),
                track(-2, "Sæglópur (Live)", "Sigur Rós", "Takk", "", ""));
        assertEquals(0, AiScreens.matchTrack(tracks, "hoppípolla"));
        assertEquals(1, AiScreens.matchTrack(tracks, "Sæglópur"));
        assertEquals(-1, AiScreens.matchTrack(tracks, "Glósóli"));
        assertEquals(-1, AiScreens.matchTrack(tracks, ""));
    }

    @Test public void insightRequiresReview() {
        assertNull(AiNotes.parseInsight("{\"genre\":\"后摇\"}"));
        assertEquals("好", AiNotes.parseInsight("```{\"review\":\"好\"}```").optString("review"));
    }
}
