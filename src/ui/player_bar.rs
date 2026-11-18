use eframe::egui::{self, Align, Align2, Color32, CursorIcon, FontId, Layout, Rect, RichText, Sense, UiBuilder, Vec2};

use crate::app::{Action, App, UiState};
use crate::library::FavTarget;
use crate::playback::Repeat;
use crate::route::Route;
use crate::theme::{self, ACCENT, TEXT_WEAK};
use crate::types::{fmt_duration, StreamQuality};
use crate::ui::widgets::{artwork, heart, link_label};

fn quality_color(q: &str) -> Color32 {
    match q {
        "HI_RES_LOSSLESS" => theme::GOLD,
        "LOSSLESS" => Color32::from_rgb(0, 185, 175),
        _ => Color32::from_rgb(160, 160, 170),
    }
}

fn khz(r: u32) -> String {
    format!("{}kHz", r as f32 / 1000.0)
}

/// Bit depth: TIDAL's stated depth if known, else derived from mpv's decoded sample format.
fn bits(app: &App, fmt: Option<&str>) -> Option<String> {
    app.pb.stream.as_ref().and_then(|s| s.bit_depth).map(|b| format!("{}bit", b)).or_else(|| match fmt {
        Some("s16" | "s16p") => Some("16bit".into()),
        Some("s32" | "s32p") => Some("24bit".into()),
        Some("float" | "floatp") => Some("32bit float".into()),
        _ => None,
    })
}

/// One-line live audio parameters + multi-line details for the tooltip.
fn audio_params(app: &App, ps: &crate::player::PlayerState) -> Option<(String, String, Color32)> {
    let a = &ps.audio;
    if !ps.active || a.samplerate.is_none() {
        return None;
    }
    let info = app.pb.stream.as_ref();
    let color = info.map(|i| quality_color(&i.quality)).unwrap_or(TEXT_WEAK);
    let codec = a.codec.clone().unwrap_or_default().to_uppercase();
    let lossy = !matches!(codec.as_str(), "FLAC" | "ALAC");
    let mut parts = vec![codec.clone()];
    if !lossy {
        if let Some(b) = bits(app, a.format.as_deref()) {
            parts.push(b);
        }
    }
    if let Some(r) = a.samplerate {
        parts.push(khz(r));
    }
    if let Some(c) = a.channels {
        parts.push(if c == 2 { "立体声".into() } else { format!("{}ch", c) });
    }
    if let Some(br) = a.bitrate.filter(|b| *b > 0.0) {
        parts.push(format!("{:.0} kbps", br / 1000.0));
    }
    let short = parts.join(" · ");

    let mut d = vec!["音频参数".to_string()];
    if let Some(i) = info {
        let quality = if app.pb.current().and_then(|t| crate::netease::native_id(t.id)).is_some() {
            i.quality.clone()
        } else {
            format!("TIDAL {}", i.quality)
        };
        d.push(format!("来源　{}（请求：{}）", quality, i.requested.short()));
    }
    d.push(format!("编码　{}", codec));
    if !lossy {
        d.push(format!(
            "位深　{}（解码格式 {}）",
            bits(app, a.format.as_deref()).unwrap_or_else(|| "?".into()),
            a.format.clone().unwrap_or_default()
        ));
    }
    if let Some(r) = a.samplerate {
        d.push(format!("采样率　{}", khz(r)));
    }
    if let Some(c) = a.channels {
        d.push(format!("声道　{}", c));
    }
    if let Some(br) = a.bitrate {
        d.push(format!("实时码率　{:.0} kbps", br / 1000.0));
    }
    if let (Some(f), Some(r)) = (&a.out_format, a.out_samplerate) {
        let bitperfect = a.samplerate == Some(r);
        d.push(format!(
            "输出　{} / {} → {}{}",
            f,
            khz(r),
            a.ao.clone().unwrap_or_else(|| "audio".into()),
            if bitperfect { "（采样率未重采样）" } else { "（已重采样）" }
        ));
    }
    if let Some(n) = info.and_then(|i| i.note.clone()) {
        d.push(n);
    }
    Some((short, d.join("\n"), color))
}

