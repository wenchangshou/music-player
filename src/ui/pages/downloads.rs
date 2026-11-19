use eframe::egui::{self, Align, Color32, Layout, RichText, Vec2};

use crate::app::{Action, App, UiState};
use crate::download::{fmt_bytes, DownloadJob, JobStatus, TrackStatus};
use crate::theme::{self, ACCENT, GOLD, OK_GREEN, TEXT_WEAK};
use crate::types::fmt_duration;
use crate::ui::widgets::*;

pub fn show(app: &App, st: &mut UiState, ui: &mut egui::Ui, acts: &mut Vec<Action>) {
    ui.add_space(8.0);
    ui.label(RichText::new("下载").size(30.0).strong());
    ui.add_space(8.0);
    if let Some(remote) = app.remote_target().filter(|remote| remote.remote_downloads()) {
        return remote_downloads(ui, remote, acts);
    }

    // ── New download form ──
    egui::Frame::new().fill(theme::CARD).corner_radius(10).inner_margin(14).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.horizontal(|ui| {
            let r = ui.add(
                egui::TextEdit::singleline(&mut st.dl_input)
                    .hint_text("TIDAL 链接、TIDAL 专辑 ID 或网易云单曲链接（空格分隔）")
                    .desired_width((ui.available_width() - 140.0).max(200.0))
                    .margin(egui::Margin::symmetric(10, 7)),
            );
            let enter = r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
            let can = !st.dl_input.trim().is_empty();
            let btn = egui::Button::new(RichText::new("➕ 加入队列").strong().color(Color32::BLACK))
                .fill(ACCENT)
                .min_size(Vec2::new(120.0, 32.0));
            if (ui.add_enabled(can, btn).clicked() || enter) && can {
                acts.push(Action::EnqueueInput);
            }
        });
        ui.horizontal(|ui| {
            ui.label(RichText::new("音质").color(TEXT_WEAK));
            for (q, name, desc) in QUALITIES {
                ui.selectable_value(&mut st.dl_quality, q.to_string(), name).on_hover_text(desc);
            }
            ui.separator();
            ui.label(RichText::new("Atmos").color(TEXT_WEAK));
            ui.selectable_value(&mut st.dl_atmos, "none".to_string(), "纯立体声");
            ui.selectable_value(&mut st.dl_atmos, "allow".to_string(), "允许 Atmos");
        });
        ui.horizontal(|ui| {
            ui.label(RichText::new("保存到").color(TEXT_WEAK));
            ui.add(egui::TextEdit::singleline(&mut st.dl_dest).desired_width(440.0));
            if ui.button("📁 浏览…").clicked() {
                if let Some(p) = rfd::FileDialog::new().set_directory(&st.dl_dest).pick_folder() {
                    st.dl_dest = p.to_string_lossy().to_string();
                }
            }
        });
    });

    // ── Queue ──
    let running = app.jobs.iter().filter(|j| j.status.is_active()).count();
    let queued = app.jobs.iter().filter(|j| j.status == JobStatus::Queued).count();
    let done = app.jobs.iter().filter(|j| j.status == JobStatus::Done).count();
    let bad = app.jobs.iter().filter(|j| matches!(j.status, JobStatus::Failed | JobStatus::Cancelled)).count();
    ui.add_space(14.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new("下载队列").size(20.0).strong());
        ui.label(RichText::new(format!("进行中 {} · 排队 {} · 完成 {} · 失败/取消 {}", running, queued, done, bad)).color(TEXT_WEAK));
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if ui.add_enabled(done + bad > 0, egui::Button::new("✖ 清除已结束")).clicked() {
                acts.push(Action::ClearFinishedJobs);
            }
        });
    });
    ui.add_space(6.0);
    if app.jobs.is_empty() {
        return empty_state(ui, "⬇", "队列为空：在专辑、歌曲或歌单上点 ⬇ 选择音质即可加入");
    }
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        for job in &app.jobs {
            job_card(ui, st, job, acts);
            ui.add_space(8.0);
        }
    });
}

