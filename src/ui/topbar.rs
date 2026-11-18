use eframe::egui::{self, Align, Color32, Layout, RichText, Vec2};

use crate::app::{Action, App, UiState};
use crate::netease::SearchSource;
use crate::route::Route;
use crate::theme::{self, ACCENT};

pub fn show(ctx: &egui::Context, app: &App, st: &mut UiState, acts: &mut Vec<Action>) {
    egui::TopBottomPanel::top("topbar")
        .exact_height(56.0)
        .show_separator_line(false)
        .frame(egui::Frame::new().fill(theme::BG).inner_margin(egui::Margin { left: 20, right: 16, top: 10, bottom: 6 }))
        .show(ctx, |ui| {
            ui.horizontal_centered(|ui| {
                let nav = |t: &str| egui::Button::new(RichText::new(t).size(16.0)).corner_radius(16).min_size(Vec2::splat(32.0));
                if ui.add_enabled(!app.history.back.is_empty(), nav("‹")).on_hover_text("后退 (Alt+←)").clicked() {
                    acts.push(Action::Back);
                }
                if ui.add_enabled(!app.history.fwd.is_empty(), nav("›")).on_hover_text("前进 (Alt+→)").clicked() {
                    acts.push(Action::Forward);
                }
                ui.add_space(10.0);

                let previous_source = st.search_source;
                egui::ComboBox::from_id_salt("search_source")
                    .selected_text(st.search_source.label())
                    .width(72.0)
                    .show_ui(ui, |ui| {
                        for source in [SearchSource::Tidal, SearchSource::Netease] {
                            ui.selectable_value(&mut st.search_source, source, source.label());
                        }
                    });
                if st.search_source != previous_source && matches!(app.route, Route::Search(..)) {
                    acts.push(Action::Search(st.search_text.clone()));
                }

                let resp = ui.add(
                    egui::TextEdit::singleline(&mut st.search_text)
                        .hint_text("🔍  搜索歌手、专辑、歌曲、歌单   (Ctrl+F)")
                        .desired_width(420.0)
                        .font(egui::FontId::proportional(14.5))
                        .margin(egui::Margin::symmetric(14, 8))
                        .background_color(Color32::from_rgb(34, 34, 40)),
                );
                if st.focus_search {
                    resp.request_focus();
                    st.focus_search = false;
                }
                if resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                    acts.push(Action::Search(st.search_text.clone()));
                }
                if !st.search_text.is_empty() && ui.add(egui::Button::new("✖").frame(false)).on_hover_text("清空").clicked() {
                    st.search_text.clear();
                }

                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if ui.selectable_label(st.show_queue, RichText::new("☰ 队列").size(14.0)).on_hover_text("显示/隐藏播放队列").clicked() {
                        st.show_queue = !st.show_queue;
                    }
                    let active = app.active_downloads();
                    let running = app.jobs.iter().any(|j| j.status.is_active());
                    let label = if active > 0 { format!("⬇ {}", active) } else { "⬇".to_string() };
                    let b = ui.selectable_label(app.route == Route::Downloads, RichText::new(label).size(14.0).color(if running { ACCENT } else { theme::TEXT }));
                    if b.on_hover_text("下载队列").clicked() {
                        acts.push(Action::Go(Route::Downloads));
                    }
                    if running {
                        ui.spinner();
                    }
                    if ui.add(egui::Button::new(RichText::new("↻").size(15.0)).frame(false)).on_hover_text("刷新当前页面").clicked() {
                        acts.push(Action::Refresh);
                    }
                });
            });
        });
}