/// Clickable quality badge that opens the preview-quality menu.
fn quality_menu(ui: &mut egui::Ui, app: &App, acts: &mut Vec<Action>) {
    let netease = app.pb.current().is_some_and(|t| crate::netease::native_id(t.id).is_some());
    let (text, color) = match &app.pb.stream {
        Some(info) => (info.label(), quality_color(&info.quality)),
        None if netease => (format!("网易云 · {}", crate::netease::level_name(app.pb.netease_level)), quality_color("LOSSLESS")),
        None => (app.pb.quality.short().to_string(), quality_color(app.pb.quality.api_name())),
    };
    let btn = egui::Button::new(RichText::new(format!("{} ▼", text)).size(11.5).strong().color(color))
        .fill(color.gamma_multiply(0.14))
        .stroke(egui::Stroke::new(1.0, color.gamma_multiply(0.55)))
        .corner_radius(4)
        .min_size(Vec2::new(0.0, 22.0));
    let hover = match app.pb.stream.as_ref().and_then(|s| s.note.clone()) {
        Some(n) => format!("试听音质（点击切换）\n{}", n),
        None => "试听音质（点击切换）".to_string(),
    };
    egui::menu::menu_custom_button(ui, btn, |ui| {
        ui.set_min_width(250.0);
        if netease {
            ui.label(RichText::new("网易云播放音质").size(12.0).color(TEXT_WEAK));
            netease_levels(ui, app.pb.netease_level, acts);
            return;
        }
        ui.label(RichText::new("TIDAL 播放音质").size(12.0).color(TEXT_WEAK));
        for q in StreamQuality::ALL {
            let sel = app.pb.quality == q;
            let label = format!("{}  {}  ·  {}", if sel { "●" } else { "○" }, q.short(), q.describe());
            let txt = if sel { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) };
            if ui.button(txt).clicked() {
                acts.push(Action::SetStreamQuality(q));
                ui.close_menu();
            }
        }
        ui.separator();
        ui.label(RichText::new("Max 需要曲目本身支持，否则自动播放 High").size(11.5).color(TEXT_WEAK));
    })
    .response
    .on_hover_text(hover);
}

/// 网易云's levels by their official names; levels above 无损 need the matching membership.
fn netease_levels(ui: &mut egui::Ui, selected: &str, acts: &mut Vec<Action>) {
    for (level, name, describe) in crate::netease::PLAY_LEVELS {
        let sel = selected == level;
        let label = format!("{}  {}  ·  {}", if sel { "●" } else { "○" }, name, describe);
        let text = if sel { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) };
        if ui.button(text).clicked() {
            acts.push(Action::SetNeteaseLevel(level));
            ui.close_menu();
        }
    }
    ui.separator();
    ui.label(RichText::new("超清母带、环绕声和 Hi-Res 需要对应会员和歌曲支持，否则网易云返回较低音质").size(11.5).color(TEXT_WEAK));
}

fn icon_btn(ui: &mut egui::Ui, icon: &str, size: f32, on: bool, enabled: bool) -> egui::Response {
    let color = if !enabled {
        Color32::from_rgb(80, 80, 88)
    } else if on {
        ACCENT
    } else {
        Color32::from_rgb(200, 200, 208)
    };
    ui.add_enabled(enabled, egui::Button::new(RichText::new(icon).size(size).color(color)).frame(false))
        .on_hover_cursor(CursorIcon::PointingHand)
}

/// What the transport controls show: this computer's player or the remote device's.
struct Transport {
    has: bool,
    playing: bool,
    loading: bool,
    shuffle: bool,
    repeat: Repeat,
    has_next: bool,
    position: f64,
    duration: f64,
    seekable: bool,
}

