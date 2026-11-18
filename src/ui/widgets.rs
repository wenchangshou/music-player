//! Reusable building blocks: artwork, cards, shelves, track lists, context menus.

use eframe::egui::{self, Align, Align2, Color32, CursorIcon, FontId, Layout, Rect, RichText, Sense, Stroke, Vec2};
use egui_extras::{Column, TableBuilder};

use crate::app::{Action, App};
use crate::library::FavTarget;
use crate::playback::PlayContext;
use crate::route::Route;
use crate::theme::{self, ACCENT, HEART, TEXT_WEAK};
use crate::types::{fmt_duration, image_url, AlbumItem, ArtistItem, PlaylistItem, TrackItem};

pub const CARD_W: f32 = 170.0;

/// tiddl `-q` values, best first. MAX is the default everywhere.
pub const QUALITIES: [(&str, &str, &str); 4] = [
    // tiddl's quality flags, with TIDAL's names for them (see `types::tidal_quality_name`).
    ("max", "Max", "HiRes FLAC 最高 24bit/192kHz"),
    ("high", "High", "FLAC 16bit/44.1kHz"),
    ("normal", "Low", "AAC 最高 320kbps"),
    ("low", "Low 96k", "AAC 96kbps"),
];

pub fn quality_label(q: &str) -> &'static str {
    QUALITIES.iter().find(|(k, _, _)| *k == q).map(|(_, n, _)| *n).unwrap_or("Max")
}

// ───────────────────────────── primitives ─────────────────────────────

/// Square (or round) artwork with a placeholder while the image streams in.
pub fn artwork(ui: &mut egui::Ui, image_id: Option<&str>, size: f32, px: u32, round: bool, placeholder: &str) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    let radius: u8 = if round { (size / 2.0).min(255.0) as u8 } else { (size * 0.04).clamp(3.0, 8.0) as u8 };
    if ui.is_rect_visible(rect) {
        let painter = ui.painter_at(rect.expand(2.0));
        painter.rect_filled(rect, radius, Color32::from_rgb(38, 38, 44));
        match image_id {
            Some(id) => {
                egui::Image::new(image_url(id, px))
                    .corner_radius(radius)
                    .show_loading_spinner(size >= 64.0)
                    .paint_at(ui, rect);
            }
            None => {
                painter.text(rect.center(), Align2::CENTER_CENTER, placeholder, FontId::proportional(size * 0.38), TEXT_WEAK);
            }
        }
    }
    resp.on_hover_cursor(CursorIcon::PointingHand)
}

pub fn badge(ui: &mut egui::Ui, text: &str, color: Color32) {
    egui::Frame::new()
        .fill(color.gamma_multiply(0.16))
        .stroke(Stroke::new(1.0, color.gamma_multiply(0.55)))
        .corner_radius(4)
        .inner_margin(egui::Margin::symmetric(5, 1))
        .show(ui, |ui| {
            ui.label(RichText::new(text).size(10.5).strong().color(color));
        });
}

pub fn quality_badges(ui: &mut egui::Ui, tags: &[String]) {
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 4.0;
        for t in tags {
            let (label, color) = match t.as_str() {
                "HIRES_LOSSLESS" => ("Max", theme::GOLD),
                "LOSSLESS" => ("High", Color32::from_rgb(0, 185, 175)),
                "DOLBY_ATMOS" => ("Atmos", Color32::from_rgb(90, 140, 255)),
                "SONY_360RA" => ("360RA", Color32::from_rgb(170, 110, 240)),
                "MQA" | "HI_RES" => ("MQA", Color32::from_rgb(200, 120, 60)),
                "HIGH" => ("Low", Color32::GRAY),
                "LOW" => ("Low 96k", Color32::GRAY),
                other => (other, Color32::GRAY),
            };
            badge(ui, label, color);
        }
    });
}

pub fn link_label(ui: &mut egui::Ui, text: RichText) -> egui::Response {
    let r = ui.add(egui::Label::new(text).truncate().sense(Sense::click()));
    if r.hovered() {
        ui.ctx().set_cursor_icon(CursorIcon::PointingHand);
    }
    r
}

