//! Navigation targets. Every page in the app is reachable through a `Route`, which gives us
//! browser-style back/forward history and per-page caching.

use crate::netease::SearchSource;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LibTab {
    Albums,
    Artists,
    Tracks,
    Playlists,
}

#[derive(Clone, PartialEq, Debug)]
pub enum Route {
    Home,
    Search(SearchSource, String),
    Album(u64),
    Artist(u64),
    Playlist(String),
    Library(LibTab),
    Downloads,
    Qa,
    Settings,
}

/// Browser-like history.
#[derive(Default)]
pub struct History {
    pub back: Vec<Route>,
    pub fwd: Vec<Route>,
}

impl History {
    pub fn push(&mut self, from: Route) {
        self.back.push(from);
        if self.back.len() > 100 {
            self.back.remove(0);
        }
        self.fwd.clear();
    }
}
