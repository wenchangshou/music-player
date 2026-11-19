package com.tidalgui.player;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

import java.io.EOFException;
import java.io.IOException;

/**
 * ExoPlayer DataSource that streams audio files directly from an SMB share
 * with instant seek capability via {@link SmbRandomAccessFile}.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class SmbDataSource extends BaseDataSource {

    public static class Factory implements DataSource.Factory {
        private final CIFSContext context;

        public Factory(CIFSContext context) {
            this.context = context;
        }

        @Override
        public DataSource createDataSource() {
            return new SmbDataSource(context);
        }
    }

    private final CIFSContext context;
    private DataSpec dataSpec;
    private SmbFile file;
    private SmbRandomAccessFile raf;
    private long bytesRemaining;
    private boolean opened;

    public SmbDataSource(CIFSContext context) {
        super(/* isNetwork= */ true);
        this.context = context;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        transferInitializing(dataSpec);
        try {
            String uriStr = dataSpec.uri.toString();
            file = new SmbFile(uriStr, context);
            raf = new SmbRandomAccessFile(file, "r");
            long totalLength = file.length();
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position);
            }
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = dataSpec.length;
            } else {
                bytesRemaining = totalLength >= 0 ? (totalLength - dataSpec.position) : C.LENGTH_UNSET;
            }
        } catch (Exception e) {
            throw new IOException("Failed to open SMB stream: " + dataSpec.uri, e);
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

        int bytesToRead = bytesRemaining == C.LENGTH_UNSET ? length : (int) Math.min(length, bytesRemaining);
        int bytesRead;
        try {
            bytesRead = raf.read(buffer, offset, bytesToRead);
        } catch (Exception e) {
            throw new IOException("Error reading from SMB stream: " + (dataSpec != null ? dataSpec.uri : ""), e);
        }

        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET) {
                throw new EOFException("Unexpected EOF on SMB stream");
            }
            return C.RESULT_END_OF_INPUT;
        }

        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
        }
        bytesTransferred(bytesRead);
        return bytesRead;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return dataSpec != null ? dataSpec.uri : null;
    }

    @Override
    public void close() throws IOException {
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        } finally {
            raf = null;
            file = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
