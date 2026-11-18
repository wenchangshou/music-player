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
    let (text, color) = match &app.pb.stream {
        Some(info) => (info.label(), quality_color(&info.quality)),
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
        ui.label(RichText::new("试听音质").size(12.0).color(TEXT_WEAK));
        let netease = app.pb.current().is_some_and(|t| crate::netease::native_id(t.id).is_some());
        for q in StreamQuality::ALL {
            let sel = app.pb.quality == q;
            let label = format!("{}  {}  ·  {}", if sel { "●" } else { "○" }, q.short(), if netease { crate::netease::level(q) } else { q.describe() });
            let txt = if sel { RichText::new(label).color(ACCENT).strong() } else { RichText::new(label) };
            if ui.button(txt).clicked() {
                acts.push(Action::SetStreamQuality(q));
                ui.close_menu();
            }
        }
        ui.separator();
        ui.label(RichText::new("Hi-Res 需要曲目本身支持，否则自动播放无损").size(11.5).color(TEXT_WEAK));
    })
    .response
    .on_hover_text(hover);
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
            cui.add_space(2.0);
            cui.horizontal(|ui| {
                // Centre the row of buttons.
                let row_w = 5.0 * 34.0 + 4.0 * 14.0 + 10.0;
                ui.add_space(((ui.available_width() - row_w) / 2.0).max(0.0));
                ui.spacing_mut().item_spacing.x = 14.0;
                if icon_btn(ui, "🔀", 16.0, app.pb.shuffle, true).on_hover_text("随机播放").clicked() {
                    acts.push(Action::ToggleShuffle);
                }
                if icon_btn(ui, "⏮", 18.0, false, has).on_hover_text("上一首 (Ctrl+←)").clicked() {
                    acts.push(Action::Prev);
                }
                // Big play/pause circle.
                let (rect, resp) = ui.allocate_exact_size(Vec2::splat(38.0), Sense::click());
                let fill = if !has { Color32::from_rgb(90, 90, 98) } else if resp.hovered() { Color32::WHITE } else { Color32::from_rgb(235, 235, 240) };
                ui.painter().circle_filled(rect.center(), 19.0, fill);
                if app.pb.is_loading() {
                    egui::Spinner::new().size(18.0).color(Color32::BLACK).paint_at(ui, Rect::from_center_size(rect.center(), Vec2::splat(18.0)));
                } else {
                    let (icon, dx) = if ps.active && !ps.paused { ("⏸", 0.0) } else { ("▶", 1.5) };
                    ui.painter().text(rect.center() + egui::vec2(dx, 0.0), Align2::CENTER_CENTER, icon, FontId::proportional(17.0), Color32::BLACK);
                }
                if has && resp.on_hover_cursor(CursorIcon::PointingHand).on_hover_text("播放/暂停 (空格)").clicked() {
                    acts.push(Action::TogglePause);
                }
                if icon_btn(ui, "⏭", 18.0, false, app.pb.next_index(false).is_some()).on_hover_text("下一首 (Ctrl+→)").clicked() {
                    acts.push(Action::Next);
                }
                let (ri, rt) = match app.pb.repeat {
                    Repeat::Off => ("🔁", "循环：关"),
                    Repeat::All => ("🔁", "循环：全部"),
                    Repeat::One => ("🔂", "循环：单曲"),
                };
                if icon_btn(ui, ri, 16.0, app.pb.repeat != Repeat::Off, true).on_hover_text(rt).clicked() {
                    acts.push(Action::CycleRepeat);
                }
            });
            cui.horizontal(|ui| {
                let dur = if ps.duration > 0.0 { ps.duration } else { track.as_ref().and_then(|t| t.duration).unwrap_or(0) as f64 };
                let mut pos = st.seek_drag.unwrap_or(ps.position).clamp(0.0, dur.max(0.0));
                let tw = 44.0;
                ui.add_sized([tw, 16.0], egui::Label::new(RichText::new(fmt_duration(pos as u32)).size(11.5).color(TEXT_WEAK)));
                ui.spacing_mut().slider_width = (ui.available_width() - tw - 16.0).max(60.0);
                let r = ui.add_enabled(
                    ps.active && dur > 0.0,
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
