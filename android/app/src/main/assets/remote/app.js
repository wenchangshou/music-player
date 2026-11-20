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
  let lastVolumeUserActionTime = 0;
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
  let autoDjEnabled = true; // 以手机上报的状态为准
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
    t.innerHTML = `<svg class="icon" style="width:18px;height:18px;"><use href="#${icon}"/></svg><span></span>`;
    // 消息里常带曲名、专辑名和 AI 生成的文字，只作为纯文本插入。
    t.querySelector("span").textContent = message;
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
    const suffix = central && device ? "?device=" + encodeURIComponent(device) : "";
    let res;
    if (!central) {
      res = await fetchJson("/api/control", { method: "POST", headers, body: JSON.stringify({ op, ...fields }) });
    } else {
      res = await fetchJson("/api/control" + suffix, { method: "POST", headers, body: JSON.stringify({ op, ...fields }) });
    }
    if (res && res.queue && Array.isArray(res.queue)) {
      renderPlayback(res);
    }
    return res;
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
      renderAiMoodRadioChips("aiMoodRadioChipsLib");
    }
    if (viewId === "search") {
      renderAiMoodRadioChips("aiMoodRadioChipsSearch");
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
    img.loading = "lazy";
    img.decoding = "async";
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
    // Asymmetric hysteresis: prevent jittering back to previous line due to small network jitter or polling latency.
    // Only allow stepping backward if currentTime rewound significantly (> 0.45s before active line's start).
    if (activeLyricIndex >= 0 && newIndex < activeLyricIndex) {
      const activeLineTime = currentLyrics[activeLyricIndex]?.time ?? 0;
      if (currentTime + 0.25 >= activeLineTime - 0.45) {
        return;
      }
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
      li.addEventListener("click", async e => {
        if (e.target.closest(".btn-remove")) {
          e.stopPropagation();
          const rev = lastState ? (lastState.revision ?? lastState.queueRevision) : null;
          try {
            const res = await call("remove", rev != null ? { index, revision: rev } : { index });
            if (res && res.queue) renderPlayback(res);
            toast(`已移出队列：${item.title}`);
          } catch (err) {
            toast(`移除失败：${err.message}`, "i-trash");
          }
          return;
        }
        const rev = lastState ? (lastState.revision ?? lastState.queueRevision) : null;
        try {
          const res = await call("play", rev != null ? { index, revision: rev } : { index });
          if (res && res.queue) renderPlayback(res);
          toast(`切换播放：${item.title}`);
        } catch (err) {
          toast(`切换歌曲失败：${err.message}`, "i-trash");
        }
      });
      frag.appendChild(li);
    });
    list.appendChild(frag);
  }

  $("btnClearQueue").addEventListener("click", () => {
    call("clear");
    toast("已清空后续队列");
  });

  const btnAutoDj = $("btnToggleAutoDj");
  if (btnAutoDj) {
    // 续播由手机自己执行（不依赖打开的网页），这里只读写手机上的开关。
    btnAutoDj.classList.toggle("active", autoDjEnabled);
    btnAutoDj.addEventListener("click", async () => {
      const enabled = !autoDjEnabled;
      try {
        await call("library", { action: "autodj", enabled });
        toast(enabled ? "✨ 已开启智能续播：队列快播完时，手机会自动续上同频曲目" : "已关闭智能续播", "i-sparkles");
      } catch (err) {
        toast(`设置失败：${err.message}`, "i-trash");
      }
    });
  }

  // --- Side Queue / Side Lyrics / Side Notes / Side Search Tab Switching ---
  function switchSideTab(tab) {
    $("tabSideQueue").setAttribute("aria-current", String(tab === "queue"));
    $("tabSideLyrics").setAttribute("aria-current", String(tab === "lyrics"));
    if ($("tabSideNotes")) $("tabSideNotes").setAttribute("aria-current", String(tab === "notes"));
    $("tabSideSearch").setAttribute("aria-current", String(tab === "search"));
    $("sideQueueContent").hidden = tab !== "queue";
    $("sideLyricsContent").hidden = tab !== "lyrics";
    if ($("sideNotesContent")) $("sideNotesContent").hidden = tab !== "notes";
    $("sideSearchContent").hidden = tab !== "search";
    if (tab === "lyrics" && lastState) {
      const track = lastState.queue?.[lastState.index];
      if (track) fetchAndRenderLyrics(track);
      let pos = (lastState.position || 0) / 1000;
      activeLyricIndex = -1;
      syncLyricsPosition(pos);
    }
    if (tab === "notes") {
      renderSideNotes();
    }
    if (activeView === "player") syncRouteToHash();
    if (tab === "search") {
      $("sideSearchInput").focus();
    }
  }

  $("tabSideQueue").addEventListener("click", () => switchSideTab("queue"));
  $("tabSideLyrics").addEventListener("click", () => switchSideTab("lyrics"));
  if ($("tabSideNotes")) $("tabSideNotes").addEventListener("click", () => switchSideTab("notes"));
  if ($("btnOpenNotes")) $("btnOpenNotes").addEventListener("click", () => switchSideTab("notes"));
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

  async function playTracks(tracks, shuffle = false, index = 0) {
    if (!tracks || !tracks.length) return;
    try {
      const items = tracks.map(t => ({
        id: t.id,
        title: t.title,
        artist: t.artist,
        album: t.album,
        cover: t.cover
      }));
      const res = await call("library", { action: "play-list", tracks: items, shuffle, index });
      if (res && res.queue) renderPlayback(res);
      const title = tracks[index]?.title || tracks[0]?.title;
      toast(shuffle ? "已开始随机播放" : `开始播放「${title}」`);
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
      hideLibraryAiBanner();
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
          if (!append && !currentPlaylist) {
            updateLibraryAiSearch($("librarySearchInput").value.trim(), res.total ?? (res.tracks || []).length, rev);
          }
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
        else if (localSubCat === "ai-recommend") {
          $("libraryTitle").textContent = "✨ 猜你喜欢 · 个性化推荐";
          $("libraryTrackList").hidden = true;
          $("libraryCardsView").hidden = false;
          await executeAiCuratedLibrary(rev);
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
          let groups = res.groups || [];
          if (localSubCat === "5") {
            groups = enrichGenreGroups(groups);
          }
          if (res.total !== undefined) {
            $("libraryCountText").textContent = `共 ${groups.length} 个${name}`;
          }
          renderGeneralGroupCards(groups, name, $("libraryCardsView"));
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
    tracks.forEach((track, idx) => {
      const li = createTrackRow(track, () => {
        if (tracks.length > 1) {
          playTracks(tracks, false, idx);
        } else {
          call("library", songRequest(track, "play"))
            .then(res => {
              if (res && res.queue) renderPlayback(res);
              toast(`开始播放「${track.title}」`);
            })
            .catch(err => toast(`播放失败：${err.message}`, "i-trash"));
        }
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
        playTracks(tracks, false, index);
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
          <img alt="" loading="lazy" decoding="async">
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
    if ($("albumAiCuratorCard")) $("albumAiCuratorCard").hidden = true;
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
      if (typeof renderAlbumDetailCurator === "function") {
        renderAlbumDetailCurator(album, tracks, "album");
      }
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
    if ($("albumAiCuratorCard")) $("albumAiCuratorCard").hidden = true;
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
      if ($("albumAiCuratorCard")) $("albumAiCuratorCard").hidden = true;
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
      const isAi = currentSearchSource === "ai";
      if ($("aiInspirationChips")) {
        $("aiInspirationChips").hidden = !isAi;
        if (isAi) renderAiMoodRadioChips("aiMoodRadioChipsSearch");
      }
      if ($("searchKindTabs")) {
        $("searchKindTabs").hidden = false;
        const playlistBtn = document.querySelector('[data-search-kind="PLAYLIST"]');
        if (playlistBtn) playlistBtn.hidden = isAi;
      }
      updateAiNowPlayingSeed();
      updateSearchPlaceholder();
      if (!isAi || $("globalSearchInput").value.trim()) {
        executeGlobalSearch();
      }
    });
  });

  // Like the app: songs, albums, playlists and artists, from TIDAL, NetEase and the local library.
  let currentSearchKind = "TRACK";
  let searchRevision = 0;
  let searchDetail = null;
  const KIND_NAMES = { TRACK: "歌曲", ALBUM: "专辑", PLAYLIST: "歌单", ARTIST: "艺人" };
  const SOURCE_NAMES = { tidal: "TIDAL", netease: "网易云", local: "本地" };

  function updateSearchPlaceholder() {
    const input = $("globalSearchInput");
    if (!input) return;
    const isAi = currentSearchSource === "ai";
    if (isAi) {
      if (currentSearchKind === "TRACK") {
        input.placeholder = "AI 寻歌（单曲）：用自然语言描述曲风、情绪或高光声学细节（如：大提琴松香泛音、低频瞬态试音单曲）…";
      } else if (currentSearchKind === "ARTIST") {
        input.placeholder = "AI 寻艺人：描述大师风范、演奏乐器或艺术流派（如：类似久石让的现代配乐大师、清冷北欧小号手）…";
      } else {
        input.placeholder = "AI 寻唱片（专辑）：描述母带录音、编曲风格或听音场景（如：ECM空间感爵士名盘、80年代模拟磁带录音）…";
      }
    } else {
      input.placeholder = `搜索${KIND_NAMES[currentSearchKind] || "音乐"}…`;
    }
  }

  const searchKindButtons = document.querySelectorAll("[data-search-kind]");
  searchKindButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      searchKindButtons.forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      currentSearchKind = btn.dataset.searchKind;
      updateSearchPlaceholder();
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
    if ($("aiCuratorBanner")) $("aiCuratorBanner").hidden = true;
    if ($("aiTurnBadge")) $("aiTurnBadge").hidden = true;
    if ($("btnAiResetTuning")) $("btnAiResetTuning").hidden = true;
    selectedAiCardIds.clear();
    updateAiSelectedCount();
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

    if (currentSearchSource === "ai") {
      await executeAiSearch(q, rev);
      return;
    }

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
    if ($("searchAlbumAiCuratorCard")) $("searchAlbumAiCuratorCard").hidden = true;
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
      if (kind === "ALBUM" && typeof renderAlbumDetailCurator === "function") {
        renderAlbumDetailCurator(item, tracks, "searchAlbum");
      }
    } catch (err) {
      if (rev === searchRevision) searchMessage(err.message, true);
    }
  }

  $("btnSearchBack").addEventListener("click", () => {
    if ($("searchAlbumAiCuratorCard")) $("searchAlbumAiCuratorCard").hidden = true;
    executeGlobalSearch();
  });

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
      await call("library", { action: "play-list", tracks: trackIds });
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
      const suffix = central && device ? "?device=" + encodeURIComponent(device) : "";
      const res = await fetchJson("/api/state" + suffix, {}, 8000);
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

  function escapeHtml(str) {
    if (!str) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }


  // =========================================================================
  // AI 底座：服务端代理调用、可用性探测、场景预算、本地缓存与请求去重
  // 密钥只保存在手机（局域网模式）或中转服务器（多端模式），浏览器只发送对话内容。
  // =========================================================================

  const AI_NOTES_PREFIX = "tidal_ai_notes_";
  const AI_GENRES_KEY = "tidal_ai_genres";
  const AI_CACHE_PREFIX = "tidal_ai_c1_";
  const AI_BUDGET_KEY = "tidal_ai_budget";
  // 每个场景每小时最多调用次数，防止自动触发的功能失控消耗额度。
  const AI_BUDGETS = { autodj: 10, bubble: 20, notes: 30, album: 30, search: 20, mood: 20, genre: 80, dlrec: 10, profile: 6, test: 10, default: 30 };

  const PROVIDER_PRESETS = {
    ark: { endpoint: "https://ark.cn-beijing.volces.com/api/v3", model: "deepseek-v4-1-flash-261017" },
    deepseek: { endpoint: "https://api.deepseek.com", model: "deepseek-chat" },
    openai: { endpoint: "https://api.openai.com/v1", model: "gpt-4o-mini" },
    ollama: { endpoint: "", model: "qwen2.5:latest", hint: "http://电脑局域网IP:11434/v1" },
    custom: { endpoint: "", model: "" }
  };

  // 旧版把密钥存在浏览器里，这里清掉。
  try { localStorage.removeItem("tidal_ai_config"); } catch {}

  class AiUnavailableError extends Error {
    constructor(message) {
      super(message);
      this.name = "AiUnavailableError";
    }
  }

  let aiStatus = { loaded: false, configured: false, managed: central, model: "" };
  let aiStatusAt = 0;
  let aiStatusPromise = null;

  async function refreshAiStatus(force = false) {
    if (!force && aiStatus.loaded && Date.now() - aiStatusAt < 60000) return aiStatus;
    if (aiStatusPromise) return aiStatusPromise;
    aiStatusPromise = fetchJson("/api/ai/config", {}, 8000)
      .then(res => {
        aiStatus = { ...res, loaded: true };
        aiStatusAt = Date.now();
        return aiStatus;
      })
      .catch(() => aiStatus)
      .finally(() => { aiStatusPromise = null; });
    return aiStatusPromise;
  }

  // 手机离线（online === false）时，AI 增强功能静默隐藏，不等待超时。
  function aiReady() {
    return aiStatus.loaded && aiStatus.configured && aiStatus.online !== false;
  }

  function aiBudgetTake(scene) {
    const limit = AI_BUDGETS[scene] ?? AI_BUDGETS.default;
    const now = Date.now();
    let book = {};
    try { book = JSON.parse(localStorage.getItem(AI_BUDGET_KEY) || "{}") || {}; } catch {}
    const recent = (Array.isArray(book[scene]) ? book[scene] : []).filter(t => now - t < 3600000);
    if (recent.length >= limit) return false;
    recent.push(now);
    book[scene] = recent;
    try { localStorage.setItem(AI_BUDGET_KEY, JSON.stringify(book)); } catch {}
    return true;
  }

  function extractJson(text) {
    if (!text) return null;
    try { return JSON.parse(text); } catch {}
    const match = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/);
    if (match) {
      try { return JSON.parse(match[1]); } catch {}
    }
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start !== -1 && end > start) {
      try { return JSON.parse(text.slice(start, end + 1)); } catch {}
    }
    return null;
  }

  /**
   * 调用 AI。interactive=true 表示用户主动发起：未配置时打开设置面板；
   * 自动触发的功能传 interactive=false，未配置时静默抛出 AiUnavailableError。
   */
  async function callAiChat({ messages, temperature = 0.7, timeout = 50000, scene = "default", interactive = true, maxTokens }) {
    await refreshAiStatus();
    if (!aiReady()) {
      if (aiStatus.configured && aiStatus.online === false) throw new AiUnavailableError("播放器当前未联网，AI 功能暂不可用");
      if (interactive && !aiStatus.managed) openAiSettingsModal();
      throw new AiUnavailableError(aiStatus.managed ? "服务器未配置 AI 服务" : "请先在「AI 音乐助理设置」中配置服务");
    }
    if (!aiBudgetTake(scene)) throw new AiUnavailableError("AI 调用过于频繁，请稍后再试");
    const body = { messages, temperature };
    if (maxTokens) body.max_tokens = maxTokens;
    try {
      const res = await fetchJson("/api/ai", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      }, timeout);
      const content = typeof res.content === "string" ? res.content.trim() : "";
      if (!content) throw new Error(res.finish === "length" ? "生成达到长度限制" : "AI 返回内容为空");
      return content;
    } catch (err) {
      if (err.name === "AbortError") throw new Error("AI 请求超时，请稍后重试");
      if (err.status === 503) {
        aiStatus = { ...aiStatus, configured: false };
        throw new AiUnavailableError(err.message);
      }
      throw err;
    }
  }

  function aiCacheGet(cacheKey) {
    try {
      const raw = localStorage.getItem(AI_CACHE_PREFIX + cacheKey);
      if (!raw) return undefined;
      const entry = JSON.parse(raw);
      if (entry.e && entry.e < Date.now()) {
        localStorage.removeItem(AI_CACHE_PREFIX + cacheKey);
        return undefined;
      }
      return entry.d;
    } catch {
      return undefined;
    }
  }

  function aiCacheEvict() {
    const entries = [];
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (!k || !k.startsWith(AI_CACHE_PREFIX)) continue;
        let t = 0;
        try { t = JSON.parse(localStorage.getItem(k)).t || 0; } catch {}
        entries.push([k, t]);
      }
      entries.sort((a, b) => a[1] - b[1]);
      entries.slice(0, Math.max(1, Math.ceil(entries.length / 2))).forEach(([k]) => localStorage.removeItem(k));
    } catch {}
  }

  function aiCacheSet(cacheKey, data, ttlDays = 180) {
    const now = Date.now();
    const raw = JSON.stringify({ t: now, e: ttlDays ? now + ttlDays * 86400000 : 0, d: data });
    try {
      localStorage.setItem(AI_CACHE_PREFIX + cacheKey, raw);
    } catch {
      aiCacheEvict();
      try { localStorage.setItem(AI_CACHE_PREFIX + cacheKey, raw); } catch {}
    }
  }

  function aiCacheKey(...parts) {
    return parts.map(p => String(p || "").trim().toLowerCase()).join("|").slice(0, 200);
  }

  const aiInflight = new Map();

  /** 缓存优先：命中缓存直接返回；同一 key 并发只发一次请求；parse 返回 null 视为失败且不缓存。 */
  function aiCached({ cacheKey, ttlDays = 180, parse, refresh = false, ...chat }) {
    if (!refresh) {
      const hit = aiCacheGet(cacheKey);
      if (hit !== undefined) return Promise.resolve(hit);
    }
    if (aiInflight.has(cacheKey)) return aiInflight.get(cacheKey);
    const job = callAiChat(chat)
      .then(text => {
        const data = parse ? parse(text) : text;
        if (data == null) throw new Error("AI 返回格式无效");
        aiCacheSet(cacheKey, data, ttlDays);
        return data;
      })
      .finally(() => aiInflight.delete(cacheKey));
    aiInflight.set(cacheKey, job);
    return job;
  }

  // --- Modal: AI Settings ---
  const aiSettingInputs = ["aiProviderSelect", "aiEndpointInput", "aiApiKeyInput", "aiModelInput"];

  async function openAiSettingsModal() {
    $("aiSettingsModal")?.classList.add("active");
    if ($("aiTestResult")) $("aiTestResult").hidden = true;
    const status = await refreshAiStatus(true);
    const managed = !!status.managed;
    aiSettingInputs.forEach(id => { if ($(id)) $(id).disabled = managed; });
    if ($("btnAiSave")) $("btnAiSave").hidden = managed;
    if ($("btnAiToggleKey")) $("btnAiToggleKey").hidden = managed;
    const lead = $("aiSettingsLead");
    if (lead) {
      lead.textContent = managed
        ? (status.configured ? `AI 服务由中转服务器统一提供（模型：${status.model}），无需在此配置。` : "中转服务器尚未配置 AI 服务，请联系服务器管理员。")
        : "配置大模型服务。密钥只保存在播放器手机上，不会回传到浏览器。";
    }
    if (managed) return;
    if ($("aiProviderSelect")) $("aiProviderSelect").value = status.provider || "ark";
    if ($("aiEndpointInput")) $("aiEndpointInput").value = status.endpoint || PROVIDER_PRESETS.ark.endpoint;
    if ($("aiModelInput")) $("aiModelInput").value = status.model || PROVIDER_PRESETS.ark.model;
    if ($("aiApiKeyInput")) {
      $("aiApiKeyInput").value = "";
      $("aiApiKeyInput").placeholder = status.hasKey ? "已保存密钥（留空保持不变）" : "sk-...";
    }
  }

  function closeAiSettingsModal() {
    $("aiSettingsModal")?.classList.remove("active");
  }

  async function saveAiSettings() {
    const res = await fetchJson("/api/ai/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: $("aiProviderSelect")?.value || "custom",
        endpoint: $("aiEndpointInput")?.value.trim() || "",
        apiKey: $("aiApiKeyInput")?.value.trim() || "",
        model: $("aiModelInput")?.value.trim() || ""
      })
    }, 8000);
    aiStatus = { ...res, loaded: true };
    aiStatusAt = Date.now();
    if ($("aiApiKeyInput")) {
      $("aiApiKeyInput").value = "";
      $("aiApiKeyInput").placeholder = res.hasKey ? "已保存密钥（留空保持不变）" : "sk-...";
    }
    return res;
  }

  $("btnAiSettings")?.addEventListener("click", openAiSettingsModal);
  $("btnAiSettingsClose")?.addEventListener("click", closeAiSettingsModal);
  $("btnAiCancel")?.addEventListener("click", closeAiSettingsModal);
  $("aiSettingsModal")?.addEventListener("click", e => {
    if (e.target === $("aiSettingsModal")) closeAiSettingsModal();
  });

  $("aiProviderSelect")?.addEventListener("change", e => {
    const p = PROVIDER_PRESETS[e.target.value];
    if (p && e.target.value !== "custom") {
      $("aiEndpointInput").value = p.endpoint;
      $("aiEndpointInput").placeholder = p.hint || "https://...";
      $("aiModelInput").value = p.model;
    }
  });

  $("btnAiToggleKey")?.addEventListener("click", () => {
    const inp = $("aiApiKeyInput");
    if (!inp) return;
    const isPw = inp.type === "password";
    inp.type = isPw ? "text" : "password";
    $("btnAiToggleKey").textContent = isPw ? "隐藏" : "显示";
  });

  $("btnAiSave")?.addEventListener("click", async () => {
    try {
      await saveAiSettings();
      toast("AI 音乐助理设置已保存到播放器");
      closeAiSettingsModal();
    } catch (err) {
      toast(`保存失败：${err.message}`, "i-trash");
    }
  });

  $("btnAiTest")?.addEventListener("click", async () => {
    const btn = $("btnAiTest");
    const resEl = $("aiTestResult");
    if (!btn || !resEl) return;
    resEl.hidden = false;
    resEl.style.color = "var(--muted)";
    resEl.textContent = "正在连接并测试 API…";
    btn.disabled = true;
    try {
      if (!aiStatus.managed) await saveAiSettings();
      const start = Date.now();
      const reply = await callAiChat({
        messages: [{ role: "user", content: "请用不超过10个字回答：连接成功" }],
        timeout: 20000,
        scene: "test",
        interactive: false
      });
      resEl.style.color = "var(--accent)";
      resEl.textContent = `✓ 连接成功 (${Date.now() - start}ms): ${reply.slice(0, 30)}`;
    } catch (err) {
      resEl.style.color = "var(--danger)";
      resEl.textContent = `✕ 测试失败: ${err.message}`;
    } finally {
      btn.disabled = false;
    }
  });

  // --- Feature 2: 深度唱片手札与发烧乐评（严格遵循“点击开启”+ 本地持久化缓存）---
  function getNoteCacheKey(track) {
    if (!track) return null;
    const album = (track.album || track.title || "").trim();
    const artist = (track.artist || "").trim();
    return `${AI_NOTES_PREFIX}${artist}_${album}`.toLowerCase();
  }

  // 手札随播放自动更新：手札面板可见时，切到新专辑就加载（有缓存秒开，无缓存且 AI 可用则自动生成）。
  let notesRenderedKey = null;
  const notesInflight = new Map();

  function maybeRefreshSideNotes(state) {
    const pane = $("sideNotesContent");
    if (!pane || pane.hidden) return;
    const key = getNoteCacheKey(state?.queue?.[state.index]);
    if (key !== notesRenderedKey) renderSideNotes();
  }

  async function renderSideNotes() {
    const container = $("sideNotesContainer");
    if (!container) return;
    const track = lastState?.queue?.[lastState.index];
    notesRenderedKey = getNoteCacheKey(track);

    if (!track) {
      container.innerHTML = `
        <div class="notes-empty-card">
          <svg class="icon" style="width:36px; height:36px; color:var(--muted); margin-bottom:10px;"><use href="#i-sparkles"/></svg>
          <div style="font-weight:600; margin-bottom:6px;">尚未播放唱片</div>
          <p style="font-size:12.5px; color:var(--muted);">请先在曲库或搜索中点播一张唱片。</p>
        </div>`;
      return;
    }

    const key = notesRenderedKey;
    let cached = null;
    try { cached = localStorage.getItem(key); } catch {}

    if (cached) {
      renderCachedNote(track, cached);
      return;
    }
    await refreshAiStatus();
    if (notesRenderedKey !== key) return;
    if (aiReady()) generateNotesForTrack(track, false);
    else renderNotePromptCard(track);
  }

  function renderNotePromptCard(track) {
    const container = $("sideNotesContainer");
    const albumName = track.album || track.title;
    const qualityTag = track.audioQuality || (track.lossless ? "Hi-Res" : "无损");

    container.innerHTML = `
      <div class="notes-empty-card">
        <svg class="icon" style="width:34px; height:34px; color:var(--gold); margin-bottom:12px;"><use href="#i-sparkles"/></svg>
        <div style="font-size:16px; font-weight:700; margin-bottom:4px; color:var(--ink);">${escapeHtml(albumName)}</div>
        <div style="font-size:13px; color:var(--muted); margin-bottom:12px;">${escapeHtml(track.artist || "未知艺人")}</div>
        <div class="notes-meta-badges" style="justify-content:center; margin-bottom:16px;">
          <span class="notes-badge gold">${escapeHtml(qualityTag)}</span>
          <span class="notes-badge">唱片手札待生成</span>
        </div>
        <p style="font-size:12.5px; color:var(--muted); max-width:320px; margin:0 auto 18px; line-height:1.6;">
          点击下方按钮开启 AI 发烧顾问，深度解析母带录音、声场动态与音乐背景。
        </p>
        <button type="button" id="btnTriggerGenerateNotes" class="primary" style="display:inline-flex; align-items:center; gap:6px; margin:0 auto;">
          <svg class="icon"><use href="#i-sparkles"/></svg> 开启 AI 深度解读
        </button>
      </div>`;

    $("btnTriggerGenerateNotes")?.addEventListener("click", () => generateNotesForTrack(track, true));
  }

  async function generateNotesForTrack(track, interactive = true, refresh = false) {
    const container = $("sideNotesContainer");
    const key = getNoteCacheKey(track);
    const albumName = track.album || track.title;
    container.innerHTML = `
      <div class="notes-empty-card" style="padding:48px 20px;">
        <div class="ai-thinking-indicator">
          <svg class="icon" style="width:24px; height:24px;"><use href="#i-sparkles"/></svg>
          <span>AI 发烧顾问正在深度剖析母带录音与乐评…</span>
        </div>
        <div style="font-size:12px; color:var(--muted); margin-top:10px;">《${escapeHtml(albumName)}》</div>
      </div>`;

    const prompt = `你是一位精通高保真音响工程、母带制作与唱片艺术的高级音乐鉴赏家。
请为专辑《${albumName}》（艺人：${track.artist || "群星"}，当前参考曲目：${track.title || ""}）撰写一份专业的高保真唱片手札。
必须包含以下三部分（条理清晰，使用 Markdown 格式）：
### 📀 唱片档案与制作脉络
- 时代与流派渊源（精准定位其二级发烧流派）
- 录音室与母带制作看点

### 🎧 发烧听音指南与声场表现
- 声场宽度、纵深与空间残响表现
- 三频结像（低频下潜、人声厚度、高频泛音）
- 动态对比度与器乐分离度建议

### 🎵 亮点曲目与鉴赏要点
- 精选 2-3 首高光曲目在 Hi-Fi 设备上的重点品味细节

请以沉稳、专业、富有洞见的行文风格撰写，适合发烧友阅读，避免空洞废话。`;

    try {
      if (refresh || !notesInflight.has(key)) {
        notesInflight.set(key, callAiChat({
          messages: [{ role: "user", content: prompt }],
          temperature: 0.65,
          scene: "notes",
          interactive
        }).finally(() => notesInflight.delete(key)));
      }
      const markdown = await notesInflight.get(key);
      try { localStorage.setItem(key, markdown); } catch {}
      // 生成期间已经切到别的专辑，就只缓存不渲染。
      if (notesRenderedKey !== key) return;
      renderCachedNote(track, markdown);
    } catch (err) {
      if (notesRenderedKey !== key) return;
      if (err instanceof AiUnavailableError) {
        renderNotePromptCard(track);
        return;
      }
      container.innerHTML = `
        <div class="notes-empty-card">
          <div style="color:var(--danger); font-weight:600; margin-bottom:8px;">手札生成失败</div>
          <div style="font-size:12.5px; color:var(--muted); margin-bottom:16px;">${escapeHtml(err.message)}</div>
          <button type="button" id="btnRetryNotes" class="ghost">重试</button>
        </div>`;
      $("btnRetryNotes")?.addEventListener("click", () => generateNotesForTrack(track, true));
    }
  }

  function renderCachedNote(track, markdown) {
    const container = $("sideNotesContainer");
    const albumName = track.album || track.title;
    const qualityTag = track.audioQuality || (track.lossless ? "Hi-Res" : "无损");

    // AI 输出可能复述带 HTML 的曲目元数据：先转义再套格式。
    const formattedHtml = escapeHtml(markdown)
      .replace(/###\s*(.+)/g, '<div class="notes-section-title">$1</div>')
      .replace(/\*\*(.+?)\*\*/g, '<strong style="color:var(--ink);">$1</strong>')
      .replace(/^-\s+(.+)/gm, '<div style="margin-left:12px; margin-bottom:6px; color:var(--ink); position:relative; padding-left:12px;"><span style="position:absolute; left:0; color:var(--accent);">•</span>$1</div>')
      .replace(/\n\n/g, '<div style="height:12px;"></div>');

    container.innerHTML = `
      <div class="notes-content-box">
        <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:12px; gap:10px;">
          <div>
            <h3 style="font-size:16px; font-weight:750; margin:0 0 4px; color:var(--ink);">${escapeHtml(albumName)}</h3>
            <div style="font-size:13px; color:var(--gold);">${escapeHtml(track.artist || "未知艺人")}</div>
          </div>
          <div style="display:flex; gap:6px;">
            <button type="button" id="btnCopyNote" class="ghost" style="padding:4px 8px; font-size:11.5px;" title="复制文本">复制</button>
            <button type="button" id="btnRegenNote" class="ghost" style="padding:4px 8px; font-size:11.5px;" title="重新生成">重新生成</button>
          </div>
        </div>

        <div class="notes-meta-badges">
          <span class="notes-badge gold">${escapeHtml(qualityTag)}</span>
          <span class="notes-badge accent">✓ AI 发烧手札已缓存</span>
        </div>

        <div style="line-height:1.75; font-size:13px; color:rgba(240,246,244,0.92);">
          ${formattedHtml}
        </div>
      </div>`;

    $("btnCopyNote")?.addEventListener("click", () => {
      navigator.clipboard?.writeText(markdown).then(() => toast("手札文本已复制到剪贴板"));
    });
    $("btnRegenNote")?.addEventListener("click", () => {
      openConfirmModal("重新生成唱片手札", "确定要重新向 AI 请求生成本张唱片的手札吗？", () => {
        generateNotesForTrack(track, true, true);
      });
    });
  }

  // --- 专辑详情页 AI 发烧导赏与声学手札 ---
  function getAlbumCuratorCacheKey(album) {
    if (!album) return null;
    const alb = (album.title || album.name || "").trim().toLowerCase();
    const art = (album.artist || album.detail || "").trim().toLowerCase();
    return `ai_curator_album_${art}_${alb}`.replace(/[^a-z0-9_\u4e00-\u9fa5]/g, "");
  }

  async function renderAlbumDetailCurator(album, tracks, prefix = "album") {
    const card = $(`${prefix}AiCuratorCard`);
    if (!card || !album || !album.title) return;

    const albTitle = album.title || album.name || "";
    const artName = album.artist || album.detail || "";
    const cacheKey = getAlbumCuratorCacheKey(album);
    const cachedRaw = cacheKey ? localStorage.getItem(cacheKey) : null;

    const tagEl = $(`${prefix}CuratorAcousticTag`);
    const textEl = $(`${prefix}CuratorText`);
    const gearBox = $(`${prefix}CuratorGearBox`);
    const gearText = $(`${prefix}CuratorGearText`);
    const keyTracksEl = $(`${prefix}CuratorKeyTracks`);
    const refreshBtn = $(`btnRefresh${prefix.charAt(0).toUpperCase() + prefix.slice(1)}Curator`);

    const applyCuratorData = (data) => {
      card.hidden = false;
      if (tagEl) tagEl.textContent = data.acoustic_highlight || data.genre || "发烧推荐";
      if (textEl) textEl.textContent = data.review || data.audiophile_review || data.summary || "";
      if (gearBox && gearText) {
        if (data.gear_tip || data.gearTip) {
          gearText.textContent = data.gear_tip || data.gearTip;
          gearBox.hidden = false;
        } else {
          gearBox.hidden = true;
        }
      }
      if (keyTracksEl) {
        keyTracksEl.replaceChildren();
        const keyList = data.key_tracks || data.keyTracks || [];
        if (Array.isArray(keyList) && keyList.length > 0) {
          const lbl = document.createElement("span");
          lbl.style.fontSize = "11.5px";
          lbl.style.color = "var(--muted)";
          lbl.textContent = "高光必听：";
          keyTracksEl.appendChild(lbl);

          keyList.forEach(trkTit => {
            const pill = document.createElement("button");
            pill.type = "button";
            pill.className = "ai-curator-key-track-pill";
            pill.innerHTML = `<svg class="icon" style="width:11px;height:11px;"><use href="#i-play"/></svg> <span>${escapeHtml(trkTit)}</span>`;
            pill.addEventListener("click", e => {
              e.stopPropagation();
              const match = tracks?.find(t => t.title && (t.title.includes(trkTit) || trkTit.includes(t.title)));
              if (match) {
                playTracks([match]);
              } else {
                toast(`正在全网检索播放「${artName} - ${trkTit}」…`);
                call("library", { action: "tidal-search", query: `${artName} ${trkTit}` }).then(r => {
                  if (r.tracks?.length) playTracks(r.tracks);
                });
              }
            });
            keyTracksEl.appendChild(pill);
          });
        }
      }
    };

    if (cachedRaw) {
      try {
        const parsed = JSON.parse(cachedRaw);
        applyCuratorData(parsed);
      } catch {
        localStorage.removeItem(cacheKey);
      }
    } else {
      await refreshAiStatus();
      if (!aiReady()) {
        card.hidden = true;
        return;
      }
      card.hidden = false;
      if (tagEl) tagEl.textContent = "研判中…";
      if (textEl) textEl.innerHTML = `<span style="color:var(--muted);"><svg class="icon" style="width:14px;height:14px;vertical-align:-2px;"><use href="#i-sparkles"/></svg> AI 音乐总监正在研判本张唱片的录音动态、编曲器乐与发烧要点…</span>`;
      if (gearBox) gearBox.hidden = true;
      if (keyTracksEl) keyTracksEl.replaceChildren();

      generateAndCacheAlbumCurator(album, tracks, cacheKey).then(data => {
        if (data) applyCuratorData(data);
      }).catch(err => {
        // AI 未配置或预算用尽时静默隐藏，不打扰浏览。
        if (err instanceof AiUnavailableError) card.hidden = true;
        else if (textEl) textEl.textContent = `导赏生成暂不可用: ${err.message}`;
      });
    }

    if (refreshBtn) {
      refreshBtn.onclick = (e) => {
        e.stopPropagation();
        card.hidden = false;
        if (tagEl) tagEl.textContent = "重新分析…";
        if (textEl) textEl.innerHTML = `<span style="color:var(--muted);"><svg class="icon" style="width:14px;height:14px;vertical-align:-2px;"><use href="#i-sparkles"/></svg> 正在重新深度剖析母带与声学表现…</span>`;
        if (gearBox) gearBox.hidden = true;
        if (keyTracksEl) keyTracksEl.replaceChildren();

        generateAndCacheAlbumCurator(album, tracks, cacheKey).then(data => {
          if (data) applyCuratorData(data);
          toast("发烧导赏已更新！");
        }).catch(err => {
          if (textEl) textEl.textContent = `生成失败: ${err.message}`;
        });
      };
    }
  }

  async function generateAndCacheAlbumCurator(album, tracks, cacheKey) {
    const albTitle = album.title || album.name || "";
    const artName = album.artist || album.detail || "未知艺人";
    const trackNames = (tracks || []).slice(0, 10).map(t => t.title).filter(Boolean).join("、");

    const prompt = `你是一位享誉业界的顶级发烧唱片策展人与母带声学工程师。
请为专辑《${albTitle}》（艺人：${artName}${trackNames ? `，收录代表曲目：${trackNames}` : ""}）撰写一份精练、深度且极富发烧品味的【唱片声学鉴赏导读】。

请以纯 JSON 格式输出，严格符合以下字段：
{
  "genre": "细分发烧流派标签（如：室内融合爵士 / 模拟母带人声 / 德奥交响）",
  "acoustic_highlight": "声学亮点精炼标签（如：大提琴松香感、30Hz极低频下潜、极高频空气感）",
  "review": "发烧深度点评（约 80-140 字，点评录音质感、母带声场纵深、器乐结像层次与情感张力）",
  "gear_tip": "回放器材建议（约 20-35 字，如：适合高阻动圈驱动以展现饱满中频，或平板耳机以还原极致细节）",
  "key_tracks": ["真实收录于该专辑中的最具代表性的高光单曲1", "高光单曲2"]
}`;

    const reply = await callAiChat({
      messages: [{ role: "user", content: prompt }],
      temperature: 0.6,
      scene: "album",
      interactive: false
    });

    const parsed = extractJson(reply);
    if (!parsed || !parsed.review) {
      throw new Error("未能生成有效导读数据");
    }

    if (cacheKey) {
      try {
        localStorage.setItem(cacheKey, JSON.stringify(parsed));
      } catch {}
    }
    return parsed;
  }


  // --- Feature 1: AI 发烧心境电台（深度唤醒机内曲库，免打字沉浸秒播） ---
  const AI_MOOD_PRESETS = [
    {
      id: "strings",
      title: "🎻 室内乐·松香泛音",
      icon: "🎻",
      query: "极具松香摩擦质感与高频空气感的小提琴、大提琴室内乐与弦乐协奏曲名盘",
      keywords: ["violin", "concerto", "mozart", "paganini", "chua", "chloe", "小提琴", "协奏曲", "室内乐", "弦乐", "提琴", "大提琴", "bach", "brahms"],
      quickGuide: "着重品味弦乐擦弦的松香泛音细节与空气感，琴体木质共鸣醇厚自然，考验耳机的极高频延展与微动态响应。"
    },
    {
      id: "piano",
      title: "🎹 肖邦夜曲·琴键微光",
      icon: "🎹",
      query: "琴键颗粒感通透、深夜静谧空间残响与微弱触键泛音的古典钢琴名盘",
      keywords: ["chopin", "nocturne", "piano", "hicks", "肖邦", "夜曲", "钢琴", "独奏", "键盘", "rubinstein", "pires", "argerich"],
      quickGuide: "静心感受琴槌轻触钢丝的呢喃与踏板延音泛音，声场残响宁静开阔，极度适合深夜独处聆听。"
    },
    {
      id: "oriental",
      title: "🏮 东方意境·梁祝民乐",
      icon: "🏮",
      query: "东方婉转旋律、民族管弦乐与梁祝小提琴交响协奏曲高保真发烧唱片",
      keywords: ["butterfly lovers", "梁祝", "阿嬷", "陈佳", "东方", "中国", "民乐", "情书", "古筝", "二胡", "琵琶", "雨果"],
      quickGuide: "东方式写意抒情与管弦乐团磅礴气势交融，高胡与小提琴如泣如诉，线条极度顺滑耐听。"
    },
    {
      id: "live_vocal",
      title: "🎙️ 现场原声·毒性人声",
      icon: "🎙️",
      query: "现场舞台Live沉浸感、人声口型结像极其清晰温润的发烧民谣与女声",
      keywords: ["live", "tour", "doudou", "春夏秋冬", "现场", "民谣", "陈佳", "情书", "原声", "蔡琴", "邓丽君", "vocal"],
      quickGuide: "人声口型结像高度凝聚靠前，呼吸气声与现场舞台混响空间感触手可及，中频饱满温润有肉感。"
    },
    {
      id: "deep_jazz",
      title: "☕ 深夜微醺·慢调爵士",
      icon: "☕",
      query: "具有ECM或Blue Note标志性清冷声场、低音大提琴拨弦肉感与沙哑萨克斯的慢调爵士名盘",
      keywords: ["jazz", "sax", "trio", "ecm", "爵士", "萨克斯", "夜曲", "miles", "coltrane", "bill evans", "jarrett"],
      quickGuide: "低音提琴拨弦弹性十足，鼓刷拂过铜钹的沙沙声清晰逼真，萨克斯管壁金属共振如在耳畔。"
    },
    {
      id: "dynamic_orchestra",
      title: "💥 大动态爆棚·交响试金",
      icon: "💥",
      query: "具有极深声场纵深、定音鼓捶击瞬态爆发力与管弦齐鸣大动态的顶级交响试音天碟",
      keywords: ["symphony", "orchestra", "concerto", "交响", "管弦", "合奏", "动态", "telarc", "mahler", "tchaikovsky"],
      quickGuide: "大编制交响乐队齐奏时层次井然，考验播放器材的推力储备与声场下盘控制力，动态大起大落极具震撼力。"
    },
    {
      id: "ambient_nordic",
      title: "❄️ 冷冽极简·北欧声场",
      icon: "❄️",
      query: "具有Sigur Rós、Ólafur Arnalds风格的冰岛清冷空灵氛围后摇与新古典极简声场",
      keywords: ["ambient", "post-rock", "minimal", "nordic", "后摇", "氛围", "新古典", "冰岛", "极简", "olafur", "sigur"],
      quickGuide: "声场如同极地冰原般空旷通透，微妙的合成器微光与真实弦乐交织，带给人纯净的心灵洗涤。"
    },
    {
      id: "soundtrack_peace",
      title: "🌧️ 雨天独处·电影原声",
      icon: "🌧️",
      query: "具有深刻故事叙事感、管弦与钢琴交织的电影大师级电影原声配乐名作",
      keywords: ["soundtrack", "ost", "score", "原声", "电影", "情书", "久石让", "坂本龙一", "morricone", "zimmer"],
      quickGuide: "叙事氛围感与画面感极强，优美的器乐独奏缓缓流淌，陪伴每一个安静独处的阅读或思绪时刻。"
    }
  ];

  let activeMoodRadioId = null;
  let cachedLocalTracksForMood = null;

  let cachedLocalTracksRevision = null;
  let localTracksPromise = null;

  // 整个本地曲库（分页拉全，最多 5000 首）；曲库文件变化后自动失效。
  async function getLocalTracksCache() {
    const revision = lastState?.filesRevision ?? null;
    if (cachedLocalTracksForMood && cachedLocalTracksForMood.length > 0 && revision === cachedLocalTracksRevision) {
      return cachedLocalTracksForMood;
    }
    if (localTracksPromise) return localTracksPromise;
    localTracksPromise = (async () => {
      const all = [];
      let offset = 0;
      try {
        while (offset >= 0 && all.length < 5000) {
          const res = await call("library", { action: "local", query: "", offset, limit: 500 });
          if (!res || !Array.isArray(res.tracks)) break;
          all.push(...res.tracks);
          offset = typeof res.next === "number" ? res.next : -1;
        }
      } catch (e) {
        console.warn("Fetch local tracks err", e);
      }
      if (all.length) {
        cachedLocalTracksForMood = all;
        cachedLocalTracksRevision = revision;
      }
      return all.length ? all : (cachedLocalTracksForMood || []);
    })().finally(() => { localTracksPromise = null; });
    return localTracksPromise;
  }

  // 时段感知：按当前时段的预设偏好 + 听众在这个时段实际点过的心境排序。
  const MOOD_TIME_AFFINITY = {
    morning: ["strings", "piano", "oriental", "live_vocal"],
    afternoon: ["live_vocal", "oriental", "dynamic_orchestra", "strings"],
    evening: ["deep_jazz", "soundtrack_peace", "live_vocal", "piano"],
    night: ["deep_jazz", "ambient_nordic", "piano", "soundtrack_peace"]
  };
  const MOOD_HISTORY_KEY = "tidal_mood_hours";

  function moodTimeBucket(hour = new Date().getHours()) {
    if (hour >= 5 && hour < 11) return "morning";
    if (hour >= 11 && hour < 17) return "afternoon";
    if (hour >= 17 && hour < 22) return "evening";
    return "night";
  }

  function moodHistory() {
    try { return JSON.parse(localStorage.getItem(MOOD_HISTORY_KEY) || "{}") || {}; } catch { return {}; }
  }

  function recordMoodLaunch(moodId) {
    const history = moodHistory();
    const bucket = moodTimeBucket();
    history[bucket] = history[bucket] || {};
    history[bucket][moodId] = (history[bucket][moodId] || 0) + 1;
    try { localStorage.setItem(MOOD_HISTORY_KEY, JSON.stringify(history)); } catch {}
  }

  function orderedMoodPresets() {
    const bucket = moodTimeBucket();
    const preferred = MOOD_TIME_AFFINITY[bucket] || [];
    const learned = moodHistory()[bucket] || {};
    return AI_MOOD_PRESETS
      .map((mood, i) => {
        const rank = preferred.indexOf(mood.id);
        const score = (rank >= 0 ? (preferred.length - rank) * 2 : 0) + Math.min(6, (learned[mood.id] || 0) * 1.5) - i * 0.01;
        return { mood, score };
      })
      .sort((a, b) => b.score - a.score)
      .map(item => item.mood);
  }

  let moodChipsBucket = moodTimeBucket();
  setInterval(() => {
    if (moodTimeBucket() === moodChipsBucket) return;
    moodChipsBucket = moodTimeBucket();
    renderAiMoodRadioChips("aiMoodRadioChipsLib");
    renderAiMoodRadioChips("aiMoodRadioChipsSearch");
  }, 10 * 60 * 1000);

  function renderAiMoodRadioChips(containerId) {
    const container = $(containerId);
    if (!container) return;
    container.replaceChildren();
    const frag = document.createDocumentFragment();

    orderedMoodPresets().forEach(mood => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ai-mood-chip";
      btn.dataset.moodId = mood.id;
      if (activeMoodRadioId === mood.id) btn.classList.add("active");
      btn.innerHTML = `<span>${mood.icon}</span> <span>${escapeHtml(mood.title.split("·")[1] || mood.title)}</span>`;
      btn.title = mood.quickGuide;
      btn.addEventListener("click", () => {
        launchAiMoodRadio(mood.id);
      });
      frag.appendChild(btn);
    });

    container.appendChild(frag);
  }

  async function launchAiMoodRadio(moodId) {
    const mood = AI_MOOD_PRESETS.find(m => m.id === moodId);
    if (!mood) return;

    activeMoodRadioId = moodId;
    recordMoodLaunch(moodId);
    document.querySelectorAll(".ai-mood-chip").forEach(btn => {
      btn.classList.toggle("active", btn.dataset.moodId === moodId);
    });

    // 1. 本地曲库毫秒级穿透与秒起播 (Zero-Latency Local Penetration)
    const localTracks = await getLocalTracksCache();
    let localMatches = [];
    if (localTracks.length > 0) {
      localMatches = localTracks.filter(t => {
        const text = `${t.title || ""} ${t.artist || ""} ${t.album || ""} ${t.genre || ""}`.toLowerCase();
        return mood.keywords.some(kw => text.includes(kw));
      });
    }

    if (localMatches.length > 0) {
      toast(`🎵 已唤醒本机 ${localMatches.length} 首【${mood.title}】无损音频，正在即刻起播！`);
      playTracks(localMatches, false, 0);
    } else {
      toast(`正在启动【${mood.title}】并全网调度同频发烧母带…`);
    }

    // 2. 切换到 AI 寻歌看板展示发烧导赏与同频唱片
    showView("search");
    document.querySelectorAll("[data-nav]").forEach(b => b.setAttribute("aria-current", String(b.dataset.nav === "viewSearch")));
    document.querySelectorAll("[data-search-src]").forEach(b => b.setAttribute("aria-current", String(b.dataset.searchSrc === "ai")));
    currentSearchSource = "ai";
    if ($("aiInspirationChips")) $("aiInspirationChips").hidden = false;
    if ($("globalSearchInput")) $("globalSearchInput").value = mood.query;

    const rev = ++searchRevision;
    resetSearchLayout();

    if ($("aiCuratorBanner")) {
      if ($("aiCuratorTitle")) $("aiCuratorTitle").textContent = `✨ ${mood.title}`;
      if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = mood.quickGuide;
      if ($("aiCuratorGearFocus")) {
        $("aiCuratorGearFocus").textContent = `🎧 正在沉浸播放本机 ${localMatches.length} 首高保真音频 · ${mood.quickGuide}`;
        $("aiCuratorGearFocus").hidden = false;
      }
      $("aiCuratorBanner").hidden = false;
    }

    // 3. 后台智能补齐与云端 Hi-Res 唱片注入
    searchMessage(`已唤醒机内 ${localMatches.length} 首音轨起播，正在检索全网同频顶级发烧名盘…`);

    try {
      const prompt = `你是一位精通各类音乐母带录音、编曲配器与高保真发烧音响鉴赏的顶级音乐顾问与唱片导赏家。
当前用户正在山灵便携播放器上收听【${mood.title}】心境电台。
心境声学诉求：'${mood.query}'。

【重要专业红线】：
1. 真实存在性：所有推荐必须是主流流媒体（TIDAL / 网易云）真实公开发行的标准真实唱片，绝对不允许虚构任何不存在的专辑名或曲目！
2. 命题切合度：必须完全契合【${mood.title}】的艺术造诣与母带声学特质！
3. 请精选推荐 3-5 张在艺术造诣、器乐编排、母带品质与动态细节上极其契合的高水准唱片（兼顾国际与华语优质制作）。

输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "${mood.title}",
  "listening_guide": "结合该心境的专业发烧听音导赏分析（约 100-180 字）...",
  "recommended_gear_focus": "重点品味要点（约 30-60 字）",
  "recommendations": [
    {
      "album": "准确的唱片原名（以流媒体收录名为准）",
      "album_alt": "中文或英文常用别名",
      "artist": "准确的艺人名字",
      "artist_alt": "艺人英文或中文别名",
      "key_track": "代表高光单曲名（真实收录于该唱片中）",
      "genre": "细分发烧流派标签",
      "release_year": "发行年份",
      "acoustic_highlight": "发烧亮点标签",
      "recommendation_reason": "深度推荐理由（约 60-120 字）"
    }
  ]
}`;

      const aiReply = await callAiChat({
        messages: [{ role: "user", content: prompt }],
        temperature: 0.65,
        scene: "mood"
      });
      if (rev !== searchRevision) return;

      const parsed = extractJson(aiReply);
      const recs = parsed?.recommendations || [];

      if (recs.length > 0) {
        if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = parsed.listening_guide || mood.quickGuide;
        if ($("aiCuratorGearFocus") && parsed.recommended_gear_focus) {
          $("aiCuratorGearFocus").textContent = `🎧 重点品味要点：${parsed.recommended_gear_focus}（机内已起播 ${localMatches.length} 首）`;
          $("aiCuratorGearFocus").hidden = false;
        }

        const matchedCards = await Promise.all(recs.map(resolveRecommendationItem));
        if (rev !== searchRevision) return;

        lastAiRecommendations = matchedCards;
        const downloadableCards = matchedCards.filter(c => c.canDownload && c.rawAlbum);
        selectedAiCardIds = new Set(downloadableCards.map(c => c.id));
        updateAiSelectedCount();

        $("searchCardsTitle").textContent = `云端同频发烧唱片推荐 (${matchedCards.length}) · 支持一键勾选下载至机身`;
        $("searchCardsTitle").hidden = false;
        $("searchCardsGrid").hidden = false;
        $("searchTrackList").hidden = true;

        renderAiRecommendationCards(matchedCards, $("searchCardsGrid"));

        // 将云端匹配的高光单曲追加在播放队列后面，达成连绵不绝的沉浸听音
        const cloudTracks = matchedCards.map(c => c.trackObj).filter(Boolean);
        if (localMatches.length === 0 && cloudTracks.length > 0) {
          playTracks(cloudTracks, false, 0);
        }
      }
    } catch (err) {
      console.warn("Mood background enrich err", err);
      if (localMatches.length > 0) {
        $("searchCardsTitle").textContent = `机内已唤醒无损音频 (${localMatches.length})`;
        $("searchCardsTitle").hidden = false;
      }
    }
  }
  let lastAiRecommendations = [];
  let selectedAiCardIds = new Set();

  function updateAiSelectedCount() {
    const downloadableCount = lastAiRecommendations.filter(c => c.canDownload && c.rawAlbum).length;
    const count = selectedAiCardIds.size;
    if ($("aiSelectedDlCount")) $("aiSelectedDlCount").textContent = String(count);
    const btnDl = $("btnAiDownloadSelected");
    if (btnDl) {
      btnDl.style.opacity = count > 0 ? "1" : "0.5";
      btnDl.style.pointerEvents = count > 0 ? "" : "none";
    }
    const selectBar = $("aiSelectionBar");
    if (selectBar) {
      selectBar.hidden = downloadableCount === 0;
    }
  }
  let aiSearchSession = {
    initialQuery: "",
    turnCount: 0,
    seenAlbums: new Set(),
    initialSnapshot: null
  };

  function renderAiCategoryChips(cat) {
    currentAiInspirationCat = cat;
    const container = $("aiChipsList");
    if (!container) return;
    container.replaceChildren();
    const list = AI_INSPIRATION_DATA[cat] || AI_INSPIRATION_DATA.instrument;
    const frag = document.createDocumentFragment();
    list.forEach(item => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ai-chip";
      btn.textContent = item.title;
      btn.dataset.query = item.query;
      btn.addEventListener("click", () => {
        $("globalSearchInput").value = item.query;
        document.querySelectorAll("[data-search-src]").forEach(b => {
          b.setAttribute("aria-current", String(b.dataset.searchSrc === "ai"));
        });
        currentSearchSource = "ai";
        if ($("aiInspirationChips")) $("aiInspirationChips").hidden = false;
        if ($("searchKindTabs")) $("searchKindTabs").hidden = true;
        updateAiNowPlayingSeed();
        executeGlobalSearch();
      });
      frag.appendChild(btn);
    });
    container.appendChild(frag);

    document.querySelectorAll("[data-ai-cat]").forEach(pill => {
      pill.setAttribute("aria-current", String(pill.dataset.aiCat === cat));
    });
  }

  function updateAiNowPlayingSeed() {
    const pill = $("aiSeedNowPlaying");
    const text = $("aiSeedNowPlayingText");
    if (!pill || !text) return;
    const isAi = currentSearchSource === "ai";
    const cur = (lastState && lastState.queue && lastState.queue[lastState.index]) || null;
    if (isAi && cur && cur.title) {
      text.textContent = `🎵 正在播放：《${cur.title}》- ${cur.artist || "未知"} · 点击深度以歌寻歌`;
      pill.hidden = false;
    } else {
      pill.hidden = true;
    }
  }


  // --- Smart High-Fidelity Audiophile Match Engine (置信度打分与跨源精准匹配) ---
  function cleanAiTitle(str) {
    if (!str) return "";
    return str
      .replace(/\(.*?\)|\[.*?\]/g, "")
      .replace(/[^\w\s\u4e00-\u9fa5\u3040-\u30ff]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase();
  }

  function computeStringSimilarity(s1, s2) {
    const a = cleanAiTitle(s1);
    const b = cleanAiTitle(s2);
    if (!a || !b) return 0;
    if (a === b) return 1.0;
    if (a.includes(b) || b.includes(a)) {
      return Math.min(a.length, b.length) / Math.max(a.length, b.length);
    }
    const bigrams = s => {
      const set = new Set();
      for (let i = 0; i < s.length - 1; i++) set.add(s.slice(i, i + 2));
      return set;
    };
    const b1 = bigrams(a);
    const b2 = bigrams(b);
    if (!b1.size || !b2.size) return 0;
    let intersect = 0;
    b1.forEach(bg => { if (b2.has(bg)) intersect++; });
    return intersect / (b1.size + b2.size - intersect);
  }

  function evaluateCandidateMatch(target, candidate) {
    if (!candidate) return 0;
    const candArt = candidate.artist || candidate.subtitle || "";
    const candAlb = candidate.album || candidate.title || "";
    const candTrk = candidate.title || "";

    const targetArts = [target.artist, target.artist_alt].filter(Boolean);
    let artScore = 0;
    for (const tart of targetArts) {
      artScore = Math.max(artScore, computeStringSimilarity(tart, candArt));
    }
    // 艺人相似度门禁：低于 0.4 直接归零，彻底杜绝张冠李戴！
    if (artScore < 0.4) return 0;

    const targetAlbs = [target.album, target.album_alt].filter(Boolean);
    let albScore = 0;
    for (const talb of targetAlbs) {
      albScore = Math.max(albScore, computeStringSimilarity(talb, candAlb));
    }

    const targetTrks = [target.key_track, target.key_track_alt].filter(Boolean);
    let trkScore = 0;
    for (const ttrk of targetTrks) {
      trkScore = Math.max(trkScore, computeStringSimilarity(ttrk, candTrk));
    }

    return artScore * 0.4 + Math.max(albScore, trkScore) * 0.6;
  }

  async function resolveRecommendationItem(item) {
    // 1. 本地曲库优先高置信度匹配
    try {
      const resLocal = await call("library", { action: "local", query: item.artist }).catch(() => ({ tracks: [] }));
      if (resLocal.tracks && resLocal.tracks.length > 0) {
        let bestLocal = null;
        let bestLocalScore = 0;
        for (const t of resLocal.tracks) {
          const sc = evaluateCandidateMatch(item, t);
          if (sc > bestLocalScore) {
            bestLocalScore = sc;
            bestLocal = t;
          }
        }
        if (bestLocalScore >= 0.68) {
          return {
            source: "local",
            sourceBadge: "本地已收录 · 无损FLAC",
            sourceColor: "var(--accent)",
            id: bestLocal.id,
            title: bestLocal.album || item.album,
            artist: bestLocal.artist || item.artist,
            year: bestLocal.year || item.release_year || "",
            keyTrack: bestLocal.title || item.key_track,
            coverUrl: `/api/artwork/${bestLocal.id}`,
            trackObj: bestLocal,
            rawAlbum: null,
            canDownload: false,
            isLocal: true,
            item
          };
        }
      }
    } catch (err) {
      console.warn("Local match err", err);
    }

    // 2. 并行跨源候选池（TIDAL 专辑、TIDAL 单曲、网易云单曲）
    try {
      const artClean = item.artist;
      const albClean = item.album;
      const trkClean = item.key_track || albClean;

      const pTidalAlb = call("library", {
        action: "tidal-collections",
        kind: "ALBUM",
        query: `${artClean} ${albClean}`
      }).catch(() => ({ collections: [] }));

      const pTidalTrk = call("library", {
        action: "tidal-search",
        query: `${artClean} ${trkClean}`
      }).catch(() => ({ tracks: [] }));

      const pNeteaseTrk = call("library", {
        action: "netease-search",
        query: `${artClean} ${trkClean}`
      }).catch(() => ({ tracks: [] }));

      const [resTidalAlb, resTidalTrk, resNeteaseTrk] = await Promise.all([pTidalAlb, pTidalTrk, pNeteaseTrk]);

      // 打分评估
      let bestTidalAlb = null, bestTidalAlbScore = 0;
      for (const c of (resTidalAlb.collections || []).slice(0, 6)) {
        const sc = evaluateCandidateMatch(item, c);
        if (sc > bestTidalAlbScore) {
          bestTidalAlbScore = sc;
          bestTidalAlb = c;
        }
      }

      let bestTidalTrk = null, bestTidalTrkScore = 0;
      for (const t of (resTidalTrk.tracks || []).slice(0, 6)) {
        const sc = evaluateCandidateMatch(item, t);
        if (sc > bestTidalTrkScore) {
          bestTidalTrkScore = sc;
          bestTidalTrk = t;
        }
      }

      let bestNetTrk = null, bestNetTrkScore = 0;
      for (const t of (resNeteaseTrk.tracks || []).slice(0, 6)) {
        const sc = evaluateCandidateMatch(item, t);
        if (sc > bestNetTrkScore) {
          bestNetTrkScore = sc;
          bestNetTrk = t;
        }
      }

      const maxTidalScore = Math.max(bestTidalAlbScore, bestTidalTrkScore);

      // 规则 A: 网易云精准命中且得分显著高于 TIDAL（典型如华语经典试音碟、东方民族录音）
      if (bestNetTrkScore >= 0.70 && bestNetTrkScore > maxTidalScore) {
        return {
          source: "netease",
          sourceBadge: "网易云 · 无损",
          sourceColor: "#e60026",
          id: bestNetTrk.id,
          title: bestNetTrk.album || item.album,
          artist: bestNetTrk.artist || item.artist,
          year: item.release_year || "",
          keyTrack: bestNetTrk.title,
          coverUrl: bestNetTrk.cover || "",
          trackObj: bestNetTrk,
          rawAlbum: null,
          canDownload: false,
          isLocal: false,
          item
        };
      }

      // 规则 B: TIDAL 专辑或单曲命中，提供 Hi-Res 母带
      if (maxTidalScore >= 0.55) {
        const cover = (bestTidalAlb && bestTidalAlb.coverUrl) || (bestTidalTrk && bestTidalTrk.cover) || "";
        return {
          source: "tidal",
          sourceBadge: "TIDAL · Hi-Res",
          sourceColor: "var(--gold)",
          id: bestTidalAlb ? bestTidalAlb.id : (bestTidalTrk ? bestTidalTrk.id : `ai-${Date.now()}`),
          title: bestTidalAlb ? bestTidalAlb.title : (bestTidalTrk?.album || item.album),
          artist: bestTidalAlb ? (bestTidalAlb.subtitle || item.artist) : (bestTidalTrk?.artist || item.artist),
          year: item.release_year || "",
          keyTrack: bestTidalTrk ? bestTidalTrk.title : (item.key_track || item.album),
          coverUrl: cover,
          trackObj: bestTidalTrk,
          rawAlbum: bestTidalAlb ? { ...bestTidalAlb, source: "tidal" } : null,
          canDownload: !!bestTidalAlb,
          isLocal: false,
          item
        };
      }

      // 规则 C: 网易云作为良好兜底
      if (bestNetTrkScore >= 0.55) {
        return {
          source: "netease",
          sourceBadge: "网易云 · 无损",
          sourceColor: "#e60026",
          id: bestNetTrk.id,
          title: bestNetTrk.album || item.album,
          artist: bestNetTrk.artist || item.artist,
          year: item.release_year || "",
          keyTrack: bestNetTrk.title,
          coverUrl: bestNetTrk.cover || "",
          trackObj: bestNetTrk,
          rawAlbum: null,
          canDownload: false,
          isLocal: false,
          item
        };
      }

    } catch (err) {
      console.warn("Multi-source smart match err", err);
    }

    // 4. 彻底未匹配（得分过低拒绝强行张冠李戴，诚实展示全网探索卡片）
    return {
      source: "unmatched",
      sourceBadge: "全网探索",
      sourceColor: "var(--muted)",
      id: `ai-${Date.now()}-${Math.random()}`,
      title: item.album,
      artist: item.artist,
      year: item.release_year || "",
      keyTrack: item.key_track || item.album,
      coverUrl: "",
      trackObj: null,
      rawAlbum: null,
      canDownload: false,
      isLocal: false,
      item
    };
  }

  async function executeAiSearch(query, rev) {
    const kindLabel = currentSearchKind === "TRACK" ? "单曲" : currentSearchKind === "ARTIST" ? "艺人" : "唱片";
    searchMessage(`✨ AI 发烧音乐顾问正在进行声学研判与【${kindLabel}】策展…`);

    const contextInfo = [];
    const cur = (lastState && lastState.queue && lastState.queue[lastState.index]) || null;
    if (cur && cur.title) {
      contextInfo.push(`当前正在播放曲目：《${cur.title}》- 艺人：${cur.artist}，所属唱片：《${cur.album || "未知"}》，流派标签：${cur.genre || "发烧音乐"}`);
    }
    const localGenres = getCachedAiGenres();
    const genreKeys = Object.keys(localGenres).slice(0, 10);
    if (genreKeys.length) {
      contextInfo.push(`用户机内曲库涵盖的发烧细分流派示例：${genreKeys.join("、")}`);
    }

    let prompt;
    if (currentSearchKind === "TRACK") {
      prompt = `你是一位精通各类音乐母带录音、编曲配器与高保真发烧音响鉴赏的顶级音乐顾问。
用户当前的寻歌诉求是查找【高光发烧单曲】：'${query}'。

${contextInfo.length ? `【听音环境与上下文参考】\n${contextInfo.join("\n")}\n` : ""}

【专业度与真实性绝对红线（严防幻觉与张冠李戴）】：
1. 真实存在性：所有推荐单曲必须是主流流媒体（TIDAL / 网易云）真实公开发行的标准歌曲，必须严格真实收录在对应专辑中，严禁任何虚构！
2. 命题切合度：必须严谨紧扣用户的声学描述（如大提琴、低频瞬态、毒性人声等）！
3. 请同时提供标准歌曲名与中/英文常用译名、原版专辑名与艺人名。

请精选推荐 6-8 首在器乐质感、母带动态与声学细节上极其卓越的高光曲目。
输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "一段精炼的单曲策展主题概括（如：极低频瞬态与微动态试音名作）",
  "listening_guide": "专业的发烧单曲听音导赏分析（约 100-180 字），重点解析这些单曲的编曲织体、声场结像与高光细节...",
  "recommended_gear_focus": "重点品味要点（约 30-60 字）",
  "recommendations": [
    {
      "key_track": "歌曲原名（必填，以流媒体收录名为准）",
      "key_track_alt": "歌曲别名或英文译名（如无留空）",
      "album": "所属原版唱片原名（必填）",
      "album_alt": "唱片别名或英文名（如无留空）",
      "artist": "准确的艺人名字",
      "artist_alt": "艺人英文或中文别名（如无留空）",
      "genre": "细分发烧流派标签",
      "release_year": "发行年份",
      "acoustic_highlight": "发烧亮点标签（如：大提琴松香感、30Hz极低频下潜）",
      "recommendation_reason": "单曲深度推荐理由（约 60-120 字）"
    }
  ]
}`;
    } else if (currentSearchKind === "ARTIST") {
      prompt = `你是一位精通全球音乐历史、母带录音与大师级演绎的高保真发烧音乐顾问。
用户当前的寻歌诉求是查找【大师级音乐家/乐手】：'${query}'。

${contextInfo.length ? `【听音环境与上下文参考】\n${contextInfo.join("\n")}\n` : ""}

【专业度与真实性绝对红线】：
1. 真实存在性：推荐的音乐家与代表专辑必须真实存在于主流流媒体（TIDAL / 网易云），严禁任何虚构！
2. 命题切合度：必须完全契合用户所描述的乐器、学派或艺术风格！
3. 必须列出该音乐家最负盛名的一张发烧代表专辑(representative_album)与高光名曲(key_track)。

请精选推荐 4-6 位在此风格领域具备顶级艺术造诣的大师级音乐家。
输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "一段精炼的大师音乐家策展概括",
  "listening_guide": "音乐家流派谱系与艺术风格导赏分析（约 100-180 字）...",
  "recommended_gear_focus": "重点品味要点（约 30-60 字）",
  "recommendations": [
    {
      "artist": "音乐家准确名字",
      "artist_alt": "英文或中文译名",
      "album": "最核心的发烧代表唱片名",
      "album_alt": "唱片别名或英文译名",
      "key_track": "该唱片中最具代表性的高光单曲",
      "key_track_alt": "单曲别名或英文名",
      "genre": "主打发烧流派与主奏乐器",
      "release_year": "代表作年份",
      "acoustic_highlight": "演奏风格与录音辨识度标签",
      "recommendation_reason": "艺术造诣与必听推荐理由（约 60-120 字）"
    }
  ]
}`;
    } else {
      prompt = `你是一位精通各类音乐母带录音、编曲配器与高保真发烧音响鉴赏的顶级音乐顾问与唱片导赏家。
用户当前的寻歌诉求是查找【发烧唱片专辑】：'${query}'。

${contextInfo.length ? `【听音环境与上下文参考】\n${contextInfo.join("\n")}\n` : ""}

【专业度与真实性绝对红线（严防幻觉与张冠李戴）】：
1. 真实存在性：所有推荐必须是主流流媒体（TIDAL / 网易云）或发烧唱片厂牌（ECM, DG, Decca, Telarc, Chesky, Blue Note, Sony Classical 等）正式公开发行的标准真实唱片，绝对不允许虚构任何不存在的专辑名或曲目！
2. 命题切合度：必须严谨审题！如果用户问人声，只推荐人声唱片，严禁推荐纯器乐；如果问纯器乐，严禁推荐带人声歌曲；如果问特定乐器（大提琴、萨克斯、吉他、钢琴），必须以该乐器为主奏录音！
3. 真实收录名：请同时提供主流流媒体的标准名称以及中/英文常用别名（用于跨源精准索引），key_track 必须真实收录于该唱片中。

请精选推荐 5-7 张在艺术造诣、器乐编排、母带品质与动态细节上极其契合的高水准唱片（包含经典名盘与冷门宝藏，兼顾国际与华语优质制作）。
输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "一段精炼的策展主题概括（如：空灵极简的北欧声场探寻）",
  "listening_guide": "专业的发烧听音导赏分析（约 100-180 字），解析编曲配器、空间声场、母带动态取向与为何契合诉求...",
  "recommended_gear_focus": "重点品味要点（约 30-60 字，如：着重聆听大提琴松香泛音与低频瞬态）",
  "recommendations": [
    {
      "album": "准确的唱片原名（以流媒体收录名为准，勿附带多余符号）",
      "album_alt": "中文或英文常用别名（如无则留空）",
      "artist": "准确的艺人名字",
      "artist_alt": "艺人英文或中文别名（如：久石让 / Joe Hisaishi，蔡琴 / Tsai Chin，如无留空）",
      "key_track": "代表高光单曲名（必填，真实收录于该唱片的代表作）",
      "key_track_alt": "单曲别名或英文译名（如无留空）",
      "genre": "细分发烧流派标签（如：氛围后摇 / 室内融合爵士 / 模拟录音人声）",
      "release_year": "发行年份（如：2015）",
      "acoustic_highlight": "发烧亮点标签（如：24bit/96kHz母带、极高频空气感、定音鼓强弱动态）",
      "recommendation_reason": "深度推荐理由（约 60-120 字，分析器乐亮点与艺术水准）"
    }
  ]
}`;
    }

    try {
      const aiReply = await callAiChat({
        messages: [{ role: "user", content: prompt }],
        temperature: 0.65,
        scene: "search"
      });
      if (rev !== searchRevision) return;

      const parsed = extractJson(aiReply);
      const recs = parsed?.recommendations || parsed?.items || [];
      if (!recs.length) {
        throw new Error("AI 未能识别出符合发烧标准的唱片列表，请尝试更换描述");
      }

      resetSearchLayout();

      if ($("aiCuratorBanner")) {
        if ($("aiCuratorTitle")) $("aiCuratorTitle").textContent = parsed.theme ? `✨ ${parsed.theme}` : "AI 发烧唱片策展与鉴赏";
        if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = parsed.listening_guide || parsed.summary || "根据你的发烧品味精选推荐：";
        if ($("aiCuratorGearFocus")) {
          if (parsed.recommended_gear_focus) {
            $("aiCuratorGearFocus").textContent = `🎧 重点品味要点：${parsed.recommended_gear_focus}`;
            $("aiCuratorGearFocus").hidden = false;
          } else {
            $("aiCuratorGearFocus").hidden = true;
          }
        }
        $("aiCuratorBanner").hidden = false;
      }

      searchMessage("正在全网（本地曲库 / TIDAL / 网易云）跨源精准匹配高保真音频…");

      const matchedCards = await Promise.all(recs.map(resolveRecommendationItem));
      if (rev !== searchRevision) return;

      lastAiRecommendations = matchedCards;
      const playableCount = matchedCards.filter(c => c.trackObj).length;
      const downloadableCards = matchedCards.filter(c => c.canDownload && c.rawAlbum);

      // 默认全选可下载的专辑
      selectedAiCardIds = new Set(downloadableCards.map(c => c.id));
      updateAiSelectedCount();

      if ($("aiPlayAllCount")) $("aiPlayAllCount").textContent = String(playableCount);

      const entityName = currentSearchKind === "TRACK" ? "发烧单曲" : currentSearchKind === "ARTIST" ? "大师音乐家与代表作" : "发烧唱片";
      $("searchCardsTitle").textContent = `深度推荐${entityName} (${matchedCards.length})`;
      $("searchCardsTitle").hidden = false;
      $("searchCardsGrid").hidden = false;
      $("searchTrackList").hidden = true;

      renderAiRecommendationCards(matchedCards, $("searchCardsGrid"));

      aiSearchSession = {
        initialQuery: query,
        turnCount: 0,
        seenAlbums: new Set(recs.map(r => r.album).filter(Boolean)),
        initialSnapshot: {
          theme: parsed.theme,
          guide: parsed.listening_guide || parsed.summary,
          gearFocus: parsed.recommended_gear_focus || "",
          cards: matchedCards,
          playableCount,
          downloadCount: downloadableCards.length
        }
      };

      if ($("aiTurnBadge")) $("aiTurnBadge").hidden = true;
      if ($("btnAiResetTuning")) $("btnAiResetTuning").hidden = true;
      if ($("aiTuningInput")) $("aiTuningInput").value = "";

    } catch (err) {
      if (rev !== searchRevision) return;
      resetSearchLayout();
      searchMessage(`AI 寻歌失败：${err.message}`, true);
    }
  }

  let isAiRefining = false;

  async function executeAiRefinement(feedbackText) {
    if (isAiRefining) return;
    const baseQuery = aiSearchSession.initialQuery || $("globalSearchInput")?.value?.trim() || "";
    if (!baseQuery) {
      toast("请先进行基础寻歌策展后再进行微调", "i-info");
      return;
    }
    const currentRev = searchRevision;
    isAiRefining = true;

    const submitBtn = $("btnAiTuneSubmit");
    const originalBtnText = submitBtn ? submitBtn.innerHTML : "";
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.innerHTML = `<svg class="icon spinning" style="width:13px;height:13px;"><use href="#i-refresh"/></svg> 微调中…`;
    }
    document.querySelectorAll("#aiTuningChips .ai-tuning-chip").forEach(c => c.style.pointerEvents = "none");
    toast(`正在根据「${feedbackText.length > 10 ? feedbackText.slice(0, 10) + "…" : feedbackText}」进行审美定向偏转…`);

    const excludedList = Array.from(aiSearchSession.seenAlbums).slice(-25);
    const cur = (lastState && lastState.queue && lastState.queue[lastState.index]) || null;
    const contextPlaying = cur && cur.title ? `当前播放背景曲目：《${cur.title}》- ${cur.artist}（${cur.album || ""}）` : "";

    const prompt = `你是一位精通各类音乐母带录音、编曲配器与高保真发烧音响鉴赏的顶级音乐顾问与唱片导赏家。
这是我们针对发烧唱片策展的多轮交互对话。

【用户最初始的寻歌审美基调】：'${baseQuery}'
${contextPlaying ? `【当前听音环境】：${contextPlaying}\n` : ""}
【用户最新反馈与声学定向偏转诉求】：'${feedbackText}'

【重要约束条件与排除规则】：
1. 真实存在性：所有推荐必须是主流流媒体（TIDAL / 网易云）真实公开发行的标准唱片，严禁任何虚构幻觉！
2. 绝对排除项：绝对不要重复推荐以下已经展示过的唱片（必须严格排除以下所有专辑）：
${excludedList.map(a => `• 《${a}》`).join("\n")}
3. 保持整体高级发烧母带与高保真鉴赏标准，围绕用户的定向反馈诉求（'${feedbackText}'），精选推荐 5-7 张全新的高水准唱片（兼顾经典名盘与冷门宝藏，兼顾国际与华语优质制作）。
4. 重新提炼本次微调后的策展主题(theme)、针对性发烧听音导赏分析(listening_guide)与重点品味要点(recommended_gear_focus)。
5. 每张唱片必须明确指定一首最能体现该唱片发烧特质与风格精髓的【代表高光曲目(key_track)】，以便直接试听。

输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "定向偏转后的新策展主题概括",
  "listening_guide": "结合用户微调偏向的专业发烧导赏分析（约 100-180 字），重点说明声学取向与选盘变化...",
  "recommended_gear_focus": "本次偏转后的重点品味要点（约 30-60 字）",
  "recommendations": [
    {
      "album": "准确的唱片原名（以流媒体收录名为准，勿附带多余符号）",
      "album_alt": "中文或英文常用别名（如无则留空）",
      "artist": "准确的艺人名字",
      "artist_alt": "艺人英文或中文别名（如：久石让 / Joe Hisaishi，蔡琴 / Tsai Chin，如无留空）",
      "key_track": "代表高光单曲名（必填，真实收录于该唱片的代表作）",
      "key_track_alt": "单曲别名或英文译名（如无留空）",
      "genre": "细分发烧流派标签",
      "release_year": "发行年份",
      "acoustic_highlight": "针对本次偏转的发烧亮点标签",
      "recommendation_reason": "针对本次偏转的推荐理由（约 60-120 字）"
    }
  ]
}`;

    try {
      const aiReply = await callAiChat({
        messages: [{ role: "user", content: prompt }],
        temperature: 0.65,
        scene: "search"
      });
      if (currentRev !== searchRevision) return;

      const parsed = extractJson(aiReply);
      const recs = parsed?.recommendations || parsed?.items || [];
      if (!recs.length) {
        throw new Error("未能生成微调唱片列表，请尝试更换微调方向");
      }

      recs.forEach(r => {
        if (r.album) aiSearchSession.seenAlbums.add(r.album);
      });
      aiSearchSession.turnCount++;

      const matchedCards = await Promise.all(recs.map(resolveRecommendationItem));
      if (currentRev !== searchRevision) return;

      lastAiRecommendations = matchedCards;
      const playableCount = matchedCards.filter(c => c.trackObj).length;
      const downloadCount = matchedCards.filter(c => c.canDownload && c.rawAlbum).length;

      if ($("aiCuratorBanner")) {
        if ($("aiCuratorTitle")) $("aiCuratorTitle").textContent = parsed.theme ? `✨ ${parsed.theme}` : "AI 发烧唱片策展与鉴赏";
        if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = parsed.listening_guide || parsed.summary || "根据微调偏好更新的推荐：";
        if ($("aiCuratorGearFocus")) {
          if (parsed.recommended_gear_focus) {
            $("aiCuratorGearFocus").textContent = `🎧 重点品味要点：${parsed.recommended_gear_focus}`;
            $("aiCuratorGearFocus").hidden = false;
          } else {
            $("aiCuratorGearFocus").hidden = true;
          }
        }
        $("aiCuratorBanner").hidden = false;
      }

      if ($("aiTurnBadge")) {
        $("aiTurnBadge").textContent = `第 ${aiSearchSession.turnCount} 轮微调`;
        $("aiTurnBadge").hidden = false;
      }
      if ($("btnAiResetTuning")) $("btnAiResetTuning").hidden = false;

      if ($("aiPlayAllCount")) $("aiPlayAllCount").textContent = String(playableCount);
      if ($("btnAiDownloadAll")) {
        if ($("aiDownloadCount")) $("aiDownloadCount").textContent = String(downloadCount);
        $("btnAiDownloadAll").hidden = downloadCount === 0;
      }

      $("searchCardsTitle").textContent = `深度推荐发烧唱片 (${matchedCards.length}) · 第 ${aiSearchSession.turnCount} 轮微调`;
      renderAiRecommendationCards(matchedCards, $("searchCardsGrid"));

      if ($("aiTuningInput")) $("aiTuningInput").value = "";
      toast(`已完成声学微调（第 ${aiSearchSession.turnCount} 轮）！`);

    } catch (err) {
      if (currentRev === searchRevision) {
        toast(`微调偏转失败：${err.message}`, "i-trash");
      }
    } finally {
      isAiRefining = false;
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalBtnText;
      }
      document.querySelectorAll("#aiTuningChips .ai-tuning-chip").forEach(c => c.style.pointerEvents = "");
    }
  }

  function resetAiToInitialSnapshot() {
    if (!aiSearchSession.initialSnapshot) return;
    const snap = aiSearchSession.initialSnapshot;
    if ($("aiCuratorTitle")) $("aiCuratorTitle").textContent = snap.theme ? `✨ ${snap.theme}` : "AI 发烧唱片策展与鉴赏";
    if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = snap.guide || "根据你的发烧品味精选推荐：";
    if ($("aiCuratorGearFocus")) {
      if (snap.gearFocus) {
        $("aiCuratorGearFocus").textContent = `🎧 重点品味要点：${snap.gearFocus}`;
        $("aiCuratorGearFocus").hidden = false;
      } else {
        $("aiCuratorGearFocus").hidden = true;
      }
    }
    lastAiRecommendations = snap.cards;
    if ($("aiPlayAllCount")) $("aiPlayAllCount").textContent = String(snap.playableCount);
    if ($("btnAiDownloadAll")) {
      if ($("aiDownloadCount")) $("aiDownloadCount").textContent = String(snap.downloadCount);
      $("btnAiDownloadAll").hidden = snap.downloadCount === 0;
    }
    $("searchCardsTitle").textContent = `深度推荐发烧唱片 (${snap.cards.length})`;
    renderAiRecommendationCards(snap.cards, $("searchCardsGrid"));

    aiSearchSession.turnCount = 0;
    if ($("aiTurnBadge")) $("aiTurnBadge").hidden = true;
    if ($("btnAiResetTuning")) $("btnAiResetTuning").hidden = true;
    if ($("aiTuningInput")) $("aiTuningInput").value = "";
    toast("已恢复初始策展推荐");
  }


  // --- AI Recommendation Cards Rendering & Batch Operations ---
  function renderAiRecommendationCards(items, container, clear = true) {
    if (!container) return;
    if (clear) container.replaceChildren();
    if (!Array.isArray(items) || items.length === 0) return;

    const frag = document.createDocumentFragment();

    items.forEach(item => {
      if (!item) return;
      const card = document.createElement("div");
      card.className = "card-item";
      card.style.display = "flex";
      card.style.flexDirection = "column";
      card.dataset.cardId = item.id || `card-${Math.random()}`;

      const canDl = !!(item.canDownload && item.rawAlbum);
      const isSelected = !!(selectedAiCardIds && selectedAiCardIds.has(item.id));
      if (isSelected) card.classList.add("selected");

      const keyTrackName = item.keyTrack || (item.trackObj && item.trackObj.title) || item.title || "试听曲目";
      const artistName = item.artist || item.subtitle || (item.rawAlbum && (item.rawAlbum.subtitle || item.rawAlbum.detail)) || "未知艺人";
      const subtitleText = `${artistName}${item.year ? ` · ${item.year}` : ""}`;

      const sourceBadge = item.sourceBadge || (item.source === "local" ? "本地已收录 · 无损FLAC" : item.source === "netease" ? "网易云 · 无损" : "TIDAL · Hi-Res");
      const sourceColor = item.sourceColor || (item.source === "local" ? "var(--accent)" : item.source === "netease" ? "#e60026" : "var(--accent)");

      // 防御性安全读取（兼容 item.item 包装或扁平结构）
      const rawMeta = item.item || item;
      const acousticHighlight = rawMeta.acoustic_highlight || rawMeta.genre || item.aiGenre || item.genre || "发烧鉴赏";
      const recommendationReason = rawMeta.recommendation_reason || rawMeta.reason || item.aiReason || item.reason || "";

      card.innerHTML = `
        <div class="card-artwork">
          <img alt="" loading="lazy" decoding="async">
          <div class="ai-card-source-tag" style="color:${sourceColor}; border:1px solid ${sourceColor}55;">
            ${escapeHtml(sourceBadge)}
          </div>
          ${canDl ? `
          <div class="ai-card-checkbox-wrap" title="勾选多选下载此专辑">
            <input type="checkbox" class="ai-card-checkbox" ${isSelected ? "checked" : ""}>
            <span style="font-size:10.5px; font-weight:600;">选</span>
          </div>` : ""}
          <div class="card-play-overlay">
            <button type="button" class="btn-card-play" title="播放整张唱片"><svg class="icon"><use href="#i-play"/></svg></button>
          </div>
        </div>
        <div class="card-title" style="margin-top:8px;" title="${escapeHtml(item.title || "")}"></div>
        <div class="card-subtitle" style="display:flex; justify-content:space-between; align-items:center;">
          <span class="sub-artist"></span>
          <span class="badge gold" style="font-size:10px; padding:1px 6px;">Hi-Res</span>
        </div>
        <div class="ai-key-track-box" title="高光代表作">
          <div style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1;">
            <span style="color:var(--muted); font-size:10.5px;">高光曲目</span>
            <span style="font-weight:600; margin-left:4px;" class="key-track-title"></span>
          </div>
          <button type="button" class="btn-track-quick-play" title="试听该曲"><svg class="icon"><use href="#i-play"/></svg></button>
        </div>
        <div class="ai-reason-tag">
          <span style="color:var(--accent); font-weight:600;">[${escapeHtml(acousticHighlight)}]</span>
          ${escapeHtml(recommendationReason)}
        </div>
        <div style="margin-top:auto; padding-top:8px; display:flex; gap:6px;">
          <button type="button" class="btn-card-listen-track primary" style="flex:1; padding:5px 8px; font-size:11.5px; justify-content:center; display:flex; align-items:center; gap:4px;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-play"/></svg> 试听
          </button>
          ${item.rawAlbum ? `
          <button type="button" class="btn-card-view-album ghost" style="flex:1; padding:5px 8px; font-size:11.5px; justify-content:center; display:flex; align-items:center; gap:4px;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-disc"/></svg> 专辑
          </button>` : ""}
          <button type="button" class="btn-card-enqueue ghost" title="加入播放队列" style="padding:5px 8px; display:inline-flex; align-items:center; justify-content:center;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-add"/></svg>
          </button>
          ${canDl ? `
          <button type="button" class="btn-card-dl ghost" title="下载整张专辑到手机" style="padding:5px 8px; display:inline-flex; align-items:center; justify-content:center;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-download"/></svg>
          </button>` : ""}
        </div>`;

      card.querySelector(".card-title").textContent = item.title || "";
      card.querySelector(".sub-artist").textContent = subtitleText;
      card.querySelector(".key-track-title").textContent = keyTrackName;
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), item.coverUrl, item.title || "");

      // Checkbox 多选事件绑定
      const cbWrap = card.querySelector(".ai-card-checkbox-wrap");
      const cb = card.querySelector(".ai-card-checkbox");
      if (cbWrap && cb) {
        const toggleCb = e => {
          e.stopPropagation();
          if (cb.checked) {
            selectedAiCardIds.add(item.id);
            card.classList.add("selected");
          } else {
            selectedAiCardIds.delete(item.id);
            card.classList.remove("selected");
          }
          updateAiSelectedCount();
        };
        cb.addEventListener("change", toggleCb);
        cbWrap.addEventListener("click", e => {
          if (e.target !== cb) {
            e.stopPropagation();
            cb.checked = !cb.checked;
            cb.dispatchEvent(new Event("change"));
          }
        });
      }

      // 1. Play Key Track Action
      const playKeyTrack = async () => {
        if (item.trackObj) {
          playTracks([item.trackObj]);
        } else if (item.rawAlbum) {
          try {
            const detail = await call("library", { action: "tidal-collection", kind: "ALBUM", collection: String(item.rawAlbum.id) });
            if (detail.tracks?.length) playTracks(detail.tracks);
          } catch {}
        } else {
          toast(`正在全网检索「${artistName} - ${keyTrackName}」…`);
          call("library", { action: "tidal-search", query: `${artistName} ${keyTrackName}` }).then(r => {
            if (r.tracks?.length) playTracks(r.tracks);
          });
        }
      };

      card.querySelector(".btn-track-quick-play")?.addEventListener("click", e => {
        e.stopPropagation();
        playKeyTrack();
      });

      card.querySelector(".btn-card-listen-track")?.addEventListener("click", e => {
        e.stopPropagation();
        playKeyTrack();
      });

      // 2. Play Entire Album Overlay
      card.querySelector(".btn-card-play")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.rawAlbum) {
          const detail = await call("library", { action: "tidal-collection", kind: "ALBUM", collection: String(item.rawAlbum.id) });
          if (detail.tracks?.length) playTracks(detail.tracks);
        } else if (item.trackObj) {
          playTracks([item.trackObj]);
        }
      });

      // 3. View Album Details
      card.querySelector(".btn-card-view-album")?.addEventListener("click", e => {
        e.stopPropagation();
        if (item.rawAlbum) {
          openSearchCollection(item.rawAlbum, "ALBUM");
        }
      });

      // 4. Click card body
      card.addEventListener("click", e => {
        if (e.target.closest("button") || e.target.closest(".ai-card-checkbox-wrap")) return;
        if (item.rawAlbum) {
          openSearchCollection(item.rawAlbum, "ALBUM");
        } else {
          playKeyTrack();
        }
      });

      // 5. Enqueue Action
      card.querySelector(".btn-card-enqueue")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.trackObj) {
          try {
            await call("library", {
              action: "song",
              id: item.trackObj.id,
              mode: "add",
              track: { id: item.trackObj.id, title: item.trackObj.title, artist: item.trackObj.artist, album: item.trackObj.album, cover: item.trackObj.cover }
            });
            toast(`已添加《${keyTrackName}》至播放队列`);
          } catch (err) {
            toast(`添加队列失败：${err.message}`, "i-trash");
          }
        } else {
          toast("该曲目暂无可直接入队的音源", "i-info");
        }
      });

      // 6. Download Action
      card.querySelector(".btn-card-dl")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.rawAlbum && item.canDownload) {
          try {
            await call("downloads", { action: "add", kind: "ALBUM", id: item.rawAlbum.id });
            toast(`已添加专辑「${item.title}」到下载队列`);
          } catch (err) {
            toast(`下载添加失败：${err.message}`, "i-trash");
          }
        }
      });

      frag.appendChild(card);
    });

    container.appendChild(frag);
  }

  // --- Session Action Bar Hooks ---
  if ($("btnAiPlayAll")) {
    $("btnAiPlayAll").addEventListener("click", () => {
      const playable = lastAiRecommendations.map(c => c.trackObj).filter(Boolean);
      if (!playable.length) {
        toast("暂未匹配到可直接播放的音频", "i-trash");
        return;
      }
      toast(`正在启动畅听 ${playable.length} 首 AI 推荐高光曲目…`);
      playTracks(playable, false, 0);
    });
  }

  if ($("btnAiEnqueueAll")) {
    $("btnAiEnqueueAll").addEventListener("click", async () => {
      const playable = lastAiRecommendations.map(c => c.trackObj).filter(Boolean);
      if (!playable.length) {
        toast("暂无可加入队列的曲目", "i-trash");
        return;
      }
      let added = 0;
      for (const t of playable) {
        try {
          await call("library", {
            action: "song",
            id: t.id,
            mode: "add",
            track: { id: t.id, title: t.title, artist: t.artist, album: t.album, cover: t.cover }
          });
          added++;
        } catch {}
      }
      toast(`已将 ${added} 首推荐曲目追加至播放队列！`);
    });
  }

  if ($("btnAiSelectAll")) {
    $("btnAiSelectAll").addEventListener("click", () => {
      const downloadable = lastAiRecommendations.filter(c => c.canDownload && c.rawAlbum);
      if (!downloadable.length) {
        toast("当前推荐列表中无可下载的唱片", "i-info");
        return;
      }
      const allSelected = downloadable.every(c => selectedAiCardIds.has(c.id));
      if (allSelected) {
        selectedAiCardIds.clear();
      } else {
        downloadable.forEach(c => selectedAiCardIds.add(c.id));
      }
      document.querySelectorAll("#searchCardsGrid .card-item").forEach(cardEl => {
        const id = cardEl.dataset.cardId;
        const cb = cardEl.querySelector(".ai-card-checkbox");
        const checked = selectedAiCardIds.has(id);
        if (cb) cb.checked = checked;
        cardEl.classList.toggle("selected", checked);
      });
      updateAiSelectedCount();
    });
  }

  if ($("btnAiDownloadSelected")) {
    $("btnAiDownloadSelected").addEventListener("click", async () => {
      const toDownload = lastAiRecommendations.filter(c => selectedAiCardIds.has(c.id) && c.canDownload && c.rawAlbum);
      if (!toDownload.length) {
        toast("请先勾选需要下载的唱片（或点击全选）", "i-info");
        return;
      }
      toast(`正在将选中的 ${toDownload.length} 张专辑添加至下载队列…`);
      let count = 0;
      for (const it of toDownload) {
        try {
          await call("downloads", { action: "add", kind: "ALBUM", id: it.rawAlbum.id });
          count++;
        } catch {}
      }
      toast(`已成功添加 ${count} 张所选发烧唱片至下载队列！`);
    });
  }

  // --- Feature C: 多轮交互审美定向微调与重置事件绑定 ---
  document.querySelectorAll("#aiTuningChips .ai-tuning-chip").forEach(chip => {
    chip.addEventListener("click", () => {
      const tune = chip.dataset.tune;
      if (tune) executeAiRefinement(tune);
    });
  });

  if ($("aiTuningForm")) {
    $("aiTuningForm").addEventListener("submit", e => {
      e.preventDefault();
      const val = $("aiTuningInput")?.value?.trim();
      if (!val) {
        toast("请输入你想要的微调方向或偏好", "i-info");
        return;
      }
      executeAiRefinement(val);
    });
  }

  if ($("btnAiResetTuning")) {
    $("btnAiResetTuning").addEventListener("click", () => {
      resetAiToInitialSnapshot();
    });
  }

  if ($("aiSeedNowPlaying")) {
    $("aiSeedNowPlaying").addEventListener("click", () => {
      const cur = (lastState && lastState.queue && lastState.queue[lastState.index]) || null;
      if (!cur || !cur.title) return;
      const q = `以当前正在播放的《${cur.title}》（艺人：${cur.artist || "未知"}，所属唱片：《${cur.album || "未知"}》，流派标签：${cur.genre || "发烧音乐"}）为发烧基准，深入寻找在编曲器乐、录音动态、艺术造诣与声学美感上高度契合的同频唱片`;
      $("globalSearchInput").value = q;
      executeGlobalSearch();
    });
  }

  document.querySelectorAll("[data-ai-cat]").forEach(btn => {
    btn.addEventListener("click", () => {
      renderAiCategoryChips(btn.dataset.aiCat);
    });
  });

  // Initial chips & mood radio rendering
  renderAiMoodRadioChips("aiMoodRadioChipsLib");
  renderAiMoodRadioChips("aiMoodRadioChipsSearch");

  // --- Feature 3: 本地曲库流派智能归类 & 猜你喜欢 ---
  function getCachedAiGenres() {
    try {
      const raw = localStorage.getItem(AI_GENRES_KEY);
      if (raw) return JSON.parse(raw);
    } catch {}
    return {};
  }

  function saveCachedAiGenres(genres) {
    localStorage.setItem(AI_GENRES_KEY, JSON.stringify(genres));
  }

  function enrichGenreGroups(groups) {
    // 若曲库已有除未知流派外的真实流派分类，优先使用设备曲库的原生流派
    const validDeviceGroups = groups.filter(g => g.title && g.title !== "未知流派" && g.title !== "未知");
    if (validDeviceGroups.length > 0) return groups;

    const cached = getCachedAiGenres();
    const cachedKeys = Object.keys(cached);
    if (!cachedKeys.length) return groups;

    const genreMap = new Map();
    groups.forEach(g => {
      if (g.title && g.title !== "未知流派" && g.title !== "未知") {
        genreMap.set(g.title, { id: g.id || g.key, key: g.key || g.id, title: g.title, count: g.count, coverUrl: g.coverUrl });
      }
    });

    cachedKeys.forEach(albumTitle => {
      const info = cached[albumTitle];
      const primary = info.primary || "其他";
      const sub = info.sub || "";
      const displayTitle = sub ? `${primary} · ${sub}` : primary;
      if (!genreMap.has(displayTitle)) {
        genreMap.set(displayTitle, {
          key: `ai_genre_${primary}`,
          title: displayTitle,
          count: 1,
          isAi: true
        });
      } else {
        const item = genreMap.get(displayTitle);
        item.count += 1;
      }
    });

    return Array.from(genreMap.values());
  }

  async function openAiGenreModal() {
    $("aiGenreModal")?.classList.add("active");
    if ($("aiGenreProgressBox")) $("aiGenreProgressBox").hidden = true;
    if ($("aiGenreLogList")) $("aiGenreLogList").innerHTML = `<div style="color:var(--dim); text-align:center; padding:20px;">正在扫描本地曲库…</div>`;

    try {
      const res = await call("library", { action: "local", category: 1, offset: 0 });
      const albums = res.groups || [];
      const cached = getCachedAiGenres();
      const cachedKeys = Object.keys(cached);

      if ($("aiGenreStats")) $("aiGenreStats").textContent = `${albums.length} 张专辑`;
      if ($("aiGenreCachedCount")) $("aiGenreCachedCount").textContent = `${cachedKeys.length} 个已归类`;

      const unmapped = albums.filter(a => !cached[a.title]);
      if ($("aiGenreLogList")) {
        $("aiGenreLogList").innerHTML = `
          <div style="font-size:12.5px; color:var(--muted); line-height:1.6;">
            本地共发现 <strong>${albums.length}</strong> 张专辑。<br>
            已建档精准流派：<strong style="color:var(--accent);">${cachedKeys.length}</strong> 张。<br>
            待整理流派：<strong style="color:var(--gold);">${unmapped.length}</strong> 张。<br><br>
            点击下方「开始智能归类」，AI 将结合音乐史与发烧音响体系自动规范分类。
          </div>`;
      }
    } catch (err) {
      if ($("aiGenreLogList")) $("aiGenreLogList").innerHTML = `<div style="color:var(--danger); padding:20px;">读取曲库失败: ${escapeHtml(err.message)}</div>`;
    }
  }

  let isClassifying = false;
  async function runAiGenreBatch() {
    if (isClassifying) return;
    isClassifying = true;

    if ($("btnStartAiGenre")) $("btnStartAiGenre").disabled = true;
    if ($("aiGenreProgressBox")) $("aiGenreProgressBox").hidden = false;
    const logList = $("aiGenreLogList");
    logList.innerHTML = "";

    try {
      const res = await call("library", { action: "local", category: 1, offset: 0 });
      const albums = res.groups || [];
      const cached = getCachedAiGenres();
      const unmapped = albums.filter(a => !cached[a.title]);

      if (!unmapped.length) {
        logList.innerHTML = `<div style="color:var(--accent); text-align:center; padding:20px;">所有本地专辑均已完成发烧流派归类！</div>`;
        return;
      }

      const BATCH_SIZE = 12;
      const total = unmapped.length;
      let processed = 0;

      for (let i = 0; i < unmapped.length; i += BATCH_SIZE) {
        const batch = unmapped.slice(i, i + BATCH_SIZE);
        const albumListStr = batch.map((a, idx) => `${idx + 1}. 《${a.title}》 艺人: ${a.detail || "未知"}`).join("\n");

        const prompt = `请对以下音乐专辑进行严谨、专业的音乐发烧流派精准归类：
${albumListStr}

分类标准请参考发烧与专业乐坛体系，一级大类尽量统一规范为（如：古典音乐、爵士乐、流行、摇滚、民谣、原声带、电子、民乐、R&B/灵魂等），二级子流派需具体（如：室内乐、后摇、慢调爵士、巴洛克、模拟人声）。
请以纯 JSON 格式输出，格式严格如下：
{
  "results": [
    {
      "album": "专辑原名",
      "primary": "一级大流派（简练）",
      "sub": "细分子流派或主导器乐"
    }
  ]
}`;

        try {
          const aiReply = await callAiChat({
            messages: [{ role: "user", content: prompt }],
            temperature: 0.2,
            scene: "genre"
          });

          const parsed = extractJson(aiReply);
          if (parsed && Array.isArray(parsed.results)) {
            parsed.results.forEach(item => {
              if (item.album && item.primary) {
                cached[item.album] = {
                  primary: item.primary,
                  sub: item.sub || ""
                };
                const itemDiv = document.createElement("div");
                itemDiv.style.fontSize = "12px";
                itemDiv.style.padding = "3px 0";
                itemDiv.innerHTML = `✓ <strong>${escapeHtml(item.album)}</strong> → <span style="color:var(--accent); font-weight:600;">${escapeHtml(item.primary)}</span>${item.sub ? ` <span style="color:var(--muted); font-size:11px;">(${escapeHtml(item.sub)})</span>` : ""}`;
                logList.prepend(itemDiv);
              }
            });
            saveCachedAiGenres(cached);
          }
        } catch (err) {
          const errDiv = document.createElement("div");
          errDiv.style.color = "var(--danger)";
          errDiv.style.fontSize = "12px";
          errDiv.textContent = `批次处理异常: ${err.message}`;
          logList.prepend(errDiv);
        }

        processed += batch.length;
        const pct = Math.min(100, Math.round((processed / total) * 100));
        if ($("aiGenreProgressBar")) $("aiGenreProgressBar").style.width = `${pct}%`;
        if ($("aiGenreProgressText")) $("aiGenreProgressText").textContent = `进度: ${processed}/${total} (${pct}%)`;
      }

      toast("本地曲库流派智能归类已完成！");
      if (activeView === "library") loadLibrary();

    } catch (err) {
      toast(`归类失败: ${err.message}`, "i-trash");
    } finally {
      isClassifying = false;
      if ($("btnStartAiGenre")) $("btnStartAiGenre").disabled = false;
    }
  }

  $("btnOpenAiGenreModal")?.addEventListener("click", openAiGenreModal);
  $("btnCloseAiGenreModal")?.addEventListener("click", () => {
    $("aiGenreModal")?.classList.remove("active");
  });
  $("btnStartAiGenre")?.addEventListener("click", runAiGenreBatch);

  $("btnSyncAiGenreToLibrary")?.addEventListener("click", async () => {
    const cached = getCachedAiGenres();
    const genreUpdates = {};
    Object.entries(cached).forEach(([album, info]) => {
      const display = info.sub ? `${info.primary} · ${info.sub}` : (info.primary || "");
      if (display) genreUpdates[album] = display;
    });
    const totalAlbums = Object.keys(genreUpdates).length;
    if (!totalAlbums) {
      toast("暂无已归类的流派缓存");
      return;
    }
    toast(`正在将 ${totalAlbums} 张专辑流派同步至曲库…`);
    try {
      const res = await call("library", { action: "genre-update", genres: genreUpdates });
      toast(`成功同步 ${res.updated || 0} 首歌曲流派到曲库`);
      if (activeView === "library") loadLibrary();
    } catch (err) {
      toast(`同步失败: ${err.message}`, "i-trash");
    }
  });

  // --- Feature 3 Part B: 猜你喜欢 (基于曲库的个性化推荐) ---
  async function executeAiCuratedLibrary(rev) {
    const container = $("libraryCardsView");
    if (!container) return;
    container.replaceChildren();
    container.innerHTML = `
      <div style="grid-column:1/-1; text-align:center; padding:60px 20px;">
        <div class="ai-thinking-indicator">
          <svg class="icon" style="width:26px; height:26px;"><use href="#i-sparkles"/></svg>
          <span>AI 音乐总监正在研判你的本地听音品味画像…</span>
        </div>
      </div>`;

    try {
      const res = await call("library", { action: "local", category: 1, offset: 0 });
      const albums = (res.groups || []).slice(0, 25);

      if (!albums.length) {
        container.innerHTML = `
          <div style="grid-column:1/-1; text-align:center; padding:60px; color:var(--muted);">
            本地曲库暂无足够的唱片来构建品味画像，请先下载或导入音乐。
          </div>`;
        return;
      }

      const sampleStr = albums.map(a => `《${a.title}》（艺人：${a.detail || "未知"}）`).join("、");
      const prompt = `用户本地高保真音乐库中收藏有以下代表性唱片：
${sampleStr}

请作为顶级音乐策展人，完成两项任务：
1. 撰写一份敏锐、精准的【听音品味画像】（约 100-150 字），概括用户的听觉审美风格与音响器材取向。
2. 精选推荐 8-12 张在 TIDAL 上具有 Hi-Res 无损母带的高契合度宝藏唱片（未在上述列表中）。

请以纯 JSON 格式输出，严格符合以下结构：
{
  "profile": "听音品味深度画像与审美分析...",
  "recommendations": [
    {
      "album": "专辑原名",
      "album_alt": "专辑英文名或原语种名（如无留空）",
      "artist": "艺人原名",
      "artist_alt": "艺人别名（如无留空）",
      "key_track": "最具代表性的高光单曲名（必填）",
      "genre": "细分发烧流派标签",
      "release_year": "发行年份",
      "acoustic_highlight": "发烧亮点标签（如：大提琴松香感、极低频下潜、模拟醇厚人声）",
      "recommendation_reason": "为何契合你的品味与该唱片的高保真鉴赏要点（约 60-120 字）"
    }
  ]
}`;

      const aiReply = await callAiChat({ messages: [{ role: "user", content: prompt }], temperature: 0.7, scene: "search" });
      if (rev !== libraryRevision) return;

      const parsed = extractJson(aiReply);
      const recs = parsed?.recommendations || [];
      if (!recs.length) {
        throw new Error("未能生成推荐唱片列表");
      }

      container.replaceChildren();

      // 1. 深度画像横幅
      const banner = document.createElement("div");
      banner.className = "ai-curator-banner";
      banner.style.gridColumn = "1 / -1";
      banner.innerHTML = `
        <svg class="icon" style="color:var(--gold); flex-shrink:0;"><use href="#i-sparkles"/></svg>
        <div>
          <div style="font-weight:750; color:var(--gold); font-size:15px; margin-bottom:4px;">🎧 你的听音品味深度画像</div>
          <div style="font-size:13.5px; color:var(--ink); line-height:1.65;">${escapeHtml(parsed.profile || "")}</div>
        </div>`;
      container.appendChild(banner);

      // 2. 跨源精准匹配并生成标准发烧卡片
      const matchedCards = await Promise.all(recs.map(resolveRecommendationItem));
      if (rev !== libraryRevision) return;

      lastAiRecommendations = matchedCards;
      const downloadableCards = matchedCards.filter(c => c.canDownload && c.rawAlbum);
      selectedAiCardIds = new Set(downloadableCards.map(c => c.id));
      updateAiSelectedCount();

      // 渲染卡片，保留顶部画像横幅 (clear = false)
      renderAiRecommendationCards(matchedCards, container, false);

    } catch (err) {
      if (rev !== libraryRevision) return;
      container.innerHTML = `
        <div style="grid-column:1/-1; text-align:center; padding:40px; color:var(--danger);">
          生成个性化推荐失败: ${escapeHtml(err.message)}
        </div>`;
    }
  }

  // --- 同频候选：按艺人/流派/年代给本地曲库打分，再让 AI 从候选中挑选 ---
  // 无尽续播本身已移到手机端（AutoDj.java），这里的工具留给网页上的推荐功能复用。
  const AUTO_DJ_BATCH = 6;
  const AUTO_DJ_POOL = 60;
  const AUTO_DJ_MAX_EXTERNAL = 2;
  const autoDjAdded = new Set();

  function autoDjNorm(value) {
    return String(value || "").trim().toLowerCase();
  }

  function autoDjGenre(track, aiGenres) {
    return track.genre || aiGenres[track.album]?.primary || "";
  }

  function buildAutoDjPool(seeds, queue, library) {
    const aiGenres = getCachedAiGenres();
    const exclude = new Set(queue.map(t => String(t.id)));
    autoDjAdded.forEach(id => exclude.add(id));
    const seedArtists = new Set(seeds.flatMap(t => [autoDjNorm(t.artist), autoDjNorm(t.albumArtist)]).filter(Boolean));
    const seedAlbums = new Set(seeds.map(t => autoDjNorm(t.album)).filter(Boolean));
    const seedGenres = new Set(seeds.map(t => autoDjNorm(autoDjGenre(t, aiGenres))).filter(Boolean));
    const seedYears = seeds.map(t => parseInt(t.year, 10)).filter(y => y > 1000);

    const scored = [];
    for (const t of library) {
      if (!t || !t.title || exclude.has(String(t.id))) continue;
      let score = Math.random() * 1.5;
      if (seedArtists.has(autoDjNorm(t.artist)) || seedArtists.has(autoDjNorm(t.albumArtist))) score += 4;
      if (seedAlbums.has(autoDjNorm(t.album))) score += 2;
      const genre = autoDjNorm(autoDjGenre(t, aiGenres));
      if (genre && seedGenres.has(genre)) score += 3;
      const year = parseInt(t.year, 10);
      if (year > 1000 && seedYears.some(y => Math.abs(y - year) <= 5)) score += 1;
      if (t.lossless) score += 0.5;
      scored.push({ score, track: t, genre: autoDjGenre(t, aiGenres) });
    }
    scored.sort((a, b) => b.score - a.score);

    // 高分段每张专辑最多 3 首，再随机补一些远一点的曲目，给 AI 留出"拓展"空间。
    const pool = [];
    const taken = new Set();
    const perAlbum = new Map();
    for (const item of scored) {
      if (pool.length >= AUTO_DJ_POOL - 15) break;
      const albumKey = autoDjNorm(item.track.album) + "|" + autoDjNorm(item.track.albumArtist || item.track.artist);
      const count = perAlbum.get(albumKey) || 0;
      if (count >= 3) continue;
      perAlbum.set(albumKey, count + 1);
      pool.push(item);
      taken.add(item);
    }
    const rest = scored.filter(item => !taken.has(item));
    while (pool.length < AUTO_DJ_POOL && rest.length) {
      pool.push(rest.splice(Math.floor(Math.random() * rest.length), 1)[0]);
    }
    return pool;
  }

  function autoDjHeuristicPicks(pool, count) {
    const perArtist = new Map();
    const picks = [];
    for (const item of [...pool].sort((a, b) => b.score - a.score)) {
      if (picks.length >= count) break;
      const artist = autoDjNorm(item.track.artist);
      if ((perArtist.get(artist) || 0) >= 2) continue;
      perArtist.set(artist, (perArtist.get(artist) || 0) + 1);
      picks.push(item.track);
    }
    return picks;
  }

  function autoDjLine(track, genre) {
    const cut = v => String(v || "").replace(/[|\n]/g, " ").slice(0, 40);
    return [cut(track.title), cut(track.artist), cut(track.album), cut(genre), cut(track.year)].join("|");
  }

  async function autoDjAiPicks(seeds, pool, count, { scene = "autodj", allowExternal = true } = {}) {
    const aiGenres = getCachedAiGenres();
    const seedText = seeds.map((t, i) => `${i + 1}. 《${t.title}》— ${t.artist || "未知"}（${[t.album, autoDjGenre(t, aiGenres), t.year].filter(Boolean).join("，")}）`).join("\n");
    const candidates = pool.map((item, i) => `c${i + 1}|${autoDjLine(item.track, item.genre)}`).join("\n");
    const externalMax = allowExternal ? Math.min(AUTO_DJ_MAX_EXTERNAL + Math.max(0, count - pool.length), count) : 0;

    const prompt = `你是一位懂发烧录音的私人电台 DJ。听众正在连续聆听（最后一首是正在播放的）：
${seedText}

以下是听众本地曲库中的候选曲目（编号|曲名|艺人|专辑|流派|年份）：
${candidates || "（无）"}

请挑选 ${count} 首最适合接在后面播放的曲目：
- 与正在播放的曲风、情绪、能量自然衔接，按播放顺序排列，整体起伏平滑
- 同一艺人最多 2 首，不要连续两首出自同一张专辑
- picks 只能填写上面候选中的编号，不得编造
${externalMax ? `- 如果候选中合适的不足，可在 external 中补充最多 ${externalMax} 首曲库之外真实存在的曲目\n` : ""}
仅输出 JSON：{"theme":"不超过12字的续播主题","picks":["c3","c17"],"external":[{"artist":"艺人","album":"专辑","key_track":"曲名"}]}`;

    const reply = await callAiChat({
      messages: [{ role: "user", content: prompt }],
      temperature: 0.6,
      scene,
      interactive: false,
      timeout: 30000,
      maxTokens: 800
    });
    const parsed = extractJson(reply);
    if (!parsed || !Array.isArray(parsed.picks)) throw new Error("续播结果格式无效");

    // 模型不一定守规矩：在代码里强制同艺人最多 2 首、相邻两首不同专辑。
    const seen = new Set();
    const perArtist = new Map();
    const local = [];
    let prevAlbum = autoDjNorm(seeds[seeds.length - 1]?.album);
    for (const code of parsed.picks) {
      const index = parseInt(String(code).replace(/^c/i, ""), 10) - 1;
      const item = pool[index];
      if (!item || seen.has(index) || local.length >= count) continue;
      const artist = autoDjNorm(item.track.artist);
      const album = autoDjNorm(item.track.album);
      if ((perArtist.get(artist) || 0) >= 2 || (album && album === prevAlbum)) continue;
      seen.add(index);
      perArtist.set(artist, (perArtist.get(artist) || 0) + 1);
      prevAlbum = album;
      local.push(item.track);
    }
    const external = (Array.isArray(parsed.external) ? parsed.external : [])
      .filter(e => e && e.artist && (e.key_track || e.album))
      .slice(0, Math.min(externalMax, count - local.length));
    return { theme: String(parsed.theme || "").slice(0, 16), local, external };
  }

  // --- 曲库自然语言搜索 ---
  // 输入像"适合深夜独处的慢歌""有大提琴的古典"这类描述时：普通筛选没有结果就自动交给 AI，
  // 有结果则在顶部给出"用 AI 理解"入口。AI 只在本地曲库的专辑索引里挑选，结果都是机内文件。
  const NL_HINT = /适合|类似|风格|氛围|感觉|心情|一点|有.{1,6}的|带.{1,6}的|安静|深夜|夜晚|清晨|早上|下雨|雨天|开车|学习|工作时|睡前|放松|伤感|治愈|欢快|激昂|慢歌|快歌|纯音乐|器乐|女声|男声|年代|老歌|想听|来点|听听/;
  const LIB_AI_MAX_ALBUMS = 300;
  const LIB_AI_MAX_CHARS = 36000;
  const LIB_AI_MAX_TRACKS = 80;

  function looksNaturalLanguage(query) {
    const q = (query || "").trim();
    if (q.length < 4) return false;
    if (NL_HINT.test(q)) return true;
    return /^[a-z\s',-]+$/i.test(q) && q.split(/\s+/).length >= 4;
  }

  function buildLibraryAlbumIndex(tracks) {
    const aiGenres = getCachedAiGenres();
    const albums = new Map();
    for (const t of tracks) {
      const key = autoDjNorm(t.album) + "|" + autoDjNorm(t.albumArtist || t.artist);
      let entry = albums.get(key);
      if (!entry) {
        entry = { album: t.album || "未知专辑", artist: t.albumArtist || t.artist || "", genre: "", year: t.year || "", tracks: [] };
        albums.set(key, entry);
      }
      entry.tracks.push(t);
      if (!entry.genre) entry.genre = t.genre || aiGenres[t.album]?.primary || "";
    }
    const list = [...albums.values()];
    list.forEach(a => a.tracks.sort((x, y) => (x.discNumber || 1) - (y.discNumber || 1) || (x.trackNumber || 0) - (y.trackNumber || 0)));
    return list.slice(0, LIB_AI_MAX_ALBUMS);
  }

  function libraryAlbumLines(albums) {
    const cut = (v, n) => String(v || "").replace(/[|\n]/g, " ").slice(0, n);
    const lines = [];
    let chars = 0;
    for (let i = 0; i < albums.length; i++) {
      const a = albums[i];
      const sample = a.tracks.slice(0, 3).map(t => cut(t.title, 24)).join(" / ");
      const line = `a${i + 1}|${cut(a.album, 40)}|${cut(a.artist, 30)}|${cut(a.genre, 16)}|${cut(a.year, 4)}|${a.tracks.length}首|${sample}`;
      if (chars + line.length > LIB_AI_MAX_CHARS) break;
      chars += line.length + 1;
      lines.push(line);
    }
    return lines;
  }

  async function aiSearchLocalLibrary(query, interactive) {
    const library = await getLocalTracksCache();
    if (!library.length) return { intent: "", tracks: [] };
    const albums = buildLibraryAlbumIndex(library);
    const lines = libraryAlbumLines(albums);

    const data = await aiCached({
      cacheKey: aiCacheKey("libsearch", query, library.length),
      ttlDays: 30,
      scene: "search",
      interactive,
      temperature: 0.3,
      timeout: 30000,
      maxTokens: 900,
      messages: [{
        role: "user",
        content: `听众想在自己的本地曲库里找：「${query}」

曲库专辑清单（编号|专辑|艺人|流派|年份|曲目数|部分曲目）：
${lines.join("\n")}

请理解听众的意图（场景、情绪、乐器、人声、年代、流派等），结合你对这些专辑的了解，挑出最符合的专辑，最多 8 张，按匹配度排序。
如果意图指向专辑中的具体曲目（如某件乐器的独奏、某首歌），在 titles 中写出该专辑里最符合的真实曲名；否则 titles 留空。
只能使用清单中的编号。仅输出 JSON：{"intent":"不超过20字的意图理解","albums":[{"id":"a3","titles":["曲名"]}]}`
      }],
      parse: text => {
        const parsed = extractJson(text);
        if (!parsed || !Array.isArray(parsed.albums)) return null;
        const ids = [];
        for (const pick of parsed.albums.slice(0, 8)) {
          const album = albums[parseInt(String(pick?.id || "").replace(/^a/i, ""), 10) - 1];
          if (!album) continue;
          const titles = Array.isArray(pick.titles) ? pick.titles.filter(t => typeof t === "string" && t.trim()) : [];
          const chosen = titles.length
            ? album.tracks.filter(t => titles.some(title => computeStringSimilarity(title, t.title) >= 0.6))
            : [];
          (chosen.length ? chosen : album.tracks).forEach(t => ids.push(String(t.id)));
        }
        return { intent: String(parsed.intent || "").slice(0, 30), ids: [...new Set(ids)].slice(0, LIB_AI_MAX_TRACKS) };
      }
    });

    const byId = new Map(library.map(t => [String(t.id), t]));
    return { intent: data.intent, tracks: data.ids.map(id => byId.get(id)).filter(Boolean) };
  }

  function libraryAiBanner() {
    let banner = $("libraryAiBanner");
    if (!banner) {
      banner = document.createElement("div");
      banner.id = "libraryAiBanner";
      banner.hidden = true;
      banner.style.cssText = "display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin:0 0 12px; padding:10px 14px; border-radius:var(--radius-md); border:1px solid rgba(245,197,66,0.22); background:linear-gradient(135deg, rgba(45,226,171,0.06), rgba(245,197,66,0.06)); font-size:13px;";
      $("libraryTrackList").before(banner);
    }
    return banner;
  }

  function hideLibraryAiBanner() {
    const banner = $("libraryAiBanner");
    if (banner) banner.hidden = true;
  }

  function setLibraryAiBanner(text, actions = []) {
    const banner = libraryAiBanner();
    banner.replaceChildren();
    const icon = document.createElement("span");
    icon.textContent = "✨";
    const label = document.createElement("span");
    label.style.cssText = "flex:1; min-width:0; color:var(--ink);";
    label.textContent = text;
    banner.append(icon, label);
    actions.forEach(([caption, handler, primary]) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = primary ? "primary" : "ghost";
      btn.style.cssText = "padding:4px 12px; font-size:12.5px;";
      btn.textContent = caption;
      btn.addEventListener("click", handler);
      banner.appendChild(btn);
    });
    banner.hidden = false;
  }

  async function runLibraryAiSearch(query, rev, interactive) {
    setLibraryAiBanner(`正在用 AI 理解「${query}」，在本地曲库里挑选…`);
    try {
      const result = await aiSearchLocalLibrary(query, interactive);
      if (rev !== libraryRevision) return;
      if (!result.tracks.length) {
        setLibraryAiBanner(`AI 没有在本地曲库里找到符合「${query}」的曲目`);
        return;
      }
      setLibraryAiBanner(`AI 理解为：${result.intent || query} · ${result.tracks.length} 首`, [
        ["播放全部", () => playTracks(result.tracks), true],
        ["随机播放", () => playTracks(result.tracks, true), false]
      ]);
      $("libraryCountText").textContent = `AI 语义匹配 ${result.tracks.length} 首`;
      renderTrackRows(result.tracks, $("libraryTrackList"));
      libraryNext = -1;
      $("btnLibMore").hidden = true;
    } catch (err) {
      if (rev !== libraryRevision) return;
      if (err instanceof AiUnavailableError && !interactive) hideLibraryAiBanner();
      else setLibraryAiBanner(`AI 搜索失败：${err.message}`);
    }
  }

  /** 本地"歌曲"列表加载完后调用：决定是否自动或可选地进行 AI 语义搜索。 */
  async function updateLibraryAiSearch(query, total, rev) {
    if (!looksNaturalLanguage(query)) {
      hideLibraryAiBanner();
      return;
    }
    await refreshAiStatus();
    if (rev !== libraryRevision) return;
    if (!aiReady()) {
      hideLibraryAiBanner();
      return;
    }
    if (total > 0) {
      setLibraryAiBanner(`「${query}」像是一句描述，要让 AI 按意思在曲库里找吗？`, [
        ["用 AI 理解", () => runLibraryAiSearch(query, libraryRevision, true), true]
      ]);
      return;
    }
    // 没有字面匹配：稍等片刻确认用户停止输入后再自动调用，避免逐字触发。
    setTimeout(() => {
      if (rev === libraryRevision && $("librarySearchInput").value.trim() === query) {
        runLibraryAiSearch(query, rev, false);
      }
    }, 700);
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

