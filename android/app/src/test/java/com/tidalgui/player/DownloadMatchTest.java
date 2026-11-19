package com.tidalgui.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** How songs with no download record are recognised as already saved. */
public class DownloadMatchTest {
    private static TidalClient.Track tidal(long id, String title, String artist, String version, long seconds) throws Exception {
        JSONObject data = new JSONObject().put("id", id).put("title", title).put("duration", seconds)
                .put("artists", new JSONArray().put(new JSONObject().put("name", artist)))
                .put("album", new JSONObject().put("title", "Parachutes").put("id", 1));
        if (version != null) data.put("version", version);
        return new TidalClient.Track(data);
    }

    private static TidalClient.Track local(String title, String artist, long seconds, String file) throws Exception {
        return new TidalClient.Track(new JSONObject().put("id", -5).put("title", title).put("duration", seconds)
                .put("file_name", file).put("artist", new JSONObject().put("name", artist))
                .put("album", new JSONObject().put("title", "Parachutes")));
    }

    @Test public void keysIgnoreCaseSpacesPunctuationAndFeaturedArtists() {
        assertEquals(DownloadTasks.nameKey("Yellow", "Coldplay"), DownloadTasks.nameKey(" yellow!", "COLDPLAY, Guest"));
        assertEquals(DownloadTasks.nameKey("晴天", "周杰伦"), DownloadTasks.nameKey("晴天", "周杰伦 feat. 某人"));
        assertEquals(DownloadTasks.nameKey("Ｙｅｌｌｏｗ", "Coldplay"), DownloadTasks.nameKey("Yellow", "Coldplay & Friends"));
        assertNull(DownloadTasks.nameKey("Yellow", ""));
        assertNull(DownloadTasks.nameKey("...", "Coldplay"));
    }

    /** The desktop app computes the same keys and hashes (src/marks.rs tests these values too). */
    @Test public void hashesMatchTheDesktopApp() {
        assertEquals("e4981116c82f", DownloadTasks.hash(DownloadTasks.nameKey("Yellow", "Coldplay")));
        assertEquals("b2fd8c968fa1", DownloadTasks.hash(DownloadTasks.nameKey("晴天", "周杰伦")));
        assertEquals("7c2f98c4fdfd", DownloadTasks.hash(DownloadTasks.nameKey("Ｙｅｌｌｏｗ (Live)", "Coldplay")));
    }

    @Test public void untaggedFilesAreTitledByTheirFileName() {
        assertEquals("Yellow", DownloadTasks.fileTitle("01. Yellow.flac"));
        assertEquals("晴天", DownloadTasks.fileTitle("1-03 晴天.m4a"));
        assertEquals("Yellow", DownloadTasks.fileTitle("Yellow.mp3"));
        assertEquals("Yellow", DownloadTasks.fileTitle("Yellow"));
    }

    @Test public void versionsMatchWithOrWithoutTheVersionInTheTitle() throws Exception {
        TidalClient.Track live = tidal(7, "Yellow", "Coldplay", "Live", 280);
        assertEquals(Arrays.asList(DownloadTasks.nameKey("Yellow", "Coldplay"),
                DownloadTasks.nameKey("Yellow (Live)", "Coldplay")), DownloadTasks.nameKeys(live));
        assertEquals(Collections.singletonList(DownloadTasks.nameKey("Yellow", "Coldplay")),
                DownloadTasks.nameKeys(tidal(8, "Yellow", "Coldplay", null, 269)));
    }

    @Test public void lengthsMustBeWithinThreeSecondsWhenBothAreKnown() {
        assertTrue(DownloadTasks.sameLength(269, 272));
        assertFalse(DownloadTasks.sameLength(269, 280));
        assertTrue(DownloadTasks.sameLength(0, 280));
        assertTrue(DownloadTasks.sameLength(269, 0));
    }

    @Test public void losslessFromTheScanOrTheFileName() throws Exception {
        assertTrue(DownloadTasks.lossless(local("Yellow", "Coldplay", 269, "01. Yellow.flac")));
        assertFalse(DownloadTasks.lossless(local("Yellow", "Coldplay", 269, "01. Yellow.mp3")));
        assertFalse(DownloadTasks.lossless(local("Yellow", "Coldplay", 269, "01. Yellow.m4a")));
        TidalClient.Track alac = new TidalClient.Track(local("Yellow", "Coldplay", 269, "01. Yellow.m4a").payload.put("lossless", true));
        assertTrue(DownloadTasks.lossless(alac));
    }

    @Test public void libraryMarksCompareLengthsAndSurviveTheTripToAnotherDevice() throws Exception {
        DownloadTasks.Downloaded marks = new DownloadTasks.Downloaded();
        marks.addLibrary(Arrays.asList(local("01. Yellow.flac", "", 0, "01. Yellow.flac"),
                local("Yellow", "Coldplay", 269, "Yellow.flac"),
                local("Shiver", "Coldplay", 0, "Shiver.flac")));
        DownloadTasks.Downloaded sent = DownloadTasks.Downloaded.fromJson(marks.toJson());
        for (DownloadTasks.Downloaded side : Arrays.asList(marks, sent)) {
            assertEquals("本地已有", side.label(tidal(1, "Yellow", "Coldplay", null, 270)));
            assertNull(side.label(tidal(2, "Yellow", "Coldplay", "Live", 300)));
            assertEquals("本地已有", side.label(tidal(3, "Shiver", "Coldplay", null, 300)));
            assertNull(side.label(tidal(4, "Trouble", "Coldplay", null, 270)));
        }
    }

    @Test public void largeLibrariesFitInOneRelayReply() throws Exception {
        java.util.List<TidalClient.Track> library = new java.util.ArrayList<>();
        for (int i = 0; i < 45000; i++) library.add(local("Song number " + i, "Artist " + (i % 500), 200 + i % 300, "x.flac"));
        DownloadTasks.Downloaded marks = new DownloadTasks.Downloaded();
        marks.addLibrary(library);
        String sent = marks.toJson().toString();
        assertTrue(sent.length() < 900 * 1024);
        DownloadTasks.Downloaded received = DownloadTasks.Downloaded.fromJson(new JSONObject(sent));
        assertEquals("本地已有", received.label(tidal(1, "Song number 12", "Artist 12", null, 212)));
        assertNull(received.label(tidal(2, "Song number 12", "Artist 12", null, 260)));
    }

    @Test public void marksFromDevicesThatSendKeysOnlyStillMatchByName() throws Exception {
        JSONObject old = new JSONObject().put("named", new JSONArray().put(DownloadTasks.nameKey("Yellow", "Coldplay")));
        assertEquals("本地已有", DownloadTasks.Downloaded.fromJson(old).label(tidal(1, "Yellow", "Coldplay", null, 300)));
    }
}
