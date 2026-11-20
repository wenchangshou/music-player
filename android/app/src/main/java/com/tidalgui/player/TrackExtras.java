package com.tidalgui.player;

import android.app.AlertDialog;
import android.view.View;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Part of {@link MainActivity}: TrackExtras. */
@UnstableApi
final class TrackExtras {
    final MainActivity ui;

    TrackExtras(MainActivity ui) {
        this.ui = ui;
    }


    void localTrackMenu(TidalClient.Track track) {
        if (ui.playback == null) return;
        String playlist = ui.page == Page.LOCAL ? ui.localPlaylist : null;
        String remove = playlist == null ? "从音乐库移除" : "从歌单移除";
        String[] options = {"下一首播放", "追加到队列", "加入本地歌单", "下载/匹配歌词", remove};
        new AlertDialog.Builder(ui).setTitle(track.title)
                .setItems(options, (dialog, choice) -> {
                    if (ui.playback == null) return;
                    if (choice == 0) ui.playback.enqueue(track, true);
                    else if (choice == 1) ui.playback.enqueue(track, false);
                    else if (choice == 2) {
                        List<String> names = ui.playback.localLibrary.playlists();
                        names.add("新建歌单…");
                        new AlertDialog.Builder(ui).setTitle("加入歌单").setItems(names.toArray(new String[0]),
                                (picker, index) -> {
                                    if (ui.playback == null) return;
                                    if (index == names.size() - 1) ui.localScreens.newLocalPlaylist(track);
                                    else {
                                        ui.playback.localLibrary.add(names.get(index), track);
                                        ui.playback.libraryChanged();
                                        ui.toast("已加入歌单");
                                    }
                                }).show();
                    } else if (choice == 3) {
                        downloadLyricsDialog(track);
                    } else {
                        ui.playback.localLibrary.remove(playlist, track.id);
                        if (playlist == null) ui.playback.fileDeleted(track.id);
                        else ui.playback.libraryChanged();
                        ui.toast("已从列表移除，文件仍保留");
                        return;
                    }
                    if (choice < 2) ui.toast(ui.remoteScreens.remoteMode() ? "已加入本机队列，本地歌曲只能在本机播放" : "已加入播放队列");
                }).show();
    }

