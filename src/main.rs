mod api;
mod app;
mod download;
mod library;
mod netease;
mod playback;
mod player;
mod qa;
mod relay;
mod remote;
mod route;
mod theme;
mod types;
mod ui;

use app::App;
use eframe::egui;

fn setup_fonts(ctx: &egui::Context) {
    let font_candidates = [
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc",
        "/usr/share/fonts/truetype/arphic/uming.ttc",
    ];

    for path in font_candidates {
        if let Ok(font_data) = std::fs::read(path) {
            let mut fonts = egui::FontDefinitions::default();
            fonts.font_data.insert(
                "cjk_font".to_owned(),
                std::sync::Arc::new(egui::FontData::from_owned(font_data)),
            );
            fonts
                .families
                .entry(egui::FontFamily::Proportional)
                .or_default()
                .insert(0, "cjk_font".to_owned());
            fonts
                .families
                .entry(egui::FontFamily::Monospace)
                .or_default()
                .push("cjk_font".to_owned());
            ctx.set_fonts(fonts);
            break;
        }
    }
}

fn main() -> eframe::Result<()> {
    let native_options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1360.0, 860.0])
            .with_min_inner_size([1000.0, 640.0])
            .with_title("TIDAL Hi-Res Player"),
        ..Default::default()
    };

    eframe::run_native(
        "TIDAL Hi-Res Manager", // storage key: keep stable so saved settings/window size survive
        native_options,
        Box::new(|cc| {
            setup_fonts(&cc.egui_ctx);
            theme::apply(&cc.egui_ctx);
            egui_extras::install_image_loaders(&cc.egui_ctx);
            Ok(Box::new(App::new(cc)))
        }),
    )
}
