"use strict";

const $ = id => document.getElementById(id);
let key = location.hash.slice(1);
const sessionKey = "tidal-lan-pair";
const sharedCode = /^[0-9]{6}$/.test(key) ? key : "";

function readLanPair() {
  try { return localStorage.getItem(sessionKey) || sessionStorage.getItem(sessionKey) || ""; }
  catch (_) { return sessionStorage.getItem(sessionKey) || ""; }
}

function saveLanPair(value) {
  try {
    if (value) localStorage.setItem(sessionKey, value);
    else localStorage.removeItem(sessionKey);
  } catch (_) {
    // Private browsing can restrict persistent storage.
  }
  if (value) sessionStorage.setItem(sessionKey, value);
  else sessionStorage.removeItem(sessionKey);
}

function requireLanPair(message) {
  $("status").classList.remove("ok");
  key = "";
  saveLanPair("");
  filesEnabled = false;
  $("openFiles").hidden = true;
  if (!$("modeSwitch").hidden) {
    setMode(false);
    $("modeSwitch").hidden = true;
  } else if (filesPage) showView("player");
  $("status").textContent = message;
  $("pairPanel").hidden = false;
  if (sharedCode) $("pairCode").value = sharedCode;
}
const validKey = value => /^[A-Za-z0-9_-]{32,128}$/.test(value || "");
const central = document.documentElement.dataset.central === "1";
let device = "";
const adminSessionKey = "tidal-central-admin";
const accountSessionKey = "tidal-player-account";
let accountMode = false;
let ready = false;
let androidSelected = false;
let issuing = false;
let lastQueue;
let poll;
let busy = false;
let seeking = false;
let filesEnabled = false;
let fileBusy = false;
let filesPage = false;
let roots = [];
let directoryItems = [];
let directoryLoading = false;
// Selected files and folders, by kind and name, so a refresh keeps the selection.
let selected = new Set();
let selectionAnchor = null;
let multiSelect = false;
let filesRevision;
let refreshPending = false;
const fileKey = item => (item.folder ? "d:" : "f:") + item.name;
const selectedItems = () => directoryItems.filter(item => selected.has(fileKey(item)));
function clearSelection() {
  selected.clear();
  selectionAnchor = null;
}
let selectedRoot = "";
let directory = "";
let listing = 0;

function showFiles(open) {
  filesPage = open && filesEnabled;
  $("playerView").hidden = filesPage;
  $("filePanel").hidden = !filesPage;
  $("openPlayer").setAttribute("aria-current", String(!filesPage));
  $("openFiles").setAttribute("aria-current", String(filesPage));
  if (filesPage) listFiles();
}

async function requestJson(path, options, timeout) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(path, {...options, signal: controller.signal});
    let json;
    try { json = await response.json(); } catch (_) { json = {}; }
    if (!response.ok) {
      const error = new Error(json.error || "后台请求失败（HTTP " + response.status + "）");
      error.status = response.status;
      throw error;
    }
    return json;
  } finally {
    clearTimeout(timer);
  }
}

function fetchJson(path, options = {}, timeout = 22000) {
  return requestJson(path, {
    ...options,
    headers: {Authorization: "Bearer " + key, ...options.headers}
  }, timeout);
}

function pairingButton() {
  $("issuePair").disabled = !ready || !androidSelected || issuing;
}

function requireAdmin(message) {
  $("status").classList.remove("ok");
  ready = false;
  key = "";
  sessionStorage.removeItem(adminSessionKey);
  sessionStorage.removeItem(accountSessionKey);
  accountMode = false;
  $("status").textContent = "请登录播放器账号";
  $("devicesPanel").hidden = true;
  $("adminPanel").hidden = false;
  $("adminMessage").textContent = message;
  pairingButton();
}

async function call(op, fields = {}) {
  if (central && !op) {
    const {devices} = await fetchJson("/api/devices");
    ready = true;
    $("adminPanel").hidden = true;
    $("devicesPanel").hidden = false;
    sessionStorage.setItem(accountMode ? accountSessionKey : adminSessionKey, key);
    $("issuePair").hidden = accountMode;
    $("removeDevice").hidden = !accountMode;
    $("accountLogout").hidden = !accountMode;
    if (!devices.some(item => item.id === device)) {
      device = devices.find(item => item.online)?.id || devices[0]?.id || "";
    }
    const selection = $("devices");
    const options = devices.map(item => new Option(
      item.name + (item.online ? " · 在线" : " · 离线") + (item.version ? " · " + item.version : ""), item.id
    ));
    if (options.map(option => option.text + option.value).join("|") !== selection.dataset.labels) {
      selection.replaceChildren(...options);
      selection.dataset.labels = options.map(option => option.text + option.value).join("|");
    }
    selection.value = device;
    androidSelected = devices.some(item => item.id === device && item.platform === "android");
    if (accountMode) {
      $("removeDevice").disabled = !device;
      $("issuedCode").textContent = devices.length ? "" : "PC 或手机登录此账号后会出现在这里。";
    } else if (!androidSelected) {
      $("issuedCode").textContent = "请选择 Android 设备以生成配对码。";
    }
    pairingButton();
    if (!device) throw new Error("暂无设备");
  }
  const suffix = central ? "?device=" + encodeURIComponent(device) : "";
  if (!op) return fetchJson("/api/state" + suffix);
  const result = await fetchJson("/api/control" + suffix, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({op, ...fields})
  }, op === "library-scan" ? 120000 : 22000);
  if (!central) return result;
  // wait=1 lets the relay hold the request until the device answers; pause only when it did not.
  let held = false;
  for (let i = 0; i < 50; i++) {
    if (!held) await new Promise(resolve => setTimeout(resolve, 750));
    const receipt = await fetchJson("/api/receipt" + suffix + "&wait=1&command=" + encodeURIComponent(result.command));
    held = receipt.held === true;
    if (receipt.pending) continue;
    if (receipt.result.error) throw new Error(receipt.result.error);
    return receipt.result;
  }
  throw new Error("设备未及时响应");
}

const format = ms => {
  const seconds = Math.floor(ms / 1000);
  return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
};

function button(label, run, disabled = false) {
  const control = document.createElement("button");
  control.textContent = label;
  control.disabled = disabled;
  control.type = "button";
  control.addEventListener("click", run);
  return control;
}

const svgNS = "http://www.w3.org/2000/svg";

