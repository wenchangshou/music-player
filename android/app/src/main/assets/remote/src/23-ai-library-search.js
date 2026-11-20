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
