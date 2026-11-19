(() => {
  "use strict";

  const $ = id => document.getElementById(id);

  // --- State & Session ---
  const hashVal = location.hash.slice(1);
  const sharedCode = /^[0-9]{6}$/.test(hashVal) ? hashVal : "";
  let key = sharedCode ? "" : hashVal;
  const sessionKey = "tidal-lan-pair";
  const central = document.documentElement.dataset.central === "1";
  let device = "";
  const adminSessionKey = "tidal-central-admin";
  const accountSessionKey = "tidal-player-account";
  let ready = false;
  let lastState = null;
  let pollActive = false;
  let seeking = false;
  let currentVolume = 0.8;
  let activeView = "player";
  let libraryTab = "local";
  let currentLyrics = [];
  let currentLyricsTrackId = null;
  let activeLyricIndex = -1;
  let isLyricsViewFs = false;
  let currentSearchSource = "all";
  let searchDebounceTimer = null;
  let libraryRevision = 0;
  let currentPlaylist = "";
  let currentCollection = null;
  let currentGroup = null;
  let libraryOffset = 0;
  let libraryHasMore = false;
  let localPlaylistsCache = [];

  function readLanPair() {
    try {
      return localStorage.getItem(sessionKey) || sessionStorage.getItem(sessionKey) || "";
    } catch { return ""; }
  }
  function saveLanPair(val) {
    try {
      if (val) localStorage.setItem(sessionKey, val);
      else localStorage.removeItem(sessionKey);
      if (val) sessionStorage.setItem(sessionKey, val);
      else sessionStorage.removeItem(sessionKey);
    } catch {}
  }
  if (!key) key = readLanPair();
  if (key && !sharedCode) saveLanPair(key);

  function requireLanPair(message) {
    ready = false;
    $("status").textContent = message || "请先在手机配对";
    $("status").classList.remove("ok");
    $("pairPanel").hidden = false;
    if (sharedCode) $("pairCode").value = sharedCode;
  }

  async function pair(code) {
    if (!code || !/^[0-9]{6}$/.test(code.trim())) {
      toast("请输入 6 位数字配对码", "i-trash");
      return;
    }
    const cleanCode = code.trim();
    const btn = $("pairForm").querySelector("button");
    btn.disabled = true;
    $("pairCode").disabled = true;
    $("status").textContent = "正在验证配对码…";
    try {
      const res = await fetchJson("/api/pair", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: cleanCode })
      }, 8000);
      if (!res.key) throw new Error("配对失败，未收到密钥");
      key = res.key;
      saveLanPair(key);
      $("pairCode").value = "";
      $("pairPanel").hidden = true;
      toast("设备配对成功！", "i-music");
      history.replaceState(null, "", location.pathname);
      pollState();
    } catch (err) {
      toast(err.message, "i-trash");
      $("status").textContent = err.message;
    } finally {
      btn.disabled = false;
      $("pairCode").disabled = false;
    }
  }

  $("pairForm").addEventListener("submit", e => {
    e.preventDefault();
    pair($("pairCode").value);
  });

  $("btnDevices").addEventListener("click", () => {
    if (central) {
      $("adminPanel").hidden = !$("adminPanel").hidden;
    } else {
      $("pairPanel").hidden = !$("pairPanel").hidden;
      if (!$("pairPanel").hidden) $("pairCode").focus();
    }
  });

  // Account login in central relay mode
  $("accountForm").addEventListener("submit", async e => {
    e.preventDefault();
    const btn = $("accountForm").querySelector("button");
    btn.disabled = true;
    try {
      const res = await fetchJson("/account/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: $("accountName").value.trim(),
          password: $("accountPassword").value
        })
      });
      if (res.key) {
        key = res.key;
        saveLanPair(key);
        $("adminPanel").hidden = true;
        toast("账号登录成功！", "i-music");
        pollState();
      }
    } catch (err) {
      $("adminMessage").textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  });

  // --- Floating Toasts ---
  function toast(message, icon = "i-music") {
    const container = $("toastContainer");
    if (!container) return;
    const t = document.createElement("div");
    t.className = "toast";
    t.innerHTML = `<svg class="icon" style="width:18px;height:18px;"><use href="#${icon}"/></svg><span>${message}</span>`;
    container.appendChild(t);
    setTimeout(() => {
      t.style.opacity = "0";
      t.style.transform = "translateY(-10px) scale(0.95)";
      t.style.transition = "all 0.25s ease";
      setTimeout(() => t.remove(), 250);
    }, 2400);
  }

  // --- Custom Modal Dialogs ---
  let modalCallback = null;
  function openCustomModal(title, lead, initialValue, onConfirm) {
    $("customModalTitle").textContent = title;
    $("customModalLead").textContent = lead;
    $("customModalInput").value = initialValue || "";
    $("customModalOverlay").classList.add("active");
    $("customModalInput").focus();
    modalCallback = onConfirm;
  }
  function closeCustomModal() {
    $("customModalOverlay").classList.remove("active");
    modalCallback = null;
  }
  $("btnCustomModalCancel").addEventListener("click", closeCustomModal);
  $("btnCustomModalConfirm").addEventListener("click", () => {
    const val = $("customModalInput").value.trim();
    if (modalCallback) modalCallback(val);
    closeCustomModal();
  });
  $("customModalOverlay").addEventListener("click", e => {
    if (e.target === $("customModalOverlay")) closeCustomModal();
  });

  // --- Network API ---
  async function fetchJson(path, options = {}, timeout = 22000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const headers = { ...options.headers };
    if (key && !headers["Authorization"]) {
      headers["Authorization"] = `Bearer ${key}`;
    }
    try {
      const res = await fetch(path, { ...options, headers, signal: controller.signal });
      const json = await res.json().catch(() => ({}));
      if (res.status === 401) {
        const error = new Error(json.error || "配对链接已过期或未授权");
        error.status = 401;
        requireLanPair(error.message);
        throw error;
      }
      if (!res.ok) {
        const error = new Error(json.error || `请求失败 (HTTP ${res.status})`);
        error.status = res.status;
        throw error;
      }
      return json;
    } finally {
      clearTimeout(timer);
    }
  }

  async function call(op, fields = {}) {
    const headers = { "Content-Type": "application/json" };
    if (!central) {
      return fetchJson("/api/control", { method: "POST", headers, body: JSON.stringify({ op, ...fields }) });
    }
    return fetchJson("/api/device/call", { method: "POST", headers, body: JSON.stringify({ device, op, ...fields }) });
  }

  // --- Navigation & View Switching ---
  function showView(viewId) {
    activeView = viewId;
    $("viewPlayer").hidden = viewId !== "player";
    $("viewLibrary").hidden = viewId !== "library";
    $("viewSearch").hidden = viewId !== "search";
    $("viewFiles").hidden = viewId !== "files";

    $("tabNavPlayer").setAttribute("aria-current", String(viewId === "player"));
    $("tabNavLibrary").setAttribute("aria-current", String(viewId === "library"));
    $("tabNavSearch").setAttribute("aria-current", String(viewId === "search"));
    $("tabNavFiles").setAttribute("aria-current", String(viewId === "files"));

    if (viewId === "library") loadLibrary();
    if (viewId === "files") loadFiles();
  }

  $("tabNavPlayer").addEventListener("click", () => showView("player"));
  $("tabNavLibrary").addEventListener("click", () => showView("library"));
  $("tabNavSearch").addEventListener("click", () => {
    showView("search");
    setTimeout(() => $("globalSearchInput").focus(), 100);
  });
  $("tabNavFiles").addEventListener("click", () => showView("files"));

  // --- Fullscreen Player Modal ---
  function openFullscreen(showLyrics = false) {
    $("fullscreenPlayer").classList.add("active");
    if (showLyrics) switchFullscreenMode(true);
  }
  function closeFullscreen() {
    $("fullscreenPlayer").classList.remove("active");
  }
  $("miniPlayerOpen").addEventListener("click", () => openFullscreen(false));
  $("miniBtnLyrics").addEventListener("click", () => openFullscreen(true));
  $("btnCloseFullscreen").addEventListener("click", closeFullscreen);
  $("btnOpenLyrics").addEventListener("click", () => openFullscreen(true));

  function switchFullscreenMode(showLyrics) {
    isLyricsViewFs = showLyrics;
    $("fsSwitchArtwork").classList.toggle("active", !showLyrics);
    $("fsSwitchLyrics").classList.toggle("active", showLyrics);
    $("fsArtworkSection").hidden = showLyrics;
    $("fsLyricsSection").hidden = !showLyrics;
    if (showLyrics && lastState) fetchAndRenderLyrics(lastState.queue?.[lastState.index]);
  }
  $("fsSwitchArtwork").addEventListener("click", () => switchFullscreenMode(false));
  $("fsSwitchLyrics").addEventListener("click", () => switchFullscreenMode(true));

  // --- Formatter & Utilities ---
  function formatTime(seconds) {
    if (isNaN(seconds) || seconds < 0) return "0:00";
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s < 10 ? "0" : ""}${s}`;
  }

  const tidalImage = /^https:\/\/resources\.tidal\.com\/images\/[a-f0-9/]+\/\d+x\d+\.jpg$/i;
  function getArtworkUrl(item, size = 640) {
    if (!item) return "";
    let cover = "";
    if (typeof item === "string") {
      cover = item;
    } else {
      if (item.coverUrl) return item.coverUrl;
      cover = item.cover || item.picture || item.image || "";
      if (!cover && item.id && (item.isLocal || item.source === "本地音乐" || item.id < 0)) {
        return `/api/artwork/${item.id}`;
      }
    }
    if (!cover) return "";
    if (tidalImage.test(cover)) {
      return cover.replace(/\/\d+x\d+\.jpg$/i, `/${size}x${size}.jpg`);
    }
    if (/^[0-9a-fA-F-]{36}$/.test(cover)) {
      return `https://resources.tidal.com/images/${cover.replace(/-/g, "/")}/${size}x${size}.jpg`;
    }
    if (cover.includes("music.126.net") && !cover.includes("param=")) {
      return `${cover}${cover.includes("?") ? "&" : "?"}param=${size}y${size}`;
    }
    return cover;
  }

  function letterOf(title) {
    const name = (title || "").trim().replace(/\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|wma)$/i, "");
    return name ? [...name][0].toUpperCase() : "♪";
  }

  function hueOf(text) {
    let hash = 0;
    for (const char of text || "") hash = (hash * 31 + char.codePointAt(0)) | 0;
    return Math.abs(hash * 37) % 360;
  }

  function applyArtwork(container, img, url, title) {
    const name = title || "";
    const letter = letterOf(name);
    const hue = hueOf(name);
    if (container) {
      container.dataset.letter = letter;
      container.style.setProperty("--art-hue", hue);
      container.style.setProperty("--hue", hue);
    }
    if (!img) return;
    if (!url) {
      img.hidden = true;
      img.removeAttribute("src");
      if (container) {
        container.classList.remove("art-loaded");
        container.classList.add("art-failed");
      }
      return;
    }
    img.onload = () => {
      img.hidden = false;
      if (container) {
        container.classList.remove("art-failed");
        container.classList.add("art-loaded");
      }
    };
    img.onerror = () => {
      img.hidden = true;
      if (container) {
        container.classList.remove("art-loaded");
        container.classList.add("art-failed");
      }
    };
    img.src = url;
    if (img.complete && img.naturalWidth > 0) {
      img.hidden = false;
      if (container) {
        container.classList.remove("art-failed");
        container.classList.add("art-loaded");
      }
    }
  }

  // --- Lyrics Parser & Engine ---
  function parseLrc(text) {
    if (!text) return [];
    const lines = text.split("\n");
    const result = [];
    const timeRegex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\]/g;
    for (const raw of lines) {
      let match;
      const times = [];
      let lastIndex = 0;
      while ((match = timeRegex.exec(raw)) !== null) {
        const min = parseInt(match[1], 10);
        const sec = parseInt(match[2], 10);
        const ms = match[3] ? parseFloat("0." + match[3]) : 0;
        times.push(min * 60 + sec + ms);
        lastIndex = timeRegex.lastIndex;
      }
      const content = raw.slice(lastIndex).trim();
      if (content && times.length > 0) {
        for (const t of times) result.push({ time: t, text: content });
      }
    }
    result.sort((a, b) => a.time - b.time);
    return result;
  }

  async function fetchAndRenderLyrics(track) {
    if (!track) return;
    if (currentLyricsTrackId === track.id && currentLyrics.length > 0) return;
    currentLyricsTrackId = track.id;
    $("fsLyricsEmpty").textContent = "正在读取动态歌词…";
    $("fsLyricsEmpty").hidden = false;
    $("fsLyricsList").replaceChildren();

    try {
      const res = await call("library", { action: "lyrics", id: track.id });
      const lrcText = res.lyrics || "";
      currentLyrics = parseLrc(lrcText);
      if (!currentLyrics.length) {
        $("fsLyricsEmpty").textContent = lrcText ? lrcText : "暂无动态歌词，轻触屏幕欣赏音乐";
        $("fsLyricsEmpty").hidden = false;
        return;
      }
      $("fsLyricsEmpty").hidden = true;
      const frag = document.createDocumentFragment();
      currentLyrics.forEach((item, idx) => {
        const div = document.createElement("div");
        div.className = "lyric-line";
        div.dataset.index = idx;
        div.textContent = item.text;
        div.addEventListener("click", () => {
          call("seek", { ms: Math.round(item.time * 1000) });
        });
        frag.appendChild(div);
      });
      $("fsLyricsList").appendChild(frag);
    } catch {
      $("fsLyricsEmpty").textContent = "未能获取歌词";
      $("fsLyricsEmpty").hidden = false;
    }
  }

  function syncLyricsPosition(currentTime) {
    if (!currentLyrics.length || !isLyricsViewFs) return;
    let newIndex = -1;
    for (let i = 0; i < currentLyrics.length; i++) {
      if (currentLyrics[i].time <= currentTime + 0.25) newIndex = i;
      else break;
    }
    if (newIndex === activeLyricIndex) return;
    activeLyricIndex = newIndex;

    const lines = $("fsLyricsList").children;
    for (let i = 0; i < lines.length; i++) {
      const isActive = i === activeLyricIndex;
      lines[i].classList.toggle("active", isActive);
      if (isActive) {
        lines[i].scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }
  }

  // --- Playback State Rendering ---
  function renderPlayback(state) {
    lastState = state;
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
    } else {
      $("fsBackdrop").style.backgroundImage = "none";
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

    // Volume
    if (state.volume !== undefined && Math.abs(state.volume - currentVolume) > 0.05) {
      currentVolume = state.volume;
      $("volumeSlider").value = String(currentVolume);
      $("fsVolumeSlider").value = String(currentVolume);
      $("miniVolumeSlider").value = String(currentVolume);
      updateVolumeIcon(currentVolume);
    }

    // Radio availability
    $("btnOpenRadio").disabled = !(track?.id > 0 && !track?.isLocal);

    // Queue list rendering
    renderQueue(state.queue || [], state.index || 0);

    // Preload lyrics for fullscreen if open
    if (isLyricsViewFs && track) fetchAndRenderLyrics(track);
  }

  function updateVolumeIcon(vol) {
    const isMuted = vol <= 0.01;
    const href = isMuted ? "#i-volume-mute" : "#i-volume";
    $("volIcon").setAttribute("href", href);
    $("fsVolIcon").setAttribute("href", href);
  }

  // --- Real-time Progress Animation Loop ---
  function startProgressTicker() {
    setInterval(() => {
      if (!lastState || seeking) return;
      let pos = (lastState.position || 0) / 1000;
      if (lastState.playing && !lastState.loading && lastState.time) {
        const delta = (Date.now() - lastState.time) / 1000;
        pos += delta * (lastState.speed || 1);
      }
      const dur = (lastState.duration || 0) / 1000;
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
    }, 250);
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
  function setVolume(v) {
    currentVolume = v;
    $("volumeSlider").value = v;
    $("fsVolumeSlider").value = v;
    $("miniVolumeSlider").value = v;
    updateVolumeIcon(v);
    call("volume", { value: v });
  }
  $("volumeSlider").addEventListener("input", e => setVolume(parseFloat(e.target.value)));
  $("fsVolumeSlider").addEventListener("input", e => setVolume(parseFloat(e.target.value)));
  $("miniVolumeSlider").addEventListener("input", e => setVolume(parseFloat(e.target.value)));
  let savedVolBeforeMute = 0.8;
  const toggleMute = () => {
    if (currentVolume > 0.01) {
      savedVolBeforeMute = currentVolume;
      setVolume(0);
    } else {
      setVolume(savedVolBeforeMute || 0.8);
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

  // --- Queue View Rendering ---
  function renderQueue(queue, currentIndex) {
    $("queueCountNum").textContent = queue.length;
    $("queueSummaryText").textContent = queue.length ? `共 ${queue.length} 首歌曲` : "队列空闲";
    const list = $("queueList");
    list.replaceChildren();

    if (!queue.length) {
      list.innerHTML = `<li style="text-align:center; padding:30px 10px; color:var(--muted);">队列为空，快去曲库添加吧</li>`;
      return;
    }

    const frag = document.createDocumentFragment();
    queue.forEach((item, index) => {
      const li = document.createElement("li");
      li.className = `track-row ${index === currentIndex ? "active" : ""}`;
      const cover = getArtworkUrl(item, 160);
      li.innerHTML = `
        <div class="track-thumb">
          <img alt="">
        </div>
        <div class="track-info">
          <div class="track-title">${item.title}</div>
          <div class="track-subtitle">${item.artist || "未知艺人"}</div>
        </div>
        ${index === currentIndex ? `<div class="eq-wave"><span></span><span></span><span></span></div>` : ""}
        <div class="track-actions">
          <button type="button" class="icon-btn hover-only btn-remove" title="从队列移除"><svg class="icon"><use href="#i-trash"/></svg></button>
        </div>
      `;
      applyArtwork(li.querySelector(".track-thumb"), li.querySelector(".track-thumb img"), cover, item.title);
      li.addEventListener("click", e => {
        if (e.target.closest(".btn-remove")) {
          e.stopPropagation();
          call("remove", { index, revision: lastState.queueRevision });
          return;
        }
        call("play", { index, revision: lastState.queueRevision });
      });
      frag.appendChild(li);
    });
    list.appendChild(frag);
  }

  $("btnClearQueue").addEventListener("click", () => {
    call("clear");
    toast("已清空后续队列");
  });

  // --- Side Queue / Side Search Tab Switching ---
  $("tabSideQueue").addEventListener("click", () => {
    $("tabSideQueue").setAttribute("aria-current", "true");
    $("tabSideSearch").setAttribute("aria-current", "false");
    $("sideQueueContent").hidden = false;
    $("sideSearchContent").hidden = true;
  });
  $("tabSideSearch").addEventListener("click", () => {
    $("tabSideQueue").setAttribute("aria-current", "false");
    $("tabSideSearch").setAttribute("aria-current", "true");
    $("sideQueueContent").hidden = true;
    $("sideSearchContent").hidden = false;
    $("sideSearchInput").focus();
  });

  $("sideSearchForm").addEventListener("submit", async e => {
    e.preventDefault();
    const q = $("sideSearchInput").value.trim();
    if (!q) return;
    const res = await call("search", { query: q });
    const list = $("sideSearchResults");
    list.replaceChildren();
    for (const track of res.tracks || []) {
      const li = createTrackRow(track, () => {
        call("add", { id: track.id, next: true });
        toast(`已加入下一首播放：${track.title}`);
      });
      list.appendChild(li);
    }
  });

  // --- Music Library System ---
  const libNavButtons = document.querySelectorAll("[data-lib]");
  libNavButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      libNavButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      libraryTab = btn.dataset.lib;
      currentCollection = null;
      currentGroup = null;
      currentPlaylist = "";
      loadLibrary();
    });
  });

  async function loadLibrary(append = false) {
    const rev = ++libraryRevision;
    $("libraryTrackList").hidden = false;
    $("libraryCardsView").hidden = true;
    $("btnLibBack").hidden = !currentCollection;
    $("localControls").hidden = libraryTab !== "local";
    $("tidalControls").hidden = libraryTab !== "tidal-collections";

    if (!append) {
      libraryOffset = 0;
      $("libraryTrackList").replaceChildren();
      $("libraryCardsView").replaceChildren();
    }

    try {
      if (libraryTab === "local") {
        $("libraryTitle").textContent = currentPlaylist ? `歌单 · ${currentPlaylist}` : "本地音乐";
        const res = await call("library", {
          action: "local",
          query: $("librarySearchInput").value.trim(),
          playlist: currentPlaylist,
          sort: $("localSort").value,
          offset: libraryOffset
        });
        if (rev !== libraryRevision) return;

        // Populate playlist select dropdown
        if (res.playlists) {
          localPlaylistsCache = res.playlists;
          const sel = $("playlistSelect");
          sel.innerHTML = `<option value="">全部歌曲</option>` + res.playlists.map(p => `<option value="${p}">${p}</option>`).join("");
          sel.value = currentPlaylist;
        }

        renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
        $("btnDeletePlaylist").hidden = !currentPlaylist;
      }
      else if (libraryTab === "recent") {
        $("libraryTitle").textContent = "最近播放";
        const res = await call("library", { action: "recent" });
        if (rev !== libraryRevision) return;
        renderTrackRows(res.tracks || [], $("libraryTrackList"), false);
      }
      else if (libraryTab === "tidal-favorites") {
        $("libraryTitle").textContent = "TIDAL 收藏歌曲";
        const res = await call("library", { action: "tidal-favorites", offset: libraryOffset });
        if (rev !== libraryRevision) return;
        renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
      }
      else if (libraryTab === "tidal-collections") {
        const kind = $("tidalCollectionKind").value;
        const kindNames = { ALBUM: "精选专辑", ARTIST: "热门艺人", PLAYLIST: "官方歌单" };
        $("libraryTitle").textContent = `TIDAL · ${kindNames[kind] || "唱片集"}`;
        $("libraryTrackList").hidden = true;
        $("libraryCardsView").hidden = false;

        const res = await call("library", {
          action: "tidal-collections",
          kind,
          query: $("librarySearchInput").value.trim(),
          offset: libraryOffset
        });
        if (rev !== libraryRevision) return;
        renderCollectionCards(res.collections || [], kind, $("libraryCardsView"));
      }
      else if (libraryTab === "netease") {
        $("libraryTitle").textContent = "网易云音乐 · 推荐与搜索";
        const q = $("librarySearchInput").value.trim();
        if (q) {
          const res = await call("library", { action: "netease-search", query: q, offset: libraryOffset });
          if (rev !== libraryRevision) return;
          renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
        } else {
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          const res = await call("library", { action: "netease-recommended" }).catch(() => ({ collections: [] }));
          if (rev !== libraryRevision) return;
          renderCollectionCards(res.collections || [], "PLAYLIST", $("libraryCardsView"));
        }
      }
    } catch (err) {
      toast(err.message, "i-trash");
    }
  }

  function renderTrackRows(tracks, container, append = false) {
    if (!append) container.replaceChildren();
    if (!tracks.length && !append) {
      container.innerHTML = `<li style="text-align:center; padding:40px; color:var(--muted);">暂无曲目</li>`;
      return;
    }
    const frag = document.createDocumentFragment();
    tracks.forEach(track => {
      const li = createTrackRow(track, () => {
        call("library", { action: "song", id: track.id, track, mode: "play" });
      });
      frag.appendChild(li);
    });
    container.appendChild(frag);
  }

  function createTrackRow(track, onPlay) {
    const li = document.createElement("li");
    li.className = "track-row";
    const cover = getArtworkUrl(track, 160);
    li.innerHTML = `
      <div class="track-thumb">
        <img alt="">
      </div>
      <div class="track-info">
        <div class="track-title">${track.title}</div>
        <div class="track-subtitle">${track.artist || "未知艺人"}${track.album ? " · " + track.album : ""}</div>
      </div>
      <div class="track-actions">
        <button type="button" class="icon-btn btn-add-next hover-only" title="下一首播放"><svg class="icon"><use href="#i-next"/></svg></button>
        <button type="button" class="icon-btn btn-add-fav hover-only" title="喜欢"><svg class="icon"><use href="#i-heart"/></svg></button>
        <button type="button" class="icon-btn btn-add-playlist hover-only" title="加入歌单"><svg class="icon"><use href="#i-plus"/></svg></button>
      </div>
    `;
    applyArtwork(li.querySelector(".track-thumb"), li.querySelector(".track-thumb img"), cover, track.title);
    li.addEventListener("click", e => {
      if (e.target.closest(".track-actions")) return;
      onPlay();
    });
    li.querySelector(".btn-add-next").addEventListener("click", () => {
      call("library", { action: "song", id: track.id, track, mode: "next" });
      toast(`已设为下一首播放：${track.title}`);
    });
    li.querySelector(".btn-add-fav").addEventListener("click", () => {
      if (track.isLocal) {
        call("library", { action: "local-favorite", id: track.id });
        toast("已更新本地喜欢");
      } else {
        call("library", { action: "tidal-favorite", id: track.id, enabled: true });
        toast("已收藏到 TIDAL");
      }
    });
    li.querySelector(".btn-add-playlist").addEventListener("click", () => {
      openCustomModal("加入歌单", `将「${track.title}」添加到歌单：`, currentPlaylist || "我的最爱", playlistName => {
        if (!playlistName) return;
        call("library", { action: "playlist-add", id: track.id, playlist: playlistName });
        toast(`已添加到歌单：${playlistName}`);
      });
    });
    return li;
  }

  function renderCollectionCards(collections, kind, container) {
    container.replaceChildren();
    if (!collections.length) {
      container.innerHTML = `<div style="grid-column:1/-1; text-align:center; padding:40px; color:var(--muted);">暂无内容</div>`;
      return;
    }
    const frag = document.createDocumentFragment();
    collections.forEach(item => {
      const card = document.createElement("div");
      card.className = "card-item";
      const isArtist = kind === "ARTIST";
      const cover = getArtworkUrl(item, 320);
      card.innerHTML = `
        <div class="card-artwork ${isArtist ? "artist" : ""}">
          <img alt="">
          <div class="card-play-overlay">
            <button type="button" class="btn-card-play"><svg class="icon"><use href="#i-play"/></svg></button>
          </div>
        </div>
        <div class="card-title">${item.title || item.name}</div>
        <div class="card-subtitle">${item.subtitle || (isArtist ? "艺人" : "专辑")}</div>
      `;
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), cover, item.title || item.name);
      card.addEventListener("click", () => {
        currentCollection = item;
        openCollectionTracks(item, kind);
      });
      frag.appendChild(card);
    });
    container.appendChild(frag);
  }

  async function openCollectionTracks(collection, kind) {
    $("libraryTitle").textContent = collection.title || collection.name;
    $("libraryTrackList").hidden = false;
    $("libraryCardsView").hidden = true;
    $("btnLibBack").hidden = false;

    const action = libraryTab === "netease" ? "netease-collection" : "tidal-collection";
    const res = await call("library", { action, kind, collection: collection.id || collection.uuid });
    renderTrackRows(res.tracks || [], $("libraryTrackList"), false);
  }

  $("btnLibBack").addEventListener("click", () => {
    currentCollection = null;
    loadLibrary();
  });

  $("playlistSelect").addEventListener("change", e => {
    currentPlaylist = e.target.value;
    loadLibrary();
  });
  $("localSort").addEventListener("change", () => loadLibrary());
  $("tidalCollectionKind").addEventListener("change", () => loadLibrary());

  $("btnNewPlaylist").addEventListener("click", () => {
    openCustomModal("新建本地歌单", "请输入新歌单名称：", "", async name => {
      if (!name) return;
      await call("library", { action: "create", playlist: name });
      toast(`已创建歌单「${name}」`);
      currentPlaylist = name;
      loadLibrary();
    });
  });

  $("btnDeletePlaylist").addEventListener("click", () => {
    if (!currentPlaylist) return;
    openCustomModal("删除歌单", `确定要删除歌单「${currentPlaylist}」吗？输入 YES 确认：`, "", async val => {
      if (val === "YES") {
        await call("library", { action: "delete", playlist: currentPlaylist, confirmed: true });
        toast(`已删除歌单「${currentPlaylist}」`);
        currentPlaylist = "";
        loadLibrary();
      }
    });
  });

  $("btnPlayAllLib").addEventListener("click", () => {
    const items = Array.from($("libraryTrackList").children);
    if (items.length > 0) items[0].click();
  });

  async function loadRadio(trackId, title) {
    libraryTab = "tidal-radio";
    $("libraryTitle").textContent = `歌曲电台 · ${title}`;
    $("libraryTrackList").hidden = false;
    $("libraryCardsView").hidden = true;
    const res = await call("library", { action: "tidal-radio", id: trackId });
    renderTrackRows(res.tracks || [], $("libraryTrackList"), false);
  }

  // --- Global Search System ---
  const searchSourceButtons = document.querySelectorAll("[data-search-src]");
  searchSourceButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      searchSourceButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      currentSearchSource = btn.dataset.searchSrc;
      executeGlobalSearch();
    });
  });

  $("globalSearchForm").addEventListener("submit", e => {
    e.preventDefault();
    executeGlobalSearch();
  });

  async function executeGlobalSearch() {
    const q = $("globalSearchInput").value.trim();
    if (!q) return;
    const trackList = $("searchTrackList");
    trackList.innerHTML = `<li style="text-align:center; padding:30px; color:var(--muted);">正在全网检索…</li>`;

    try {
      let tracks = [];
      if (currentSearchSource === "all" || currentSearchSource === "tidal") {
        const res = await call("library", { action: "tidal-search", query: q });
        if (res.tracks) tracks.push(...res.tracks);
      }
      if (currentSearchSource === "all" || currentSearchSource === "local") {
        const res = await call("library", { action: "local", query: q });
        if (res.tracks) tracks.push(...res.tracks);
      }
      if (currentSearchSource === "all" || currentSearchSource === "netease") {
        const res = await call("library", { action: "netease-search", query: q }).catch(() => ({ tracks: [] }));
        if (res.tracks) tracks.push(...res.tracks);
      }
      renderTrackRows(tracks, trackList, false);
    } catch (err) {
      trackList.innerHTML = `<li style="text-align:center; padding:30px; color:var(--danger);">${err.message}</li>`;
    }
  }

  // --- Files Explorer System ---
  async function loadFiles() {
    const list = $("filesItemList");
    list.innerHTML = `<li style="text-align:center; padding:30px; color:var(--muted);">正在读取存储目录…</li>`;
    try {
      const res = await call("files", { rootsOnly: false });
      const roots = $("storageRootsList");
      roots.replaceChildren();
      for (const folder of res.folders || []) {
        const d = document.createElement("div");
        d.className = "track-row";
        d.innerHTML = `<svg class="icon"><use href="#i-folder"/></svg><span>${folder.name}</span>`;
        roots.appendChild(d);
      }
      renderTrackRows(res.files || [], list, false);
    } catch (err) {
      list.innerHTML = `<li style="text-align:center; padding:30px; color:var(--danger);">${err.message}</li>`;
    }
  }

  $("btnUploadFiles").addEventListener("click", () => $("uploadFileInput").click());
  $("btnRefreshFiles").addEventListener("click", loadFiles);

  // --- Polling & Connection Loop ---
  async function pollState() {
    if (pollActive) return;
    pollActive = true;
    try {
      const res = await fetchJson("/api/state", {}, 8000);
      renderPlayback(res);
      $("pairPanel").hidden = true;
    } catch (err) {
      if (err.status === 401) {
        requireLanPair("配对已失效，请重新输入配对码");
      } else {
        $("status").textContent = "正在重连…";
        $("status").classList.remove("ok");
      }
    } finally {
      pollActive = false;
      setTimeout(pollState, 1800);
    }
  }

  // --- Init ---
  window.addEventListener("DOMContentLoaded", () => {
    startProgressTicker();
    if (sharedCode) {
      pair(sharedCode);
    } else if (!key) {
      requireLanPair("请先在手机配对");
    } else {
      pollState();
    }
  });

})();
