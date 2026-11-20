  // --- Polling & Connection Loop ---
  async function pollState() {
    if (pollActive) return;
    pollActive = true;
    try {
      const suffix = central && device ? "?device=" + encodeURIComponent(device) : "";
      const res = await fetchJson("/api/state" + suffix, {}, 8000);
      renderPlayback(res);
      $("pairPanel").hidden = true;
    } catch (err) {
      if (err.status === 401) {
        requireLanPair("配对已失效，请重新输入配对码");
      } else {
        $("status").textContent = "正在重连…";
        $("status").classList.remove("ok");
      }
    } finally {
      pollActive = false;
      setTimeout(pollState, 1800);
    }
  }

  function escapeHtml(str) {
    if (!str) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

