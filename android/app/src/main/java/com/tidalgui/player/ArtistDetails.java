package com.tidalgui.player;

import java.util.List;

final class ArtistDetails {
    final TidalClient.Collection artist;
    final List<TidalClient.Track> top;
    final List<TidalClient.Collection> albums;
    final List<TidalClient.Collection> singles;
    final List<TidalClient.Collection> similar;
    final String biography;

    ArtistDetails(TidalClient api, TidalClient.Collection selected) throws Exception {
        String id = selected.id;
        artist = optional(() -> api.artist(id), selected);
        albums = optional(() -> api.artistReleases(id, false), java.util.Collections.emptyList());
        singles = optional(() -> api.artistReleases(id, true), java.util.Collections.emptyList());
        similar = optional(() -> api.similarArtists(id), java.util.Collections.emptyList());
        biography = optional(() -> api.artistBiography(id), "");
        if (albums.isEmpty() && singles.isEmpty() && similar.isEmpty() && biography.isEmpty()) {
            top = api.collectionTracks(selected, 0);
        } else {
            top = optional(() -> api.collectionTracks(selected, 0), java.util.Collections.emptyList());
        }
    }

    private static <T> T optional(java.util.concurrent.Callable<T> request, T fallback) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            return request.call();
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception unavailable) {
            return fallback;
        }
    }
}
