  // --- Folder-based Files Explorer System ---
  let fileRoots = [];
  let fileCurrentRoot = null;
  let fileCurrentPath = "";
  let fileCurrentDirectoryData = { directories: [], files: [] };
  let fileCurrentTracks = [];

  async function loadFiles() {
    try {
      if (!fileRoots.length) {
        const res = await call("files", { rootsOnly: true });
        fileRoots = res.folders || [];
      }
      if (!fileCurrentRoot && fileRoots.length > 0) {
        const pref = fileRoots.find(r => (r.name || "").toLowerCase().includes("external") || (r.id || "").toLowerCase().includes("external"));
        fileCurrentRoot = pref ? pref.id : fileRoots[0].id;
      }
      renderStorageRoots();
      if (fileCurrentRoot) {
        await loadCurrentDirectory();
      }
    } catch (err) {
      toast(`读取存储根目录失败：${err.message}`, "i-trash");
    }
  }

  function renderStorageRoots() {
    const list = $("storageRootsList");
    list.replaceChildren();
    if (!fileRoots.length) {
      const empty = document.createElement("div");
      empty.className = "folder-empty";
      empty.textContent = "未找到授权存储根目录";
      list.appendChild(empty);
      return;
    }
    fileRoots.forEach(root => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "root-btn" + (root.id === fileCurrentRoot ? " active" : "");

      const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      iconSvg.setAttribute("class", "icon");
      const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
      use.setAttribute("href", "#i-folder");
      iconSvg.appendChild(use);
      btn.appendChild(iconSvg);

      const span = document.createElement("span");
      let displayName = root.name;
      if (displayName.includes("external_sd1")) {
        displayName = "外置 SD 卡 (" + displayName.split(":").pop() + ")";
      } else if (displayName.includes("primary")) {
        displayName = "内置存储 (" + displayName.split(":").pop() + ")";
      }
      span.textContent = displayName;
      btn.appendChild(span);

      btn.addEventListener("click", () => {
        if (fileCurrentRoot === root.id && fileCurrentPath === "") return;
        fileCurrentRoot = root.id;
        fileCurrentPath = "";
        syncRouteToHash();
        renderStorageRoots();
        loadCurrentDirectory();
      });
      list.appendChild(btn);
    });
  }

  async function loadCurrentDirectory() {
    if (!fileCurrentRoot) return;
    $("fileSummaryText").textContent = "正在读取文件夹内容…";
    $("folderEmptyMessage").hidden = true;
    $("subfoldersGrid").replaceChildren();
    $("filesItemList").replaceChildren();
    $("folderAttachmentsList").replaceChildren();
    $("folderAttachmentsSection").hidden = true;
    $("btnPlayFolder").hidden = true;
    $("btnAddFolderToQueue").hidden = true;

    try {
      const res = await call("file-directory", { root: fileCurrentRoot, path: fileCurrentPath });
      fileCurrentDirectoryData = res;
      renderBreadcrumbs();

      const dirs = (res.directories || []).sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      const allFiles = res.files || [];
      const musicFiles = [];
      const attachmentFiles = [];

      allFiles.forEach(f => {
        if (f.attachment) attachmentFiles.push(f);
        else musicFiles.push(f);
      });
      musicFiles.sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      attachmentFiles.sort((a, b) => a.name.localeCompare(b.name, "zh-CN", { numeric: true }));
      // 若默认根目录为空且存在其他非空根目录，自动切换至有内容的根目录
      if (fileCurrentPath === "" && dirs.length === 0 && allFiles.length === 0 && fileRoots.length > 1) {
        const other = fileRoots.find(r => r.id !== fileCurrentRoot);
        if (other) {
          fileCurrentRoot = other.id;
          renderStorageRoots();
          return loadCurrentDirectory();
        }
      }

      fileCurrentTracks = musicFiles;

      // 渲染子文件夹
      $("subfoldersCount").textContent = String(dirs.length);
      $("subfoldersSection").hidden = dirs.length === 0;
      if (dirs.length > 0) {
        const fragDirs = document.createDocumentFragment();
        dirs.forEach(dir => {
          const card = document.createElement("div");
          card.className = "folder-card";
          card.title = dir.name;

          const iconDiv = document.createElement("div");
          iconDiv.className = "folder-icon";
          const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          iconSvg.setAttribute("class", "icon");
          const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
          use.setAttribute("href", "#i-folder");
          iconSvg.appendChild(use);
          iconDiv.appendChild(iconSvg);
          card.appendChild(iconDiv);

          const nameSpan = document.createElement("span");
          nameSpan.className = "folder-name";
          nameSpan.textContent = dir.name;
          card.appendChild(nameSpan);

          card.addEventListener("click", () => {
            fileCurrentPath = fileCurrentPath ? `${fileCurrentPath}/${dir.name}` : dir.name;
            syncRouteToHash();
            loadCurrentDirectory();
          });
          fragDirs.appendChild(card);
        });
        $("subfoldersGrid").appendChild(fragDirs);
      }

      // 渲染音乐文件
      $("folderTracksCount").textContent = String(musicFiles.length);
      $("folderTracksSection").hidden = musicFiles.length === 0;
      if (musicFiles.length > 0) {
        $("btnPlayFolder").hidden = false;
        $("btnAddFolderToQueue").hidden = false;
        const fragFiles = document.createDocumentFragment();
        musicFiles.forEach((file, index) => {
          const li = document.createElement("li");
          li.className = "track-row";

          const numSpan = document.createElement("span");
          numSpan.className = "track-num";
          numSpan.textContent = String(index + 1);
          li.appendChild(numSpan);

          const thumbDiv = document.createElement("div");
          thumbDiv.className = "track-thumb art-loaded";
          thumbDiv.style.background = "rgba(var(--accent-rgb, 10, 207, 131), 0.15)";
          thumbDiv.style.color = "var(--accent)";
          thumbDiv.style.display = "grid";
          thumbDiv.style.placeItems = "center";
          const mIcon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          mIcon.setAttribute("class", "icon");
          const mUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          mUse.setAttribute("href", "#i-music");
          mIcon.appendChild(mUse);
          thumbDiv.appendChild(mIcon);
          li.appendChild(thumbDiv);

          const infoDiv = document.createElement("div");
          infoDiv.className = "track-info";
          const titleDiv = document.createElement("div");
          titleDiv.className = "track-title";
          titleDiv.textContent = file.name;
          infoDiv.appendChild(titleDiv);

          const subDiv = document.createElement("div");
          subDiv.className = "track-subtitle";
          subDiv.textContent = (file.name.split(".").pop() || "").toUpperCase() + " 音频文件";
          infoDiv.appendChild(subDiv);
          li.appendChild(infoDiv);

          const actionsDiv = document.createElement("div");
          actionsDiv.className = "track-actions";

          const btnPlay = document.createElement("button");
          btnPlay.type = "button";
          btnPlay.className = "icon-btn";
          btnPlay.title = "立即播放";
          const pSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          pSvg.setAttribute("class", "icon");
          const pUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          pUse.setAttribute("href", "#i-play");
          pSvg.appendChild(pUse);
          btnPlay.appendChild(pSvg);
          btnPlay.addEventListener("click", e => {
            e.stopPropagation();
            call("add", { id: file.id, next: true });
            call("next");
            toast(`正在播放：${file.name}`, "i-play");
          });
          actionsDiv.appendChild(btnPlay);

          const btnAdd = document.createElement("button");
          btnAdd.type = "button";
          btnAdd.className = "icon-btn hover-only";
          btnAdd.title = "加入下一首播放";
          const aSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          aSvg.setAttribute("class", "icon");
          const aUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          aUse.setAttribute("href", "#i-plus");
          aSvg.appendChild(aUse);
          btnAdd.appendChild(aSvg);
          btnAdd.addEventListener("click", e => {
            e.stopPropagation();
            call("add", { id: file.id, next: true });
            toast(`已加入下一首播放：${file.name}`);
          });
          actionsDiv.appendChild(btnAdd);

          li.appendChild(actionsDiv);

          li.addEventListener("click", () => {
            call("add", { id: file.id, next: true });
            call("next");
            toast(`正在播放：${file.name}`, "i-play");
          });

          fragFiles.appendChild(li);
        });
        $("filesItemList").appendChild(fragFiles);
      }

      // 渲染附件（LRC、JPG 等）
      if (attachmentFiles.length > 0) {
        $("folderAttachmentsCount").textContent = String(attachmentFiles.length);
        $("folderAttachmentsSection").hidden = false;
        const fragAtt = document.createDocumentFragment();
        attachmentFiles.forEach(att => {
          const li = document.createElement("li");
          li.className = "track-row";
          li.style.opacity = "0.75";

          const ext = (att.name.split(".").pop() || "").toLowerCase();
          const isLrc = ext === "lrc";
          const isImg = ["jpg", "jpeg", "png", "webp"].includes(ext);

          const thumbDiv = document.createElement("div");
          thumbDiv.className = "track-thumb art-loaded";
          thumbDiv.style.background = isLrc ? "rgba(10, 132, 255, 0.15)" : "rgba(255, 255, 255, 0.08)";
          thumbDiv.style.color = isLrc ? "var(--accent)" : "var(--muted)";
          thumbDiv.style.display = "grid";
          thumbDiv.style.placeItems = "center";
          const attIcon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          attIcon.setAttribute("class", "icon");
          const attUse = document.createElementNS("http://www.w3.org/2000/svg", "use");
          attUse.setAttribute("href", isLrc ? "#i-lyrics" : isImg ? "#i-music" : "#i-folder");
          attIcon.appendChild(attUse);
          thumbDiv.appendChild(attIcon);
          li.appendChild(thumbDiv);

          const infoDiv = document.createElement("div");
          infoDiv.className = "track-info";
          const titleDiv = document.createElement("div");
          titleDiv.className = "track-title";
          titleDiv.textContent = att.name;
          infoDiv.appendChild(titleDiv);
          const subDiv = document.createElement("div");
          subDiv.className = "track-subtitle";
          subDiv.textContent = isLrc ? "歌词文件 (LRC)" : isImg ? "专辑封面图片" : "附件文件";
          infoDiv.appendChild(subDiv);
          li.appendChild(infoDiv);

          fragAtt.appendChild(li);
        });
        $("folderAttachmentsList").appendChild(fragAtt);
      }

      if (dirs.length === 0 && musicFiles.length === 0 && attachmentFiles.length === 0) {
        $("folderEmptyMessage").hidden = false;
      }

      const sumParts = [];
      if (dirs.length) sumParts.push(`${dirs.length} 个文件夹`);
      if (musicFiles.length) sumParts.push(`${musicFiles.length} 首音乐`);
      if (attachmentFiles.length) sumParts.push(`${attachmentFiles.length} 个附件`);
      $("fileSummaryText").textContent = sumParts.length ? sumParts.join(" · ") : "此文件夹为空";
      restoreScrollForView("files");
    } catch (err) {
      toast(`读取目录失败：${err.message}`, "i-trash");
      $("fileSummaryText").textContent = err.message;
    }
  }

  function renderBreadcrumbs() {
    const bar = $("fileBreadcrumbs");
    bar.replaceChildren();

    const rootObj = fileRoots.find(r => r.id === fileCurrentRoot);
    let rootName = "根目录";
    if (rootObj) {
      if (rootObj.name.includes("external")) rootName = "外置SD卡";
      else if (rootObj.name.includes("primary")) rootName = "内置存储";
      else rootName = rootObj.name.split(":").pop();
    }

    const pathSegments = fileCurrentPath ? fileCurrentPath.split("/").filter(Boolean) : [];
    $("btnParentFolder").hidden = pathSegments.length === 0;

    const rootItem = document.createElement("span");
    rootItem.className = "breadcrumb-item" + (pathSegments.length === 0 ? " current" : "");
    rootItem.textContent = rootName;
    rootItem.addEventListener("click", () => {
      if (fileCurrentPath === "") return;
      fileCurrentPath = "";
      syncRouteToHash();
      loadCurrentDirectory();
    });
    bar.appendChild(rootItem);

    let accumulatedPath = "";
    pathSegments.forEach((segment, idx) => {
      const sep = document.createElement("span");
      sep.className = "breadcrumb-sep";
      sep.textContent = "/";
      bar.appendChild(sep);

      accumulatedPath = accumulatedPath ? `${accumulatedPath}/${segment}` : segment;
      const thisPath = accumulatedPath;
      const isLast = idx === pathSegments.length - 1;

      const item = document.createElement("span");
      item.className = "breadcrumb-item" + (isLast ? " current" : "");
      item.textContent = segment;
      if (!isLast) {
        item.addEventListener("click", () => {
          fileCurrentPath = thisPath;
          syncRouteToHash();
          loadCurrentDirectory();
        });
      }
      bar.appendChild(item);
    });
  }

  $("btnParentFolder").addEventListener("click", () => {
    if (!fileCurrentPath) return;
    const parts = fileCurrentPath.split("/").filter(Boolean);
    parts.pop();
    fileCurrentPath = parts.join("/");
    syncRouteToHash();
    loadCurrentDirectory();
  });

  $("btnPlayFolder").addEventListener("click", async () => {
    if (!fileCurrentTracks.length) return;
    try {
      const trackIds = fileCurrentTracks.map(t => ({ id: t.id }));
      await call("library", { action: "play-list", tracks: trackIds });
      toast(`开始播放文件夹：${trackIds.length} 首音乐`, "i-play");
    } catch (err) {
      toast(`播放失败：${err.message}`, "i-trash");
    }
  });

  $("btnAddFolderToQueue").addEventListener("click", async () => {
    if (!fileCurrentTracks.length) return;
    try {
      for (const t of fileCurrentTracks) {
        await call("add", { id: t.id, next: false });
      }
      toast(`已将 ${fileCurrentTracks.length} 首音乐加入队列`, "i-queue");
    } catch (err) {
      toast(`加入队列失败：${err.message}`, "i-trash");
    }
  });

  // --- Multi-Directory Upload Management System ---
  const AUDIO_EXTS = new Set([
    "flac", "mp3", "wav", "m4a", "aac", "ogg", "aif", "aiff", "dsf", "dff", "ape", "alac", "opus", "wma"
  ]);
  const ATTACHMENT_EXTS = new Set([
    "jpg", "jpeg", "png", "webp", "gif", "lrc"
  ]);

  function getFileExt(filename) {
    const dot = filename.lastIndexOf(".");
    return dot > 0 ? filename.slice(dot + 1).toLowerCase() : "";
  }

  function isAllowedUploadFile(file) {
    if (!file || !file.name || file.name.startsWith(".")) return false;
    const ext = getFileExt(file.name);
    return AUDIO_EXTS.has(ext) || ATTACHMENT_EXTS.has(ext);
  }

  function formatFileSize(bytes) {
    if (!bytes || bytes <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + " " + units[i];
  }

  // 待上传文件夹分组数据
  let uploadFolderGroups = [];
  let uploadAbortController = null;
  let isUploading = false;
  let uploadAnySuccess = false;

  function openUploadFolderModal() {
    $("uploadFolderModal").classList.add("active");
    $("uploadConfigSection").hidden = false;
    $("uploadProgressSection").hidden = true;
    $("uploadSummaryResult").hidden = true;
    $("uploadSummaryResult").replaceChildren();
    $("btnUploadAbort").hidden = false;
    $("btnUploadFinish").hidden = true;

    // 显示目标目录
    let rootName = "当前存储根目录";
    const rootObj = fileRoots.find(r => r.id === fileCurrentRoot);
    if (rootObj) {
      if (rootObj.name.includes("external")) rootName = "外置SD卡 (" + rootObj.name.split(":").pop() + ")";
      else if (rootObj.name.includes("primary")) rootName = "内置存储 (" + rootObj.name.split(":").pop() + ")";
      else rootName = rootObj.name;
    }
    const fullTargetDisplay = fileCurrentPath ? `${rootName} / ${fileCurrentPath}` : rootName;
    $("uploadTargetDirName").textContent = fullTargetDisplay;
    renderUploadFolderGroups();
  }

  function closeUploadFolderModal() {
    if (isUploading) {
      if (!confirm("上传仍在进行中，确定要终止并关闭吗？")) return;
      if (uploadAbortController) {
        uploadAbortController.abort = true;
        if (uploadAbortController.xhr) {
          try { uploadAbortController.xhr.abort(); } catch (_) {}
        }
      }
    }
    $("uploadFolderModal").classList.remove("active");
    if (uploadAnySuccess) {
      uploadAnySuccess = false;
      loadCurrentDirectory();
    }
  }

  // 解析选择的文件集合，智能归纳上级目录与子目录
  function parseAndAppendFiles(fileList) {
    const rawFiles = Array.from(fileList);
    const validFiles = rawFiles.filter(isAllowedUploadFile);

    if (validFiles.length === 0) {
      toast("未找到支持的音频、歌词或封面文件", "i-trash");
      return;
    }

    const map = new Map();
    // 现有 groups 加入 map 索引以支持追加去重
    uploadFolderGroups.forEach(g => map.set(g.key, g));

    validFiles.forEach(file => {
      const relPath = file.webkitRelativePath || file.name;
      const parts = relPath.split("/").filter(Boolean);

      let parentName = "";
      let folderName = "";
      let relSubDir = "";
      let isRootGroup = false;

      if (parts.length <= 1) {
        // 单独文件（无层级）
        parentName = "";
        folderName = "散装文件";
        relSubDir = "";
        isRootGroup = true;
      } else if (parts.length === 2) {
        // 例如：范特西/01.flac
        parentName = parts[0];
        folderName = parts[0];
        relSubDir = "";
        isRootGroup = false;
      } else {
        // parts.length >= 3，例如：周杰伦/范特西/01.flac 或 周杰伦/范特西/CD1/01.flac
        parentName = parts[0];
        folderName = parts[1];
        // 内部相对子目录（例如 CD1）
        relSubDir = parts.slice(2, parts.length - 1).join("/");
        isRootGroup = false;
      }

      const key = `${parentName}:::${folderName}:::${isRootGroup}`;
      let group = map.get(key);
      if (!group) {
        group = {
          key,
          parentName,
          folderName,
          isRootGroup,
          files: [],
          musicCount: 0,
          attachCount: 0,
          totalBytes: 0,
          checked: true
        };
        map.set(key, group);
      }

      // 避免同一文件重复加入
      if (!group.files.some(item => item.file.name === file.name && item.file.size === file.size && item.file.lastModified === file.lastModified)) {
        const ext = getFileExt(file.name);
        const isMusic = AUDIO_EXTS.has(ext);
        group.files.push({
          file,
          parentName,
          folderName,
          relSubDir,
          isRootGroup
        });
        if (isMusic) group.musicCount++;
        else group.attachCount++;
        group.totalBytes += file.size;
      }
    });

    uploadFolderGroups = Array.from(map.values()).sort((a, b) => {
      if (a.parentName !== b.parentName) {
        return a.parentName.localeCompare(b.parentName, "zh-CN", { numeric: true });
      }
      return a.folderName.localeCompare(b.folderName, "zh-CN", { numeric: true });
    });

    openUploadFolderModal();
  }
  window.tidalParseAndAppendFiles = parseAndAppendFiles;

  // 渲染待上传子目录列表
  function renderUploadFolderGroups() {
    const list = $("uploadSubfoldersList");
    list.replaceChildren();

    if (uploadFolderGroups.length === 0) {
      const empty = document.createElement("div");
      empty.className = "folder-empty";
      empty.textContent = "未选择任何文件夹";
      list.appendChild(empty);
      updateUploadSummary();
      return;
    }

    const frag = document.createDocumentFragment();
    uploadFolderGroups.forEach(group => {
      const item = document.createElement("div");
      item.className = "upload-folder-item" + (group.checked ? " selected" : "");

      // 左侧信息
      const info = document.createElement("div");
      info.className = "upload-folder-info";

      const chk = document.createElement("input");
      chk.type = "checkbox";
      chk.checked = group.checked;
      chk.style.marginRight = "6px";
      chk.addEventListener("click", e => e.stopPropagation());
      chk.addEventListener("change", () => {
        group.checked = chk.checked;
        item.classList.toggle("selected", group.checked);
        updateUploadSummary();
      });
      info.appendChild(chk);

      const iconSvg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      iconSvg.setAttribute("class", "icon");
      iconSvg.style.color = "var(--accent)";
      const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
      use.setAttribute("href", group.isRootGroup ? "#i-music" : "#i-folder");
      iconSvg.appendChild(use);
      info.appendChild(iconSvg);

      const meta = document.createElement("div");
      meta.className = "upload-folder-meta";

      const nameEl = document.createElement("div");
      nameEl.className = "upload-folder-name";
      nameEl.textContent = group.folderName;
      meta.appendChild(nameEl);

      if (group.parentName && group.parentName !== group.folderName) {
        const parentEl = document.createElement("div");
        parentEl.className = "upload-folder-parent";
        parentEl.textContent = `所属上级: ${group.parentName}`;
        meta.appendChild(parentEl);
      }
      info.appendChild(meta);
      item.appendChild(info);

      // 右侧统计
      const stats = document.createElement("div");
      stats.className = "upload-folder-stats";
      const parts = [];
      if (group.musicCount > 0) parts.push(`${group.musicCount} 首音乐`);
      if (group.attachCount > 0) parts.push(`${group.attachCount} 附件`);
      parts.push(formatFileSize(group.totalBytes));
      stats.textContent = parts.join(" · ");
      item.appendChild(stats);

      // 卡片点击反转
      item.addEventListener("click", () => {
        group.checked = !group.checked;
        chk.checked = group.checked;
        item.classList.toggle("selected", group.checked);
        updateUploadSummary();
      });

      frag.appendChild(item);
    });

    list.appendChild(frag);
    updateUploadSummary();
  }

  function updateUploadSummary() {
    const selectedGroups = uploadFolderGroups.filter(g => g.checked);
    $("uploadSelectedFolderCount").textContent = String(selectedGroups.length);
    $("uploadTotalFolderCount").textContent = String(uploadFolderGroups.length);

    let totalFiles = 0;
    let totalBytes = 0;
    selectedGroups.forEach(g => {
      totalFiles += g.files.length;
      totalBytes += g.totalBytes;
    });

    $("uploadSummaryBadge").textContent = `${totalFiles} 个文件 · ${formatFileSize(totalBytes)}`;
    $("btnUploadFolderStart").disabled = totalFiles === 0;
  }

  // 执行批量上传
  async function startBatchUpload() {
    const selectedGroups = uploadFolderGroups.filter(g => g.checked);
    if (selectedGroups.length === 0) {
      toast("请至少勾选一个要上传的子目录", "i-trash");
      return;
    }

    if (!fileCurrentRoot) {
      toast("请先选择授权存储根目录", "i-trash");
      return;
    }

    const keepParent = $("chkKeepParentFolder").checked;
    const policy = document.querySelector('input[name="uploadConflictPolicy"]:checked')?.value || "skip";

    // 收集所有待上传文件条目
    const queue = [];
    selectedGroups.forEach(group => {
      group.files.forEach(item => {
        let dirParts = [];
        // 如果当前在某个子目录内，以当前子目录为前缀
        if (fileCurrentPath) {
          dirParts.push(fileCurrentPath);
        }

        if (keepParent && item.parentName) {
          dirParts.push(item.parentName);
        }

        // 如果不是根目录散装，且 (未保留上级目录 或 folderName 与 parentName 不同)
        if (!item.isRootGroup) {
          if (!keepParent || item.folderName !== item.parentName) {
            dirParts.push(item.folderName);
          }
        }

        if (item.relSubDir) {
          dirParts.push(item.relSubDir);
        }

        const targetDirectory = dirParts.filter(Boolean).join("/");
        queue.push({
          file: item.file,
          targetDirectory,
          displayName: item.file.name,
          size: item.file.size
        });
      });
    });

    if (queue.length === 0) {
      toast("待上传文件列表为空", "i-trash");
      return;
    }

    // 切换至进度视图
    isUploading = true;
    uploadAnySuccess = false;
    $("uploadConfigSection").hidden = true;
    $("uploadProgressSection").hidden = false;
    $("uploadSummaryResult").hidden = true;
    $("btnUploadAbort").hidden = false;
    $("btnUploadFinish").hidden = true;

    uploadAbortController = { abort: false, xhr: null };

    const totalFilesCount = queue.length;
    let totalBytesAll = queue.reduce((sum, item) => sum + item.size, 0);
    let bytesUploadedTotal = 0;
    let successCount = 0;
    let skippedCount = 0;
    let failedCount = 0;
    const logs = [];

    $("uploadProgressStatusText").textContent = `正在上传 (1/${totalFilesCount})…`;
    $("uploadProgressRatioText").textContent = `1 / ${totalFilesCount}`;
    $("uploadProgressFill").style.width = "0%";

    for (let i = 0; i < queue.length; i++) {
      if (uploadAbortController.abort) {
        logs.push(`⚠️ 上传已由用户终止`);
        break;
      }

      const item = queue[i];
      $("uploadProgressStatusText").textContent = `正在上传 (${i + 1}/${totalFilesCount})…`;
      $("uploadProgressRatioText").textContent = `${i + 1} / ${totalFilesCount}`;
      $("uploadCurrentFileName").textContent = item.targetDirectory ? `${item.targetDirectory}/${item.displayName}` : item.displayName;
      $("uploadCurrentFileProgress").textContent = `0 B / ${formatFileSize(item.size)}`;

      let currentFileLoaded = 0;

      try {
        const res = await new Promise((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          uploadAbortController.xhr = xhr;

          xhr.upload.onprogress = (e) => {
            if (e.lengthComputable) {
              currentFileLoaded = e.loaded;
              const overallLoaded = bytesUploadedTotal + currentFileLoaded;
              const percent = Math.min(100, Math.round((overallLoaded / (totalBytesAll || 1)) * 100));
              $("uploadProgressFill").style.width = `${percent}%`;
              $("uploadCurrentFileProgress").textContent = `${formatFileSize(e.loaded)} / ${formatFileSize(e.total)}`;
              $("uploadTotalSizeProgress").textContent = `${formatFileSize(overallLoaded)} / ${formatFileSize(totalBytesAll)} · ${percent}%`;
            }
          };

          xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
              try {
                resolve(JSON.parse(xhr.responseText || "{}"));
              } catch (_) {
                resolve({ status: "ok" });
              }
            } else {
              try {
                const errObj = JSON.parse(xhr.responseText || "{}");
                reject(new Error(errObj.error || `HTTP ${xhr.status}`));
              } catch (_) {
                reject(new Error(`HTTP ${xhr.status}`));
              }
            }
          };

          xhr.onerror = () => reject(new Error("网络传输失败"));
          xhr.ontimeout = () => reject(new Error("上传超时"));
          xhr.onabort = () => reject(new Error("上传已取消"));

          xhr.open("POST", "/api/upload");
          if (key) {
            xhr.setRequestHeader("Authorization", "Bearer " + key);
          }
          xhr.setRequestHeader("Content-Type", "application/octet-stream");
          xhr.setRequestHeader("X-File-Name", encodeURIComponent(item.file.name));
          xhr.setRequestHeader("X-Upload-Folder", encodeURIComponent(fileCurrentRoot));
          xhr.setRequestHeader("X-Upload-Directory", encodeURIComponent(item.targetDirectory));
          xhr.setRequestHeader("X-Upload-Conflict", policy);
          xhr.timeout = 300000;
          xhr.send(item.file);
        });

        bytesUploadedTotal += item.size;
        if (res.status === "skipped") {
          skippedCount++;
          logs.push(`⚪ 已跳过: ${item.displayName} (已存在)`);
        } else {
          successCount++;
          uploadAnySuccess = true;
          logs.push(`✅ 成功: ${item.displayName}`);
        }
      } catch (err) {
        if (uploadAbortController.abort) break;
        failedCount++;
        bytesUploadedTotal += item.size;
        logs.push(`❌ 失败: ${item.displayName} (${err.message})`);
      }
    }

    isUploading = false;
    $("btnUploadAbort").hidden = true;
    $("btnUploadFinish").hidden = false;
    $("uploadProgressFill").style.width = "100%";

    if (uploadAbortController.abort) {
      $("uploadProgressStatusText").textContent = "上传已中断";
      $("uploadProgressStatusText").style.color = "var(--danger)";
    } else {
      $("uploadProgressStatusText").textContent = "全部上传完成！";
      $("uploadProgressStatusText").style.color = "var(--accent)";
    }

    const summaryResultEl = $("uploadSummaryResult");
    summaryResultEl.hidden = false;
    summaryResultEl.replaceChildren();

    const summaryTitle = document.createElement("div");
    summaryTitle.style.fontWeight = "bold";
    summaryTitle.style.marginBottom = "8px";
    summaryTitle.textContent = `上传统计：成功 ${successCount} 个，跳过 ${skippedCount} 个，失败 ${failedCount} 个`;
    summaryResultEl.appendChild(summaryTitle);

    const logList = document.createElement("div");
    logList.style.display = "flex";
    logList.style.flexDirection = "column";
    logList.style.gap = "4px";
    logs.slice(-20).forEach(msg => {
      const line = document.createElement("div");
      line.style.fontSize = "12px";
      line.style.color = msg.startsWith("❌") ? "var(--danger)" : msg.startsWith("⚪") ? "var(--muted)" : "var(--ink)";
      line.textContent = msg;
      logList.appendChild(line);
    });
    if (logs.length > 20) {
      const more = document.createElement("div");
      more.style.fontSize = "11.5px";
      more.style.color = "var(--muted)";
      more.textContent = `… 以及其他 ${logs.length - 20} 个文件`;
      logList.appendChild(more);
    }
    summaryResultEl.appendChild(logList);

    if (uploadAnySuccess) {
      toast(`成功上传 ${successCount} 个文件`, "i-upload");
    }
  }

  // 绑定文件夹选择与上传相关事件
  $("btnUploadFolder").addEventListener("click", () => {
    uploadFolderGroups = [];
    $("uploadFolderInput").value = "";
    $("uploadFolderInput").click();
  });

  $("uploadFolderInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadAddMoreFolders").addEventListener("click", () => {
    $("uploadMoreFolderInput").value = "";
    $("uploadMoreFolderInput").click();
  });

  $("uploadMoreFolderInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadFiles").addEventListener("click", () => {
    $("uploadFileInput").value = "";
    $("uploadFileInput").click();
  });

  $("uploadFileInput").addEventListener("change", e => {
    if (e.target.files && e.target.files.length > 0) {
      parseAndAppendFiles(e.target.files);
    }
  });

  $("btnUploadSelectAll").addEventListener("click", () => {
    uploadFolderGroups.forEach(g => g.checked = true);
    renderUploadFolderGroups();
  });

  $("btnUploadDeselectAll").addEventListener("click", () => {
    uploadFolderGroups.forEach(g => g.checked = false);
    renderUploadFolderGroups();
  });

  $("btnUploadFolderStart").addEventListener("click", startBatchUpload);

  $("btnUploadAbort").addEventListener("click", () => {
    if (confirm("确定要终止正在进行的上传任务吗？")) {
      if (uploadAbortController) {
        uploadAbortController.abort = true;
        if (uploadAbortController.xhr) {
          try { uploadAbortController.xhr.abort(); } catch (_) {}
        }
      }
    }
  });

  $("btnUploadFinish").addEventListener("click", closeUploadFolderModal);
  $("btnUploadFolderModalClose").addEventListener("click", closeUploadFolderModal);
  $("btnUploadFolderCancel").addEventListener("click", closeUploadFolderModal);

  // 支持拖拽上传至文件页面
  const viewFilesEl = $("viewFiles");
  if (viewFilesEl) {
    viewFilesEl.addEventListener("dragover", e => {
      e.preventDefault();
      e.stopPropagation();
    });
    viewFilesEl.addEventListener("drop", e => {
      e.preventDefault();
      e.stopPropagation();
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        parseAndAppendFiles(e.dataTransfer.files);
      }
    });
  }

  $("btnRefreshFiles").addEventListener("click", loadCurrentDirectory);

