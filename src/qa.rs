use std::path::{Path, PathBuf};
use std::process::Command;
use crate::types::{AlbumQaReport, TrackQaResult};
use walkdir::WalkDir;

pub fn run_shanling_qa(album_dir: &Path) -> Result<AlbumQaReport, String> {
    if !album_dir.is_dir() {
        return Err(format!("{} is not a valid directory", album_dir.display()));
    }

    let mut flac_files: Vec<PathBuf> = WalkDir::new(album_dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_file() && e.path().extension().map_or(false, |ext| ext == "flac"))
        .map(|e| e.into_path())
        .collect();

    flac_files.sort();

    if flac_files.is_empty() {
        return Err(format!("No FLAC files found in {}", album_dir.display()));
    }

    // 1. Process 1000x1000 JPEG Cover
    let cover_target = album_dir.join("cover.jpg");
    let mut cover_status = "Not Found".to_string();

    // Check if cover image exists or try extracting from first FLAC
    let mut source_cover = None;
    for entry in std::fs::read_dir(album_dir).map_err(|e| e.to_string())? {
        if let Ok(entry) = entry {
            let p = entry.path();
            if let Some(ext) = p.extension() {
                let ext_str = ext.to_string_lossy().to_lowercase();
                if ext_str == "jpg" || ext_str == "jpeg" || ext_str == "png" {
                    source_cover = Some(p);
                    break;
                }
            }
        }
    }

    // If no image on disk, extract embedded art from first FLAC using ffmpeg
    if source_cover.is_none() {
        let temp_extracted = album_dir.join(".temp_cover.jpg");
        let extract_cmd = Command::new("ffmpeg")
            .args(["-v", "error", "-y", "-i", &flac_files[0].to_string_lossy(), "-an", "-vcodec", "copy", &temp_extracted.to_string_lossy()])
            .output();
        if let Ok(out) = extract_cmd {
            if out.status.success() && temp_extracted.exists() {
                source_cover = Some(temp_extracted);
            }
        }
    }

    if let Some(src) = source_cover {
        if let Ok(img) = image::open(&src) {
            let resized = img.resize_exact(1000, 1000, image::imageops::FilterType::Lanczos3);
            if resized.save_with_format(&cover_target, image::ImageFormat::Jpeg).is_ok() {
                cover_status = "1000x1000 JPEG OK".to_string();

                // Embed into all flac files using python mutagen in venv
                let py_embed = r#"
import sys
from pathlib import Path
from mutagen.flac import FLAC, Picture
cover_bytes = Path(sys.argv[1]).read_bytes()
for flac in sys.argv[2:]:
    audio = FLAC(flac)
    audio.clear_pictures()
    p = Picture()
    p.type = 3
    p.mime = "image/jpeg"
    p.desc = "Front Cover"
    p.width = 1000
    p.height = 1000
    p.depth = 24
    p.data = cover_bytes
    audio.add_picture(p)
    audio.save()
"#;
                let mut cmd = Command::new(crate::api::python_bin());
                cmd.arg("-c").arg(py_embed).arg(&cover_target);
                for f in &flac_files {
                    cmd.arg(f);
                }
                let _ = cmd.output();
            }
        }
    }

    // 2. Track Inspection and FFmpeg Decode Verification
    let mut tracks_result = Vec::new();
    let mut all_decode_ok = true;
    let mut lrc_count = 0;

    for (idx, flac) in flac_files.iter().enumerate() {
        let file_name = flac.file_name().unwrap_or_default().to_string_lossy().to_string();
        let lrc_path = flac.with_extension("lrc");
        let has_lrc = lrc_path.exists();
        if has_lrc {
            lrc_count += 1;
        }

        // FFprobe audio stream details
        let mut sample_rate = 44100;
        let mut bit_depth = 16;
        let mut channels = 2;
        let mut title = file_name.clone();
        let mut disc_track = format!("{:02}", idx + 1);

        let probe = Command::new("ffprobe")
            .args([
                "-v", "error",
                "-show_entries", "stream=sample_rate,bits_per_raw_sample,channels:format_tags=title,track,disc",
                "-of", "default=noprint_wrappers=1:nokey=0",
                &flac.to_string_lossy()
            ])
            .output();

        if let Ok(out) = probe {
            let out_str = String::from_utf8_lossy(&out.stdout);
            for line in out_str.lines() {
                if let Some((k, v)) = line.split_once('=') {
                    match k.trim() {
                        "sample_rate" => sample_rate = v.trim().parse().unwrap_or(sample_rate),
                        "bits_per_raw_sample" => bit_depth = v.trim().parse().unwrap_or(bit_depth),
                        "channels" => channels = v.trim().parse().unwrap_or(channels),
                        "TAG:title" => title = v.trim().to_string(),
                        "TAG:track" => disc_track = v.trim().to_string(),
                        _ => {}
                    }
                }
            }
        }

        // FFmpeg full decode verification
        let decode = Command::new("ffmpeg")
            .args(["-v", "error", "-i", &flac.to_string_lossy(), "-f", "null", "-"])
            .output();

        let (decode_pass, error_msg) = match decode {
            Ok(out) => {
                let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
                if out.status.success() && err.is_empty() {
                    (true, String::new())
                } else {
                    all_decode_ok = false;
                    (false, err)
                }
            }
            Err(e) => {
                all_decode_ok = false;
                (false, e.to_string())
            }
        };

        tracks_result.push(TrackQaResult {
            file_name,
            disc_track,
            title,
            bit_depth,
            sample_rate,
            channels,
            has_lrc,
            decode_pass,
            error_msg,
        });
    }

    Ok(AlbumQaReport {
        album_dir: album_dir.to_string_lossy().to_string(),
        total_tracks: flac_files.len(),
        cover_status,
        lrc_count,
        all_decode_ok,
        tracks: tracks_result,
    })
}
