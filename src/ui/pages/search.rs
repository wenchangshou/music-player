use eframe::egui::{self, RichText};

use crate::app::{Action, App, Load, SearchFilter, UiState};
use crate::netease::SearchSource;
use crate::playback::PlayContext;
use crate::route::Route;
use crate::theme::TEXT_WEAK;
use crate::ui::widgets::*;

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>, source: SearchSource, query: &str) {
    if query.is_empty() {
        ui.add_space(8.0);
        ui.label(RichText::new("搜索").size(30.0).strong());
        ui.label(RichText::new("在上方搜索框输入歌手、专辑、歌曲或歌单名称，回车搜索").color(TEXT_WEAK));
        if !app.recent_searches.is_empty() {
            section_header(ui, "最近搜索", None, false);
            ui.horizontal_wrapped(|ui| {
                for q in &app.recent_searches {
                    if pill(ui, q).clicked() {
                        acts.push(Action::Search(q.clone()));
                    }
                }
            });
        }
        return;
    }

    let sr = match app.cache.searches.get(&(source, query.to_string())) {
        None | Some(Load::Loading) => return loading(ui, &format!("正在搜索 “{}” …", query)),
        Some(Load::Failed(e)) => {
            if error_box(ui, e) {
                acts.push(Action::Refresh);
            }
            return;
        }
        Some(Load::Ready(sr)) => sr,
    };

    let tracks = sr.tracks.as_ref().map(|l| l.items.as_slice()).unwrap_or(&[]);
    let artists = sr.artists.as_ref().map(|l| l.items.as_slice()).unwrap_or(&[]);
    let albums = sr.albums.as_ref().map(|l| l.items.as_slice()).unwrap_or(&[]);
    let playlists = sr.playlists.as_ref().map(|l| l.items.as_slice()).unwrap_or(&[]);

    ui.add_space(4.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(format!("“{}”", query)).size(24.0).strong());
        ui.add_space(12.0);
        let f = &mut st.search_filter;
        for (v, label) in [
            (SearchFilter::All, "全部".to_string()),
            (SearchFilter::Tracks, format!("歌曲 {}", tracks.len())),
            (SearchFilter::Artists, format!("歌手 {}", artists.len())),
            (SearchFilter::Albums, format!("专辑 {}", albums.len())),
            (SearchFilter::Playlists, format!("歌单 {}", playlists.len())),
        ] {
            if ui.selectable_label(*f == v, RichText::new(label).size(14.0)).clicked() {
                *f = v;
            }
        }
    });

    if tracks.is_empty() && artists.is_empty() && albums.is_empty() && playlists.is_empty() {
        return empty_state(ui, "🔎", "没有找到相关结果，换个关键词试试");
    }

    let filter = st.search_filter;
    let all = filter == SearchFilter::All;
    let ctx = Some(PlayContext { label: format!("{} 搜索 · {}", source.label(), query), route: Route::Search(source, query.to_string()) });

    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        if (all || filter == SearchFilter::Tracks) && !tracks.is_empty() {
            if section_header(ui, "歌曲", Some(tracks.len()), all) {
                st.search_filter = SearchFilter::Tracks;
            }
            let shown = if all { &tracks[..tracks.len().min(6)] } else { tracks };
            track_list(ui, app, "search_tracks", shown, TrackOpts { cover: true, album_col: true, ..Default::default() }, ctx.clone(), acts);
        }
        if (all || filter == SearchFilter::Artists) && !artists.is_empty() {
            if section_header(ui, "歌手", Some(artists.len()), all) {
                st.search_filter = SearchFilter::Artists;
            }
            let body = |ui: &mut egui::Ui, acts: &mut Vec<Action>| {
                for a in artists.iter().take(if all { 14 } else { usize::MAX }) {
                    artist_card(ui, app, a, acts);
                }
            };
            if all {
                shelf(ui, "s_artists", |ui| body(ui, acts));
            } else {
                grid(ui, |ui| body(ui, acts));
            }
        }
        if (all || filter == SearchFilter::Albums) && !albums.is_empty() {
            if section_header(ui, "专辑", Some(albums.len()), all) {
                st.search_filter = SearchFilter::Albums;
            }
            let body = |ui: &mut egui::Ui, acts: &mut Vec<Action>| {
                for a in albums.iter().take(if all { 14 } else { usize::MAX }) {
                    album_card(ui, app, a, acts);
                }
            };
            if all {
                shelf(ui, "s_albums", |ui| body(ui, acts));
            } else {
                grid(ui, |ui| body(ui, acts));
            }
        }
        if (all || filter == SearchFilter::Playlists) && !playlists.is_empty() {
            if section_header(ui, "歌单", Some(playlists.len()), all) {
                st.search_filter = SearchFilter::Playlists;
            }
            let body = |ui: &mut egui::Ui, acts: &mut Vec<Action>| {
                for p in playlists.iter().take(if all { 14 } else { usize::MAX }) {
                    playlist_card(ui, app, p, acts);
                }
            };
            if all {
                shelf(ui, "s_playlists", |ui| body(ui, acts));
            } else {
                grid(ui, |ui| body(ui, acts));
            }
        }
        ui.add_space(30.0);
    });
}
