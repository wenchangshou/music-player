package com.tidalgui.player;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SmbClientTest {

    @Test
    public void testServerNormalizationAndJson() {
        SmbClient.Server s1 = new SmbClient.Server("test-id", "NAS", "smb://192.168.1.100:445/music/", 445, "music", "alice", "secret", "WORKGROUP");
        assertEquals("test-id", s1.id);
        assertEquals("NAS", s1.name);
        assertEquals("192.168.1.100", s1.host);
        assertEquals(445, s1.port);
        assertEquals("music", s1.share);
        assertEquals("smb://192.168.1.100/music/", s1.rootUrl());
        assertEquals("smb://192.168.1.100/music · alice", s1.displayAddress());

        JSONObject json = s1.toJson();
        SmbClient.Server restored = new SmbClient.Server(json);
        assertEquals(s1.id, restored.id);
        assertEquals(s1.name, restored.name);
        assertEquals(s1.host, restored.host);
        assertEquals(s1.port, restored.port);
        assertEquals(s1.share, restored.share);
        assertEquals(s1.user, restored.user);
        assertEquals(s1.pass, restored.pass);
        assertEquals(s1.domain, restored.domain);
    }

    @Test
    public void testServerWithCustomPortAndNoShare() {
        SmbClient.Server s2 = new SmbClient.Server(null, "", "10.0.0.5", 1445, "", "", "", "");
        assertEquals("SMB 共享", s2.name);
        assertNotNull(s2.id);
        assertFalse(s2.id.isEmpty());
        assertEquals("10.0.0.5", s2.host);
        assertEquals(1445, s2.port);
        assertTrue(s2.share.isEmpty());
        assertEquals("smb://10.0.0.5:1445/", s2.rootUrl());
        assertEquals("smb://10.0.0.5:1445", s2.displayAddress());
    }

    @Test
    public void testAudioFileDetection() {
        assertTrue(SmbClient.isAudioFile("song.flac"));
        assertTrue(SmbClient.isAudioFile("SONG.FLAC"));
        assertTrue(SmbClient.isAudioFile("track.wav"));
        assertTrue(SmbClient.isAudioFile("audio.mp3"));
        assertTrue(SmbClient.isAudioFile("music.m4a"));
        assertTrue(SmbClient.isAudioFile("album.dsf"));
        assertTrue(SmbClient.isAudioFile("sacd.dff"));
        assertTrue(SmbClient.isAudioFile("stream.opus"));
        assertTrue(SmbClient.isAudioFile("lossless.ape"));

        assertFalse(SmbClient.isAudioFile("lyrics.lrc"));
        assertFalse(SmbClient.isAudioFile("cover.jpg"));
        assertFalse(SmbClient.isAudioFile("cover.png"));
        assertFalse(SmbClient.isAudioFile("document.pdf"));
        assertFalse(SmbClient.isAudioFile("archive.zip"));
        assertFalse(SmbClient.isAudioFile(null));
    }

    @Test
    public void testItemSorting() {
        List<SmbClient.Item> items = new ArrayList<>();
        items.add(new SmbClient.Item("Track B.flac", "smb://host/share/Track B.flac", "smb://host/share/Track B.flac", false, 1024));
        items.add(new SmbClient.Item("Folder Z", "smb://host/share/Folder Z/", "smb://host/share/Folder Z/", true, 0));
        items.add(new SmbClient.Item("Track A.flac", "smb://host/share/Track A.flac", "smb://host/share/Track A.flac", false, 2048));
        items.add(new SmbClient.Item("Folder A", "smb://host/share/Folder A/", "smb://host/share/Folder A/", true, 0));

        Collections.sort(items, (a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        assertEquals("Folder A", items.get(0).name);
        assertTrue(items.get(0).isDirectory);
        assertEquals("Folder Z", items.get(1).name);
        assertTrue(items.get(1).isDirectory);
        assertEquals("Track A.flac", items.get(2).name);
        assertFalse(items.get(2).isDirectory);
        assertEquals("Track B.flac", items.get(3).name);
        assertFalse(items.get(3).isDirectory);
    }

    @Test
    public void testToTrack() throws Exception {
        SmbClient.Server server = new SmbClient.Server("nas-01", "家庭 NAS", "192.168.1.50", 445, "music", "wcs", "123", "");
        SmbClient.Item item = new SmbClient.Item("01. Nocturne.flac", "smb://192.168.1.50/music/Chopin/01. Nocturne.flac",
                "smb://192.168.1.50/music/Chopin/01. Nocturne.flac", false, 55000000);

        TidalClient.Track track = SmbClient.toTrack(server, item, "Chopin Nocturnes");
        assertTrue(track.id < 0);
        assertEquals("01. Nocturne", track.title);
        assertEquals("家庭 NAS", track.artist);
        assertEquals("Chopin Nocturnes", track.album);
        assertEquals("smb://192.168.1.50/music/Chopin/01. Nocturne.flac", track.smbUrl);
        assertEquals("nas-01", track.smbServerId);
        assertTrue(track.isSmb());
        assertFalse(track.isLocal());
        assertFalse(track.isWebdav());
        assertFalse(track.isNetease());
        assertFalse(track.needsTidal());
    }
}
