use eframe::egui::{self, RichText};

use crate::app::{Action, App, UiState};
use crate::theme::{self, OK_GREEN, TEXT_WEAK};
use crate::ui::widgets::*;

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>) {
    ui.add_space(8.0);
    ui.label(RichText::new("山灵质检").size(30.0).strong());
    ui.label(
        RichText::new("扫描专辑目录：生成 1000×1000 JPEG 封面并内嵌 FLAC、核对 LRC 同步歌词、FFmpeg 全无损解码校验")
            .color(TEXT_WEAK),
    );
    ui.add_space(10.0);
    egui::Frame::new().fill(theme::CARD).corner_radius(10).inner_margin(14).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.horizontal(|ui| {
            ui.label(RichText::new("专辑目录").color(TEXT_WEAK));
            ui.add(egui::TextEdit::singleline(&mut st.qa_folder).desired_width(480.0));
            if ui.button("📁 浏览…").clicked() {
                if let Some(p) = rfd::FileDialog::new().set_directory(&st.qa_folder).pick_folder() {
                    st.qa_folder = p.to_string_lossy().to_string();
                }
            }
            if app.qa_running {
                ui.spinner();
                ui.label("处理中…");
            } else if ui.button("🛡 执行质检与修复").clicked() {
                acts.push(Action::RunQa);
            }
        });
    });

    let Some(report) = &app.qa_report else {
        return empty_state(ui, "🛡", "选择一个已下载的专辑目录开始质检");
    };
    ui.add_space(14.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(format!("音轨 {} 首", report.total_tracks)).strong());
        ui.separator();
        ui.label(format!("封面：{}", report.cover_status));
        ui.separator();
        ui.label(format!("LRC 歌词 {}/{}", report.lrc_count, report.total_tracks));
        ui.separator();
        if report.all_decode_ok {
            ui.label(RichText::new("● 解码校验 100% 通过").color(OK_GREEN));
        } else {
            ui.label(RichText::new("✖ 发现解码异常音轨").color(theme::DANGER));
        }
    });
    ui.add_space(8.0);
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        egui::Grid::new("qa_grid").striped(true).min_col_width(60.0).spacing([18.0, 6.0]).show(ui, |ui| {
            for h in ["曲序", "曲名", "位深 / 采样率", "LRC", "解码"] {
                ui.label(RichText::new(h).color(TEXT_WEAK));
            }
            ui.end_row();
            for t in &report.tracks {
                ui.label(&t.disc_track);
                ui.label(&t.title);
                ui.label(format!("{}bit / {:.1}kHz", t.bit_depth, t.sample_rate as f32 / 1000.0));
                ui.label(if t.has_lrc { "✔" } else { "—" });
                if t.decode_pass {
                    ui.label(RichText::new("PASS").color(OK_GREEN));
                } else {
                    ui.label(RichText::new("FAIL").color(theme::DANGER)).on_hover_text(&t.error_msg);
                }
                ui.end_row();
            }
        });
    });
}
