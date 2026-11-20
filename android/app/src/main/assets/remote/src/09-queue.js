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

