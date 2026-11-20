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

