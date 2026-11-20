package com.tidalgui.player;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import static com.tidalgui.player.ListeningRoomColors.GOLD;
import static com.tidalgui.player.ListeningRoomColors.INK;
import static com.tidalgui.player.ListeningRoomColors.MUTED;
import static com.tidalgui.player.ListeningRoomColors.RAISED;
import static com.tidalgui.player.ListeningRoomColors.SURFACE;

/** Part of {@link MainActivity}: LocalLibraryScreens. */
@UnstableApi
final class LocalLibraryScreens {
    final MainActivity ui;

    LocalLibraryScreens(MainActivity ui) {
        this.ui = ui;
    }

    static final java.util.regex.Pattern YEAR_KEY = java.util.regex.Pattern.compile("(19|20)\\d{2}");
    static Object nameLatin;

    void localMusic() {
        if (ui.remoteScreens.remoteMode()) {
            ui.remoteScreens.remoteLibraryPage(true);
            return;
        }
        if (ui.playback == null) return;
        LocalLibrary library = ui.playback.localLibrary;
        List<TidalClient.Track> tracks = library.search("", ui.localPlaylist);
        if (ui.localPlaylist != null || ui.localGroup != null) {
            if (ui.localGroup != null) {
                LocalGroup selected = ui.localGroup;
                for (int i = tracks.size() - 1; i >= 0; i--) {
                    if (!LocalLibrary.groupFor(tracks.get(i), selected.category).key.equals(selected.key)) tracks.remove(i);
                }
                if (selected.category == 1) sortAlbum(tracks);
                else sortLocal(tracks);
            }
            if (ui.localPlaylist != null) {
                ui.content.addView(ui.toolbar(ui.secondary("导出 M3U", ui.importExport::pickPlaylistDestination),
                        ui.secondary("删除歌单", this::deleteLocalPlaylist)));
            }
            if (tracks.isEmpty()) {
                ui.empty("暂无歌曲", ui.localPlaylist != null ? "从本地歌曲的更多菜单加入曲目。" : "");
            } else if (ui.localGroup != null && ui.localGroup.category == 1) {
                renderAlbumDetailView(tracks, ui.localGroup.label);
            } else {
                ui.content.addView(ui.text(tracks.size() + " 首歌曲", 13, MUTED, false));
                ui.catalogueScreens.showTracks(tracks, false);
            }
            return;
        }

        pinLibraryTabs();
        LinearLayout meta = ui.row();
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, 0, 0, ui.dp(4));
        TextView info = ui.text(library.size() + " 首歌曲 · " + library.folders().size() + " 个目录", 13, MUTED, false);
        meta.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        if (ui.localTab == 0 && !tracks.isEmpty()) {
            Button sort = ui.action("排序 · " + MainActivity.LIBRARY_SORTS[ui.localSort], "歌曲排序", this::chooseLocalSort);
            sort.setTextSize(12);
            meta.addView(sort);
        }
        ui.content.addView(meta);
        if (ui.localBusy) ui.content.addView(ui.text("正在扫描音乐…", 12, ACCENT, false));
        else if (!ui.localScanSummary.isEmpty()) ui.content.addView(ui.text(ui.localScanSummary, 12, MUTED, false));