pub fn heart(ui: &mut egui::Ui, on: bool, size: f32) -> egui::Response {
    let (txt, color) = if on { ("♥", HEART) } else { ("♡", TEXT_WEAK) };
    ui.add(egui::Button::new(RichText::new(txt).size(size).color(color)).frame(false))
        .on_hover_text(if on { "取消收藏" } else { "收藏" })
        .on_hover_cursor(CursorIcon::PointingHand)
}

/// Big round accent play button.
pub fn play_button(ui: &mut egui::Ui, size: f32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    let c = if resp.hovered() { theme::ACCENT_HOVER } else { ACCENT };
    ui.painter().circle_filled(rect.center(), size / 2.0, c);
    ui.painter().text(rect.center() + egui::vec2(size * 0.04, 0.0), Align2::CENTER_CENTER, "▶", FontId::proportional(size * 0.42), Color32::BLACK);
    resp.on_hover_cursor(CursorIcon::PointingHand)
}

/// Translucent pill button used in page headers.
pub fn pill(ui: &mut egui::Ui, text: &str) -> egui::Response {
    ui.add(
        egui::Button::new(RichText::new(text).size(14.0))
            .corner_radius(18)
            .min_size(Vec2::new(0.0, 36.0)),
    )
}

/// Vertical gradient behind a page header (album "vibrant" colour → background).
pub fn hero_gradient(ui: &egui::Ui, color: Color32, height: f32) {
    let top = ui.max_rect().min - egui::vec2(24.0, 4.0);
    let rect = Rect::from_min_size(top, egui::vec2(ui.max_rect().width() + 48.0, height));
    let c0 = Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 120);
    let c1 = Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 0);
    let mut mesh = egui::Mesh::default();
    mesh.colored_vertex(rect.left_top(), c0);
    mesh.colored_vertex(rect.right_top(), c0);
    mesh.colored_vertex(rect.left_bottom(), c1);
    mesh.colored_vertex(rect.right_bottom(), c1);
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(1, 2, 3);
    ui.painter().add(egui::Shape::mesh(mesh));
}

pub fn section_header(ui: &mut egui::Ui, title: &str, count: Option<usize>, more: bool) -> bool {
    let mut clicked = false;
    ui.add_space(18.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(title).size(20.0).strong());
        if let Some(n) = count {
            ui.label(RichText::new(n.to_string()).color(TEXT_WEAK));
        }
        if more {
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if ui.add(egui::Label::new(RichText::new("查看全部").color(TEXT_WEAK)).sense(Sense::click())).on_hover_cursor(CursorIcon::PointingHand).clicked() {
                    clicked = true;
                }
            });
        }
    });
    ui.add_space(8.0);
    clicked
}

pub fn loading(ui: &mut egui::Ui, text: &str) {
    ui.add_space(60.0);
    ui.vertical_centered(|ui| {
        ui.spinner();
        ui.label(RichText::new(text).color(TEXT_WEAK));
    });
}

pub fn empty_state(ui: &mut egui::Ui, icon: &str, text: &str) {
    ui.add_space(70.0);
    ui.vertical_centered(|ui| {
        ui.label(RichText::new(icon).size(46.0).color(TEXT_WEAK));
        ui.add_space(8.0);
        ui.label(RichText::new(text).size(15.0).color(TEXT_WEAK));
    });
}

/// Error banner with a retry button; returns true when retry is clicked.
pub fn error_box(ui: &mut egui::Ui, msg: &str) -> bool {
    let mut retry = false;
    ui.add_space(20.0);
    egui::Frame::new()
        .fill(theme::DANGER.gamma_multiply(0.12))
        .stroke(Stroke::new(1.0, theme::DANGER.gamma_multiply(0.5)))
        .corner_radius(8)
        .inner_margin(12)
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new(format!("⚠ {}", msg)).color(theme::DANGER));
                retry = ui.button("↻ 重试").clicked();
            });
        });
    retry
}

/// Horizontally scrolling row of cards.
pub fn shelf(ui: &mut egui::Ui, id: impl std::hash::Hash, add: impl FnOnce(&mut egui::Ui)) {
    egui::ScrollArea::horizontal().id_salt(id).show(ui, |ui| {
        ui.horizontal_top(|ui| {
            ui.spacing_mut().item_spacing.x = 18.0;
            add(ui);
        });
    });
}

/// Wrapping grid of cards.
pub fn grid(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    ui.horizontal_wrapped(|ui| {
        ui.spacing_mut().item_spacing = Vec2::new(18.0, 22.0);
        add(ui);
    });
}

