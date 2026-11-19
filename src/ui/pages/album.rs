use eframe::egui::{self, RichText};

use crate::app::{Action, App, Load};
use crate::library::FavTarget;
use crate::playback::PlayContext;
use crate::route::Route;
use crate::theme::{self, parse_hex, TEXT_WEAK};
use crate::types::{fmt_total, image_url, ArtistItem};
use crate::ui::widgets::*;

pub fn show(app: &App, ui: &mut egui::Ui, acts: &mut Vec<Action>, id: u64) {
    let page = match app.cache.albums.get(&id) {
        None | Some(Load::Loading) => return loading(ui, "正在加载专辑…"),
        Some(Load::Failed(e)) => {
            if error_box(ui, e) {
                acts.push(Action::Refresh);
            }
            return;
        }
        Some(Load::Ready(p)) => p,
    };
    let a = &page.album;
    let color = a.vibrant_color.as_deref().and_then(parse_hex).unwrap_or(theme::CARD);

    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        hero_gradient(ui, color, 380.0);
        ui.add_space(16.0);
        ui.horizontal(|ui| {
            let cover = artwork(ui, a.cover.as_deref(), 232.0, 640, false, "💿");
            if let Some(c) = &a.cover {
                if cover.on_hover_text("查看大图").clicked() {
                    acts.push(Action::OpenUrl(image_url(c, 1280)));
                }
            }
            ui.add_space(22.0);
            ui.vertical(|ui| {
                ui.add_space(30.0);
                let kind = match a.album_type.as_deref() {
                    Some("EP") => "EP",
                    Some("SINGLE") => "单曲",
                    _ => "专辑",
                };
                ui.label(RichText::new(kind).strong().color(TEXT_WEAK));
                ui.add(egui::Label::new(RichText::new(a.full_title()).size(34.0).strong()).wrap());
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    let list: Vec<&ArtistItem> = match &a.artists {
                        Some(v) if !v.is_empty() => v.iter().collect(),
                        _ => a.artist.iter().collect(),
                    };
                    for (i, ar) in list.iter().enumerate() {
                        if i > 0 {
                            ui.label(RichText::new("·").color(TEXT_WEAK));
                        }
                        if link_label(ui, RichText::new(&ar.name).size(16.0).strong()).clicked() {
                            acts.push(Action::Go(Route::Artist(ar.id)));
                        }
                    }
                });
                ui.add_space(2.0);
                let total = a.duration.unwrap_or_else(|| page.tracks.iter().filter_map(|t| t.duration).sum());
                let mut meta = vec![];
                if let Some(d) = &a.release_date {
                    meta.push(d.clone());
                }
                meta.push(format!("{} 首歌曲", a.number_of_tracks.unwrap_or(page.tracks.len() as u32)));
                meta.push(fmt_total(total));
                if a.number_of_volumes.unwrap_or(1) > 1 {
                    meta.push(format!("{} 张碟", a.number_of_volumes.unwrap_or(1)));
                }
                ui.horizontal(|ui| {
                    ui.label(RichText::new(meta.join(" · ")).color(TEXT_WEAK));
                    quality_badges(ui, &a.tags());
                    if a.explicit == Some(true) {
                        badge(ui, "Explicit", egui::Color32::GRAY);
                    }
                });
            });
        });

        // Action row
        ui.add_space(18.0);
        let ctx = Some(PlayContext { label: format!("专辑 · {}", a.title), route: Route::Album(a.id) });
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 12.0;
            if play_button(ui, 52.0).on_hover_text("播放").clicked() {
                acts.push(Action::PlayTracks { tracks: page.tracks.clone(), index: 0, shuffle: false, context: ctx.clone() });
            }
            if pill(ui, "🔀 随机播放").clicked() {
                acts.push(Action::PlayTracks { tracks: page.tracks.clone(), index: 0, shuffle: true, context: ctx.clone() });
            }
            if crate::netease::native_id(a.id).is_none() {
                let fav = app.lib.has_album(a.id);
                let ft = if fav { RichText::new("♥ 已收藏").color(theme::HEART) } else { RichText::new("♡ 收藏") };
                if ui.add(egui::Button::new(ft.size(14.0)).corner_radius(18).min_size(egui::vec2(0.0, 36.0))).clicked() {
                    acts.push(Action::ToggleFav(FavTarget::Album(a.clone())));
                }
                let saved = app.marks().album(a);
                let label = if saved > 0 { format!("⬇ 已下载 {saved} 首 ▼") } else { "⬇ 下载 ▼".to_owned() };
                ui.menu_button(RichText::new(label).size(14.0), |ui| download_items(ui, &a.url(), &a.title, acts));
            }
            ui.menu_button(RichText::new("…").size(18.0), |ui| album_menu(ui, app, a, acts));
        });

        ui.add_space(14.0);
        let multi_disc = a.number_of_volumes.unwrap_or(1) > 1;
        track_list(ui, app, "album_tracks", &page.tracks, TrackOpts { multi_disc, ..Default::default() }, ctx, acts);

        if let Some(c) = &a.copyright {
            ui.add_space(14.0);
            ui.label(RichText::new(c).size(12.0).color(TEXT_WEAK));
        }

        // More by the same artist, if that artist page is already cached.
        if let Some(ar) = a.main_artist() {
            if let Some(Load::Ready(ap)) = app.cache.artists.get(&ar.id) {
                let more: Vec<_> = ap.albums.iter().chain(ap.singles.iter()).filter(|x| x.id != a.id).take(12).collect();
                if !more.is_empty() {
                    if section_header(ui, &format!("更多来自 {}", ar.name), None, true) {
                        acts.push(Action::Go(Route::Artist(ar.id)));
                    }
                    shelf(ui, "more_by", |ui| {
                        for x in more {
                            album_card(ui, app, x, acts);
                        }
                    });
                }
            }
        }
        ui.add_space(30.0);
    });
}