function icon(name) {
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(svgNS, "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}

function iconButton(name, label, run, disabled = false, reveal = true) {
  const control = button("", run, disabled);
  control.className = "icon-btn" + (reveal ? " reveal" : "");
  control.title = label;
  control.setAttribute("aria-label", label);
  control.append(icon(name));
  return control;
}

const tidalImage = /^https:\/\/resources\.tidal\.com\/images\/[a-f0-9/]+\/\d+x\d+\.jpg$/i;
function coverUrl(cover, size) {
  return tidalImage.test(cover || "") ? cover.replace(/\/\d+x\d+\.jpg$/i, `/${size}x${size}.jpg`) : "";
}

function hueOf(text) {
  let hash = 0;
  for (const char of text || "") hash = (hash * 31 + char.codePointAt(0)) | 0;
  return Math.abs(hash * 37) % 360;
}

function letterOf(text) {
  const name = (text || "").trim().replace(/\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|wma)$/i, "");
  return name ? [...name][0].toUpperCase() : "♪";
}

function thumb(name, cover, size = 160) {
  const box = document.createElement("span");
  box.className = "thumb";
  box.dataset.letter = letterOf(name);
  box.style.setProperty("--hue", hueOf(name));
  const src = coverUrl(cover, size);
  if (src) {
    const image = new Image();
    image.alt = "";
    image.loading = "lazy";
    image.decoding = "async";
    image.addEventListener("error", () => image.remove());
    image.src = src;
    box.append(image);
  }
  return box;
}

function displayTitle(track) {
  return track.source === "本地音乐"
    ? (track.title || "").replace(/\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|wma)$/i, "") || track.title
    : track.title;
}

function trackRow(track, active = false) {
  const row = document.createElement("li");
  row.classList.toggle("current", active);
  const art = thumb(track.album || track.title, track.cover);
  if (active) {
    const bars = document.createElement("span");
    bars.className = "eq";
    bars.append(document.createElement("i"), document.createElement("i"), document.createElement("i"));
    art.append(bars);
  }
  const detail = document.createElement("div");
  detail.className = "info";
  const title = document.createElement("strong");
  const subtitle = document.createElement("small");
  title.textContent = displayTitle(track);
  title.title = track.title;
  subtitle.textContent = [track.artist, track.album].filter(Boolean).join(" · ") || track.source || "";
  detail.append(title, subtitle);
  const actions = document.createElement("div");
  actions.className = "actions";
  row.append(art, detail, actions);
  row.actions = actions;
  return row;
}

// TIDAL's names for its levels, the same on every client.
const qualityNames = {HI_RES_LOSSLESS: "Max", HI_RES: "Max", LOSSLESS: "High", HIGH: "Low", LOW: "Low 96k"};

function render(state) {
  filesChanged(state.filesRevision);
  const allowFiles = !central && state.remoteFiles === true;
  filesEnabled = allowFiles;
  $("openFiles").hidden = !allowFiles;
  if (!allowFiles && filesPage) showView("player");
  renderMode(state);
  $("status").textContent = state.loggedIn ? "已连接 · TIDAL 已登录" : "已连接 · 等待 TIDAL 登录";
  $("status").classList.add("ok");
  $("authorization").hidden = central || (state.loggedIn && !state.login && !state.loginPending);
  $("login").disabled = state.loginPending || !!state.login;
  $("cancel").hidden = !state.loginPending;
  $("loginDetails").hidden = !state.login;
  if (state.login) {
    $("loginLink").href = state.login.url;
    $("code").textContent = state.login.code;
    $("loginPage").textContent = "也可打开 " + state.login.page + " 并输入授权码";
  }
  const current = state.queue[state.index];
  $("title").textContent = current ? displayTitle(current) : "尚未播放";
  $("artist").textContent = [current?.artist, current?.album].filter(Boolean).join(" · ");
  // Local files show their path on the device; the tooltip keeps the full path.
  const path = current?.source === "本地音乐" ? state.sourcePath || "" : "";
  $("sourceName").textContent = current?.source || "正在播放";
  $("sourceName").title = path ? "文件路径：" + path : "";
  const quality = qualityNames[state.actualQuality] || "";
  const chip = $("qualityChip");
  chip.hidden = !current || (!quality && !state.resolution);
  chip.textContent = [quality, state.resolution].filter(Boolean).join(" · ");
  chip.classList.toggle("hires", quality === "Max");
  document.title = current ? displayTitle(current) + (current.artist ? " · " + current.artist : "") + " — 听音室" : "听音室 · 遥控";
  const artwork = $("artwork");
  const name = current ? current.album || current.title : "";
  artwork.dataset.letter = current ? letterOf(name) : "♪";
  artwork.style.setProperty("--hue", hueOf(name));
  const cover = $("cover");
  const src = coverUrl(current?.cover, 640);
  if (src !== cover.dataset.source) {
    cover.dataset.source = src;
    cover.hidden = true;
    if (src) cover.src = src;
    else cover.removeAttribute("src");
    $("backdrop").style.backgroundImage = src ? `url("${src}")` : "";
  }
  if (!seeking) {
    $("position").max = Math.max(1, state.duration);
    $("position").value = state.position;
    updateTiming(state.position, state.duration);
  }
  $("position").disabled = !state.seekable;
  const control = name => document.querySelector(`[data-op="${name}"]`);
  $("toggleIcon").setAttribute("href", state.playing ? "#i-pause" : "#i-play");
  control("toggle").setAttribute("aria-label", state.playing ? "暂停" : "播放");
  control("shuffle").classList.toggle("on", !!state.shuffle);
  control("shuffle").setAttribute("aria-pressed", String(!!state.shuffle));
  const repeatLabel = ["关闭循环", "队列循环", "单曲循环"][state.repeat] || "循环";
  control("repeat").classList.toggle("on", state.repeat > 0);
  control("repeat").title = repeatLabel;
  control("repeat").setAttribute("aria-label", repeatLabel);
  $("repeatBadge").textContent = state.repeat === 2 ? "1" : "";
  for (const row of $("queue").children) row.classList.toggle("playing", !!state.playing);
  $("error").textContent = state.loginError || state.error || (state.loading ? "正在获取音频…" : "");
  const queueVersion = device + ":" + state.revision + ":" + state.index;
  if (queueVersion === lastQueue) return;
  lastQueue = queueVersion;
  $("queue").replaceChildren();
  $("queueCount").textContent = state.queue.length
    ? `${state.queue.length} 首 · 正在播放第 ${state.index + 1} 首` : "";
  if (!state.queue.length) $("queue").append(emptyRow("队列为空 · 在「搜索」里添加歌曲"));
  let currentRow;
  state.queue.forEach((track, index) => {
    const active = index === state.index;
    const row = trackRow(track, active);
    row.classList.toggle("playing", active && !!state.playing);
    const data = {revision: state.revision, index};
    row.addEventListener("dblclick", () => send("play", data));
    if (!active) row.actions.append(iconButton("play", "播放", () => send("play", data)));
    row.actions.append(iconButton("up", "上移", () => send("move", {...data, to: index - 1}), index === 0),
      iconButton("down", "下移", () => send("move", {...data, to: index + 1}), index + 1 === state.queue.length),
      iconButton("trash", "从队列移除", () => send("remove", data), active));
    $("queue").append(row);
    if (active) currentRow = row;
  });
  if (currentRow && queueFollow) {
    const list = $("queue");
    queueScrollByUser = false;
    list.scrollTop = Math.max(0, currentRow.offsetTop - list.offsetTop - 60);
    requestAnimationFrame(() => { queueScrollByUser = true; });
  }
}