// ───────────────────────────── menus ─────────────────────────────

fn item(ui: &mut egui::Ui, text: &str) -> bool {
    let c = ui.button(text).clicked();
    if c {
        ui.close_menu();
    }
    c
}

/// Quality list for a download submenu/popup.
pub fn download_items(ui: &mut egui::Ui, url: &str, title: &str, acts: &mut Vec<Action>) {
    ui.set_min_width(260.0);
    ui.label(RichText::new("选择下载音质").color(TEXT_WEAK).small());
    let qualities = if crate::netease::song_link_id(url).is_some() {
        [("max", "Hi-Res", "最高 24bit/192kHz"), ("high", "无损", "FLAC"), ("normal", "极高", "最高 320kbps"), ("low", "标准", "128kbps")]
    } else {
        QUALITIES
    };
    for (i, (q, name, desc)) in qualities.iter().enumerate() {
        let text = if i == 0 {
            RichText::new(format!("★ {} · {}（默认）", name, desc)).strong().color(ACCENT)
        } else {
            RichText::new(format!("   {} · {}", name, desc))
        };
        if ui.button(text).clicked() {
            acts.push(Action::Download { url: url.to_string(), quality: q, title: Some(title.to_string()) });
            ui.close_menu();
        }
    }
}

fn fav_item(ui: &mut egui::Ui, app: &App, t: FavTarget, acts: &mut Vec<Action>) {
    if t.is_netease() {
        return;
    }
    let on = app.lib.is_fav(&t);
    if item(ui, if on { "♥ 取消收藏" } else { "♡ 收藏" }) {
        acts.push(Action::ToggleFav(t));
    }
}

pub fn album_menu(ui: &mut egui::Ui, app: &App, a: &AlbumItem, acts: &mut Vec<Action>) {
    if item(ui, "▶ 播放") {
        acts.push(Action::PlayAlbum { id: a.id, shuffle: false });
    }
    if item(ui, "🔀 随机播放") {
        acts.push(Action::PlayAlbum { id: a.id, shuffle: true });
    }
    ui.separator();
    fav_item(ui, app, FavTarget::Album(a.clone()), acts);
    if crate::netease::native_id(a.id).is_none() {
        ui.menu_button("⬇ 下载", |ui| download_items(ui, &a.url(), &a.title, acts));
    }
    ui.separator();
    if item(ui, "💿 打开专辑") {
        acts.push(Action::Go(Route::Album(a.id)));
    }
    if let Some(ar) = a.main_artist() {
        if item(ui, &format!("👤 {}", ar.name)) {
            acts.push(Action::Go(Route::Artist(ar.id)));
        }
    }
    if item(ui, "📋 复制链接") {
        acts.push(Action::Copy(a.url()));
    }
    if item(ui, "🌐 在浏览器打开") {
        let url = if crate::netease::native_id(a.id).is_some() {
            a.url()
        } else {
            format!("https://tidal.com/browse/album/{}", a.id)
        };
        acts.push(Action::OpenUrl(url));
    }
}

pub fn artist_menu(ui: &mut egui::Ui, app: &App, a: &ArtistItem, acts: &mut Vec<Action>) {
    if item(ui, "👤 打开歌手") {
        acts.push(Action::Go(Route::Artist(a.id)));
    }
    fav_item(ui, app, FavTarget::Artist(a.clone()), acts);
    let url = match crate::netease::native_id(a.id) {
        Some(id) => crate::netease::link("artist", id),
        None => format!("https://tidal.com/artist/{}", a.id),
    };
    if item(ui, "📋 复制链接") {
        acts.push(Action::Copy(url.clone()));
    }
    if item(ui, "🌐 在浏览器打开") {
        let url = if crate::netease::native_id(a.id).is_some() {
            url
        } else {
            format!("https://tidal.com/browse/artist/{}", a.id)
        };
        acts.push(Action::OpenUrl(url));
    }
}

