use eframe::egui::{self, RichText};

use crate::app::{Action, App, UiState};
use crate::route::Route;
use crate::theme::{self, OK_GREEN, TEXT_WEAK};

fn card(ui: &mut egui::Ui, title: &str, add: impl FnOnce(&mut egui::Ui)) {
    ui.add_space(12.0);
    egui::Frame::new().fill(theme::CARD).corner_radius(10).inner_margin(16).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.label(RichText::new(title).size(17.0).strong());
        ui.add_space(6.0);
        add(ui);
    });
}

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>) {
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        ui.add_space(8.0);
        ui.label(RichText::new("设置").size(30.0).strong());

        card(ui, "播放器账号", |ui| {
            ui.add_enabled_ui(!app.player_account_busy, |ui| {
                if let Some(account) = &app.player_account {
                    ui.label(&account.username);
                    if let Some(origin) = account.origin() {
                        ui.horizontal(|ui| {
                            if ui.button("📡 我的设备").on_hover_text("查看并遥控同一账号的所有设备").clicked() {
                                acts.push(Action::Go(Route::Devices));
                            }
                            if ui.button("网页遥控").on_hover_text("在浏览器打开多端遥控").clicked() {
                                acts.push(Action::OpenUrl(origin));
                            }
                        });
                    }
                    let mut lend = crate::relay::lends_tidal();
                    if ui.checkbox(&mut lend, "允许我的其他设备借用本机的 TIDAL 登录")
                        .on_hover_text("开启后，未登录 TIDAL 的同账号设备遥控本机时，可以借用本机的登录浏览、收藏，它们拿不到本机的登录凭据。关闭后仍可遥控本机下载。")
                        .changed()
                    {
                        crate::relay::LEND_TIDAL.store(lend, std::sync::atomic::Ordering::Relaxed);
                    }
                    if ui.button("退出播放器账号").clicked() {
                        acts.push(Action::PlayerLogout);
                    }
                } else {
                    ui.add(egui::TextEdit::singleline(&mut st.account_username).hint_text("用户名"));
                    ui.add(egui::TextEdit::singleline(&mut st.account_password).password(true).hint_text("密码"));
                    if ui.button("登录").clicked() {
                        acts.push(Action::PlayerLogin(st.account_username.trim().to_owned(),
                            std::mem::take(&mut st.account_password)));
                    }
                }
            });
        });

        card(ui, "TIDAL 账号", |ui| {
            let auth = app.api.auth();
            if auth.token.is_some() {
                ui.label(RichText::new("● 已授权").color(OK_GREEN));
                egui::Grid::new("acct").spacing([24.0, 6.0]).show(ui, |ui| {
                    ui.label(RichText::new("User ID").color(TEXT_WEAK));
                    ui.label(auth.user_id.clone().unwrap_or_default());
                    ui.end_row();
                    ui.label(RichText::new("地区").color(TEXT_WEAK));
                    ui.label(auth.country_code.clone().unwrap_or_default());
                    ui.end_row();
                    ui.label(RichText::new("Token 有效期").color(TEXT_WEAK));
                    match app.api.token_remaining() {
                        Some(r) if r > 0 => ui.label(format!("剩余 {} 小时 {} 分钟（过期前会自动续期）", r / 3600, (r % 3600) / 60)),
                        Some(_) => ui.label(RichText::new("已过期 —— 将在下次请求时自动刷新").color(theme::DANGER)),
                        None => ui.label("未知"),
                    };
                    ui.end_row();
                    ui.label(RichText::new("配置目录").color(TEXT_WEAK));
                    ui.label(app.api.config_path.display().to_string());
                    ui.end_row();
                });
            } else {
                ui.label(RichText::new("○ 未登录").color(theme::DANGER));
                ui.label(RichText::new("在终端运行  tiddl auth login  完成授权，然后点击“重新读取”。").color(TEXT_WEAK));
            }
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if app.auth_busy {
                    ui.spinner();
                } else if ui.button("🔄 立即刷新 Token").clicked() {
                    acts.push(Action::RefreshToken);
                }
                if ui.button("📄 重新读取 auth.json").clicked() {
                    acts.push(Action::ReloadAuth);
                }
                if ui.button("🌐 打开 TIDAL 授权页").clicked() {
                    acts.push(Action::OpenUrl("https://link.tidal.com".into()));
                }
            });
        });

        card(ui, "下载", |ui| {
            ui.label(RichText::new("默认音质始终为 Max（HiRes FLAC 最高 24bit/192kHz），每次下载时都可在菜单中改选。").color(TEXT_WEAK));
            ui.horizontal(|ui| {
                ui.label("Dolby Atmos");
                ui.selectable_value(&mut st.dl_atmos, "none".to_string(), "纯立体声（推荐）");
                ui.selectable_value(&mut st.dl_atmos, "allow".to_string(), "允许 Atmos");
            });
            ui.horizontal(|ui| {
                ui.label("保存到");
                ui.add(egui::TextEdit::singleline(&mut st.dl_dest).desired_width(440.0));
                if ui.button("📁 浏览…").clicked() {
                    if let Some(p) = rfd::FileDialog::new().set_directory(&st.dl_dest).pick_folder() {
                        st.dl_dest = p.to_string_lossy().to_string();
                    }
                }
            });
        });

        card(ui, "播放", |ui| {
            ui.horizontal(|ui| {
                ui.label("试听音质");
                for q in crate::types::StreamQuality::ALL {
                    if ui.selectable_label(app.pb.quality == q, q.short()).on_hover_text(q.describe()).clicked() {
                        acts.push(Action::SetStreamQuality(q));
                    }
                }
            });
            ui.label(
                RichText::new("Max 为 TIDAL 的 HiRes FLAC（最高 24bit/192kHz）；曲目没有 Max 版本时自动播放 High（16bit/44.1kHz FLAC）。也可以在底部播放条的音质标签上随时切换。")
                    .color(TEXT_WEAK),
            );
        });

        card(ui, "快捷键", |ui| {
            egui::Grid::new("keys").spacing([28.0, 6.0]).show(ui, |ui| {
                for (k, v) in [
                    ("空格", "播放 / 暂停"),
                    ("Ctrl + → / ←", "下一首 / 上一首"),
                    ("Ctrl + F", "搜索"),
                    ("Alt + ← / →，鼠标侧键", "后退 / 前进"),
                    ("双击歌曲", "播放"),
                    ("右键", "更多操作（下一首播放、加入队列、收藏、下载…）"),
                ] {
                    ui.label(RichText::new(k).monospace());
                    ui.label(RichText::new(v).color(TEXT_WEAK));
                    ui.end_row();
                }
            });
        });
        ui.add_space(30.0);
    });
}
