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

