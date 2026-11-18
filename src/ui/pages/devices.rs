//! Every player signed in to this player account, with remote control.

use eframe::egui::{self, Align, Color32, Layout, RichText, Vec2};

use crate::app::{Action, App, UiState};
use crate::remote::DeviceInfo;
use crate::theme::{self, ACCENT, OK_GREEN, TEXT_WEAK};
use crate::ui::widgets::{artwork, badge, empty_state, error_box, loading};

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>) {
    ui.add_space(8.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new("设备").size(30.0).strong());
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if ui.button("↻ 刷新").clicked() {
                acts.push(Action::RefreshDevices);
            }
            if app.remote.as_ref().is_some_and(|remote| remote.devices.iter().any(|device| !device.is_self)) {
                if st.devices_logout_confirm {
                    if ui.button("取消").clicked() {
                        st.devices_logout_confirm = false;
                    }
                    let confirm = egui::Button::new(RichText::new("确认退出其他设备").color(Color32::WHITE)).fill(theme::DANGER);
                    if ui.add(confirm).clicked() {
                        st.devices_logout_confirm = false;
                        acts.push(Action::LogoutOtherDevices);
                    }
                } else if ui.add(egui::Button::new(RichText::new("退出其他所有设备").color(theme::DANGER)).frame(false))
                    .on_hover_text("其他电脑、手机和网页都需要重新登录播放器账号")
                    .clicked()
                {
                    st.devices_logout_confirm = true;
                }
            }
        });
    });
    let Some(remote) = &app.remote else {
        empty_state(ui, "📡", "在 设置 → 播放器账号 登录后，可查看并遥控同一账号的所有设备");
        return;
    };
    let username = app.player_account.as_ref().map(|account| account.username.as_str()).unwrap_or_default();
    ui.label(
        RichText::new(format!("登录播放器账号「{username}」的电脑和手机。选择一台设备后，底部播放条和播放队列会改为控制它。"))
            .color(TEXT_WEAK),
    );
    ui.add_space(8.0);

    if let Some(error) = &remote.devices_error {
        if error_box(ui, error) {
            acts.push(Action::RefreshDevices);
        }
    }
    if !remote.devices_loaded {
        loading(ui, "正在读取设备…");
        return;
    }

    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        // This computer first, then online devices, then offline ones.
        let mut devices: Vec<&DeviceInfo> = remote.devices.iter().collect();
        devices.sort_by_key(|device| (!device.is_self, !device.online));
        for device in devices {
            let selected = remote.target.as_deref() == Some(device.id.as_str());
            card(ui, app, st, device, selected, acts);
        }
        if remote.devices.iter().all(|device| device.is_self) {
            ui.add_space(12.0);
            ui.label(RichText::new("其他电脑或手机登录同一个播放器账号后会出现在这里。").color(TEXT_WEAK));
        }
    });
}

fn card(ui: &mut egui::Ui, app: &App, st: &mut UiState, device: &DeviceInfo, selected: bool, acts: &mut Vec<Action>) {
    ui.add_space(10.0);
    let stroke = if selected { egui::Stroke::new(1.5, ACCENT) } else { egui::Stroke::NONE };
    egui::Frame::new().fill(theme::CARD).stroke(stroke).corner_radius(10).inner_margin(16).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.horizontal(|ui| {
            ui.label(RichText::new(device.icon()).size(30.0));
            ui.add_space(6.0);
            ui.vertical(|ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new(&device.name).size(17.0).strong());
                    if device.is_self {
                        badge(ui, "本机", ACCENT);
                    }
                    if selected {
                        badge(ui, "正在遥控", ACCENT);
                    }
                    if device.online {
                        badge(ui, "在线", OK_GREEN);
                    } else {
                        badge(ui, "离线", TEXT_WEAK);
                    }
                });
                let mut detail = device.platform_label().to_string();
                if !device.version.is_empty() {
                    detail.push_str(&format!(" · 版本 {}", device.version));
                }
                if !device.online && !device.is_self {
                    detail.push_str(&format!(" · {}", device.last_seen_label()));
                }
                ui.label(RichText::new(detail).size(12.5).color(TEXT_WEAK));
            });
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                buttons(ui, app, st, device, selected, acts);
            });
        });

        // What the device is playing right now.
        let now = if device.is_self && app.remote_target().is_none() {
            app.pb.current().map(|track| (track.full_title(), track.artist_names(), track.cover().map(str::to_owned),
                app.pb.player.snapshot().active && !app.pb.player.snapshot().paused))
        } else {
            device.now.as_ref().map(|now| (now.title.clone(), now.artist.clone(), now.cover.clone(), now.playing))
        };
        if let Some((title, artist, cover, playing)) = now {
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                artwork(ui, cover.as_deref(), 44.0, 80, false, "🎵");
                ui.add_space(4.0);
                ui.vertical(|ui| {
                    ui.spacing_mut().item_spacing.y = 1.0;
                    ui.add_space(3.0);
                    let state = if playing { "▶ 正在播放" } else { "⏸ 已暂停" };
                    ui.label(RichText::new(state).size(11.5).color(if playing { ACCENT } else { TEXT_WEAK }));
                    ui.add(egui::Label::new(RichText::new(title).strong()).truncate());
                    if !artist.is_empty() {
                        ui.add(egui::Label::new(RichText::new(artist).size(12.0).color(TEXT_WEAK)).truncate());
                    }
                });
            });
        } else if device.online {
            ui.add_space(6.0);
            ui.label(RichText::new("空闲").size(12.5).color(TEXT_WEAK));
        }
    });
}

fn buttons(ui: &mut egui::Ui, app: &App, st: &mut UiState, device: &DeviceInfo, selected: bool, acts: &mut Vec<Action>) {
    if !device.is_self {
        if st.device_remove_confirm.as_deref() == Some(device.id.as_str()) {
            if ui.button("取消").clicked() {
                st.device_remove_confirm = None;
            }
            let confirm = egui::Button::new(RichText::new("确认移除").color(Color32::WHITE)).fill(theme::DANGER);
            if ui.add(confirm).on_hover_text("该设备需要重新登录播放器账号").clicked() {
                st.device_remove_confirm = None;
                acts.push(Action::RemoveDevice(device.id.clone()));
            }
        } else if ui.add(egui::Button::new(RichText::new("移除").color(theme::DANGER)).frame(false)).clicked() {
            st.device_remove_confirm = Some(device.id.clone());
        }
    }
    if device.is_self {
        return;
    }
    if selected {
        if ui.button("切回本机").clicked() {
            acts.push(Action::SelectDevice(None));
        }
    } else {
        let control = egui::Button::new(RichText::new("📡 遥控").strong().color(Color32::BLACK))
            .fill(ACCENT)
            .min_size(Vec2::new(88.0, 30.0));
        let response = ui.add_enabled(device.online, control);
        let response = if device.online { response } else { response.on_disabled_hover_text("设备离线") };
        if response.clicked() {
            acts.push(Action::SelectDevice(Some(device.id.clone())));
        }
        // Hand this computer's playback over: same queue, same position, paused here.
        let local = app.remote_target().is_none()
            && app.pb.current().is_some_and(|track| crate::netease::native_id(track.id).is_none());
        if local && device.online
            && ui.button("⇢ 转到此设备").on_hover_text("把本机的队列和进度转到这台设备，并暂停本机").clicked()
        {
            acts.push(Action::TransferTo(device.id.clone()));
        }
    }
}