    void downloadLyricsDialog(TidalClient.Track track) {
        if (ui.playback == null || track == null) return;
        ui.toast("正在检索歌词…");
        ui.worker.submit(() -> {
            try {
                LyricsFill fill = LyricsFill.get(ui.playback);
                List<LyricsFill.Candidate> candidates = fill.candidates(track);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed()) return;
                    if (candidates.isEmpty()) {
                        new AlertDialog.Builder(ui)
                                .setTitle("未找到歌词")
                                .setMessage("在 TIDAL 和网易云中未找到「" + track.title + " - " + track.artist + "」的歌词。\n\n请确认歌曲标题与艺人标签是否准确。")
                                .setPositiveButton("确定", null)
                                .show();
                        return;
                    }
                    List<String> labels = new ArrayList<>();
                    for (LyricsFill.Candidate c : candidates) {
                        String durStr = "";
                        long dur = DownloadTasks.length(c.track);
                        if (dur > 0) durStr = " · " + UiKit.formatTime(dur * 1000);
                        String matchTag = c.match ? " ★精准匹配" : "";
                        labels.add("[" + c.source + "] " + c.track.title + " - " + c.track.artist + durStr + matchTag);
                    }
                    new AlertDialog.Builder(ui)
                            .setTitle("选择要下载的歌词 (" + candidates.size() + ")")
                            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                                LyricsFill.Candidate picked = candidates.get(which);
                                savePickedLyrics(track, picked);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (!ui.isDestroyed()) ui.toast("搜索歌词失败：" + UiKit.readable(error));
                });
            }
        });
    }

    void savePickedLyrics(TidalClient.Track track, LyricsFill.Candidate picked) {
        if (ui.playback == null) return;
        ui.toast("正在下载歌词…");
        ui.worker.submit(() -> {
            try {
                LyricsFill fill = LyricsFill.get(ui.playback);
                JSONObject words = fill.fetch(picked.source, picked.track.id);
                String synced = words.optString("synced");
                String plain = words.optString("plain");
                String lrc = !synced.isEmpty() ? synced : plain;
                if (lrc.trim().isEmpty()) throw new IOException("该候选没有歌词文本");
                fill.save(track, lrc);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed()) return;
                    ui.toast("歌词已保存为同名 LRC 文件");
                    if (ui.playback != null && ui.playback.current() != null && ui.playback.current().id == track.id) {
                        if (ui.lyricsMode) showLyrics();
                    }
                    ui.render();
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (!ui.isDestroyed()) ui.toast("保存歌词失败：" + UiKit.readable(error));
                });
            }
        });
    }

    void showBatchLyricsFill() {
        if (ui.playback == null) return;
        int without = ui.playback.localLibrary.withoutLyrics().size();
        if (without == 0) {
            ui.toast("所有本地歌曲都已有歌词文件");
            return;
        }
        new AlertDialog.Builder(ui)
                .setTitle("批量补全歌词")
                .setMessage("当前共有 " + without + " 首本地歌曲缺少同名 .lrc 歌词文件。\n\n是否从 TIDAL 与网易云自动搜索并下载保存为同名 .lrc？")
                .setPositiveButton("开始补全", (dialog, which) -> {
                    try {
                        LyricsFill.get(ui.playback).start(null, false);
                        ui.toast("已启动后台歌词补全，匹配成功后自动落盘保存");
                    } catch (IOException err) {
                        ui.toast(err.getMessage());
                    }
                })
                .setNeutralButton("重试所有未找到", (dialog, which) -> {
                    try {
                        LyricsFill.get(ui.playback).start(null, true);
                        ui.toast("已重新尝试补全所有无歌词歌曲");
                    } catch (IOException err) {
                        ui.toast(err.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void playRadio(TidalClient.Track track) {
        TidalClient api = ui.remoteScreens.tidalApi();
        if (api == null) return;
        ui.toast("正在加载歌曲电台");
        ui.worker.submit(() -> {
            try {
                List<TidalClient.Track> mix = api.radio(track.id);
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || ui.playback == null || ui.remoteScreens.tidalApi() != api) return;
                    if (mix.isEmpty()) ui.toast("暂无电台歌曲");
                    else ui.catalogueScreens.playList(mix, 0, false);
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (!ui.isDestroyed() && ui.playback != null && ui.remoteScreens.tidalApi() == api) ui.toast(UiKit.readable(error));
                });
            }
        });
    }

    /** Switch the Now Playing stage between the cover and full-width lyrics. */
    void toggleLyrics() {
        if (ui.lyricsMode) {
            closeLyrics();
            return;
        }
        ui.lyricsMode = true;
        showLyrics();
        if (ui.lyrics == null) ui.lyricsMode = false;
    }

    void closeLyrics() {
        ui.lyricsMode = false;
        ui.lyrics = null;
        if (ui.lyricsPane != null) {
            ui.lyricsPane.removeAllViews();
            ui.lyricsPane.setVisibility(View.GONE);
        }
        if (ui.cover != null) ui.cover.setVisibility(View.VISIBLE);
    }

    /** Put lyrics for {@code track} in place of the cover; they reload when the track changes. */
    LyricsDialog inlineLyrics(TidalClient.Track track, LyricsDialog.Seek seek) {
        LyricsDialog[] self = new LyricsDialog[1];
        LyricsDialog view = LyricsDialog.inline(ui, track, seek, this::closeLyrics, () -> {
            // The track changed: stop syncing this view, then load the new track's lyrics once.
            if (ui.lyrics != self[0]) return;
            ui.lyrics = null;
            ui.handler.post(() -> {
                if (!ui.lyricsMode || ui.lyrics != null) return;
                showLyrics();
                if (ui.lyrics == null) closeLyrics();
            });
        });
        self[0] = view;
        ui.lyrics = view;
        ui.lyricsPane.removeAllViews();
        ui.lyricsPane.addView(view.view(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        ui.lyricsPane.setVisibility(View.VISIBLE);
        ui.cover.setVisibility(View.INVISIBLE);
        return view;
    }

    void showLyrics() {
        ui.lyrics = null;
        if (ui.lyricsPane == null) return;
        if (ui.remoteScreens.remoteMode()) {
            ui.nowPlaying.showRemoteLyrics();
            return;
        }
        if (ui.playback == null || ui.playback.current() == null) return;
        TidalClient.Track track = ui.playback.current();
        TidalClient api = ui.playback.client;
        LocalLibrary library = ui.playback.localLibrary;
        if (track.needsTidal() && api == null) return;
        NeteaseClient netease = ui.playback.netease();
        LyricsDialog view = inlineLyrics(track, millis -> {
            if (ui.playback != null && ui.playback.current() != null && ui.playback.current().id == track.id
                    && !ui.playback.loading && ui.playback.player.isCurrentMediaItemSeekable()) {
                ui.playback.player.seekTo(millis);
            }
        });
        ui.worker.submit(() -> {
            try {
                org.json.JSONObject data = track.isLocal()
                        ? LocalMusicFiles.lyrics(ui.getContentResolver(), library.latest(track)) : track.isNetease() ? netease.lyrics(track.id) : api.lyrics(track.id);
                ui.runOnUiThread(() -> {
                    if (ui.lyrics == view) view.display(data);
                });
            } catch (Exception error) {
                ui.runOnUiThread(() -> {
                    if (ui.lyrics == view) {
                        if (track.isLocal()) {
                            view.unavailable(UiKit.readable(error), () -> downloadLyricsDialog(track));
                        } else {
                            view.unavailable(UiKit.readable(error));
                        }
                    }
                });
            }
        });
    }

}