fn job_card(ui: &mut egui::Ui, st: &mut UiState, job: &DownloadJob, acts: &mut Vec<Action>) {
    let (status_text, status_color) = match job.status {
        JobStatus::Queued => ("排队中", Color32::GRAY),
        JobStatus::Resolving => ("读取曲目信息…", ACCENT),
        JobStatus::Downloading => ("下载中", ACCENT),
        JobStatus::PostProcessing => ("山灵后处理中", GOLD),
        JobStatus::Done => ("已完成", OK_GREEN),
        JobStatus::Failed => ("失败", theme::DANGER),
        JobStatus::Cancelled => ("已取消", Color32::GRAY),
    };
    let stroke = if job.status.is_active() { ACCENT.gamma_multiply(0.5) } else { Color32::from_rgb(40, 40, 46) };
    let tracks_open = st.job_tracks_open.contains(&job.id);
    let log_open = st.job_log_open.contains(&job.id);

    egui::Frame::new()
        .fill(theme::CARD)
        .stroke(egui::Stroke::new(1.0, stroke))
        .corner_radius(10)
        .inner_margin(12)
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                artwork(ui, job.meta.cover.as_deref(), 72.0, 160, false, "💿");
                ui.add_space(8.0);
                ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                    if job.status.is_finished() {
                        if ui.button("🗑").on_hover_text("从列表移除").clicked() {
                            acts.push(Action::RemoveJob(job.id));
                        }
                        if job.status != JobStatus::Done && ui.button("↻ 重试").clicked() {
                            acts.push(Action::RetryJob(job.id));
                        }
                    } else if ui.button("⏹ 取消").clicked() {
                        acts.push(Action::CancelJob(job.id));
                    }
                    let dir = job.out_dir.clone().unwrap_or_else(|| job.dest.clone());
                    if ui.button("🗁").on_hover_text(format!("打开文件夹\n{}", dir)).clicked() {
                        acts.push(Action::OpenUrl(dir));
                    }
                    if ui.selectable_label(log_open, "日志").clicked() {
                        toggle(&mut st.job_log_open, job.id);
                    }
                    if job.total() > 0 && ui.selectable_label(tracks_open, format!("曲目 {}", job.total())).clicked() {
                        toggle(&mut st.job_tracks_open, job.id);
                    }

                    ui.with_layout(Layout::top_down(Align::Min), |ui| {
                        ui.spacing_mut().item_spacing.y = 4.0;
                        ui.horizontal(|ui| {
                            if !job.meta.kind.is_empty() {
                                badge(ui, &job.meta.kind, Color32::GRAY);
                            }
                            badge(ui, quality_label(&job.quality), GOLD);
                            ui.add(egui::Label::new(RichText::new(&job.meta.title).strong().size(15.0)).truncate());
                        });
                        if !job.meta.subtitle.is_empty() {
                            ui.label(RichText::new(&job.meta.subtitle).color(TEXT_WEAK));
                        }
                        let total = job.total();
                        let frac = job.fraction();
                        let text = match job.status {
                            JobStatus::Queued => "等待前面的任务完成".to_string(),
                            JobStatus::Resolving => "正在读取曲目信息…".to_string(),
                            JobStatus::PostProcessing => format!("{} 首已处理 · 正在生成封面 / 核对歌词 / 解码校验…", job.results),
                            _ if total > 0 => format!("已完成 {}/{} 首 · {:.0}%", job.results.min(total), total, frac.unwrap_or(0.0) * 100.0),
                            _ => format!("已完成 {} 首", job.results),
                        };
                        let fill = match job.status {
                            JobStatus::Done => OK_GREEN,
                            JobStatus::Failed => theme::DANGER,
                            JobStatus::Cancelled => Color32::GRAY,
                            JobStatus::PostProcessing => GOLD,
                            _ => ACCENT.gamma_multiply(0.8),
                        };
                        ui.add(
                            egui::ProgressBar::new(frac.unwrap_or(0.0))
                                .fill(fill)
                                .corner_radius(4)
                                .text(RichText::new(text).color(Color32::WHITE).size(12.5))
                                .animate(job.status.is_active() && frac.unwrap_or(0.0) < 1.0),
                        );
                        ui.horizontal_wrapped(|ui| {
                            ui.spacing_mut().item_spacing.x = 12.0;
                            ui.label(RichText::new(status_text).strong().color(status_color));
                            if job.status == JobStatus::Downloading && job.speed > 1.0 {
                                ui.label(RichText::new(format!("⚡ {}/s", fmt_bytes(job.speed))).monospace());
                            }
                            if job.bytes > 0 {
                                ui.label(RichText::new(format!("已写入 {}", fmt_bytes(job.bytes as f64))).color(TEXT_WEAK));
                            }
                            let parts: Vec<String> = [
                                (TrackStatus::Downloaded, "新下载"),
                                (TrackStatus::Exists, "已存在"),
                                (TrackStatus::Skipped, "跳过"),
                                (TrackStatus::Failed, "失败"),
                            ]
                            .iter()
                            .filter_map(|(s, n)| {
                                let c = job.count(*s);
                                (c > 0).then(|| format!("{} {}", n, c))
                            })
                            .collect();
                            if !parts.is_empty() {
                                ui.label(RichText::new(parts.join(" · ")).color(TEXT_WEAK));
                            }
                            if let Some(d) = job.elapsed.or_else(|| job.started.map(|s| s.elapsed())) {
                                ui.label(RichText::new(format!("用时 {}", fmt_duration(d.as_secs() as u32))).color(TEXT_WEAK));
                            }
                        });
                    });
                });
            });

            if tracks_open && job.total() > 0 {
                ui.add_space(6.0);
                ui.separator();
                egui::Grid::new(("job_tracks", job.id)).striped(true).num_columns(3).spacing([16.0, 4.0]).show(ui, |ui| {
                    for t in &job.meta.tracks {
                        ui.label(RichText::new(&t.label).monospace().color(TEXT_WEAK));
                        ui.label(&t.title);
                        let (txt, c) = match t.status {
                            TrackStatus::Pending if job.status.is_active() => ("… 等待", Color32::GRAY),
                            TrackStatus::Pending => ("—", Color32::GRAY),
                            TrackStatus::Downloaded => ("✔ 已下载", OK_GREEN),
                            TrackStatus::Exists => ("＝ 已存在", ACCENT),
                            TrackStatus::Skipped => ("↷ 跳过", GOLD),
                            TrackStatus::Failed => ("✖ 失败", theme::DANGER),
                        };
                        ui.label(RichText::new(txt).color(c));
                        ui.end_row();
                    }
                });
            }
            if log_open {
                ui.add_space(6.0);
                egui::Frame::new().fill(Color32::from_rgb(10, 10, 12)).corner_radius(6).inner_margin(8).show(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .id_salt(("job_log", job.id))
                        .max_height(200.0)
                        .auto_shrink([false, true])
                        .stick_to_bottom(true)
                        .show(ui, |ui| {
                            if job.logs.is_empty() {
                                ui.label(RichText::new("（暂无输出）").color(TEXT_WEAK));
                            }
                            for l in &job.logs {
                                ui.label(RichText::new(l).monospace().size(12.0));
                            }
                        });
                });
            }
        });
}

