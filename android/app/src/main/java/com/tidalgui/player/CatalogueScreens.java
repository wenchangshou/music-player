package com.tidalgui.player;

import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: CatalogueScreens. */
@UnstableApi
final class CatalogueScreens {
    final MainActivity ui;

    CatalogueScreens(MainActivity ui) {
        this.ui = ui;
    }

    static final int PAGE_SIZE = 50;
    ArtistDetails artistDetails;
    boolean showAllArtistTracks;
    int growTotal;
    boolean hasMore;

    void catalogue() {
        if (ui.page != Page.COLLECTION) {
            LinearLayout chips = ui.row();
            String[] names = ui.page == Page.LIBRARY
                    ? new String[]{"歌曲", "专辑", "歌单", "艺人", "自建"}
                    : new String[]{"歌曲", "专辑", "歌单", "艺人"};
            for (int i = 0; i < names.length; i++) {
                int selected = i;
                Button chip = ui.action(names[i], names[i], () -> {
                    if (ui.page == Page.LIBRARY) ui.libraryFilter = selected;
                    else ui.filter = selected;
                    load(false);
                });
                if (i == (ui.page == Page.LIBRARY ? ui.libraryFilter : ui.filter)) chip.setBackground(ui.shape(RAISED, 18));
                chips.addView(chip);
            }
            ui.content.addView(chips);
        } else {
            artHeader(artistDetails == null ? ui.collection : artistDetails.artist);
        }

        if (ui.catalogueLoading && ui.tracks.isEmpty() && ui.collections.isEmpty()) {
            ui.empty("正在加载…", "");
        } else if (!ui.notice.isEmpty()) {
            ui.empty(ui.notice, "");
            if (ui.notice.startsWith("加载失败")) ui.content.addView(ui.action("重试", "重试", () -> load(false)));
        } else if (artistDetails != null) {
            if (!ui.tracks.isEmpty()) {
                ui.section("热门歌曲");
                showTracks(new ArrayList<>(ui.tracks), false, showAllArtistTracks ? Integer.MAX_VALUE : 8);
                if (ui.tracks.size() > 8) {
                    String label = showAllArtistTracks ? "收起热门歌曲" : "查看全部热门歌曲";
                    ui.content.addView(ui.action(label, label, () -> {
                        showAllArtistTracks = !showAllArtistTracks;
                        ui.render();
                    }));
                }
            }
            artistSection("专辑", artistDetails.albums);
            artistSection("单曲与 EP", artistDetails.singles);
            artistSection("相似艺人", artistDetails.similar);
            if (!artistDetails.biography.isEmpty()) {
                ui.section("艺人简介");
                TextView biography = ui.text(artistDetails.biography, 15, INK, false);
                biography.setMaxLines(5);
                biography.setEllipsize(android.text.TextUtils.TruncateAt.END);
                ui.content.addView(biography);
                ui.content.addView(ui.action("阅读简介", "阅读艺人简介", () -> new AlertDialog.Builder(ui)
                        .setTitle(artistDetails.artist.title).setMessage(artistDetails.biography)
                        .setPositiveButton("关闭", null).show()));
            }
            if (ui.tracks.isEmpty() && artistDetails.albums.isEmpty() && artistDetails.singles.isEmpty()
                    && artistDetails.similar.isEmpty() && artistDetails.biography.isEmpty()) {
                ui.empty("暂无艺人作品", "");
            }
        } else if (!ui.tracks.isEmpty() || !ui.collections.isEmpty()) {
            if (!ui.tracks.isEmpty()) showTracks(new ArrayList<>(ui.tracks), ui.page == Page.LIBRARY);
            if (ui.page == Page.COLLECTION && ui.collection.kind == TidalClient.Collection.Kind.ARTIST
                    && !ui.collections.isEmpty()) ui.section("专辑");
            for (TidalClient.Collection item : ui.collections) collectionRow(item);
        } else if (ui.page == Page.SEARCH && ui.term.isEmpty()) {
            ui.empty("搜索音乐", "输入歌曲名、艺人名，或按类型查找专辑和歌单");
        } else {
            ui.empty("暂无内容", "");
        }
        if (hasMore) ui.content.addView(ui.action("加载更多", "加载更多", () -> load(true)));
    }

