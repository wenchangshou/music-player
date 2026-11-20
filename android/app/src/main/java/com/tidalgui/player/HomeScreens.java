package com.tidalgui.player;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import com.tidalgui.player.LocalLibrary.LocalGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.tidalgui.player.ListeningRoomColors.ACCENT;
import static com.tidalgui.player.ListeningRoomColors.BG;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: HomeScreens. */
@UnstableApi
final class HomeScreens {
    final MainActivity ui;

    HomeScreens(MainActivity ui) {
        this.ui = ui;
    }


    void home() {
        if (ui.remoteScreens.remoteMode()) {
            ui.remoteScreens.remoteHome();
            return;
        }
        LocalLibrary library = ui.playback == null ? null : ui.playback.localLibrary;
        List<TidalClient.Track> recent = ui.playback == null ? new ArrayList<>() : new ArrayList<>(ui.playback.history);
        int songs = library == null ? 0 : library.size();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, ui.dp(6));
        head.addView(ui.text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.action("搜索", "搜索本地音乐", () -> ui.openSearch(SearchSource.LOCAL)));
        ui.content.addView(head);
        if (songs == 0 && recent.isEmpty()) {
            ui.content.addView(ui.primary("导入音乐", ui.localScreens::importMenu), new LinearLayout.LayoutParams(-2, ui.dp(40)));
        }

