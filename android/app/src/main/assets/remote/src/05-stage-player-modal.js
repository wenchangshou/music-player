  // --- Stage Mode Switcher (Cover vs Synced Lyrics) ---
  let isLyricsViewStage = false;
  function switchStageMode(showLyrics) {
    isLyricsViewStage = showLyrics;
    $("stageSwitchArtwork").classList.toggle("active", !showLyrics);
    $("stageSwitchLyrics").classList.toggle("active", showLyrics);
    $("stageSwitchArtwork").setAttribute("aria-selected", String(!showLyrics));
    $("stageSwitchLyrics").setAttribute("aria-selected", String(showLyrics));
    $("artworkBox").hidden = showLyrics;
    $("stageLyricsWrap").hidden = !showLyrics;
    $("btnOpenLyrics").classList.toggle("active", showLyrics);

    // 桌面端宽屏：切换歌词模式时，右侧同时开启流光动态歌词视图
    if (showLyrics && window.innerWidth > 860) {
      switchSideTab("lyrics");
    }

    if (showLyrics && lastState) {
      const track = lastState.queue?.[lastState.index];
      if (track) fetchAndRenderLyrics(track);
      let pos = (lastState.position || 0) / 1000;
      activeLyricIndex = -1;
      syncLyricsPosition(pos);
    }
    if (activeView === "player") syncRouteToHash();
  }

  $("stageSwitchArtwork").addEventListener("click", () => switchStageMode(false));
  $("stageSwitchLyrics").addEventListener("click", () => switchStageMode(true));
  $("artworkBox").addEventListener("click", () => switchStageMode(true));
  $("btnOpenLyrics").addEventListener("click", () => switchStageMode(!isLyricsViewStage));
  const btnQueueMobile = $("btnStageQueueMobile");
  if (btnQueueMobile) {
    btnQueueMobile.addEventListener("click", () => {
      const grid = document.querySelector(".player-grid");
      if (grid) grid.classList.toggle("show-mobile-queue");
    });
  }
  $("btnStageFs").addEventListener("click", () => openFullscreen(isLyricsViewStage));
  $("btnStageLyricsDownload").addEventListener("click", () => {
    if (lyricsTrack) openLyricsPicker(lyricsTrack);
  });

  // --- Fullscreen Player Modal ---
  function openFullscreen(showLyrics = false) {
    $("fullscreenPlayer").classList.add("active");
    switchFullscreenMode(showLyrics);
  }
  function closeFullscreen() {
    $("fullscreenPlayer").classList.remove("active");
  }
  $("miniPlayerOpen").addEventListener("click", () => openFullscreen(isLyricsViewStage));
  $("miniBtnLyrics").addEventListener("click", () => openFullscreen(true));
  $("btnCloseFullscreen").addEventListener("click", closeFullscreen);

  function switchFullscreenMode(showLyrics) {
    isLyricsViewFs = showLyrics;
    $("fsSwitchArtwork").classList.toggle("active", !showLyrics);
    $("fsSwitchLyrics").classList.toggle("active", showLyrics);
    $("fsArtworkSection").hidden = showLyrics;
    $("fsLyricsSection").hidden = !showLyrics;
    if (showLyrics && lastState) {
      const track = lastState.queue?.[lastState.index];
      if (track) fetchAndRenderLyrics(track);
      let pos = (lastState.position || 0) / 1000;
      activeLyricIndex = -1;
      syncLyricsPosition(pos);
    }
    if (activeView === "player") syncRouteToHash();
  }
  $("fsSwitchArtwork").addEventListener("click", () => switchFullscreenMode(false));
  $("fsSwitchLyrics").addEventListener("click", () => switchFullscreenMode(true));