/// Transport buttons and progress bar. The actions run on whichever device is selected.
fn transport(cui: &mut egui::Ui, st: &mut UiState, acts: &mut Vec<Action>, t: &Transport) {
    cui.add_space(2.0);
    cui.horizontal(|ui| {
        // Centre the row of buttons.
        let row_w = 5.0 * 34.0 + 4.0 * 14.0 + 10.0;
        ui.add_space(((ui.available_width() - row_w) / 2.0).max(0.0));
        ui.spacing_mut().item_spacing.x = 14.0;
        if icon_btn(ui, "🔀", 16.0, t.shuffle, true).on_hover_text("随机播放").clicked() {
            acts.push(Action::ToggleShuffle);
        }
        if icon_btn(ui, "⏮", 18.0, false, t.has).on_hover_text("上一首 (Ctrl+←)").clicked() {
            acts.push(Action::Prev);
        }
        // Big play/pause circle.
        let (rect, resp) = ui.allocate_exact_size(Vec2::splat(38.0), Sense::click());
        let fill = if !t.has { Color32::from_rgb(90, 90, 98) } else if resp.hovered() { Color32::WHITE } else { Color32::from_rgb(235, 235, 240) };
        ui.painter().circle_filled(rect.center(), 19.0, fill);
        if t.loading {
            egui::Spinner::new().size(18.0).color(Color32::BLACK).paint_at(ui, Rect::from_center_size(rect.center(), Vec2::splat(18.0)));
        } else {
            let (icon, dx) = if t.playing { ("⏸", 0.0) } else { ("▶", 1.5) };
            ui.painter().text(rect.center() + egui::vec2(dx, 0.0), Align2::CENTER_CENTER, icon, FontId::proportional(17.0), Color32::BLACK);
        }
        if t.has && resp.on_hover_cursor(CursorIcon::PointingHand).on_hover_text("播放/暂停 (空格)").clicked() {
            acts.push(Action::TogglePause);
        }
        if icon_btn(ui, "⏭", 18.0, false, t.has_next).on_hover_text("下一首 (Ctrl+→)").clicked() {
            acts.push(Action::Next);
        }
        let (ri, rt) = match t.repeat {
            Repeat::Off => ("🔁", "循环：关"),
            Repeat::All => ("🔁", "循环：全部"),
            Repeat::One => ("🔂", "循环：单曲"),
        };
        if icon_btn(ui, ri, 16.0, t.repeat != Repeat::Off, true).on_hover_text(rt).clicked() {
            acts.push(Action::CycleRepeat);
        }
    });
    cui.horizontal(|ui| {
        let dur = t.duration;
        let mut pos = st.seek_drag.unwrap_or(t.position).clamp(0.0, dur.max(0.0));
        let tw = 44.0;
        ui.add_sized([tw, 16.0], egui::Label::new(RichText::new(fmt_duration(pos as u32)).size(11.5).color(TEXT_WEAK)));
        ui.spacing_mut().slider_width = (ui.available_width() - tw - 16.0).max(60.0);
        let r = ui.add_enabled(
            t.seekable,
            egui::Slider::new(&mut pos, 0.0..=dur.max(1.0)).show_value(false).trailing_fill(true),
        );
        if r.dragged() {
            st.seek_drag = Some(pos);
        } else if r.drag_stopped() || r.changed() {
            acts.push(Action::Seek(pos));
            st.seek_drag = None;
        }
        ui.add_sized([tw, 16.0], egui::Label::new(RichText::new(fmt_duration(dur as u32)).size(11.5).color(TEXT_WEAK)));
    });
}

