use eframe::egui::{self, RichText};

use crate::app::{Action, App, Load};
use crate::library::FavTarget;
use crate::playback::PlayContext;
use crate::route::Route;
use crate::theme::{self, TEXT_WEAK};
use crate::ui::widgets::*;

pub fn show(app: &App, ui: &mut egui::Ui, acts: &mut Vec<Action>, id: u64) {
    let page = match app.cache.artists.get(&id) {
        None | Some(Load::Loading) => return loading(ui, "正在加载歌手…"),
        Some(Load::Failed(e)) => {
            if error_box(ui, e) {
                acts.push(Action::Refresh);
            }
            return;
        }
        Some(Load::Ready(p)) => p,
    };
    let ar = &page.artist;

    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        hero_gradient(ui, egui::Color32::from_rgb(70, 70, 90), 340.0);
        ui.add_space(16.0);
        ui.horizontal(|ui| {
            artwork(ui, ar.picture.as_deref(), 200.0, 750, true, "👤");
            ui.add_space(24.0);
            ui.vertical(|ui| {
                ui.add_space(46.0);
                ui.label(RichText::new("歌手").strong().color(TEXT_WEAK));
                ui.label(RichText::new(&ar.name).size(40.0).strong());
                let mut info = format!("{} 张专辑 · {} 张 EP / 单曲", page.albums.len(), page.singles.len());
                if let Some(p) = ar.popularity {
                    info.push_str(&format!(" · 热度 {}", p));
                }
                ui.label(RichText::new(info).color(TEXT_WEAK));
            });
        });

        ui.add_space(18.0);
        let ctx = Some(PlayContext { label: format!("{} · 热门歌曲", ar.name), route: Route::Artist(ar.id) });
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 12.0;
            if play_button(ui, 52.0).on_hover_text("播放热门歌曲").clicked() {
                acts.push(Action::PlayTracks { tracks: page.top_tracks.clone(), index: 0, shuffle: false, context: ctx.clone() });
            }
            if pill(ui, "🔀 随机播放").clicked() {
                acts.push(Action::PlayTracks { tracks: page.top_tracks.clone(), index: 0, shuffle: true, context: ctx.clone() });
            }
            if crate::netease::native_id(ar.id).is_none() {
                let fav = app.lib.has_artist(ar.id);
                let t = if fav { RichText::new("♥ 已关注").color(theme::HEART) } else { RichText::new("♡ 关注") };
                if ui.add(egui::Button::new(t.size(14.0)).corner_radius(18).min_size(egui::vec2(0.0, 36.0))).clicked() {
                    acts.push(Action::ToggleFav(FavTarget::Artist(ar.clone())));
                }
            }
            ui.menu_button(RichText::new("…").size(18.0), |ui| artist_menu(ui, app, ar, acts));
        });

        if !page.top_tracks.is_empty() {
            section_header(ui, "热门歌曲", None, false);
            track_list(ui, app, "artist_top", &page.top_tracks, TrackOpts { cover: true, album_col: true, ..Default::default() }, ctx, acts);
        }
        if !page.albums.is_empty() {
            section_header(ui, "专辑", Some(page.albums.len()), false);
            grid(ui, |ui| {
                for a in &page.albums {
                    album_card(ui, app, a, acts);
                }
            });
        }
        if !page.singles.is_empty() {
            section_header(ui, "EP 与单曲", Some(page.singles.len()), false);
            grid(ui, |ui| {
                for a in &page.singles {
                    album_card(ui, app, a, acts);
                }
            });
        }
        if page.albums.is_empty() && page.singles.is_empty() && page.top_tracks.is_empty() {
            empty_state(ui, "💿", "该歌手在当前地区暂无可用作品");
        }
        ui.add_space(30.0);
    });
}
