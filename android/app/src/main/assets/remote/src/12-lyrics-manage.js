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