/// Picker for the device the player bar controls (this computer or another of the account's).
/// Picker for the device the app controls. `pill` shows it labelled, for the top bar.
pub fn device_menu(ui: &mut egui::Ui, app: &App, acts: &mut Vec<Action>, pill: bool) {
    let Some(remote) = &app.remote else { return };
    let target = remote.target_device();
    let (icon, color) = match target {
        Some(device) => (device.icon(), ACCENT),
        None => ("📡", Color32::from_rgb(200, 200, 208)),
    };
    let button = if pill {
        let (text, fill, ink) = match target {
            Some(device) => (format!("{} {} ▾", device.icon(), device.name), ACCENT, Color32::BLACK),
            None => ("💻 本机 ▾".to_string(), Color32::from_rgb(34, 34, 40), theme::TEXT),
        };
        egui::Button::new(RichText::new(text).size(14.0).strong().color(ink)).fill(fill).corner_radius(16)
            .min_size(Vec2::new(0.0, 30.0))
    } else {
        egui::Button::new(RichText::new(icon).size(15.0).color(color)).frame(false)
    };
    let hover = match target {
        Some(device) => format!("正在遥控：{}", device.name),
        None => "选择播放设备".to_string(),
    };
    let response = egui::menu::menu_custom_button(ui, button, |ui| {
        ui.set_min_width(290.0);
        ui.label(RichText::new("播放设备").size(12.0).color(TEXT_WEAK));
        let local = remote.target.is_none();
        let label = format!("{}  本机 · 此电脑", if local { "●" } else { "○" });
        if ui.button(if local { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) }).clicked() {
            acts.push(Action::SelectDevice(None));
            ui.close_menu();
        }
        let others: Vec<_> = remote.devices.iter().filter(|device| !device.is_self).collect();
        for device in &others {
            let selected = remote.target.as_deref() == Some(device.id.as_str());
            let status = match (&device.now, device.online) {
                (_, false) => "离线".to_string(),
                (Some(now), true) => format!("{} {}", if now.playing { "▶" } else { "⏸" }, now.title),
                (None, true) => "在线 · 空闲".to_string(),
            };
            let label = format!("{}  {} {}\n      {}", if selected { "●" } else { "○" }, device.icon(), device.name, status);
            let text = if selected { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) };
            if ui.add_enabled(device.online, egui::Button::new(text)).clicked() {
                acts.push(Action::SelectDevice(Some(device.id.clone())));
                ui.close_menu();
            }
        }
        if others.is_empty() {
            let note = if remote.devices_loaded { "其他电脑或手机登录同一播放器账号后会出现在这里" } else { "正在读取设备…" };
            ui.label(RichText::new(note).size(11.5).color(TEXT_WEAK));
        }
        if let Some(error) = &remote.devices_error {
            ui.label(RichText::new(error).size(11.5).color(theme::DANGER));
        }
        ui.separator();
        if ui.button("管理设备…").clicked() {
            acts.push(Action::Go(Route::Devices));
            ui.close_menu();
        }
    })
    .response
    .on_hover_text(hover);
    if response.clicked() {
        acts.push(Action::RefreshDevices);
    }
}