pub fn playlist_menu(ui: &mut egui::Ui, app: &App, p: &PlaylistItem, acts: &mut Vec<Action>) {
    if item(ui, "▶ 播放") {
        acts.push(Action::PlayPlaylist { uuid: p.uuid.clone(), shuffle: false });
    }
    if item(ui, "🔀 随机播放") {
        acts.push(Action::PlayPlaylist { uuid: p.uuid.clone(), shuffle: true });
    }
    ui.separator();
    if !app.lib.is_mine(&p.uuid) {
        fav_item(ui, app, FavTarget::Playlist(p.clone()), acts);
    }
    if crate::netease::playlist_id(&p.uuid).is_none() {
        ui.menu_button("⬇ 下载", |ui| download_items(ui, &p.url(), &p.title, acts));
    }
    if item(ui, "📋 复制链接") {
        acts.push(Action::Copy(p.url()));
    }
}

pub fn track_menu(ui: &mut egui::Ui, app: &App, tracks: &[TrackItem], i: usize, ctx: &Option<PlayContext>, acts: &mut Vec<Action>) {
    let t = &tracks[i];
    // While another device is selected, these entries send the track there.
    let device = app.remote_target().map(|remote| format!(" · {}", remote.target_name())).unwrap_or_default();
    if item(ui, &format!("▶ 播放{device}")) {
        acts.push(Action::PlayTracks { tracks: tracks.to_vec(), index: i, shuffle: false, context: ctx.clone() });
    }
    if item(ui, &format!("⤵ 下一首播放{device}")) {
        acts.push(Action::PlayNext(vec![t.clone()]));
    }
    if item(ui, &format!("☰ 添加到播放队列{device}")) {
        acts.push(Action::Enqueue(vec![t.clone()]));
    }
    ui.separator();
    fav_item(ui, app, FavTarget::Track(t.clone()), acts);
    ui.menu_button("⬇ 下载", |ui| download_items(ui, &t.url(), &t.title, acts));
    ui.separator();
    if let Some(a) = &t.album {
        if item(ui, &format!("💿 {}", a.title)) {
            acts.push(Action::Go(Route::Album(a.id)));
        }
    }
    if let Some(ar) = t.main_artist() {
        if item(ui, &format!("👤 {}", ar.name)) {
            acts.push(Action::Go(Route::Artist(ar.id)));
        }
    }
    if item(ui, "📋 复制链接") {
        acts.push(Action::Copy(t.url()));
    }
}

// ───────────────────────────── cards ─────────────────────────────

/// Paints a round play button over the bottom-right of `rect` while hovered.
fn hover_play(ui: &mut egui::Ui, rect: Rect, id: egui::Id) -> bool {
    if !ui.rect_contains_pointer(rect) {
        return false;
    }
    let c = rect.right_bottom() - egui::vec2(28.0, 28.0);
    let r = 21.0;
    let resp = ui.interact(Rect::from_center_size(c, Vec2::splat(2.0 * r)), id, Sense::click());
    let p = ui.painter();
    p.circle_filled(c + egui::vec2(0.0, 2.0), r, Color32::from_black_alpha(90));
    p.circle_filled(c, r, if resp.hovered() { theme::ACCENT_HOVER } else { ACCENT });
    p.text(c + egui::vec2(1.5, 0.0), Align2::CENTER_CENTER, "▶", FontId::proportional(17.0), Color32::BLACK);
    resp.on_hover_cursor(CursorIcon::PointingHand).on_hover_text("播放").clicked()
}

pub fn album_card(ui: &mut egui::Ui, app: &App, a: &AlbumItem, acts: &mut Vec<Action>) {
    ui.allocate_ui_with_layout(Vec2::new(CARD_W, CARD_W + 58.0), Layout::top_down(Align::Min), |ui| {
        ui.set_width(CARD_W);
        ui.spacing_mut().item_spacing.y = 2.0;
        let resp = artwork(ui, a.cover.as_deref(), CARD_W, 320, false, "💿");
        if hover_play(ui, resp.rect, ui.id().with(("aplay", a.id))) {
            acts.push(Action::PlayAlbum { id: a.id, shuffle: false });
        } else if resp.clicked() {
            acts.push(Action::Go(Route::Album(a.id)));
        }
        resp.context_menu(|ui| album_menu(ui, app, a, acts));
        ui.add_space(6.0);
        if link_label(ui, RichText::new(a.full_title()).strong()).clicked() {
            acts.push(Action::Go(Route::Album(a.id)));
        }
        let mut sub = a.artist_names();
        if !a.year().is_empty() {
            sub = format!("{} · {}", a.year(), sub);
        }
        if link_label(ui, RichText::new(sub).color(TEXT_WEAK).size(12.5)).clicked() {
            if let Some(ar) = a.main_artist() {
                acts.push(Action::Go(Route::Artist(ar.id)));
            }
        }
    });
}

