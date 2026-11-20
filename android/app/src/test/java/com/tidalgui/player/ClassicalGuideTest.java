package com.tidalgui.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ClassicalGuideTest {
    private static TidalClient.Track track(String title, String album, String genre) throws Exception {
        JSONObject data = new JSONObject().put("id", -1).put("title", title)
                .put("artist", new JSONObject().put("name", "Tom Hicks"))
                .put("album", new JSONObject().put("title", album));
        if (genre != null) data.put("genre", genre);
        return new TidalClient.Track(data);
    }

    @Test public void detectsClassicalByGenreFirstThenTitle() throws Exception {
        assertTrue(ClassicalGuide.isClassical(track("Nocturne", "Chopin", "古典 · 独奏键盘音乐")));
        assertTrue(ClassicalGuide.isClassical(track("Spring", "Seasons", "Classical")));
        assertFalse(ClassicalGuide.isClassical(track("Nocturne Op. 9", "x", "流行 · 流行摇滚"))); // genre wins
        assertTrue(ClassicalGuide.isClassical(track("Nocturnes, Op. 9, B. 54: No. 1", "Chopin", null)));
        assertTrue(ClassicalGuide.isClassical(track("梁祝小提琴协奏曲", "x", null)));
        assertFalse(ClassicalGuide.isClassical(track("董小姐", "安和桥北", null)));
        assertFalse(ClassicalGuide.isClassical(null));
    }

    @Test public void readsTimestamps() {
        assertEquals(92, ClassicalGuide.seconds("1:32"));
        assertEquals(92, ClassicalGuide.seconds("01:32"));
        assertEquals(92, ClassicalGuide.seconds("1:32.50"));
        assertEquals(92, ClassicalGuide.seconds(92));
        assertEquals(92, ClassicalGuide.seconds("92"));
        assertEquals(-1, ClassicalGuide.seconds("约一分半"));
    }

    @Test public void lrcKeepsOrderedSegmentsInsideTheRecording() {
        String reply = "```json\n{\"work\":\"降B小调夜曲 Op.9 No.1（肖邦）\",\"overview\":\"慵懒如梦的夜晚独白\",\"segments\":["
                + "{\"t\":\"0:00\",\"title\":\"A 段\",\"text\":\"右手旋律[如]歌\"},"
                + "{\"t\":\"1:30\",\"title\":\"B 段\",\"text\":\"转入降D大调\"},"
                + "{\"t\":\"1:10\",\"title\":\"倒退\",\"text\":\"应被丢弃\"},"
                + "{\"t\":\"9:59\",\"title\":\"超时\",\"text\":\"应被丢弃\"},"
                + "{\"t\":\"3:40\",\"title\":\"再现\",\"text\":\"主题回归\"}]}\n```";
        String lrc = ClassicalGuide.toLrc(reply, 342);
        assertTrue(lrc.startsWith("[00:00.00]✨ AI 聆听导览 · 降B小调夜曲 Op.9 No.1（肖邦）\n[00:00.00]慵懒如梦的夜晚独白\n"));
        assertTrue(lrc.contains("[00:00.00]【A 段】右手旋律 如 歌\n"));
        assertTrue(lrc.contains("[01:30.00]【B 段】转入降D大调\n"));
        assertTrue(lrc.contains("[03:40.00]【再现】主题回归\n"));
        assertFalse(lrc.contains("应被丢弃"));
    }

    @Test public void rejectsTooFewOrBrokenSegments() {
        assertNull(ClassicalGuide.toLrc("{\"segments\":[{\"t\":\"0:00\",\"title\":\"全曲\",\"text\":\"一段\"}]}", 300));
        assertNull(ClassicalGuide.toLrc("对不起，我不了解这首作品。", 300));
    }

    @Test public void longTextIsTrimmed() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80; i++) text.append('音');
        String lrc = ClassicalGuide.toLrc("{\"segments\":[{\"t\":0,\"title\":\"甲\",\"text\":\"" + text + "\"},"
                + "{\"t\":60,\"title\":\"乙\",\"text\":\"短\"}]}", 0);
        assertTrue(lrc.contains("…\n[01:00.00]【乙】短"));
    }
}
