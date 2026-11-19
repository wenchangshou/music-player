use chrono::Timelike;
use eframe::egui::{self, RichText};

use crate::app::{Action, App, UiState};
use crate::library::LibState;
use crate::playback::PlayContext;
use crate::route::{LibTab, Route};
use crate::ui::widgets::*;

pub fn show(app: &App, _st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>) {
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        ui.add_space(8.0);
        let h = chrono::Local::now().hour();
        let greet = match h {
            5..=10 => "早上好",
            11..=13 => "中午好",
            14..=17 => "下午好",
            _ => "晚上好",
        };
        ui.label(RichText::new(greet).size(30.0).strong());

        if !app.recent_albums.is_empty() {
            section_header(ui, "最近播放", None, false);
            shelf(ui, "recent", |ui| {
                for a in app.recent_albums.iter().take(16) {
                    album_card(ui, app, a, acts);
                }
            });
        }

        match &app.lib.state {
            LibState::Loading | LibState::Idle if app.api.can_browse() => {
                loading(ui, "正在同步你的 TIDAL 收藏…");
                return;
            }
            LibState::Failed(e) => {
                if error_box(ui, &format!("收藏加载失败：{}", e)) {
                    acts.push(Action::ReloadLibrary);
                }
            }
            _ => {}
        }
        if !app.api.can_browse() {
            empty_state(ui, "🔒", "尚未授权 TIDAL，请到“设置”完成登录，或遥控一台已登录 TIDAL 的设备");
            return;
        }

        let d = &app.lib.data;
        if !d.albums.is_empty() {
            if section_header(ui, "收藏的专辑", Some(d.albums.len()), true) {
                acts.push(Action::Go(Route::Library(LibTab::Albums)));
            }
            shelf(ui, "fav_albums", |ui| {
                for f in d.albums.iter().take(16) {
                    album_card(ui, app, &f.item, acts);
                }
            });
        }

        let lists: Vec<_> = d.my_playlists.iter().chain(d.playlists.iter().map(|f| &f.item)).collect();
        if !lists.is_empty() {
            if section_header(ui, "歌单", Some(lists.len()), true) {
                acts.push(Action::Go(Route::Library(LibTab::Playlists)));
            }
            shelf(ui, "fav_playlists", |ui| {
                for p in lists.iter().take(16) {
                    playlist_card(ui, app, p, acts);
                }
            });
        }

        if !d.artists.is_empty() {
            if section_header(ui, "收藏的歌手", Some(d.artists.len()), true) {
                acts.push(Action::Go(Route::Library(LibTab::Artists)));
            }
            shelf(ui, "fav_artists", |ui| {
                for f in d.artists.iter().take(16) {
                    artist_card(ui, app, &f.item, acts);
                }
            });
        }

        if !d.tracks.is_empty() {
            if section_header(ui, "收藏的歌曲", Some(d.tracks.len()), true) {
                acts.push(Action::Go(Route::Library(LibTab::Tracks)));
            }
            let tracks: Vec<_> = d.tracks.iter().take(8).map(|f| f.item.clone()).collect();
            let ctx = Some(PlayContext { label: "我的收藏 · 歌曲".into(), route: Route::Library(LibTab::Tracks) });
            track_list(ui, app, "home_tracks", &tracks, TrackOpts { cover: true, album_col: true, ..Default::default() }, ctx, acts);
        }

        if d.albums.is_empty() && d.artists.is_empty() && d.tracks.is_empty() && lists.is_empty() && app.recent_albums.is_empty() {
            empty_state(ui, "♡", "你的收藏还是空的 —— 去搜索喜欢的音乐，点 ♡ 收藏吧");
            ui.vertical_centered(|ui| {
                if ui.button("🔍 去搜索").clicked() {
                    acts.push(Action::Go(Route::Search(Default::default(), String::new())));
                }
            });
        }
        ui.add_space(30.0);
    });
}
