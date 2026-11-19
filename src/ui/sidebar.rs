use eframe::egui::{self, Align2, Color32, CursorIcon, FontId, RichText, Sense, Vec2};

use crate::app::{Action, App};
use crate::library::LibState;
use crate::route::{LibTab, Route};
use crate::theme::{self, ACCENT, TEXT_WEAK};

fn nav_item(ui: &mut egui::Ui, selected: bool, icon: &str, label: &str, badge: Option<String>) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 34.0), Sense::click());
    let p = ui.painter();
    if selected {
        p.rect_filled(rect, 6, Color32::from_rgb(36, 36, 44));
        p.rect_filled(egui::Rect::from_min_size(rect.min + egui::vec2(0.0, 8.0), egui::vec2(3.0, 18.0)), 2, ACCENT);
    } else if resp.hovered() {
        p.rect_filled(rect, 6, Color32::from_rgb(26, 26, 32));
    }
    let color = if selected { Color32::WHITE } else if resp.hovered() { theme::TEXT } else { Color32::from_rgb(185, 185, 195) };
    p.text(rect.left_center() + egui::vec2(14.0, 0.0), Align2::LEFT_CENTER, icon, FontId::proportional(15.0), if selected { ACCENT } else { color });
    p.text(rect.left_center() + egui::vec2(42.0, 0.0), Align2::LEFT_CENTER, label, FontId::proportional(14.5), color);
    if let Some(b) = badge {
        p.text(rect.right_center() - egui::vec2(12.0, 0.0), Align2::RIGHT_CENTER, b, FontId::proportional(12.0), TEXT_WEAK);
    }
    resp.on_hover_cursor(CursorIcon::PointingHand)
}

fn group_label(ui: &mut egui::Ui, s: &str) {
    ui.add_space(14.0);
    ui.label(RichText::new(s).size(11.5).color(TEXT_WEAK).strong());
    ui.add_space(2.0);
}

