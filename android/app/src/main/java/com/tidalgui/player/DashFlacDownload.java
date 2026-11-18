package com.tidalgui.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

@androidx.media3.common.util.UnstableApi
final class DashFlacDownload {
    private static final long MAX_BYTES = 1024L * 1024 * 1024;

    static Representation select(byte[] manifest) throws IOException {
        DashManifest dash = parse(manifest);
        Representation chosen = null;
        for (AdaptationSet group : dash.getPeriod(0).adaptationSets) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue;
            for (Representation candidate : group.representations) {
                if (!"audio/flac".equals(candidate.format.sampleMimeType) || candidate.format.drmInitData != null
                        || candidate.getInitializationUri() == null || candidate.getIndex() == null) continue;
                if (chosen == null || candidate.format.bitrate > chosen.format.bitrate) chosen = candidate;
            }
        }
        if (chosen == null) throw new DownloadTasks.QualityUnavailable("该分段音源没有可保存的明文 FLAC 音轨");
        String base = chosen.baseUrls.get(0).url;
        checkedUrl(chosen.getInitializationUri().resolveUri(base));
        return chosen;
    }

    private static DashManifest parse(byte[] manifest) throws IOException {
        // A static manifest from the authenticated TIDAL API with absolute HTTPS segment URLs.
        DashManifest result = new DashManifestParser().parse(
                Uri.parse("https://api.tidal.com/"), new ByteArrayInputStream(manifest));
        if (result.dynamic || result.getPeriodCount() != 1) {
            throw new DownloadTasks.QualityUnavailable("暂不支持动态或多段 DASH 下载");
        }
        return result;
    }

    static void save(File cache, byte[] manifest, OutputStream target, MusicDownload.Progress progress) throws IOException {
        DashManifest dash = parse(manifest);
        Representation track = select(manifest);
        String baseUrl = track.baseUrls.get(0).url;
        DashSegmentIndex index = track.getIndex();
        long count = index.getSegmentCount(dash.getPeriodDurationUs(0));
        if (count < 1 || count > 10_000) throw new IOException("音源分段数量无效");
        File fragments = File.createTempFile("tidal-flac-", ".mp4", cache);
        try {
            try (OutputStream output = new FileOutputStream(fragments)) {
                long bytes = copy(track.getInitializationUri(), baseUrl, output, 0, progress);
                long first = index.getFirstSegmentNum();
                for (long i = 0; i < count; i++) {
                    bytes = copy(index.getSegmentUrl(first + i), baseUrl, output, bytes, progress);
                }
            }
            extract(fragments, target);
        } finally {
            fragments.delete();
        }
    }

    private static Uri checkedUrl(Uri uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("无效的音频分段地址");
        }
        return uri;
    }

    private static long copy(RangedUri range, String base, OutputStream output, long bytes,
                             MusicDownload.Progress progress) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
        Uri uri = checkedUrl(range.resolveUri(base));
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        try {
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            boolean ranged = range.start != 0 || range.length != C.LENGTH_UNSET;
            if (ranged) {
                String end = range.length == C.LENGTH_UNSET ? "" : Long.toString(range.start + range.length - 1);
                connection.setRequestProperty("Range", "bytes=" + range.start + "-" + end);
            }
            int status = connection.getResponseCode();
            if (status != (ranged ? 206 : 200)) throw new IOException("音频分段下载失败（HTTP " + status + "）");
            long expected = range.length;
            if (expected == C.LENGTH_UNSET) {
                String length = connection.getHeaderField("Content-Length");
                if (length != null) expected = Long.parseLong(length);
            }
            long before = bytes;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    bytes += read;
                    if (bytes > MAX_BYTES || Thread.currentThread().isInterrupted()) throw new IOException("下载已取消或文件过大");
                    output.write(buffer, 0, read);
                    progress.transferred(bytes, -1);
                }
            }
            if (bytes == before || (expected >= 0 && bytes - before != expected)) throw new IOException("音频分段不完整");
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    static void extract(File fragments, OutputStream target) throws IOException {
        FlacOutput output = new FlacOutput(target);
        FragmentedMp4Extractor extractor = new FragmentedMp4Extractor();
        extractor.init(output);
        PositionHolder seek = new PositionHolder();
        try (RandomAccessFile data = new RandomAccessFile(fragments, "r")) {
            DefaultExtractorInput input = new DefaultExtractorInput(data::read, 0, data.length());
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                int result = extractor.read(input, seek);
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (result == Extractor.RESULT_SEEK) {
                    data.seek(seek.position);
                    input = new DefaultExtractorInput(data::read, seek.position, data.length());
                }
            }
            if (!output.header || output.samples == 0) throw new IOException("未读到 FLAC 音频帧");
        } catch (TransferError error) {
            throw error.failure;
        } finally {
            extractor.release();
        }
    }

    private static final class TransferError extends RuntimeException {
        final IOException failure;

        TransferError(IOException failure) {
            super(failure);
            this.failure = failure;
        }
    }

    private static final class FlacOutput implements ExtractorOutput, TrackOutput {
        private final OutputStream output;
        private final byte[] buffer = new byte[64 * 1024];
        private boolean header;
        private int samples;

        FlacOutput(OutputStream output) {
            this.output = output;
        }

        @Override public TrackOutput track(int id, int type) {
            if (type != C.TRACK_TYPE_AUDIO) throw invalid("下载清单包含非预期的音轨");
            return this;
        }

        @Override public void endTracks() {}
        @Override public void seekMap(SeekMap map) {}

        @Override public void format(Format format) {
            if (format.drmInitData != null || !"audio/flac".equals(format.sampleMimeType)
                    || format.initializationData.isEmpty()) throw invalid("找不到明文 FLAC 配置");
            if (header) return;
            byte[] info = format.initializationData.get(0);
            if (info.length < 42 || info[0] != 'f' || info[1] != 'L' || info[2] != 'a' || info[3] != 'C'
                    || (info[4] & 127) != 0 || info[5] != 0 || info[6] != 0 || info[7] != 34) {
                throw invalid("FLAC STREAMINFO 无效");
            }
            try {
                output.write(new byte[]{'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34});
                output.write(info, 8, 34);
                header = true;
            } catch (IOException error) {
                throw new TransferError(error);
            }
        }

        @Override public int sampleData(DataReader input, int size, boolean allowEndOfInput, int part) throws IOException {
            if (!header || part != SAMPLE_DATA_PART_MAIN) throw new IOException("音频帧格式无效");
            int read = input.read(buffer, 0, Math.min(size, buffer.length));
            if (read == -1 && !allowEndOfInput) throw new EOFException();
            if (read > 0) output.write(buffer, 0, read);
            return read;
        }

        @Override public void sampleData(ParsableByteArray input, int size, int part) {
            if (!header || part != SAMPLE_DATA_PART_MAIN) throw invalid("音频帧格式无效");
            try {
                output.write(input.getData(), input.getPosition(), size);
                input.skipBytes(size);
            } catch (IOException error) {
                throw new TransferError(error);
            }
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            if (crypto != null) throw invalid("音源分段已加密");
            samples++;
        }

        private static TransferError invalid(String reason) {
            return new TransferError(new IOException(reason));
        }
    }
}
