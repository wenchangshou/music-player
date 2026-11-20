  // --- Playback State Rendering ---
  function renderPlayback(state) {
    if (lastState && lastState.playing && state.playing && lastState.index === state.index) {
      // Calculate continuous local progress
      const prevEst = ((lastState.position || 0) / 1000) + (lastState.time ? ((Date.now() - lastState.time) / 1000) * (lastState.speed || 1) : 0);
      const newPos = (state.position || 0) / 1000;
      // If polling returns a slightly stale position within 1.5s due to network transit, smooth it instead of jumping backwards
      if (prevEst > newPos && prevEst - newPos < 1.5) {
        state.time = Date.now() - ((prevEst - newPos) * 1000);
      } else {
        state.time = Date.now();
      }
    } else {
      state.time = Date.now();
    }
    lastState = state;
    updateAiNowPlayingSeed();
    if (typeof state.autoDj === "boolean" && state.autoDj !== autoDjEnabled) {
      autoDjEnabled = state.autoDj;
      $("btnToggleAutoDj")?.classList.toggle("active", autoDjEnabled);
    }
    maybeRefreshSideNotes(state);
    ready = true;
    $("status").textContent = "已连接设备";
    $("status").classList.add("ok");

    const track = state.queue?.[state.index];
    const isPlaying = state.playing && !state.loading;
    const trackName = track ? (track.album || track.title || "") : "";

    // Update Cover & Artwork
    const coverUrl = getArtworkUrl(track, 640);
    applyArtwork($("artworkBox"), $("artworkImg"), coverUrl, trackName);
    applyArtwork($("miniThumbBox"), $("miniThumbImg"), getArtworkUrl(track, 160), trackName);
    applyArtwork($("fsArtworkBox"), $("fsArtworkImg"), coverUrl, trackName);

    if (coverUrl) {
      $("fsBackdrop").style.backgroundImage = `url('${coverUrl}')`;
      $("stageBackdrop").style.backgroundImage = `url('${coverUrl}')`;
    } else {
      $("fsBackdrop").style.backgroundImage = "none";
      $("stageBackdrop").style.backgroundImage = "none";
    }

    // Dynamic Ambient Glow
    const hue = hueOf(trackName);
    document.documentElement.style.setProperty("--ambient-color", `hsla(${hue}, 70%, 55%, 0.25)`);

    // Titles & Info
    const title = track?.title || "未播放";
    const artist = track?.artist ? `${track.artist}${track.album ? " · " + track.album : ""}` : "轻触以选择曲目";
    $("trackTitle").textContent = title;
    $("trackArtist").textContent = artist;
    $("miniTrackTitle").textContent = title;
    $("miniTrackArtist").textContent = track?.artist || "听音室 · Hi-Res 播放";
    $("fsTrackTitle").textContent = title;
    $("fsTrackArtist").textContent = artist;

    // Badges & Audio Chain
    const source = track?.source || (track?.isLocal ? "本地音乐" : track?.isNetease ? "网易云" : "TIDAL");
    $("sourceBadge").textContent = source;
    if (state.actualLevel || state.requestedQuality) {
      $("qualityBadge").textContent = state.actualLevel || state.requestedQuality;
      $("qualityBadge").hidden = false;
    } else {
      $("qualityBadge").hidden = true;
    }

    // Hero / Mini Play/Pause Icons
    const playIconHref = isPlaying ? "#i-pause" : "#i-play";
    $("heroPlayIcon").setAttribute("href", playIconHref);
    $("miniPlayIcon").setAttribute("href", playIconHref);
    $("fsHeroPlayIcon").setAttribute("href", playIconHref);

    // Modes: Shuffle & Repeat
    $("btnShuffle").classList.toggle("on", Boolean(state.shuffle));
    $("fsBtnShuffle").classList.toggle("on", Boolean(state.shuffle));
    $("btnRepeat").classList.toggle("on", state.repeat > 0);
    $("fsBtnRepeat").classList.toggle("on", state.repeat > 0);
    $("repeatDot").hidden = state.repeat === 0;

    // Quality Selector
    if (state.quality !== undefined) $("qualitySelect").value = String(state.quality);

    // Volume: Echo and oscillation suppression (lock window of 1200ms after user adjustment)
    const isRecentVolumeAction = volumeDragging || (Date.now() - lastVolumeUserActionTime < 1200);
    if (state.volume !== undefined && !isRecentVolumeAction) {
      const incomingVol = Math.round(state.volume);
      if (Math.abs(incomingVol - currentVolume) >= 1) {
        currentVolume = incomingVol;
        $("volumeSlider").value = String(currentVolume);
        $("fsVolumeSlider").value = String(currentVolume);
        $("miniVolumeSlider").value = String(currentVolume);
        updateVolumeIcon(currentVolume);
      }
    }

    // Radio availability
    $("btnOpenRadio").disabled = !(track?.id > 0 && !track?.isLocal);

    // Queue list rendering
    renderQueue(state.queue || [], state.index || 0);

    // Preload lyrics for stage or fullscreen if open
    if ((isLyricsViewStage || isLyricsViewFs) && track) fetchAndRenderLyrics(track);
  }

  function updateVolumeIcon(vol) {
    const isMuted = vol <= 0;
    const href = isMuted ? "#i-volume-mute" : "#i-volume";
    $("volIcon").setAttribute("href", href);
    $("fsVolIcon").setAttribute("href", href);
  }

  // --- Real-time Progress Animation Loop ---
  let lastMonotonicPos = 0;
  function startProgressTicker() {
    setInterval(() => {
      if (!lastState || seeking) return;
      let pos = (lastState.position || 0) / 1000;
      if (lastState.playing && !lastState.loading && lastState.time) {
        const delta = (Date.now() - lastState.time) / 1000;
        pos += delta * (lastState.speed || 1);
      }
      const dur = (lastState.duration || 0) / 1000;
      pos = dur > 0 ? Math.min(pos, dur) : Math.max(0, pos);

      // Monotonic smoothing: clamp against micro-stutters during normal playback
      if (lastState.playing && !lastState.loading) {
        if (pos < lastMonotonicPos && lastMonotonicPos - pos < 1.2) {
          pos = lastMonotonicPos;
        } else {
          lastMonotonicPos = pos;
        }
      } else {
        lastMonotonicPos = pos;
      }

      const pct = dur > 0 ? Math.min(1, Math.max(0, pos / dur)) : 0;

      // Update Sliders
      $("seekSlider").value = pct;
      $("seekSlider").style.setProperty("--pct", `${pct * 100}%`);
      $("fsSeekSlider").value = pct;
      $("fsSeekSlider").style.setProperty("--pct", `${pct * 100}%`);
      $("miniProgressFill").style.width = `${pct * 100}%`;

      // Update Labels
      const curFormatted = formatTime(pos);
      const durFormatted = formatTime(dur);
      $("currentTime").textContent = curFormatted;
      $("totalDuration").textContent = durFormatted;
      $("fsCurrentTime").textContent = curFormatted;
      $("fsTotalDuration").textContent = durFormatted;

      // Sync Lyrics
      syncLyricsPosition(pos);
    }, 125);
  }

  // --- Transport Controls Event Bindings ---
  const togglePlay = () => call("toggle");
  $("btnPlayHero").addEventListener("click", togglePlay);
  $("miniBtnPlay").addEventListener("click", togglePlay);
  $("fsBtnPlay").addEventListener("click", togglePlay);

  const prevTrack = () => call("previous");
  $("btnPrev").addEventListener("click", prevTrack);
  $("miniBtnPrev").addEventListener("click", prevTrack);
  $("fsBtnPrev").addEventListener("click", prevTrack);

  const nextTrack = () => call("next");
  $("btnNext").addEventListener("click", nextTrack);
  $("miniBtnNext").addEventListener("click", nextTrack);
  $("fsBtnNext").addEventListener("click", nextTrack);

  const toggleShuffle = () => call("shuffle");
  $("btnShuffle").addEventListener("click", toggleShuffle);
  $("fsBtnShuffle").addEventListener("click", toggleShuffle);

  const cycleRepeat = () => call("repeat");
  $("btnRepeat").addEventListener("click", cycleRepeat);
  $("fsBtnRepeat").addEventListener("click", cycleRepeat);

  // Volume
  function sendVolume(v) {
    const val = Math.max(0, Math.min(100, Math.round(v)));
    call("volume", { value: val });
  }

  function handleVolumeInput(v) {
    volumeDragging = true;
    lastVolumeUserActionTime = Date.now();
    currentVolume = Math.max(0, Math.min(100, Math.round(v)));
    $("volumeSlider").value = String(currentVolume);
    $("fsVolumeSlider").value = String(currentVolume);
    $("miniVolumeSlider").value = String(currentVolume);
    updateVolumeIcon(currentVolume);

    clearTimeout(volumeDebounceTimer);
    volumeDebounceTimer = setTimeout(() => {
      sendVolume(currentVolume);
    }, 60);
  }

  function handleVolumeChange(v) {
    clearTimeout(volumeDebounceTimer);
    lastVolumeUserActionTime = Date.now();
    currentVolume = Math.max(0, Math.min(100, Math.round(v)));
    $("volumeSlider").value = String(currentVolume);
    $("fsVolumeSlider").value = String(currentVolume);
    $("miniVolumeSlider").value = String(currentVolume);
    updateVolumeIcon(currentVolume);
    sendVolume(currentVolume);
    setTimeout(() => {
      volumeDragging = false;
    }, 800);
  }

  ["volumeSlider", "fsVolumeSlider", "miniVolumeSlider"].forEach(id => {
    const el = $(id);
    if (!el) return;
    el.addEventListener("input", e => handleVolumeInput(parseFloat(e.target.value)));
    el.addEventListener("change", e => handleVolumeChange(parseFloat(e.target.value)));
  });

  let savedVolBeforeMute = 80;
  const toggleMute = () => {
    if (currentVolume > 0) {
      savedVolBeforeMute = currentVolume;
      handleVolumeChange(0);
    } else {
      handleVolumeChange(savedVolBeforeMute || 80);
    }
  };
  $("btnMute").addEventListener("click", toggleMute);
  $("fsBtnMute").addEventListener("click", toggleMute);

  // Seeking
  function handleSeek(slider) {
    slider.addEventListener("input", () => { seeking = true; });
    slider.addEventListener("change", () => {
      seeking = false;
      if (!lastState?.duration) return;
      const pct = parseFloat(slider.value);
      const ms = Math.round(pct * lastState.duration);
      call("seek", { ms });
    });
  }
  handleSeek($("seekSlider"));
  handleSeek($("fsSeekSlider"));

  // Quality
  $("qualitySelect").addEventListener("change", e => {
    call("quality", { value: e.target.value });
    toast("音质档位已切换");
  });

  // Radio
  $("btnOpenRadio").addEventListener("click", () => {
    const track = lastState?.queue?.[lastState.index];
    if (!track?.id) return;
    showView("library");
    loadRadio(track.id, track.title);
  });

