use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AuthData {
    pub token: Option<String>,
    pub refresh_token: Option<String>,
    pub expires_at: Option<i64>,
    pub user_id: Option<String>,
    pub country_code: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ArtistItem {
    pub id: u64,
    pub name: String,
    pub picture: Option<String>,
    pub popularity: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MediaMetadata {
    pub tags: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AlbumItem {
    pub id: u64,
    pub title: String,
    pub version: Option<String>,
    #[serde(rename = "releaseDate")]
    pub release_date: Option<String>,
    #[serde(rename = "numberOfTracks")]
    pub number_of_tracks: Option<u32>,
    #[serde(rename = "numberOfVolumes")]
    pub number_of_volumes: Option<u32>,
    pub duration: Option<u32>,
    #[serde(rename = "type")]
    pub album_type: Option<String>,
    pub explicit: Option<bool>,
    pub copyright: Option<String>,
    #[serde(rename = "audioQuality")]
    pub audio_quality: Option<String>,
    #[serde(rename = "mediaMetadata")]
    pub media_metadata: Option<MediaMetadata>,
    #[serde(rename = "vibrantColor")]
    pub vibrant_color: Option<String>,
    pub artist: Option<ArtistItem>,
    pub artists: Option<Vec<ArtistItem>>,
    pub cover: Option<String>,
}

impl AlbumItem {
    pub fn artist_names(&self) -> String {
        artist_names(self.artist.as_ref(), self.artists.as_ref())
    }
    pub fn main_artist(&self) -> Option<&ArtistItem> {
        self.artist.as_ref().or_else(|| self.artists.as_ref().and_then(|v| v.first()))
    }
    pub fn year(&self) -> &str {
        self.release_date.as_deref().map(|d| d.get(..4).unwrap_or(d)).unwrap_or("")
    }
    pub fn tags(&self) -> Vec<String> {
        quality_tags(self.media_metadata.as_ref(), self.audio_quality.as_ref())
    }
    pub fn full_title(&self) -> String {
        full_title(&self.title, self.version.as_deref())
    }
    pub fn url(&self) -> String {
        if let Some(id) = crate::netease::native_id(self.id) {
            return crate::netease::link("album", id);
        }
        format!("https://tidal.com/album/{}", self.id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TrackItem {
    pub id: u64,
    pub title: String,
    pub version: Option<String>,
    pub duration: Option<u32>,
    pub explicit: Option<bool>,
    #[serde(rename = "trackNumber")]
    pub track_number: Option<u32>,
    #[serde(rename = "volumeNumber")]
    pub volume_number: Option<u32>,
    #[serde(rename = "audioQuality")]
    pub audio_quality: Option<String>,
    #[serde(rename = "mediaMetadata")]
    pub media_metadata: Option<MediaMetadata>,
    pub artist: Option<ArtistItem>,
    pub artists: Option<Vec<ArtistItem>>,
    pub album: Option<AlbumItem>,
}

impl TrackItem {
    pub fn artist_names(&self) -> String {
        artist_names(self.artist.as_ref(), self.artists.as_ref())
    }
    pub fn main_artist(&self) -> Option<&ArtistItem> {
        self.artist.as_ref().or_else(|| self.artists.as_ref().and_then(|v| v.first()))
    }
    pub fn tags(&self) -> Vec<String> {
        quality_tags(self.media_metadata.as_ref(), self.audio_quality.as_ref())
    }
    pub fn full_title(&self) -> String {
        full_title(&self.title, self.version.as_deref())
    }
    pub fn cover(&self) -> Option<&str> {
        self.album.as_ref().and_then(|a| a.cover.as_deref())
    }
    pub fn url(&self) -> String {
        if let Some(id) = crate::netease::native_id(self.id) {
            return crate::netease::link("song", id);
        }
        format!("https://tidal.com/track/{}", self.id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Creator {
    pub id: Option<u64>,
    pub name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PlaylistItem {
    pub uuid: String,
    pub title: String,
    pub description: Option<String>,
    #[serde(rename = "numberOfTracks")]
    pub number_of_tracks: Option<u32>,
    pub duration: Option<u32>,
    #[serde(rename = "squareImage")]
    pub square_image: Option<String>,
    pub image: Option<String>,
    pub creator: Option<Creator>,
    #[serde(rename = "type")]
    pub playlist_type: Option<String>,
    #[serde(rename = "lastUpdated")]
    pub last_updated: Option<String>,
}

impl PlaylistItem {
    pub fn cover(&self) -> Option<&str> {
        self.square_image.as_deref().or(self.image.as_deref())
    }
    pub fn creator_name(&self) -> String {
        match self.playlist_type.as_deref() {
            Some("EDITORIAL") => "TIDAL".to_string(),
            _ => self
                .creator
                .as_ref()
                .and_then(|c| c.name.clone())
                .filter(|n| !n.is_empty())
                .unwrap_or_else(|| "我".to_string()),
        }
    }
    pub fn url(&self) -> String {
        if let Some(id) = crate::netease::playlist_id(&self.uuid) {
            return crate::netease::link("playlist", id);
        }
        format!("https://tidal.com/playlist/{}", self.uuid)
    }
}

fn full_title(title: &str, version: Option<&str>) -> String {
    match version {
        Some(v) if !v.trim().is_empty() => format!("{} ({})", title, v),
        _ => title.to_string(),
    }
}

fn artist_names(main: Option<&ArtistItem>, all: Option<&Vec<ArtistItem>>) -> String {
    if let Some(list) = all {
        if !list.is_empty() {
            return list.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ");
        }
    }
    main.map(|a| a.name.clone()).unwrap_or_else(|| "Various Artists".to_string())
}

fn quality_tags(meta: Option<&MediaMetadata>, quality: Option<&String>) -> Vec<String> {
    match meta.and_then(|m| m.tags.clone()) {
        Some(t) if !t.is_empty() => t,
        _ => quality.cloned().into_iter().collect(),
    }
}

/// TIDAL image ids are UUIDs; the CDN path uses `/` instead of `-`.
/// Valid square sizes: albums 80/160/320/640/1280, artists 160/320/750, playlists 160/320/480/640/750.
pub fn image_url(id: &str, size: u32) -> String {
    if id.starts_with("https://") || id.starts_with("http://") {
        return id.to_string();
    }
    format!("https://resources.tidal.com/images/{}/{}x{}.jpg", id.replace('-', "/"), size, size)
}

pub fn fmt_duration(sec: u32) -> String {
    if sec >= 3600 {
        format!("{}:{:02}:{:02}", sec / 3600, (sec % 3600) / 60, sec % 60)
    } else {
        format!("{}:{:02}", sec / 60, sec % 60)
    }
}

pub fn fmt_total(sec: u32) -> String {
    if sec >= 3600 {
        format!("{} 小时 {} 分钟", sec / 3600, (sec % 3600) / 60)
    } else {
        format!("{} 分钟", (sec + 59) / 60)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SearchResponse {
    pub artists: Option<ItemList<ArtistItem>>,
    pub albums: Option<ItemList<AlbumItem>>,
    pub tracks: Option<ItemList<TrackItem>>,
    pub playlists: Option<ItemList<PlaylistItem>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ItemList<T> {
    pub items: Vec<T>,
    #[serde(rename = "totalNumberOfItems")]
    pub total_number_of_items: Option<u32>,
}

/// Item of `/users/{id}/favorites/{kind}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Fav<T> {
    pub created: Option<String>,
    pub item: T,
}

#[derive(Debug, Clone)]
pub struct AlbumPage {
    pub album: AlbumItem,
    pub tracks: Vec<TrackItem>,
}

#[derive(Debug, Clone)]
pub struct ArtistPage {
    pub artist: ArtistItem,
    pub top_tracks: Vec<TrackItem>,
    pub albums: Vec<AlbumItem>,
    pub singles: Vec<AlbumItem>,
}

#[derive(Debug, Clone)]
pub struct PlaylistPage {
    pub playlist: PlaylistItem,
    pub tracks: Vec<TrackItem>,
}

/// Everything in "My Collection".
#[derive(Debug, Clone, Default)]
pub struct LibraryData {
    pub albums: Vec<Fav<AlbumItem>>,
    pub artists: Vec<Fav<ArtistItem>>,
    pub tracks: Vec<Fav<TrackItem>>,
    pub playlists: Vec<Fav<PlaylistItem>>,
    pub my_playlists: Vec<PlaylistItem>,
}

/// Quality requested for online playback (preview). Best first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum StreamQuality {
    #[default]
    HiRes,
    Lossless,
    High,
    Low,
}

impl StreamQuality {
    pub const ALL: [StreamQuality; 4] = [StreamQuality::HiRes, StreamQuality::Lossless, StreamQuality::High, StreamQuality::Low];

    /// `audioquality` parameter of the playbackinfo endpoint.
    pub fn api_name(self) -> &'static str {
        match self {
            StreamQuality::HiRes => "HI_RES_LOSSLESS",
            StreamQuality::Lossless => "LOSSLESS",
            StreamQuality::High => "HIGH",
            StreamQuality::Low => "LOW",
        }
    }
    /// TIDAL's own name for the level (see [`tidal_quality_name`]).
    pub fn short(self) -> &'static str {
        tidal_quality_name(self.api_name()).unwrap_or("Max")
    }
    pub fn describe(self) -> &'static str {
        match self {
            StreamQuality::HiRes => "HiRes FLAC 最高 24bit/192kHz",
            StreamQuality::Lossless => "FLAC 16bit/44.1kHz",
            StreamQuality::High => "AAC 最高 320kbps",
            StreamQuality::Low => "AAC 96kbps",
        }
    }
}

/// A resolved, playable audio stream: a direct URL or a local DASH manifest path.
#[derive(Debug, Clone)]
pub struct StreamInfo {
    pub url: String,
    /// What TIDAL actually delivered (may be lower than requested).
    pub quality: String,
    pub bit_depth: Option<u32>,
    pub sample_rate: Option<u32>,
    pub requested: StreamQuality,
    /// Explanation when the stream differs from what was requested.
    pub note: Option<String>,
}

/// TIDAL's names for its audio quality levels, as in the TIDAL apps: Max (HiRes FLAC), High
/// (16-bit FLAC) and Low (AAC up to 320 kbps). The API's 96 kbps AAC level, which the apps no
/// longer offer, is "Low 96k". Every screen and every platform uses these names.
pub fn tidal_quality_name(api: &str) -> Option<&'static str> {
    Some(match api {
        "HI_RES_LOSSLESS" | "HIRES_LOSSLESS" => "Max",
        "LOSSLESS" => "High",
        "HIGH" => "Low",
        "LOW" => "Low 96k",
        _ => return None,
    })
}

impl StreamInfo {
    pub fn label(&self) -> String {
        let fmt = |b: Option<u32>, r: Option<u32>| match (b, r) {
            (Some(b), Some(r)) => format!(" {}bit/{}kHz", b, (r as f32 / 1000.0).to_string()),
            _ => String::new(),
        };
        match self.quality.as_str() {
            "HI_RES_LOSSLESS" | "LOSSLESS" => format!("{}{}", tidal_quality_name(&self.quality).unwrap_or_default(),
                fmt(self.bit_depth, self.sample_rate)),
            "HIGH" => "Low 320kbps".into(),
            "LOW" => "Low 96kbps".into(),
            q => q.to_string(),
        }
    }
}

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct TrackQaResult {
    pub file_name: String,
    pub disc_track: String,
    pub title: String,
    pub bit_depth: u32,
    pub sample_rate: u32,
    pub channels: u32,
    pub has_lrc: bool,
    pub decode_pass: bool,
    pub error_msg: String,
}

#[derive(Debug, Clone, Default)]
#[allow(dead_code)]
pub struct AlbumQaReport {
    pub album_dir: String,
    pub total_tracks: usize,
    pub cover_status: String,
    pub lrc_count: usize,
    pub all_decode_ok: bool,
    pub tracks: Vec<TrackQaResult>,
}