let queueFollow = true;
let queueTimer;
let queueScrollByUser = true;
function updateTiming(position, duration) {
  $("elapsed").textContent = format(position);
  $("total").textContent = format(duration);
  $("timing").textContent = format(position) + " / " + format(duration);
  const pct = duration > 0 ? Math.min(100, position / duration * 100) : 0;
  $("position").style.setProperty("--pct", pct + "%");
}

async function fileCommand(op, fields = {}) {
  return fetchJson("/api/control", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({op, ...fields})
  });
}

function emptyRow(message) {
  const row = document.createElement("li");
  row.className = "empty";
  row.textContent = message;
  return row;
}

function openDirectory(root, path) {
  if (fileBusy) return;
  if (selectedRoot === root && directory === path) return;
  selectedRoot = root;
  directory = path;
  clearSelection();
  directoryItems = [];
  loadDirectory();
}

function renderFiles() {
  const root = roots.find(item => item.id === selectedRoot);
  $("roots").replaceChildren(...roots.map(item => {
    const control = button(item.name.split("/").at(-1), () => openDirectory(item.id, ""));
    control.title = item.name;
    control.setAttribute("aria-current", String(item.id === selectedRoot));
    return control;
  }));
  for (const id of ["chooseFiles", "chooseFolder"]) $(id).disabled = !root || fileBusy || directoryLoading;
  $("parentFolder").disabled = !directory || fileBusy;
  $("breadcrumbs").replaceChildren();
  $("files").replaceChildren();
  if (!root) {
    $("files").append(emptyRow("请先在手机授权一个音乐文件夹"));
    updateFileSelection();
    return;
  }
  const parts = directory ? directory.split("/") : [];
  [root.name.split("/").at(-1), ...parts].forEach((part, index) => {
    if (index) $("breadcrumbs").append("›");
    const control = button(part, () => openDirectory(root.id, parts.slice(0, index).join("/")));
    if (index === parts.length) control.setAttribute("aria-current", "location");
    $("breadcrumbs").append(control);
  });
  for (const item of directoryItems) {
    const row = document.createElement("li");
    const tile = button("", event => selectFile(item, event));
    tile.className = "file-tile";
    const icon = document.createElement("span");
    icon.className = "tile-icon " + (item.folder ? "tile-folder" : "tile-audio");
    icon.setAttribute("aria-hidden", "true");
    if (!item.folder) icon.textContent = item.attachment ? "▤" : "♫";
    const name = document.createElement("span");
    name.className = "tile-name";
    name.textContent = item.name;
    tile.title = item.name;
    tile.append(icon, name);
    tile.addEventListener("dblclick", () => activateFile(item));
    tile.addEventListener("keydown", event => {
      if (event.key === "Enter") {
        event.preventDefault();
        activateFile(item);
      }
    });
    item.tile = tile;
    row.append(tile);
    $("files").append(row);
  }
  if (!$("files").children.length) {
    $("files").append(emptyRow(directoryLoading ? "正在读取目录…" : "此目录没有文件夹或已入库音频"));
  }
  updateFileSelection();
}

// Click selects one item; Ctrl/⌘-click or 多选 toggles; Shift-click selects a range.
function selectFile(item, event) {
  if (fileBusy) return;
  const key = fileKey(item);
  const adding = multiSelect || event?.ctrlKey || event?.metaKey;
  if (event?.shiftKey && selectionAnchor) {
    const from = directoryItems.findIndex(entry => fileKey(entry) === selectionAnchor);
    const to = directoryItems.indexOf(item);
    if (from >= 0 && to >= 0) {
      if (!adding) selected.clear();
      for (let i = Math.min(from, to); i <= Math.max(from, to); i++) selected.add(fileKey(directoryItems[i]));
      updateFileSelection();
      return;
    }
  }
  if (adding) {
    if (selected.has(key)) selected.delete(key);
    else selected.add(key);
  } else {
    selected.clear();
    selected.add(key);
  }
  selectionAnchor = key;
  updateFileSelection();
}

function selectAllFiles() {
  if (fileBusy) return;
  if (selected.size && selected.size === directoryItems.length) clearSelection();
  else selected = new Set(directoryItems.map(fileKey));
  updateFileSelection();
}

function updateFileSelection() {
  const items = selectedItems();
  const one = items.length === 1 ? items[0] : null;
  for (const item of directoryItems) item.tile?.setAttribute("aria-pressed", String(selected.has(fileKey(item))));
  $("fileOpen").disabled = !one?.folder || fileBusy;
  $("fileDownload").disabled = !items.length || items.some(item => item.folder) || fileBusy;
  $("fileRename").disabled = !one || one.folder || !!one.attachment || fileBusy;
  $("fileDelete").disabled = !items.length || fileBusy;
  $("fileAll").disabled = !directoryItems.length || fileBusy;
  $("fileAll").textContent = items.length && items.length === directoryItems.length ? "取消全选" : "全选";
  $("fileMulti").setAttribute("aria-pressed", String(multiSelect));
  const folders = items.filter(item => item.folder).length;
  $("fileSelection").textContent = one ? one.name : items.length
    ? "已选 " + items.length + " 项" + (folders ? "（" + folders + " 个文件夹，" + (items.length - folders) + " 个文件）" : "")
    : "";
}

function itemPath(item) {
  return [directory, item.name].filter(Boolean).join("/");
}

function activateFile(item) {
  if (!item || fileBusy) return;
  if (item.folder) openDirectory(selectedRoot, itemPath(item));
  else downloadFile(item);
}

function deleteCommand(item) {
  if (item.folder) return ["file-delete-directory", {root: selectedRoot, path: itemPath(item), directoryId: item.id, confirmed: true}];
  if (item.attachment) return ["file-delete-attachment", {root: selectedRoot, path: directory, name: item.name, confirmed: true}];
  return ["file-delete", {id: item.id, confirmed: true}];
}

function deleteSelected() {
  const items = selectedItems();
  if (!items.length || fileBusy) return;
  const folders = items.filter(item => item.folder).length;
  const question = items.length > 1
    ? "永久删除手机上的 " + items.length + " 项" + (folders ? "（含 " + folders + " 个文件夹及其中的所有文件）" : "") + "？"
    : items[0].folder ? "永久删除手机上的文件夹「" + items[0].name + "」及其中的所有文件？"
      : "永久删除手机上的文件「" + items[0].name + "」？";
  if (!window.confirm(question)) return;
  fileTask(async () => {
    let failed = 0;
    let reason = "";
    for (const item of items) {
      try {
        await fileCommand(...deleteCommand(item));
      } catch (error) {
        failed++;
        reason = error.message;
      }
    }
    await listFiles();
    if (failed) return "已删除 " + (items.length - failed) + " 项，" + failed + " 项失败：" + reason;
    return items.length > 1 ? "已删除 " + items.length + " 项" : folders ? "已删除文件夹" : "已删除文件";
  });
}

