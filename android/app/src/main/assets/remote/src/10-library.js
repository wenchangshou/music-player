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