pub fn artist_card(ui: &mut egui::Ui, app: &App, a: &ArtistItem, acts: &mut Vec<Action>) {
    const W: f32 = 150.0;
    ui.allocate_ui_with_layout(Vec2::new(W, W + 44.0), Layout::top_down(Align::Center), |ui| {
        ui.set_width(W);
        ui.spacing_mut().item_spacing.y = 2.0;
        let r = artwork(ui, a.picture.as_deref(), W, 320, true, "👤");
        r.context_menu(|ui| artist_menu(ui, app, a, acts));
        ui.add_space(6.0);
        let r2 = link_label(ui, RichText::new(&a.name).strong());
        ui.label(RichText::new("歌手").color(TEXT_WEAK).size(12.0));
        if r.clicked() || r2.clicked() {
            acts.push(Action::Go(Route::Artist(a.id)));
        }
    });
}

pub fn playlist_card(ui: &mut egui::Ui, app: &App, p: &PlaylistItem, acts: &mut Vec<Action>) {
    ui.allocate_ui_with_layout(Vec2::new(CARD_W, CARD_W + 58.0), Layout::top_down(Align::Min), |ui| {
        ui.set_width(CARD_W);
        ui.spacing_mut().item_spacing.y = 2.0;
        let resp = artwork(ui, p.cover(), CARD_W, 320, false, "📃");
        if hover_play(ui, resp.rect, ui.id().with(("pplay", &p.uuid))) {
            acts.push(Action::PlayPlaylist { uuid: p.uuid.clone(), shuffle: false });
        } else if resp.clicked() {
            acts.push(Action::Go(Route::Playlist(p.uuid.clone())));
        }
        resp.context_menu(|ui| playlist_menu(ui, app, p, acts));
        ui.add_space(6.0);
        if link_label(ui, RichText::new(&p.title).strong()).clicked() {
            acts.push(Action::Go(Route::Playlist(p.uuid.clone())));
        }
        ui.label(
            RichText::new(format!("{} · {} 首", p.creator_name(), p.number_of_tracks.unwrap_or(0)))
                .color(TEXT_WEAK)
                .size(12.5),
        );
    });
}

// ───────────────────────────── track list ─────────────────────────────

#[derive(Clone, Copy, Default)]
pub struct TrackOpts {
    /// Show album artwork instead of the track number.
    pub cover: bool,
    pub album_col: bool,
    pub multi_disc: bool,
}