fn toggle(set: &mut std::collections::HashSet<u64>, id: u64) {
    if !set.remove(&id) {
        set.insert(id);
    }
}

/// The controlled device's downloads: it downloads with its own TIDAL login into its own folder.
fn remote_downloads(ui: &mut egui::Ui, remote: &crate::remote::Remote, acts: &mut Vec<Action>) {
    let name = remote.target_name();
    ui.label(RichText::new(format!("正在查看 {name} 上的下载，文件保存在该设备。在专辑或歌曲上点 ⬇ 即在该设备下载。"))
        .color(ACCENT));
    ui.add_space(8.0);
    let empty = Vec::new();
    let jobs = remote.downloads.as_ref().and_then(|list| list["jobs"].as_array()).unwrap_or(&empty);
    let text = |job: &serde_json::Value, key: &str| job[key].as_str().unwrap_or_default().to_owned();
    let finished = |job: &serde_json::Value| matches!(job["state"].as_str(), Some("complete" | "failed" | "cancelled"));
    ui.horizontal(|ui| {
        let active = jobs.iter().filter(|job| !finished(job)).count();
        let done = jobs.iter().filter(|job| job["state"] == "complete").count();
        ui.label(RichText::new("下载队列").size(20.0).strong());
        ui.label(RichText::new(format!("进行中 {active} · 完成 {done} · 共 {}", jobs.len())).color(TEXT_WEAK));
        if let Some(parallel) = remote.downloads.as_ref().and_then(|list| list["parallel"].as_u64()).filter(|n| *n > 1) {
            ui.label(RichText::new(format!("同时下载 {parallel} 首")).color(TEXT_WEAK));
        }
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if ui.add_enabled(jobs.iter().any(finished), egui::Button::new("✖ 清除已结束")).clicked() {
                acts.push(Action::RemoteDownload("clear", String::new()));
            }
        });
    });
    ui.add_space(6.0);
    if let Some(error) = &remote.downloads_error {
        ui.label(RichText::new(format!("读取下载列表失败：{error}")).color(theme::DANGER));
    }
    if remote.downloads.is_none() {
        return loading(ui, "正在读取该设备的下载…");
    }
    if jobs.is_empty() {
        return empty_state(ui, "⬇", "该设备暂无下载任务");
    }
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        for job in jobs {
            let id = text(job, "id");
            let state = job["state"].as_str().unwrap_or_default();
            let (status, color) = match state {
                "queued" => ("排队中", Color32::GRAY),
                "preparing" => ("获取下载地址…", ACCENT),
                "waiting" => ("等待系统下载", ACCENT),
                "transferring" => ("下载中", ACCENT),
                "complete" => ("已完成", OK_GREEN),
                "failed" => ("失败", theme::DANGER),
                _ => ("已取消", Color32::GRAY),
            };
            egui::Frame::new().fill(theme::CARD).corner_radius(10).inner_margin(10).show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if finished(job) {
                            if ui.button("🗑").on_hover_text("从列表移除").clicked() {
                                acts.push(Action::RemoteDownload("remove", id.clone()));
                            }
                            if state != "complete" && ui.button("↻ 重试").clicked() {
                                acts.push(Action::RemoteDownload("retry", id.clone()));
                            }
                        } else if ui.button("⏹ 取消").clicked() {
                            acts.push(Action::RemoteDownload("cancel", id.clone()));
                        }
                        ui.with_layout(Layout::top_down(Align::Min), |ui| {
                            ui.spacing_mut().item_spacing.y = 3.0;
                            ui.horizontal(|ui| {
                                badge(ui, &text(job, "source"), Color32::GRAY);
                                ui.add(egui::Label::new(RichText::new(text(job, "title")).strong()).truncate());
                            });
                            let artist = text(job, "artist");
                            let album = text(job, "album");
                            let line = [artist, album].into_iter().filter(|part| !part.is_empty()).collect::<Vec<_>>().join(" · ");
                            if !line.is_empty() {
                                ui.add(egui::Label::new(RichText::new(line).color(TEXT_WEAK)).truncate());
                            }
                            ui.horizontal_wrapped(|ui| {
                                ui.spacing_mut().item_spacing.x = 12.0;
                                ui.label(RichText::new(status).strong().color(color));
                                let (bytes, total) = (job["bytes"].as_f64().unwrap_or(0.0), job["total"].as_f64().unwrap_or(-1.0));
                                if state == "transferring" && total > 0.0 {
                                    ui.label(format!("{} / {} · {:.0}%", fmt_bytes(bytes), fmt_bytes(total), bytes * 100.0 / total));
                                } else if bytes > 0.0 {
                                    ui.label(RichText::new(format!("已写入 {}", fmt_bytes(bytes))).color(TEXT_WEAK));
                                }
                                let detail = text(job, "resolution");
                                if !detail.is_empty() {
                                    ui.label(RichText::new(detail).color(TEXT_WEAK));
                                }
                            });
                            let error = text(job, "error");
                            if state == "failed" && !error.is_empty() {
                                ui.label(RichText::new(error).color(theme::DANGER).size(12.0));
                            }
                        });
                    });
                });
            });
            ui.add_space(6.0);
        }
    });
}
