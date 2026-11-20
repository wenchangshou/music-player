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

