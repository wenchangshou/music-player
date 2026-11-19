//! "已下载" marks in track lists and album cards: what this computer downloaded, or, while it
//! controls another device that downloads for it, what that device has saved (its download
//! records and, for Android, its local music library matched by title, artist and length).
//! Keys and hashes are computed exactly as the Android app does (`DownloadTasks.nameKey`).

use std::collections::HashMap;

use serde_json::Value;
use unicode_normalization::UnicodeNormalization;

use crate::types::{AlbumItem, TrackItem};

#[derive(Default, Clone)]
pub struct Marks {
    /// "TIDAL:id" or "网易云:id" → quality saved ("" when unknown).
    tracks: HashMap<String, String>,
    /// "TIDAL:id" → songs of the album saved.
    albums: HashMap<String, u64>,
    /// Hashed "title|artist" key → lengths in seconds of local copies, e.g. "215,0".
    named: HashMap<String, String>,
    /// "album|artist" key → local songs of the album.
    named_albums: HashMap<String, u64>,
    /// "TIDAL:album id" → normalized titles of its songs saved by an album download here, whose
    /// records list songs by title only.
    album_songs: HashMap<String, std::collections::HashSet<String>>,
}

impl Marks {
    /// Marks as a "downloads"/"marks" reply carries them.
    pub fn from_json(data: &Value) -> Self {
        let mut marks = Self::default();
        let strings = |key: &str| -> Vec<(String, String)> {
            data[key].as_object().map(|map| map.iter()
                .map(|(key, value)| (key.clone(), value.as_str().map(str::to_owned).unwrap_or_else(|| value.to_string())))
                .collect()).unwrap_or_default()
        };
        let counts = |key: &str| -> HashMap<String, u64> {
            data[key].as_object().map(|map| map.iter().map(|(key, value)| (key.clone(), value.as_u64().unwrap_or(0))).collect())
                .unwrap_or_default()
        };
        marks.tracks = strings("tracks").into_iter().collect();
        marks.albums = counts("albums");
        marks.named_albums = counts("namedAlbums");
        for entry in data["namedHashes"].as_str().unwrap_or_default().split(';') {
            if let Some((hash, lengths)) = entry.split_once('=').filter(|(hash, _)| !hash.is_empty()) {
                marks.named.insert(hash.to_owned(), lengths.to_owned());
            }
        }
        // Devices from before hashes were sent list keys (with or without lengths).
        for (key, lengths) in strings("named") {
            marks.named.insert(hash(&key), lengths);
        }
        for key in data["named"].as_array().into_iter().flatten().filter_map(Value::as_str) {
            marks.named.insert(hash(key), String::new());
        }
        marks
    }

    /// "已下载 · Max", "已下载", "本地已有", or None.
    pub fn track(&self, track: &TrackItem) -> Option<String> {
        if self.tracks.is_empty() && self.named.is_empty() && self.album_songs.is_empty() {
            return None;
        }
        let key = match crate::netease::native_id(track.id) {
            Some(id) => format!("网易云:{id}"),
            None => format!("TIDAL:{}", track.id),
        };
        if let Some(quality) = self.tracks.get(&key) {
            return Some(if quality.is_empty() { "已下载".into() } else { format!("已下载 · {quality}") });
        }
        if let Some(songs) = track.album.as_ref().and_then(|album| self.album_songs.get(&format!("TIDAL:{}", album.id))) {
            if songs.contains(&normalize(&track.title)) || songs.contains(&normalize(&track.full_title())) {
                return Some("已下载".into());
            }
        }
        let length = u64::from(track.duration.unwrap_or(0));
        let artist = track.main_artist().map(|artist| artist.name.as_str()).unwrap_or_default();
        let found = name_keys(&track.title, track.version.as_deref(), artist).iter()
            .filter_map(|key| self.named.get(&hash(key)))
            .any(|lengths| any_same_length(lengths, length));
        found.then(|| "本地已有".into())
    }

    /// Songs saved by a finished album download on this computer.
    pub fn add_album_songs<'a>(&mut self, album: u64, titles: impl IntoIterator<Item = &'a str>) {
        self.album_songs.entry(format!("TIDAL:{album}")).or_default().extend(titles.into_iter().map(normalize));
    }

    /// Songs of the album saved, from download records or the local music library.
    pub fn album(&self, album: &AlbumItem) -> u64 {
        let recorded = if crate::netease::native_id(album.id).is_some() {
            0
        } else {
            self.albums.get(&format!("TIDAL:{}", album.id)).copied().unwrap_or(0)
        };
        let artist = album.main_artist().map(|artist| artist.name.as_str()).unwrap_or_default();
        let local = name_key(&album.title, artist).and_then(|key| self.named_albums.get(&key)).copied().unwrap_or(0);
        recorded.max(local)
    }
}

