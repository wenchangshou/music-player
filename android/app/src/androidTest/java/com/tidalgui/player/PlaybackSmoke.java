package com.tidalgui.player;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@UnstableApi
public final class PlaybackSmoke extends Instrumentation {
    private PlaybackService playback;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity = (MainActivity) startActivitySync(launch);
            CountDownLatch bound = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    playback = ((PlaybackService.LocalBinder) service).service();
                    bound.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) {}
            };
            context.bindService(new Intent(context, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound.await(8, TimeUnit.SECONDS)) throw new AssertionError("Playback service disconnected");

            File audio = new File(context.getCacheDir(), "background-smoke.wav");
            int samples = 8000 * 40;
            ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0x46464952).putInt(36 + samples * 2).putInt(0x45564157).putInt(0x20746d66);
            data.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000);
            data.putShort((short) 2).putShort((short) 16).putInt(0x61746164).putInt(samples * 2);
            try (FileOutputStream out = new FileOutputStream(audio)) { out.write(data.array()); }

            runOnMainSync(() -> {
                playback.player.setMediaItem(new MediaItem.Builder().setUri(Uri.fromFile(audio))
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle("Background smoke test").build()).build());
                playback.player.prepare();
                playback.player.play();
            });
            Thread.sleep(2400);

            boolean[] playing = {false};
            runOnMainSync(() -> {
                playing[0] = playback.player.isPlaying() && playback.isPlaybackOngoing();
                activity.moveTaskToBack(true);
            });
            if (!playing[0]) throw new AssertionError("Playback did not become a foreground media session");
            Thread.sleep(700);

            long[] position = {-1};
            runOnMainSync(() -> {
                if (playback.player.isPlaying()) position[0] = playback.player.getCurrentPosition();
            });
            if (position[0] <= 0) throw new AssertionError("Audio stopped in background");

            getUiAutomation().executeShellCommand("cmd media_session dispatch pause").close();
            Thread.sleep(650);
            runOnMainSync(() -> playing[0] = playback.player.isPlaying());
            if (playing[0]) throw new AssertionError("Media session pause did not reach service");

            getUiAutomation().executeShellCommand("cmd media_session dispatch play").close();
            Thread.sleep(650);
            runOnMainSync(() -> playing[0] = playback.player.isPlaying());
            if (!playing[0]) throw new AssertionError("Media session play did not reach service");

            runOnMainSync(() -> {
                playback.player.stop();
                context.unbindService(connection);
                activity.finish();
            });
            audio.delete();
            result.putString("stream", "Verified playback in background and Android media pause/resume.");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }
}
