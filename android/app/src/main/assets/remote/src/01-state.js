(() => {
  "use strict";

  const $ = id => document.getElementById(id);

  // --- State & Session ---
  const hashVal = location.hash.slice(1);
  let sharedCode = /^[0-9]{6}$/.test(hashVal) ? hashVal : "";
  let key = (!sharedCode && !hashVal.startsWith("/") && hashVal.length > 10) ? hashVal : "";
  const sessionKey = "tidal-lan-pair";
  const central = document.documentElement.dataset.central === "1";
  let device = "";
  const adminSessionKey = "tidal-central-admin";
  const accountSessionKey = "tidal-player-account";
  let ready = false;
  let lastState = null;
  let pollActive = false;
  let seeking = false;
  let currentVolume = 80;
  let volumeDragging = false;
  let volumeDebounceTimer = null;
  let lastVolumeUserActionTime = 0;
  let activeView = "player";
  let libraryTab = "local";
  let currentLyrics = [];
  let currentLyricsTrackId = null;
  let activeLyricIndex = -1;
  let isLyricsViewFs = false;
  let currentSearchSource = "all";
  let searchDebounceTimer = null;
  let libraryRevision = 0;
  let currentPlaylist = "";
  let currentCollection = null;
  let currentGroup = null;
  let localSubCat = "songs";
  let detailStack = [];
  let currentDetail = null;
  let currentDetailTracks = [];
  let libraryOffset = 0;
  let libraryNext = -1;
  let autoDjEnabled = true; // 以手机上报的状态为准
  let libraryHasMore = false;
  let localPlaylistsCache = [];

  function readLanPair() {
    try {
      return localStorage.getItem(sessionKey) || sessionStorage.getItem(sessionKey) || "";
    } catch { return ""; }
  }
  function saveLanPair(val) {
    try {
      if (val) localStorage.setItem(sessionKey, val);
      else localStorage.removeItem(sessionKey);
      if (val) sessionStorage.setItem(sessionKey, val);
      else sessionStorage.removeItem(sessionKey);
    } catch {}
  }
  if (!key) key = readLanPair();
  if (key && !sharedCode) saveLanPair(key);

  function requireLanPair(message) {
    ready = false;
    $("status").textContent = message || "请先在手机配对";
    $("status").classList.remove("ok");
    $("pairPanel").hidden = false;
    if (sharedCode) $("pairCode").value = sharedCode;
  }

  async function pair(code) {
    if (!code || !/^[0-9]{6}$/.test(code.trim())) {
      toast("请输入 6 位数字配对码", "i-trash");
      return;
    }
    const cleanCode = code.trim();
    const btn = $("pairForm").querySelector("button");
    btn.disabled = true;
    $("pairCode").disabled = true;
    $("status").textContent = "正在验证配对码…";
    try {
      const res = await fetchJson("/api/pair", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: cleanCode })
      }, 8000);
      if (!res.key) throw new Error("配对失败，未收到密钥");
      key = res.key;
      saveLanPair(key);
      sharedCode = "";
      $("pairCode").value = "";
      $("pairPanel").hidden = true;
      toast("设备配对成功！", "i-music");
      pollState();
      loadDownloads();
      applyRouteFromHash("");
    } catch (err) {
      toast(err.message, "i-trash");
      $("status").textContent = err.message;
    } finally {
      btn.disabled = false;
      $("pairCode").disabled = false;
    }
  }

  $("pairForm").addEventListener("submit", e => {
    e.preventDefault();
    pair($("pairCode").value);
  });

  $("btnDevices").addEventListener("click", () => {
    if (central) {
      $("adminPanel").hidden = !$("adminPanel").hidden;
    } else {
      $("pairPanel").hidden = !$("pairPanel").hidden;
      if (!$("pairPanel").hidden) $("pairCode").focus();
    }
  });

  // Account login in central relay mode
  $("accountForm").addEventListener("submit", async e => {
    e.preventDefault();
    const btn = $("accountForm").querySelector("button");
    btn.disabled = true;
    try {
      const res = await fetchJson("/account/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: $("accountName").value.trim(),
          password: $("accountPassword").value
        })
      });
      if (res.key) {
        key = res.key;
        saveLanPair(key);
        $("adminPanel").hidden = true;
        toast("账号登录成功！", "i-music");
        pollState();
      }
    } catch (err) {
      $("adminMessage").textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  });

