//! Window layout: sidebar | (top bar / page) | optional queue, with the player bar along the bottom.

pub mod pages;
pub mod player_bar;
pub mod queue;
pub mod sidebar;
pub mod topbar;
pub mod widgets;

use eframe::egui::{self, Align2, Color32, RichText};

use crate::app::{Action, App, UiState};
use crate::route::Route;
use crate::theme;

pub fn render(ctx: &egui::Context, app: &App, st: &mut UiState, acts: &mut Vec<Action>) {
    // Order matters: earlier panels take the outer edges.
    player_bar::show(ctx, app, st, acts);
    sidebar::show(ctx, app, st, acts);
    if st.show_queue {
        queue::show(ctx, app, st, acts);
    }
    topbar::show(ctx, app, st, acts);

    egui::CentralPanel::default()
        .frame(egui::Frame::new().fill(theme::BG).inner_margin(egui::Margin { left: 24, right: 16, top: 4, bottom: 0 }))
        .show(ctx, |ui| match app.route.clone() {
            Route::Home => pages::home::show(app, st, ui, acts),
            Route::Search(source, q) => pages::search::show(app, st, ui, acts, source, &q),
            Route::Album(id) => pages::album::show(app, ui, acts, id),
            Route::Artist(id) => pages::artist::show(app, ui, acts, id),
            Route::Playlist(u) => pages::playlist::show(app, ui, acts, &u),
            Route::Library(tab) => pages::library::show(app, st, ui, acts, tab),
            Route::Downloads => pages::downloads::show(app, st, ui, acts),
            Route::Qa => pages::qa::show(app, st, ui, acts),
            Route::Settings => pages::settings::show(app, st, ui, acts),
        });

    toast(ctx, app);
}

fn toast(ctx: &egui::Context, app: &App) {
    let Some((msg, at)) = &app.toast else { return };
    let age = at.elapsed().as_secs_f32();
    if age > 3.5 {
        return;
    }
    let alpha = if age > 3.0 { (3.5 - age) / 0.5 } else { 1.0 };
    egui::Area::new(egui::Id::new("toast"))
        .anchor(Align2::CENTER_BOTTOM, egui::vec2(0.0, -104.0))
        .order(egui::Order::Foreground)
        .interactable(false)
        .show(ctx, |ui| {
            egui::Frame::new()
                .fill(Color32::from_rgb(40, 40, 48).gamma_multiply(alpha))
                .stroke(egui::Stroke::new(1.0, theme::ACCENT.gamma_multiply(0.5 * alpha)))
                .corner_radius(20)
                .inner_margin(egui::Margin::symmetric(18, 9))
                .show(ui, |ui| {
                    ui.label(RichText::new(msg).color(theme::TEXT.gamma_multiply(alpha)));
                });
        });
    if age > 3.0 {
        ctx.request_repaint();
    }
}