// quiet: keep the listing and selection on screen while reloading, e.g. after a download.
async function loadDirectory(quiet = false) {
  const sequence = ++listing;
  if (!quiet) {
    directoryLoading = true;
    directoryItems = [];
    clearSelection();
    renderFiles();
  }
  try {
    if (!selectedRoot) return;
    let result = await fileCommand("file-directory", {root: selectedRoot, path: directory});
    if (sequence !== listing) return;
    if (result.missing && directory) {
      directory = "";
      result = await fileCommand("file-directory", {root: selectedRoot, path: ""});
    }
    if (sequence !== listing) return;
    const compare = (a, b) => a.name.localeCompare(b.name, "zh-CN", {numeric: true});
    directoryItems = [
      ...result.directories.sort(compare).map(item => ({...item, folder: true})),
      ...result.files.sort(compare)
    ];
    const present = new Set(directoryItems.map(fileKey));
    selected = new Set([...selected].filter(key => present.has(key)));
  } catch (error) {
    if (sequence === listing) $("fileMessage").textContent = error.message;
  } finally {
    if (sequence === listing) {
      directoryLoading = false;
      renderFiles();
    }
  }
}

async function listFiles(quiet = false) {
  const sequence = ++listing;
  try {
    const result = await fileCommand("files", {rootsOnly: true});
    if (sequence !== listing) return;
    roots = result.folders;
    if (!roots.some(item => item.id === selectedRoot)) {
      selectedRoot = roots[0]?.id || "";
      directory = "";
      quiet = false;
    }
    await loadDirectory(quiet);
  } catch (error) {
    if (sequence === listing) $("fileMessage").textContent = error.message;
  }
}

async function fileTask(work, success) {
  if (fileBusy) return;
  fileBusy = true;
  updateFileSelection();
  for (const id of ["chooseFiles", "chooseFolder", "refreshFiles"]) $(id).disabled = true;
  $("fileMessage").textContent = "正在处理…";
  try {
    const detail = await work();
    $("fileMessage").textContent = detail ?? success;
  } catch (error) {
    $("fileMessage").textContent = error.message;
  } finally {
    fileBusy = false;
    $("refreshFiles").disabled = false;
    renderFiles();
    if (refreshPending) {
      refreshPending = false;
      listFiles(true);
    }
  }
}

// The phone reports filesRevision; it changes when downloads or the library add or remove files.
function filesChanged(revision) {
  if (revision === undefined) return;
  const changed = filesRevision !== undefined && revision !== filesRevision;
  filesRevision = revision;
  if (!changed || !filesPage) return;
  if (fileBusy) refreshPending = true;
  else listFiles(true);
}

function changeFile(op, fields, success) {
  return fileTask(async () => { await fileCommand(op, fields); await listFiles(); }, success);
}

function downloadFile(file) {
  return fileTask(() => fetchFile(file), "文件已下载");
}

function downloadSelected() {
  const files = selectedItems().filter(item => !item.folder);
  if (!files.length) return;
  if (files.length === 1) return downloadFile(files[0]);
  return fileTask(async () => {
    for (const [index, file] of files.entries()) {
      $("fileMessage").textContent = "正在下载 " + (index + 1) + "/" + files.length + " · " + file.name;
      await fetchFile(file);
    }
    return "已下载 " + files.length + " 个文件";
  });
}

async function fetchFile(file) {
  {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 120000);
    try {
      const options = {headers: {Authorization: "Bearer " + key}, signal: controller.signal};
      let path = "/api/file/" + encodeURIComponent(file.id);
      if (file.attachment) {
        path = "/api/attachment";
        options.method = "POST";
        options.headers["Content-Type"] = "application/json";
        options.body = JSON.stringify({root: selectedRoot, path: directory, name: file.name});
      }
      const response = await fetch(path, options);
      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.error || "下载失败");
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = file.name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 30000);
    } finally {
      clearTimeout(timer);
    }
  }
}

$("openPlayer").addEventListener("click", () => showView("player"));
$("openFiles").addEventListener("click", () => showView("files"));
$("cover").addEventListener("load", () => { $("cover").hidden = false; });
$("cover").addEventListener("error", () => { $("cover").hidden = true; });
$("refreshFiles").addEventListener("click", listFiles);
$("parentFolder").addEventListener("click", () => openDirectory(selectedRoot, directory.split("/").slice(0, -1).join("/")));
$("fileOpen").addEventListener("click", () => activateFile(selectedItems()[0]));
$("fileDownload").addEventListener("click", downloadSelected);
$("fileAll").addEventListener("click", selectAllFiles);
$("fileMulti").addEventListener("click", () => {
  multiSelect = !multiSelect;
  updateFileSelection();
});
$("fileRename").addEventListener("click", () => {
  const items = selectedItems();
  const item = items.length === 1 ? items[0] : null;
  if (!item || item.folder || fileBusy) return;
  const name = window.prompt("新的文件名（含扩展名）", item.name);
  if (name && name !== item.name) changeFile("file-rename", {id: item.id, name}, "已重命名");
});
$("fileDelete").addEventListener("click", deleteSelected);
$("files").addEventListener("keydown", event => {
  if (event.key === "Delete" && selected.size) {
    event.preventDefault();
    deleteSelected();
  } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "a") {
    event.preventDefault();
    selected = new Set(directoryItems.map(fileKey));
    updateFileSelection();
  } else if (event.key === "Escape" && selected.size) {
    clearSelection();
    updateFileSelection();
  }
});

function chooseConflict(path) {
  const dialog = $("conflictDialog");
  $("conflictPath").textContent = path;
  $("conflictAll").checked = false;
  dialog.returnValue = "cancel";
  return new Promise(resolve => {
    dialog.addEventListener("close", () => resolve({
      policy: dialog.returnValue,
      all: $("conflictAll").checked
    }), {once: true});
    dialog.showModal();
  });
}

