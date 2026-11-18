//! Play queue (order, shuffle, repeat) on top of the mpv `Player`.

use crate::player::Player;
use crate::route::Route;
use crate::types::{StreamInfo, StreamQuality, TrackItem};

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Repeat {
    Off,
    All,
    One,
}

/// Where the current queue came from ("专辑 · 安和桥北"), so the UI can link back to it.
#[derive(Clone, Debug)]
pub struct PlayContext {
    pub label: String,
    pub route: Route,
}

pub struct Playback {
    pub player: Player,
    pub queue: Vec<TrackItem>,
    pub index: usize,
    pub shuffle: bool,
    pub repeat: Repeat,
    /// Sequence number of the in-flight stream request (0 = none).
    pub req: u64,
    pub stream: Option<StreamInfo>,
    pub context: Option<PlayContext>,
    /// Preview quality to request.
    pub quality: StreamQuality,
    /// 网易云 level for NetEase tracks, one of `netease::PLAY_LEVELS`.
    pub netease_level: &'static str,
    /// Where to start the pending stream (used when switching quality mid-track).
    pub pending_start: f64,
}

/// Tiny xorshift RNG; good enough for shuffling a play queue without another dependency.
fn shuffle<T>(v: &mut [T]) {
    let mut x = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E3779B97F4A7C15)
        | 1;
    for i in (1..v.len()).rev() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        v.swap(i, (x % (i as u64 + 1)) as usize);
    }
}

impl Playback {
    pub fn new() -> Self {
        Self {
            player: Player::new(),
            queue: Vec::new(),
            index: 0,
            shuffle: false,
            repeat: Repeat::Off,
            req: 0,
            stream: None,
            context: None,
            quality: StreamQuality::HiRes,
            netease_level: "lossless",
            pending_start: 0.0,
        }
    }

    pub fn current(&self) -> Option<&TrackItem> {
        self.queue.get(self.index)
    }

    pub fn is_loading(&self) -> bool {
        self.req != 0
    }

    /// Replace the queue. With `shuffle`, the chosen track plays first and the rest is shuffled.
    pub fn set_queue(&mut self, mut tracks: Vec<TrackItem>, start: usize, shuffle_all: bool) {
        let start = start.min(tracks.len().saturating_sub(1));
        if shuffle_all || self.shuffle {
            if !shuffle_all && !tracks.is_empty() {
                tracks.swap(0, start);
                shuffle(&mut tracks[1..]);
            } else {
                shuffle(&mut tracks);
            }
            self.index = 0;
            self.shuffle = true;
        } else {
            self.index = start;
        }
        self.queue = tracks;
    }

    /// Next index; `auto` = the track ended by itself (honours repeat-one).
    pub fn next_index(&self, auto: bool) -> Option<usize> {
        if self.queue.is_empty() {
            return None;
        }
        if auto && self.repeat == Repeat::One {
            return Some(self.index);
        }
        if self.index + 1 < self.queue.len() {
            Some(self.index + 1)
        } else if self.repeat == Repeat::All {
            Some(0)
        } else {
            None
        }
    }

    pub fn prev_index(&self) -> Option<usize> {
        if self.index > 0 {
            Some(self.index - 1)
        } else if self.repeat == Repeat::All && !self.queue.is_empty() {
            Some(self.queue.len() - 1)
        } else {
            None
        }
    }

    pub fn toggle_shuffle(&mut self) {
        self.shuffle = !self.shuffle;
        if self.shuffle && self.index + 1 < self.queue.len() {
            shuffle(&mut self.queue[self.index + 1..]);
        }
    }

    pub fn cycle_repeat(&mut self) {
        self.repeat = match self.repeat {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        };
    }

    pub fn insert_next(&mut self, tracks: Vec<TrackItem>) {
        if self.queue.is_empty() {
            self.queue = tracks;
            self.index = 0;
        } else {
            let at = self.index + 1;
            self.queue.splice(at..at, tracks);
        }
    }

    pub fn append(&mut self, tracks: Vec<TrackItem>) {
        self.queue.extend(tracks);
    }

    /// Remove a queued item (not the one playing).
    pub fn move_track(&mut self, from: usize, to: usize) {
        let item = self.queue.remove(from);
        self.queue.insert(to, item);
        if self.index == from {
            self.index = to;
        } else if from < self.index && to >= self.index {
            self.index -= 1;
        } else if from > self.index && to <= self.index {
            self.index += 1;
        }
    }

    pub fn remove(&mut self, i: usize) {
        if i >= self.queue.len() || i == self.index {
            return;
        }
        self.queue.remove(i);
        if i < self.index {
            self.index -= 1;
        }
    }

    /// Drop everything after the current track.
    pub fn clear_upcoming(&mut self) {
        self.queue.truncate(self.index + 1);
    }

    pub fn stop(&mut self) {
        self.player.stop();
        self.req = 0;
        self.queue.clear();
        self.index = 0;
        self.stream = None;
        self.context = None;
    }
}