pub fn track_list(
    ui: &mut egui::Ui,
    app: &App,
    id: &str,
    tracks: &[TrackItem],
    opts: TrackOpts,
    ctx: Option<PlayContext>,
    acts: &mut Vec<Action>,
) {
    if tracks.is_empty() {
        return;
    }
    let playing_id = app.pb.current().map(|t| t.id);
    let paused = app.pb.player.snapshot().paused;
    let hover_key = egui::Id::new(("track_hover", id));
    let last_hover: Option<usize> = ui.data(|d| d.get_temp::<Option<usize>>(hover_key)).flatten();
    let mut now_hover = None;
    let row_h = if opts.cover { 54.0 } else { 46.0 };

    let mut tb = TableBuilder::new(ui)
        .id_salt(id)
        .striped(false)
        .vscroll(false)
        .sense(Sense::click())
        .cell_layout(Layout::left_to_right(Align::Center))
        .column(Column::exact(if opts.cover { 52.0 } else { 40.0 }))
        .column(Column::remainder().at_least(200.0).clip(true));
    if opts.album_col {
        tb = tb.column(Column::initial(240.0).at_least(100.0).clip(true).resizable(true));
    }
    tb = tb
        .column(Column::exact(34.0))
        .column(Column::exact(58.0))
        .column(Column::exact(186.0))
        .column(Column::exact(34.0));

    tb.header(26.0, |mut h| {
        let head = |ui: &mut egui::Ui, s: &str| {
            ui.label(RichText::new(s).color(TEXT_WEAK).size(12.5));
        };
        h.col(|ui| head(ui, "#"));
        h.col(|ui| head(ui, "标题"));
        if opts.album_col {
            h.col(|ui| head(ui, "专辑"));
        }
        h.col(|_| {});
        h.col(|ui| head(ui, "时长"));
        h.col(|ui| head(ui, "音质"));
        h.col(|_| {});
    })
    .body(|mut body| {
        for (i, t) in tracks.iter().enumerate() {
            let is_playing = playing_id == Some(t.id);
            let hovered = last_hover == Some(i);
            body.row(row_h, |mut row| {
                row.set_selected(is_playing);
                // Lead: number / cover, ▶ on hover, 🔊 when playing.
                row.col(|ui| {
                    if opts.cover {
                        let r = artwork(ui, t.cover(), 42.0, 80, false, "🎵");
                        if hovered || is_playing {
                            let icon = if is_playing && !paused { "🔊" } else { "▶" };
                            ui.painter().rect_filled(r.rect, 4, Color32::from_black_alpha(140));
                            ui.painter().text(r.rect.center(), Align2::CENTER_CENTER, icon, FontId::proportional(16.0), Color32::WHITE);
                        }
                        if r.clicked() {
                            acts.push(Action::PlayTracks { tracks: tracks.to_vec(), index: i, shuffle: false, context: ctx.clone() });
                        }
                    } else if hovered && !is_playing {
                        if ui.add(egui::Button::new("▶").frame(false)).clicked() {
                            acts.push(Action::PlayTracks { tracks: tracks.to_vec(), index: i, shuffle: false, context: ctx.clone() });
                        }
                    } else if is_playing {
                        ui.label(RichText::new(if paused { "⏸" } else { "🔊" }).color(ACCENT));
                    } else {
                        let n = t.track_number.unwrap_or(i as u32 + 1);
                        let s = if opts.multi_disc { format!("{}-{:02}", t.volume_number.unwrap_or(1), n) } else { format!("{}", n) };
                        ui.label(RichText::new(s).color(TEXT_WEAK));
                    }
                });
                // Title + artists
                row.col(|ui| {
                    ui.vertical(|ui| {
                        ui.spacing_mut().item_spacing.y = 1.0;
                        ui.horizontal(|ui| {
                            let c = if is_playing { ACCENT } else { theme::TEXT };
                            ui.add(egui::Label::new(RichText::new(t.full_title()).color(c).size(14.5)).truncate().selectable(false));
                            if t.explicit == Some(true) {
                                badge(ui, "E", Color32::GRAY);
                            }
                        });
                        if link_label(ui, RichText::new(t.artist_names()).color(TEXT_WEAK).size(12.5)).clicked() {
                            if let Some(a) = t.main_artist() {
                                acts.push(Action::Go(Route::Artist(a.id)));
                            }
                        }
                    });
                });
                if opts.album_col {
                    row.col(|ui| {
                        if let Some(a) = &t.album {
                            if link_label(ui, RichText::new(&a.title).color(TEXT_WEAK)).clicked() {
                                acts.push(Action::Go(Route::Album(a.id)));
                            }
                        }
                    });
                }
                row.col(|ui| {
                    let on = app.lib.has_track(t.id);
                    if crate::netease::native_id(t.id).is_none() && (on || hovered) && heart(ui, on, 15.0).clicked() {
                        acts.push(Action::ToggleFav(FavTarget::Track(t.clone())));
                    }
                });
                row.col(|ui| {
                    ui.label(RichText::new(fmt_duration(t.duration.unwrap_or(0))).color(TEXT_WEAK));
                });
                row.col(|ui| quality_badges(ui, &t.tags()));
                row.col(|ui| {
                    ui.menu_button(RichText::new("…").color(TEXT_WEAK), |ui| track_menu(ui, app, tracks, i, &ctx, acts));
                });

                let resp = row.response();
                if resp.hovered() {
                    now_hover = Some(i);
                }
                if resp.double_clicked() {
                    acts.push(Action::PlayTracks { tracks: tracks.to_vec(), index: i, shuffle: false, context: ctx.clone() });
                }
                resp.context_menu(|ui| track_menu(ui, app, tracks, i, &ctx, acts));
            });
        }
    });

    if now_hover != last_hover {
        ui.data_mut(|d| d.insert_temp(hover_key, now_hover));
        ui.ctx().request_repaint();
    }
}
