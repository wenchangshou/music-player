  // --- Feature 1: AI 发烧心境电台（深度唤醒机内曲库，免打字沉浸秒播） ---
  const AI_MOOD_PRESETS = [
    {
      id: "strings",
      title: "🎻 室内乐·松香泛音",
      icon: "🎻",
      query: "极具松香摩擦质感与高频空气感的小提琴、大提琴室内乐与弦乐协奏曲名盘",
      keywords: ["violin", "concerto", "mozart", "paganini", "chua", "chloe", "小提琴", "协奏曲", "室内乐", "弦乐", "提琴", "大提琴", "bach", "brahms"],
      quickGuide: "着重品味弦乐擦弦的松香泛音细节与空气感，琴体木质共鸣醇厚自然，考验耳机的极高频延展与微动态响应。"
    },
    {
      id: "piano",
      title: "🎹 肖邦夜曲·琴键微光",
      icon: "🎹",
      query: "琴键颗粒感通透、深夜静谧空间残响与微弱触键泛音的古典钢琴名盘",
      keywords: ["chopin", "nocturne", "piano", "hicks", "肖邦", "夜曲", "钢琴", "独奏", "键盘", "rubinstein", "pires", "argerich"],
      quickGuide: "静心感受琴槌轻触钢丝的呢喃与踏板延音泛音，声场残响宁静开阔，极度适合深夜独处聆听。"
    },
    {
      id: "oriental",
      title: "🏮 东方意境·梁祝民乐",
      icon: "🏮",
      query: "东方婉转旋律、民族管弦乐与梁祝小提琴交响协奏曲高保真发烧唱片",
      keywords: ["butterfly lovers", "梁祝", "阿嬷", "陈佳", "东方", "中国", "民乐", "情书", "古筝", "二胡", "琵琶", "雨果"],
      quickGuide: "东方式写意抒情与管弦乐团磅礴气势交融，高胡与小提琴如泣如诉，线条极度顺滑耐听。"
    },
    {
      id: "live_vocal",
      title: "🎙️ 现场原声·毒性人声",
      icon: "🎙️",
      query: "现场舞台Live沉浸感、人声口型结像极其清晰温润的发烧民谣与女声",
      keywords: ["live", "tour", "doudou", "春夏秋冬", "现场", "民谣", "陈佳", "情书", "原声", "蔡琴", "邓丽君", "vocal"],
      quickGuide: "人声口型结像高度凝聚靠前，呼吸气声与现场舞台混响空间感触手可及，中频饱满温润有肉感。"
    },
    {
      id: "deep_jazz",
      title: "☕ 深夜微醺·慢调爵士",
      icon: "☕",
      query: "具有ECM或Blue Note标志性清冷声场、低音大提琴拨弦肉感与沙哑萨克斯的慢调爵士名盘",
      keywords: ["jazz", "sax", "trio", "ecm", "爵士", "萨克斯", "夜曲", "miles", "coltrane", "bill evans", "jarrett"],
      quickGuide: "低音提琴拨弦弹性十足，鼓刷拂过铜钹的沙沙声清晰逼真，萨克斯管壁金属共振如在耳畔。"
    },
    {
      id: "dynamic_orchestra",
      title: "💥 大动态爆棚·交响试金",
      icon: "💥",
      query: "具有极深声场纵深、定音鼓捶击瞬态爆发力与管弦齐鸣大动态的顶级交响试音天碟",
      keywords: ["symphony", "orchestra", "concerto", "交响", "管弦", "合奏", "动态", "telarc", "mahler", "tchaikovsky"],
      quickGuide: "大编制交响乐队齐奏时层次井然，考验播放器材的推力储备与声场下盘控制力，动态大起大落极具震撼力。"
    },
    {
      id: "ambient_nordic",
      title: "❄️ 冷冽极简·北欧声场",
      icon: "❄️",
      query: "具有Sigur Rós、Ólafur Arnalds风格的冰岛清冷空灵氛围后摇与新古典极简声场",
      keywords: ["ambient", "post-rock", "minimal", "nordic", "后摇", "氛围", "新古典", "冰岛", "极简", "olafur", "sigur"],
      quickGuide: "声场如同极地冰原般空旷通透，微妙的合成器微光与真实弦乐交织，带给人纯净的心灵洗涤。"
    },
    {
      id: "soundtrack_peace",
      title: "🌧️ 雨天独处·电影原声",
      icon: "🌧️",
      query: "具有深刻故事叙事感、管弦与钢琴交织的电影大师级电影原声配乐名作",
      keywords: ["soundtrack", "ost", "score", "原声", "电影", "情书", "久石让", "坂本龙一", "morricone", "zimmer"],
      quickGuide: "叙事氛围感与画面感极强，优美的器乐独奏缓缓流淌，陪伴每一个安静独处的阅读或思绪时刻。"
    }
  ];

  let activeMoodRadioId = null;
  let cachedLocalTracksForMood = null;

  let cachedLocalTracksRevision = null;
  let localTracksPromise = null;

  // 整个本地曲库（分页拉全，最多 5000 首）；曲库文件变化后自动失效。
  async function getLocalTracksCache() {
    const revision = lastState?.filesRevision ?? null;
    if (cachedLocalTracksForMood && cachedLocalTracksForMood.length > 0 && revision === cachedLocalTracksRevision) {
      return cachedLocalTracksForMood;
    }
    if (localTracksPromise) return localTracksPromise;
    localTracksPromise = (async () => {
      const all = [];
      let offset = 0;
      try {
        while (offset >= 0 && all.length < 5000) {
          const res = await call("library", { action: "local", query: "", offset, limit: 500 });
          if (!res || !Array.isArray(res.tracks)) break;
          all.push(...res.tracks);
          offset = typeof res.next === "number" ? res.next : -1;
        }
      } catch (e) {
        console.warn("Fetch local tracks err", e);
      }
      if (all.length) {
        cachedLocalTracksForMood = all;
        cachedLocalTracksRevision = revision;
      }
      return all.length ? all : (cachedLocalTracksForMood || []);
    })().finally(() => { localTracksPromise = null; });
    return localTracksPromise;
  }

  // 时段感知：按当前时段的预设偏好 + 听众在这个时段实际点过的心境排序。
  const MOOD_TIME_AFFINITY = {
    morning: ["strings", "piano", "oriental", "live_vocal"],
    afternoon: ["live_vocal", "oriental", "dynamic_orchestra", "strings"],
    evening: ["deep_jazz", "soundtrack_peace", "live_vocal", "piano"],
    night: ["deep_jazz", "ambient_nordic", "piano", "soundtrack_peace"]
  };
  const MOOD_HISTORY_KEY = "tidal_mood_hours";

  function moodTimeBucket(hour = new Date().getHours()) {
    if (hour >= 5 && hour < 11) return "morning";
    if (hour >= 11 && hour < 17) return "afternoon";
    if (hour >= 17 && hour < 22) return "evening";
    return "night";
  }

  function moodHistory() {
    try { return JSON.parse(localStorage.getItem(MOOD_HISTORY_KEY) || "{}") || {}; } catch { return {}; }
  }

  function recordMoodLaunch(moodId) {
    const history = moodHistory();
    const bucket = moodTimeBucket();
    history[bucket] = history[bucket] || {};
    history[bucket][moodId] = (history[bucket][moodId] || 0) + 1;
    try { localStorage.setItem(MOOD_HISTORY_KEY, JSON.stringify(history)); } catch {}
  }

  function orderedMoodPresets() {
    const bucket = moodTimeBucket();
    const preferred = MOOD_TIME_AFFINITY[bucket] || [];
    const learned = moodHistory()[bucket] || {};
    return AI_MOOD_PRESETS
      .map((mood, i) => {
        const rank = preferred.indexOf(mood.id);
        const score = (rank >= 0 ? (preferred.length - rank) * 2 : 0) + Math.min(6, (learned[mood.id] || 0) * 1.5) - i * 0.01;
        return { mood, score };
      })
      .sort((a, b) => b.score - a.score)
      .map(item => item.mood);
  }

  let moodChipsBucket = moodTimeBucket();
  setInterval(() => {
    if (moodTimeBucket() === moodChipsBucket) return;
    moodChipsBucket = moodTimeBucket();
    renderAiMoodRadioChips("aiMoodRadioChipsLib");
    renderAiMoodRadioChips("aiMoodRadioChipsSearch");
  }, 10 * 60 * 1000);

  function renderAiMoodRadioChips(containerId) {
    const container = $(containerId);
    if (!container) return;
    container.replaceChildren();
    const frag = document.createDocumentFragment();

    orderedMoodPresets().forEach(mood => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ai-mood-chip";
      btn.dataset.moodId = mood.id;
      if (activeMoodRadioId === mood.id) btn.classList.add("active");
      btn.innerHTML = `<span>${mood.icon}</span> <span>${escapeHtml(mood.title.split("·")[1] || mood.title)}</span>`;
      btn.title = mood.quickGuide;
      btn.addEventListener("click", () => {
        launchAiMoodRadio(mood.id);
      });
      frag.appendChild(btn);
    });

    container.appendChild(frag);
  }

  async function launchAiMoodRadio(moodId) {
    const mood = AI_MOOD_PRESETS.find(m => m.id === moodId);
    if (!mood) return;

    activeMoodRadioId = moodId;
    recordMoodLaunch(moodId);
    document.querySelectorAll(".ai-mood-chip").forEach(btn => {
      btn.classList.toggle("active", btn.dataset.moodId === moodId);
    });

    // 1. 本地曲库毫秒级穿透与秒起播 (Zero-Latency Local Penetration)
    const localTracks = await getLocalTracksCache();
    let localMatches = [];
    if (localTracks.length > 0) {
      localMatches = localTracks.filter(t => {
        const text = `${t.title || ""} ${t.artist || ""} ${t.album || ""} ${t.genre || ""}`.toLowerCase();
        return mood.keywords.some(kw => text.includes(kw));
      });
    }

    if (localMatches.length > 0) {
      toast(`🎵 已唤醒本机 ${localMatches.length} 首【${mood.title}】无损音频，正在即刻起播！`);
      playTracks(localMatches, false, 0);
    } else {
      toast(`正在启动【${mood.title}】并全网调度同频发烧母带…`);
    }

    // 2. 切换到 AI 寻歌看板展示发烧导赏与同频唱片
    showView("search");
    document.querySelectorAll("[data-nav]").forEach(b => b.setAttribute("aria-current", String(b.dataset.nav === "viewSearch")));
    document.querySelectorAll("[data-search-src]").forEach(b => b.setAttribute("aria-current", String(b.dataset.searchSrc === "ai")));
    currentSearchSource = "ai";
    if ($("aiInspirationChips")) $("aiInspirationChips").hidden = false;
    if ($("globalSearchInput")) $("globalSearchInput").value = mood.query;

    const rev = ++searchRevision;
    resetSearchLayout();

    if ($("aiCuratorBanner")) {
      if ($("aiCuratorTitle")) $("aiCuratorTitle").textContent = `✨ ${mood.title}`;
      if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = mood.quickGuide;
      if ($("aiCuratorGearFocus")) {
        $("aiCuratorGearFocus").textContent = `🎧 正在沉浸播放本机 ${localMatches.length} 首高保真音频 · ${mood.quickGuide}`;
        $("aiCuratorGearFocus").hidden = false;
      }
      $("aiCuratorBanner").hidden = false;
    }

    // 3. 后台智能补齐与云端 Hi-Res 唱片注入
    searchMessage(`已唤醒机内 ${localMatches.length} 首音轨起播，正在检索全网同频顶级发烧名盘…`);

    try {
      const prompt = `你是一位精通各类音乐母带录音、编曲配器与高保真发烧音响鉴赏的顶级音乐顾问与唱片导赏家。
当前用户正在山灵便携播放器上收听【${mood.title}】心境电台。
心境声学诉求：'${mood.query}'。

【重要专业红线】：
1. 真实存在性：所有推荐必须是主流流媒体（TIDAL / 网易云）真实公开发行的标准真实唱片，绝对不允许虚构任何不存在的专辑名或曲目！
2. 命题切合度：必须完全契合【${mood.title}】的艺术造诣与母带声学特质！
3. 请精选推荐 3-5 张在艺术造诣、器乐编排、母带品质与动态细节上极其契合的高水准唱片（兼顾国际与华语优质制作）。

输出必须严格为标准合法 JSON 格式，绝不允许任何 Markdown 外壳（如 \`\`\`json 标记）：
{
  "theme": "${mood.title}",
  "listening_guide": "结合该心境的专业发烧听音导赏分析（约 100-180 字）...",
  "recommended_gear_focus": "重点品味要点（约 30-60 字）",
  "recommendations": [
    {
      "album": "准确的唱片原名（以流媒体收录名为准）",
      "album_alt": "中文或英文常用别名",
      "artist": "准确的艺人名字",
      "artist_alt": "艺人英文或中文别名",
      "key_track": "代表高光单曲名（真实收录于该唱片中）",
      "genre": "细分发烧流派标签",
      "release_year": "发行年份",
      "acoustic_highlight": "发烧亮点标签",
      "recommendation_reason": "深度推荐理由（约 60-120 字）"
    }
  ]
}`;

      const aiReply = await callAiChat({
        messages: [{ role: "user", content: prompt }],
        temperature: 0.65,
        scene: "mood"
      });
      if (rev !== searchRevision) return;

      const parsed = extractJson(aiReply);
      const recs = parsed?.recommendations || [];

      if (recs.length > 0) {
        if ($("aiCuratorGuide")) $("aiCuratorGuide").textContent = parsed.listening_guide || mood.quickGuide;
        if ($("aiCuratorGearFocus") && parsed.recommended_gear_focus) {
          $("aiCuratorGearFocus").textContent = `🎧 重点品味要点：${parsed.recommended_gear_focus}（机内已起播 ${localMatches.length} 首）`;
          $("aiCuratorGearFocus").hidden = false;
        }

        const matchedCards = await Promise.all(recs.map(resolveRecommendationItem));
        if (rev !== searchRevision) return;

        lastAiRecommendations = matchedCards;
        const downloadableCards = matchedCards.filter(c => c.canDownload && c.rawAlbum);
        selectedAiCardIds = new Set(downloadableCards.map(c => c.id));
        updateAiSelectedCount();

        $("searchCardsTitle").textContent = `云端同频发烧唱片推荐 (${matchedCards.length}) · 支持一键勾选下载至机身`;
        $("searchCardsTitle").hidden = false;
        $("searchCardsGrid").hidden = false;
        $("searchTrackList").hidden = true;

        renderAiRecommendationCards(matchedCards, $("searchCardsGrid"));

        // 将云端匹配的高光单曲追加在播放队列后面，达成连绵不绝的沉浸听音
        const cloudTracks = matchedCards.map(c => c.trackObj).filter(Boolean);
        if (localMatches.length === 0 && cloudTracks.length > 0) {
          playTracks(cloudTracks, false, 0);
        }
      }
    } catch (err) {
      console.warn("Mood background enrich err", err);
      if (localMatches.length > 0) {
        $("searchCardsTitle").textContent = `机内已唤醒无损音频 (${localMatches.length})`;
        $("searchCardsTitle").hidden = false;
      }
    }
  }
  let lastAiRecommendations = [];
  let selectedAiCardIds = new Set();

  function updateAiSelectedCount() {
    const downloadableCount = lastAiRecommendations.filter(c => c.canDownload && c.rawAlbum).length;
    const count = selectedAiCardIds.size;
    if ($("aiSelectedDlCount")) $("aiSelectedDlCount").textContent = String(count);
    const btnDl = $("btnAiDownloadSelected");
    if (btnDl) {
      btnDl.style.opacity = count > 0 ? "1" : "0.5";
      btnDl.style.pointerEvents = count > 0 ? "" : "none";
    }
    const selectBar = $("aiSelectionBar");
    if (selectBar) {
      selectBar.hidden = downloadableCount === 0;
    }
  }
  let aiSearchSession = {
    initialQuery: "",
    turnCount: 0,
    seenAlbums: new Set(),
    initialSnapshot: null
  };

  function renderAiCategoryChips(cat) {
    currentAiInspirationCat = cat;
    const container = $("aiChipsList");
    if (!container) return;
    container.replaceChildren();
    const list = AI_INSPIRATION_DATA[cat] || AI_INSPIRATION_DATA.instrument;
    const frag = document.createDocumentFragment();
    list.forEach(item => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ai-chip";
      btn.textContent = item.title;
      btn.dataset.query = item.query;
      btn.addEventListener("click", () => {
        $("globalSearchInput").value = item.query;
        document.querySelectorAll("[data-search-src]").forEach(b => {
          b.setAttribute("aria-current", String(b.dataset.searchSrc === "ai"));
        });
        currentSearchSource = "ai";
        if ($("aiInspirationChips")) $("aiInspirationChips").hidden = false;
        if ($("searchKindTabs")) $("searchKindTabs").hidden = true;
        updateAiNowPlayingSeed();
        executeGlobalSearch();
      });
      frag.appendChild(btn);
    });
    container.appendChild(frag);

    document.querySelectorAll("[data-ai-cat]").forEach(pill => {
      pill.setAttribute("aria-current", String(pill.dataset.aiCat === cat));
    });
  }

  function updateAiNowPlayingSeed() {
    const pill = $("aiSeedNowPlaying");
    const text = $("aiSeedNowPlayingText");
    if (!pill || !text) return;
    const isAi = currentSearchSource === "ai";
    const cur = (lastState && lastState.queue && lastState.queue[lastState.index]) || null;
    if (isAi && cur && cur.title) {
      text.textContent = `🎵 正在播放：《${cur.title}》- ${cur.artist || "未知"} · 点击深度以歌寻歌`;
      pill.hidden = false;
    } else {
      pill.hidden = true;
    }
  }

