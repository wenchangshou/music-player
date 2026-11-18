//! The local music library of the Android device being controlled. Songs play on that device.

use eframe::egui::{self, Align, Color32, CursorIcon, Layout, RichText, Sense, UiBuilder, Vec2};

use crate::app::{Action, App, UiState};
use crate::route::{RemoteTab, Route};
use crate::theme::{self, ACCENT, TEXT_WEAK};
use crate::ui::widgets::{artwork, empty_state, error_box, loading};

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>, tab: RemoteTab) {
    let Some(remote) = app.remote_target().filter(|remote| remote.target_has_library()) else {
        ui.add_space(8.0);
        ui.label(RichText::new("设备音乐库").size(30.0).strong());
        empty_state(ui, "📱", "遥控一台 Android 设备后，这里会显示它的本地音乐库");
        return;
    };
    let name = remote.target_name();
    let library = &remote.library;
    ui.add_space(8.0);
    ui.label(RichText::new(format!("{name} 的音乐库")).size(30.0).strong());
    ui.label(RichText::new("这台设备上的本地音乐。播放、下一首和加入队列都在这台设备上执行。").color(TEXT_WEAK));
    ui.add_space(8.0);

    ui.horizontal(|ui| {
        for (value, label) in [(RemoteTab::Songs, "歌曲"), (RemoteTab::Recent, "最近播放")] {
            if ui.selectable_label(tab == value, RichText::new(label).size(15.0)).clicked() {
                acts.push(Action::Go(Route::RemoteLibrary(value)));
            }
        }
    });
    if tab == RemoteTab::Songs {
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            let response = ui.add(egui::TextEdit::singleline(&mut st.remote_library_input)
                .hint_text("搜索这台设备上的歌曲、艺人或专辑").desired_width(320.0));
            let enter = response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
            if ui.button("🔍 搜索").clicked() || enter {
                acts.push(Action::RemoteLibraryFilter(st.remote_library_input.clone(), library.playlist.clone()));
            }
            let mut playlist = library.playlist.clone();
            egui::ComboBox::from_id_salt("remote_playlist")
                .selected_text(if playlist.is_empty() { "全部歌曲".to_string() } else { playlist.clone() })
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut playlist, String::new(), "全部歌曲");
                    for name in &library.playlists {
                        ui.selectable_value(&mut playlist, name.clone(), name);
                    }
                });
            if playlist != library.playlist {
                acts.push(Action::RemoteLibraryFilter(library.query.clone(), playlist));
            }
            ui.separator();
            let local = library.tracks.iter().any(|track| track.local());
            let play = egui::Button::new(RichText::new("▶ 播放本页").strong().color(Color32::BLACK)).fill(ACCENT);
            if ui.add_enabled(local, play).clicked() {
                acts.push(Action::RemoteLibraryPlayAll(false));
            }
            if ui.add_enabled(local, egui::Button::new("🔀 随机播放")).clicked() {
                acts.push(Action::RemoteLibraryPlayAll(true));
            }
        });
    }
    ui.add_space(8.0);

    if let Some(error) = &library.error {
        if error_box(ui, error) {
            acts.push(Action::Refresh);
        }
        return;
    }
    if library.tracks.is_empty() {
        if library.loading || library.loaded.is_none() {
            loading(ui, "正在读取设备音乐库…");
        } else if tab == RemoteTab::Recent {
            empty_state(ui, "🕘", "这台设备还没有播放记录");
        } else {
            empty_state(ui, "🎵", if library.query.is_empty() { "这台设备还没有导入本地音乐" } else { "没有找到相关歌曲" });
        }
        return;
    }
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        for (index, track) in library.tracks.iter().enumerate() {
            let (rect, response) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 54.0), Sense::click());
            let hovered = response.hovered();
            if hovered {
                ui.painter().rect_filled(rect, 6, Color32::from_rgb(30, 30, 36));
            }
            let mut row = ui.new_child(UiBuilder::new().max_rect(rect.shrink2(Vec2::new(8.0, 5.0))).layout(Layout::left_to_right(Align::Center)));
            artwork(&mut row, track.cover.as_deref(), 44.0, 80, false, if track.local() { "🎵" } else { "◆" });
            row.add_space(6.0);
            row.allocate_ui_with_layout(Vec2::new(row.available_width() - 90.0, 44.0), Layout::top_down(Align::Min), |ui| {
                ui.spacing_mut().item_spacing.y = 1.0;
                ui.add_space(3.0);
                ui.add(egui::Label::new(RichText::new(&track.title).color(theme::TEXT)).truncate().selectable(false));
                let detail = [track.artist.as_str(), track.album.as_str()].iter().filter(|part| !part.is_empty()).copied().collect::<Vec<_>>().join(" · ");
                ui.add(egui::Label::new(RichText::new(detail).size(12.0).color(TEXT_WEAK)).truncate().selectable(false));
            });
            let mut clicked_button = false;
            row.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if hovered {
                    if ui.add(egui::Button::new("＋").frame(false)).on_hover_text("加入队列末尾").clicked() {
                        acts.push(Action::RemoteLibrarySong(track.id, "add"));
                        clicked_button = true;
                    }
                    if ui.add(egui::Button::new("⤵").frame(false)).on_hover_text("下一首播放").clicked() {
                        acts.push(Action::RemoteLibrarySong(track.id, "next"));
                        clicked_button = true;
                    }
                }
            });
            if response.on_hover_cursor(CursorIcon::PointingHand).on_hover_text("在这台设备播放").clicked() && !clicked_button {
                acts.push(Action::RemoteLibraryPlay(index));
            }
        }
        if library.next.is_some() {
            ui.add_space(8.0);
            if library.loading {
                ui.spinner();
            } else if ui.button("加载更多").clicked() {
                acts.push(Action::RemoteLibraryMore);
            }
        }
    });
}
