use reqwest::blocking::{Client, Response};
use reqwest::Url;
use serde::de::DeserializeOwned;
use serde::Deserialize;

use crate::types::*;

// Keep existing numeric queue keys distinct from TIDAL IDs and exact in JavaScript relay clients.
const ID_BASE: u64 = 1 << 52;
const PLAYLIST_PREFIX: &str = "netease:";

pub fn native_id(id: u64) -> Option<u64> {
    (ID_BASE..2 * ID_BASE).contains(&id).then(|| id - ID_BASE)
}

fn catalogue_id(id: u64) -> u64 {
    ID_BASE + id
}

pub fn playlist_id(id: &str) -> Option<&str> {
    id.strip_prefix(PLAYLIST_PREFIX)
}

pub fn link(kind: &str, id: impl std::fmt::Display) -> String {
    format!("https://music.163.com/{kind}?id={id}")
}

pub fn song_link_id(input: &str) -> Option<u64> {
    let input = input.replace("/#/", "/");
    let url = Url::parse(&input).ok()?;
    if url.host_str() != Some("music.163.com") || !matches!(url.path(), "/song" | "/m/song") {
        return None;
    }
    let id = url
        .query_pairs()
        .find(|(key, _)| key == "id")?
        .1
        .parse()
        .ok()?;
    (1..ID_BASE).contains(&id).then_some(id)
}

pub fn is_link(input: &str) -> bool {
    Url::parse(input)
        .ok()
        .and_then(|url| url.host_str().map(str::to_owned))
        .as_deref()
        == Some("music.163.com")
}

#[derive(Deserialize)]
struct Artist {
    id: u64,
    name: String,
    #[serde(rename = "picUrl")]
    picture: Option<String>,
    #[serde(rename = "img1v1Url")]
    avatar: Option<String>,
}

impl From<Artist> for ArtistItem {
    fn from(a: Artist) -> Self {
        Self {
            id: catalogue_id(a.id),
            name: a.name,
            picture: a.picture.or(a.avatar),
            ..Default::default()
        }
    }
}

#[derive(Deserialize)]
struct Album {
    id: u64,
    name: String,
    #[serde(rename = "picUrl")]
    cover: Option<String>,
    size: Option<u32>,
    artist: Option<Artist>,
    artists: Option<Vec<Artist>>,
}

impl From<Album> for AlbumItem {
    fn from(a: Album) -> Self {
        Self {
            id: catalogue_id(a.id),
            title: a.name,
            cover: a.cover,
            number_of_tracks: a.size,
            artist: a.artist.map(Into::into),
            artists: a.artists.map(convert),
            ..Default::default()
        }
    }
}

#[derive(Deserialize)]
struct Song {
    id: u64,
    name: String,
    dt: Option<u64>,
    no: Option<u32>,
    ar: Option<Vec<Artist>>,
    al: Option<Album>,
}