pub fn show(ctx: &egui::Context, app: &App, st: &mut UiState, acts: &mut Vec<Action>) {
    egui::TopBottomPanel::bottom("player_bar")
        .exact_height(88.0)
        .show_separator_line(false)
        .frame(
            egui::Frame::new()
                .fill(theme::BAR)
                .stroke(egui::Stroke::new(1.0, Color32::from_rgb(38, 38, 44)))
                .inner_margin(egui::Margin::symmetric(16, 10)),
        )
        .show(ctx, |ui| {
            let full = ui.max_rect();
            let w = full.width();
            let left = Rect::from_min_size(full.min, Vec2::new(w * 0.30, full.height()));
            let center = Rect::from_min_size(full.min + Vec2::new(w * 0.30, 0.0), Vec2::new(w * 0.40, full.height()));
            let right = Rect::from_min_size(full.min + Vec2::new(w * 0.70, 0.0), Vec2::new(w * 0.30, full.height()));
            if let Some(remote) = app.remote_target() {
                show_remote(ui, [left, center, right], app, remote, st, acts);
                return;
            }

            let ps = app.pb.player.snapshot();
            let track = app.pb.current().cloned();
            let has = track.is_some();

            // ── Left: now playing ──
            let mut lui = ui.new_child(UiBuilder::new().max_rect(left).layout(Layout::left_to_right(Align::Center)));
            if let Some(t) = &track {
                let r = artwork(&mut lui, t.cover(), 60.0, 160, false, "🎵").on_hover_text("打开专辑");
                if r.clicked() {
                    if let Some(a) = &t.album {
                        acts.push(Action::Go(Route::Album(a.id)));
                    }
                }
                lui.add_space(6.0);
                lui.allocate_ui_with_layout(Vec2::new((left.width() - 120.0).max(80.0), 64.0), Layout::top_down(Align::Min), |ui| {
                    ui.add_space(1.0);
                    ui.spacing_mut().item_spacing.y = 1.0;
                    if link_label(ui, RichText::new(t.full_title()).strong().size(14.5)).clicked() {
                        if let Some(a) = &t.album {
                            acts.push(Action::Go(Route::Album(a.id)));
                        }
                    }
                    if link_label(ui, RichText::new(t.artist_names()).color(TEXT_WEAK).size(12.5)).clicked() {
                        if let Some(a) = t.main_artist() {
                            acts.push(Action::Go(Route::Artist(a.id)));
                        }
                    }
                    // Live audio parameters from mpv.
                    match audio_params(app, &ps) {
                        Some((short, detail, color)) => {
                            ui.add(egui::Label::new(RichText::new(format!("◈ {}", short)).size(11.5).color(color)).truncate().sense(Sense::hover()))
                                .on_hover_text(RichText::new(detail).size(12.5));
                        }
                        None if app.pb.is_loading() => {
                            ui.label(RichText::new("正在缓冲…").size(11.5).color(TEXT_WEAK));
                        }
                        None => {}
                    }
                });
                if crate::netease::native_id(t.id).is_none() && heart(&mut lui, app.lib.has_track(t.id), 17.0).clicked() {
                    acts.push(Action::ToggleFav(FavTarget::Track(t.clone())));
                }
            } else {
                lui.label(RichText::new("🎧  选择一首歌开始播放").color(TEXT_WEAK));
            }

            // ── Center: transport + progress ──
            let mut cui = ui.new_child(UiBuilder::new().max_rect(center).layout(Layout::top_down(Align::Center)));
            let dur = if ps.duration > 0.0 { ps.duration } else { track.as_ref().and_then(|t| t.duration).unwrap_or(0) as f64 };
            transport(&mut cui, st, acts, &Transport {
                has,
                playing: ps.active && !ps.paused,
                loading: app.pb.is_loading(),
                shuffle: app.pb.shuffle,
                repeat: app.pb.repeat,
                has_next: app.pb.next_index(false).is_some(),
                position: ps.position,
                duration: dur,
                seekable: ps.active && dur > 0.0,
            });

            // ── Right: stream info, queue, volume ──
            let mut rui = ui.new_child(UiBuilder::new().max_rect(right).layout(Layout::right_to_left(Align::Center)));
            rui.spacing_mut().slider_width = 96.0;
            let mut vol = st.volume;
            if rui.add(egui::Slider::new(&mut vol, 0.0..=100.0).show_value(false)).on_hover_text(format!("音量 {:.0}", vol)).changed() {
                st.volume = vol;
                acts.push(Action::SetVolume(vol));
            }
            let vi = if vol < 1.0 { "🔇" } else if vol < 50.0 { "🔉" } else { "🔊" };
            if icon_btn(&mut rui, vi, 15.0, false, true).on_hover_text("静音/恢复").clicked() {
                let v = if vol < 1.0 { 70.0 } else { 0.0 };
                st.volume = v;
                acts.push(Action::SetVolume(v));
            }
            if icon_btn(&mut rui, "☰", 16.0, st.show_queue, true).on_hover_text("播放队列").clicked() {
                st.show_queue = !st.show_queue;
            }
            device_menu(&mut rui, app, acts, false);
            rui.add_space(6.0);
            rui.allocate_ui_with_layout(Vec2::new(170.0, 24.0), Layout::right_to_left(Align::Center), |ui| {
                quality_menu(ui, app, acts);
            });
            if let Some(c) = &app.pb.context {
                rui.allocate_ui_with_layout(Vec2::new((rui.available_width() - 4.0).max(40.0), 22.0), Layout::right_to_left(Align::Center), |ui| {
                    if link_label(ui, RichText::new(&c.label).size(12.0).color(TEXT_WEAK)).on_hover_text("正在播放的来源").clicked() {
                        acts.push(Action::Go(c.route.clone()));
                    }
                });
            }
        });
}