function uploadSelection(selection) {
  if (!selection.length || !selectedRoot) return;
  const supported = selection.filter(({file}) => /\.(aac|aif|aiff|alac|ape|flac|m4a|mp3|oga|ogg|opus|wav|wma|wv|jpg|jpeg|png|webp|gif|lrc)$/i.test(file.name));
  const skipped = selection.length - supported.length;
  const root = selectedRoot;
  const destination = directory;
  fileTask(async () => {
    const failures = [];
    let policyForBatch;
    let uploaded = 0;
    let replaced = 0;
    let duplicates = 0;
    let cancelled = false;
    for (const [index, {file, relative}] of supported.entries()) {
      const parent = relative.slice(0, Math.max(0, relative.lastIndexOf("/")));
      const path = [destination, parent].filter(Boolean).join("/");
      $("fileMessage").textContent = `正在上传 ${index + 1} / ${supported.length} · ${relative}`;
      try {
        const limit = /\.lrc$/i.test(file.name) ? 2 : /\.(jpg|jpeg|png|webp|gif)$/i.test(file.name) ? 20 : 200;
        if (!file.size || file.size > limit * 1024 * 1024) throw new Error("文件为空或超过 " + limit + " MB");
        let policy = policyForBatch || "prompt";
        const target = {root, path, name: file.name};
        const check = await fileCommand("file-upload-check", target);
        const decide = async () => {
          const choice = await chooseConflict([destination, relative].filter(Boolean).join("/"));
          policy = choice.policy;
          if (choice.all && policy !== "cancel") policyForBatch = policy;
        };
        if (check.conflict && policy === "prompt") await decide();
        let status;
        while (policy !== "cancel") {
          if (policy === "skip" && check.conflict) {
            status = "skipped";
            break;
          }
          const result = await requestJson("/api/upload", {
            method: "POST",
            headers: {Authorization: "Bearer " + key, "Content-Type": "application/octet-stream",
              "X-File-Name": encodeURIComponent(file.name), "X-Upload-Folder": encodeURIComponent(root),
              "X-Upload-Directory": encodeURIComponent(path), "X-Upload-Conflict": policy},
            body: file
          }, 300000);
          status = result.status;
          if (status !== "conflict") break;
          check.conflict = true;
          await decide();
        }
        if (policy === "cancel") {
          cancelled = true;
          break;
        }
        if (status === "skipped") duplicates++;
        else if (status === "replaced") replaced++;
        else if (status === "uploaded") uploaded++;
        else throw new Error("设备返回了未知的上传状态");
      } catch (error) {
        failures.push(relative + " · " + (error.name === "AbortError" ? "上传超时" : error.message));
      }
    }
    await listFiles();
    const summary = [`已上传 ${uploaded} 个文件`];
    if (replaced) summary.push(`替换 ${replaced} 个同名文件`);
    if (duplicates) summary.push(`跳过 ${duplicates} 个同名文件`);
    if (cancelled) summary.push("已取消剩余上传");
    if (skipped) summary.push(`跳过 ${skipped} 个不支持的文件`);
    if (failures.length) summary.push(`${failures.length} 个失败`, ...failures);
    return summary.join("\n");
  });
}

const queuedFolders = [];
const subfolders = [];
let readingDrop = false;
let folderBatchGeneration = 0;
const folderBatch = $("folderBatchDialog");

function renderFolderBatch() {
  $("folderBatchList").replaceChildren(...queuedFolders.map((folder, index) => {
    const row = document.createElement("li");
    const label = document.createElement("span");
    label.textContent = folder.name + " · " + folder.files.length + " 个文件";
    row.append(label, button("移除", () => {
      queuedFolders.splice(index, 1);
      renderFolderBatch();
    }));
    return row;
  }));
  updateFolderBatchControls();
}

function updateFolderBatchControls() {
  const selected = subfolders.filter(folder => folder.selected).length;
  const all = $("uploadSelectAll");
  all.checked = subfolders.length > 0 && selected === subfolders.length;
  all.indeterminate = selected > 0 && selected < subfolders.length;
  $("startFolderBatch").disabled = (!queuedFolders.length && !selected) || readingDrop;
  const disablePicker = readingDrop || !("webkitdirectory" in $("audioFolder"));
  $("addUploadFolder").disabled = disablePicker;
  $("pickUploadParent").disabled = disablePicker;
}

function renderSubfolders() {
  $("uploadSubfolders").hidden = !subfolders.length;
  $("uploadSubfolderList").replaceChildren(...subfolders.map(folder => {
    const row = document.createElement("label");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = folder.selected;
    checkbox.addEventListener("change", () => {
      folder.selected = checkbox.checked;
      updateFolderBatchControls();
    });
    row.append(checkbox, " " + folder.name);
    return row;
  }));
  updateFolderBatchControls();
}

function pickFiles(input) {
  const files = Array.from(input.files, file => ({file, relative: file.webkitRelativePath || file.name}));
  input.value = "";
  return files;
}

$("chooseFiles").addEventListener("click", () => $("audioFile").click());
$("audioFile").addEventListener("change", () => uploadSelection(pickFiles($("audioFile"))));
$("chooseFolder").addEventListener("click", () => {
  folderBatchGeneration++;
  readingDrop = false;
  queuedFolders.length = 0;
  subfolders.length = 0;
  $("folderBatchStatus").textContent = "";
  renderSubfolders();
  renderFolderBatch();
  folderBatch.showModal();
});
$("pickUploadParent").addEventListener("click", () => $("parentUploadInput").click());
$("parentUploadInput").addEventListener("change", () => {
  const entries = pickFiles($("parentUploadInput"));
  if (!entries.length) return;
  const folders = new Map();
  for (const {file, relative} of entries) {
    const parts = relative.split("/");
    if (parts.length < 3) continue;
    const name = parts[1];
    if (!folders.has(name)) folders.set(name, {name, files: [], selected: false});
    folders.get(name).files.push({file, relative: parts.slice(1).join("/")});
  }
  subfolders.splice(0, subfolders.length, ...folders.values());
  subfolders.sort((a, b) => a.name.localeCompare(b.name, "zh-CN", {numeric: true}));
  $("uploadParentName").textContent = entries[0].relative.split("/")[0];
  $("folderBatchStatus").textContent = folders.size ? "" : "该目录中没有包含文件的子目录";
  renderSubfolders();
});
$("uploadSelectAll").addEventListener("change", event => {
  for (const folder of subfolders) folder.selected = event.target.checked;
  renderSubfolders();
  $("uploadSelectAll").focus();
});
$("addUploadFolder").addEventListener("click", () => $("audioFolder").click());
$("audioFolder").addEventListener("change", () => {
  const groups = new Map();
  for (const entry of pickFiles($("audioFolder"))) {
    const name = entry.relative.split("/")[0];
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(entry);
  }
  for (const [name, files] of groups) queuedFolders.push({name, files});
  renderFolderBatch();
});
$("cancelFolderBatch").addEventListener("click", () => folderBatch.close());
folderBatch.addEventListener("close", () => {
  folderBatchGeneration++;
  queuedFolders.length = 0;
});
$("startFolderBatch").addEventListener("click", () => {
  const files = [...queuedFolders, ...subfolders.filter(folder => folder.selected)].flatMap(folder => folder.files);
  folderBatch.close();
  uploadSelection(files);
});

async function readDroppedFolder(directory, parent = "") {
  const prefix = parent + directory.name + "/";
  const files = [];
  const reader = directory.createReader();
  while (true) {
    const children = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    if (!children.length) return files;
    for (const child of children) {
      if (child.isDirectory) files.push(...await readDroppedFolder(child, prefix));
      else if (child.isFile) {
        const file = await new Promise((resolve, reject) => child.file(resolve, reject));
        files.push({file, relative: prefix + file.name});
      }
    }
  }
}

