  // =========================================================================
  // AI 底座：服务端代理调用、可用性探测、场景预算、本地缓存与请求去重
  // 密钥只保存在手机（局域网模式）或中转服务器（多端模式），浏览器只发送对话内容。
  // =========================================================================

  const AI_NOTES_PREFIX = "tidal_ai_notes_";
  const AI_GENRES_KEY = "tidal_ai_genres";
  const AI_CACHE_PREFIX = "tidal_ai_c1_";
  const AI_BUDGET_KEY = "tidal_ai_budget";
  // 每个场景每小时最多调用次数，防止自动触发的功能失控消耗额度。
  const AI_BUDGETS = { autodj: 10, bubble: 20, notes: 30, album: 30, search: 20, mood: 20, genre: 80, dlrec: 10, profile: 6, test: 10, default: 30 };

  const PROVIDER_PRESETS = {
    ark: { endpoint: "https://ark.cn-beijing.volces.com/api/v3", model: "deepseek-v4-1-flash-261017" },
    deepseek: { endpoint: "https://api.deepseek.com", model: "deepseek-chat" },
    openai: { endpoint: "https://api.openai.com/v1", model: "gpt-4o-mini" },
    ollama: { endpoint: "", model: "qwen2.5:latest", hint: "http://电脑局域网IP:11434/v1" },
    custom: { endpoint: "", model: "" }
  };

  // 旧版把密钥存在浏览器里，这里清掉。
  try { localStorage.removeItem("tidal_ai_config"); } catch {}

  class AiUnavailableError extends Error {
    constructor(message) {
      super(message);
      this.name = "AiUnavailableError";
    }
  }

  let aiStatus = { loaded: false, configured: false, managed: central, model: "" };
  let aiStatusAt = 0;
  let aiStatusPromise = null;

  async function refreshAiStatus(force = false) {
    if (!force && aiStatus.loaded && Date.now() - aiStatusAt < 60000) return aiStatus;
    if (aiStatusPromise) return aiStatusPromise;
    aiStatusPromise = fetchJson("/api/ai/config", {}, 8000)
      .then(res => {
        aiStatus = { ...res, loaded: true };
        aiStatusAt = Date.now();
        return aiStatus;
      })
      .catch(() => aiStatus)
      .finally(() => { aiStatusPromise = null; });
    return aiStatusPromise;
  }

  // 手机离线（online === false）时，AI 增强功能静默隐藏，不等待超时。
  function aiReady() {
    return aiStatus.loaded && aiStatus.configured && aiStatus.online !== false;
  }

  function aiBudgetTake(scene) {
    const limit = AI_BUDGETS[scene] ?? AI_BUDGETS.default;
    const now = Date.now();
    let book = {};
    try { book = JSON.parse(localStorage.getItem(AI_BUDGET_KEY) || "{}") || {}; } catch {}
    const recent = (Array.isArray(book[scene]) ? book[scene] : []).filter(t => now - t < 3600000);
    if (recent.length >= limit) return false;
    recent.push(now);
    book[scene] = recent;
    try { localStorage.setItem(AI_BUDGET_KEY, JSON.stringify(book)); } catch {}
    return true;
  }

  function extractJson(text) {
    if (!text) return null;
    try { return JSON.parse(text); } catch {}
    const match = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/);
    if (match) {
      try { return JSON.parse(match[1]); } catch {}
    }
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start !== -1 && end > start) {
      try { return JSON.parse(text.slice(start, end + 1)); } catch {}
    }
    return null;
  }

  /**
   * 调用 AI。interactive=true 表示用户主动发起：未配置时打开设置面板；
   * 自动触发的功能传 interactive=false，未配置时静默抛出 AiUnavailableError。
   */
  async function callAiChat({ messages, temperature = 0.7, timeout = 50000, scene = "default", interactive = true, maxTokens }) {
    await refreshAiStatus();
    if (!aiReady()) {
      if (aiStatus.configured && aiStatus.online === false) throw new AiUnavailableError("播放器当前未联网，AI 功能暂不可用");
      if (interactive && !aiStatus.managed) openAiSettingsModal();
      throw new AiUnavailableError(aiStatus.managed ? "服务器未配置 AI 服务" : "请先在「AI 音乐助理设置」中配置服务");
    }
    if (!aiBudgetTake(scene)) throw new AiUnavailableError("AI 调用过于频繁，请稍后再试");
    const body = { messages, temperature };
    if (maxTokens) body.max_tokens = maxTokens;
    try {
      const res = await fetchJson("/api/ai", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      }, timeout);
      const content = typeof res.content === "string" ? res.content.trim() : "";
      if (!content) throw new Error(res.finish === "length" ? "生成达到长度限制" : "AI 返回内容为空");
      return content;
    } catch (err) {
      if (err.name === "AbortError") throw new Error("AI 请求超时，请稍后重试");
      if (err.status === 503) {
        aiStatus = { ...aiStatus, configured: false };
        throw new AiUnavailableError(err.message);
      }
      throw err;
    }
  }

  function aiCacheGet(cacheKey) {
    try {
      const raw = localStorage.getItem(AI_CACHE_PREFIX + cacheKey);
      if (!raw) return undefined;
      const entry = JSON.parse(raw);
      if (entry.e && entry.e < Date.now()) {
        localStorage.removeItem(AI_CACHE_PREFIX + cacheKey);
        return undefined;
      }
      return entry.d;
    } catch {
      return undefined;
    }
  }

  function aiCacheEvict() {
    const entries = [];
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (!k || !k.startsWith(AI_CACHE_PREFIX)) continue;
        let t = 0;
        try { t = JSON.parse(localStorage.getItem(k)).t || 0; } catch {}
        entries.push([k, t]);
      }
      entries.sort((a, b) => a[1] - b[1]);
      entries.slice(0, Math.max(1, Math.ceil(entries.length / 2))).forEach(([k]) => localStorage.removeItem(k));
    } catch {}
  }

  function aiCacheSet(cacheKey, data, ttlDays = 180) {
    const now = Date.now();
    const raw = JSON.stringify({ t: now, e: ttlDays ? now + ttlDays * 86400000 : 0, d: data });
    try {
      localStorage.setItem(AI_CACHE_PREFIX + cacheKey, raw);
    } catch {
      aiCacheEvict();
      try { localStorage.setItem(AI_CACHE_PREFIX + cacheKey, raw); } catch {}
    }
  }

  function aiCacheKey(...parts) {
    return parts.map(p => String(p || "").trim().toLowerCase()).join("|").slice(0, 200);
  }

  const aiInflight = new Map();

  /** 缓存优先：命中缓存直接返回；同一 key 并发只发一次请求；parse 返回 null 视为失败且不缓存。 */
  function aiCached({ cacheKey, ttlDays = 180, parse, refresh = false, ...chat }) {
    if (!refresh) {
      const hit = aiCacheGet(cacheKey);
      if (hit !== undefined) return Promise.resolve(hit);
    }
    if (aiInflight.has(cacheKey)) return aiInflight.get(cacheKey);
    const job = callAiChat(chat)
      .then(text => {
        const data = parse ? parse(text) : text;
        if (data == null) throw new Error("AI 返回格式无效");
        aiCacheSet(cacheKey, data, ttlDays);
        return data;
      })
      .finally(() => aiInflight.delete(cacheKey));
    aiInflight.set(cacheKey, job);
    return job;
  }

  // --- Modal: AI Settings ---
  const aiSettingInputs = ["aiProviderSelect", "aiEndpointInput", "aiApiKeyInput", "aiModelInput"];

  async function openAiSettingsModal() {
    $("aiSettingsModal")?.classList.add("active");
    if ($("aiTestResult")) $("aiTestResult").hidden = true;
    const status = await refreshAiStatus(true);
    const managed = !!status.managed;
    aiSettingInputs.forEach(id => { if ($(id)) $(id).disabled = managed; });
    if ($("btnAiSave")) $("btnAiSave").hidden = managed;
    if ($("btnAiToggleKey")) $("btnAiToggleKey").hidden = managed;
    const lead = $("aiSettingsLead");
    if (lead) {
      lead.textContent = managed
        ? (status.configured ? `AI 服务由中转服务器统一提供（模型：${status.model}），无需在此配置。` : "中转服务器尚未配置 AI 服务，请联系服务器管理员。")
        : "配置大模型服务。密钥只保存在播放器手机上，不会回传到浏览器。";
    }
    if (managed) return;
    if ($("aiProviderSelect")) $("aiProviderSelect").value = status.provider || "ark";
    if ($("aiEndpointInput")) $("aiEndpointInput").value = status.endpoint || PROVIDER_PRESETS.ark.endpoint;
    if ($("aiModelInput")) $("aiModelInput").value = status.model || PROVIDER_PRESETS.ark.model;
    if ($("aiApiKeyInput")) {
      $("aiApiKeyInput").value = "";
      $("aiApiKeyInput").placeholder = status.hasKey ? "已保存密钥（留空保持不变）" : "sk-...";
    }
  }

  function closeAiSettingsModal() {
    $("aiSettingsModal")?.classList.remove("active");
  }

  async function saveAiSettings() {
    const res = await fetchJson("/api/ai/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: $("aiProviderSelect")?.value || "custom",
        endpoint: $("aiEndpointInput")?.value.trim() || "",
        apiKey: $("aiApiKeyInput")?.value.trim() || "",
        model: $("aiModelInput")?.value.trim() || ""
      })
    }, 8000);
    aiStatus = { ...res, loaded: true };
    aiStatusAt = Date.now();
    if ($("aiApiKeyInput")) {
      $("aiApiKeyInput").value = "";
      $("aiApiKeyInput").placeholder = res.hasKey ? "已保存密钥（留空保持不变）" : "sk-...";
    }
    return res;
  }

  $("btnAiSettings")?.addEventListener("click", openAiSettingsModal);
  $("btnAiSettingsClose")?.addEventListener("click", closeAiSettingsModal);
  $("btnAiCancel")?.addEventListener("click", closeAiSettingsModal);
  $("aiSettingsModal")?.addEventListener("click", e => {
    if (e.target === $("aiSettingsModal")) closeAiSettingsModal();
  });

  $("aiProviderSelect")?.addEventListener("change", e => {
    const p = PROVIDER_PRESETS[e.target.value];
    if (p && e.target.value !== "custom") {
      $("aiEndpointInput").value = p.endpoint;
      $("aiEndpointInput").placeholder = p.hint || "https://...";
      $("aiModelInput").value = p.model;
    }
  });

  $("btnAiToggleKey")?.addEventListener("click", () => {
    const inp = $("aiApiKeyInput");
    if (!inp) return;
    const isPw = inp.type === "password";
    inp.type = isPw ? "text" : "password";
    $("btnAiToggleKey").textContent = isPw ? "隐藏" : "显示";
  });

  $("btnAiSave")?.addEventListener("click", async () => {
    try {
      await saveAiSettings();
      toast("AI 音乐助理设置已保存到播放器");
      closeAiSettingsModal();
    } catch (err) {
      toast(`保存失败：${err.message}`, "i-trash");
    }
  });

  $("btnAiTest")?.addEventListener("click", async () => {
    const btn = $("btnAiTest");
    const resEl = $("aiTestResult");
    if (!btn || !resEl) return;
    resEl.hidden = false;
    resEl.style.color = "var(--muted)";
    resEl.textContent = "正在连接并测试 API…";
    btn.disabled = true;
    try {
      if (!aiStatus.managed) await saveAiSettings();
      const start = Date.now();
      const reply = await callAiChat({
        messages: [{ role: "user", content: "请用不超过10个字回答：连接成功" }],
        timeout: 20000,
        scene: "test",
        interactive: false
      });
      resEl.style.color = "var(--accent)";
      resEl.textContent = `✓ 连接成功 (${Date.now() - start}ms): ${reply.slice(0, 30)}`;
    } catch (err) {
      resEl.style.color = "var(--danger)";
      resEl.textContent = `✕ 测试失败: ${err.message}`;
    } finally {
      btn.disabled = false;
    }
  });
