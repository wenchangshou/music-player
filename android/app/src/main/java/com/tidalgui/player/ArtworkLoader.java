package com.tidalgui.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class ArtworkLoader {
    private static final long DISK_LIMIT = 48 * 1024 * 1024;
    private static final long MISSING_RETRY_MS = 5 * 60 * 1000;
    private final Context context;
    private final File thumbnails;
    private final ExecutorService localRows = Executors.newFixedThreadPool(2);
    private final ExecutorService localDetails = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger diskWrites = new AtomicInteger();
    private final Map<String, List<ImageView>> pending = new HashMap<>();
    private final LruCache<String, Long> missing = new LruCache<>(1024);
    /** Name used to draw the generated cover when an image has no artwork. */
    private final java.util.WeakHashMap<ImageView, String> seeds = new java.util.WeakHashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    ArtworkLoader(Context context) {
        this.context = context.getApplicationContext();
        thumbnails = new File(context.getCacheDir(), "local-artwork-v1");
    }

    void load(ImageView image, String cover, int size) {
        load(image, TidalClient.artwork(cover, size), size, "");
    }

    /** Like {@link #load(ImageView, String, int)}, with {@code name} for the generated fallback. */
    void load(ImageView image, String cover, int size, String name) {
        seed(image, name);
        load(image, cover, size);
    }

    void seed(ImageView image, String name) {
        String value = name == null ? "" : name;
        // An image already showing the generated cover is redrawn for its new name.
        if (!value.equals(seeds.put(image, value)) && "".equals(image.getTag())) fallback(image);
    }

    void load(ImageView image, TidalClient.Track track, int size) {
        if (track != null) seed(image, track.album.isEmpty() ? track.title : track.album);
        else seed(image, "");
        loadTrack(image, track, size);
    }

    /** Track artwork that falls back to the photo instead of a generated cover (large banners). */
    void loadPhoto(ImageView image, TidalClient.Track track, int size) {
        seed(image, "");
        loadTrack(image, track, size);
    }

    private void loadTrack(ImageView image, TidalClient.Track track, int size) {
        if (track != null && track.isLocal()) {
            String revision = track.payload.optLong("scan_modified") + ":" + track.payload.optLong("scan_size");
            load(image, track.localUri, size, revision);
        } else {
            load(image, track == null ? "" : track.cover, size);
        }
    }

    private void load(ImageView image, Uri uri, int size, String revision) {
        if (uri == null) {
            if (!"".equals(image.getTag())) {
                image.setTag("");
                fallback(image);
            }
            return;
        }
        String key = uri + "\n" + size + "\n" + revision;
        if (key.equals(image.getTag())) return;
        image.setTag(key);
        Bitmap existing = cache.get(key);
        if (existing != null) {
            image.setImageBitmap(existing);
            return;
        }
        fallback(image);
        Long retryAfter = missing.get(key);
        if (retryAfter != null && retryAfter > SystemClock.elapsedRealtime()) return;
        List<ImageView> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(image);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(image);
        pending.put(key, waiting);
        boolean local = "content".equals(uri.getScheme());
        ExecutorService executor = local ? (size <= 160 ? localRows : localDetails) : network;
        executor.submit(() -> {
            Bitmap bitmap = local ? localThumbnail(uri, size, key) : remoteArtwork(uri);
            main.post(() -> {
                List<ImageView> targets = pending.remove(key);
                if (targets == null) return;
                if (bitmap == null) {
                    missing.put(key, SystemClock.elapsedRealtime() + MISSING_RETRY_MS);
                    return;
                }
                cache.put(key, bitmap);
                for (ImageView target : targets) {
                    if (key.equals(target.getTag())) target.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void fallback(ImageView image) {
        android.view.ViewGroup.LayoutParams layout = image.getLayoutParams();
        boolean large = layout == null || layout.width < 0
                || layout.width >= 150 * context.getResources().getDisplayMetrics().density;
        String name = seeds.get(image);
        if (name == null || name.isEmpty()) {
            image.setImageResource(large ? R.drawable.listening_room : R.drawable.player_icon);
        } else {
            image.setImageDrawable(new FallbackArt(name));
        }
    }

    private Bitmap localThumbnail(Uri uri, int size, String key) {
        File file = thumbnailFile(key);
        if (file != null && file.isFile()) {
            if (file.length() == 0 && System.currentTimeMillis() - file.lastModified() < MISSING_RETRY_MS) return null;
            Bitmap saved = BitmapFactory.decodeFile(file.getPath());
            if (saved != null) {
                file.setLastModified(System.currentTimeMillis());
                return saved;
            }
        }
        android.media.MediaMetadataRetriever reader = new android.media.MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            byte[] cover = reader.getEmbeddedPicture();
            Bitmap bitmap = cover == null || cover.length > 16 * 1024 * 1024 ? null : thumbnail(cover, size);
            if (file != null) saveThumbnail(file, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { reader.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * The embedded cover of a local file as a small JPEG for other devices, or null when the file
     * has none. Kept under {@code limit} bytes by lowering the quality.
     */
    static byte[] localJpeg(Context context, Uri uri, int size, int limit) {
        android.media.MediaMetadataRetriever reader = new android.media.MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            byte[] cover = reader.getEmbeddedPicture();
            Bitmap bitmap = cover == null || cover.length > 16 * 1024 * 1024 ? null : thumbnail(cover, size);
            if (bitmap == null) return null;
            for (int quality = 85; quality >= 40; quality -= 15) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                if (out.size() <= limit) return out.toByteArray();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { reader.release(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap thumbnail(byte[] cover, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(cover, 0, cover.length, options);
        int maximum = Math.max(options.outWidth, options.outHeight);
        if (maximum <= 0) return null;
        options.inSampleSize = 1;
        while (maximum / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.length, options);
        if (bitmap == null) return null;
        int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (edge <= size) return bitmap;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, bitmap.getWidth() * size / edge),
                Math.max(1, bitmap.getHeight() * size / edge), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private File thumbnailFile(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return new File(thumbnails, android.util.Base64.encodeToString(digest,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING) + ".art");
        } catch (java.security.NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    private void saveThumbnail(File file, Bitmap bitmap) {
        File temporary = null;
        try {
            if (!thumbnails.isDirectory() && !thumbnails.mkdirs() && !thumbnails.isDirectory()) return;
            temporary = File.createTempFile("artwork-", ".tmp", thumbnails);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (bitmap != null && !bitmap.compress(bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG
                        : Bitmap.CompressFormat.JPEG, 86, output)) return;
            }
            if (temporary.renameTo(file) && diskWrites.getAndIncrement() % 32 == 0) trimThumbnails();
        } catch (Exception ignored) {
            // Disk caching is optional when storage is unavailable.
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private synchronized void trimThumbnails() {
        File[] files = thumbnails.listFiles((directory, name) -> name.endsWith(".art"));
        if (files == null) return;
        long size = 0;
        for (File file : files) size += file.length();
        if (size <= DISK_LIMIT && files.length <= 2400) return;
        Arrays.sort(files, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        int count = files.length;
        for (File file : files) {
            if (size <= DISK_LIMIT && count <= 2400) break;
            long length = file.length();
            if (file.delete()) {
                size -= length;
                count--;
            }
        }
    }

    private Bitmap remoteArtwork(Uri uri) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void close() {
        localRows.shutdownNow();
        localDetails.shutdownNow();
        network.shutdownNow();
        pending.clear();
    }
}
