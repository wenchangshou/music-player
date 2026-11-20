  // --- Downloads (the device's download tasks) ---
  const DL_ACTIVE = new Set(["queued", "preparing", "transferring", "waiting"]);
  const DL_LABELS = { queued: "排队中", preparing: "准备中", transferring: "下载中", waiting: "等待网络",
    complete: "已完成", failed: "失败", cancelled: "已取消" };
  let dlFilter = "all";
  let dlLimit = 300;
  let dlData = null;
  let dlBusy = false;
  let dlTimer = null;
  const dlSelected = new Set();

  function dlMatches(job) {
    if (dlFilter === "active") return DL_ACTIVE.has(job.state);
    if (dlFilter === "failed") return job.state === "failed" || job.state === "cancelled";
    if (dlFilter === "complete") return job.state === "complete";
    return true;
  }

  function mb(bytes) {
    return (bytes / 1048576).toFixed(1);
  }

  function dlStatus(job) {
    const label = DL_LABELS[job.state] || job.state;
    if (job.state === "transferring" && job.total > 0) {
      return `${label} · ${Math.floor(job.bytes * 100 / job.total)}% · ${mb(job.bytes)} / ${mb(job.total)} MB`;
    }
    if (job.state === "failed") return `${label}：${job.error || "未知错误"}`;
    if (job.state === "complete") return [label, job.resolution, job.total > 0 ? `${mb(job.total)} MB` : ""].filter(Boolean).join(" · ");
    return job.error && job.state === "waiting" ? `${label}：${job.error}` : label;
  }

  function scheduleDownloads() {
    clearTimeout(dlTimer);
    const busy = dlData && dlData.jobs.some(job => DL_ACTIVE.has(job.state));
    // Fast while the page is open and something downloads, slow otherwise (only the badge).
    const delay = activeView === "downloads" ? (busy ? 2000 : 8000) : 30000;
    dlTimer = setTimeout(() => loadDownloads(), delay);
  }

  async function downloadsCall(action, fields = {}) {
    return call("downloads", { action, limit: dlLimit, ...fields });
  }

  async function loadDownloads(reply) {
    if (!reply && dlBusy) return;
    try {
      if (!reply) {
        dlBusy = true;
        reply = await downloadsCall("list");
      }
      dlData = { ...reply, jobs: reply.jobs || [] };
      trackSpeeds(dlData.jobs);
      renderDownloads();
    } catch (err) {
      if (activeView === "downloads") {
        $("dlSummary").textContent = `读取下载任务失败：${err.message}`;
      }
    } finally {
      dlBusy = false;
      scheduleDownloads();
    }
  }

  // Download speed of each running task, from the bytes between two refreshes.
  const dlSpeeds = new Map();
  const dlInfo = new Map();
  const dlExpanded = new Set();
  const dlGroupOpen = new Map();
  let dlGrouped = true;
  try { dlGrouped = localStorage.getItem("dl-grouped") !== "0"; } catch (_) {}

  function trackSpeeds(jobs) {
    const now = performance.now();
    const seen = new Set();
    for (const job of jobs) {
      if (job.state !== "transferring") continue;
      seen.add(job.id);
      const prev = dlSpeeds.get(job.id);
      let rate = null;
      if (prev && job.bytes >= prev.bytes && now - prev.at > 300) {
        const instant = (job.bytes - prev.bytes) / ((now - prev.at) / 1000);
        rate = prev.rate == null ? instant : prev.rate * 0.5 + instant * 0.5;
      } else if (prev) rate = prev.rate;
      dlSpeeds.set(job.id, { bytes: job.bytes, at: now, rate });
    }
    for (const id of [...dlSpeeds.keys()]) if (!seen.has(id)) dlSpeeds.delete(id);
  }

  function speedText(rate) {
    if (rate == null) return "";
    return rate >= 1048576 ? `${(rate / 1048576).toFixed(1)} MB/s` : `${Math.round(rate / 1024)} KB/s`;
  }

  function sizeText(bytes) {
    if (!(bytes > 0)) return "";
    return bytes >= 1073741824 ? `${(bytes / 1073741824).toFixed(2)} GB` : `${mb(bytes)} MB`;
  }

  function durationText(seconds) {
    if (!(seconds > 0)) return "";
    const s = Math.round(seconds);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  }

  function dlProgressText(job) {
    const rate = dlSpeeds.get(job.id)?.rate;
    const parts = [];
    if (job.total > 0) {
      parts.push(`${Math.floor(job.bytes * 100 / job.total)}%`, `${mb(job.bytes)} / ${mb(job.total)} MB`);
    } else if (job.bytes > 0) parts.push(`已下载 ${mb(job.bytes)} MB`);
    if (rate != null) parts.push(speedText(rate));
    if (rate > 0 && job.total > job.bytes) parts.push(`剩余 ${durationText((job.total - job.bytes) / rate)}`);
    return parts.join(" · ");
  }

  function dlStatusText(job) {
    if (job.state === "transferring") {
      const progress = dlProgressText(job);
      return progress ? `下载中 · ${progress}` : "下载中";
    }
    return dlStatus(job);
  }

  function dlDetails(job) {
    const info = dlInfo.get(job.id);
    const box = document.createElement("dl");
    box.className = "dl-details";
    const add = (label, value) => {
      if (value === undefined || value === null || value === "") return;
      const dt = document.createElement("dt");
      dt.textContent = label;
      const dd = document.createElement("dd");
      dd.textContent = String(value);
      box.append(dt, dd);
    };
    if (!info) {
      add("详情", "正在读取…");
      return box;
    }
    if (info.error && !info.id) {
      add("详情", info.error);
      return box;
    }
    const ext = (info.name || "").includes(".") ? info.name.split(".").pop().toUpperCase() : "";
    const sample = info.sampleRate ? `${info.bitDepth ? info.bitDepth + " bit / " : ""}${(info.sampleRate / 1000).toFixed(1)} kHz` : "";
    add("版本", info.version);
    add("文件名", info.exists === false ? "文件已不存在" : info.name);
    add("位置", info.path);
    add("保存目录", info.folder);
    add("格式", [ext, info.mime].filter(Boolean).join(" · "));
    add("码率", info.bitrate ? `${Math.round(info.bitrate / 1000)} kbps` : "");
    add("采样", sample);
    add("时长", durationText(info.duration));
    add("大小", sizeText(info.size || (info.total > 0 ? info.total : 0)));
    add("下载音质", [info.resolution, info.verifiedLossless ? "已校验无损" : "", info.adopted ? "使用手机上已有的文件" : ""].filter(Boolean).join(" · "));
    add("专辑", [info.album, info.albumArtist].filter(Boolean).join(" · "));
    add("曲目", info.number ? `${info.volumes > 1 ? `第 ${info.disc} 碟 ` : ""}第 ${info.number} 首` : "");
    add("发行", info.date);
    add("来源", `${info.source} · 曲目 ${info.track}`);
    if (info.error) add("错误", info.error);
    if (info.attempts > 1) add("尝试次数", info.attempts);
    return box;
  }

  async function loadDownloadInfo(id) {
    try {
      const reply = await downloadsCall("info", { ids: [id] });
      dlInfo.set(id, reply.info);
    } catch (err) {
      dlInfo.set(id, { error: err.message });
    }
    renderDownloads();
  }

  const dlTrackDOM = new Map();
  const dlGroupDOM = new Map();

  function createDlTrackRow(job) {
    const li = document.createElement("li");
    li.className = "dl-track-row";
    li.dataset.id = job.id;

    const box = document.createElement("input");
    box.type = "checkbox";
    box.setAttribute("aria-label", "选择曲目");
    box.addEventListener("change", () => {
      if (box.checked) dlSelected.add(job.id); else dlSelected.delete(job.id);
      renderBatchBar();
    });
    li.appendChild(box);

    const thumb = document.createElement("div");
    thumb.className = "dl-track-thumb";
    const img = document.createElement("img");
    img.alt = "";
    img.hidden = true;
    thumb.appendChild(img);
    li.appendChild(thumb);

    const main = document.createElement("div");
    main.className = "dl-track-main";
    main.title = "查看文件技术信息";

    const header = document.createElement("div");
    header.className = "dl-track-header";
    const title = document.createElement("span");
    title.className = "dl-track-title";
    header.appendChild(title);
    const version = document.createElement("span");
    version.className = "dl-version";
    version.hidden = true;
    header.appendChild(version);
    main.appendChild(header);

    const sub = document.createElement("div");
    sub.className = "dl-track-sub";
    const sourcePill = document.createElement("span");
    sourcePill.className = "dl-pill";
    sub.appendChild(sourcePill);
    const qualityPill = document.createElement("span");
    qualityPill.className = "dl-pill";
    qualityPill.hidden = true;
    sub.appendChild(qualityPill);
    const meta = document.createElement("span");
    sub.appendChild(meta);
    main.appendChild(sub);

    const status = document.createElement("div");
    status.className = "dl-status-line";
    main.appendChild(status);

    const streamBar = document.createElement("div");
    streamBar.className = "dl-stream-bar";
    streamBar.hidden = true;
    const streamFill = document.createElement("div");
    streamFill.className = "dl-stream-fill";
    streamBar.appendChild(streamFill);
    main.appendChild(streamBar);

    main.addEventListener("click", () => {
      if (dlExpanded.has(job.id)) dlExpanded.delete(job.id);
      else {
        dlExpanded.add(job.id);
        loadDownloadInfo(job.id);
      }
      renderDownloads();
    });
    li.appendChild(main);

    const actions = document.createElement("div");
    actions.className = "dl-track-actions";
    li.appendChild(actions);

    return { root: li, box, thumb, img, title, version, sourcePill, qualityPill, meta, status, streamBar, streamFill, actions, detailsBox: null, lastState: null };
  }

  function updateDlTrackRow(record, job, isGrouped) {
    if (record.box.checked !== dlSelected.has(job.id)) {
      record.box.checked = dlSelected.has(job.id);
    }
    const displayTitle = job.title || `曲目 ${job.track}`;
    if (record.title.textContent !== displayTitle) record.title.textContent = displayTitle;

    if (job.version && !displayTitle.includes(job.version)) {
      record.version.textContent = ` (${job.version})`;
      record.version.hidden = false;
    } else {
      record.version.hidden = true;
    }

    const coverUrl = job.cover ? getArtworkUrl(job.cover, 120) : (job.track && job.source === "本地音乐" ? `/api/artwork/${job.track}` : "");
    applyArtwork(record.thumb, record.img, coverUrl, job.title || job.artist);

    const srcName = job.source || "TIDAL";
    if (record.sourcePill.textContent !== srcName) {
      record.sourcePill.textContent = srcName;
      record.sourcePill.className = "dl-pill " + (srcName === "网易云" ? "dl-pill-source-netease" : "dl-pill-source-tidal");
    }

    const qual = (job.resolution && job.state !== "complete" ? job.resolution : job.quality) || "";
    if (record.qualityPill.textContent !== qual) {
      record.qualityPill.textContent = qual;
      record.qualityPill.hidden = !qual;
      if (qual.includes("Hi-Res") || qual.includes("HI_RES") || qual.includes("192") || qual.includes("96")) {
        record.qualityPill.className = "dl-pill dl-pill-quality-hires";
      } else if (qual.includes("无损") || qual.includes("LOSSLESS") || qual.includes("FLAC")) {
        record.qualityPill.className = "dl-pill dl-pill-quality-lossless";
      } else {
        record.qualityPill.className = "dl-pill dl-pill-quality-standard";
      }
    }

    const parts = [job.artist, isGrouped ? "" : job.album].filter(Boolean);
    const metaText = parts.join(" · ");
    if (record.meta.textContent !== metaText) record.meta.textContent = metaText;

    const st = dlStatusText(job);
    if (record.status.textContent !== st) record.status.textContent = st;
    const statusCls = "dl-status-line " + (job.state || "");
    if (record.status.className !== statusCls) record.status.className = statusCls;

    if (job.state === "transferring" && job.total > 0) {
      record.streamBar.hidden = false;
      const pct = Math.min(100, Math.max(0, job.bytes * 100 / job.total));
      record.streamFill.style.width = pct + "%";
    } else {
      record.streamBar.hidden = true;
    }

    if (record.lastState !== job.state) {
      record.actions.replaceChildren();
      const btn = (label, action, cls = "ghost") => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = cls;
        b.textContent = label;
        b.addEventListener("click", e => {
          e.stopPropagation();
          downloadAction(action, [job.id]);
        });
        record.actions.appendChild(b);
      };
      if (DL_ACTIVE.has(job.state)) {
        btn("取消", "cancel");
      } else {
        if (job.state !== "complete") btn("重试", "retry");
        btn("移除", "remove", "ghost danger-text");
      }
      record.lastState = job.state;
    }

    if (dlExpanded.has(job.id)) {
      if (!record.detailsBox) {
        record.detailsBox = dlDetails(job);
        record.root.appendChild(record.detailsBox);
      }
    } else if (record.detailsBox) {
      record.detailsBox.remove();
      record.detailsBox = null;
    }
  }

  function createDlGroupRow(groupKey) {
    const li = document.createElement("li");
    li.className = "dl-group-card";
    li.dataset.group = groupKey;

    const header = document.createElement("div");
    header.className = "dl-group-header";

    const box = document.createElement("input");
    box.type = "checkbox";
    box.setAttribute("aria-label", "选择整张专辑");
    box.addEventListener("click", e => e.stopPropagation());
    header.appendChild(box);

    const thumb = document.createElement("div");
    thumb.className = "dl-group-thumb";
    const img = document.createElement("img");
    img.alt = "";
    img.hidden = true;
    thumb.appendChild(img);
    header.appendChild(thumb);

    const info = document.createElement("div");
    info.className = "dl-group-info";
    const title = document.createElement("div");
    title.className = "dl-group-title";
    info.appendChild(title);
    const sub = document.createElement("div");
    sub.className = "dl-group-sub";
    info.appendChild(sub);

    const metaBar = document.createElement("div");
    metaBar.className = "dl-group-meta-bar";
    const progressBar = document.createElement("div");
    progressBar.className = "dl-group-progress-bar";
    const progressFill = document.createElement("div");
    progressFill.className = "dl-group-progress-fill";
    progressBar.appendChild(progressFill);
    metaBar.appendChild(progressBar);
    const count = document.createElement("span");
    count.className = "dl-group-count";
    metaBar.appendChild(count);
    info.appendChild(metaBar);
    header.appendChild(info);

    const actions = document.createElement("div");
    actions.className = "dl-group-actions";
    header.appendChild(actions);

    const chevron = document.createElement("div");
    chevron.className = "dl-chevron-btn";
    chevron.innerHTML = `<svg class="icon"><use href="#i-chevron-down"/></svg>`;
    header.appendChild(chevron);

    li.appendChild(header);

    const memberList = document.createElement("ul");
    memberList.className = "dl-group-members";
    li.appendChild(memberList);

    header.addEventListener("click", e => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "BUTTON" || e.target.closest("button")) return;
      const curOpen = !li.classList.contains("closed");
      dlGroupOpen.set(groupKey, !curOpen);
      renderDownloads();
    });

    return { root: li, header, box, thumb, img, title, sub, progressFill, count, actions, chevron, memberList, lastActionSig: "" };
  }

  function updateDlGroupRow(record, groupKey, members) {
    const done = members.filter(j => j.state === "complete").length;
    const running = members.filter(j => DL_ACTIVE.has(j.state));
    const failed = members.filter(j => j.state === "failed" || j.state === "cancelled");
    const rate = running.reduce((sum, j) => sum + (dlSpeeds.get(j.id)?.rate || 0), 0);
    const open = dlGroupOpen.has(groupKey) ? dlGroupOpen.get(groupKey) : (running.length > 0 || failed.length > 0);

    record.root.classList.toggle("closed", !open);

    const groupTitle = groupKey || "单曲集";
    if (record.title.textContent !== groupTitle) record.title.textContent = groupTitle;

    const first = members[0] || {};
    const subText = [first.artist || "", `${members.length} 首曲目`].filter(Boolean).join(" · ");
    if (record.sub.textContent !== subText) record.sub.textContent = subText;

    const coverJob = members.find(j => j.cover);
    const coverUrl = coverJob ? getArtworkUrl(coverJob.cover, 160) : "";
    applyArtwork(record.thumb, record.img, coverUrl, groupTitle || first.artist);

    const pct = members.length ? Math.round(done * 100 / members.length) : 0;
    record.progressFill.style.width = pct + "%";

    const metaParts = [`${done} / ${members.length} 完成`, running.length ? `进行中 ${running.length}` : "", failed.length ? `失败 ${failed.length}` : "", rate > 0 ? speedText(rate) : ""].filter(Boolean);
    const countText = metaParts.join(" · ");
    if (record.count.textContent !== countText) record.count.textContent = countText;

    const allChecked = members.length > 0 && members.every(j => dlSelected.has(j.id));
    if (record.box.checked !== allChecked) record.box.checked = allChecked;
    record.box.onchange = () => {
      members.forEach(j => record.box.checked ? dlSelected.add(j.id) : dlSelected.delete(j.id));
      renderBatchBar();
      renderDownloads();
    };

    const sig = `${failed.length}:${running.length}:${done === members.length}`;
    if (record.lastActionSig !== sig) {
      record.actions.replaceChildren();
      const btn = (label, action, ids, cls = "ghost") => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = cls;
        b.textContent = label;
        b.addEventListener("click", e => {
          e.stopPropagation();
          downloadAction(action, ids);
        });
        record.actions.appendChild(b);
      };
      if (failed.length) btn("重试失败", "retry", failed.map(j => j.id));
      if (running.length) btn("全部取消", "cancel", running.map(j => j.id));
      if (!running.length) btn("移除", "remove", members.map(j => j.id), "ghost danger-text");
      record.lastActionSig = sig;
    }

    if (open) {
      members.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
      for (let i = 0; i < members.length; i++) {
        const job = members[i];
        let itemRec = dlTrackDOM.get(job.id);
        if (!itemRec) {
          itemRec = createDlTrackRow(job);
          dlTrackDOM.set(job.id, itemRec);
        }
        updateDlTrackRow(itemRec, job, true);
        if (record.memberList.children[i] !== itemRec.root) {
          record.memberList.insertBefore(itemRec.root, record.memberList.children[i] || null);
        }
      }
      while (record.memberList.children.length > members.length) {
        record.memberList.lastElementChild.remove();
      }
    }
  }

  function renderDownloads() {
    if (!dlData) return;
    const jobs = dlData.jobs;
    const active = jobs.filter(job => DL_ACTIVE.has(job.state)).length;
    const failed = jobs.filter(job => job.state === "failed" || job.state === "cancelled").length;
    const done = jobs.filter(job => job.state === "complete").length;
    const total = dlData.total ?? jobs.length;

    $("dlNavBadge").hidden = active === 0;
    $("dlNavBadge").textContent = String(active);
    if (activeView !== "downloads") return;

    const known = new Set(jobs.map(job => job.id));
    for (const id of [...dlSelected]) if (!known.has(id)) dlSelected.delete(id);
    for (const id of [...dlExpanded]) if (!known.has(id)) dlExpanded.delete(id);

    const parallel = $("dlParallel");
    const max = dlData.maxParallel || 5;
    if (parallel.options.length !== max) {
      parallel.innerHTML = Array.from({ length: max }, (_, i) => `<option value="${i + 1}">${i + 1} 首</option>`).join("");
    }
    if (document.activeElement !== parallel) parallel.value = String(dlData.parallel || 1);
    $("btnDlGroup").setAttribute("aria-pressed", String(dlGrouped));
    if ($("dlGroupBtnText")) $("dlGroupBtnText").textContent = dlGrouped ? "专辑分组" : "单曲平铺";

    if ($("dlBadgeAll")) $("dlBadgeAll").textContent = String(total);
    if ($("dlBadgeActive")) $("dlBadgeActive").textContent = String(active);
    if ($("dlBadgeFailed")) $("dlBadgeFailed").textContent = String(failed);
    if ($("dlBadgeComplete")) $("dlBadgeComplete").textContent = String(done);

    if ($("dlOverallCount")) $("dlOverallCount").textContent = `${done} / ${total} 完成`;
    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
    if ($("dlOverallPercent")) $("dlOverallPercent").textContent = `${pct}%`;
    if ($("dlOverallFill")) $("dlOverallFill").style.width = `${pct}%`;

    if ($("btnDlRetryAllFailed")) $("btnDlRetryAllFailed").hidden = failed === 0;

    const rate = [...dlSpeeds.values()].reduce((sum, item) => sum + (item.rate || 0), 0);
    const speedBadge = $("dlSpeedBadge");
    const speedTextEl = $("dlSpeedText");
    if (speedBadge && speedTextEl) {
      if (rate > 0) {
        speedBadge.classList.add("active");
        speedTextEl.textContent = speedText(rate);
      } else if (active > 0) {
        speedBadge.classList.add("active");
        speedTextEl.textContent = "排队准备中";
      } else {
        speedBadge.classList.remove("active");
        speedTextEl.textContent = "空闲";
      }
    }

    $("btnDlMore").hidden = !(total > jobs.length && dlLimit < 2000);

    const list = $("dlList");
    const shown = jobs.filter(dlMatches);

    if (!shown.length) {
      list.replaceChildren();
      const li = document.createElement("li");
      li.style.cssText = "text-align:center; padding:40px; color:var(--muted); list-style:none;";
      li.textContent = jobs.length ? "没有符合条件的任务" : "暂无下载任务";
      list.appendChild(li);
      renderBatchBar();
      return;
    }

    // If placeholder is present, clear it
    if (list.firstElementChild && !list.firstElementChild.dataset.id && !list.firstElementChild.dataset.group) {
      list.replaceChildren();
    }

    if (dlGrouped) {
      // Group by album
      const groups = new Map();
      for (const job of shown) {
        const key = job.album || "";
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(job);
      }
      const groupEntries = [...groups.entries()];
      for (let i = 0; i < groupEntries.length; i++) {
        const [key, members] = groupEntries[i];
        let groupRec = dlGroupDOM.get(key);
        if (!groupRec) {
          groupRec = createDlGroupRow(key);
          dlGroupDOM.set(key, groupRec);
        }
        updateDlGroupRow(groupRec, key, members);
        if (list.children[i] !== groupRec.root) {
          list.insertBefore(groupRec.root, list.children[i] || null);
        }
      }
      while (list.children.length > groupEntries.length) {
        list.lastElementChild.remove();
      }
    } else {
      for (let i = 0; i < shown.length; i++) {
        const job = shown[i];
        let itemRec = dlTrackDOM.get(job.id);
        if (!itemRec) {
          itemRec = createDlTrackRow(job);
          dlTrackDOM.set(job.id, itemRec);
        }
        updateDlTrackRow(itemRec, job, false);
        if (list.children[i] !== itemRec.root) {
          list.insertBefore(itemRec.root, list.children[i] || null);
        }
      }
      while (list.children.length > shown.length) {
        list.lastElementChild.remove();
      }
    }

    renderBatchBar();
    restoreScrollForView("downloads");
  }

  function renderBatchBar() {
    $("dlBatchBar").hidden = dlSelected.size === 0;
    $("dlSelCount").textContent = `已选 ${dlSelected.size} 项`;
  }

  async function downloadAction(action, ids) {
    const names = { cancel: "取消", retry: "重试", remove: "移除" };
    try {
      const reply = await downloadsCall(action, { ids });
      const skipped = reply.skipped ? `，${reply.skipped} 项未处理（正在下载的任务需先取消）` : "";
      toast(`已${names[action]} ${ids.length - (reply.skipped || 0)} 项${skipped}`, "i-download");
      if (action === "remove") ids.forEach(id => dlSelected.delete(id));
      loadDownloads(reply);
    } catch (err) {
      toast(`${names[action]}失败：${err.message}`, "i-trash");
    }
  }

  $("tabNavDownloads").addEventListener("click", () => showView("downloads"));
  $("btnDlRefresh").addEventListener("click", () => loadDownloads());
  $("btnDlGroup").addEventListener("click", () => {
    dlGrouped = !dlGrouped;
    try { localStorage.setItem("dl-grouped", dlGrouped ? "1" : "0"); } catch (_) {}
    syncRouteToHash();
    renderDownloads();
  });
  $("btnDlMore").addEventListener("click", () => {
    dlLimit = Math.min(2000, dlLimit + 300);
    loadDownloads();
  });
  document.querySelectorAll("[data-dl-filter]").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("[data-dl-filter]").forEach(b => b.setAttribute("aria-current", "false"));
      btn.setAttribute("aria-current", "true");
      dlFilter = btn.dataset.dlFilter;
      syncRouteToHash();
      renderDownloads();
    });
  });
  document.querySelectorAll("[data-dl-batch]").forEach(btn => {
    btn.addEventListener("click", () => {
      if (dlSelected.size) downloadAction(btn.dataset.dlBatch, [...dlSelected].slice(0, 500));
    });
  });
  $("btnDlSelectAll").addEventListener("click", () => {
    (dlData ? dlData.jobs.filter(dlMatches) : []).forEach(job => dlSelected.add(job.id));
    renderDownloads();
  });
  $("btnDlSelNone").addEventListener("click", () => {
    dlSelected.clear();
    renderDownloads();
  });
  $("btnDlClear").addEventListener("click", () => {
    openConfirmModal("清除已结束任务", "从列表中清除所有已完成、失败和已取消的任务？已下载的文件会保留在手机中。", async () => {
      try {
        loadDownloads(await downloadsCall("clear"));
        toast("已清除结束的任务", "i-trash");
      } catch (err) {
        toast(`清除失败：${err.message}`, "i-trash");
      }
    });
  });
  if ($("btnDlRetryAllFailed")) {
    $("btnDlRetryAllFailed").addEventListener("click", () => {
      if (!dlData) return;
      const failedJobs = dlData.jobs.filter(j => j.state === "failed" || j.state === "cancelled");
      if (failedJobs.length) {
        downloadAction("retry", failedJobs.map(j => j.id).slice(0, 500));
      }
    });
  }
  $("dlParallel").addEventListener("change", async e => {
    try {
      loadDownloads(await downloadsCall("parallel", { count: Number(e.target.value) }));
      toast(`同时下载 ${e.target.value} 首`, "i-download");
    } catch (err) {
      toast(`设置失败：${err.message}`, "i-trash");
    }
  });