folderBatch.addEventListener("dragover", event => {
  event.preventDefault();
  $("folderDrop").classList.add("dragging");
});
folderBatch.addEventListener("dragleave", event => {
  if (!folderBatch.contains(event.relatedTarget)) $("folderDrop").classList.remove("dragging");
});
folderBatch.addEventListener("drop", async event => {
  event.preventDefault();
  $("folderDrop").classList.remove("dragging");
  if (readingDrop) return;
  const directories = Array.from(event.dataTransfer.items, item => item.webkitGetAsEntry?.()).filter(item => item?.isDirectory);
  if (!directories.length) {
    $("folderBatchStatus").textContent = "请拖入文件夹，或点击「添加文件夹」";
    return;
  }
  const generation = folderBatchGeneration;
  readingDrop = true;
  renderFolderBatch();
  $("folderBatchStatus").textContent = "正在读取文件夹…";
  let failed = 0;
  for (const entry of directories) {
    try {
      const files = await readDroppedFolder(entry);
      if (generation !== folderBatchGeneration) return;
      if (files.length) queuedFolders.push({name: entry.name, files});
    } catch (_) {
      failed++;
    }
  }
  if (generation !== folderBatchGeneration) return;
  readingDrop = false;
  $("folderBatchStatus").textContent = failed ? failed + " 个文件夹读取失败" : "";
  renderFolderBatch();
});

let queuedCommand;
async function send(op, fields) {
  if (!central && !validKey(key)) return;
  if (busy) {
    // A state poll or another command is in flight: run this user action right after it.
    if (op) queuedCommand = [op, fields];
    return;
  }
  busy = true;
  if (op === "search") $("results").replaceChildren(emptyRow("正在搜索…"));
  if (op && op !== "search") document.body.classList.add("sending");
  clearTimeout(poll);
  let nextCheck = 1200;
  try {
    const result = await call(op, fields);
    if (op === "search") {
      $("results").replaceChildren();
      for (const track of result.tracks) {
        const row = trackRow(track);
        row.addEventListener("dblclick", () => send("add", {id: track.id, next: true}));
        row.actions.append(iconButton("next-up", "下一首播放", () => send("add", {id: track.id, next: true})),
          iconButton("plus", "加入队列末尾", () => send("add", {id: track.id})));
        $("results").append(row);
      }
      if (!result.tracks.length) $("results").append(emptyRow("没有找到相关歌曲"));
    } else render(result);
  } catch (error) {
    $("error").textContent = error.message;
    if (!op) $("status").classList.remove("ok");
    if (central && error.status === 401) requireAdmin("登录已失效，请重新登录。");
    else if (!central && error.status === 401) requireLanPair("请用手机上当前的认证码重新配对");
    else if (!op) $("status").textContent = error.status === 409
      ? "所选设备离线" : "暂无可用设备";
    if (central && !ready) $("adminMessage").textContent = error.message;
    nextCheck = 3200;
  } finally {
    busy = false;
    document.body.classList.remove("sending");
    const next = queuedCommand;
    queuedCommand = undefined;
    if (next) send(...next);
    else if (validKey(key)) poll = setTimeout(() => send(), nextCheck);
  }
}

document.querySelectorAll("[data-op]").forEach(node => node.addEventListener("click", () => send(node.dataset.op)));
$("login").addEventListener("click", () => send("login"));
$("cancel").addEventListener("click", () => send("cancel-login"));
$("position").addEventListener("input", () => {
  seeking = true;
  updateTiming(Number($("position").value), Number($("position").max));
});

function showSideTab(search) {
  $("queuePane").hidden = search;
  $("searchPane").hidden = !search;
  $("tabQueue").setAttribute("aria-current", String(!search));
  $("tabSearch").setAttribute("aria-current", String(search));
  if (search) $("query").focus();
}
$("tabQueue").addEventListener("click", () => showSideTab(false));
$("tabSearch").addEventListener("click", () => showSideTab(true));
$("queue").addEventListener("scroll", () => {
  if (!queueScrollByUser) return;
  clearTimeout(queueTimer);
  queueFollow = false;
  queueTimer = setTimeout(() => { queueFollow = true; }, 15000);
}, {passive: true});

document.addEventListener("keydown", event => {
  const target = event.target;
  if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
  if (target.closest?.("input,select,textarea,dialog,[contenteditable]") || view !== "player" || !lastState) return;
  if (event.key === " " && target.tagName !== "BUTTON") {
    event.preventDefault();
    send("toggle");
  } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
    event.preventDefault();
    const forward = event.key === "ArrowRight";
    if (event.shiftKey) send(forward ? "next" : "previous");
    else if (lastState.seekable) {
      const ms = Math.max(0, Math.min(lastState.duration, lastState.position + (forward ? 10000 : -10000)));
      send("seek", {ms});
    }
  } else if (event.key === "/") {
    event.preventDefault();
    showSideTab(true);
  }
});
$("position").addEventListener("change", () => {
  seeking = false;
  send("seek", {ms: Number($("position").value)});
});
$("search").addEventListener("submit", event => {
  event.preventDefault();
  send("search", {query: $("query").value});
});

$("devices").addEventListener("change", event => {
  if (busy) {
    event.target.value = device;
    return;
  }
  device = event.target.value;
  libraryRevision++;
  playlist = "";
  localGroup = undefined;
  if (full) setMode(false);
  ready = false;
  pairingButton();
  $("issuedCode").textContent = "";
  $("results").replaceChildren();
  $("queue").replaceChildren();
  lastQueue = undefined;
  send();
});

$("issuePair").addEventListener("click", async () => {
  if (!ready || !androidSelected || issuing) return;
  const selected = device;
  issuing = true;
  pairingButton();
  $("issuedCode").textContent = "正在向后台申请配对码…";
  try {
    const result = await fetchJson("/api/pairing?device=" + encodeURIComponent(selected), {method: "POST"});
    if (device === selected) $("issuedCode").textContent = "设备 " + selected + " · " + result.code + " · 5 分钟内使用一次";
  } catch (error) {
    if (error.status === 401) requireAdmin(error.message);
    $("issuedCode").textContent = error.message;
  } finally {
    issuing = false;
    pairingButton();
  }
});

async function pair(code) {
  const control = $("pairForm").querySelector("button");
  control.disabled = true;
  try {
    const result = await requestJson("/api/pair", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({code})
    }, 8000);
    if (!validKey(result.key)) throw new Error("配对失败");
    key = result.key;
    saveLanPair(key);
    $("pairCode").value = "";
    history.replaceState(null, "", location.pathname);
    $("pairPanel").hidden = true;
    send();
  } catch (error) {
    $("status").textContent = error.message;
  } finally {
    control.disabled = false;
  }
}

$("pairForm").addEventListener("submit", event => {
  event.preventDefault();
  pair($("pairCode").value);
});

