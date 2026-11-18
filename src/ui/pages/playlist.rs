use eframe::egui::{self, RichText};

use crate::app::{Action, App, Load};
use crate::library::FavTarget;
use crate::playback::PlayContext;
use crate::route::Route;
use crate::theme::{self, TEXT_WEAK};
use crate::types::fmt_total;
use crate::ui::widgets::*;

pub fn show(app: &App, ui: &mut egui::Ui, acts: &mut Vec<Action>, uuid: &str) {
    let page = match app.cache.playlists.get(uuid) {
        None | Some(Load::Loading) => return loading(ui, "正在加载歌单…"),
        Some(Load::Failed(e)) => {
            if error_box(ui, e) {
                acts.push(Action::Refresh);
            }
            return;
        }
        Some(Load::Ready(p)) => p,
    };
    let p = &page.playlist;
    let mine = app.lib.is_mine(&p.uuid);

    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        hero_gradient(ui, egui::Color32::from_rgb(40, 90, 110), 360.0);
        ui.add_space(16.0);
        ui.horizontal(|ui| {
            artwork(ui, p.cover(), 232.0, 640, false, "📃");
            ui.add_space(22.0);
            ui.vertical(|ui| {
                ui.add_space(34.0);
                ui.label(RichText::new(if mine { "我的歌单" } else { "歌单" }).strong().color(TEXT_WEAK));
                ui.add(egui::Label::new(RichText::new(&p.title).size(34.0).strong()).wrap());
                let total = p.duration.unwrap_or_else(|| page.tracks.iter().filter_map(|t| t.duration).sum());
                ui.label(
                    RichText::new(format!("{} · {} 首歌曲 · {}", p.creator_name(), page.tracks.len(), fmt_total(total)))
                        .color(TEXT_WEAK),
                );
                if let Some(d) = p.description.as_deref().filter(|d| !d.trim().is_empty()) {
                    ui.add_space(4.0);
                    ui.add(egui::Label::new(RichText::new(d).size(13.0).color(TEXT_WEAK)).wrap());
                }
            });
        });

        ui.add_space(18.0);
        let ctx = Some(PlayContext { label: format!("歌单 · {}", p.title), route: Route::Playlist(p.uuid.clone()) });
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 12.0;
            if play_button(ui, 52.0).on_hover_text("播放").clicked() {
                acts.push(Action::PlayTracks { tracks: page.tracks.clone(), index: 0, shuffle: false, context: ctx.clone() });
            }
            if pill(ui, "🔀 随机播放").clicked() {
                acts.push(Action::PlayTracks { tracks: page.tracks.clone(), index: 0, shuffle: true, context: ctx.clone() });
            }
            if crate::netease::playlist_id(&p.uuid).is_none() {
                if !mine {
                    let fav = app.lib.has_playlist(&p.uuid);
                    let t = if fav { RichText::new("♥ 已收藏").color(theme::HEART) } else { RichText::new("♡ 收藏") };
                    if ui.add(egui::Button::new(t.size(14.0)).corner_radius(18).min_size(egui::vec2(0.0, 36.0))).clicked() {
                        acts.push(Action::ToggleFav(FavTarget::Playlist(p.clone())));
                    }
                }
                ui.menu_button(RichText::new("⬇ 下载 ▼").size(14.0), |ui| download_items(ui, &p.url(), &p.title, acts));
            }
            ui.menu_button(RichText::new("…").size(18.0), |ui| playlist_menu(ui, app, p, acts));
        });

        ui.add_space(14.0);
        if page.tracks.is_empty() {
            empty_state(ui, "🎵", "歌单是空的");
        }
        track_list(ui, app, "playlist_tracks", &page.tracks, TrackOpts { cover: true, album_col: true, ..Default::default() }, ctx, acts);
        ui.add_space(30.0);
    });
}