pub fn show(ctx: &egui::Context, app: &App, st: &crate::app::UiState, acts: &mut Vec<Action>) {
    egui::SidePanel::left("sidebar")
        .exact_width(232.0)
        .resizable(false)
        .frame(egui::Frame::new().fill(theme::SIDEBAR).inner_margin(egui::Margin::symmetric(12, 14)))
        .show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new("◆").size(22.0).color(ACCENT));
                ui.label(RichText::new("TIDAL").size(20.0).strong());
                ui.label(RichText::new("Hi-Res").size(14.0).color(TEXT_WEAK));
            });
            ui.add_space(12.0);

            let r = &app.route;
            let mut nav = |ui: &mut egui::Ui, sel: bool, icon: &str, label: &str, badge: Option<String>, to: Route| {
                if nav_item(ui, sel, icon, label, badge).clicked() {
                    acts.push(Action::Go(to));
                }
            };
            nav(ui, *r == Route::Home, "🏠", "首页", None, Route::Home);
            nav(ui, matches!(r, Route::Search(..)), "🔍", "搜索", None, Route::Search(st.search_source, app.recent_searches.first().cloned().unwrap_or_default()));

            // The controlled phone's own library comes first, since playback happens there.
            if let Some(remote) = app.remote_target().filter(|remote| remote.target_has_library()) {
                group_label(ui, &format!("📱 {}", remote.target_name()));
                use crate::route::RemoteTab;
                nav(ui, *r == Route::RemoteLibrary(RemoteTab::Songs), "🎵", "设备音乐库", None, Route::RemoteLibrary(RemoteTab::Songs));
                nav(ui, *r == Route::RemoteLibrary(RemoteTab::Recent), "🕘", "最近播放", None, Route::RemoteLibrary(RemoteTab::Recent));
            }

            group_label(ui, "我的收藏");
            let d = &app.lib.data;
            let ready = app.lib.state == LibState::Ready;
            let cnt = |n: usize| ready.then(|| n.to_string());
            nav(ui, *r == Route::Library(LibTab::Albums), "💿", "专辑", cnt(d.albums.len()), Route::Library(LibTab::Albums));
            nav(ui, *r == Route::Library(LibTab::Artists), "👤", "歌手", cnt(d.artists.len()), Route::Library(LibTab::Artists));
            nav(ui, *r == Route::Library(LibTab::Tracks), "🎵", "歌曲", cnt(d.tracks.len()), Route::Library(LibTab::Tracks));
            nav(
                ui,
                *r == Route::Library(LibTab::Playlists),
                "📃",
                "歌单",
                cnt(d.playlists.len() + d.my_playlists.len()),
                Route::Library(LibTab::Playlists),
            );

            group_label(ui, "工具");
            let active = app.active_downloads();
            nav(ui, *r == Route::Downloads, "⬇", "下载", (active > 0).then(|| active.to_string()), Route::Downloads);
            nav(ui, *r == Route::Qa, "🛡", "山灵质检", None, Route::Qa);
            if let Some(remote) = &app.remote {
                let online = remote.devices.iter().filter(|device| device.online && !device.is_self).count();
                nav(ui, *r == Route::Devices, "📡", "设备", (online > 0).then(|| format!("{online} 在线")), Route::Devices);
            }
            nav(ui, *r == Route::Settings, "⚙", "设置", None, Route::Settings);

            let lists: Vec<_> = d.my_playlists.iter().chain(d.playlists.iter().map(|f| &f.item)).collect();
            if !lists.is_empty() {
                group_label(ui, "歌单");
                egui::ScrollArea::vertical().auto_shrink([false, true]).max_height(ui.available_height() - 40.0).show(ui, |ui| {
                    for p in lists {
                        let sel = *r == Route::Playlist(p.uuid.clone());
                        let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 28.0), Sense::click());
                        if sel || resp.hovered() {
                            ui.painter().rect_filled(rect, 6, Color32::from_rgb(28, 28, 34));
                        }
                        let galley_color = if sel { ACCENT } else { Color32::from_rgb(175, 175, 185) };
                        let clip = ui.painter_at(rect.shrink2(egui::vec2(14.0, 0.0)));
                        clip.text(rect.left_center() + egui::vec2(14.0, 0.0), Align2::LEFT_CENTER, &p.title, FontId::proportional(13.5), galley_color);
                        if resp.on_hover_cursor(CursorIcon::PointingHand).on_hover_text(&p.title).clicked() {
                            acts.push(Action::Go(Route::Playlist(p.uuid.clone())));
                        }
                    }
                });
            }

            // Footer: connection state.
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                let auth = app.api.auth();
                if app.api.borrowed() {
                    let name = app.remote_target().map(crate::remote::Remote::target_name).unwrap_or_default();
                    ui.label(RichText::new(format!("● 使用 {name} 的 TIDAL 登录")).size(12.0).color(theme::OK_GREEN))
                        .on_hover_text("本机未登录 TIDAL：浏览、收藏和下载都经由这台设备，歌曲在这台设备上播放");
                } else if app.api.is_authenticated() {
                    ui.label(
                        RichText::new(format!("● 已连接 TIDAL · {}", auth.country_code.unwrap_or_default()))
                            .size(12.0)
                            .color(theme::OK_GREEN),
                    );
                } else if let Some(name) = app.remote_target().filter(|remote| {
                    remote.state.as_ref().is_some_and(|state| state.tidal_logged_in && !state.lends_tidal)
                }).map(crate::remote::Remote::target_name) {
                    ui.label(RichText::new(format!("○ {name} 未开放 TIDAL 登录")).size(12.0).color(theme::DANGER))
                        .on_hover_text(format!("{name} 已登录 TIDAL，但未开放借用。请在该设备上更新到最新版，并在 设置 › 播放器账号 中打开“允许我的其他设备借用本机的 TIDAL 登录”。"));
                } else if ui.link(RichText::new("○ 未授权 · 去设置").size(12.0).color(theme::DANGER)).clicked() {
                    acts.push(Action::Go(Route::Settings));
                }
            });
        });
}