    void load(boolean append) {
        int request = ++ui.generation;
        if (ui.catalogue != null) ui.catalogue.cancel(true);
        if (!append) {
            ui.tracks.clear();
            ui.collections.clear();
            artistDetails = null;
            showAllArtistTracks = false;
        }
        ui.notice = "";
        hasMore = false;
        ui.catalogueLoading = false;
        if (ui.page == Page.SEARCH && ui.term.isEmpty()) {
            ui.render();
            return;
        }
        TidalClient api = ui.remoteScreens.tidalApi();
        boolean useNetease = ui.page == Page.SEARCH && ui.searchSource == SearchSource.NETEASE
                || ui.page == Page.COLLECTION && ui.collection.isNetease();
        NeteaseClient netease = ui.playback == null ? null : ui.playback.netease();
        if (useNetease && netease == null) {
            ui.notice = "播放器正在连接，请稍后重试";
            ui.render();
            return;
        }
        if (!useNetease && api == null) {
            ui.notice = "请先登录 TIDAL";
            ui.render();
            return;
        }
        Page requestedPage = ui.page;
        int requestedFilter = requestedPage == Page.LIBRARY ? ui.libraryFilter : ui.filter;
        String query = ui.term;
        TidalClient.Collection selected = ui.collection;
        boolean collectionList = requestedPage != Page.COLLECTION && requestedFilter != 0;
        int offset = collectionList ? ui.collections.size() : ui.tracks.size();
        ui.catalogueLoading = true;
        ui.render();
        ui.catalogue = ui.worker.submit(() -> {
            try {
                if (requestedPage == Page.COLLECTION && selected.kind == TidalClient.Collection.Kind.ARTIST
                        && !useNetease) {
                    ArtistDetails details = new ArtistDetails(api, selected);
                    ui.runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        artistDetails = details;
                        ui.tracks.addAll(details.top);
                        finishLoad(false);
                    });
                } else if (collectionList) {
                    TidalClient.Collection.Kind kind;
                    switch (requestedFilter) {
                        case 1: kind = TidalClient.Collection.Kind.ALBUM; break;
                        case 3: kind = TidalClient.Collection.Kind.ARTIST; break;
                        default: kind = TidalClient.Collection.Kind.PLAYLIST;
                    }
                    List<TidalClient.Collection> found = useNetease ? netease.collections(query, kind, offset) : api.collections(
                            requestedPage == Page.LIBRARY ? "" : query, kind, offset, requestedFilter == 4);
                    ui.runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        ui.collections.addAll(found);
                        finishLoad(found.size() == 40);
                    });
                } else {
                    List<TidalClient.Track> found;
                    List<TidalClient.Collection> releases = new ArrayList<>();
                    boolean artist = requestedPage == Page.COLLECTION && selected.kind == TidalClient.Collection.Kind.ARTIST;
                    if (requestedPage == Page.COLLECTION) {
                        found = useNetease ? netease.collectionTracks(selected, offset) : api.collectionTracks(selected, offset);
                        if (artist && offset == 0) {
                            try {
                                releases.addAll(netease.artistAlbums(selected.id));
                            } catch (Exception ignored) {
                                // The playable top tracks remain available if releases cannot be loaded.
                            }
                        }
                    } else if (requestedPage == Page.LIBRARY) {
                        found = api.favorites(offset);
                    } else {
                        found = useNetease ? netease.search(query, offset) : api.search(query, offset);
                    }
                    ui.runOnUiThread(() -> {
                        if (!current(request, requestedPage)) return;
                        ui.tracks.addAll(found);
                        ui.collections.addAll(releases);
                        if (requestedPage == Page.LIBRARY) {
                            for (TidalClient.Track track : found) {
                                ui.favoriteIds.add(track.id);
                            }
                        }
                        boolean paged = !artist && (!useNetease || requestedPage != Page.COLLECTION
                                || selected.kind == TidalClient.Collection.Kind.PLAYLIST);
                        finishLoad(paged && found.size() == PAGE_SIZE);
                    });
                }
            } catch (Exception e) {
                ui.runOnUiThread(() -> {
                    if (!current(request, requestedPage)) return;
                    ui.notice = "加载失败 · " + UiKit.readable(e);
                    finishLoad(false);
                });
            }
        });
    }

    boolean current(int request, Page requestedPage) {
        return !ui.isDestroyed() && ui.page == requestedPage && request == ui.generation;
    }

    void finishLoad(boolean more) {
        ui.catalogueLoading = false;
        hasMore = more;
        ui.render();
    }

    void artHeader(TidalClient.Collection item) {
        boolean artist = item.kind == TidalClient.Collection.Kind.ARTIST;
        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, ui.dp(4), 0, ui.dp(6));
        ImageView image = ui.art(140);
        if (artist) image.setBackground(ui.shape(RAISED, 70));
        ui.artwork.load(image, item.cover, 320, item.title);
        header.addView(image, new LinearLayout.LayoutParams(ui.dp(140), ui.dp(140)));
        LinearLayout info = ui.column();
        info.setPadding(ui.dp(16), 0, 0, 0);
        info.addView(ui.text(collectionKind(item), 12, ACCENT, true));
        TextView name = ui.text(item.title, 22, INK, true);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, ui.dp(2), 0, ui.dp(4));
        info.addView(name);
        if (!item.subtitle.isEmpty()) info.addView(ui.single(item.subtitle, 13, MUTED, false));
        if (item.kind == TidalClient.Collection.Kind.ALBUM && !item.isNetease()) {
            Button download = ui.withIcon(ui.secondary("下载专辑",
                    () -> ui.downloadScreens.chooseTidalDownload(item, java.util.Collections.emptyList())), "download", ACCENT);
            ui.downloadScreens.mark(() -> {
                int saved = ui.downloaded == null ? 0 : ui.downloaded.album(item);
                download.setText(saved > 0 ? "已下载 " + saved + " 首 · 继续下载" : "下载专辑");
            });
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, ui.dp(36));
            chip.topMargin = ui.dp(12);
            info.addView(download, chip);
        }
        header.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        ui.content.addView(header);
        if (item.kind == TidalClient.Collection.Kind.ALBUM) {
            String albumBy = item.albumArtist.isEmpty() ? item.subtitle : item.albumArtist;
            ui.aiScreens.albumInsight(albumBy, item.title, new ArrayList<>(ui.tracks));
        }
        if (item.kind != TidalClient.Collection.Kind.ARTIST || item.isNetease()) {
            ui.section(item.kind == TidalClient.Collection.Kind.ARTIST ? "热门歌曲"
                    : item.kind == TidalClient.Collection.Kind.PLAYLIST ? "歌单曲目"
                    : item.kind == TidalClient.Collection.Kind.MIX ? "混音曲目" : "专辑曲目");
        }
    }

    void artistSection(String title, List<TidalClient.Collection> items) {
        if (items.isEmpty()) return;
        ui.section(title);
        int preview = Math.min(6, items.size());
        for (int i = 0; i < preview; i++) collectionRow(items.get(i));
        if (items.size() > preview) {
            ui.content.addView(ui.action("查看全部 · " + title, "查看全部" + title, () -> {
                String[] names = new String[items.size()];
                for (int i = 0; i < names.length; i++) {
                    TidalClient.Collection item = items.get(i);
                    names[i] = item.subtitle.isEmpty() ? item.title : item.title + " · " + item.subtitle;
                }
                new AlertDialog.Builder(ui).setTitle(title).setItems(names, (dialog, index) ->
                        openCollection(items.get(index))).setNegativeButton("关闭", null).show();
            }));
        }
    }

    static String collectionKind(TidalClient.Collection item) {
        switch (item.kind) {
            case ARTIST: return "艺人";
            case PLAYLIST: return "歌单";
            case MIX: return "混音";
            default: return "专辑";
        }
    }

    void collectionRow(TidalClient.Collection item) {
        String type = collectionKind(item);
        String detail = item.subtitle.isEmpty() ? type : item.subtitle;
        LinearLayout row = ui.entry(item.title, detail, item.cover, () -> openCollection(item));
        TextView subtitle = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1);
        ui.downloadScreens.mark(() -> {
            int saved = item.kind == TidalClient.Collection.Kind.ALBUM && ui.downloaded != null ? ui.downloaded.album(item) : 0;
            subtitle.setText(saved > 0 ? "已下载 " + saved + " 首 · " + detail : detail);
            subtitle.setTextColor(saved > 0 ? ACCENT : MUTED);
        });
        ui.content.addView(row);
    }

    void openCollection(TidalClient.Collection item) {
        if (ui.page == Page.COLLECTION) {
            if (ui.collection.kind == item.kind && ui.collection.id.equals(item.id)) return;
            ui.collectionTrail.push(ui.collection);
            ui.collection = item;
            load(false);
        } else {
            ui.collectionTrail.clear();
            ui.collectionBack = ui.page;
            ui.collection = item;
            ui.open(Page.COLLECTION);
        }
    }

    void showTracks(List<TidalClient.Track> source, boolean favoriteList) {
        showTracks(source, favoriteList, Integer.MAX_VALUE);
    }

    void showTracks(List<TidalClient.Track> source, boolean favoriteList, int maximumVisible) {
        List<TidalClient.Track> downloadable = new ArrayList<>();
        for (TidalClient.Track track : source) {
            if (track.needsTidal()) downloadable.add(track);
        }
        Button all = ui.withIcon(ui.primary("全部播放", () -> playList(source, 0, false)), "play", BG);
        Button random = ui.withIcon(ui.secondary("随机", () -> playList(source, 0, true)), "shuffle", ACCENT);
        ui.content.addView(downloadable.size() > 1
                ? ui.toolbar(all, random, ui.withIcon(ui.secondary("多选下载", () -> ui.downloadScreens.selectTidalDownloads(downloadable)), "download", ACCENT))
                : ui.toolbar(all, random));
        boolean local = !source.isEmpty() && source.get(0).isLocal();
        int limit = Math.min(source.size(), maximumVisible);
        if (ui.revealTrackId != Long.MIN_VALUE) {
            for (int i = limit; i < source.size(); i++) {
                if (source.get(i).id == ui.revealTrackId) {
                    limit = i + 1;
                    break;
                }
            }
        }
        RangeDrawer draw = (from, to) -> {
            for (int i = from; i < to; i++) {
                int index = i;
                ui.content.addView(trackEntry(source.get(index), favoriteList, () -> playList(source, index, false)));
            }
        };
        if (local) {
            showGrowing(limit, draw);
            if (ui.revealTrackId != Long.MIN_VALUE) {
                for (int i = MainActivity.LOCAL_PAGE_SIZE; i < limit; i++) {
                    if (source.get(i).id == ui.revealTrackId) {
                        fillTo(i);
                        break;
                    }
                }
            }
        } else draw.draw(0, limit);
    }

    /** Draw the first page now, and the rest when the list scrolls near the bottom. */
    void showGrowing(int total, RangeDrawer drawer) {
        growTotal = total;
        ui.growDrawer = drawer;
        ui.grown = new int[] {Math.min(total, MainActivity.LOCAL_PAGE_SIZE)};
        if (ui.grown[0] > 0) drawer.draw(0, ui.grown[0]);
        armGrow();
    }

    void armGrow() {
        if (ui.grown == null || ui.grown[0] >= growTotal) {
            ui.loadMore = null;
            return;
        }
        ui.loadMore = () -> fillTo(ui.grown[0] + MainActivity.LOCAL_PAGE_SIZE - 1);
    }

    void fillTo(int index) {
        if (ui.loadingMore || ui.grown == null || ui.growDrawer == null) return;
        ui.loadingMore = true;
        int guard = 0;
        while (ui.grown[0] <= index && ui.grown[0] < growTotal && guard++ < 80) {
            int from = ui.grown[0];
            int to = Math.min(growTotal, Math.max(from + MainActivity.LOCAL_PAGE_SIZE, index + 1));
            ui.growDrawer.draw(from, to);
            ui.grown[0] = to;
        }
        ui.loadingMore = false;
        armGrow();
    }

    void playList(List<TidalClient.Track> list, int index, boolean random) {
        if (ui.remoteScreens.remoteMode() && !list.isEmpty()) {
            ui.remoteScreens.remoteTracks(list, index, random, "play");
            return;
        }
        if (ui.playback == null || list.isEmpty()) return;
        ui.playback.replaceQueue(list, index, random);
        ui.nowPlaying.updatePlayer();
    }

    LinearLayout trackEntry(TidalClient.Track track, boolean favoriteList, Runnable playTrack) {
        LinearLayout row = ui.row();
        row.setTag(track.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(8), ui.dp(4), ui.dp(6), ui.dp(4));
        row.setBackground(ui.shape(SURFACE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(48));
        params.bottomMargin = ui.dp(3);
        row.setLayoutParams(params);

        ImageView image = ui.art(36);
        image.setBackground(ui.shape(RAISED, 6));
        ui.artwork.load(image, track, 160);
        image.setOnClickListener(v -> playTrack.run());
        row.addView(image);

        LinearLayout info = ui.column();
        info.setPadding(ui.dp(10), 0, ui.dp(6), 0);

        TextView titleView = ui.single(UiKit.displayTitle(track), 13, INK, true);
        info.addView(titleView);

        LinearLayout metaRow = ui.row();
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        AudioBadge spec = ui.audioSpecBadge(track);
        if (spec != null) {
            TextView bView = ui.text(spec.text, 8, spec.textColor, true);
            bView.setPadding(ui.dp(3), ui.dp(1), ui.dp(3), ui.dp(1));
            bView.setBackground(ui.shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.rightMargin = ui.dp(4);
            metaRow.addView(bView, bLp);
        }

        String sourceName = track.isLocal() ? "本地"
                : track.isNetease() ? "网易云"
                : track.isWebdav() ? "WebDAV"
                : track.isSmb() ? "SMB" : "TIDAL";
        TextView sView = ui.text(sourceName, 8, MUTED, false);
        sView.setPadding(ui.dp(3), ui.dp(1), ui.dp(3), ui.dp(1));
        sView.setBackground(ui.shape(0x18ffffff, 3));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.rightMargin = ui.dp(5);
        metaRow.addView(sView, sLp);

        String byline = track.artist;
        if (!track.album.isEmpty()) byline += (byline.isEmpty() ? "" : " · ") + track.album;
        if (byline.isEmpty()) byline = sourceName;
        TextView artistView = ui.single(byline, 11, MUTED, false);
        metaRow.addView(artistView, new LinearLayout.LayoutParams(0, -2, 1));

        info.addView(metaRow);
        info.setOnClickListener(v -> playTrack.run());
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        long dur = DownloadTasks.length(track);
        if (dur > 0) {
            TextView durView = ui.text(UiKit.formatTime(dur * 1000), 10, MUTED, false);
            durView.setPadding(0, 0, ui.dp(4), 0);
            row.addView(durView);
        }

        row.addView(ui.iconButton("more", "更多 " + track.title, () -> trackMenu(track, favoriteList)),
                new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        return row;
    }

    LinearLayout albumTrackEntry(TidalClient.Track track, int trackNum, Runnable playTrack) {
        LinearLayout row = ui.row();
        row.setTag(track.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(8), ui.dp(4), ui.dp(6), ui.dp(4));
        row.setBackground(ui.shape(SURFACE, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(44));
        params.bottomMargin = ui.dp(2);
        row.setLayoutParams(params);

        boolean isCurrent = ui.playback != null && ui.playback.current() != null && ui.playback.current().id == track.id;

        TextView numView = ui.text(isCurrent ? "▶" : String.format(Locale.ROOT, "%02d", trackNum), 11, isCurrent ? GOLD : MUTED, isCurrent);
        numView.setGravity(Gravity.CENTER);
        row.addView(numView, new LinearLayout.LayoutParams(ui.dp(28), -2));

        LinearLayout info = ui.column();
        info.setPadding(ui.dp(6), 0, ui.dp(6), 0);

        TextView titleView = ui.single(UiKit.displayTitle(track), 13, isCurrent ? GOLD : INK, isCurrent);
        info.addView(titleView);

        if (!track.artist.isEmpty()) {
            TextView artistView = ui.single(track.artist, 10, MUTED, false);
            info.addView(artistView);
        }
        info.setOnClickListener(v -> playTrack.run());
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        AudioBadge spec = ui.audioSpecBadge(track);
        if (spec != null) {
            TextView bView = ui.text(spec.text, 8, spec.textColor, true);
            bView.setPadding(ui.dp(3), ui.dp(1), ui.dp(3), ui.dp(1));
            bView.setBackground(ui.shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.rightMargin = ui.dp(6);
            row.addView(bView, bLp);
        }

        long dur = DownloadTasks.length(track);
        if (dur > 0) {
            TextView durView = ui.text(UiKit.formatTime(dur * 1000), 10, MUTED, false);
            durView.setPadding(0, 0, ui.dp(4), 0);
            row.addView(durView);
        }

        row.addView(ui.iconButton("more", "更多 " + track.title, () -> trackMenu(track, false)),
                new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        return row;
    }

    void trackMenu(TidalClient.Track track, boolean favoriteList) {
        if (track.isLocal()) {
            ui.trackExtras.localTrackMenu(track);
            return;
        }
        if (track.isWebdav()) {
            ui.webdavScreens.webdavTrackMenu(track, null);
            return;
        }
        if (track.isSmb()) {
            ui.smbScreens.smbTrackMenu(track, null);
            return;
        }
        boolean liked = favoriteList || ui.favoriteIds.contains(track.id);
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (ui.remoteScreens.remoteMode() && !track.isNetease()) {
            String name = ui.devices.targetName();
            choices.add("在 " + name + " 播放");
            actions.add(() -> ui.remoteScreens.remoteTracks(java.util.Collections.singletonList(track), 0, false, "play"));
            choices.add("下一首播放 · " + name);
            actions.add(() -> { if (ui.remoteScreens.remoteAdd(track, true)) ui.toast("已发送到 " + name + " 的下一首"); });
            choices.add("追加到队列 · " + name);
            actions.add(() -> { if (ui.remoteScreens.remoteAdd(track, false)) ui.toast("已发送到 " + name + " 的队列"); });
        } else {
            choices.add("下一首播放");
            actions.add(() -> { ui.playback.enqueue(track, true); ui.toast("已加入下一首"); });
            choices.add("追加到队列");
            actions.add(() -> { ui.playback.enqueue(track, false); ui.toast("已加入队列"); });
        }
        if (!track.isNetease()) {
            choices.add(liked ? "取消收藏" : "收藏歌曲");
            actions.add(() -> ui.changeFavorite(track, !liked));
        }
        choices.add("分享歌曲");
        actions.add(() -> ui.share(track));
        if (track.isNetease()) {
            choices.add("查看评论");
            actions.add(() -> ui.downloadScreens.showNeteaseComments(track));
            choices.add(ui.downloadScreens.downloadLabel(track));
            actions.add(() -> ui.downloadScreens.downloadNetease(track));
        } else {
            choices.add(ui.downloadScreens.downloadLabel(track));
            actions.add(() -> ui.downloadScreens.downloadTidal(track));
            if (track.artistId > 0 && !track.artist.isEmpty()) {
                choices.add("查看艺人");
                actions.add(() -> {
                    try {
                        openCollection(new TidalClient.Collection(new JSONObject().put("id", track.artistId)
                                .put("name", track.artist).put("picture", track.artistPicture),
                                TidalClient.Collection.Kind.ARTIST));
                    } catch (org.json.JSONException ignored) {}
                });
            }
            choices.add("歌曲电台");
            actions.add(() -> ui.trackExtras.playRadio(track));
        }
        if (track.albumId > 0) {
            choices.add("查看专辑");
            actions.add(() -> {
                try {
                    openCollection(new TidalClient.Collection(new org.json.JSONObject()
                            .put("id", track.albumId).put("title", track.album).put("cover", track.cover),
                            TidalClient.Collection.Kind.ALBUM));
                } catch (org.json.JSONException ignored) {}
            });
        }
        new AlertDialog.Builder(ui).setTitle(track.title)
                .setItems(choices.toArray(new String[0]), (dialog, choice) -> {
                    if (ui.playback != null) actions.get(choice).run();
                }).show();
    }
}
