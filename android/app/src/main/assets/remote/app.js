(() => {
  "use strict";

  const $ = id => document.getElementById(id);

  // --- State & Session ---
  const hashVal = location.hash.slice(1);
  let sharedCode = /^[0-9]{6}$/.test(hashVal) ? hashVal : "";
  let key = (!sharedCode && !hashVal.startsWith("/") && hashVal.length > 10) ? hashVal : "";
  const sessionKey = "tidal-lan-pair";
  const central = document.documentElement.dataset.central === "1";
  let device = "";
  const adminSessionKey = "tidal-central-admin";
  const accountSessionKey = "tidal-player-account";
  let ready = false;
  let lastState = null;
  let pollActive = false;
  let seeking = false;
  let currentVolume = 80;
  let volumeDragging = false;
  let volumeDebounceTimer = null;
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
  let localSubCat = "songs";
  let detailStack = [];
  let currentDetail = null;
  let currentDetailTracks = [];
  let libraryOffset = 0;
  let libraryNext = -1;
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
      sharedCode = "";
      $("pairCode").value = "";
      $("pairPanel").hidden = true;
      toast("设备配对成功！", "i-music");
      pollState();
      loadDownloads();
      applyRouteFromHash("");
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
    $("customModalInput").style.display = "";
    $("customModalInput").value = initialValue || "";
    $("customModalOverlay").classList.add("active");
    $("customModalInput").focus();
    modalCallback = onConfirm;
  }
  function openConfirmModal(title, lead, onConfirm) {
    $("customModalTitle").textContent = title;
    $("customModalLead").textContent = lead;
    $("customModalInput").style.display = "none";
    $("customModalOverlay").classList.add("active");
    modalCallback = () => {
      $("customModalInput").style.display = "";
      if (onConfirm) onConfirm();
    };
  }
  function closeCustomModal() {
    $("customModalOverlay").classList.remove("active");
    $("customModalInput").style.display = "";
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

  // --- Router & Location State Persistence System ---
  const STATE_STORAGE_KEY = "tidal-spa-location-state";
  let isRestoringState = false;
  let ignoreNextHashChange = false;

  function getStoredState() {
    try {
      const raw = localStorage.getItem(STATE_STORAGE_KEY);
      return raw ? JSON.parse(raw) : {};
    } catch { return {}; }
  }

  function saveStoredState(patch) {
    try {
      const cur = getStoredState();
      const updated = { ...cur, ...patch };
      localStorage.setItem(STATE_STORAGE_KEY, JSON.stringify(updated));
    } catch {}
  }

  function saveSpaSnapshot() {
    const cur = getStoredState();
    const data = {
      ...cur,
      view: activeView,
      player: { mode: isLyricsViewStage ? "lyrics" : "cover" },
      library: { tab: libraryTab, sub: localSubCat, playlist: currentPlaylist },
      files: { root: fileCurrentRoot, path: fileCurrentPath },
      downloads: { filter: dlFilter, grouped: dlGrouped },
      scrolls: { ...(cur.scrolls || {}) }
    };
    if (activeView) {
      data.scrolls[activeView] = window.scrollY || document.documentElement.scrollTop || 0;
    }
    saveStoredState(data);
  }

  const scrollPosMap = new Map();
  function recordCurrentScroll() {
    if (activeView && !isRestoringState) {
      const pos = window.scrollY || document.documentElement.scrollTop || 0;
      scrollPosMap.set(activeView, pos);
      saveSpaSnapshot();
    }
  }

  let scrollThrottleTimer = null;
  window.addEventListener("scroll", () => {
    if (scrollThrottleTimer) return;
    scrollThrottleTimer = setTimeout(() => {
      scrollThrottleTimer = null;
      recordCurrentScroll();
    }, 120);
  }, { passive: true });

  function restoreScrollForView(viewId) {
    const state = getStoredState();
    const pos = scrollPosMap.get(viewId) ?? state.scrolls?.[viewId] ?? 0;
    if (pos > 0) {
      isRestoringState = true;
      requestAnimationFrame(() => {
        window.scrollTo({ top: pos, behavior: "instant" });
        setTimeout(() => {
          window.scrollTo({ top: pos, behavior: "instant" });
          isRestoringState = false;
        }, 150);
      });
    }
  }

  function buildRouteHash(viewId) {
    if (viewId === "player") {
      return isLyricsViewStage ? "#/player?mode=lyrics" : "#/player";
    }
    if (viewId === "library") {
      const p = new URLSearchParams();
      if (libraryTab) p.set("tab", libraryTab);
      if (localSubCat && localSubCat !== "songs") p.set("sub", localSubCat);
      if (currentPlaylist) p.set("playlist", currentPlaylist);
      const qs = p.toString();
      return qs ? `#/library?${qs}` : "#/library";
    }
    if (viewId === "files") {
      const p = new URLSearchParams();
      if (fileCurrentRoot) p.set("root", fileCurrentRoot);
      if (fileCurrentPath) p.set("path", fileCurrentPath);
      const qs = p.toString();
      return qs ? `#/files?${qs}` : "#/files";
    }
    if (viewId === "downloads") {
      const p = new URLSearchParams();
      if (dlFilter && dlFilter !== "all") p.set("filter", dlFilter);
      if (!dlGrouped) p.set("grouped", "0");
      const qs = p.toString();
      return qs ? `#/downloads?${qs}` : "#/downloads";
    }
    if (viewId === "search") {
      const q = $("globalSearchInput")?.value?.trim();
      const p = new URLSearchParams();
      if (q) p.set("q", q);
      if (currentSearchSource && currentSearchSource !== "all") p.set("src", currentSearchSource);
      const qs = p.toString();
      return qs ? `#/search?${qs}` : "#/search";
    }
    return `#/${viewId}`;
  }

  function syncRouteToHash(replace = false) {
    if (sharedCode) return;
    const target = buildRouteHash(activeView);
    if (location.hash !== target) {
      if (replace) {
        history.replaceState(null, "", target);
      } else {
        ignoreNextHashChange = true;
        location.hash = target;
      }
    }
    saveSpaSnapshot();
  }

  function applyRouteFromHash(rawHash) {
    if (!rawHash || !rawHash.startsWith("#/")) {
      const snapshot = getStoredState();
      if (snapshot.view && ["player", "library", "search", "files", "downloads"].includes(snapshot.view)) {
        restoreFromSnapshot(snapshot);
      } else {
        showView("player", false);
        syncRouteToHash(true);
      }
      return;
    }

    const clean = rawHash.slice(2);
    const [viewPart, queryPart] = clean.split("?");
    const viewId = viewPart || "player";
    const params = new URLSearchParams(queryPart || "");

    if (viewId === "player") {
      const mode = params.get("mode");
      showView("player", false);
      switchStageMode(mode === "lyrics");
    } else if (viewId === "library") {
      const tab = params.get("tab") || "local";
      const sub = params.get("sub") || "songs";
      const pl = params.get("playlist") || "";
      libraryTab = tab;
      localSubCat = sub;
      currentPlaylist = pl;
      document.querySelectorAll("[data-lib]").forEach(b => b.setAttribute("aria-current", String(b.dataset.lib === tab)));
      document.querySelectorAll("[data-local-cat]").forEach(b => b.setAttribute("aria-current", String(b.dataset.localCat === sub)));
      showView("library", false);
    } else if (viewId === "files") {
      const root = params.get("root");
      const path = params.get("path") || "";
      if (root) fileCurrentRoot = root;
      fileCurrentPath = path;
      showView("files", false);
    } else if (viewId === "downloads") {
      const filter = params.get("filter") || "all";
      const grouped = params.get("grouped") !== "0";
      dlFilter = filter;
      dlGrouped = grouped;
      document.querySelectorAll("[data-dl-filter]").forEach(b => b.setAttribute("aria-current", String(b.dataset.dlFilter === filter)));
      showView("downloads", false);
    } else if (viewId === "search") {
      const q = params.get("q") || "";
      const src = params.get("src") || "all";
      if (q && $("globalSearchInput")) $("globalSearchInput").value = q;
      currentSearchSource = src;
      showView("search", false);
    } else {
      showView(viewId, false);
    }

    saveSpaSnapshot();
  }

  function restoreFromSnapshot(snapshot) {
    if (!snapshot || !snapshot.view) {
      showView("player", false);
      syncRouteToHash(true);
      return;
    }
    const viewId = snapshot.view;
    if (snapshot.player?.mode) {
      isLyricsViewStage = (snapshot.player.mode === "lyrics");
    }
    if (snapshot.library) {
      libraryTab = snapshot.library.tab || "local";
      localSubCat = snapshot.library.sub || "songs";
      currentPlaylist = snapshot.library.playlist || "";
      document.querySelectorAll("[data-lib]").forEach(b => b.setAttribute("aria-current", String(b.dataset.lib === libraryTab)));
      document.querySelectorAll("[data-local-cat]").forEach(b => b.setAttribute("aria-current", String(b.dataset.localCat === localSubCat)));
    }
    if (snapshot.files) {
      if (snapshot.files.root) fileCurrentRoot = snapshot.files.root;
      fileCurrentPath = snapshot.files.path || "";
    }
    if (snapshot.downloads) {
      dlFilter = snapshot.downloads.filter || "all";
      dlGrouped = (snapshot.downloads.grouped !== false);
      document.querySelectorAll("[data-dl-filter]").forEach(b => b.setAttribute("aria-current", String(b.dataset.dlFilter === dlFilter)));
    }
    showView(viewId, false);
    if (viewId === "player") {
      switchStageMode(isLyricsViewStage);
    }
    syncRouteToHash(true);
  }

  window.addEventListener("hashchange", () => {
    if (ignoreNextHashChange) {
      ignoreNextHashChange = false;
      return;
    }
    applyRouteFromHash(location.hash);
  });

  // --- Navigation & View Switching ---
  function showView(viewId, updateHash = true) {
    if (activeView && activeView !== viewId) {
      recordCurrentScroll();
    }
    activeView = viewId;
    document.body.dataset.view = viewId;
    $("viewPlayer").hidden = viewId !== "player";
    $("viewLibrary").hidden = viewId !== "library";
    $("viewSearch").hidden = viewId !== "search";
    $("viewFiles").hidden = viewId !== "files";
    $("viewDownloads").hidden = viewId !== "downloads";

    $("tabNavPlayer").setAttribute("aria-current", String(viewId === "player"));
    $("tabNavLibrary").setAttribute("aria-current", String(viewId === "library"));
    $("tabNavSearch").setAttribute("aria-current", String(viewId === "search"));
    $("tabNavFiles").setAttribute("aria-current", String(viewId === "files"));
    $("tabNavDownloads").setAttribute("aria-current", String(viewId === "downloads"));

    // 处于播放主界面时，隐藏底部迷你播放条，避免高度重复与溢出
    const miniEl = $("miniPlayer");
    if (miniEl) miniEl.hidden = (viewId === "player");

    if (updateHash) {
      syncRouteToHash();
    }

    if (viewId === "library") {
      loadLibrary();
      pollFill();
    }
    if (viewId === "files") loadFiles();
    if (viewId === "downloads") loadDownloads();
    if (viewId === "player") {
      restoreScrollForView("player");
    }
  }

  $("tabNavPlayer").addEventListener("click", () => showView("player"));
  $("tabNavLibrary").addEventListener("click", () => showView("library"));
  $("tabNavSearch").addEventListener("click", () => {
    showView("search");
    setTimeout(() => $("globalSearchInput").focus(), 100);
  });
  $("tabNavFiles").addEventListener("click", () => showView("files"));

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

  function buildLyricsFragment(lyrics) {
    const frag = document.createDocumentFragment();
    lyrics.forEach((item, idx) => {
      const div = document.createElement("div");
      div.className = "lyric-line";
      div.dataset.index = idx;
      div.textContent = item.text;
      div.addEventListener("click", () => {
        call("seek", { ms: Math.round(item.time * 1000) });
      });
      frag.appendChild(div);
    });
    return frag;
  }

  async function fetchAndRenderLyrics(track) {
    if (!track) return;
    if (currentLyricsTrackId === track.id && currentLyrics.length > 0) {
      if (!$("stageLyricsList").hasChildNodes()) {
        $("stageLyricsList").appendChild(buildLyricsFragment(currentLyrics));
        $("stageLyricsEmpty").hidden = true;
      }
      if ($("sideLyricsList") && !$("sideLyricsList").hasChildNodes()) {
        $("sideLyricsList").appendChild(buildLyricsFragment(currentLyrics));
        $("sideLyricsEmpty").hidden = true;
      }
      if (!$("fsLyricsList").hasChildNodes()) {
        $("fsLyricsList").appendChild(buildLyricsFragment(currentLyrics));
        $("fsLyricsEmpty").hidden = true;
      }
      return;
    }
    currentLyricsTrackId = track.id;
    lyricsTrack = track;
    activeLyricIndex = -1;

    const loadingText = "正在读取动态歌词…";
    $("btnFsLyricsDownload").hidden = true;
    $("btnStageLyricsDownload").hidden = true;
    if ($("btnSideLyricsDownload")) $("btnSideLyricsDownload").hidden = true;
    $("fsLyricsEmpty").textContent = loadingText;
    $("stageLyricsEmpty").textContent = loadingText;
    if ($("sideLyricsEmpty")) $("sideLyricsEmpty").textContent = loadingText;
    $("fsLyricsEmpty").hidden = false;
    $("stageLyricsEmpty").hidden = false;
    if ($("sideLyricsEmpty")) $("sideLyricsEmpty").hidden = false;
    $("fsLyricsList").replaceChildren();
    $("stageLyricsList").replaceChildren();
    if ($("sideLyricsList")) $("sideLyricsList").replaceChildren();

    try {
      const res = await call("library", { action: "lyrics", id: track.id });
      const lrcText = res.lyrics || "";
      currentLyrics = parseLrc(lrcText);
      if (!currentLyrics.length) {
        const emptyText = lrcText ? lrcText : "暂无动态歌词，轻触屏幕欣赏音乐";
        $("fsLyricsEmpty").textContent = emptyText;
        $("stageLyricsEmpty").textContent = emptyText;
        if ($("sideLyricsEmpty")) $("sideLyricsEmpty").textContent = emptyText;
        $("fsLyricsEmpty").hidden = false;
        $("stageLyricsEmpty").hidden = false;
        if ($("sideLyricsEmpty")) $("sideLyricsEmpty").hidden = false;
        const canDownload = track.kind === "local";
        $("btnFsLyricsDownload").hidden = !canDownload;
        $("btnStageLyricsDownload").hidden = !canDownload;
        if ($("btnSideLyricsDownload")) $("btnSideLyricsDownload").hidden = !canDownload;
        return;
      }
      $("fsLyricsEmpty").hidden = true;
      $("stageLyricsEmpty").hidden = true;
      if ($("sideLyricsEmpty")) $("sideLyricsEmpty").hidden = true;
      $("fsLyricsList").appendChild(buildLyricsFragment(currentLyrics));
      $("stageLyricsList").appendChild(buildLyricsFragment(currentLyrics));
      if ($("sideLyricsList")) $("sideLyricsList").appendChild(buildLyricsFragment(currentLyrics));

      if (lastState) {
        let pos = (lastState.position || 0) / 1000;
        syncLyricsPosition(pos);
      }
    } catch {
      const errText = track.kind === "local" ? "这首歌还没有歌词文件" : "未能获取歌词";
      $("fsLyricsEmpty").textContent = errText;
      $("stageLyricsEmpty").textContent = errText;
      if ($("sideLyricsEmpty")) $("sideLyricsEmpty").textContent = errText;
      $("fsLyricsEmpty").hidden = false;
      $("stageLyricsEmpty").hidden = false;
      if ($("sideLyricsEmpty")) $("sideLyricsEmpty").hidden = false;
      const canDownload = track.kind === "local";
      $("btnFsLyricsDownload").hidden = !canDownload;
      $("btnStageLyricsDownload").hidden = !canDownload;
      if ($("btnSideLyricsDownload")) $("btnSideLyricsDownload").hidden = !canDownload;
    }
  }

  let lyricsTrack = null;
  $("btnFsLyricsDownload").addEventListener("click", () => {
    if (lyricsTrack) openLyricsPicker(lyricsTrack);
  });

  function syncLyricsPosition(currentTime) {
    if (!currentLyrics.length) return;
    const isSideVisible = $("sideLyricsContent") && !$("sideLyricsContent").hidden;
    if (!isLyricsViewStage && !isLyricsViewFs && !isSideVisible) return;
    let newIndex = -1;
    for (let i = 0; i < currentLyrics.length; i++) {
      if (currentLyrics[i].time <= currentTime + 0.25) newIndex = i;
      else break;
    }
    if (newIndex === activeLyricIndex) return;
    activeLyricIndex = newIndex;

    function highlightContainer(container) {
      if (!container) return;
      const lines = container.children;
      for (let i = 0; i < lines.length; i++) {
        const isActive = i === activeLyricIndex;
        lines[i].classList.toggle("active", isActive);
        if (isActive) {
          lines[i].scrollIntoView({ behavior: "smooth", block: "center" });
        }
      }
    }

    if (isLyricsViewStage) highlightContainer($("stageLyricsList"));
    if (isSideVisible) highlightContainer($("sideLyricsList"));
    if (isLyricsViewFs) highlightContainer($("fsLyricsList"));
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

    // Volume
    if (state.volume !== undefined && !volumeDragging && Math.abs(state.volume - currentVolume) >= 1) {
      currentVolume = Math.round(state.volume);
      $("volumeSlider").value = String(currentVolume);
      $("fsVolumeSlider").value = String(currentVolume);
      $("miniVolumeSlider").value = String(currentVolume);
      updateVolumeIcon(currentVolume);
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
  function sendVolume(v) {
    const val = Math.max(0, Math.min(100, Math.round(v)));
    call("volume", { value: val });
  }

  function handleVolumeInput(v) {
    volumeDragging = true;
    currentVolume = Math.max(0, Math.min(100, Math.round(v)));
    $("volumeSlider").value = String(currentVolume);
    $("fsVolumeSlider").value = String(currentVolume);
    $("miniVolumeSlider").value = String(currentVolume);
    updateVolumeIcon(currentVolume);

    clearTimeout(volumeDebounceTimer);
    volumeDebounceTimer = setTimeout(() => {
      sendVolume(currentVolume);
    }, 80);
  }

  function handleVolumeChange(v) {
    clearTimeout(volumeDebounceTimer);
    currentVolume = Math.max(0, Math.min(100, Math.round(v)));
    $("volumeSlider").value = String(currentVolume);
    $("fsVolumeSlider").value = String(currentVolume);
    $("miniVolumeSlider").value = String(currentVolume);
    updateVolumeIcon(currentVolume);
    sendVolume(currentVolume);
    setTimeout(() => {
      volumeDragging = false;
    }, 300);
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

  // --- Side Queue / Side Lyrics / Side Search Tab Switching ---
  function switchSideTab(tab) {
    $("tabSideQueue").setAttribute("aria-current", String(tab === "queue"));
    $("tabSideLyrics").setAttribute("aria-current", String(tab === "lyrics"));
    $("tabSideSearch").setAttribute("aria-current", String(tab === "search"));
    $("sideQueueContent").hidden = tab !== "queue";
    $("sideLyricsContent").hidden = tab !== "lyrics";
    $("sideSearchContent").hidden = tab !== "search";
    if (tab === "lyrics" && lastState) {
      const track = lastState.queue?.[lastState.index];
      if (track) fetchAndRenderLyrics(track);
      let pos = (lastState.position || 0) / 1000;
      activeLyricIndex = -1;
      syncLyricsPosition(pos);
    }
    if (activeView === "player") syncRouteToHash();
    if (tab === "search") {
      $("sideSearchInput").focus();
    }
  }

  $("tabSideQueue").addEventListener("click", () => switchSideTab("queue"));
  $("tabSideLyrics").addEventListener("click", () => switchSideTab("lyrics"));
  $("tabSideSearch").addEventListener("click", () => switchSideTab("search"));
  if ($("btnSideLyricsDownload")) {
    $("btnSideLyricsDownload").addEventListener("click", () => {
      if (lyricsTrack) openLyricsPicker(lyricsTrack);
    });
  }

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
  function formatTotalDuration(seconds) {
    if (!seconds || seconds <= 0) return "";
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    if (h > 0) return `${h} 小时 ${m} 分钟`;
    return `${m} 分钟`;
  }

  const libNavButtons = document.querySelectorAll("[data-lib]");
  libNavButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      libNavButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      libraryTab = btn.dataset.lib;
      currentCollection = null;
      currentGroup = null;
      currentPlaylist = "";
      detailStack = [];
      currentDetail = null;
      currentDetailTracks = [];
      syncRouteToHash();
      loadLibrary();
    });
  });

  const localSubnavButtons = document.querySelectorAll("[data-local-cat]");
  localSubnavButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      localSubnavButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      localSubCat = btn.dataset.localCat;
      $("localCategorySelect").value = "";
      detailStack = [];
      currentDetail = null;
      currentDetailTracks = [];
      currentPlaylist = "";
      syncRouteToHash();
      loadLibrary();
    });
  });

  $("localCategorySelect").addEventListener("change", e => {
    const val = e.target.value;
    if (!val) return;
    localSubnavButtons.forEach(b => b.setAttribute("aria-current", "false"));
    localSubCat = val;
    detailStack = [];
    currentDetail = null;
    currentDetailTracks = [];
    currentPlaylist = "";
    syncRouteToHash();
    loadLibrary();
  });

  async function playTracks(tracks, shuffle = false) {
    if (!tracks || !tracks.length) return;
    try {
      const ids = tracks.map(t => t.id);
      await call("library", { action: "play-list", ids, shuffle });
      toast(shuffle ? "已开始随机播放" : `开始播放「${tracks[0].title}」`);
    } catch (err) {
      toast(`播放失败：${err.message}`, "i-trash");
    }
  }

  async function loadLibrary(append = false) {
    const rev = ++libraryRevision;
    document.querySelector(".library-header").hidden = false;
    document.querySelector(".library-subnav-bar").hidden = false;
    $("libraryDetailView").hidden = true;
    $("libraryCardsView").hidden = true;
    $("libraryTrackList").hidden = false;
    $("btnLibBack").hidden = !currentCollection && !detailStack.length;
    $("btnDownloadAlbum").hidden = true;
    $("localControls").hidden = libraryTab !== "local";
    $("localSubnavPills").hidden = libraryTab !== "local";
    $("btnScanLibrary").hidden = libraryTab !== "local";
    $("tidalControls").hidden = libraryTab !== "tidal-collections";

    libraryNext = -1;
    $("btnLibMore").hidden = true;
    if (!append) {
      $("libraryCountText").textContent = "";
      libraryOffset = 0;
      $("libraryTrackList").replaceChildren();
      $("libraryCardsView").replaceChildren();
    }

    try {
      if (libraryTab === "local" && lyricsMissingOnly) {
        $("libraryTitle").textContent = "缺少歌词的歌曲";
        $("libraryTrackList").hidden = false;
        $("libraryCardsView").hidden = true;
        const res = await call("library", {
          action: "lyrics-missing",
          query: $("librarySearchInput").value.trim(),
          offset: libraryOffset
        });
        if (rev !== libraryRevision) return;
        $("libraryCountText").textContent = `${res.total || 0} 首没有 .lrc 歌词文件`;
        renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
        libraryNext = res.next ?? -1;
      }
      else if (libraryTab === "local") {
        $("localSort").hidden = localSubCat !== "songs";
        $("playlistSelect").hidden = localSubCat !== "songs" && localSubCat !== "playlists";
        $("btnNewPlaylist").hidden = localSubCat !== "playlists";
        $("btnDeletePlaylist").hidden = !currentPlaylist || localSubCat !== "songs";

        if (localSubCat === "songs") {
          $("libraryTitle").textContent = currentPlaylist ? `歌单 · ${currentPlaylist}` : "本地歌曲";
          $("libraryTrackList").hidden = false;
          $("libraryCardsView").hidden = true;
          const res = await call("library", {
            action: "local",
            query: $("librarySearchInput").value.trim(),
            playlist: currentPlaylist,
            sort: $("localSort").value,
            offset: libraryOffset
          });
          if (rev !== libraryRevision) return;

          if (res.playlists) {
            localPlaylistsCache = res.playlists;
            const sel = $("playlistSelect");
            sel.innerHTML = `<option value="">全部歌曲</option>` + res.playlists.map(p => `<option value="${p}">${p}</option>`).join("");
            sel.value = currentPlaylist;
          }

          if (res.total !== undefined) {
            $("libraryCountText").textContent = `共 ${res.total} 首`;
          }
          renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
          libraryNext = res.next ?? -1;
        }
        else if (localSubCat === "albums") {
          $("libraryTitle").textContent = "本地专辑";
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          const res = await call("library", {
            action: "local",
            category: 1,
            query: $("librarySearchInput").value.trim(),
            offset: libraryOffset
          });
          if (rev !== libraryRevision) return;
          if (res.total !== undefined) {
            $("libraryCountText").textContent = `共 ${res.total} 张专辑`;
          }
          renderAlbumGridCards(res.groups || [], $("libraryCardsView"));
          libraryNext = res.next ?? -1;
        }
        else if (localSubCat === "artists") {
          $("libraryTitle").textContent = "本地艺人";
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          const res = await call("library", {
            action: "local",
            category: 3,
            query: $("librarySearchInput").value.trim(),
            offset: libraryOffset
          });
          if (rev !== libraryRevision) return;
          if (res.total !== undefined) {
            $("libraryCountText").textContent = `共 ${res.total} 位艺人`;
          }
          renderArtistGridCards(res.groups || [], $("libraryCardsView"));
          libraryNext = res.next ?? -1;
        }
        else if (localSubCat === "playlists") {
          $("libraryTitle").textContent = "本地歌单";
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          const res = await call("library", { action: "local", offset: 0 });
          if (rev !== libraryRevision) return;
          if (res.playlists) localPlaylistsCache = res.playlists;
          renderPlaylistGridCards(localPlaylistsCache, $("libraryCardsView"));
        }
        else if (localSubCat === "4" || localSubCat === "5" || localSubCat === "6") {
          const catMap = { "4": "专辑艺人", "5": "音乐流派", "6": "发行年份" };
          const name = catMap[localSubCat] || "分类";
          $("libraryTitle").textContent = `本地 · ${name}`;
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          const res = await call("library", {
            action: "local",
            category: parseInt(localSubCat),
            query: $("librarySearchInput").value.trim(),
            offset: libraryOffset
          });
          if (rev !== libraryRevision) return;
          if (res.total !== undefined) {
            $("libraryCountText").textContent = `共 ${res.total} 个${name}`;
          }
          renderGeneralGroupCards(res.groups || [], name, $("libraryCardsView"));
          libraryNext = res.next ?? -1;
        }
      }
      else if (libraryTab === "recent") {
        $("libraryTitle").textContent = "最近播放";
        $("libraryTrackList").hidden = false;
        $("libraryCardsView").hidden = true;
        const res = await call("library", { action: "recent" });
        if (rev !== libraryRevision) return;
        renderTrackRows(res.tracks || [], $("libraryTrackList"), false);
      }
      else if (libraryTab === "tidal-favorites") {
        $("libraryTitle").textContent = "TIDAL 收藏歌曲";
        $("libraryTrackList").hidden = false;
        $("libraryCardsView").hidden = true;
        const res = await call("library", { action: "tidal-favorites", offset: libraryOffset });
        if (rev !== libraryRevision) return;
        renderTrackRows(res.tracks || [], $("libraryTrackList"), append);
        libraryNext = res.next ?? -1;
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
          $("libraryTrackList").hidden = false;
          $("libraryCardsView").hidden = true;
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
    if (rev === libraryRevision) {
      $("btnLibMore").hidden = libraryNext < 0;
      restoreScrollForView("library");
    }
  }

  $("btnLibMore").addEventListener("click", () => {
    if (libraryNext < 0) return;
    libraryOffset = libraryNext;
    loadLibrary(true);
  });
  let libraryFilterTimer = null;
  $("librarySearchInput").addEventListener("input", () => {
    clearTimeout(libraryFilterTimer);
    libraryFilterTimer = setTimeout(() => {
      if (!currentCollection && !detailStack.length) loadLibrary();
    }, 350);
  });

  // The device plays its own copy of a song, found by id; only NetEase songs, which it cannot
  // look up by id, carry their title, artist and cover.
  function songRequest(track, mode) {
    const request = { action: "song", id: track.id, mode };
    if (track.kind === "netease") {
      request.track = { id: track.id, title: track.title, artist: track.artist, album: track.album, cover: track.cover };
    }
    return request;
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
        call("library", songRequest(track, "play"));
      });
      frag.appendChild(li);
    });
    container.appendChild(frag);
  }

  function createTrackRow(track, onPlay, options = {}) {
    const li = document.createElement("li");
    li.className = "track-row";
    const isAlbum = !!options.isAlbumView;
    const trackNum = options.trackIndex ?? track.trackNumber;

    li.innerHTML = `
      <div class="track-num" ${!isAlbum ? "hidden" : ""}></div>
      <div class="track-thumb" ${isAlbum ? "hidden" : ""}><img alt=""></div>
      <div class="track-info">
        <div class="track-title-wrap"><span class="track-title"></span></div>
        <div class="track-subtitle"></div>
      </div>
      <div class="track-duration"></div>
      <div class="track-actions">
        <button type="button" class="icon-btn btn-dl-track hover-only" title="下载到手机" hidden><svg class="icon"><use href="#i-download"/></svg></button>
        <button type="button" class="icon-btn btn-lyrics hover-only" title="歌词" hidden><svg class="icon"><use href="#i-lyrics"/></svg></button>
        <button type="button" class="icon-btn btn-add-next hover-only" title="下一首播放"><svg class="icon"><use href="#i-next"/></svg></button>
        <button type="button" class="icon-btn btn-add-fav hover-only" title="喜欢"><svg class="icon"><use href="#i-heart"/></svg></button>
        <button type="button" class="icon-btn btn-add-playlist hover-only" title="加入歌单"><svg class="icon"><use href="#i-plus"/></svg></button>
      </div>
    `;

    if (isAlbum) {
      li.querySelector(".track-num").textContent = trackNum || "";
    } else {
      const cover = getArtworkUrl(track, 160);
      applyArtwork(li.querySelector(".track-thumb"), li.querySelector(".track-thumb img"), cover, track.title);
    }

    const titleWrap = li.querySelector(".track-title-wrap");
    li.querySelector(".track-title").textContent = track.title || "未知曲目";

    if (track.lossless) {
      const badge = document.createElement("span");
      badge.className = "badge-tag";
      badge.textContent = "无损";
      titleWrap.appendChild(badge);
    }

    const subtitleEl = li.querySelector(".track-subtitle");
    let subText = "";
    if (isAlbum) {
      subText = track.artist || "";
    } else {
      subText = `${track.artist || "未知艺人"}${track.album ? " · " + track.album : ""}`;
    }
    subtitleEl.textContent = subText;

    if (track.kind === "local" && track.lyrics && track.lyrics !== "saved") {
      const state = document.createElement("span");
      state.className = `lyrics-state ${track.lyrics}`;
      state.textContent = track.lyrics === "missing" ? " · 缺歌词" : " · 无歌词";
      subtitleEl.appendChild(state);
    }

    if (track.duration && track.duration > 0) {
      li.querySelector(".track-duration").textContent = formatTime(track.duration);
    }

    const canDownload = !track.isLocal && track.id > 0 && track.kind !== "netease" && track.kind !== "local";
    if (canDownload) {
      const btnDl = li.querySelector(".btn-dl-track");
      btnDl.hidden = false;
      btnDl.addEventListener("click", () => {
        toast("正在发送下载请求…");
        call("download", { ids: [track.id], quality: "BEST" })
          .then(() => toast(`已将「${track.title}」加入手机下载队列`, "i-music"))
          .catch(err => toast(`下载失败：${err.message}`, "i-trash"));
      });
    }

    if (track.kind === "local") {
      const btnLyrics = li.querySelector(".btn-lyrics");
      btnLyrics.hidden = false;
      if (track.lyrics !== "saved") {
        btnLyrics.classList.add("needed");
        btnLyrics.classList.remove("hover-only");
      }
      btnLyrics.title = track.lyrics === "saved" ? "更换歌词" : "下载歌词";
      btnLyrics.addEventListener("click", () => openLyricsPicker(track));
    }

    li.querySelector(".btn-add-next").addEventListener("click", () => {
      call("library", songRequest(track, "next"));
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

    li.addEventListener("click", e => {
      if (e.target.closest(".track-actions")) return;
      onPlay();
    });

    return li;
  }

  function renderAlbumTracks(tracks, container) {
    container.replaceChildren();
    if (!tracks.length) {
      container.innerHTML = `<li style="text-align:center; padding:40px; color:var(--muted);">暂无曲目</li>`;
      return;
    }

    const discSet = new Set(tracks.map(t => t.discNumber || 1));
    const hasMultipleDiscs = discSet.size > 1;

    let currentDisc = null;
    const frag = document.createDocumentFragment();

    tracks.forEach((track, index) => {
      const discNum = track.discNumber || 1;
      if (hasMultipleDiscs && discNum !== currentDisc) {
        currentDisc = discNum;
        const discLi = document.createElement("li");
        discLi.className = "disc-divider";
        discLi.innerHTML = `<svg class="icon"><use href="#i-music"/></svg> <span></span>`;
        discLi.querySelector("span").textContent = `光盘 ${discNum}`;
        frag.appendChild(discLi);
      }

      const row = createTrackRow(track, () => {
        call("library", songRequest(track, "play"));
      }, { isAlbumView: true, trackIndex: track.trackNumber || (index + 1) });

      frag.appendChild(row);
    });

    container.appendChild(frag);
  }

  function renderAlbumGridCards(albums, container, onOpen) {
    container.replaceChildren();
    if (!albums.length) {
      container.innerHTML = `<div style="grid-column:1/-1; text-align:center; padding:40px; color:var(--muted);">暂无专辑</div>`;
      return;
    }
    const frag = document.createDocumentFragment();
    albums.forEach(album => {
      const card = document.createElement("div");
      card.className = "card-item";
      card.innerHTML = `
        <div class="card-artwork">
          <img alt="">
          <div class="card-play-overlay">
            <button type="button" class="btn-card-play" title="播放专辑"><svg class="icon"><use href="#i-play"/></svg></button>
          </div>
        </div>
        <div class="card-title"></div>
        <div class="card-subtitle"></div>
      `;
      card.querySelector(".card-title").textContent = album.title || "未知专辑";
      const subParts = [];
      if (album.detail || album.artist) subParts.push(album.detail || album.artist);
      if (album.year) subParts.push(album.year);
      if (album.count) subParts.push(`${album.count}首`);
      card.querySelector(".card-subtitle").textContent = subParts.join(" · ");

      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), getArtworkUrl(album, 320), album.title);

      const playBtn = card.querySelector(".btn-card-play");
      playBtn.addEventListener("click", async e => {
        e.stopPropagation();
        toast(`正在播放「${album.title}」…`);
        try {
          if (album.isLocal !== false && album.kind !== "tidal" && album.kind !== "netease") {
            const res = await call("library", { action: "local", category: 1, group: album.id, limit: 500 });
            playTracks(res.tracks || [], false);
          } else {
            const action = album.kind === "netease" ? "netease-collection" : "tidal-collection";
            const res = await call("library", { action, kind: "ALBUM", collection: String(album.id || album.uuid) });
            playTracks(res.tracks || [], false);
          }
        } catch (err) {
          toast(`播放失败：${err.message}`, "i-trash");
        }
      });

      card.addEventListener("click", () => {
        if (onOpen) onOpen(album);
        else openAlbumDetail(album);
      });

      frag.appendChild(card);
    });
    container.appendChild(frag);
  }

  function renderArtistGridCards(artists, container, onOpen) {
    container.replaceChildren();
    if (!artists.length) {
      container.innerHTML = `<div style="grid-column:1/-1; text-align:center; padding:40px; color:var(--muted);">暂无艺人</div>`;
      return;
    }
    const frag = document.createDocumentFragment();
    artists.forEach(artist => {
      const card = document.createElement("div");
      card.className = "card-item";
      card.innerHTML = `
        <div class="card-artwork artist">
          <img alt="">
        </div>
        <div class="card-title" style="text-align:center;"></div>
        <div class="card-subtitle" style="text-align:center;"></div>
      `;
      const name = artist.title || artist.name || "未知艺人";
      card.querySelector(".card-title").textContent = name;
      card.querySelector(".card-subtitle").textContent = artist.count ? `${artist.count} 首歌曲` : "艺人";

      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), getArtworkUrl(artist, 320), name);

      card.addEventListener("click", () => {
        if (onOpen) onOpen(artist);
        else openArtistDetail(artist);
      });

      frag.appendChild(card);
    });
    container.appendChild(frag);
  }

  function renderPlaylistGridCards(playlists, container) {
    container.replaceChildren();
    const frag = document.createDocumentFragment();

    const allCard = document.createElement("div");
    allCard.className = "card-item";
    allCard.innerHTML = `
      <div class="card-artwork">
        <div class="card-play-overlay">
          <button type="button" class="btn-card-play"><svg class="icon"><use href="#i-play"/></svg></button>
        </div>
      </div>
      <div class="card-title">全部歌曲</div>
      <div class="card-subtitle">本地曲库完整列表</div>
    `;
    allCard.querySelector(".card-artwork").dataset.letter = "♪";
    allCard.addEventListener("click", () => {
      localSubCat = "songs";
      currentPlaylist = "";
      document.querySelectorAll("[data-local-cat]").forEach(b => {
        b.setAttribute("aria-current", String(b.dataset.localCat === "songs"));
      });
      syncRouteToHash();
      loadLibrary();
    });
    frag.appendChild(allCard);

    (playlists || []).forEach(name => {
      const card = document.createElement("div");
      card.className = "card-item";
      card.innerHTML = `
        <div class="card-artwork">
          <img alt="">
          <div class="card-play-overlay">
            <button type="button" class="btn-card-play"><svg class="icon"><use href="#i-play"/></svg></button>
          </div>
        </div>
        <div class="card-title"></div>
        <div class="card-subtitle" style="display:flex; justify-content:space-between; align-items:center;">
          <span>歌单</span>
          <button type="button" class="icon-btn btn-del-p hover-only" style="width:24px; height:24px; color:var(--danger);" title="删除歌单"><svg class="icon"><use href="#i-trash"/></svg></button>
        </div>
      `;
      card.querySelector(".card-title").textContent = name;
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), "", name);

      card.querySelector(".btn-del-p").addEventListener("click", e => {
        e.stopPropagation();
        openCustomModal("删除歌单", `确定要删除歌单「${name}」吗？输入 YES 确认：`, "", async val => {
          if (val === "YES") {
            await call("library", { action: "delete", playlist: name, confirmed: true });
            toast(`已删除歌单「${name}」`);
            loadLibrary();
          }
        });
      });

      card.addEventListener("click", () => {
        localSubCat = "songs";
        currentPlaylist = name;
        document.querySelectorAll("[data-local-cat]").forEach(b => {
          b.setAttribute("aria-current", String(b.dataset.localCat === "songs"));
        });
        syncRouteToHash();
        loadLibrary();
      });

      frag.appendChild(card);
    });

    container.appendChild(frag);
  }

  function renderGeneralGroupCards(groups, categoryName, container) {
    container.replaceChildren();
    if (!groups.length) {
      container.innerHTML = `<div style="grid-column:1/-1; text-align:center; padding:40px; color:var(--muted);">暂无内容</div>`;
      return;
    }
    const frag = document.createDocumentFragment();
    groups.forEach(g => {
      const card = document.createElement("div");
      card.className = "card-item";
      card.innerHTML = `
        <div class="card-artwork">
          <img alt="">
        </div>
        <div class="card-title"></div>
        <div class="card-subtitle"></div>
      `;
      card.querySelector(".card-title").textContent = g.title || "未知";
      card.querySelector(".card-subtitle").textContent = `${g.count} 首 · ${categoryName}`;
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), getArtworkUrl(g, 320), g.title);

      card.addEventListener("click", async () => {
        detailStack.push({ type: "group", data: g, categoryName });
        currentDetail = { type: "group", data: g, categoryName };

        $("libraryDetailView").hidden = false;
        $("libraryCardsView").hidden = true;
        $("libraryTrackList").hidden = true;
        $("btnLibMore").hidden = true;
        $("btnLibBack").hidden = false;
        $("artistAlbumsSection").hidden = true;
        $("detailTracksTitle").hidden = false;
        $("detailTracksTitle").textContent = `${categoryName} · ${g.title}`;

        const heroArt = $("albumHeroArtwork");
        heroArt.classList.remove("artist");
        applyArtwork(heroArt, heroArt.querySelector("img"), getArtworkUrl(g, 640), g.title);
        $("albumHeroTag").textContent = categoryName;
        $("albumHeroTitle").textContent = g.title;
        $("albumHeroSubtitle").textContent = "";
        $("albumHeroMeta").textContent = `${g.count} 首歌曲`;
        $("btnDetailDownload").hidden = true;

        const res = await call("library", {
          action: "local",
          category: parseInt(localSubCat),
          group: g.id,
          limit: 500
        });
        currentDetailTracks = res.tracks || [];
        renderTrackRows(currentDetailTracks, $("detailTrackList"), false);
      });

      frag.appendChild(card);
    });
    container.appendChild(frag);
  }

  function renderCollectionCards(collections, kind, container, onOpen) {
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
        <div class="card-title"></div>
        <div class="card-subtitle"></div>
      `;
      card.querySelector(".card-title").textContent = item.title || item.name || "";
      const kindName = isArtist ? "艺人" : kind === "PLAYLIST" ? "歌单" : "专辑";
      card.querySelector(".card-subtitle").textContent =
        (item.sourceName ? item.sourceName + " · " : "") + (item.subtitle || kindName);
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), cover, item.title || item.name);
      card.addEventListener("click", () => {
        if (onOpen) return onOpen(item);
        if (kind === "ALBUM") {
          openAlbumDetail({ ...item, isLocal: false });
        } else if (kind === "ARTIST") {
          openArtistDetail({ ...item, isLocal: false });
        } else {
          currentCollection = item;
          openCollectionTracks(item, kind);
        }
      });
      frag.appendChild(card);
    });
    container.appendChild(frag);
  }

  async function openAlbumDetail(album) {
    const rev = ++libraryRevision;
    detailStack.push({ type: "album", data: album });
    currentDetail = { type: "album", data: album };

    document.querySelector(".library-header").hidden = true;
    document.querySelector(".library-subnav-bar").hidden = true;
    $("libraryDetailView").hidden = false;
    $("libraryCardsView").hidden = true;
    $("libraryTrackList").hidden = true;
    $("btnLibMore").hidden = true;
    $("artistAlbumsSection").hidden = true;
    $("detailTracksTitle").hidden = true;
    $("detailTrackList").replaceChildren();

    const heroArt = $("albumHeroArtwork");
    const heroImg = heroArt.querySelector("img");
    heroArt.classList.remove("artist");
    applyArtwork(heroArt, heroImg, getArtworkUrl(album, 640), album.title);

    const isLocal = album.isLocal !== false && album.kind !== "tidal" && album.kind !== "netease";
    $("albumHeroTag").textContent = isLocal ? "专辑 · 本地音乐" : `专辑 · ${album.sourceName || (album.kind === "netease" ? "网易云" : "TIDAL")}`;
    $("albumHeroTitle").textContent = album.title || "未知专辑";

    const artistWrap = $("albumHeroSubtitle");
    artistWrap.replaceChildren();
    const artistName = album.artist || album.detail || "未知艺人";
    if (artistName && artistName !== "未知艺人") {
      const artistLink = document.createElement("a");
      artistLink.textContent = artistName;
      artistLink.addEventListener("click", e => {
        e.preventDefault();
        openArtistDetail({ id: album.artist_id || 0, title: artistName, isLocal });
      });
      artistWrap.appendChild(artistLink);
    } else {
      artistWrap.textContent = artistName;
    }

    $("albumHeroMeta").textContent = "正在加载曲目…";
    $("btnDetailDownload").hidden = isLocal || !album.id || album.kind === "netease";

    try {
      let tracks = [];
      if (isLocal) {
        const res = await call("library", {
          action: "local",
          category: 1,
          group: album.id,
          limit: 500
        });
        if (rev !== libraryRevision) return;
        tracks = res.tracks || [];
      } else {
        const action = album.kind === "netease" ? "netease-collection" : "tidal-collection";
        const res = await call("library", {
          action,
          kind: "ALBUM",
          collection: String(album.id || album.uuid)
        });
        if (rev !== libraryRevision) return;
        tracks = res.tracks || [];
      }
      currentDetailTracks = tracks;

      let totalDuration = 0;
      for (const t of tracks) if (t.duration) totalDuration += t.duration;
      const durStr = formatTotalDuration(totalDuration);
      const metaParts = [];
      if (album.year) metaParts.push(`${album.year}年`);
      metaParts.push(`${tracks.length} 首歌曲`);
      if (durStr) metaParts.push(durStr);
      $("albumHeroMeta").textContent = metaParts.join(" · ");

      renderAlbumTracks(tracks, $("detailTrackList"));
    } catch (err) {
      $("albumHeroMeta").textContent = `加载失败：${err.message}`;
      toast(err.message, "i-trash");
    }
  }

  async function openArtistDetail(artist) {
    const rev = ++libraryRevision;
    detailStack.push({ type: "artist", data: artist });
    currentDetail = { type: "artist", data: artist };

    document.querySelector(".library-header").hidden = true;
    document.querySelector(".library-subnav-bar").hidden = true;
    $("libraryDetailView").hidden = false;
    $("libraryCardsView").hidden = true;
    $("libraryTrackList").hidden = true;
    $("btnLibMore").hidden = true;
    $("artistAlbumsSection").hidden = true;
    $("detailTracksTitle").hidden = false;
    $("detailTracksTitle").textContent = "全部歌曲";
    $("detailTrackList").replaceChildren();
    $("artistAlbumsGrid").replaceChildren();

    const heroArt = $("albumHeroArtwork");
    const heroImg = heroArt.querySelector("img");
    heroArt.classList.add("artist");
    applyArtwork(heroArt, heroImg, getArtworkUrl(artist, 640), artist.title || artist.name);

    const isLocal = artist.isLocal !== false && artist.kind !== "tidal" && artist.kind !== "netease";
    $("albumHeroTag").textContent = isLocal ? "艺人 · 本地音乐" : `艺人 · ${artist.sourceName || (artist.kind === "netease" ? "网易云" : "TIDAL")}`;
    $("albumHeroTitle").textContent = artist.title || artist.name || "未知艺人";
    $("albumHeroSubtitle").replaceChildren();
    $("albumHeroMeta").textContent = "正在加载专辑与歌曲…";
    $("btnDetailDownload").hidden = true;

    try {
      let albums = [];
      let tracks = [];
      if (isLocal) {
        const artistName = artist.title || artist.name || "";
        const [albumRes, trackRes] = await Promise.all([
          call("library", { action: "local", category: 1, artist: artistName, limit: 100 }),
          call("library", { action: "local", artist: artistName, limit: 500 })
        ]);
        if (rev !== libraryRevision) return;
        albums = (albumRes.groups || []).map(g => ({
          ...g,
          artist: artistName,
          isLocal: true
        }));
        tracks = trackRes.tracks || [];
      } else {
        const id = String(artist.id || artist.uuid);
        const actionPrefix = artist.kind === "netease" ? "netease" : "tidal";
        const [albumRes, trackRes] = await Promise.all([
          call("library", { action: `${actionPrefix}-artist-albums`, collection: id }).catch(() => ({ collections: [] })),
          call("library", { action: `${actionPrefix}-collection`, kind: "ARTIST", collection: id })
        ]);
        if (rev !== libraryRevision) return;
        albums = (albumRes.collections || []).map(c => ({ ...c, kind: artist.kind, sourceName: artist.sourceName }));
        tracks = trackRes.tracks || [];
      }
      currentDetailTracks = tracks;

      $("albumHeroMeta").textContent = `${albums.length} 张专辑 · ${tracks.length} 首歌曲`;

      if (albums.length > 0) {
        $("artistAlbumsSection").hidden = false;
        renderAlbumGridCards(albums, $("artistAlbumsGrid"), album => openAlbumDetail(album));
      }

      renderTrackRows(tracks, $("detailTrackList"), false);
    } catch (err) {
      $("albumHeroMeta").textContent = `加载失败：${err.message}`;
      toast(err.message, "i-trash");
    }
  }

  function goBackDetail() {
    if (detailStack.length > 1) {
      detailStack.pop();
      const prev = detailStack.pop();
      if (prev.type === "album") openAlbumDetail(prev.data);
      else if (prev.type === "artist") openArtistDetail(prev.data);
    } else {
      detailStack = [];
      currentDetail = null;
      currentDetailTracks = [];
      document.querySelector(".library-header").hidden = false;
      document.querySelector(".library-subnav-bar").hidden = false;
      $("libraryDetailView").hidden = true;
      $("btnLibBack").hidden = true;
      loadLibrary();
    }
  }

  async function openCollectionTracks(collection, kind) {
    $("libraryTitle").textContent = collection.title || collection.name;
    $("libraryTrackList").hidden = false;
    $("libraryCardsView").hidden = true;
    $("btnLibBack").hidden = false;
    $("btnDownloadAlbum").hidden = kind !== "ALBUM";

    const action = libraryTab === "netease" ? "netease-collection" : "tidal-collection";
    const res = await call("library", { action, kind, collection: collection.id || collection.uuid });
    renderTrackRows(res.tracks || [], $("libraryTrackList"), false);
  }

  $("btnDownloadAlbum").addEventListener("click", async () => {
    if (!currentCollection) return;
    const albumId = currentCollection.id || currentCollection.uuid;
    toast("正在向手机发送下载整张专辑请求…");
    try {
      const res = await call("download", { album: String(albumId), quality: "BEST" });
      toast(`已成功添加下载任务！共 ${res.queued || "整张"} 首曲目`, "i-music");
    } catch (err) {
      toast(`下载失败：${err.message}`, "i-trash");
    }
  });

  $("btnDetailPlayAll").addEventListener("click", () => playTracks(currentDetailTracks, false));
  $("btnDetailShuffle").addEventListener("click", () => playTracks(currentDetailTracks, true));
  $("btnDetailBack").addEventListener("click", goBackDetail);
  $("btnLibBack").addEventListener("click", () => {
    if (detailStack.length > 0) goBackDetail();
    else if (currentCollection) {
      currentCollection = null;
      loadLibrary();
    }
  });

  $("btnDetailDownload").addEventListener("click", async () => {
    if (!currentDetail || !currentDetail.data) return;
    const item = currentDetail.data;
    const albumId = item.id || item.uuid;
    toast("正在向手机发送下载整张专辑请求…");
    try {
      const res = await call("download", { album: String(albumId), quality: "BEST" });
      toast(`已成功添加下载任务！共 ${res.queued || "整张"} 首曲目`, "i-music");
    } catch (err) {
      toast(`下载失败：${err.message}`, "i-trash");
    }
  });

  $("btnScanLibrary").addEventListener("click", async () => {
    toast("正在重新扫描音乐库…");
    try {
      const res = await call("library-scan", {});
      toast(`扫描完成，共 ${res.scanned || 0} 首歌曲`, "i-music");
      loadLibrary();
    } catch (err) {
      toast(`扫描失败：${err.message}`, "i-trash");
    }
  });

  $("playlistSelect").addEventListener("change", e => {
    currentPlaylist = e.target.value;
    syncRouteToHash();
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
      localSubCat = "songs";
      localSubnavButtons.forEach(b => b.setAttribute("aria-current", String(b.dataset.localCat === "songs")));
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
    if (!$("libraryDetailView").hidden) {
      $("btnDetailPlayAll").click();
      return;
    }
    const items = Array.from($("libraryTrackList").children);
    if (items.length > 0) items[0].click();
  });

  $("btnShuffleAllLib").addEventListener("click", () => {
    if (!$("libraryDetailView").hidden) {
      $("btnDetailShuffle").click();
      return;
    }
    const rows = Array.from($("libraryTrackList").children);
    if (rows.length > 0) {
      const randomIndex = Math.floor(Math.random() * rows.length);
      rows[randomIndex].click();
    }
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

  // Like the app: songs, albums, playlists and artists, from TIDAL, NetEase and the local library.
  let currentSearchKind = "TRACK";
  let searchRevision = 0;
  let searchDetail = null;
  const KIND_NAMES = { TRACK: "歌曲", ALBUM: "专辑", PLAYLIST: "歌单", ARTIST: "艺人" };
  const SOURCE_NAMES = { tidal: "TIDAL", netease: "网易云", local: "本地" };

  const searchKindButtons = document.querySelectorAll("[data-search-kind]");
  searchKindButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      searchKindButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      currentSearchKind = btn.dataset.searchKind;
      executeGlobalSearch();
    });
  });

  $("globalSearchForm").addEventListener("submit", e => {
    e.preventDefault();
    executeGlobalSearch();
  });

  function searchSources() {
    return currentSearchSource === "all" ? ["tidal", "netease", "local"] : [currentSearchSource];
  }

  function searchMessage(text, danger = false) {
    const list = $("searchTrackList");
    list.hidden = false;
    list.replaceChildren();
    const li = document.createElement("li");
    li.style.cssText = `text-align:center; padding:30px; color:var(${danger ? "--danger" : "--muted"});`;
    li.textContent = text;
    list.appendChild(li);
  }

  function resetSearchLayout() {
    $("searchDetailBar").hidden = !searchDetail;
    $("btnSearchDownloadAlbum").hidden = true;
    $("searchCardsTitle").hidden = true;
    $("searchTracksTitle").hidden = true;
    $("searchCardsGrid").hidden = true;
    $("searchCardsGrid").replaceChildren();
    $("searchTrackList").hidden = false;
    $("searchTrackList").replaceChildren();
  }

  // Albums, artists and local playlists of the local library that match the search.
  async function localCollections(kind, q) {
    const res = await call("library", { action: "local", query: q });
    if (kind === "PLAYLIST") {
      const term = q.toLowerCase();
      return (res.playlists || []).filter(name => name.toLowerCase().includes(term))
        .map(name => ({ id: name, title: name, subtitle: "本地歌单", playlist: name }));
    }
    const groups = new Map();
    for (const track of res.tracks || []) {
      const name = kind === "ALBUM" ? track.album : track.artist;
      if (!name) continue;
      if (!groups.has(name)) {
        groups.set(name, { id: name, title: name, subtitle: kind === "ALBUM" ? track.artist || "" : "",
          coverUrl: getArtworkUrl(track, 320), tracks: [] });
      }
      groups.get(name).tracks.push(track);
    }
    for (const group of groups.values()) {
      if (kind === "ARTIST") group.subtitle = `${group.tracks.length} 首本地歌曲`;
    }
    return [...groups.values()];
  }

  async function executeGlobalSearch() {
    const q = $("globalSearchInput").value.trim();
    if (!q) return;
    const rev = ++searchRevision;
    searchDetail = null;
    resetSearchLayout();
    searchMessage("正在全网检索…");
    const kind = currentSearchKind;
    const failures = [];

    const jobs = searchSources().map(async source => {
      try {
        if (kind === "TRACK") {
          const action = source === "tidal" ? "tidal-search" : source === "netease" ? "netease-search" : "local";
          return (await call("library", { action, query: q })).tracks || [];
        }
        const items = source === "local"
          ? await localCollections(kind, q)
          : (await call("library", { action: `${source}-collections`, kind, query: q })).collections || [];
        return items.map(item => ({ ...item, source, sourceName: currentSearchSource === "all" ? SOURCE_NAMES[source] : "" }));
      } catch (err) {
        failures.push(`${SOURCE_NAMES[source]}：${err.message}`);
        return [];
      }
    });
    const results = (await Promise.all(jobs)).flat();
    if (rev !== searchRevision) return;

    resetSearchLayout();
    if (!results.length) {
      searchMessage(failures.length ? failures.join("；") : `没有找到相关${KIND_NAMES[kind]}`, failures.length > 0);
      return;
    }
    if (failures.length) toast(failures.join("；"), "i-trash");
    if (kind === "TRACK") {
      renderTrackRows(results, $("searchTrackList"), false);
    } else {
      $("searchTrackList").hidden = true;
      $("searchCardsGrid").hidden = false;
      renderCollectionCards(results, kind, $("searchCardsGrid"), item => openSearchCollection(item, kind));
    }
  }

  async function openSearchCollection(item, kind) {
    const rev = ++searchRevision;
    searchDetail = { item, kind };
    resetSearchLayout();
    $("searchDetailTitle").textContent = `${KIND_NAMES[kind]} · ${item.title || item.name || ""}`;
    // Whole albums download through the device's TIDAL login, as in the app.
    $("btnSearchDownloadAlbum").hidden = !(kind === "ALBUM" && item.source === "tidal");
    window.scrollTo({ top: 0 });
    searchMessage("正在加载…");
    try {
      let tracks = [];
      let albums = [];
      if (item.source === "local") {
        tracks = item.tracks || (await call("library", { action: "local", playlist: item.playlist, query: "" })).tracks || [];
      } else {
        const id = String(item.id || item.uuid);
        const requests = [call("library", { action: `${item.source}-collection`, kind, collection: id })];
        if (kind === "ARTIST") {
          requests.push(call("library", { action: `${item.source}-artist-albums`, collection: id }).catch(() => ({ collections: [] })));
        }
        const [page, releases] = await Promise.all(requests);
        tracks = page.tracks || [];
        albums = ((releases && releases.collections) || []).map(album => ({ ...album, source: item.source }));
      }
      if (rev !== searchRevision) return;
      $("searchTrackList").replaceChildren();
      if (albums.length) {
        $("searchCardsTitle").textContent = "专辑";
        $("searchCardsTitle").hidden = false;
        $("searchCardsGrid").hidden = false;
        renderCollectionCards(albums, "ALBUM", $("searchCardsGrid"), album => openSearchCollection(album, "ALBUM"));
      }
      if (kind === "ARTIST" && tracks.length) {
        $("searchTracksTitle").textContent = "热门歌曲";
        $("searchTracksTitle").hidden = false;
      }
      if (tracks.length || !albums.length) renderTrackRows(tracks, $("searchTrackList"), false);
    } catch (err) {
      if (rev === searchRevision) searchMessage(err.message, true);
    }
  }

  $("btnSearchBack").addEventListener("click", () => executeGlobalSearch());

  $("btnSearchDownloadAlbum").addEventListener("click", async () => {
    if (!searchDetail) return;
    const album = searchDetail.item;
    toast("正在向手机发送下载整张专辑请求…");
    try {
      const res = await call("download", { album: String(album.id), quality: "BEST" });
      const skipped = res.skipped ? `，${res.skipped} 首已在下载队列或已下载` : "";
      toast(`已添加「${album.title || ""}」：${res.queued ?? 0} 首${skipped}`, "i-music");
    } catch (err) {
      toast(`下载失败：${err.message}`, "i-trash");
    }
  });

  // --- Lyrics: fill in .lrc files for local songs, or pick one by hand ---
  let lyricsMissingOnly = false;
  let lyricsSong = null;
  let lyricsChoice = null;
  let lyricsTimer = null;

  function lyricsTime(seconds) {
    return seconds > 0 ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}` : "";
  }

  function closeLyrics() {
    $("lyricsModal").classList.remove("active");
    lyricsSong = null;
  }

  async function openLyricsPicker(track) {
    lyricsSong = track;
    lyricsChoice = null;
    $("lyricsModalTitle").textContent = `歌词 · ${track.title}`;
    $("lyricsModalLead").textContent = "正在 TIDAL 和网易云中搜索…";
    $("lyricsCandidates").hidden = false;
    $("lyricsCandidates").replaceChildren();
    $("lyricsPreview").hidden = true;
    $("btnLyricsBack").hidden = true;
    $("btnLyricsSave").hidden = true;
    $("lyricsModal").classList.add("active");
    try {
      const res = await call("library", { action: "lyrics-candidates", id: track.id });
      if (lyricsSong !== track) return;
      const items = res.candidates || [];
      const length = lyricsTime(track.duration);
      $("lyricsModalLead").textContent = items.length
        ? `本地文件${length ? " " + length : ""} · ${track.artist || "未知艺人"}。✓ 表示歌名、艺人和时长都对得上。`
        : "TIDAL 和网易云都没有找到这首歌。";
      for (const item of items) {
        const li = document.createElement("li");
        li.className = "track-row";
        li.innerHTML = `<div class="track-info"><div class="track-title"></div><div class="track-subtitle"></div></div>`;
        const title = li.querySelector(".track-title");
        if (item.match) {
          const mark = document.createElement("span");
          mark.className = "lyrics-match";
          mark.textContent = "✓ ";
          title.appendChild(mark);
        }
        title.appendChild(document.createTextNode(item.title));
        li.querySelector(".track-subtitle").textContent =
          [item.artist, item.album, lyricsTime(item.duration), item.source].filter(Boolean).join(" · ");
        li.addEventListener("click", () => previewLyrics(item));
        $("lyricsCandidates").appendChild(li);
      }
    } catch (err) {
      if (lyricsSong === track) $("lyricsModalLead").textContent = `搜索失败：${err.message}`;
    }
  }

  async function previewLyrics(item) {
    const song = lyricsSong;
    $("lyricsModalLead").textContent = `正在读取「${item.title}」（${item.source}）的歌词…`;
    try {
      const words = await call("library", { action: "lyrics-preview", source: item.source, track: item.id });
      if (lyricsSong !== song) return;
      const text = words.synced || words.plain || "";
      if (!text.trim()) {
        $("lyricsModalLead").textContent = `「${item.title}」（${item.source}）没有歌词，请换一个。`;
        return;
      }
      lyricsChoice = { item, plain: !words.synced };
      $("lyricsModalLead").textContent = words.synced
        ? `${item.title} · ${item.artist} · ${item.source} · 带时间轴`
        : `${item.title} · ${item.source} · 只有纯文本歌词（没有时间轴，播放时不会滚动）`;
      $("lyricsPreview").textContent = text;
      $("lyricsPreview").hidden = false;
      $("lyricsCandidates").hidden = true;
      $("btnLyricsBack").hidden = false;
      $("btnLyricsSave").hidden = false;
      $("btnLyricsSave").textContent = words.synced ? "保存" : "仍然保存";
    } catch (err) {
      if (lyricsSong === song) $("lyricsModalLead").textContent = `读取歌词失败：${err.message}`;
    }
  }

  $("btnLyricsClose").addEventListener("click", closeLyrics);
  $("lyricsModal").addEventListener("click", e => { if (e.target === $("lyricsModal")) closeLyrics(); });
  $("btnLyricsBack").addEventListener("click", () => {
    $("lyricsPreview").hidden = true;
    $("lyricsCandidates").hidden = false;
    $("btnLyricsBack").hidden = true;
    $("btnLyricsSave").hidden = true;
    $("lyricsModalLead").textContent = "选择另一个版本。✓ 表示歌名、艺人和时长都对得上。";
  });
  $("btnLyricsSave").addEventListener("click", async () => {
    if (!lyricsSong || !lyricsChoice) return;
    const song = lyricsSong;
    try {
      await call("library", { action: "lyrics-save", id: song.id, source: lyricsChoice.item.source,
        track: lyricsChoice.item.id, plain: lyricsChoice.plain });
      toast(`已保存歌词：${song.title}`, "i-lyrics");
      closeLyrics();
      if (currentLyricsTrackId === song.id) {
        currentLyricsTrackId = null;
        currentLyrics = [];
        fetchAndRenderLyrics(song);
      }
      if (activeView === "library") loadLibrary();
    } catch (err) {
      toast(`保存歌词失败：${err.message}`, "i-trash");
    }
  });

  function showFillStatus(status) {
    const line = $("lyricsFillStatus");
    clearTimeout(lyricsTimer);
    if (status.running) {
      line.hidden = false;
      line.textContent = `正在补全歌词：${status.done} / ${status.total} · 已保存 ${status.saved} · 未找到 ${status.missing}`
        + (status.current ? ` · ${status.current}` : "");
      $("btnLyricsFill").textContent = "停止补全";
      lyricsTimer = setTimeout(pollFill, 1500);
    } else {
      $("btnLyricsFill").innerHTML = `<svg class="icon"><use href="#i-lyrics"/></svg> 补全歌词`;
      if (status.total > 0) {
        line.hidden = false;
        line.textContent = `补全完成：已保存 ${status.saved} 首，未找到 ${status.missing} 首。仍有 ${status.without} 首没有歌词，可点歌曲的歌词按钮手动挑选。`;
      }
    }
  }

  async function pollFill() {
    try {
      const status = await call("library", { action: "lyrics-status" });
      const wasRunning = $("btnLyricsFill").textContent === "停止补全";
      showFillStatus(status);
      if (wasRunning && !status.running && activeView === "library") loadLibrary();
    } catch (_) {
      lyricsTimer = setTimeout(pollFill, 4000);
    }
  }

  $("btnLyricsFill").addEventListener("click", async () => {
    if ($("btnLyricsFill").textContent === "停止补全") {
      showFillStatus(await call("library", { action: "lyrics-stop" }));
      return;
    }
    try {
      const status = await call("library", { action: "lyrics-status" });
      if (!status.without) {
        toast("所有歌曲都已有歌词", "i-lyrics");
        return;
      }
      const retry = confirm(`${status.without} 首歌曲没有歌词文件。将在 TIDAL 和网易云中按歌名、艺人和时长查找，保存为同名 .lrc。\n\n之前没找到的歌曲也再找一次吗？（取消 = 只找新的）`);
      showFillStatus(await call("library", { action: "lyrics-fill", retry }));
    } catch (err) {
      toast(err.message, "i-trash");
    }
  });

  $("btnLyricsMissing").addEventListener("click", () => {
    lyricsMissingOnly = !lyricsMissingOnly;
    $("btnLyricsMissing").setAttribute("aria-pressed", String(lyricsMissingOnly));
    loadLibrary();
  });

  // --- Downloads (the device's download tasks) ---
  const DL_ACTIVE = new Set(["queued", "preparing", "transferring", "waiting"]);
  const DL_LABELS = { queued: "排队中", preparing: "准备中", transferring: "下载中", waiting: "等待网络",
    complete: "已完成", failed: "失败", cancelled: "已取消" };
  let dlFilter = "all";
  let dlLimit = 300;
  let dlData = null;
  let dlBusy = false;
  let dlTimer = null;
  const dlSelected = new Set();

  function dlMatches(job) {
    if (dlFilter === "active") return DL_ACTIVE.has(job.state);
    if (dlFilter === "failed") return job.state === "failed" || job.state === "cancelled";
    if (dlFilter === "complete") return job.state === "complete";
    return true;
  }

  function mb(bytes) {
    return (bytes / 1048576).toFixed(1);
  }

  function dlStatus(job) {
    const label = DL_LABELS[job.state] || job.state;
    if (job.state === "transferring" && job.total > 0) {
      return `${label} · ${Math.floor(job.bytes * 100 / job.total)}% · ${mb(job.bytes)} / ${mb(job.total)} MB`;
    }
    if (job.state === "failed") return `${label}：${job.error || "未知错误"}`;
    if (job.state === "complete") return [label, job.resolution, job.total > 0 ? `${mb(job.total)} MB` : ""].filter(Boolean).join(" · ");
    return job.error && job.state === "waiting" ? `${label}：${job.error}` : label;
  }

  function scheduleDownloads() {
    clearTimeout(dlTimer);
    const busy = dlData && dlData.jobs.some(job => DL_ACTIVE.has(job.state));
    // Fast while the page is open and something downloads, slow otherwise (only the badge).
    const delay = activeView === "downloads" ? (busy ? 2000 : 8000) : 30000;
    dlTimer = setTimeout(() => loadDownloads(), delay);
  }

  async function downloadsCall(action, fields = {}) {
    return call("downloads", { action, limit: dlLimit, ...fields });
  }

  async function loadDownloads(reply) {
    if (!reply && dlBusy) return;
    try {
      if (!reply) {
        dlBusy = true;
        reply = await downloadsCall("list");
      }
      dlData = { ...reply, jobs: reply.jobs || [] };
      trackSpeeds(dlData.jobs);
      renderDownloads();
    } catch (err) {
      if (activeView === "downloads") {
        $("dlSummary").textContent = `读取下载任务失败：${err.message}`;
      }
    } finally {
      dlBusy = false;
      scheduleDownloads();
    }
  }

  // Download speed of each running task, from the bytes between two refreshes.
  const dlSpeeds = new Map();
  const dlInfo = new Map();
  const dlExpanded = new Set();
  const dlGroupOpen = new Map();
  let dlGrouped = true;
  try { dlGrouped = localStorage.getItem("dl-grouped") !== "0"; } catch (_) {}

  function trackSpeeds(jobs) {
    const now = performance.now();
    const seen = new Set();
    for (const job of jobs) {
      if (job.state !== "transferring") continue;
      seen.add(job.id);
      const prev = dlSpeeds.get(job.id);
      let rate = null;
      if (prev && job.bytes >= prev.bytes && now - prev.at > 300) {
        const instant = (job.bytes - prev.bytes) / ((now - prev.at) / 1000);
        rate = prev.rate == null ? instant : prev.rate * 0.5 + instant * 0.5;
      } else if (prev) rate = prev.rate;
      dlSpeeds.set(job.id, { bytes: job.bytes, at: now, rate });
    }
    for (const id of [...dlSpeeds.keys()]) if (!seen.has(id)) dlSpeeds.delete(id);
  }

  function speedText(rate) {
    if (rate == null) return "";
    return rate >= 1048576 ? `${(rate / 1048576).toFixed(1)} MB/s` : `${Math.round(rate / 1024)} KB/s`;
  }

  function sizeText(bytes) {
    if (!(bytes > 0)) return "";
    return bytes >= 1073741824 ? `${(bytes / 1073741824).toFixed(2)} GB` : `${mb(bytes)} MB`;
  }

  function durationText(seconds) {
    if (!(seconds > 0)) return "";
    const s = Math.round(seconds);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  }

  function dlProgressText(job) {
    const rate = dlSpeeds.get(job.id)?.rate;
    const parts = [];
    if (job.total > 0) {
      parts.push(`${Math.floor(job.bytes * 100 / job.total)}%`, `${mb(job.bytes)} / ${mb(job.total)} MB`);
    } else if (job.bytes > 0) parts.push(`已下载 ${mb(job.bytes)} MB`);
    if (rate != null) parts.push(speedText(rate));
    if (rate > 0 && job.total > job.bytes) parts.push(`剩余 ${durationText((job.total - job.bytes) / rate)}`);
    return parts.join(" · ");
  }

  function dlStatusText(job) {
    if (job.state === "transferring") {
      const progress = dlProgressText(job);
      return progress ? `下载中 · ${progress}` : "下载中";
    }
    return dlStatus(job);
  }

  function dlDetails(job) {
    const info = dlInfo.get(job.id);
    const box = document.createElement("dl");
    box.className = "dl-details";
    const add = (label, value) => {
      if (value === undefined || value === null || value === "") return;
      const dt = document.createElement("dt");
      dt.textContent = label;
      const dd = document.createElement("dd");
      dd.textContent = String(value);
      box.append(dt, dd);
    };
    if (!info) {
      add("详情", "正在读取…");
      return box;
    }
    if (info.error && !info.id) {
      add("详情", info.error);
      return box;
    }
    const ext = (info.name || "").includes(".") ? info.name.split(".").pop().toUpperCase() : "";
    const sample = info.sampleRate ? `${info.bitDepth ? info.bitDepth + " bit / " : ""}${(info.sampleRate / 1000).toFixed(1)} kHz` : "";
    add("版本", info.version);
    add("文件名", info.exists === false ? "文件已不存在" : info.name);
    add("位置", info.path);
    add("保存目录", info.folder);
    add("格式", [ext, info.mime].filter(Boolean).join(" · "));
    add("码率", info.bitrate ? `${Math.round(info.bitrate / 1000)} kbps` : "");
    add("采样", sample);
    add("时长", durationText(info.duration));
    add("大小", sizeText(info.size || (info.total > 0 ? info.total : 0)));
    add("下载音质", [info.resolution, info.verifiedLossless ? "已校验无损" : "", info.adopted ? "使用手机上已有的文件" : ""].filter(Boolean).join(" · "));
    add("专辑", [info.album, info.albumArtist].filter(Boolean).join(" · "));
    add("曲目", info.number ? `${info.volumes > 1 ? `第 ${info.disc} 碟 ` : ""}第 ${info.number} 首` : "");
    add("发行", info.date);
    add("来源", `${info.source} · 曲目 ${info.track}`);
    if (info.error) add("错误", info.error);
    if (info.attempts > 1) add("尝试次数", info.attempts);
    return box;
  }

  async function loadDownloadInfo(id) {
    try {
      const reply = await downloadsCall("info", { ids: [id] });
      dlInfo.set(id, reply.info);
    } catch (err) {
      dlInfo.set(id, { error: err.message });
    }
    renderDownloads();
  }

  const dlTrackDOM = new Map();
  const dlGroupDOM = new Map();

  function createDlTrackRow(job) {
    const li = document.createElement("li");
    li.className = "dl-track-row";
    li.dataset.id = job.id;

    const box = document.createElement("input");
    box.type = "checkbox";
    box.setAttribute("aria-label", "选择曲目");
    box.addEventListener("change", () => {
      if (box.checked) dlSelected.add(job.id); else dlSelected.delete(job.id);
      renderBatchBar();
    });
    li.appendChild(box);

    const thumb = document.createElement("div");
    thumb.className = "dl-track-thumb";
    const img = document.createElement("img");
    img.alt = "";
    img.hidden = true;
    thumb.appendChild(img);
    li.appendChild(thumb);

    const main = document.createElement("div");
    main.className = "dl-track-main";
    main.title = "查看文件技术信息";

    const header = document.createElement("div");
    header.className = "dl-track-header";
    const title = document.createElement("span");
    title.className = "dl-track-title";
    header.appendChild(title);
    const version = document.createElement("span");
    version.className = "dl-version";
    version.hidden = true;
    header.appendChild(version);
    main.appendChild(header);

    const sub = document.createElement("div");
    sub.className = "dl-track-sub";
    const sourcePill = document.createElement("span");
    sourcePill.className = "dl-pill";
    sub.appendChild(sourcePill);
    const qualityPill = document.createElement("span");
    qualityPill.className = "dl-pill";
    qualityPill.hidden = true;
    sub.appendChild(qualityPill);
    const meta = document.createElement("span");
    sub.appendChild(meta);
    main.appendChild(sub);

    const status = document.createElement("div");
    status.className = "dl-status-line";
    main.appendChild(status);

    const streamBar = document.createElement("div");
    streamBar.className = "dl-stream-bar";
    streamBar.hidden = true;
    const streamFill = document.createElement("div");
    streamFill.className = "dl-stream-fill";
    streamBar.appendChild(streamFill);
    main.appendChild(streamBar);

    main.addEventListener("click", () => {
      if (dlExpanded.has(job.id)) dlExpanded.delete(job.id);
      else {
        dlExpanded.add(job.id);
        loadDownloadInfo(job.id);
      }
      renderDownloads();
    });
    li.appendChild(main);

    const actions = document.createElement("div");
    actions.className = "dl-track-actions";
    li.appendChild(actions);

    return { root: li, box, thumb, img, title, version, sourcePill, qualityPill, meta, status, streamBar, streamFill, actions, detailsBox: null, lastState: null };
  }

  function updateDlTrackRow(record, job, isGrouped) {
    if (record.box.checked !== dlSelected.has(job.id)) {
      record.box.checked = dlSelected.has(job.id);
    }
    const displayTitle = job.title || `曲目 ${job.track}`;
    if (record.title.textContent !== displayTitle) record.title.textContent = displayTitle;

    if (job.version && !displayTitle.includes(job.version)) {
      record.version.textContent = ` (${job.version})`;
      record.version.hidden = false;
    } else {
      record.version.hidden = true;
    }

    const coverUrl = job.cover ? getArtworkUrl(job.cover, 120) : (job.track && job.source === "本地音乐" ? `/api/artwork/${job.track}` : "");
    applyArtwork(record.thumb, record.img, coverUrl, job.title || job.artist);

    const srcName = job.source || "TIDAL";
    if (record.sourcePill.textContent !== srcName) {
      record.sourcePill.textContent = srcName;
      record.sourcePill.className = "dl-pill " + (srcName === "网易云" ? "dl-pill-source-netease" : "dl-pill-source-tidal");
    }

    const qual = (job.resolution && job.state !== "complete" ? job.resolution : job.quality) || "";
    if (record.qualityPill.textContent !== qual) {
      record.qualityPill.textContent = qual;
      record.qualityPill.hidden = !qual;
      if (qual.includes("Hi-Res") || qual.includes("HI_RES") || qual.includes("192") || qual.includes("96")) {
        record.qualityPill.className = "dl-pill dl-pill-quality-hires";
      } else if (qual.includes("无损") || qual.includes("LOSSLESS") || qual.includes("FLAC")) {
        record.qualityPill.className = "dl-pill dl-pill-quality-lossless";
      } else {
        record.qualityPill.className = "dl-pill dl-pill-quality-standard";
      }
    }

    const parts = [job.artist, isGrouped ? "" : job.album].filter(Boolean);
    const metaText = parts.join(" · ");
    if (record.meta.textContent !== metaText) record.meta.textContent = metaText;

    const st = dlStatusText(job);
    if (record.status.textContent !== st) record.status.textContent = st;
    const statusCls = "dl-status-line " + (job.state || "");
    if (record.status.className !== statusCls) record.status.className = statusCls;

    if (job.state === "transferring" && job.total > 0) {
      record.streamBar.hidden = false;
      const pct = Math.min(100, Math.max(0, job.bytes * 100 / job.total));
      record.streamFill.style.width = pct + "%";
    } else {
      record.streamBar.hidden = true;
    }

    if (record.lastState !== job.state) {
      record.actions.replaceChildren();
      const btn = (label, action, cls = "ghost") => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = cls;
        b.textContent = label;
        b.addEventListener("click", e => {
          e.stopPropagation();
          downloadAction(action, [job.id]);
        });
        record.actions.appendChild(b);
      };
      if (DL_ACTIVE.has(job.state)) {
        btn("取消", "cancel");
      } else {
        if (job.state !== "complete") btn("重试", "retry");
        btn("移除", "remove", "ghost danger-text");
      }
      record.lastState = job.state;
    }

    if (dlExpanded.has(job.id)) {
      if (!record.detailsBox) {
        record.detailsBox = dlDetails(job);
        record.root.appendChild(record.detailsBox);
      }
    } else if (record.detailsBox) {
      record.detailsBox.remove();
      record.detailsBox = null;
    }
  }

  function createDlGroupRow(groupKey) {
    const li = document.createElement("li");
    li.className = "dl-group-card";
    li.dataset.group = groupKey;

    const header = document.createElement("div");
    header.className = "dl-group-header";

    const box = document.createElement("input");
    box.type = "checkbox";
    box.setAttribute("aria-label", "选择整张专辑");
    box.addEventListener("click", e => e.stopPropagation());
    header.appendChild(box);

    const thumb = document.createElement("div");
    thumb.className = "dl-group-thumb";
    const img = document.createElement("img");
    img.alt = "";
    img.hidden = true;
    thumb.appendChild(img);
    header.appendChild(thumb);

    const info = document.createElement("div");
    info.className = "dl-group-info";
    const title = document.createElement("div");
    title.className = "dl-group-title";
    info.appendChild(title);
    const sub = document.createElement("div");
    sub.className = "dl-group-sub";
    info.appendChild(sub);

    const metaBar = document.createElement("div");
    metaBar.className = "dl-group-meta-bar";
    const progressBar = document.createElement("div");
    progressBar.className = "dl-group-progress-bar";
    const progressFill = document.createElement("div");
    progressFill.className = "dl-group-progress-fill";
    progressBar.appendChild(progressFill);
    metaBar.appendChild(progressBar);
    const count = document.createElement("span");
    count.className = "dl-group-count";
    metaBar.appendChild(count);
    info.appendChild(metaBar);
    header.appendChild(info);

    const actions = document.createElement("div");
    actions.className = "dl-group-actions";
    header.appendChild(actions);

    const chevron = document.createElement("div");
    chevron.className = "dl-chevron-btn";
    chevron.innerHTML = `<svg class="icon"><use href="#i-chevron-down"/></svg>`;
    header.appendChild(chevron);

    li.appendChild(header);

    const memberList = document.createElement("ul");
    memberList.className = "dl-group-members";
    li.appendChild(memberList);

    header.addEventListener("click", e => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "BUTTON" || e.target.closest("button")) return;
      const curOpen = !li.classList.contains("closed");
      dlGroupOpen.set(groupKey, !curOpen);
      renderDownloads();
    });

    return { root: li, header, box, thumb, img, title, sub, progressFill, count, actions, chevron, memberList, lastActionSig: "" };
  }

  function updateDlGroupRow(record, groupKey, members) {
    const done = members.filter(j => j.state === "complete").length;
    const running = members.filter(j => DL_ACTIVE.has(j.state));
    const failed = members.filter(j => j.state === "failed" || j.state === "cancelled");
    const rate = running.reduce((sum, j) => sum + (dlSpeeds.get(j.id)?.rate || 0), 0);
    const open = dlGroupOpen.has(groupKey) ? dlGroupOpen.get(groupKey) : (running.length > 0 || failed.length > 0);

    record.root.classList.toggle("closed", !open);

    const groupTitle = groupKey || "单曲集";
    if (record.title.textContent !== groupTitle) record.title.textContent = groupTitle;

    const first = members[0] || {};
    const subText = [first.artist || "", `${members.length} 首曲目`].filter(Boolean).join(" · ");
    if (record.sub.textContent !== subText) record.sub.textContent = subText;

    const coverJob = members.find(j => j.cover);
    const coverUrl = coverJob ? getArtworkUrl(coverJob.cover, 160) : "";
    applyArtwork(record.thumb, record.img, coverUrl, groupTitle || first.artist);

    const pct = members.length ? Math.round(done * 100 / members.length) : 0;
    record.progressFill.style.width = pct + "%";

    const metaParts = [`${done} / ${members.length} 完成`, running.length ? `进行中 ${running.length}` : "", failed.length ? `失败 ${failed.length}` : "", rate > 0 ? speedText(rate) : ""].filter(Boolean);
    const countText = metaParts.join(" · ");
    if (record.count.textContent !== countText) record.count.textContent = countText;

    const allChecked = members.length > 0 && members.every(j => dlSelected.has(j.id));
    if (record.box.checked !== allChecked) record.box.checked = allChecked;
    record.box.onchange = () => {
      members.forEach(j => record.box.checked ? dlSelected.add(j.id) : dlSelected.delete(j.id));
      renderBatchBar();
      renderDownloads();
    };

    const sig = `${failed.length}:${running.length}:${done === members.length}`;
    if (record.lastActionSig !== sig) {
      record.actions.replaceChildren();
      const btn = (label, action, ids, cls = "ghost") => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = cls;
        b.textContent = label;
        b.addEventListener("click", e => {
          e.stopPropagation();
          downloadAction(action, ids);
        });
        record.actions.appendChild(b);
      };
      if (failed.length) btn("重试失败", "retry", failed.map(j => j.id));
      if (running.length) btn("全部取消", "cancel", running.map(j => j.id));
      if (!running.length) btn("移除", "remove", members.map(j => j.id), "ghost danger-text");
      record.lastActionSig = sig;
    }

    if (open) {
      members.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
      for (let i = 0; i < members.length; i++) {
        const job = members[i];
        let itemRec = dlTrackDOM.get(job.id);
        if (!itemRec) {
          itemRec = createDlTrackRow(job);
          dlTrackDOM.set(job.id, itemRec);
        }
        updateDlTrackRow(itemRec, job, true);
        if (record.memberList.children[i] !== itemRec.root) {
          record.memberList.insertBefore(itemRec.root, record.memberList.children[i] || null);
        }
      }
      while (record.memberList.children.length > members.length) {
        record.memberList.lastElementChild.remove();
      }
    }
  }

  function renderDownloads() {
    if (!dlData) return;
    const jobs = dlData.jobs;
    const active = jobs.filter(job => DL_ACTIVE.has(job.state)).length;
    const failed = jobs.filter(job => job.state === "failed" || job.state === "cancelled").length;
    const done = jobs.filter(job => job.state === "complete").length;
    const total = dlData.total ?? jobs.length;

    $("dlNavBadge").hidden = active === 0;
    $("dlNavBadge").textContent = String(active);
    if (activeView !== "downloads") return;

    const known = new Set(jobs.map(job => job.id));
    for (const id of [...dlSelected]) if (!known.has(id)) dlSelected.delete(id);
    for (const id of [...dlExpanded]) if (!known.has(id)) dlExpanded.delete(id);

    const parallel = $("dlParallel");
    const max = dlData.maxParallel || 5;
    if (parallel.options.length !== max) {
      parallel.innerHTML = Array.from({ length: max }, (_, i) => `<option value="${i + 1}">${i + 1} 首</option>`).join("");
    }
    if (document.activeElement !== parallel) parallel.value = String(dlData.parallel || 1);
    $("btnDlGroup").setAttribute("aria-pressed", String(dlGrouped));
    if ($("dlGroupBtnText")) $("dlGroupBtnText").textContent = dlGrouped ? "专辑分组" : "单曲平铺";

    if ($("dlBadgeAll")) $("dlBadgeAll").textContent = String(total);
    if ($("dlBadgeActive")) $("dlBadgeActive").textContent = String(active);
    if ($("dlBadgeFailed")) $("dlBadgeFailed").textContent = String(failed);
    if ($("dlBadgeComplete")) $("dlBadgeComplete").textContent = String(done);

    if ($("dlOverallCount")) $("dlOverallCount").textContent = `${done} / ${total} 完成`;
    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
    if ($("dlOverallPercent")) $("dlOverallPercent").textContent = `${pct}%`;
    if ($("dlOverallFill")) $("dlOverallFill").style.width = `${pct}%`;

    if ($("btnDlRetryAllFailed")) $("btnDlRetryAllFailed").hidden = failed === 0;

    const rate = [...dlSpeeds.values()].reduce((sum, item) => sum + (item.rate || 0), 0);
    const speedBadge = $("dlSpeedBadge");
    const speedTextEl = $("dlSpeedText");
    if (speedBadge && speedTextEl) {
      if (rate > 0) {
        speedBadge.classList.add("active");
        speedTextEl.textContent = speedText(rate);
      } else if (active > 0) {
        speedBadge.classList.add("active");
        speedTextEl.textContent = "排队准备中";
      } else {
        speedBadge.classList.remove("active");
        speedTextEl.textContent = "空闲";
      }
    }

    $("btnDlMore").hidden = !(total > jobs.length && dlLimit < 2000);

    const list = $("dlList");
    const shown = jobs.filter(dlMatches);

    if (!shown.length) {
      list.replaceChildren();
      const li = document.createElement("li");
      li.style.cssText = "text-align:center; padding:40px; color:var(--muted); list-style:none;";
      li.textContent = jobs.length ? "没有符合条件的任务" : "暂无下载任务";
      list.appendChild(li);
      renderBatchBar();
      return;
    }

    // If placeholder is present, clear it
    if (list.firstElementChild && !list.firstElementChild.dataset.id && !list.firstElementChild.dataset.group) {
      list.replaceChildren();
    }

    if (dlGrouped) {
      // Group by album
      const groups = new Map();
      for (const job of shown) {
        const key = job.album || "";
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(job);
      }
      const groupEntries = [...groups.entries()];
      for (let i = 0; i < groupEntries.length; i++) {
        const [key, members] = groupEntries[i];
        let groupRec = dlGroupDOM.get(key);
        if (!groupRec) {
          groupRec = createDlGroupRow(key);
          dlGroupDOM.set(key, groupRec);
        }
        updateDlGroupRow(groupRec, key, members);
        if (list.children[i] !== groupRec.root) {
          list.insertBefore(groupRec.root, list.children[i] || null);
        }
      }
      while (list.children.length > groupEntries.length) {
        list.lastElementChild.remove();
      }
    } else {
      for (let i = 0; i < shown.length; i++) {
        const job = shown[i];
        let itemRec = dlTrackDOM.get(job.id);
        if (!itemRec) {
          itemRec = createDlTrackRow(job);
          dlTrackDOM.set(job.id, itemRec);
        }
        updateDlTrackRow(itemRec, job, false);
        if (list.children[i] !== itemRec.root) {
          list.insertBefore(itemRec.root, list.children[i] || null);
        }
      }
      while (list.children.length > shown.length) {
        list.lastElementChild.remove();
      }
    }

    renderBatchBar();
    restoreScrollForView("downloads");
  }

  function renderBatchBar() {
    $("dlBatchBar").hidden = dlSelected.size === 0;
    $("dlSelCount").textContent = `已选 ${dlSelected.size} 项`;
  }

  async function downloadAction(action, ids) {
    const names = { cancel: "取消", retry: "重试", remove: "移除" };
    try {
      const reply = await downloadsCall(action, { ids });
      const skipped = reply.skipped ? `，${reply.skipped} 项未处理（正在下载的任务需先取消）` : "";
      toast(`已${names[action]} ${ids.length - (reply.skipped || 0)} 项${skipped}`, "i-download");
      if (action === "remove") ids.forEach(id => dlSelected.delete(id));
      loadDownloads(reply);
    } catch (err) {
      toast(`${names[action]}失败：${err.message}`, "i-trash");
    }
  }

  $("tabNavDownloads").addEventListener("click", () => showView("downloads"));
  $("btnDlRefresh").addEventListener("click", () => loadDownloads());
  $("btnDlGroup").addEventListener("click", () => {
    dlGrouped = !dlGrouped;
    try { localStorage.setItem("dl-grouped", dlGrouped ? "1" : "0"); } catch (_) {}
    syncRouteToHash();
    renderDownloads();
  });
  $("btnDlMore").addEventListener("click", () => {
    dlLimit = Math.min(2000, dlLimit + 300);
    loadDownloads();
  });
  document.querySelectorAll("[data-dl-filter]").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("[data-dl-filter]").forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      dlFilter = btn.dataset.dlFilter;
      syncRouteToHash();
      renderDownloads();
    });
  });
  document.querySelectorAll("[data-dl-batch]").forEach(btn => {
    btn.addEventListener("click", () => {
      if (dlSelected.size) downloadAction(btn.dataset.dlBatch, [...dlSelected].slice(0, 500));
    });
  });
  $("btnDlSelectAll").addEventListener("click", () => {
    (dlData ? dlData.jobs.filter(dlMatches) : []).forEach(job => dlSelected.add(job.id));
    renderDownloads();
  });
  $("btnDlSelNone").addEventListener("click", () => {
    dlSelected.clear();
    renderDownloads();
  });
  $("btnDlClear").addEventListener("click", () => {
    openConfirmModal("清除已结束任务", "从列表中清除所有已完成、失败和已取消的任务？已下载的文件会保留在手机中。", async () => {
      try {
        loadDownloads(await downloadsCall("clear"));
        toast("已清除结束的任务", "i-trash");
      } catch (err) {
        toast(`清除失败：${err.message}`, "i-trash");
      }
    });
  });
  if ($("btnDlRetryAllFailed")) {
    $("btnDlRetryAllFailed").addEventListener("click", () => {
      if (!dlData) return;
      const failedJobs = dlData.jobs.filter(j => j.state === "failed" || j.state === "cancelled");
      if (failedJobs.length) {
        downloadAction("retry", failedJobs.map(j => j.id).slice(0, 500));
      }
    });
  }
  $("dlParallel").addEventListener("change", async e => {
    try {
      loadDownloads(await downloadsCall("parallel", { count: Number(e.target.value) }));
      toast(`同时下载 ${e.target.value} 首`, "i-download");
    } catch (err) {
      toast(`设置失败：${err.message}`, "i-trash");
    }
  });

  // --- Folder-based Files Explorer System ---
  let fileRoots = [];
  let fileCurrentRoot = null;
  let fileCurrentPath = "";
  let fileCurrentDirectoryData = { directories: [], files: [] };
  let fileCurrentTracks = [];

  async function loadFiles() {
    try {
      if (!fileRoots.length) {
        const res = await call("files", { rootsOnly: true });
        fileRoots = res.folders || [];
      }
      if (!fileCurrentRoot && fileRoots.length > 0) {
        const pref = fileRoots.find(r => (r.name || "").toLowerCase().includes("external") || (r.id || "").toLowerCase().includes("external"));
        fileCurrentRoot = pref ? pref.id : fileRoots[0].id;
      }
      renderStorageRoots();
      if (fileCurrentRoot) {
        await loadCurrentDirectory();
      }
    } catch (err) {
      toast(`读取存储根目录失败：${err.message}`, "i-trash");
    }
  }

  function renderStorageRoots() {
    const list = $("storageRootsList");
    list.replaceChildren();
    if (!fileRoots.length) {
      const empty = document.createElement("div");
      empty.className = "folder-empty";
      empty.textContent = "未找到授权存储根目录";
      list.appendChild(empty);
      return;
    }
    fileRoots.forEach(root => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "root-btn" + (root.id === fileCurrentRoot ? " active" : "");

      const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      iconSvg.setAttribute("class", "icon");
      const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
      use.setAttribute("href", "#i-folder");
      iconSvg.appendChild(use);
      btn.appendChild(iconSvg);

      const span = document.createElement("span");
      let displayName = root.name;
      if (displayName.includes("external_sd1")) {
        displayName = "外置 SD 卡 (" + displayName.split(":").pop() + ")";
      } else if (displayName.includes("primary")) {
        displayName = "内置存储 (" + displayName.split(":").pop() + ")";
      }
      span.textContent = displayName;
      btn.appendChild(span);

      btn.addEventListener("click", () => {
        if (fileCurrentRoot === root.id && fileCurrentPath === "") return;
        fileCurrentRoot = root.id;
        fileCurrentPath = "";
        syncRouteToHash();
        renderStorageRoots();
        loadCurrentDirectory();
      });
      list.appendChild(btn);
    });
  }

  async function loadCurrentDirectory() {
    if (!fileCurrentRoot) return;
    $("fileSummaryText").textContent = "正在读取文件夹内容…";
    $("folderEmptyMessage").hidden = true;
    $("subfoldersGrid").replaceChildren();
    $("filesItemList").replaceChildren();
    $("folderAttachmentsList").replaceChildren();
    $("folderAttachmentsSection").hidden = true;
    $("btnPlayFolder").hidden = true;
    $("btnAddFolderToQueue").hidden = true;

    try {
      const res = await call("file-directory", { root: fileCurrentRoot, path: fileCurrentPath });
      fileCurrentDirectoryData = res;
      renderBreadcrumbs();

      const dirs = (res.directories || []).sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      const allFiles = res.files || [];
      const musicFiles = [];
      const attachmentFiles = [];

      allFiles.forEach(f => {
        if (f.attachment) attachmentFiles.push(f);
        else musicFiles.push(f);
      });
      musicFiles.sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      attachmentFiles.sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      // 若默认根目录为空且存在其他非空根目录，自动切换至有内容的根目录
      if (fileCurrentPath === "" && dirs.length === 0 && allFiles.length === 0 && fileRoots.length > 1) {
        const other = fileRoots.find(r => r.id !== fileCurrentRoot);
        if (other) {
          fileCurrentRoot = other.id;
          renderStorageRoots();
          return loadCurrentDirectory();
        }
      }

      fileCurrentTracks = musicFiles;

      // 渲染子文件夹
      $("subfoldersCount").textContent = String(dirs.length);
      $("subfoldersSection").hidden = dirs.length === 0;
      if (dirs.length > 0) {
        const fragDirs = document.createDocumentFragment();
        dirs.forEach(dir => {
          const card = document.createElement("div");
          card.className = "folder-card";
          card.title = dir.name;

          const iconDiv = document.createElement("div");
          iconDiv.className = "folder-icon";
          const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          iconSvg.setAttribute("class", "icon");
          const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
          use.setAttribute("href", "#i-folder");
          iconSvg.appendChild(use);
          iconDiv.appendChild(iconSvg);
          card.appendChild(iconDiv);

          const nameSpan = document.createElement("span");
          nameSpan.className = "folder-name";
          nameSpan.textContent = dir.name;
          card.appendChild(nameSpan);

          card.addEventListener("click", () => {
            fileCurrentPath = fileCurrentPath ? `${fileCurrentPath}/${dir.name}` : dir.name;
            syncRouteToHash();
            loadCurrentDirectory();
          });
          fragDirs.appendChild(card);
        });
        $("subfoldersGrid").appendChild(fragDirs);
      }

      // 渲染音乐文件
      $("folderTracksCount").textContent = String(musicFiles.length);
      $("folderTracksSection").hidden = musicFiles.length === 0;
      if (musicFiles.length > 0) {
        $("btnPlayFolder").hidden = false;
        $("btnAddFolderToQueue").hidden = false;
        const fragFiles = document.createDocumentFragment();
        musicFiles.forEach((file, index) => {
          const li = document.createElement("li");
          li.className = "track-row";

          const numSpan = document.createElement("span");
          numSpan.className = "track-num";
          numSpan.textContent = String(index + 1);
          li.appendChild(numSpan);

          const thumbDiv = document.createElement("div");
          thumbDiv.className = "track-thumb art-loaded";
          thumbDiv.style.background = "rgba(var(--accent-rgb, 10, 207, 131), 0.15)";
          thumbDiv.style.color = "var(--accent)";
          thumbDiv.style.display = "grid";
          thumbDiv.style.placeItems = "center";
          const mIcon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          mIcon.setAttribute("class", "icon");
          const mUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          mUse.setAttribute("href", "#i-music");
          mIcon.appendChild(mUse);
          thumbDiv.appendChild(mIcon);
          li.appendChild(thumbDiv);

          const infoDiv = document.createElement("div");
          infoDiv.className = "track-info";
          const titleDiv = document.createElement("div");
          titleDiv.className = "track-title";
          titleDiv.textContent = file.name;
          infoDiv.appendChild(titleDiv);

          const subDiv = document.createElement("div");
          subDiv.className = "track-subtitle";
          subDiv.textContent = (file.name.split(".").pop() || "").toUpperCase() + " 音频文件";
          infoDiv.appendChild(subDiv);
          li.appendChild(infoDiv);

          const actionsDiv = document.createElement("div");
          actionsDiv.className = "track-actions";

          const btnPlay = document.createElement("button");
          btnPlay.type = "button";
          btnPlay.className = "icon-btn";
          btnPlay.title = "立即播放";
          const pSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          pSvg.setAttribute("class", "icon");
          const pUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          pUse.setAttribute("href", "#i-play");
          pSvg.appendChild(pUse);
          btnPlay.appendChild(pSvg);
          btnPlay.addEventListener("click", e => {
            e.stopPropagation();
            call("add", { id: file.id, next: true });
            call("next");
            toast(`正在播放：${file.name}`, "i-play");
          });
          actionsDiv.appendChild(btnPlay);

          const btnAdd = document.createElement("button");
          btnAdd.type = "button";
          btnAdd.className = "icon-btn hover-only";
          btnAdd.title = "加入下一首播放";
          const aSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          aSvg.setAttribute("class", "icon");
          const aUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          aUse.setAttribute("href", "#i-plus");
          aSvg.appendChild(aUse);
          btnAdd.appendChild(aSvg);
          btnAdd.addEventListener("click", e => {
            e.stopPropagation();
            call("add", { id: file.id, next: true });
            toast(`已加入下一首播放：${file.name}`);
          });
          actionsDiv.appendChild(btnAdd);

          li.appendChild(actionsDiv);

          li.addEventListener("click", () => {
            call("add", { id: file.id, next: true });
            call("next");
            toast(`正在播放：${file.name}`, "i-play");
          });

          fragFiles.appendChild(li);
        });
        $("filesItemList").appendChild(fragFiles);
      }

      // 渲染附件（LRC、JPG 等）
      if (attachmentFiles.length > 0) {
        $("folderAttachmentsCount").textContent = String(attachmentFiles.length);
        $("folderAttachmentsSection").hidden = false;
        const fragAtt = document.createDocumentFragment();
        attachmentFiles.forEach(att => {
          const li = document.createElement("li");
          li.className = "track-row";
          li.style.opacity = "0.75";

          const ext = (att.name.split(".").pop() || "").toLowerCase();
          const isLrc = ext === "lrc";
          const isImg = ["jpg", "jpeg", "png", "webp"].includes(ext);

          const thumbDiv = document.createElement("div");
          thumbDiv.className = "track-thumb art-loaded";
          thumbDiv.style.background = isLrc ? "rgba(10, 132, 255, 0.15)" : "rgba(255, 255, 255, 0.08)";
          thumbDiv.style.color = isLrc ? "var(--accent)" : "var(--muted)";
          thumbDiv.style.display = "grid";
          thumbDiv.style.placeItems = "center";
          const attIcon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          attIcon.setAttribute("class", "icon");
          const attUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          attUse.setAttribute("href", isLrc ? "#i-lyrics" : isImg ? "#i-music" : "#i-folder");
          attIcon.appendChild(attUse);
          thumbDiv.appendChild(attIcon);
          li.appendChild(thumbDiv);

          const infoDiv = document.createElement("div");
          infoDiv.className = "track-info";
          const titleDiv = document.createElement("div");
          titleDiv.className = "track-title";
          titleDiv.textContent = att.name;
          infoDiv.appendChild(titleDiv);
          const subDiv = document.createElement("div");
          subDiv.className = "track-subtitle";
          subDiv.textContent = isLrc ? "歌词文件 (LRC)" : isImg ? "专辑封面图片" : "附件文件";
          infoDiv.appendChild(subDiv);
          li.appendChild(infoDiv);

          fragAtt.appendChild(li);
        });
        $("folderAttachmentsList").appendChild(fragAtt);
      }

      if (dirs.length === 0 && musicFiles.length === 0 && attachmentFiles.length === 0) {
        $("folderEmptyMessage").hidden = false;
      }

      const sumParts = [];
      if (dirs.length) sumParts.push(`${dirs.length} 个文件夹`);
      if (musicFiles.length) sumParts.push(`${musicFiles.length} 首音乐`);
      if (attachmentFiles.length) sumParts.push(`${attachmentFiles.length} 个附件`);
      $("fileSummaryText").textContent = sumParts.length ? sumParts.join(" · ") : "此文件夹为空";
      restoreScrollForView("files");
    } catch (err) {
      toast(`读取目录失败：${err.message}`, "i-trash");
      $("fileSummaryText").textContent = err.message;
    }
  }

  function renderBreadcrumbs() {
    const bar = $("fileBreadcrumbs");
    bar.replaceChildren();

    const rootObj = fileRoots.find(r => r.id === fileCurrentRoot);
    let rootName = "根目录";
    if (rootObj) {
      if (rootObj.name.includes("external")) rootName = "外置SD卡";
      else if (rootObj.name.includes("primary")) rootName = "内置存储";
      else rootName = rootObj.name.split(":").pop();
    }

    const pathSegments = fileCurrentPath ? fileCurrentPath.split("/").filter(Boolean) : [];
    $("btnParentFolder").hidden = pathSegments.length === 0;

    const rootItem = document.createElement("span");
    rootItem.className = "breadcrumb-item" + (pathSegments.length === 0 ? " current" : "");
    rootItem.textContent = rootName;
    rootItem.addEventListener("click", () => {
      if (fileCurrentPath === "") return;
      fileCurrentPath = "";
      syncRouteToHash();
      loadCurrentDirectory();
    });
    bar.appendChild(rootItem);

    let accumulatedPath = "";
    pathSegments.forEach((segment, idx) => {
      const sep = document.createElement("span");
      sep.className = "breadcrumb-sep";
      sep.textContent = "/";
      bar.appendChild(sep);

      accumulatedPath = accumulatedPath ? `${accumulatedPath}/${segment}` : segment;
      const thisPath = accumulatedPath;
      const isLast = idx === pathSegments.length - 1;

      const item = document.createElement("span");
      item.className = "breadcrumb-item" + (isLast ? " current" : "");
      item.textContent = segment;
      if (!isLast) {
        item.addEventListener("click", () => {
          fileCurrentPath = thisPath;
          syncRouteToHash();
          loadCurrentDirectory();
        });
      }
      bar.appendChild(item);
    });
  }

  $("btnParentFolder").addEventListener("click", () => {
    if (!fileCurrentPath) return;
    const parts = fileCurrentPath.split("/").filter(Boolean);
    parts.pop();
    fileCurrentPath = parts.join("/");
    syncRouteToHash();
    loadCurrentDirectory();
  });

  $("btnPlayFolder").addEventListener("click", async () => {
    if (!fileCurrentTracks.length) return;
    try {
      const trackIds = fileCurrentTracks.map(t => ({ id: t.id }));
      await call("play-list", { tracks: trackIds });
      toast(`开始播放文件夹：${trackIds.length} 首音乐`, "i-play");
    } catch (err) {
      toast(`播放失败：${err.message}`, "i-trash");
    }
  });

  $("btnAddFolderToQueue").addEventListener("click", async () => {
    if (!fileCurrentTracks.length) return;
    try {
      for (const t of fileCurrentTracks) {
        await call("add", { id: t.id, next: false });
      }
      toast(`已将 ${fileCurrentTracks.length} 首音乐加入队列`, "i-queue");
    } catch (err) {
      toast(`加入队列失败：${err.message}`, "i-trash");
    }
  });

  // --- Multi-Directory Upload Management System ---
  const AUDIO_EXTS = new Set([
    "flac", "mp3", "wav", "m4a", "aac", "ogg", "aif", "aiff", "dsf", "dff", "ape", "alac", "opus", "wma"
  ]);
  const ATTACHMENT_EXTS = new Set([
    "jpg", "jpeg", "png", "webp", "gif", "lrc"
  ]);

  function getFileExt(filename) {
    const dot = filename.lastIndexOf(".");
    return dot > 0 ? filename.slice(dot + 1).toLowerCase() : "";
  }

  function isAllowedUploadFile(file) {
    if (!file || !file.name || file.name.startsWith(".")) return false;
    const ext = getFileExt(file.name);
    return AUDIO_EXTS.has(ext) || ATTACHMENT_EXTS.has(ext);
  }

  function formatFileSize(bytes) {
    if (!bytes || bytes <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + " " + units[i];
  }

  // 待上传文件夹分组数据
  let uploadFolderGroups = [];
  let uploadAbortController = null;
  let isUploading = false;
  let uploadAnySuccess = false;

  function openUploadFolderModal() {
    $("uploadFolderModal").classList.add("active");
    $("uploadConfigSection").hidden = false;
    $("uploadProgressSection").hidden = true;
    $("uploadSummaryResult").hidden = true;
    $("uploadSummaryResult").replaceChildren();
    $("btnUploadAbort").hidden = false;
    $("btnUploadFinish").hidden = true;

    // 显示目标目录
    let rootName = "当前存储根目录";
    const rootObj = fileRoots.find(r => r.id === fileCurrentRoot);
    if (rootObj) {
      if (rootObj.name.includes("external")) rootName = "外置SD卡 (" + rootObj.name.split(":").pop() + ")";
      else if (rootObj.name.includes("primary")) rootName = "内置存储 (" + rootObj.name.split(":").pop() + ")";
      else rootName = rootObj.name;
    }
    const fullTargetDisplay = fileCurrentPath ? `${rootName} / ${fileCurrentPath}` : rootName;
    $("uploadTargetDirName").textContent = fullTargetDisplay;
    renderUploadFolderGroups();
  }

  function closeUploadFolderModal() {
    if (isUploading) {
      if (!confirm("上传仍在进行中，确定要终止并关闭吗？")) return;
      if (uploadAbortController) {
        uploadAbortController.abort = true;
        if (uploadAbortController.xhr) {
          try { uploadAbortController.xhr.abort(); } catch (_) {}
        }
      }
    }
    $("uploadFolderModal").classList.remove("active");
    if (uploadAnySuccess) {
      uploadAnySuccess = false;
      loadCurrentDirectory();
    }
  }

  // 解析选择的文件集合，智能归纳上级目录与子目录
  function parseAndAppendFiles(fileList) {
    const rawFiles = Array.from(fileList);
    const validFiles = rawFiles.filter(isAllowedUploadFile);

    if (validFiles.length === 0) {
      toast("未找到支持的音频、歌词或封面文件", "i-trash");
      return;
    }

    const map = new Map();
    // 现有 groups 加入 map 索引以支持追加去重
    uploadFolderGroups.forEach(g => map.set(g.key, g));

    validFiles.forEach(file => {
      const relPath = file.webkitRelativePath || file.name;
      const parts = relPath.split("/").filter(Boolean);

      let parentName = "";
      let folderName = "";
      let relSubDir = "";
      let isRootGroup = false;

      if (parts.length <= 1) {
        // 单独文件（无层级）
        parentName = "";
        folderName = "散装文件";
        relSubDir = "";
        isRootGroup = true;
      } else if (parts.length === 2) {
        // 例如：范特西/01.flac
        parentName = parts[0];
        folderName = parts[0];
        relSubDir = "";
        isRootGroup = false;
      } else {
        // parts.length >= 3，例如：周杰伦/范特西/01.flac 或 周杰伦/范特西/CD1/01.flac
        parentName = parts[0];
        folderName = parts[1];
        // 内部相对子目录（例如 CD1）
        relSubDir = parts.slice(2, parts.length - 1).join("/");
        isRootGroup = false;
      }

      const key = `${parentName}:::${folderName}:::${isRootGroup}`;
      let group = map.get(key);
      if (!group) {
        group = {
          key,
          parentName,
          folderName,
          isRootGroup,
          files: [],
          musicCount: 0,
          attachCount: 0,
          totalBytes: 0,
          checked: true
        };
        map.set(key, group);
      }

      // 避免同一文件重复加入
      if (!group.files.some(item => item.file.name === file.name && item.file.size === file.size && item.file.lastModified === file.lastModified)) {
        const ext = getFileExt(file.name);
        const isMusic = AUDIO_EXTS.has(ext);
        group.files.push({
          file,
          parentName,
          folderName,
          relSubDir,
          isRootGroup
        });
        if (isMusic) group.musicCount++;
        else group.attachCount++;
        group.totalBytes += file.size;
      }
    });

    uploadFolderGroups = Array.from(map.values()).sort((a, b) => {
      if (a.parentName !== b.parentName) {
        return a.parentName.localeCompare(b.parentName, "zh-CN", { numeric: true });
      }
      return a.folderName.localeCompare(b.folderName, "zh-CN", { numeric: true });
    });

    openUploadFolderModal();
  }
  window.tidalParseAndAppendFiles = parseAndAppendFiles;

  // 渲染待上传子目录列表
  function renderUploadFolderGroups() {
    const list = $("uploadSubfoldersList");
    list.replaceChildren();

    if (uploadFolderGroups.length === 0) {
      const empty = document.createElement("div");
      empty.className = "folder-empty";
      empty.textContent = "未选择任何文件夹";
      list.appendChild(empty);
      updateUploadSummary();
      return;
    }

    const frag = document.createDocumentFragment();
    uploadFolderGroups.forEach(group => {
      const item = document.createElement("div");
      item.className = "upload-folder-item" + (group.checked ? " selected" : "");

      // 左侧信息
      const info = document.createElement("div");
      info.className = "upload-folder-info";

      const chk = document.createElement("input");
      chk.type = "checkbox";
      chk.checked = group.checked;
      chk.style.marginRight = "6px";
      chk.addEventListener("click", e => e.stopPropagation());
      chk.addEventListener("change", () => {
        group.checked = chk.checked;
        item.classList.toggle("selected", group.checked);
        updateUploadSummary();
      });
      info.appendChild(chk);

      const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      iconSvg.setAttribute("class", "icon");
      iconSvg.style.color = "var(--accent)";
      const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
      use.setAttribute("href", group.isRootGroup ? "#i-music" : "#i-folder");
      iconSvg.appendChild(use);
      info.appendChild(iconSvg);

      const meta = document.createElement("div");
      meta.className = "upload-folder-meta";

      const nameEl = document.createElement("div");
      nameEl.className = "upload-folder-name";
      nameEl.textContent = group.folderName;
      meta.appendChild(nameEl);

      if (group.parentName && group.parentName !== group.folderName) {
        const parentEl = document.createElement("div");
        parentEl.className = "upload-folder-parent";
        parentEl.textContent = `所属上级: ${group.parentName}`;
        meta.appendChild(parentEl);
      }
      info.appendChild(meta);
      item.appendChild(info);

      // 右侧统计
      const stats = document.createElement("div");
      stats.className = "upload-folder-stats";
      const parts = [];
      if (group.musicCount > 0) parts.push(`${group.musicCount} 首音乐`);
      if (group.attachCount > 0) parts.push(`${group.attachCount} 附件`);
      parts.push(formatFileSize(group.totalBytes));
      stats.textContent = parts.join(" · ");
      item.appendChild(stats);

      // 卡片点击反转
      item.addEventListener("click", () => {
        group.checked = !group.checked;
        chk.checked = group.checked;
        item.classList.toggle("selected", group.checked);
        updateUploadSummary();
      });

      frag.appendChild(item);
    });

    list.appendChild(frag);
    updateUploadSummary();
  }

  function updateUploadSummary() {
    const selectedGroups = uploadFolderGroups.filter(g => g.checked);
    $("uploadSelectedFolderCount").textContent = String(selectedGroups.length);
    $("uploadTotalFolderCount").textContent = String(uploadFolderGroups.length);

    let totalFiles = 0;
    let totalBytes = 0;
    selectedGroups.forEach(g => {
      totalFiles += g.files.length;
      totalBytes += g.totalBytes;
    });

    $("uploadSummaryBadge").textContent = `${totalFiles} 个文件 · ${formatFileSize(totalBytes)}`;
    $("btnUploadFolderStart").disabled = totalFiles === 0;
  }

  // 执行批量上传
  async function startBatchUpload() {
    const selectedGroups = uploadFolderGroups.filter(g => g.checked);
    if (selectedGroups.length === 0) {
      toast("请至少勾选一个要上传的子目录", "i-trash");
      return;
    }

    if (!fileCurrentRoot) {
      toast("请先选择授权存储根目录", "i-trash");
      return;
    }

    const keepParent = $("chkKeepParentFolder").checked;
    const policy = document.querySelector('input[name="uploadConflictPolicy"]:checked')?.value || "skip";

    // 收集所有待上传文件条目
    const queue = [];
    selectedGroups.forEach(group => {
      group.files.forEach(item => {
        let dirParts = [];
        // 如果当前在某个子目录内，以当前子目录为前缀
        if (fileCurrentPath) {
          dirParts.push(fileCurrentPath);
        }

        if (keepParent && item.parentName) {
          dirParts.push(item.parentName);
        }

        // 如果不是根目录散装，且 (未保留上级目录 或 folderName 与 parentName 不同)
        if (!item.isRootGroup) {
          if (!keepParent || item.folderName !== item.parentName) {
            dirParts.push(item.folderName);
          }
        }

        if (item.relSubDir) {
          dirParts.push(item.relSubDir);
        }

        const targetDirectory = dirParts.filter(Boolean).join("/");
        queue.push({
          file: item.file,
          targetDirectory,
          displayName: item.file.name,
          size: item.file.size
        });
      });
    });

    if (queue.length === 0) {
      toast("待上传文件列表为空", "i-trash");
      return;
    }

    // 切换至进度视图
    isUploading = true;
    uploadAnySuccess = false;
    $("uploadConfigSection").hidden = true;
    $("uploadProgressSection").hidden = false;
    $("uploadSummaryResult").hidden = true;
    $("btnUploadAbort").hidden = false;
    $("btnUploadFinish").hidden = true;

    uploadAbortController = { abort: false, xhr: null };

    const totalFilesCount = queue.length;
    let totalBytesAll = queue.reduce((sum, item) => sum + item.size, 0);
    let bytesUploadedTotal = 0;
    let successCount = 0;
    let skippedCount = 0;
    let failedCount = 0;
    const logs = [];

    $("uploadProgressStatusText").textContent = `正在上传 (1/${totalFilesCount})…`;
    $("uploadProgressRatioText").textContent = `1 / ${totalFilesCount}`;
    $("uploadProgressFill").style.width = "0%";

    for (let i = 0; i < queue.length; i++) {
      if (uploadAbortController.abort) {
        logs.push(`⚠️ 上传已由用户终止`);
        break;
      }

      const item = queue[i];
      $("uploadProgressStatusText").textContent = `正在上传 (${i + 1}/${totalFilesCount})…`;
      $("uploadProgressRatioText").textContent = `${i + 1} / ${totalFilesCount}`;
      $("uploadCurrentFileName").textContent = item.targetDirectory ? `${item.targetDirectory}/${item.displayName}` : item.displayName;
      $("uploadCurrentFileProgress").textContent = `0 B / ${formatFileSize(item.size)}`;

      let currentFileLoaded = 0;

      try {
        const res = await new Promise((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          uploadAbortController.xhr = xhr;

          xhr.upload.onprogress = (e) => {
            if (e.lengthComputable) {
              currentFileLoaded = e.loaded;
              const overallLoaded = bytesUploadedTotal + currentFileLoaded;
              const percent = Math.min(100, Math.round((overallLoaded / (totalBytesAll || 1)) * 100));
              $("uploadProgressFill").style.width = `${percent}%`;
              $("uploadCurrentFileProgress").textContent = `${formatFileSize(e.loaded)} / ${formatFileSize(e.total)}`;
              $("uploadTotalSizeProgress").textContent = `${formatFileSize(overallLoaded)} / ${formatFileSize(totalBytesAll)} · ${percent}%`;
            }
          };

          xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
              try {
                resolve(JSON.parse(xhr.responseText || "{}"));
              } catch (_) {
                resolve({ status: "ok" });
              }
            } else {
              try {
                const errObj = JSON.parse(xhr.responseText || "{}");
                reject(new Error(errObj.error || `HTTP ${xhr.status}`));
              } catch (_) {
                reject(new Error(`HTTP ${xhr.status}`));
              }
            }
          };

          xhr.onerror = () => reject(new Error("网络传输失败"));
          xhr.ontimeout = () => reject(new Error("上传超时"));
          xhr.onabort = () => reject(new Error("上传已取消"));

          xhr.open("POST", "/api/upload");
          if (key) {
            xhr.setRequestHeader("Authorization", "Bearer " + key);
          }
          xhr.setRequestHeader("Content-Type", "application/octet-stream");
          xhr.setRequestHeader("X-File-Name", encodeURIComponent(item.file.name));
          xhr.setRequestHeader("X-Upload-Folder", encodeURIComponent(fileCurrentRoot));
          xhr.setRequestHeader("X-Upload-Directory", encodeURIComponent(item.targetDirectory));
          xhr.setRequestHeader("X-Upload-Conflict", policy);
          xhr.timeout = 300000;
          xhr.send(item.file);
        });

        bytesUploadedTotal += item.size;
        if (res.status === "skipped") {
          skippedCount++;
          logs.push(`⚪ 已跳过: ${item.displayName} (已存在)`);
        } else {
          successCount++;
          uploadAnySuccess = true;
          logs.push(`✅ 成功: ${item.displayName}`);
        }
      } catch (err) {
        if (uploadAbortController.abort) break;
        failedCount++;
        bytesUploadedTotal += item.size;
        logs.push(`❌ 失败: ${item.displayName} (${err.message})`);
      }
    }

    isUploading = false;
    $("btnUploadAbort").hidden = true;
    $("btnUploadFinish").hidden = false;
    $("uploadProgressFill").style.width = "100%";

    if (uploadAbortController.abort) {
      $("uploadProgressStatusText").textContent = "上传已中断";
      $("uploadProgressStatusText").style.color = "var(--danger)";
    } else {
      $("uploadProgressStatusText").textContent = "全部上传完成！";
      $("uploadProgressStatusText").style.color = "var(--accent)";
    }

    const summaryResultEl = $("uploadSummaryResult");
    summaryResultEl.hidden = false;
    summaryResultEl.replaceChildren();

    const summaryTitle = document.createElement("div");
    summaryTitle.style.fontWeight = "bold";
    summaryTitle.style.marginBottom = "8px";
    summaryTitle.textContent = `上传统计：成功 ${successCount} 个，跳过 ${skippedCount} 个，失败 ${failedCount} 个`;
    summaryResultEl.appendChild(summaryTitle);

    const logList = document.createElement("div");
    logList.style.display = "flex";
    logList.style.flexDirection = "column";
    logList.style.gap = "4px";
    logs.slice(-20).forEach(msg => {
      const line = document.createElement("div");
      line.style.fontSize = "12px";
      line.style.color = msg.startsWith("❌") ? "var(--danger)" : msg.startsWith("⚪") ? "var(--muted)" : "var(--ink)";
      line.textContent = msg;
      logList.appendChild(line);
    });
    if (logs.length > 20) {
      const more = document.createElement("div");
      more.style.fontSize = "11.5px";
      more.style.color = "var(--muted)";
      more.textContent = `… 以及其他 ${logs.length - 20} 个文件`;
      logList.appendChild(more);
    }
    summaryResultEl.appendChild(logList);

    if (uploadAnySuccess) {
      toast(`成功上传 ${successCount} 个文件`, "i-upload");
    }
  }

  // 绑定文件夹选择与上传相关事件
  $("btnUploadFolder").addEventListener("click", () => {
    uploadFolderGroups = [];
    $("uploadFolderInput").value = "";
    $("uploadFolderInput").click();
  });

  $("uploadFolderInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadAddMoreFolders").addEventListener("click", () => {
    $("uploadMoreFolderInput").value = "";
    $("uploadMoreFolderInput").click();
  });

  $("uploadMoreFolderInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadFiles").addEventListener("click", () => {
    $("uploadFileInput").value = "";
    $("uploadFileInput").click();
  });

  $("uploadFileInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadSelectAll").addEventListener("click", () => {
    uploadFolderGroups.forEach(g => g.checked = true);
    renderUploadFolderGroups();
  });

  $("btnUploadDeselectAll").addEventListener("click", () => {
    uploadFolderGroups.forEach(g => g.checked = false);
    renderUploadFolderGroups();
  });

  $("btnUploadFolderStart").addEventListener("click", startBatchUpload);

  $("btnUploadAbort").addEventListener("click", () => {
    if (confirm("确定要终止正在进行的上传任务吗？")) {
      if (uploadAbortController) {
        uploadAbortController.abort = true;
        if (uploadAbortController.xhr) {
          try { uploadAbortController.xhr.abort(); } catch (_) {}
        }
      }
    }
  });

  $("btnUploadFinish").addEventListener("click", closeUploadFolderModal);
  $("btnUploadFolderModalClose").addEventListener("click", closeUploadFolderModal);
  $("btnUploadFolderCancel").addEventListener("click", closeUploadFolderModal);

  // 支持拖拽上传至文件页面
  const viewFilesEl = $("viewFiles");
  if (viewFilesEl) {
    viewFilesEl.addEventListener("dragover", e => {
      e.preventDefault();
      e.stopPropagation();
    });
    viewFilesEl.addEventListener("drop", e => {
      e.preventDefault();
      e.stopPropagation();
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        parseAndAppendFiles(e.dataTransfer.files);
      }
    });
  }

  $("btnRefreshFiles").addEventListener("click", loadCurrentDirectory);

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
      loadDownloads();
      applyRouteFromHash(location.hash);
    }
  });

})();