        trackShelf("最近播放", recent.isEmpty() ? null : "播放全部",
                recent.isEmpty() ? null : () -> ui.catalogueScreens.playList(recent, 0, false), recent);
        if (library != null && songs > 0) {
            groupShelf("专辑", () -> openLocalLibrary(1), localAlbums(library, recent));
            groupShelf("歌单", () -> openLocalLibrary(2), localPlaylists(library));
        }
        if (!ui.neteaseScreens.hasPlayerAccount()) {
            LinearLayout sync = ui.row();
            sync.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12));
            sync.setBackground(ui.shape(SURFACE, 15));
            ImageView symbol = new ImageView(ui);
            symbol.setImageDrawable(new PlayerIcon("user", ACCENT, ui.dp(24)));
            sync.addView(symbol, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            TextView about = ui.text("登录播放器账号，在电脑和手机之间同步登录、遥控播放。", 13, MUTED, false);
            about.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            sync.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
            sync.addView(ui.secondary("登录", ui.settingsScreens::playerAccountLogin), new LinearLayout.LayoutParams(-2, ui.dp(38)));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.topMargin = ui.dp(12);
            ui.content.addView(sync, gap);
        }
    }

    List<LocalLibrary.LocalGroup> localAlbums(LocalLibrary library, List<TidalClient.Track> recent) {
        java.util.Map<String, LocalLibrary.LocalGroup> grouped = new java.util.LinkedHashMap<>();
        for (TidalClient.Track track : recent) rememberAlbum(grouped, track);
        if (grouped.size() < 12) {
            for (TidalClient.Track track : library.search("", null)) {
                rememberAlbum(grouped, track);
                if (grouped.size() >= 12) break;
            }
        }
        return new ArrayList<>(grouped.values());
    }

    static LocalLibrary.LocalGroup rememberAlbum(java.util.Map<String, LocalLibrary.LocalGroup> grouped,
                                                         TidalClient.Track track) {
        if (track == null || !track.isLocal()) return null;
        LocalLibrary.LocalGroup candidate = LocalLibrary.groupFor(track, 1);
        LocalLibrary.LocalGroup group = grouped.get(candidate.key);
        if (group == null) {
            if (grouped.size() >= 12) return null;
            group = candidate;
            grouped.put(group.key, group);
        }
        if (group.members.size() < 40) group.members.add(track);
        return group;
    }

    List<LocalLibrary.LocalGroup> localPlaylists(LocalLibrary library) {
        List<LocalLibrary.LocalGroup> groups = new ArrayList<>();
        for (String name : library.playlists()) {
            if (groups.size() >= 12) break;
            List<TidalClient.Track> songs = library.search("", name);
            LocalLibrary.LocalGroup group = new LocalLibrary.LocalGroup(name, name, songs.size() + " 首", 2);
            group.members.addAll(songs);
            groups.add(group);
        }
        return groups;
    }

    void groupShelf(String heading, Runnable openMore, List<LocalLibrary.LocalGroup> groups) {
        if (groups.isEmpty()) return;
        sectionLink(heading, openMore == null ? null : "全部", openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ui);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (LocalLibrary.LocalGroup group : groups) {
            LinearLayout card = ui.column();
            card.setContentDescription(group.label);
            card.setOnClickListener(view -> {
                if (group.category == 2) {
                    ui.localPlaylist = group.label;
                    ui.localGroup = null;
                    ui.localTab = 2;
                    if (ui.page == Page.LOCAL) ui.render();
                    else ui.open(Page.LOCAL);
                } else {
                    ui.remoteScreens.openLocalGroup(group);
                }
            });
            ImageView cover = ui.art(124);
            if (!group.members.isEmpty()) ui.artwork.load(cover, group.members.get(0), 320);
            card.addView(cover, new LinearLayout.LayoutParams(ui.dp(124), ui.dp(124)));
            TextView name = ui.single(group.label, 13, INK, true);
            name.setPadding(0, ui.dp(6), 0, 0);
            card.addView(name);
            String caption = group.detail.isEmpty() ? group.members.size() + " 首" : group.detail;
            card.addView(ui.single(caption, 11, MUTED, false));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ui.dp(124), -2);
            cardParams.setMarginEnd(ui.dp(10));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        ui.content.addView(scroll);
    }

    void openLocalLibrary(int tab) {
        ui.localTab = tab;
        ui.localPlaylist = null;
        ui.localGroup = null;
        if (ui.page == Page.LOCAL) ui.render();
        else ui.open(Page.LOCAL);
    }

    List<?> personalKey;
    List<TidalClient.Track> personalTracks = java.util.Collections.emptyList();
    List<TidalClient.Track> personalLiked = java.util.Collections.emptyList();
    List<TidalClient.Collection> personalPlaylists = java.util.Collections.emptyList();
    List<TidalClient.Collection> personalSquare = java.util.Collections.emptyList();
    List<TidalClient.Collection> personalAlbums = java.util.Collections.emptyList();
    List<TidalClient.HomeShelf> personalDiscover = java.util.Collections.emptyList();
    boolean personalPlaylistsForAccount;
    boolean personalLoading;

    void loadPersonalHome(SearchSource source, NeteaseClient netease,
                                  android.content.SharedPreferences prefs) {
        TidalClient tidal = ui.remoteScreens.tidalApi();
        List<?> key = source == SearchSource.TIDAL ? java.util.Arrays.asList(source, tidal)
                : java.util.Arrays.asList(source, prefs.getString(NeteaseClient.SETTING, ""),
                        prefs.getString(NeteaseClient.SESSION, ""));
        if (key.equals(personalKey)) return;
        personalKey = key;
        personalTracks = java.util.Collections.emptyList();
        personalLiked = java.util.Collections.emptyList();
        personalPlaylists = java.util.Collections.emptyList();
        personalSquare = java.util.Collections.emptyList();
        personalAlbums = java.util.Collections.emptyList();
        personalDiscover = java.util.Collections.emptyList();
        personalPlaylistsForAccount = false;
        personalLoading = source == SearchSource.NETEASE || tidal != null;
        if (!personalLoading) return;

        ui.worker.submit(() -> {
            if (source == SearchSource.TIDAL) {
                List<TidalClient.Track> picks = new ArrayList<>();
                List<TidalClient.Collection> playlists = new ArrayList<>();
                List<TidalClient.Collection> albums = new ArrayList<>();
                List<TidalClient.HomeShelf> discover = new ArrayList<>();
                addPersonalItems(picks, () -> tidal.favorites(0));
                addPersonalItems(albums, () -> tidal.collections("", TidalClient.Collection.Kind.ALBUM, 0, false));
                addPersonalItems(playlists, () -> tidal.collections("", TidalClient.Collection.Kind.PLAYLIST, 0, true));
                boolean ownPlaylists = !playlists.isEmpty();
                if (playlists.isEmpty()) {
                    addPersonalItems(playlists, () -> tidal.collections("", TidalClient.Collection.Kind.PLAYLIST, 0, false));
                }
                try {
                    discover.addAll(tidal.home());
                } catch (Exception ignored) {
                    // Favourite rows still render when the editorial home is unavailable.
                }
                ui.runOnUiThread(() -> {
                    if (ui.isDestroyed() || personalKey != key) return;
                    personalTracks = picks;
                    personalPlaylists = playlists;
                    personalAlbums = albums;
                    personalDiscover = discover;
                    personalPlaylistsForAccount = ownPlaylists;
                    personalLoading = false;
                    if (ui.page == Page.HOME && ui.homeSource == source) ui.render();
                });
                return;
            }
            boolean signedIn = netease.signedIn();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(signedIn ? 4 : 2);
        if (signedIn) {
            ui.worker.submit(() -> {
                List<TidalClient.Track> rows = new ArrayList<>();
                addPersonalItems(rows, netease::dailyTracks);
                deliverHomeSection(key, source, pending, () -> personalTracks = rows);
            });
            ui.worker.submit(() -> {
                List<TidalClient.Track> rows = new ArrayList<>();
                addPersonalItems(rows, netease::likedTracks);
                deliverHomeSection(key, source, pending, () -> personalLiked = rows);
            });
        }
        ui.worker.submit(() -> {
            List<TidalClient.Collection> rows = new ArrayList<>();
            boolean own = false;
            if (signedIn) {
                addPersonalItems(rows, () -> netease.recommendedPlaylists(true));
                own = !rows.isEmpty();
            }
            if (rows.isEmpty()) addPersonalItems(rows, () -> netease.recommendedPlaylists(false));
            boolean account = own;
            deliverHomeSection(key, source, pending, () -> {
                personalPlaylists = rows;
                personalPlaylistsForAccount = account;
            });
        });
        ui.worker.submit(() -> {
            List<TidalClient.Collection> rows = new ArrayList<>();
            addPersonalItems(rows, () -> {
                List<TidalClient.Collection> page = netease.playlistSquare("全部", 0).created;
                return page.size() > 12 ? new ArrayList<>(page.subList(0, 12)) : page;
            });
            deliverHomeSection(key, source, pending, () -> personalSquare = rows);
        });
        });
    }

    /** Show one NetEase home section as soon as it arrives, without waiting for the others. */
    void deliverHomeSection(List<?> key, SearchSource source,
                                    java.util.concurrent.atomic.AtomicInteger pending, Runnable apply) {
        ui.runOnUiThread(() -> {
            if (ui.isDestroyed() || personalKey != key) return;
            apply.run();
            if (pending.decrementAndGet() == 0) personalLoading = false;
            if (ui.page == Page.HOME && ui.homeSource == source) {
                ui.homeScrollRestore = ui.listScroll == null ? 0 : ui.listScroll.getScrollY();
                ui.render();
            }
        });
    }

    static <T> void addPersonalItems(List<T> destination, PersonalItems<T> source) {
        try {
            destination.addAll(source.load());
        } catch (Exception ignored) {
            // Other sections and local listening history remain available.
        }
    }

    void neteaseHome() {
        android.content.SharedPreferences prefs = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        NeteaseClient netease = new NeteaseClient(prefs);
        boolean signedIn = netease.signedIn();
        loadPersonalHome(SearchSource.NETEASE, netease, prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (ui.playback != null) {
            for (TidalClient.Track track : ui.playback.history) {
                if (SearchSource.NETEASE.includes(track)) recent.add(track);
            }
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, ui.dp(6));
        head.addView(ui.text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.action("搜索", "搜索网易云", () -> ui.openSearch(SearchSource.NETEASE)));
        ui.content.addView(head);
        if (!signedIn && netease.usesDefaultBackend()) {
            ui.content.addView(ui.primary("登录网易云", () -> ui.neteaseScreens.showNeteaseLogin(netease)),
                    new LinearLayout.LayoutParams(-2, ui.dp(40)));
        }
        if (personalLoading && personalTracks.isEmpty() && personalLiked.isEmpty()
                && personalPlaylists.isEmpty() && personalSquare.isEmpty()) {
            TextView waiting = ui.text("正在读取每日推荐和歌单…", 13, MUTED, false);
            waiting.setPadding(0, ui.dp(8), 0, ui.dp(4));
            ui.content.addView(waiting);
        }

        List<TidalClient.Track> daily = personalTracks;
        trackShelf("每日推荐", daily.isEmpty() ? null : "播放全部",
                daily.isEmpty() ? null : () -> ui.catalogueScreens.playList(daily, 0, false), daily);
        if (!personalLoading && signedIn && daily.isEmpty()) homeNote("今天的每日推荐还没有返回");

        List<TidalClient.Track> liked = personalLiked;
        trackShelf("喜欢的歌", liked.isEmpty() ? null : "播放全部",
                liked.isEmpty() ? null : () -> ui.catalogueScreens.playList(liked, 0, false), liked);
        if (!personalLoading && signedIn && liked.isEmpty()) homeNote("还没有红心歌曲，或喜欢列表暂时读不到");

        linkedShelf(personalPlaylistsForAccount ? "专属歌单" : "推荐歌单",
                () -> ui.neteaseScreens.openNeteasePlaylists(Page.NETEASE_PLAYLISTS), personalPlaylists);
        linkedShelf("歌单广场", () -> ui.neteaseScreens.openNeteasePlaylists(Page.NETEASE_SQUARE), personalSquare);

        if (!recent.isEmpty()) {
            ui.section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                ui.content.addView(ui.catalogueScreens.trackEntry(recent.get(at), false, () -> ui.catalogueScreens.playList(recent, at, false)));
            }
        }
    }

    void linkedShelf(String heading, Runnable openMore, List<TidalClient.Collection> items) {
        if (items.isEmpty()) {
            if (!personalLoading) sectionLink(heading, "全部", openMore);
            return;
        }
        shelf(heading, "全部", openMore, items);
    }

    void homeNote(String value) {
        TextView line = ui.text(value, 13, MUTED, false);
        line.setPadding(ui.dp(2), 0, 0, ui.dp(8));
        ui.content.addView(line);
    }

    void tidalHome() {
        boolean signedIn = ui.remoteScreens.tidalApi() != null;
        android.content.SharedPreferences prefs = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        loadPersonalHome(SearchSource.TIDAL, new NeteaseClient(prefs), prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (ui.playback != null) {
            for (TidalClient.Track track : ui.playback.history) {
                if (SearchSource.TIDAL.includes(track)) recent.add(track);
            }
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, ui.dp(6));
        head.addView(ui.text(greeting, 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.action("搜索", "搜索 TIDAL", () -> ui.openSearch(SearchSource.TIDAL)));
        ui.content.addView(head);
        if (!signedIn) {
            ui.content.addView(ui.primary("登录 TIDAL", ui.loginFlow::login), new LinearLayout.LayoutParams(-2, ui.dp(40)));
        }
        boolean nothing = personalDiscover.isEmpty() && personalTracks.isEmpty()
                && personalAlbums.isEmpty() && personalPlaylists.isEmpty();
        if (personalLoading && nothing) {
            TextView waiting = ui.text("正在读取推荐、混音和收藏…", 13, MUTED, false);
            waiting.setPadding(0, ui.dp(8), 0, ui.dp(4));
            ui.content.addView(waiting);
        }
        for (TidalClient.HomeShelf shelf : personalDiscover) {
            String title = tidalShelfTitle(shelf.title);
            if (!shelf.tracks.isEmpty()) {
                List<TidalClient.Track> tracks = shelf.tracks;
                trackShelf(title, "播放全部", () -> ui.catalogueScreens.playList(tracks, 0, false), tracks);
            } else if (!shelf.collections.isEmpty()) {
                shelf(title, shelf.collections);
            }
        }
        if (signedIn) {
            List<TidalClient.Track> liked = personalTracks;
            trackShelf("收藏的歌", liked.isEmpty() ? null : "播放全部",
                    liked.isEmpty() ? null : () -> ui.catalogueScreens.playList(liked, 0, false), liked);
            linkedShelf("收藏专辑", () -> openLibrary(1), personalAlbums);
            linkedShelf(personalPlaylistsForAccount ? "自建歌单" : "收藏歌单",
                    () -> openLibrary(personalPlaylistsForAccount ? 4 : 2), personalPlaylists);
        }
        if (!personalLoading && signedIn && nothing) homeNote("首页推荐和收藏暂时都没有返回");
        if (!recent.isEmpty()) {
            ui.section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                ui.content.addView(ui.catalogueScreens.trackEntry(recent.get(at), false, () -> ui.catalogueScreens.playList(recent, at, false)));
            }
        }
    }

    /** Common TIDAL home headings. Anything else stays in the language TIDAL returned. */
    static String tidalShelfTitle(String title) {
        String key = title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (key.equals("recently played")) return "最近播放";
        if (key.equals("custom mixes")) return "专属混音";
        if (key.startsWith("radio stations")) return "为你推荐的电台";
        if (key.startsWith("suggested new albums")) return "新专辑推荐";
        return title == null ? "" : title;
    }

    void openLibrary(int filter) {
        ui.libraryFilter = filter;
        if (ui.page != Page.LIBRARY) ui.page = Page.LIBRARY;
        ui.notice = "";
        ui.catalogueScreens.load(false);
    }

    void sourceHome(SearchSource source) {
        if (source == SearchSource.NETEASE) {
            neteaseHome();
            return;
        }
        if (source == SearchSource.TIDAL) {
            tidalHome();
            return;
        }
        boolean tidal = source == SearchSource.TIDAL;
        android.content.SharedPreferences prefs = ui.getSharedPreferences("tidal", android.content.Context.MODE_PRIVATE);
        NeteaseClient netease = new NeteaseClient(prefs);
        boolean signedIn = tidal ? ui.remoteScreens.tidalApi() != null : netease.signedIn();
        loadPersonalHome(source, netease, prefs);

        List<TidalClient.Track> recent = new ArrayList<>();
        if (ui.playback != null) {
            for (TidalClient.Track track : ui.playback.history) {
                if (source.includes(track)) recent.add(track);
            }
        }

        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "凌晨好" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        TextView title = ui.text(greeting, 25, INK, true);
        title.setPadding(0, 0, 0, ui.dp(10));
        ui.content.addView(title);

        List<TidalClient.Track> featured = personalTracks.isEmpty() ? recent : personalTracks;
        LinearLayout hero = ui.column();
        hero.setPadding(ui.dp(15), ui.dp(14), ui.dp(15), ui.dp(13));
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                tidal ? new int[]{0xff173542, SURFACE} : new int[]{0xff48252e, SURFACE});
        backdrop.setCornerRadius(ui.dp(20));
        hero.setBackground(backdrop);
        ui.content.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (featured.isEmpty() && tidal && signedIn && !personalAlbums.isEmpty()) {
            TidalClient.Collection first = personalAlbums.get(0);
            LinearLayout feature = ui.row();
            ImageView image = ui.art(72);
            ui.artwork.load(image, first.cover, 320, first.title);
            feature.addView(image, new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)));
            LinearLayout details = ui.column();
            details.setPadding(ui.dp(12), 0, 0, 0);
            details.addView(ui.text("最近收藏的专辑 · 共 " + personalAlbums.size() + " 张", 12, ACCENT, true));
            details.addView(ui.single(first.title, 20, INK, true));
            details.addView(ui.single(first.subtitle, 13, MUTED, false));
            feature.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            hero.addView(feature);
            LinearLayout actions = ui.row();
            actions.setPadding(0, ui.dp(12), 0, 0);
            actions.addView(ui.withIcon(ui.primary("打开专辑", () -> ui.catalogueScreens.openCollection(first)), "forward", BG),
                    new LinearLayout.LayoutParams(-2, ui.dp(40)));
            hero.addView(actions);
        } else if (featured.isEmpty()) {
            String label = personalLoading ? "正在准备你的音乐"
                    : tidal && !signedIn ? "登录后查看你的收藏"
                    : !tidal && !signedIn && netease.usesDefaultBackend() ? "登录后查看每日推荐" : "开始聆听";
            hero.addView(ui.text(label, 19, INK, true));
            String hint = personalLoading ? "稍等片刻，正在读取推荐与收藏。"
                    : tidal && !signedIn ? "在 TIDAL 网页确认一次授权，即可收听 Hi-Res 无损并同步收藏歌曲与歌单。"
                    : "搜索歌曲、专辑或艺人，开始一段新的聆听。";
            TextView about = ui.text(hint, 13, MUTED, false);
            about.setPadding(0, ui.dp(4), 0, ui.dp(12));
            hero.addView(about);
            if (!personalLoading) {
                String button = tidal && !signedIn ? "登录 TIDAL" : "搜索音乐";
                hero.addView(ui.primary(button, tidal && !signedIn ? ui.loginFlow::login : () -> ui.openSearch(source)),
                        new LinearLayout.LayoutParams(-2, ui.dp(42)));
            }
        } else {
            TidalClient.Track first = featured.get(0);
            LinearLayout feature = ui.row();
            ImageView image = ui.art(62);
            ui.artwork.load(image, first, 320);
            feature.addView(image, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(62)));
            LinearLayout details = ui.column();
            details.setPadding(ui.dp(12), 0, 0, 0);
            String kind = personalTracks.isEmpty() ? "继续聆听" : tidal ? "从你的收藏听起" : "每日推荐";
            details.addView(ui.text(kind + " · " + featured.size() + " 首", 12, ACCENT, true));
            details.addView(ui.single(first.title, 20, INK, true));
            details.addView(ui.single(first.artist, 13, MUTED, false));
            feature.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            hero.addView(feature);
            LinearLayout actions = ui.row();
            actions.setPadding(0, ui.dp(9), 0, 0);
            actions.addView(ui.withIcon(ui.primary("立即播放", () -> ui.catalogueScreens.playList(featured, 0, false)), "play", BG),
                    new LinearLayout.LayoutParams(-2, ui.dp(40)));
            View gap = new View(ui);
            actions.addView(gap, new LinearLayout.LayoutParams(ui.dp(8), 1));
            actions.addView(ui.withIcon(ui.secondary("加入队列", () -> {
                if (ui.playback == null) return;
                ui.playback.enqueueAll(featured);
                ui.toast("已加入 " + featured.size() + " 首");
            }), "queue", ACCENT), new LinearLayout.LayoutParams(-2, ui.dp(40)));
            hero.addView(actions);
        }

        LinearLayout links = ui.row();
        links.setPadding(0, ui.dp(12), 0, 0);
        links.addView(ui.tile("search", "搜索", () -> ui.openSearch(source)));
        if (!featured.isEmpty()) {
            links.addView(ui.tile("shuffle", "随机听", () -> ui.catalogueScreens.playList(featured,
                    new java.util.Random().nextInt(featured.size()), false)));
        }
        if (tidal) {
            links.addView(ui.tile("heart", "我的收藏", signedIn ? () -> ui.open(Page.LIBRARY) : ui.loginFlow::login));
            links.addView(ui.tile("user", "账号", () -> ui.open(Page.ACCOUNT)));
        } else {
            if (netease.usesDefaultBackend()) {
                links.addView(ui.tile("user", signedIn ? "账号" : "登录网易云",
                        signedIn ? () -> ui.open(Page.SOURCES) : () -> ui.neteaseScreens.showNeteaseLogin(netease)));
            }
        }
        for (int i = 0; i < links.getChildCount(); i++) {
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, ui.dp(70), 1);
            if (i > 0) cell.leftMargin = ui.dp(8);
            links.getChildAt(i).setLayoutParams(cell);
        }
        ui.content.addView(links);
        if (!tidal) {
            LinearLayout playlistLinks = ui.row();
            ui.neteaseScreens.addShortcut(playlistLinks, "我的歌单", "我创建的与收藏的", "library",
                    () -> ui.neteaseScreens.openNeteasePlaylists(Page.NETEASE_PLAYLISTS));
            ui.neteaseScreens.addShortcut(playlistLinks, "歌单广场", "按分类发现歌单", "sources",
                    () -> ui.neteaseScreens.openNeteasePlaylists(Page.NETEASE_SQUARE));
            ui.content.addView(playlistLinks);
        }

        if (!recent.isEmpty()) {
            ui.section("最近聆听");
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                int at = i;
                ui.content.addView(ui.catalogueScreens.trackEntry(recent.get(at), false, () -> ui.catalogueScreens.playList(recent, at, false)));
            }
        }
        if (tidal && personalAlbums.size() > 1) shelf("收藏专辑", personalAlbums.subList(1, personalAlbums.size()));
        if (!personalPlaylists.isEmpty()) {
            shelf(tidal ? "歌单" : personalPlaylistsForAccount ? "专属歌单" : "推荐歌单", personalPlaylists);
        }
    }

    /** Horizontally scrolling row of album / playlist covers. */
    void shelf(String heading, List<TidalClient.Collection> items) {
        shelf(heading, null, null, items);
    }

    void shelf(String heading, String more, Runnable openMore, List<TidalClient.Collection> items) {
        if (items.isEmpty()) return;
        sectionLink(heading, more, openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ui);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(12, items.size()); i++) {
            TidalClient.Collection item = items.get(i);
            LinearLayout card = ui.column();
            ImageView cover = ui.art(124);
            ui.artwork.load(cover, item.cover, 320, item.title);
            card.addView(cover, new LinearLayout.LayoutParams(ui.dp(124), ui.dp(124)));
            TextView name = ui.single(item.title, 13, INK, true);
            name.setPadding(0, ui.dp(7), 0, 0);
            card.addView(name);
            TextView caption = ui.single(item.subtitle, 12, MUTED, false);
            card.addView(caption);
            ui.downloadScreens.mark(() -> {
                int saved = item.kind == TidalClient.Collection.Kind.ALBUM && ui.downloaded != null ? ui.downloaded.album(item) : 0;
                caption.setText(saved > 0 ? "已下载 " + saved + " 首" : item.subtitle);
                caption.setTextColor(saved > 0 ? ACCENT : MUTED);
                caption.setVisibility(saved > 0 || !item.subtitle.isEmpty() ? View.VISIBLE : View.GONE);
            });
            card.setContentDescription(item.title);
            card.setOnClickListener(view -> ui.catalogueScreens.openCollection(item));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ui.dp(124), -2);
            cardParams.setMarginEnd(ui.dp(12));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        ui.content.addView(scroll);
    }

    /** Horizontally scrolling row of songs, used by the NetEase home. */
    void trackShelf(String heading, String more, Runnable openMore, List<TidalClient.Track> tracks) {
        if (tracks.isEmpty()) return;
        sectionLink(heading, more, openMore);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ui);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(20, tracks.size()); i++) {
            int index = i;
            TidalClient.Track track = tracks.get(index);
            LinearLayout card = ui.column();
            card.setContentDescription(UiKit.displayTitle(track) + "，" + track.artist);
            card.setOnClickListener(view -> ui.catalogueScreens.playList(tracks, index, false));
            ImageView cover = ui.art(112);
            ui.artwork.load(cover, track, 320);
            card.addView(cover, new LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)));
            TextView name = ui.single(UiKit.displayTitle(track), 13, INK, true);
            name.setPadding(0, ui.dp(6), 0, 0);
            card.addView(name);
            card.addView(ui.single(track.artist, 11, MUTED, false));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ui.dp(112), -2);
            cardParams.setMarginEnd(ui.dp(10));
            row.addView(card, cardParams);
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        ui.content.addView(scroll);
    }

    void sectionLink(String heading, String more, Runnable openMore) {
        if (more == null || openMore == null) {
            ui.section(heading);
            return;
        }
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(10), 0, ui.dp(6));
        line.addView(ui.text(heading, 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button open = ui.action(more, more + heading, openMore);
        open.setTextSize(13);
        open.setPadding(ui.dp(8), 0, 0, 0);
        line.addView(open);
        ui.content.addView(line);
    }
}
