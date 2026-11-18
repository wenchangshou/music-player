//! "My Collection": the user's TIDAL favorites, with O(1) membership checks for the ♥ buttons
//! and optimistic local updates.

use std::collections::HashSet;

use crate::api::FavKind;
use crate::types::{AlbumItem, ArtistItem, Fav, LibraryData, PlaylistItem, TrackItem};

/// Something that can be (un)favorited. Carries the full item so an add can be shown
/// in the collection immediately, before the server confirms.
#[derive(Clone, Debug)]
pub enum FavTarget {
    Album(AlbumItem),
    Artist(ArtistItem),
    Track(TrackItem),
    Playlist(PlaylistItem),
}

impl FavTarget {
    pub fn is_netease(&self) -> bool {
        use crate::netease::{native_id, playlist_id};
        match self {
            Self::Album(a) => native_id(a.id).is_some(),
            Self::Artist(a) => native_id(a.id).is_some(),
            Self::Track(t) => native_id(t.id).is_some(),
            Self::Playlist(p) => playlist_id(&p.uuid).is_some(),
        }
    }

    pub fn kind(&self) -> FavKind {
        match self {
            FavTarget::Album(_) => FavKind::Album,
            FavTarget::Artist(_) => FavKind::Artist,
            FavTarget::Track(_) => FavKind::Track,
            FavTarget::Playlist(_) => FavKind::Playlist,
        }
    }
    pub fn id(&self) -> String {
        match self {
            FavTarget::Album(a) => a.id.to_string(),
            FavTarget::Artist(a) => a.id.to_string(),
            FavTarget::Track(t) => t.id.to_string(),
            FavTarget::Playlist(p) => p.uuid.clone(),
        }
    }
    pub fn name(&self) -> String {
        match self {
            FavTarget::Album(a) => a.title.clone(),
            FavTarget::Artist(a) => a.name.clone(),
            FavTarget::Track(t) => t.title.clone(),
            FavTarget::Playlist(p) => p.title.clone(),
        }
    }
}

#[derive(Clone, PartialEq, Debug, Default)]
pub enum LibState {
    #[default]
    Idle,
    Loading,
    Ready,
    Failed(String),
}

#[derive(Default)]
pub struct Library {
    pub state: LibState,
    pub data: LibraryData,
    albums: HashSet<u64>,
    artists: HashSet<u64>,
    tracks: HashSet<u64>,
    playlists: HashSet<String>,
}

fn now_iso() -> Option<String> {
    Some(chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3f+0000").to_string())
}

impl Library {
    pub fn set_data(&mut self, d: LibraryData) {
        self.albums = d.albums.iter().map(|f| f.item.id).collect();
        self.artists = d.artists.iter().map(|f| f.item.id).collect();
        self.tracks = d.tracks.iter().map(|f| f.item.id).collect();
        self.playlists = d.playlists.iter().map(|f| f.item.uuid.clone()).collect();
        self.data = d;
        self.state = LibState::Ready;
    }

    pub fn has_album(&self, id: u64) -> bool {
        self.albums.contains(&id)
    }
    pub fn has_artist(&self, id: u64) -> bool {
        self.artists.contains(&id)
    }
    pub fn has_track(&self, id: u64) -> bool {
        self.tracks.contains(&id)
    }
    pub fn has_playlist(&self, uuid: &str) -> bool {
        self.playlists.contains(uuid)
    }
    pub fn is_fav(&self, t: &FavTarget) -> bool {
        match t {
            FavTarget::Album(a) => self.has_album(a.id),
            FavTarget::Artist(a) => self.has_artist(a.id),
            FavTarget::Track(x) => self.has_track(x.id),
            FavTarget::Playlist(p) => self.has_playlist(&p.uuid),
        }
    }
    /// Playlists the user created (can't be favorited, they're already theirs).
    pub fn is_mine(&self, uuid: &str) -> bool {
        self.data.my_playlists.iter().any(|p| p.uuid == uuid)
    }

    /// Optimistically add/remove locally. Returns false if nothing changed.
    pub fn apply(&mut self, t: &FavTarget, add: bool) -> bool {
        if self.is_fav(t) == add {
            return false;
        }
        let d = &mut self.data;
        match t {
            FavTarget::Album(a) => {
                if add {
                    self.albums.insert(a.id);
                    d.albums.insert(0, Fav { created: now_iso(), item: a.clone() });
                } else {
                    self.albums.remove(&a.id);
                    d.albums.retain(|f| f.item.id != a.id);
                }
            }
            FavTarget::Artist(a) => {
                if add {
                    self.artists.insert(a.id);
                    d.artists.insert(0, Fav { created: now_iso(), item: a.clone() });
                } else {
                    self.artists.remove(&a.id);
                    d.artists.retain(|f| f.item.id != a.id);
                }
            }
            FavTarget::Track(x) => {
                if add {
                    self.tracks.insert(x.id);
                    d.tracks.insert(0, Fav { created: now_iso(), item: x.clone() });
                } else {
                    self.tracks.remove(&x.id);
                    d.tracks.retain(|f| f.item.id != x.id);
                }
            }
            FavTarget::Playlist(p) => {
                if add {
                    self.playlists.insert(p.uuid.clone());
                    d.playlists.insert(0, Fav { created: now_iso(), item: p.clone() });
                } else {
                    self.playlists.remove(&p.uuid);
                    d.playlists.retain(|f| f.item.uuid != p.uuid);
                }
            }
        }
        true
    }
}
