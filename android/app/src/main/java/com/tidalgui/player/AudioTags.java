package com.tidalgui.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tags for downloaded songs, so players and the system media library know the title, artist,
 * album, track and disc numbers, year and cover. TIDAL and NetEase serve their audio untagged.
 *
 * Tags are written while the file is saved: {@link #wrap} returns a stream that replaces the
 * tag section of the FLAC (Vorbis comment + picture) or MP3 (ID3v2.3) passing through it.
 */
final class AudioTags {
    final String title;
    final String artist;
    final String album;
    final String albumArtist;
    final String date;
    final int track;
    final int totalTracks;
    final int disc;
    final int totalDiscs;
    /** Front cover as a 1000×1000 JPEG, or null. */
    final byte[] cover;

    AudioTags(DownloadTasks.Job job, byte[] cover) {
        title = job.title;
        artist = job.artist;
        album = job.albumTitle;
        albumArtist = job.albumArtist;
        date = job.date;
        track = job.number;
        totalTracks = job.totalTracks;
        disc = job.disc;
        totalDiscs = job.volumes;
        this.cover = cover;
    }

    /** A stream that writes {@code target} with these tags; other formats pass through untouched. */
    OutputStream wrap(OutputStream target, String extension) {
        if ("flac".equals(extension)) return new Rewriter(target, true);
        if ("mp3".equals(extension)) return new Rewriter(target, false);
        return target;
    }

    // ───────────── FLAC: Vorbis comment and picture blocks ─────────────

    private byte[] flacBlocks() throws IOException {
        ByteArrayOutputStream comment = new ByteArrayOutputStream();
        byte[] vendor = "听音室".getBytes(StandardCharsets.UTF_8);
        littleEndian(comment, vendor.length);
        comment.write(vendor);
        java.util.List<byte[]> fields = new java.util.ArrayList<>();
        field(fields, "TITLE", title);
        field(fields, "ARTIST", artist);
        field(fields, "ALBUM", album);
        field(fields, "ALBUMARTIST", albumArtist);
        field(fields, "DATE", date);
        if (track > 0) field(fields, "TRACKNUMBER", Integer.toString(track));
        if (totalTracks > 0) {
            field(fields, "TOTALTRACKS", Integer.toString(totalTracks));
            field(fields, "TRACKTOTAL", Integer.toString(totalTracks));
        }
        if (disc > 0) field(fields, "DISCNUMBER", Integer.toString(disc));
        if (totalDiscs > 0) {
            field(fields, "TOTALDISCS", Integer.toString(totalDiscs));
            field(fields, "DISCTOTAL", Integer.toString(totalDiscs));
        }
        littleEndian(comment, fields.size());
        for (byte[] entry : fields) {
            littleEndian(comment, entry.length);
            comment.write(entry);
        }
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        block(blocks, 4, comment.toByteArray(), false);
        if (cover != null) {
            ByteArrayOutputStream picture = new ByteArrayOutputStream();
            byte[] mime = "image/jpeg".getBytes(StandardCharsets.US_ASCII);
            bigEndian(picture, 3); // front cover
            bigEndian(picture, mime.length);
            picture.write(mime);
            bigEndian(picture, 0); // no description
            bigEndian(picture, 1000);
            bigEndian(picture, 1000);
            bigEndian(picture, 24);
            bigEndian(picture, 0);
            bigEndian(picture, cover.length);
            picture.write(cover);
            block(blocks, 6, picture.toByteArray(), false);
        }
        block(blocks, 1, new byte[1024], true); // padding, so tag edits need no rewrite
        return blocks.toByteArray();
    }

    private static void field(java.util.List<byte[]> fields, String key, String value) {
        if (value != null && !value.trim().isEmpty()) fields.add((key + "=" + value.trim()).getBytes(StandardCharsets.UTF_8));
    }

    private static void block(ByteArrayOutputStream out, int type, byte[] body, boolean last) throws IOException {
        if (body.length >= 1 << 24) throw new IOException("标签数据过大");
        out.write((last ? 0x80 : 0) | type);
        out.write(body.length >>> 16);
        out.write(body.length >>> 8);
        out.write(body.length);
        out.write(body);
    }

    // ───────────── MP3: ID3v2.3 ─────────────

    private byte[] id3() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        text(frames, "TIT2", title);
        text(frames, "TPE1", artist);
        text(frames, "TALB", album);
        text(frames, "TPE2", albumArtist);
        if (track > 0) text(frames, "TRCK", track + (totalTracks > 0 ? "/" + totalTracks : ""));
        if (disc > 0) text(frames, "TPOS", disc + (totalDiscs > 0 ? "/" + totalDiscs : ""));
        if (date != null && date.length() >= 4) text(frames, "TYER", date.substring(0, 4));
        if (cover != null) {
            ByteArrayOutputStream picture = new ByteArrayOutputStream();
            picture.write(0);
            picture.write("image/jpeg".getBytes(StandardCharsets.US_ASCII));
            picture.write(0);
            picture.write(3); // front cover
            picture.write(0); // empty description
            picture.write(cover);
            frame(frames, "APIC", picture.toByteArray());
        }
        frames.write(new byte[1024]); // padding
        int size = frames.size();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write(new byte[]{'I', 'D', '3', 3, 0, 0});
        tag.write((size >>> 21) & 0x7f);
        tag.write((size >>> 14) & 0x7f);
        tag.write((size >>> 7) & 0x7f);
        tag.write(size & 0x7f);
        tag.write(frames.toByteArray());
        return tag.toByteArray();
    }

    private static void text(ByteArrayOutputStream frames, String id, String value) throws IOException {
        if (value == null || value.trim().isEmpty()) return;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(1); // UTF-16 with byte order mark, for Chinese titles
        body.write(0xff);
        body.write(0xfe);
        body.write(value.trim().getBytes(StandardCharsets.UTF_16LE));
        frame(frames, id, body.toByteArray());
    }

    private static void frame(ByteArrayOutputStream frames, String id, byte[] body) throws IOException {
        frames.write(id.getBytes(StandardCharsets.US_ASCII));
        bigEndian(frames, body.length);
        frames.write(0);
        frames.write(0);
        frames.write(body);
    }

    private static void littleEndian(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void bigEndian(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Holds back the start of the file until its tag section has passed, drops the old tags and
     * writes ours in their place; the audio then streams straight through.
     */
    private final class Rewriter extends OutputStream {
        private static final int LIMIT = 32 * 1024 * 1024;
        private final OutputStream out;
        private final boolean flac;
        private ByteArrayOutputStream head = new ByteArrayOutputStream();

        Rewriter(OutputStream out, boolean flac) {
            this.out = out;
            this.flac = flac;
        }

        @Override public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (head == null) {
                out.write(data, offset, length);
                return;
            }
            head.write(data, offset, length);
            if (head.size() > LIMIT) throw new IOException("音频文件头过大");
            byte[] start = head.toByteArray();
            int end = flac ? flacEnd(start) : id3End(start);
            if (end == NEED_MORE) return;
            head = null;
            if (end == NOT_TAGGABLE) {
                out.write(start);
                return;
            }
            if (flac) {
                out.write(new byte[]{'f', 'L', 'a', 'C'});
                out.write(keptFlacBlocks(start, end));
                out.write(flacBlocks());
            } else {
                out.write(id3());
            }
            out.write(start, end, start.length - end);
        }

        @Override public void flush() throws IOException {
            if (head == null) out.flush();
        }

        @Override public void close() throws IOException {
            if (head != null) {
                out.write(head.toByteArray());
                head = null;
            }
            out.close();
        }
    }

    private static final int NEED_MORE = -1;
    private static final int NOT_TAGGABLE = -2;

    /** End of the FLAC metadata blocks, once all of them have arrived. */
    private static int flacEnd(byte[] data) {
        if (data.length < 4) return NEED_MORE;
        if (data[0] != 'f' || data[1] != 'L' || data[2] != 'a' || data[3] != 'C') return NOT_TAGGABLE;
        int position = 4;
        while (true) {
            if (data.length < position + 4) return NEED_MORE;
            int flags = data[position] & 0xff;
            int length = ((data[position + 1] & 0xff) << 16) | ((data[position + 2] & 0xff) << 8) | (data[position + 3] & 0xff);
            if (data.length < position + 4 + length) return NEED_MORE;
            position += 4 + length;
            if ((flags & 0x80) != 0) return position;
        }
    }

    /** STREAMINFO, seek table, application and cue sheet blocks; old tags, pictures and padding go. */
    private static byte[] keptFlacBlocks(byte[] data, int end) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int position = 4;
        while (position < end) {
            int type = data[position] & 0x7f;
            int length = ((data[position + 1] & 0xff) << 16) | ((data[position + 2] & 0xff) << 8) | (data[position + 3] & 0xff);
            if (type == 0 || type == 2 || type == 3 || type == 5) {
                kept.write(type); // not last: our blocks follow
                kept.write(data, position + 1, 3 + length);
            }
            position += 4 + length;
        }
        return kept.toByteArray();
    }

    /** End of an existing ID3v2 tag (0 when the file has none), once it has arrived. */
    private static int id3End(byte[] data) {
        if (data.length < 10) return NEED_MORE;
        if (data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        int size = ((data[6] & 0x7f) << 21) | ((data[7] & 0x7f) << 14) | ((data[8] & 0x7f) << 7) | (data[9] & 0x7f);
        int end = 10 + size + ((data[5] & 0x10) != 0 ? 10 : 0);
        return data.length < end ? NEED_MORE : end;
    }

    /**
     * Whether a saved song already carries an album tag and an embedded cover, read from the start
     * of the file. Unknown formats count as tagged, so they are never downloaded again for this.
     */
    static boolean complete(InputStream input) throws IOException {
        byte[] start = readUpTo(input, 4);
        if (start.length < 4) return false;
        if (start[0] == 'f' && start[1] == 'L' && start[2] == 'a' && start[3] == 'C') {
            boolean album = false;
            boolean picture = false;
            while (true) {
                byte[] header = readUpTo(input, 4);
                if (header.length < 4) return false;
                int type = header[0] & 0x7f;
                int length = ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
                if (type == 4) {
                    String comments = new String(readUpTo(input, length), StandardCharsets.UTF_8).toUpperCase(java.util.Locale.ROOT);
                    album = comments.contains("ALBUM=");
                } else {
                    if (type == 6) picture = true;
                    skip(input, length);
                }
                if ((header[0] & 0x80) != 0) return album && picture;
            }
        }
        if (start[0] == 'I' && start[1] == 'D' && start[2] == '3') {
            byte[] rest = readUpTo(input, 6);
            if (rest.length < 6) return false;
            int size = ((rest[2] & 0x7f) << 21) | ((rest[3] & 0x7f) << 14) | ((rest[4] & 0x7f) << 7) | (rest[5] & 0x7f);
            String frames = new String(readUpTo(input, Math.min(size, LIMIT_READ)), StandardCharsets.ISO_8859_1);
            return frames.contains("TALB") && frames.contains("APIC");
        }
        // An MP3 without an ID3 tag starts with a frame sync; other formats are left alone.
        return !((start[0] & 0xff) == 0xff && (start[1] & 0xe0) == 0xe0);
    }

    private static final int LIMIT_READ = 32 * 1024 * 1024;

    private static byte[] readUpTo(InputStream input, int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (out.size() < count) {
            int read = input.read(buffer, 0, Math.min(buffer.length, count - out.size()));
            if (read < 0) break;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void skip(InputStream input, long count) throws IOException {
        while (count > 0) {
            long skipped = input.skip(count);
            if (skipped <= 0) {
                if (input.read() < 0) return;
                skipped = 1;
            }
            count -= skipped;
        }
    }
}