$("adminForm").addEventListener("submit", event => {
  event.preventDefault();
  const candidate = $("adminKey").value.trim();
  if (!validKey(candidate)) {
    $("adminMessage").textContent = "请输入配对资料里的后台管理密钥，也可直接打开管理链接。";
    return;
  }
  key = candidate;
  $("adminKey").value = "";
  $("adminMessage").textContent = "正在验证管理密钥…";
  send();
});
$("accountForm").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  try {
    const {key: result} = await requestJson("/account/login", {
      method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({username: $("accountName").value.trim(), password: $("accountPassword").value,
        platform: "web", name: "网页遥控"})
    }, 12000);
    if (!validKey(result)) throw new Error("账号服务返回无效会话");
    key = result;
    accountMode = true;
    $("accountPassword").value = "";
    send();
  } catch (error) {
    $("adminMessage").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

$("accountLogout").addEventListener("click", async () => {
  try {
    await fetchJson("/account/logout", {method: "POST"});
    requireAdmin("已退出播放器账号。");
  } catch (error) {
    if (error.status === 401) requireAdmin("登录已失效，请重新登录。");
    else $("error").textContent = error.message;
  }
});

$("removeDevice").addEventListener("click", async () => {
  if (!device || !confirm("移除所选设备？该设备需要重新登录。")) return;
  try {
    await fetchJson("/account/remove-device", {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({id: device})
    });
    device = "";
    send();
  } catch (error) {
    $("error").textContent = error.message;
  }
});

if (central) {
  if (!validKey(key)) {
    key = sessionStorage.getItem(accountSessionKey) || "";
    accountMode = validKey(key);
    if (!accountMode) key = sessionStorage.getItem(adminSessionKey) || "";
  }
  if (validKey(key)) {
    history.replaceState(null, "", location.pathname);
    send();
  } else requireAdmin("使用 PC 和手机上的同一个播放器账号。");
} else {
  if (!validKey(key)) key = readLanPair();
  history.replaceState(null, "", location.pathname);
  if (validKey(key)) send();
  else requireLanPair("等待局域网配对");
}

let full = false;
let view = "player";
let lastState;
let libraryAction = "local";
let libraryCollection;
let radioId;
let libraryOffset = -1;
let libraryTracks = [];
let libraryRevision = 0;
let libraryWorking = false;
let playlist = "";
let localGroup;

function renderMode(state) {
  const supported = state.fullMode === true;
  $("modeSwitch").hidden = !supported;
  if (!supported && full) {
    full = false;
    if (view === "library") showView("player");
  }
  lastState = state;
  $("openLibrary").hidden = !full;
  $("fullPlayer").hidden = !full;
  $("scanLibrary").hidden = !filesEnabled;
  $("qualitySelect").value = String(state.quality ?? 0);
  const current = state.queue[state.index];
  $("lyricsButton").disabled = !current;
  $("radioButton").disabled = !(current?.id > 0);
  if ($("fullLyrics").dataset.id !== String(current?.id)) $("fullLyrics").hidden = true;
}

function showView(target) {
  view = target;
  const music = target === "library";
  $("libraryPanel").hidden = !music;
  showFiles(target === "files");
  $("playerView").hidden = target !== "player";
  $("openPlayer").setAttribute("aria-current", String(target === "player"));
  $("openLibrary").setAttribute("aria-current", String(music));
  if (music) loadLibrary();
}

function setMode(enabled) {
  full = enabled;
  $("modeRemote").setAttribute("aria-current", String(!full));
  $("modeFull").setAttribute("aria-current", String(full));
  renderMode(lastState);
  showView(full ? "library" : "player");
}

function selectLibrary(action) {
  libraryAction = action;
  libraryCollection = undefined;
  $("libraryQuery").value = "";
  loadLibrary();
}

function libraryControls() {
  const local = libraryAction === "local";
  const collections = libraryAction === "tidal-collections";
  $("localPlaylists").hidden = !local;
  $("localGroupBack").hidden = !localGroup;
  $("fullFilter").hidden = !local && !collections && libraryAction !== "tidal-search";
  $("collectionKind").hidden = !collections;
  $("libraryBack").hidden = !libraryCollection;
  $("deletePlaylist").hidden = !playlist;
  $("importPlaylist").hidden = !filesEnabled;
  $("exportPlaylist").hidden = !filesEnabled || !playlist;
  $("artistAlbums").hidden = libraryAction !== "tidal-collection" || libraryCollection?.kind !== "ARTIST";
  $("playAll").hidden = $("shuffleAll").hidden = !local || !libraryTracks.length;
  $("loadMore").hidden = libraryOffset < 0;
  const tabs = document.querySelectorAll("[data-library]");
  for (const tab of tabs) {
    const active = tab.dataset.library === libraryAction || (!!libraryCollection && tab.dataset.library === "tidal-collections");
    tab.setAttribute("aria-current", String(active));
    if (active) $("libraryCategory").textContent = libraryCollection?.title || (local ? localGroup?.title : undefined) || tab.textContent;
  }
  if (libraryAction === "tidal-radio") $("libraryCategory").textContent = "歌曲电台";
}

async function loadLibrary(append = false) {
  if (view !== "library") return;
  const revision = ++libraryRevision;
  if (!append) {
    libraryOffset = -1;
    libraryTracks = [];
    $("libraryTracks").replaceChildren();
    $("libraryTracks").classList.remove("cards");
  }
  libraryControls();
  $("loadMore").disabled = true;
  $("libraryMessage").textContent = "正在读取设备音乐库…";
  try {
    const result = await call("library", {
      action: libraryAction, query: $("libraryQuery").value, playlist, sort: $("localSort").value,
      kind: libraryCollection?.kind || $("collectionKind").value,
      collection: libraryCollection?.id, category: Number($("localCategory").value), group: localGroup?.id, id: radioId, offset: append ? libraryOffset : 0
    });
    if (revision !== libraryRevision || view !== "library") return;
    if (result.playlists) {
      if (!result.playlists.includes(playlist)) playlist = "";
      $("playlistSelect").replaceChildren(new Option("全部歌曲", ""),
        ...result.playlists.map(name => new Option(name, name)));
      $("playlistSelect").value = playlist;
    }
    libraryOffset = result.next;
    libraryTracks.push(...(result.tracks || []));
    for (const track of result.tracks || []) $("libraryTracks").append(libraryTrackRow(track));
    const cards = (result.groups || []).length + (result.collections || []).length > 0;
    if (!append || cards) $("libraryTracks").classList.toggle("cards", cards);
    for (const group of result.groups || []) {
      const detail = [group.detail, group.count + " 首"].filter(Boolean).join(" · ");
      $("libraryTracks").append(cardTile(group.title, detail, "", false, () => {
        localGroup = group;
        loadLibrary();
      }));
    }
    for (const collection of result.collections || []) {
      const kind = {ALBUM: "专辑", ARTIST: "艺人", PLAYLIST: "歌单"}[collection.kind] || "";
      $("libraryTracks").append(cardTile(collection.title, collection.subtitle || kind, collection.cover,
        collection.kind === "ARTIST", () => {
          libraryAction = "tidal-collection";
          libraryCollection = collection;
          loadLibrary();
        }));
    }
    if (!$("libraryTracks").children.length) $("libraryTracks").append(emptyRow("暂无内容"));
    $("libraryMessage").textContent = "";
  } catch (error) {
    if (revision === libraryRevision) $("libraryMessage").textContent = error.message;
  } finally {
    if (revision === libraryRevision) {
      libraryControls();
      $("loadMore").disabled = false;
    }
  }
}

async function libraryCommand(action, fields, message, reload = false) {
  if (libraryWorking) return;
  libraryWorking = true;
  const output = view === "player" ? $("error") : $("libraryMessage");
  output.textContent = "正在处理…";
  try {
    const result = await (action === "scan" || action === "export"
      ? call("library-" + action, fields) : call("library", {action, ...fields}));
    if (reload) await loadLibrary();
    output.textContent = typeof message === "function" ? message(result) : message;
    return result;
  } catch (error) {
    output.textContent = error.message;
  } finally {
    libraryWorking = false;
  }
}

function cardTile(title, detail, cover, round, open) {
  const row = document.createElement("li");
  const tile = button("", open);
  tile.className = "card-tile" + (round ? " round" : "");
  const name = document.createElement("strong");
  name.textContent = title;
  const sub = document.createElement("small");
  sub.textContent = detail || "";
  tile.append(thumb(title, cover, 320), name, sub);
  row.append(tile);
  return row;
}

function libraryTrackRow(track) {
  const row = trackRow(track);
  const id = track.id;
  row.addEventListener("dblclick", () => libraryCommand("song", {id, mode: "play"}, "已发送到播放设备"));
  for (const [mode, label, name] of [["play", "立即播放", "play"], ["next", "下一首播放", "next-up"], ["add", "加入队列", "plus"]]) {
    row.actions.append(iconButton(name, label, () => libraryCommand("song", {id, mode}, "已发送到播放设备")));
  }
  if (id < 0) {
    row.actions.append(iconButton("heart", "切换喜欢", () => libraryCommand("local-favorite", {id}, "已更新喜欢列表", true)));
    row.actions.append(iconButton("list-add", "加入歌单", () => {
      const name = window.prompt("输入目标歌单名称", playlist);
      if (name?.trim()) libraryCommand("playlist-add", {id, playlist: name.trim()}, "已加入歌单", true);
    }));
    if (libraryAction === "local" && playlist) {
      row.actions.append(iconButton("list-remove", "从歌单移除", () => libraryCommand("playlist-remove", {id, playlist}, "已从歌单移除", true)));
    }
  } else {
    const saved = libraryAction === "tidal-favorites";
    row.actions.append(iconButton(saved ? "heart-off" : "heart", saved ? "取消收藏" : "收藏到 TIDAL", () => libraryCommand("tidal-favorite",
      {id, enabled: !saved}, saved ? "已取消收藏" : "已收藏", saved)));
  }
  return row;
}

$("modeRemote").addEventListener("click", () => setMode(false));
$("modeFull").addEventListener("click", () => setMode(true));
$("openLibrary").addEventListener("click", () => showView("library"));
document.querySelectorAll("[data-library]").forEach(tab =>
  tab.addEventListener("click", () => selectLibrary(tab.dataset.library)));
$("fullFilter").addEventListener("submit", event => {
  event.preventDefault();
  loadLibrary();
});
$("collectionKind").addEventListener("change", () => loadLibrary());
$("localSort").addEventListener("change", () => loadLibrary());
function resetLocalGroup() {
  localGroup = undefined;
  loadLibrary();
}
$("localCategory").addEventListener("change", resetLocalGroup);
$("localGroupBack").addEventListener("click", resetLocalGroup);
$("artistAlbums").addEventListener("click", () => {
  libraryAction = "tidal-artist-albums";
  loadLibrary();
});
$("libraryBack").addEventListener("click", () => {
  libraryAction = "tidal-collections";
  libraryCollection = undefined;
  loadLibrary();
});
$("playlistSelect").addEventListener("change", () => {
  playlist = $("playlistSelect").value;
  resetLocalGroup();
});
$("createPlaylist").addEventListener("click", () => {
  const name = window.prompt("新歌单名称");
  if (name?.trim()) libraryCommand("create", {playlist: name.trim()}, "已创建歌单", true);
});
$("deletePlaylist").addEventListener("click", () => {
  if (playlist && window.confirm("删除歌单「" + playlist + "」？音频文件会保留。")) {
    const name = playlist;
    playlist = "";
    libraryCommand("delete", {playlist: name, confirmed: true}, "已删除歌单", true);
  }
});
$("loadMore").addEventListener("click", () => loadLibrary(true));
$("scanLibrary").addEventListener("click", () => libraryCommand("scan", {},
  result => "扫描完成 · " + result.scanned + " 首歌曲", true));
for (const [control, shuffle] of [["playAll", false], ["shuffleAll", true]]) {
  $(control).addEventListener("click", () => libraryCommand("play-list",
    {ids: libraryTracks.slice(-50).map(track => track.id), shuffle}, "已开始播放"));
}
$("qualitySelect").addEventListener("change", () =>
  libraryCommand("quality", {quality: Number($("qualitySelect").value)}, "已更新播放音质"));
$("lyricsButton").addEventListener("click", async () => {
  const current = lastState?.queue[lastState.index];
  if (!current) return;
  const panel = $("fullLyrics");
  if (!panel.hidden) {
    panel.hidden = true;
    return;
  }
  const result = await libraryCommand("lyrics", {id: current.id}, "");
  if (!result || lastState?.queue[lastState.index]?.id !== current.id) return;
  panel.textContent = result.lyrics || "此曲目暂无歌词";
  panel.dataset.id = String(current.id);
  panel.hidden = false;
});
$("radioButton").addEventListener("click", () => {
  const current = lastState?.queue[lastState.index];
  if (!(current?.id > 0)) return;
  radioId = current.id;
  libraryAction = "tidal-radio";
  libraryCollection = undefined;
  showView("library");
});

$("importPlaylist").addEventListener("click", () => $("m3uFile").click());
$("m3uFile").addEventListener("change", async () => {
  const input = $("m3uFile");
  const file = input.files[0];
  input.value = "";
  if (!file || libraryWorking) return;
  libraryWorking = true;
  try {
    if (file.size > 240 * 1024) throw new Error("歌单文件不能超过 240 KB");
    const name = file.name.replace(/\.m3u8?$/i, "");
    const result = await fetchJson("/api/playlist-import", {
      method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({name, text: await file.text()})
    });
    playlist = result.name;
    await loadLibrary();
    $("libraryMessage").textContent = "已导入「" + result.name + "」" +
      (result.missing ? " · " + result.missing + " 个条目未匹配到设备音频" : "");
  } catch (error) {
    $("libraryMessage").textContent = error.message;
  } finally {
    libraryWorking = false;
  }
});
$("exportPlaylist").addEventListener("click", async () => {
  const name = playlist;
  if (!name) return;
  const result = await libraryCommand("export", {playlist: name}, "歌单已导出");
  if (!result) return;
  const url = URL.createObjectURL(new Blob([result.text], {type: "audio/x-mpegurl"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = name + ".m3u8";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
});