/// Lowercased NFKC without whitespace and punctuation, as Java's
/// `replaceAll("[\\s\\p{Punct}·・—–‘’“”「」『』《》（）【】]+", "")` leaves it.
pub fn normalize(value: &str) -> String {
    value.nfkc().collect::<String>().to_lowercase().chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r') && !c.is_ascii_punctuation()
            && !"·・—–‘’“”「」『』《》（）【】".contains(*c))
        .collect()
}

/// The first of several artists, split as the Android app splits them.
fn first_artist(artist: &str) -> &str {
    const SEPARATORS: [&str; 10] = [",", ";", "&", "/", "、", "，", " feat. ", " feat ", " ft. ", " ft "];
    let cut = SEPARATORS.iter().filter_map(|separator| artist.find(separator)).min().unwrap_or(artist.len());
    artist[..cut].trim_end_matches([' ', '\t', '\n', '\u{0B}', '\u{0C}', '\r'])
}

/// "title|artist", normalized; None when either is empty.
pub fn name_key(title: &str, artist: &str) -> Option<String> {
    let (name, by) = (normalize(title), normalize(first_artist(artist)));
    (!name.is_empty() && !by.is_empty()).then(|| format!("{name}|{by}"))
}

/// Keys a song may be stored under: without and with its version, e.g. "(Live)".
fn name_keys(title: &str, version: Option<&str>, artist: &str) -> Vec<String> {
    let mut keys: Vec<String> = name_key(title, artist).into_iter().collect();
    if let Some(version) = version.filter(|version| !version.is_empty() && *version != "null") {
        keys.extend(name_key(&format!("{title} ({version})"), artist));
    }
    keys
}

/// 48-bit FNV-1a of a key, in hex, as `DownloadTasks.hash`.
pub fn hash(key: &str) -> String {
    let mut value: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in key.bytes() {
        value ^= u64::from(byte);
        value = value.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{:x}", value & 0xffff_ffff_ffff)
}

/// Within 3 seconds, or either length unknown.
fn same_length(a: u64, b: u64) -> bool {
    a == 0 || b == 0 || a.abs_diff(b) <= 3
}

fn any_same_length(lengths: &str, length: u64) -> bool {
    lengths.is_empty() || lengths.split(',').any(|value| value.parse().map_or(true, |value| same_length(value, length)))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The Android app's DownloadMatchTest asserts the same values.
    #[test]
    fn keys_and_hashes_match_the_android_app() {
        assert_eq!(hash(&name_key("Yellow", "Coldplay").unwrap()), "e4981116c82f");
        assert_eq!(hash(&name_key("晴天", "周杰伦").unwrap()), "b2fd8c968fa1");
        assert_eq!(hash(&name_key("Ｙｅｌｌｏｗ (Live)", "Coldplay").unwrap()), "7c2f98c4fdfd");
        assert_eq!(name_key(" yellow!", "COLDPLAY, Guest"), name_key("Yellow", "Coldplay"));
        assert_eq!(name_key("晴天", "周杰伦 feat. 某人"), name_key("晴天", "周杰伦"));
        assert_eq!(name_key("...", "Coldplay"), None);
    }

    fn track(id: u64, title: &str, version: Option<&str>, seconds: u32) -> TrackItem {
        serde_json::from_value(serde_json::json!({"id": id, "title": title, "version": version, "duration": seconds,
            "artist": {"id": 1, "name": "Coldplay"}})).unwrap()
    }

    #[test]
    fn marks_from_another_device() {
        let marks = Marks::from_json(&serde_json::json!({
            "tracks": {"TIDAL:5": "Max", "TIDAL:6": ""},
            "albums": {"TIDAL:9": 4},
            "namedHashes": format!("{}=269,0;{}=300", hash("yellow|coldplay"), hash("shiver|coldplay")),
        }));
        assert_eq!(marks.track(&track(5, "Anything", None, 0)).as_deref(), Some("已下载 · Max"));
        assert_eq!(marks.track(&track(6, "Anything", None, 0)).as_deref(), Some("已下载"));
        assert_eq!(marks.track(&track(1, "Yellow", None, 270)).as_deref(), Some("本地已有"));
        assert_eq!(marks.track(&track(2, "Shiver", Some("Live"), 340)), None);
        assert_eq!(marks.track(&track(3, "Trouble", None, 270)), None);
    }
}
