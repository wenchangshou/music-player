use eframe::egui::{self, Align, Color32, Layout, RichText};

use crate::app::{Action, App, LibSort, UiState};
use crate::library::LibState;
use crate::playback::PlayContext;
use crate::route::{LibTab, Route};
use crate::theme::TEXT_WEAK;
use crate::types::{AlbumItem, PlaylistItem, TrackItem};
use crate::ui::widgets::*;

fn matches(filter: &str, fields: &[&str]) -> bool {
    filter.is_empty() || fields.iter().any(|f| f.to_lowercase().contains(filter))
}

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>, tab: LibTab) {
    let d = &app.lib.data;
    ui.add_space(8.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new("我的收藏").size(30.0).strong());
        ui.add_space(16.0);
        for (t, label) in [
            (LibTab::Albums, format!("专辑 {}", d.albums.len())),
            (LibTab::Artists, format!("歌手 {}", d.artists.len())),
            (LibTab::Tracks, format!("歌曲 {}", d.tracks.len())),
            (LibTab::Playlists, format!("歌单 {}", d.playlists.len() + d.my_playlists.len())),
        ] {
            if ui.selectable_label(tab == t, RichText::new(label).size(14.5)).clicked() && tab != t {
                acts.push(Action::Go(Route::Library(t)));
            }
        }
    });

    ui.add_space(6.0);
    ui.horizontal(|ui| {
        ui.add(
            egui::TextEdit::singleline(&mut st.lib_filter)
                .hint_text("🔎 在收藏中筛选")
                .desired_width(240.0)
                .background_color(Color32::from_rgb(34, 34, 40)),
        );
        if matches!(tab, LibTab::Albums | LibTab::Tracks) {
            egui::ComboBox::from_id_salt("lib_sort")
                .selected_text(match st.lib_sort {
                    LibSort::Recent => "最近添加",
                    LibSort::Title => "名称",
                    LibSort::Artist => "歌手",
                    LibSort::Release => "发行日期",
                })
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut st.lib_sort, LibSort::Recent, "最近添加");
                    ui.selectable_value(&mut st.lib_sort, LibSort::Title, "名称");
                    ui.selectable_value(&mut st.lib_sort, LibSort::Artist, "歌手");
                    if tab == LibTab::Albums {
                        ui.selectable_value(&mut st.lib_sort, LibSort::Release, "发行日期");
                    }
                });
        }
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if ui.button("↻ 同步").on_hover_text("重新从 TIDAL 拉取收藏").clicked() {
                acts.push(Action::ReloadLibrary);
            }
            if app.lib.state == LibState::Loading {
                ui.spinner();
            }
        });
    });

    if let LibState::Failed(e) = &app.lib.state {
        if error_box(ui, e) {
            acts.push(Action::ReloadLibrary);
        }
    }

    let f = st.lib_filter.trim().to_lowercase();
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        ui.add_space(12.0);
        match tab {
            LibTab::Albums => {
                let mut items: Vec<&AlbumItem> = d
                    .albums
                    .iter()
                    .map(|x| &x.item)
                    .filter(|a| matches(&f, &[&a.title, &a.artist_names()]))
                    .collect();
                match st.lib_sort {
                    LibSort::Recent => {}
                    LibSort::Title => items.sort_by_key(|a| a.title.to_lowercase()),
                    LibSort::Artist => items.sort_by_key(|a| a.artist_names().to_lowercase()),
                    LibSort::Release => items.sort_by(|a, b| b.release_date.cmp(&a.release_date)),
                }
                if items.is_empty() {
                    return empty_state(ui, "💿", "没有收藏的专辑。打开任意专辑点 ♡ 收藏");
                }
                grid(ui, |ui| {
                    for a in items {
                        album_card(ui, app, a, acts);
                    }
                });
            }
            LibTab::Artists => {
                let items: Vec<_> = d.artists.iter().map(|x| &x.item).filter(|a| matches(&f, &[&a.name])).collect();
                if items.is_empty() {
                    return empty_state(ui, "👤", "没有关注的歌手");
                }
                grid(ui, |ui| {
                    for a in items {
                        artist_card(ui, app, a, acts);
                    }
                });
            }
            LibTab::Tracks => {
                let mut items: Vec<TrackItem> = d
                    .tracks
                    .iter()
                    .map(|x| x.item.clone())
                    .filter(|t| matches(&f, &[&t.title, &t.artist_names(), t.album.as_ref().map_or("", |a| a.title.as_str())]))
                    .collect();
                match st.lib_sort {
                    LibSort::Title => items.sort_by_key(|t| t.title.to_lowercase()),
                    LibSort::Artist => items.sort_by_key(|t| t.artist_names().to_lowercase()),
                    _ => {}
                }
                if items.is_empty() {
                    return empty_state(ui, "🎵", "没有收藏的歌曲。在歌曲行上点 ♡ 收藏");
                }
                let ctx = Some(PlayContext { label: "我的收藏 · 歌曲".into(), route: Route::Library(LibTab::Tracks) });
                ui.horizontal(|ui| {
                    if play_button(ui, 44.0).on_hover_text("全部播放").clicked() {
                        acts.push(Action::PlayTracks { tracks: items.clone(), index: 0, shuffle: false, context: ctx.clone() });
                    }
                    if pill(ui, "🔀 随机播放").clicked() {
                        acts.push(Action::PlayTracks { tracks: items.clone(), index: 0, shuffle: true, context: ctx.clone() });
                    }
                    ui.label(RichText::new(format!("{} 首", items.len())).color(TEXT_WEAK));
                });
                ui.add_space(8.0);
                track_list(ui, app, "lib_tracks", &items, TrackOpts { cover: true, album_col: true, ..Default::default() }, ctx, acts);
            }
            LibTab::Playlists => {
                let items: Vec<&PlaylistItem> = d
                    .my_playlists
                    .iter()
                    .chain(d.playlists.iter().map(|x| &x.item))
                    .filter(|p| matches(&f, &[&p.title]))
                    .collect();
                if items.is_empty() {
                    return empty_state(ui, "📃", "没有歌单");
                }
                grid(ui, |ui| {
                    for p in items {
                        playlist_card(ui, app, p, acts);
                    }
                });
            }
        }
        ui.add_space(30.0);
    });
}
