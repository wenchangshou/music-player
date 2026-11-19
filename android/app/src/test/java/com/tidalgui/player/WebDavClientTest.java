package com.tidalgui.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class WebDavClientTest {
    @Test
    public void parsesSynologyAndAListWebDavMultiStatus() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
                + "<D:multistatus xmlns:D=\"DAV:\">\n"
                + "  <D:response>\n"
                + "    <D:href>/music/</D:href>\n"
                + "    <D:propstat>\n"
                + "      <D:prop>\n"
                + "        <D:resourcetype><D:collection/></D:resourcetype>\n"
                + "      </D:prop>\n"
                + "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "    </D:propstat>\n"
                + "  </D:response>\n"
                + "  <D:response>\n"
                + "    <D:href>/music/Jay%20Chou/</D:href>\n"
                + "    <D:propstat>\n"
                + "      <D:prop>\n"
                + "        <D:displayname>Jay Chou</D:displayname>\n"
                + "        <D:resourcetype><D:collection/></D:resourcetype>\n"
                + "      </D:prop>\n"
                + "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "    </D:propstat>\n"
                + "  </D:response>\n"
                + "  <D:response>\n"
                + "    <D:href>/music/01.%20%E6%99%B4%E5%A4%A9.flac</D:href>\n"
                + "    <D:propstat>\n"
                + "      <D:prop>\n"
                + "        <D:displayname>01. 晴天.flac</D:displayname>\n"
                + "        <D:resourcetype/>\n"
                + "        <D:getcontentlength>32456789</D:getcontentlength>\n"
                + "      </D:prop>\n"
                + "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "    </D:propstat>\n"
                + "  </D:response>\n"
                + "</D:multistatus>";

        WebDavClient.Server server = new WebDavClient.Server("srv1", "我的 NAS", "http://192.168.1.100:5005/music/", "admin", "123456");
        List<WebDavClient.Item> items = WebDavClient.parseMultiStatus(server, "http://192.168.1.100:5005/music/", xml);

        // Self-node /music/ is filtered out, leaving 2 items
        assertEquals(2, items.size());

        // First item is directory (directories sorted first)
        WebDavClient.Item dir = items.get(0);
        assertTrue(dir.isDirectory);
        assertEquals("Jay Chou", dir.name);
        assertFalse(dir.isAudio);

        // Second item is audio file
        WebDavClient.Item track = items.get(1);
        assertFalse(track.isDirectory);
        assertTrue(track.isAudio);
        assertEquals("01. 晴天.flac", track.name);
        assertEquals(32456789L, track.size);
        assertEquals("http://192.168.1.100:5005/music/01.%20%E6%99%B4%E5%A4%A9.flac", track.url);

        // Convert to TidalClient.Track
        TidalClient.Track t = WebDavClient.toTrack(server, track, "叶惠美");
        assertTrue(t.isWebdav());
        assertFalse(t.isLocal());
        assertFalse(t.isNetease());
        assertFalse(t.needsTidal());
        assertEquals("01. 晴天", t.title);
        assertEquals("叶惠美", t.album);
        assertEquals("我的 NAS", t.artist);
        assertEquals(track.url, t.webdavUrl);
    }
}