/// The player bar while another device is selected: same layout, showing and controlling that device.
fn show_remote(
    ui: &mut egui::Ui,
    [left, center, right]: [Rect; 3],
    app: &App,
    remote: &crate::remote::Remote,
    st: &mut UiState,
    acts: &mut Vec<Action>,
) {
    let full = ui.max_rect();
    ui.painter().hline(full.x_range(), full.top() - 10.0, egui::Stroke::new(2.0, ACCENT));
    let state = remote.state.as_ref();
    let track = state.and_then(crate::remote::RemoteState::current);
    let device = remote.target_device();
    let name = remote.target_name();
    let icon = device.map_or("📡", crate::remote::DeviceInfo::icon);

    // ── Left: now playing on the device ──
    let mut lui = ui.new_child(UiBuilder::new().max_rect(left).layout(Layout::left_to_right(Align::Center)));
    match track {
        Some(t) => {
            artwork(&mut lui, t.cover.as_deref(), 60.0, 160, false, "🎵");
        }
        None => {
            artwork(&mut lui, None, 60.0, 160, false, icon);
        }
    }
    lui.add_space(6.0);
    lui.allocate_ui_with_layout(Vec2::new((left.width() - 80.0).max(80.0), 64.0), Layout::top_down(Align::Min), |ui| {
        ui.add_space(1.0);
        ui.spacing_mut().item_spacing.y = 1.0;
        let (title, artist) = match track {
            Some(t) => (t.title.clone(), t.artist.clone()),
            None if state.is_some() => ("尚未播放".to_string(), "可从歌曲菜单发送到此设备".to_string()),
            None => ("正在连接…".to_string(), String::new()),
        };
        ui.add(egui::Label::new(RichText::new(title).strong().size(14.5)).truncate());
        ui.add(egui::Label::new(RichText::new(artist).color(TEXT_WEAK).size(12.5)).truncate());
        let status = match (&remote.error, state.and_then(|state| state.error.as_ref())) {
            (Some(error), _) | (None, Some(error)) => RichText::new(format!("⚠ {error}")).color(theme::DANGER),
            _ if state.is_some_and(|state| state.loading) => RichText::new(format!("{icon} 正在遥控 · {name} · 缓冲中…")).color(ACCENT),
            _ => RichText::new(format!("{icon} 正在遥控 · {name}")).color(ACCENT),
        };
        // 播放信息 on hover, like this computer's audio parameters: source and, for local files, the path.
        let mut details = vec!["播放信息".to_string(), format!("播放设备　{name}")];
        if let Some(t) = track {
            let local = t.source == "本地音乐";
            details.push(format!("来源　{}", if local { "本地文件" } else { t.source.as_str() }));
            if let Some(path) = state.and_then(|state| state.source_path.as_deref()).filter(|_| local) {
                details.push(format!("文件路径　{path}"));
            }
        }
        if let Some(quality) = state.and_then(|state| state.quality.as_ref()) {
            details.push(format!("实际音质　{quality}"));
        }
        ui.add(egui::Label::new(status.size(11.5)).truncate().sense(Sense::hover()))
            .on_hover_text(RichText::new(details.join("\n")).size(12.5));
    });

    // ── Center: transport + progress (same controls, sent to the device) ──
    let mut cui = ui.new_child(UiBuilder::new().max_rect(center).layout(Layout::top_down(Align::Center)));
    transport(&mut cui, st, acts, &Transport {
        has: track.is_some(),
        playing: state.is_some_and(|state| state.playing),
        loading: state.is_some_and(|state| state.loading) || remote.command_busy && state.is_none(),
        shuffle: state.is_some_and(|state| state.shuffle),
        repeat: state.map_or(Repeat::Off, |state| state.repeat),
        has_next: state.is_some_and(crate::remote::RemoteState::has_next),
        position: state.map_or(0.0, crate::remote::RemoteState::position),
        duration: state.map_or(0.0, crate::remote::RemoteState::duration),
        seekable: state.is_some_and(|state| state.seekable && state.duration_ms > 0),
    });

    // ── Right: back to this computer, device picker, queue ──
    let mut rui = ui.new_child(UiBuilder::new().max_rect(right).layout(Layout::right_to_left(Align::Center)));
    if rui.button("切回本机").on_hover_text("让播放条重新控制这台电脑").clicked() {
        acts.push(Action::SelectDevice(None));
    }
    if track.is_some_and(|track| track.tidal_id.is_some())
        && rui.button("在本机继续").on_hover_text(format!("把 {name} 的队列和进度转到这台电脑，并暂停 {name}")).clicked()
    {
        acts.push(Action::TransferHere);
    }
    if let Some(volume) = state.and_then(|state| state.volume) {
        // Send the volume once the drag ends, not on every step of it.
        rui.spacing_mut().slider_width = 96.0;
        let mut vol = st.remote_volume_drag.unwrap_or(volume);
        let response = rui.add(egui::Slider::new(&mut vol, 0.0..=100.0).show_value(false))
            .on_hover_text(format!("{name} 音量 {vol:.0}"));
        if response.dragged() {
            st.remote_volume_drag = Some(vol);
        } else if response.drag_stopped() || response.changed() {
            st.remote_volume_drag = None;
            acts.push(Action::SetVolume(vol));
        }
        let icon = if vol < 1.0 { "🔇" } else if vol < 50.0 { "🔉" } else { "🔊" };
        rui.label(RichText::new(icon).size(15.0).color(Color32::from_rgb(200, 200, 208)));
    }
    if icon_btn(&mut rui, "☰", 16.0, st.show_queue, true).on_hover_text("播放队列").clicked() {
        st.show_queue = !st.show_queue;
    }
    device_menu(&mut rui, app, acts, false);
    rui.add_space(6.0);
    if let Some(state) = state.filter(|state| state.requested.is_some() || state.quality.is_some()) {
        let netease = track.is_some_and(|track| track.source == "网易云");
        let text = state.quality.clone().or_else(|| state.requested.map(|quality| quality.short().to_string())).unwrap_or_default();
        let color = if text.starts_with("Max") { theme::GOLD } else { Color32::from_rgb(0, 185, 175) };
        let button = egui::Button::new(RichText::new(format!("{text} ▼")).size(11.5).strong().color(color))
            .fill(color.gamma_multiply(0.14))
            .stroke(egui::Stroke::new(1.0, color.gamma_multiply(0.55)))
            .corner_radius(4)
            .min_size(Vec2::new(0.0, 22.0));
        egui::menu::menu_custom_button(&mut rui, button, |ui| {
            ui.set_min_width(250.0);
            ui.label(RichText::new(format!("{name} 的{}播放音质", if netease { "网易云" } else { "TIDAL " })).size(12.0).color(TEXT_WEAK));
            if netease {
                netease_levels(ui, state.requested_netease.as_deref().unwrap_or_default(), acts);
                return;
            }
            for quality in StreamQuality::ALL {
                let selected = state.requested == Some(quality);
                let label = format!("{}  {}  ·  {}", if selected { "●" } else { "○" }, quality.short(), quality.describe());
                let text = if selected { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) };
                if ui.button(text).clicked() {
                    acts.push(Action::SetStreamQuality(quality));
                    ui.close_menu();
                }
            }
        })
        .response
        .on_hover_text(format!("{name} 的播放音质（点击切换）"));
    }
    if remote.pending() > 0 {
        rui.add(egui::Spinner::new().size(14.0)).on_hover_text(format!("正在发送 {} 个命令", remote.pending()));
    }
}
