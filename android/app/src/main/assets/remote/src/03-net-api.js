  // --- Network API ---
  async function fetchJson(path, options = {}, timeout = 22000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const headers = { ...options.headers };
    if (key && !headers["Authorization"]) {
      headers["Authorization"] = `Bearer ${key}`;
    }
    try {
      const res = await fetch(path, { ...options, headers, signal: controller.signal });
      const json = await res.json().catch(() => ({}));
      if (res.status === 401) {
        const error = new Error(json.error || "配对链接已过期或未授权");
        error.status = 401;
        requireLanPair(error.message);
        throw error;
      }
      if (!res.ok) {
        const error = new Error(json.error || `请求失败 (HTTP ${res.status})`);
        error.status = res.status;
        throw error;
      }
      return json;
    } finally {
      clearTimeout(timer);
    }
  }

  async function call(op, fields = {}) {
    const headers = { "Content-Type": "application/json" };
    const suffix = central && device ? "?device=" + encodeURIComponent(device) : "";
    let res;
    if (!central) {
      res = await fetchJson("/api/control", { method: "POST", headers, body: JSON.stringify({ op, ...fields }) });
    } else {
      res = await fetchJson("/api/control" + suffix, { method: "POST", headers, body: JSON.stringify({ op, ...fields }) });
    }
    if (res && res.queue && Array.isArray(res.queue)) {
      renderPlayback(res);
    }
    return res;
  }

