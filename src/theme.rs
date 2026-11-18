//! Dark, player-style visual theme.

use eframe::egui::{self, Color32, CornerRadius, FontId, Stroke, TextStyle};

pub const ACCENT: Color32 = Color32::from_rgb(0, 210, 196);
pub const ACCENT_HOVER: Color32 = Color32::from_rgb(80, 235, 222);
pub const GOLD: Color32 = Color32::from_rgb(230, 175, 30);
pub const OK_GREEN: Color32 = Color32::from_rgb(40, 200, 110);
pub const DANGER: Color32 = Color32::from_rgb(240, 90, 90);
pub const HEART: Color32 = Color32::from_rgb(255, 75, 110);

pub const BG: Color32 = Color32::from_rgb(18, 18, 21);
pub const SIDEBAR: Color32 = Color32::from_rgb(11, 11, 13);
pub const BAR: Color32 = Color32::from_rgb(24, 24, 28);
pub const CARD: Color32 = Color32::from_rgb(28, 28, 33);
pub const TEXT: Color32 = Color32::from_rgb(235, 235, 240);
pub const TEXT_WEAK: Color32 = Color32::from_rgb(150, 150, 160);

pub fn apply(ctx: &egui::Context) {
    ctx.set_theme(egui::ThemePreference::Dark);
    ctx.style_mut_of(egui::Theme::Dark, |s| {
        s.text_styles.insert(TextStyle::Body, FontId::proportional(14.0));
        s.text_styles.insert(TextStyle::Button, FontId::proportional(14.0));
        s.text_styles.insert(TextStyle::Small, FontId::proportional(11.5));
        s.text_styles.insert(TextStyle::Heading, FontId::proportional(22.0));
        s.text_styles.insert(TextStyle::Monospace, FontId::monospace(13.0));
        s.spacing.item_spacing = egui::vec2(8.0, 6.0);
        s.spacing.button_padding = egui::vec2(10.0, 5.0);
        s.spacing.interact_size.y = 26.0;
        s.spacing.scroll.floating = true;

        let v = &mut s.visuals;
        v.dark_mode = true;
        v.override_text_color = Some(TEXT);
        v.panel_fill = BG;
        v.window_fill = Color32::from_rgb(30, 30, 36);
        v.extreme_bg_color = Color32::from_rgb(10, 10, 12);
        v.faint_bg_color = Color32::from_rgb(23, 23, 27);
        v.code_bg_color = Color32::from_rgb(10, 10, 12);
        v.hyperlink_color = ACCENT;
        v.selection.bg_fill = ACCENT.gamma_multiply(0.30);
        v.selection.stroke = Stroke::new(1.0, ACCENT);
        v.window_corner_radius = CornerRadius::same(10);
        v.menu_corner_radius = CornerRadius::same(8);
        v.window_stroke = Stroke::new(1.0, Color32::from_rgb(50, 50, 58));
        v.slider_trailing_fill = true;

        let r = CornerRadius::same(6);
        let w = &mut v.widgets;
        w.noninteractive.bg_stroke = Stroke::new(1.0, Color32::from_rgb(40, 40, 46));
        w.noninteractive.fg_stroke = Stroke::new(1.0, TEXT);
        w.noninteractive.corner_radius = r;
        w.inactive.weak_bg_fill = Color32::from_rgb(38, 38, 45);
        w.inactive.bg_fill = Color32::from_rgb(55, 55, 64);
        w.inactive.bg_stroke = Stroke::NONE;
        w.inactive.fg_stroke = Stroke::new(1.0, TEXT);
        w.inactive.corner_radius = r;
        w.hovered.weak_bg_fill = Color32::from_rgb(52, 52, 62);
        w.hovered.bg_fill = Color32::from_rgb(70, 70, 82);
        w.hovered.bg_stroke = Stroke::new(1.0, Color32::from_rgb(80, 80, 92));
        w.hovered.fg_stroke = Stroke::new(1.5, Color32::WHITE);
        w.hovered.corner_radius = r;
        w.active.weak_bg_fill = Color32::from_rgb(64, 64, 76);
        w.active.bg_fill = ACCENT;
        w.active.fg_stroke = Stroke::new(1.5, Color32::WHITE);
        w.active.corner_radius = r;
        w.open.weak_bg_fill = Color32::from_rgb(52, 52, 62);
        w.open.corner_radius = r;
    });
}

pub fn parse_hex(s: &str) -> Option<Color32> {
    let h = s.trim().trim_start_matches('#');
    if h.len() != 6 {
        return None;
    }
    let p = |i: usize| u8::from_str_radix(&h[i..i + 2], 16).ok();
    Some(Color32::from_rgb(p(0)?, p(2)?, p(4)?))
}
