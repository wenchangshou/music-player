use eframe::egui::{self, Align, Color32, CursorIcon, Layout, RichText, Sense, UiBuilder, Vec2};

use crate::app::{Action, App, UiState};
use crate::theme::{self, ACCENT, TEXT_WEAK};
use crate::remote::{Remote, RemoteTrack};
use crate::types::{fmt_duration, TrackItem};
use crate::ui::widgets::{artwork, empty_state};

/// What a queue row shows; the same for local tracks and a remote device's queue.
struct Entry<'a> {
    title: String,
    artist: String,
    cover: Option<&'a str>,
    duration: Option<u32>,
}

impl<'a> From<&'a TrackItem> for Entry<'a> {
    fn from(t: &'a TrackItem) -> Self {
        Self { title: t.full_title(), artist: t.artist_names(), cover: t.cover(), duration: t.duration }
    }
}

impl<'a> From<&'a RemoteTrack> for Entry<'a> {
    fn from(t: &'a RemoteTrack) -> Self {
        Self { title: t.title.clone(), artist: t.artist.clone(), cover: t.cover.as_deref(), duration: None }
    }
}

/// One queue entry. Returns (row clicked, remove clicked).
fn row<'a>(ui: &mut egui::Ui, t: impl Into<Entry<'a>>, current: bool, removable: bool) -> (bool, bool) {
    let t = t.into();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 52.0), Sense::click());
    let hovered = resp.hovered();
    if current {
        ui.painter().rect_filled(rect, 6, ACCENT.gamma_multiply(0.15));
    } else if hovered {
        ui.painter().rect_filled(rect, 6, Color32::from_rgb(30, 30, 36));
    }
    let mut removed = false;
    let mut child = ui.new_child(UiBuilder::new().max_rect(rect.shrink2(Vec2::new(6.0, 4.0))).layout(Layout::left_to_right(Align::Center)));
    artwork(&mut child, t.cover, 42.0, 80, false, "🎵");
    child.add_space(4.0);
    child.allocate_ui_with_layout(Vec2::new(child.available_width() - 56.0, 44.0), Layout::top_down(Align::Min), |ui| {
        ui.spacing_mut().item_spacing.y = 1.0;
        ui.add_space(3.0);
        ui.add(egui::Label::new(RichText::new(t.title).color(if current { ACCENT } else { theme::TEXT })).truncate().selectable(false));
        ui.add(egui::Label::new(RichText::new(t.artist).size(12.0).color(TEXT_WEAK)).truncate().selectable(false));
    });
    child.with_layout(Layout::right_to_left(Align::Center), |ui| {
        if removable && hovered {
            if ui.add(egui::Button::new("✖").frame(false)).on_hover_text("移出队列").clicked() {
                removed = true;
            }
        } else if let Some(duration) = t.duration {
            ui.label(RichText::new(fmt_duration(duration)).size(12.0).color(TEXT_WEAK));
        }
    });
    (resp.on_hover_cursor(CursorIcon::PointingHand).clicked() && !removed, removed)
}