        if (ui.localTab == 2) {
            ui.content.addView(ui.toolbar(ui.withIcon(ui.secondary("新建歌单", () -> newLocalPlaylist(null)), "plus", ACCENT),
                    ui.secondary("导入 M3U", ui.importExport::pickPlaylistSource)));
            for (String name : library.playlists()) {
                ui.content.addView(ui.entry(name, library.search("", name).size() + " 首歌曲", "", () -> {
                    ui.localPlaylist = name;
                    ui.render();
                }));
            }
        } else if (tracks.isEmpty()) {
            LinearLayout empty = ui.panel("添加本地音乐", "导入歌曲，或授权音乐目录扫描其中的音频文件。");
            empty.addView(ui.toolbar(ui.withIcon(ui.primary("扫描文件夹", ui.importExport::pickFolder), "folder", BG),
                    ui.secondary("选择音频文件", ui.importExport::pickAudio)));
        } else if (ui.localTab == 0) {
            sortLocal(tracks);
            ui.catalogueScreens.showTracks(tracks, false);
        } else {
            java.util.Map<String, LocalGroup> grouped = new java.util.LinkedHashMap<>();
            for (TidalClient.Track track : tracks) {
                LocalGroup candidate = LocalLibrary.groupFor(track, ui.localTab);
                LocalGroup group = grouped.get(candidate.key);
                if (group == null) {
                    group = candidate;
                    grouped.put(group.key, group);
                }
                group.members.add(track);
            }
            List<LocalGroup> groups = new ArrayList<>(grouped.values());
            java.text.Collator collator = java.text.Collator.getInstance();
            java.util.Collections.sort(groups, (a, b) -> {
                if (ui.localTab == 6) {
                    int yearOrder = Integer.compare(yearNumber(b.label), yearNumber(a.label));
                    if (yearOrder != 0) return yearOrder;
                }
                int labelOrder = collator.compare(a.label, b.label);
                return labelOrder != 0 ? labelOrder : collator.compare(a.detail, b.detail);
            });
            if (!groups.isEmpty()) {
                Button locate = ui.action("定位", "快速定位", () -> chooseGroup(groups));
                locate.setTextSize(12);
                meta.addView(locate);
            }
            showGroups(groups);
        }
    }

    /** Category labels stay put while the library list scrolls. */
    void pinLibraryTabs() {
        android.widget.HorizontalScrollView categories = new android.widget.HorizontalScrollView(ui);
        categories.setHorizontalScrollBarEnabled(false);
        categories.setBackgroundColor(BG);
        LinearLayout tabs = ui.row();
        tabs.setBaselineAligned(false);
        tabs.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(4));
        View selected = null;
        for (int i = 0; i < MainActivity.LIBRARY_TABS.length; i++) {
            int tab = i;
            boolean on = ui.localTab == i;
            TextView label = ui.text(MainActivity.LIBRARY_TABS[i], 15, on ? GOLD : MUTED, true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(6));
            label.setMinWidth(ui.dp(48));
            GradientDrawable mark = new GradientDrawable();
            mark.setColor(on ? GOLD : 0x00000000);
            mark.setSize(ui.dp(18), ui.dp(2));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, mark);
            label.setCompoundDrawablePadding(ui.dp(4));
            label.setContentDescription(MainActivity.LIBRARY_TABS[i]);
            label.setOnClickListener(v -> {
                if (ui.localTab == tab) return;
                ui.localTab = tab;
                ui.render();
            });
            tabs.addView(label, new LinearLayout.LayoutParams(-2, -2));
            if (on) selected = label;
        }
        categories.addView(tabs, new android.widget.FrameLayout.LayoutParams(-2, -2));
        View anchor = ui.listStage != null ? ui.listStage : null;
        int index = anchor == null ? -1 : ui.root.indexOfChild(anchor);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, -2);
        if (index >= 0) ui.root.addView(categories, index, bar);
        else ui.content.addView(categories, 0, bar);
        if (selected != null) {
            View shown = selected;
            categories.post(() -> categories.scrollTo(Math.max(0, shown.getLeft() - ui.dp(24)), 0));
        }
    }

    void libraryTools() {
        LocalLibrary library = ui.playback == null ? null : ui.playback.localLibrary;
        java.util.List<String> labels = new ArrayList<>();
        labels.add("已下载");
        if (library != null && library.hasFolders()) labels.add(ui.localBusy ? "正在扫描…" : "更新音乐库");
        labels.add("补全歌词");
        new AlertDialog.Builder(ui).setTitle("音乐库")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String choice = labels.get(which);
                    if ("已下载".equals(choice)) {
                        ui.offlineAlbum = null;
                        ui.open(Page.OFFLINE);
                    } else if ("更新音乐库".equals(choice) && library != null) {
                        ui.importExport.scanDirectories(library.folders());
                    } else if (choice.startsWith("正在")) {
                        ui.toast("正在扫描音乐…");
                    } else {
                        ui.trackExtras.showBatchLyricsFill();
                    }
                }).show();
    }

    /** Albums, artists and the other groupings, with a letter index once the list is long. */
    void showGroups(List<LocalGroup> groups) {
        String[] keys = new String[groups.size()];
        java.util.LinkedHashMap<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            keys[i] = sectionKey(groups.get(i).label);
            if (!index.containsKey(keys[i])) index.put(keys[i], i);
        }
        int columns = ui.localTab == 1 ? 2 : ui.localTab == 3 ? 3 : 1;
        ui.catalogueScreens.showGrowing(groups.size(), (from, to) -> {
            int cursor = from;
            while (cursor < to) {
                if (cursor == 0 || !keys[cursor].equals(keys[cursor - 1])) {
                    TextView head = ui.text(keys[cursor], 12, GOLD, true);
                    head.setPadding(ui.dp(4), ui.dp(12), ui.dp(36), ui.dp(2));
                    ui.indexAnchors.put(cursor, head);
                    ui.content.addView(head);
                }
                if (columns == 1) {
                    LocalGroup group = groups.get(cursor);
                    String detail = group.members.size() + " 首歌曲";
                    if (!group.detail.isEmpty()) detail = group.detail + " · " + detail;
                    ui.content.addView(ui.entry(group.label, detail, "", () -> openGroup(group)));
                    cursor++;
                    continue;
                }
                LinearLayout gridRow = ui.row();
                gridRow.setPadding(0, 0, ui.dp(20), ui.dp(8));
                String letter = keys[cursor];
                int filled = 0;
                while (filled < columns && cursor < to && keys[cursor].equals(letter)) {
                    LocalGroup group = groups.get(cursor);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    if (filled > 0) cell.leftMargin = ui.dp(8);
                    gridRow.addView(ui.localTab == 1 ? albumCard(group) : artistCard(group), cell);
                    filled++;
                    cursor++;
                }
                while (filled < columns) {
                    View spacer = new View(ui);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    cell.leftMargin = ui.dp(8);
                    gridRow.addView(spacer, cell);
                    filled++;
                }
                ui.content.addView(gridRow);
            }
        });
        if (index.size() > 1) attachIndex(index);
    }

    void attachIndex(java.util.LinkedHashMap<String, Integer> index) {
        if (ui.listStage == null) return;
        boolean wide = false;
        for (String key : index.keySet()) if (key.length() > 1) wide = true;
        ui.content.setPadding(ui.content.getPaddingLeft(), ui.content.getPaddingTop(), ui.dp(wide ? 44 : 28), ui.content.getPaddingBottom());
        LinearLayout rail = ui.column();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, ui.dp(4), ui.dp(2), ui.dp(4));
        for (java.util.Map.Entry<String, Integer> entry : index.entrySet()) {
            TextView tick = ui.text(entry.getKey(), wide ? 8 : 10, MUTED, true);
            tick.setGravity(Gravity.CENTER);
            tick.setIncludeFontPadding(false);
            tick.setContentDescription("定位到 " + entry.getKey());
            int at = entry.getValue();
            tick.setOnClickListener(v -> scrollToSection(at));
            rail.addView(tick, new LinearLayout.LayoutParams(-2, 0, 1));
        }
        ui.listStage.addView(rail, new android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));
    }

    void scrollToSection(int index) {
        ui.catalogueScreens.fillTo(index);
        View anchor = ui.indexAnchors.get(index);
        if (anchor == null || ui.listScroll == null) return;
        ui.listScroll.post(() -> ui.listScroll.smoothScrollTo(0, anchor.getTop()));
    }

    String sectionKey(String label) {
        if (ui.localTab == 6) {
            java.util.regex.Matcher year = YEAR_KEY.matcher(label == null ? "" : label);
            return year.find() ? year.group() : "#";
        }
        return indexLetter(label);
    }

    /** First letter for the library index. Chinese names use pinyin on Android 10 and later. */
    static String indexLetter(String label) {
        if (label == null) return "#";
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return "#";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                if (nameLatin == null) nameLatin = android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII; Upper");
                String converted = ((android.icu.text.Transliterator) nameLatin).transliterate(trimmed);
                if (!converted.isEmpty()) trimmed = converted;
            } catch (Exception ignored) {}
        }
        int point = trimmed.codePointAt(0);
        if (point >= 'a' && point <= 'z') return String.valueOf((char) (point - 32));
        if (point >= 'A' && point <= 'Z') return String.valueOf((char) point);
        return "#";
    }

    LinearLayout albumCard(LocalGroup group) {
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(SURFACE, 10));
        card.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(8));
        card.setOnClickListener(v -> openGroup(group));

        ImageView cover = ui.art(0);
        cover.setBackground(ui.shape(RAISED, 8));
        cover.setClipToOutline(true);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(-1, ui.dp(120));
        card.addView(cover, imgLp);

        if (!group.members.isEmpty()) {
            ui.artwork.load(cover, group.members.get(0), 320);
        }

        TextView title = ui.single(group.label, 12, INK, true);
        title.setPadding(0, ui.dp(6), 0, ui.dp(1));
        card.addView(title);

        String subtitle = group.detail;
        if (subtitle.isEmpty() && !group.members.isEmpty()) {
            subtitle = group.members.get(0).artist;
        }
        if (!group.members.isEmpty()) {
            String year = group.members.get(0).payload.optString("year", "");
            if (!year.isEmpty()) subtitle += (subtitle.isEmpty() ? "" : " · ") + year;
        }
        TextView sub = ui.single(subtitle, 10, MUTED, false);
        card.addView(sub);
        return card;
    }

    LinearLayout artistCard(LocalGroup group) {
        LinearLayout card = ui.column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(ui.shape(SURFACE, 10));
        card.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10));
        card.setOnClickListener(v -> openGroup(group));

        ImageView avatar = ui.art(60);
        avatar.setBackground(ui.shape(RAISED, 30));
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!group.members.isEmpty()) {
            ui.artwork.loadPhoto(avatar, group.members.get(0), 160);
        }
        card.addView(avatar, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));

        TextView name = ui.single(group.label, 12, INK, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, ui.dp(6), 0, ui.dp(1));
        card.addView(name);

        TextView count = ui.single(group.members.size() + " 首歌曲", 10, MUTED, false);
        count.setGravity(Gravity.CENTER);
        card.addView(count);
        return card;
    }

    void renderAlbumDetailView(List<TidalClient.Track> tracks, String albumName) {
        if (tracks.isEmpty()) return;
        TidalClient.Track sample = tracks.get(0);
        LinearLayout banner = ui.row();
        banner.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        banner.setBackground(ui.shape(SURFACE, 12));

        ImageView coverView = ui.art(84);
        coverView.setBackground(ui.shape(RAISED, 8));
        ui.artwork.load(coverView, sample, 320);
        banner.addView(coverView);

        LinearLayout meta = ui.column();
        meta.setPadding(ui.dp(12), 0, 0, 0);

        TextView aTitle = ui.text(sample.album.isEmpty() ? albumName : sample.album, 16, INK, true);
        aTitle.setMaxLines(2);
        aTitle.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(aTitle);

        TextView aArtist = ui.text(sample.artist, 12, ACCENT, false);
        aArtist.setPadding(0, ui.dp(2), 0, ui.dp(4));
        meta.addView(aArtist);

        LinearLayout badges = ui.row();
        badges.setGravity(Gravity.CENTER_VERTICAL);
        AudioBadge spec = ui.audioSpecBadge(sample);
        if (spec != null) {
            TextView bView = ui.text(spec.text, 8, spec.textColor, true);
            bView.setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1));
            bView.setBackground(ui.shapeWithStroke(spec.bgColor, spec.borderColor, 1, 3));
            badges.addView(bView);
        }
        String year = sample.payload.optString("year", "");
        String infoText = (year.isEmpty() ? "" : year + " · ") + tracks.size() + " 首歌曲";
        TextView iView = ui.text(infoText, 10, MUTED, false);
        iView.setPadding(ui.dp(6), 0, 0, 0);
        badges.addView(iView);
        meta.addView(badges);

        banner.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        ui.content.addView(banner);

        Button all = ui.withIcon(ui.primary("播放全部", () -> ui.catalogueScreens.playList(tracks, 0, false)), "play", BG);
        Button random = ui.withIcon(ui.secondary("随机播放", () -> ui.catalogueScreens.playList(tracks, 0, true)), "shuffle", ACCENT);
        ui.content.addView(ui.toolbar(all, random));

        int lastDisc = -1;
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            TidalClient.Track track = tracks.get(i);
            int disc = track.payload.optInt("disc_number", 0);
            if (disc > 0 && disc != lastDisc) {
                lastDisc = disc;
                TextView discHeader = ui.text("Disc " + disc, 12, GOLD, true);
                discHeader.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(3));
                ui.content.addView(discHeader);
            }
            ui.content.addView(ui.catalogueScreens.albumTrackEntry(track, i + 1, () -> ui.catalogueScreens.playList(tracks, index, false)));
        }
    }

    void openGroup(LocalGroup group) {
        ui.localGroup = group;
        ui.render();
    }

    void chooseGroup(List<LocalGroup> groups) {
        EditText query = new EditText(ui);
        query.setSingleLine(true);
        query.setHint("输入分类名称");
        List<String> labels = new ArrayList<>();
        List<LocalGroup> matches = new ArrayList<>();
        android.widget.ArrayAdapter<String> results = new android.widget.ArrayAdapter<>(ui,
                android.R.layout.simple_list_item_1, labels);
        android.widget.ListView list = new android.widget.ListView(ui);
        list.setAdapter(results);
        LinearLayout body = ui.column();
        body.addView(query);
        body.addView(list, new LinearLayout.LayoutParams(-1, ui.dp(280)));
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("快速定位")
                .setView(body).setNegativeButton("取消", null).create();
        QueryFilter filter = term -> {
            String needle = term.toLowerCase(Locale.ROOT).trim();
            matches.clear();
            results.setNotifyOnChange(false);
            results.clear();
            for (LocalGroup group : groups) {
                String full = group.label + " " + group.detail;
                if (full.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(group);
                    results.add(group.detail.isEmpty() ? group.label : group.label + " · " + group.detail);
                }
            }
            results.notifyDataSetChanged();
        };
        filter.apply("");
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.apply(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable editable) {}
        });
        list.setOnItemClickListener((view, item, position, id) -> {
            dialog.dismiss();
            openGroup(matches.get(position));
        });
        dialog.show();
    }

    void chooseLocalSort() {
        new AlertDialog.Builder(ui).setTitle("歌曲排序")
                .setSingleChoiceItems(MainActivity.LIBRARY_SORTS, ui.localSort, (dialog, index) -> {
                    ui.localSort = index;
                    ui.getPreferences(android.content.Context.MODE_PRIVATE).edit().putInt("local_sort", index).apply();
                    dialog.dismiss();
                    ui.render();
                }).setNegativeButton("取消", null).show();
    }

    void sortLocal(List<TidalClient.Track> tracks) {
        int order = ui.localSort;
        if (order == 4) return;
        java.text.Collator collator = java.text.Collator.getInstance();
        java.util.Collections.sort(tracks, (a, b) -> {
            int key;
            switch (order) {
                case 1: key = collator.compare(a.artist, b.artist); break;
                case 2: key = collator.compare(a.album, b.album); break;
                case 3: key = Integer.compare(yearNumber(b.payload.optString("year")), yearNumber(a.payload.optString("year"))); break;
                default: key = 0; break;
            }
            return key != 0 ? key : collator.compare(a.title, b.title);
        });
    }

    static int yearNumber(String year) {
        try { return Integer.parseInt(year); }
        catch (NumberFormatException absent) { return 0; }
    }

    static int trackNumber(TidalClient.Track track, String field, int fallback) {
        String value = track.payload.optString(field).split("/", 2)[0].trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException absent) { return fallback; }
    }

    static void sortAlbum(List<TidalClient.Track> tracks) {
        java.util.Collections.sort(tracks, (a, b) -> {
            int disc = Integer.compare(trackNumber(a, "disc_number", 1), trackNumber(b, "disc_number", 1));
            if (disc != 0) return disc;
            int track = Integer.compare(trackNumber(a, "track_number", Integer.MAX_VALUE),
                    trackNumber(b, "track_number", Integer.MAX_VALUE));
            return track != 0 ? track : a.title.compareToIgnoreCase(b.title);
        });
    }

    void localResults(String query) {
        if (ui.remoteScreens.remoteMode()) {
            ui.remoteLibTab = 0;
            ui.remoteLibQuery = query;
            ui.remoteLibPlaylist = "";
            ui.remoteLibGroup = "";
            ui.remoteLibGroupTitle = "";
            if (query.isEmpty()) {
                ui.empty("搜索 " + ui.devices.targetName() + " 的本地音乐", "可以按歌曲、艺人、专辑、流派或年份查找。");
                return;
            }
            ui.remoteScreens.remoteLibraryPage(false);
            return;
        }
        if (query.isEmpty()) {
            ui.empty("搜索本地音乐", "可以按歌曲、艺人、专辑、流派或年份查找。");
        } else if (ui.playback != null) {
            List<TidalClient.Track> result = ui.playback.localLibrary.search(query, null);
            List<LocalGroup> albums = localGroups(result, 1, query);
            List<LocalGroup> artists = localGroups(result, 3, query);
            if (result.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
                ui.empty("没有找到匹配的本地音乐", "");
                return;
            }
            ui.homeScreens.groupShelf("专辑", null, albums);
            ui.homeScreens.groupShelf("艺人", null, artists);
            if (!result.isEmpty()) {
                ui.section("歌曲");
                sortLocal(result);
                ui.catalogueScreens.showTracks(result, false);
            }
        }
    }

    /** Albums or artists whose name contains the query. */
    static List<LocalGroup> localGroups(List<TidalClient.Track> tracks, int category, String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, LocalGroup> grouped = new java.util.LinkedHashMap<>();
        for (TidalClient.Track track : tracks) {
            LocalGroup candidate = LocalLibrary.groupFor(track, category);
            if (!candidate.label.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            LocalGroup group = grouped.get(candidate.key);
            if (group == null) {
                if (grouped.size() >= 12) continue;
                group = candidate;
                grouped.put(group.key, group);
            }
            if (group.members.size() < 40) group.members.add(track);
        }
        return new ArrayList<>(grouped.values());
    }

    void importMenu() {
        new AlertDialog.Builder(ui).setTitle("添加本地音乐")
                .setItems(new String[]{"扫描文件夹", "选择音频文件", "管理已授权目录"}, (dialog, which) -> {
                    if (which == 0) ui.importExport.pickFolder();
                    else if (which == 1) ui.importExport.pickAudio();
                    else ui.importExport.chooseFolders();
                }).show();
    }

    void newLocalPlaylist(TidalClient.Track initial) {
        EditText name = new EditText(ui);
        name.setSingleLine(true);
        name.setHint("歌单名称");
        AlertDialog dialog = new AlertDialog.Builder(ui).setTitle("新建本地歌单").setView(name)
                .setNegativeButton("取消", null).setPositiveButton("创建", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (ui.playback == null) return;
            String value = name.getText().toString().trim();
            if (!ui.playback.localLibrary.create(value)) {
                name.setError("请输入新的名称（1–64 个字符）");
                return;
            }
            if (initial != null) ui.playback.localLibrary.add(value, initial);
            dialog.dismiss();
            ui.playback.libraryChanged();
            ui.toast("已创建歌单");
        });
    }

    void deleteLocalPlaylist() {
        String name = ui.localPlaylist;
        new AlertDialog.Builder(ui).setTitle("删除歌单 " + name + "？")
                .setMessage("音频文件仍会保留。").setNegativeButton("取消", null)
                .setPositiveButton("删除歌单", (dialog, which) -> {
                    if (ui.playback == null) return;
                    ui.playback.localLibrary.deletePlaylist(name);
                    ui.localPlaylist = null;
                    ui.playback.libraryChanged();
                }).show();
    }
}
