package com.tidalgui.player;


enum SearchSource {
    LOCAL("本地音乐"), TIDAL("TIDAL"), NETEASE("网易云");
    final String label;
    SearchSource(String label) { this.label = label; }

    String homeTitle() {
        switch (this) {
            case TIDAL: return "TIDAL";
            case NETEASE: return "网易云音乐";
            default: return "听音室";
        }
    }

    boolean includes(TidalClient.Track track) {
        switch (this) {
            case TIDAL: return track.needsTidal();
            case NETEASE: return track.isNetease();
            default: return track.isLocal();
        }
    }
}
