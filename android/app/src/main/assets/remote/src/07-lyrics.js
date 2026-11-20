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

