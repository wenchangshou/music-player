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