pub fn show(ctx: &egui::Context, app: &App, st: &mut UiState, acts: &mut Vec<Action>) {
    egui::SidePanel::right("queue")
        .exact_width(330.0)
        .resizable(false)
        .frame(egui::Frame::new().fill(theme::SIDEBAR).inner_margin(egui::Margin::symmetric(12, 14)))
        .show(ctx, |ui| {
            if let Some(remote) = app.remote_target() {
                show_remote(ui, remote, st, acts);
                return;
            }
            ui.horizontal(|ui| {
                ui.label(RichText::new("播放队列").size(18.0).strong());
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if ui.add(egui::Button::new("✖").frame(false)).on_hover_text("关闭").clicked() {
                        st.show_queue = false;
                    }
                    if app.pb.current().is_some() && ui.button("⏹ 停止").on_hover_text("停止播放并清空队列").clicked() {
                        acts.push(Action::StopPlayback);
                    }
                    let upcoming = app.pb.queue.len().saturating_sub(app.pb.index + 1);
                    if ui.add_enabled(upcoming > 0, egui::Button::new("清空")).on_hover_text("清空“接下来”").clicked() {
                        acts.push(Action::ClearQueue);
                    }
                });
            });
            if let Some(c) = &app.pb.context {
                if ui.link(RichText::new(format!("来自：{}", c.label)).size(12.5)).clicked() {
                    acts.push(Action::Go(c.route.clone()));
                }
            }
            ui.add_space(6.0);

            let Some(cur) = app.pb.current() else {
                empty_state(ui, "☰", "队列为空");
                return;
            };
            ui.label(RichText::new("正在播放").size(12.0).color(TEXT_WEAK).strong());
            row(ui, cur, true, false);

            ui.add_space(10.0);
            let total = app.pb.queue.len();
            ui.label(RichText::new(format!("接下来 · {} 首", total - app.pb.index - 1)).size(12.0).color(TEXT_WEAK).strong());
            egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
                for i in app.pb.index + 1..total {
                    let (clicked, removed) = row(ui, &app.pb.queue[i], false, true);
                    if removed {
                        acts.push(Action::RemoveQueue(i));
                    } else if clicked {
                        acts.push(Action::JumpQueue(i));
                    }
                }
                if app.pb.index > 0 {
                    ui.add_space(10.0);
                    ui.label(RichText::new("已播放").size(12.0).color(TEXT_WEAK).strong());
                    for i in 0..app.pb.index {
                        let (clicked, _) = row(ui, &app.pb.queue[i], false, false);
                        if clicked {
                            acts.push(Action::JumpQueue(i));
                        }
                    }
                }
            });
        });
}

/// The selected remote device's queue, laid out like this computer's.
fn show_remote(ui: &mut egui::Ui, remote: &Remote, st: &mut UiState, acts: &mut Vec<Action>) {
    let upcoming = remote.state.as_ref()
        .and_then(|state| state.index.map(|index| state.queue.len().saturating_sub(index + 1)))
        .unwrap_or(0);
    ui.horizontal(|ui| {
        ui.label(RichText::new("播放队列").size(18.0).strong());
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if ui.add(egui::Button::new("✖").frame(false)).on_hover_text("关闭").clicked() {
                st.show_queue = false;
            }
            if ui.add_enabled(upcoming > 0, egui::Button::new("清空")).on_hover_text("清空该设备的“接下来”").clicked() {
                acts.push(Action::ClearQueue);
            }
        });
    });
    let icon = remote.target_device().map_or("📡", crate::remote::DeviceInfo::icon);
    ui.label(RichText::new(format!("{icon} 正在遥控：{}", remote.target_name())).size(12.5).color(ACCENT));
    ui.add_space(6.0);

    let Some(state) = &remote.state else {
        match &remote.error {
            Some(error) => empty_state(ui, "⚠", error),
            None => crate::ui::widgets::loading(ui, "正在读取设备队列…"),
        }
        return;
    };
    let Some(index) = state.index.filter(|index| *index < state.queue.len()) else {
        empty_state(ui, "☰", "队列为空");
        return;
    };
    ui.label(RichText::new("正在播放").size(12.0).color(TEXT_WEAK).strong());
    row(ui, &state.queue[index], true, false);

    ui.add_space(10.0);
    let total = state.queue.len();
    ui.label(RichText::new(format!("接下来 · {} 首", total - index - 1)).size(12.0).color(TEXT_WEAK).strong());
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        for i in index + 1..total {
            let (clicked, removed) = row(ui, &state.queue[i], false, true);
            if removed {
                acts.push(Action::RemoveQueue(i));
            } else if clicked {
                acts.push(Action::JumpQueue(i));
            }
        }
        if index > 0 {
            ui.add_space(10.0);
            ui.label(RichText::new("已播放").size(12.0).color(TEXT_WEAK).strong());
            for i in 0..index {
                if row(ui, &state.queue[i], false, false).0 {
                    acts.push(Action::JumpQueue(i));
                }
            }
        }
    });
}