impl From<Song> for TrackItem {
    fn from(s: Song) -> Self {
        Self {
            id: catalogue_id(s.id),
            title: s.name,
            duration: s.dt.map(|ms| (ms / 1000) as u32),
            track_number: s.no,
            artists: s.ar.map(convert),
            album: s.al.map(Into::into),
            ..Default::default()
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Playlist {
    id: u64,
    name: String,
    cover_img_url: Option<String>,
    track_count: Option<u32>,
    description: Option<String>,
    creator: Option<PlaylistCreator>,
}

#[derive(Deserialize)]
struct PlaylistCreator {
    nickname: String,
}

impl From<Playlist> for PlaylistItem {
    fn from(p: Playlist) -> Self {
        Self {
            uuid: format!("{PLAYLIST_PREFIX}{}", p.id),
            title: p.name,
            description: p.description,
            number_of_tracks: p.track_count,
            image: p.cover_img_url,
            creator: p.creator.map(|c| Creator {
                id: None,
                name: Some(c.nickname),
            }),
            ..Default::default()
        }
    }
}

fn convert<A, B: From<A>>(items: Vec<A>) -> Vec<B> {
    items.into_iter().map(Into::into).collect()
}

fn list<A, B: From<A>>(items: Vec<A>) -> ItemList<B> {
    ItemList {
        total_number_of_items: None,
        items: convert(items),
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Hash)]
pub enum SearchSource {
    #[default]
    Tidal,
    Netease,
}

impl SearchSource {
    pub fn label(self) -> &'static str {
        match self {
            Self::Tidal => "TIDAL",
            Self::Netease => "网易云",
        }
    }
}

#[derive(Deserialize)]
struct Search<T> {
    result: Option<T>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct Results {
    songs: Vec<Song>,
    albums: Vec<Album>,
    artists: Vec<Artist>,
    playlists: Vec<Playlist>,
}

#[derive(Deserialize)]
struct Songs {
    songs: Vec<Song>,
}

#[derive(Deserialize)]
struct AlbumDetail {
    album: Album,
    songs: Vec<Song>,
}

#[derive(Deserialize)]
struct ArtistDetail {
    artist: Artist,
    #[serde(rename = "hotSongs")]
    hot_songs: Vec<Song>,
}

#[derive(Deserialize)]
struct ArtistAlbums {
    #[serde(rename = "hotAlbums")]
    hot_albums: Vec<Album>,
}

#[derive(Deserialize)]
struct PlaylistDetail {
    playlist: Playlist,
}

#[derive(Deserialize)]
struct StreamResponse {
    data: Vec<Audio>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Audio {
    id: u64,
    url: Option<String>,
    level: Option<String>,
    free_trial_info: Option<serde_json::Value>,
    #[serde(rename = "type")]
    format: Option<String>,
}

pub fn level(quality: StreamQuality) -> &'static str {
    match quality {
        StreamQuality::HiRes => "hires",
        StreamQuality::Lossless => "lossless",
        StreamQuality::High => "exhigh",
        StreamQuality::Low => "standard",
    }
}

pub struct Netease<'a>(pub &'a Client);

impl Netease<'_> {
    fn get<T: DeserializeOwned>(&self, path: &str, params: &[(&str, &str)]) -> Result<T, String> {
        let base = std::env::var("NETEASE_API_URL")
            .map_err(|_| "请先设置 NETEASE_API_URL，然后重启应用".to_string())?;
        let mut url = Url::parse(&base).map_err(|_| "NETEASE_API_URL 格式无效".to_string())?;
        if !matches!(url.scheme(), "http" | "https") || url.host().is_none() {
            return Err("NETEASE_API_URL 需要 HTTP 或 HTTPS 地址".into());
        }
        url.path_segments_mut()
            .map_err(|_| "NETEASE_API_URL 路径无效")?
            .pop_if_empty()
            .extend(path.split('/'));
        let response = self
            .0
            .get(url)
            .query(params)
            .send()
            .map_err(|e| format!("网易云接口连接失败：{}", e.without_url()))?;
        let body: serde_json::Value = response
            .error_for_status()
            .map_err(|e| format!("网易云接口请求失败：{}", e.without_url()))?
            .json()
            .map_err(|e| format!("网易云响应解析失败：{}", e.without_url()))?;
        if let Some(code) = body["code"].as_i64().filter(|&code| code != 200) {
            return Err(format!("网易云接口返回错误（{code}）"));
        }
        serde_json::from_value(body).map_err(|e| format!("网易云响应解析失败：{e}"))
    }

    pub fn search(&self, query: &str, limit: u32) -> Result<SearchResponse, String> {
        let fetch = |kind| -> Result<Results, String> {
            let result: Search<Results> = self.get(
                "cloudsearch",
                &[
                    ("keywords", query),
                    ("type", kind),
                    ("limit", &limit.to_string()),
                ],
            )?;
            Ok(result.result.unwrap_or_default())
        };
        let (songs, albums, artists, playlists) = std::thread::scope(|scope| {
            let songs = scope.spawn(|| fetch("1"));
            let albums = scope.spawn(|| fetch("10"));
            let artists = scope.spawn(|| fetch("100"));
            let playlists = fetch("1000");
            (songs.join(), albums.join(), artists.join(), playlists)
        });
        let worker_error = |_| "网易云搜索任务异常退出".to_string();
        Ok(SearchResponse {
            tracks: Some(list(songs.map_err(worker_error)??.songs)),
            albums: Some(list(albums.map_err(worker_error)??.albums)),
            artists: Some(list(artists.map_err(worker_error)??.artists)),
            playlists: Some(list(playlists?.playlists)),
        })
    }

    pub fn track(&self, id: u64) -> Result<TrackItem, String> {
        let detail: Songs = self.get("song/detail", &[("ids", &id.to_string())])?;
        detail
            .songs
            .into_iter()
            .find(|song| song.id == id)
            .map(Into::into)
            .ok_or_else(|| "找不到网易云歌曲".into())
    }

    pub fn album(&self, id: u64) -> Result<AlbumPage, String> {
        let detail: AlbumDetail = self.get("album", &[("id", &id.to_string())])?;
        let album: AlbumItem = detail.album.into();
        let mut tracks: Vec<TrackItem> = convert(detail.songs);
        for track in &mut tracks {
            track.album = Some(album.clone());
        }
        Ok(AlbumPage { album, tracks })
    }

    pub fn artist(&self, id: u64) -> Result<ArtistPage, String> {
        let id = id.to_string();
        let detail: ArtistDetail = self.get("artists", &[("id", &id)])?;
        let albums: ArtistAlbums = self.get("artist/album", &[("id", &id), ("limit", "50")])?;
        Ok(ArtistPage {
            artist: detail.artist.into(),
            top_tracks: convert(detail.hot_songs),
            albums: convert(albums.hot_albums),
            singles: Vec::new(),
        })
    }

    pub fn playlist(&self, id: &str) -> Result<PlaylistPage, String> {
        let detail: PlaylistDetail = self.get("playlist/detail", &[("id", id)])?;
        let mut tracks = Vec::new();
        while tracks.len() < detail.playlist.track_count.unwrap_or(2000) as usize {
            let page: Songs = self.get(
                "playlist/track/all",
                &[
                    ("id", id),
                    ("limit", "200"),
                    ("offset", &tracks.len().to_string()),
                ],
            )?;
            let count = page.songs.len();
            tracks.extend(convert::<_, TrackItem>(page.songs));
            if count < 200 {
                break;
            }
        }
        Ok(PlaylistPage {
            playlist: detail.playlist.into(),
            tracks,
        })
    }

    pub fn stream(&self, id: u64, quality: StreamQuality) -> Result<(StreamInfo, String), String> {
        let level = level(quality);
        let body: StreamResponse =
            self.get("song/url/v1", &[("id", &id.to_string()), ("level", level)])?;
        let Some(audio) = body.data.into_iter().find(|audio| audio.id == id) else {
            return Err("网易云未返回该歌曲的音频".into());
        };
        if audio.free_trial_info.is_some() {
            return Err("网易云仅提供试听片段，完整音频需要相应权限".into());
        }
        let url = audio
            .url
            .and_then(|url| Url::parse(&url).ok())
            .filter(|url| matches!(url.scheme(), "http" | "https"))
            .ok_or("网易云歌曲不可用，可能需要登录、会员或地区授权")?;
        let actual = audio.level.as_deref().unwrap_or("未知");
        let format = audio.format.unwrap_or_default().to_ascii_lowercase();
        let info = StreamInfo {
            url: url.to_string(),
            quality: format!("网易云 · {actual}"),
            bit_depth: None,
            sample_rate: None,
            requested: quality,
            note: (actual != level).then(|| format!("网易云提供的实际音质为 {actual}")),
        };
        Ok((info, format))
    }

    pub fn audio(&self, url: &str) -> Result<Response, String> {
        self.0
            .get(url)
            .timeout(std::time::Duration::from_secs(300))
            .send()
            .and_then(Response::error_for_status)
            .map_err(|e| format!("网易云音频下载失败：{}", e.without_url()))
    }
}
