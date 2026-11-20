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

