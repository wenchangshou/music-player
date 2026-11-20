  // --- AI Recommendation Cards Rendering & Batch Operations ---
  function renderAiRecommendationCards(items, container, clear = true) {
    if (!container) return;
    if (clear) container.replaceChildren();
    if (!Array.isArray(items) || items.length === 0) return;

    const frag = document.createDocumentFragment();

    items.forEach(item => {
      if (!item) return;
      const card = document.createElement("div");
      card.className = "card-item";
      card.style.display = "flex";
      card.style.flexDirection = "column";
      card.dataset.cardId = item.id || `card-${Math.random()}`;

      const canDl = !!(item.canDownload && item.rawAlbum);
      const isSelected = !!(selectedAiCardIds && selectedAiCardIds.has(item.id));
      if (isSelected) card.classList.add("selected");

      const keyTrackName = item.keyTrack || (item.trackObj && item.trackObj.title) || item.title || "试听曲目";
      const artistName = item.artist || item.subtitle || (item.rawAlbum && (item.rawAlbum.subtitle || item.rawAlbum.detail)) || "未知艺人";
      const subtitleText = `${artistName}${item.year ? ` · ${item.year}` : ""}`;

      const sourceBadge = item.sourceBadge || (item.source === "local" ? "本地已收录 · 无损FLAC" : item.source === "netease" ? "网易云 · 无损" : "TIDAL · Hi-Res");
      const sourceColor = item.sourceColor || (item.source === "local" ? "var(--accent)" : item.source === "netease" ? "#e60026" : "var(--accent)");

      // 防御性安全读取（兼容 item.item 包装或扁平结构）
      const rawMeta = item.item || item;
      const acousticHighlight = rawMeta.acoustic_highlight || rawMeta.genre || item.aiGenre || item.genre || "发烧鉴赏";
      const recommendationReason = rawMeta.recommendation_reason || rawMeta.reason || item.aiReason || item.reason || "";

      card.innerHTML = `
        <div class="card-artwork">
          <img alt="" loading="lazy" decoding="async">
          <div class="ai-card-source-tag" style="color:${sourceColor}; border:1px solid ${sourceColor}55;">
            ${escapeHtml(sourceBadge)}
          </div>
          ${canDl ? `
          <div class="ai-card-checkbox-wrap" title="勾选多选下载此专辑">
            <input type="checkbox" class="ai-card-checkbox" ${isSelected ? "checked" : ""}>
            <span style="font-size:10.5px; font-weight:600;">选</span>
          </div>` : ""}
          <div class="card-play-overlay">
            <button type="button" class="btn-card-play" title="播放整张唱片"><svg class="icon"><use href="#i-play"/></svg></button>
          </div>
        </div>
        <div class="card-title" style="margin-top:8px;" title="${escapeHtml(item.title || "")}"></div>
        <div class="card-subtitle" style="display:flex; justify-content:space-between; align-items:center;">
          <span class="sub-artist"></span>
          <span class="badge gold" style="font-size:10px; padding:1px 6px;">Hi-Res</span>
        </div>
        <div class="ai-key-track-box" title="高光代表作">
          <div style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1;">
            <span style="color:var(--muted); font-size:10.5px;">高光曲目</span>
            <span style="font-weight:600; margin-left:4px;" class="key-track-title"></span>
          </div>
          <button type="button" class="btn-track-quick-play" title="试听该曲"><svg class="icon"><use href="#i-play"/></svg></button>
        </div>
        <div class="ai-reason-tag">
          <span style="color:var(--accent); font-weight:600;">[${escapeHtml(acousticHighlight)}]</span>
          ${escapeHtml(recommendationReason)}
        </div>
        <div style="margin-top:auto; padding-top:8px; display:flex; gap:6px;">
          <button type="button" class="btn-card-listen-track primary" style="flex:1; padding:5px 8px; font-size:11.5px; justify-content:center; display:flex; align-items:center; gap:4px;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-play"/></svg> 试听
          </button>
          ${item.rawAlbum ? `
          <button type="button" class="btn-card-view-album ghost" style="flex:1; padding:5px 8px; font-size:11.5px; justify-content:center; display:flex; align-items:center; gap:4px;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-disc"/></svg> 专辑
          </button>` : ""}
          <button type="button" class="btn-card-enqueue ghost" title="加入播放队列" style="padding:5px 8px; display:inline-flex; align-items:center; justify-content:center;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-add"/></svg>
          </button>
          ${canDl ? `
          <button type="button" class="btn-card-dl ghost" title="下载整张专辑到手机" style="padding:5px 8px; display:inline-flex; align-items:center; justify-content:center;">
            <svg class="icon" style="width:12px;height:12px;"><use href="#i-download"/></svg>
          </button>` : ""}
        </div>`;

      card.querySelector(".card-title").textContent = item.title || "";
      card.querySelector(".sub-artist").textContent = subtitleText;
      card.querySelector(".key-track-title").textContent = keyTrackName;
      applyArtwork(card.querySelector(".card-artwork"), card.querySelector(".card-artwork img"), item.coverUrl, item.title || "");

      // Checkbox 多选事件绑定
      const cbWrap = card.querySelector(".ai-card-checkbox-wrap");
      const cb = card.querySelector(".ai-card-checkbox");
      if (cbWrap && cb) {
        const toggleCb = e => {
          e.stopPropagation();
          if (cb.checked) {
            selectedAiCardIds.add(item.id);
            card.classList.add("selected");
          } else {
            selectedAiCardIds.delete(item.id);
            card.classList.remove("selected");
          }
          updateAiSelectedCount();
        };
        cb.addEventListener("change", toggleCb);
        cbWrap.addEventListener("click", e => {
          if (e.target !== cb) {
            e.stopPropagation();
            cb.checked = !cb.checked;
            cb.dispatchEvent(new Event("change"));
          }
        });
      }

      // 1. Play Key Track Action
      const playKeyTrack = async () => {
        if (item.trackObj) {
          playTracks([item.trackObj]);
        } else if (item.rawAlbum) {
          try {
            const detail = await call("library", { action: "tidal-collection", kind: "ALBUM", collection: String(item.rawAlbum.id) });
            if (detail.tracks?.length) playTracks(detail.tracks);
          } catch {}
        } else {
          toast(`正在全网检索「${artistName} - ${keyTrackName}」…`);
          call("library", { action: "tidal-search", query: `${artistName} ${keyTrackName}` }).then(r => {
            if (r.tracks?.length) playTracks(r.tracks);
          });
        }
      };

      card.querySelector(".btn-track-quick-play")?.addEventListener("click", e => {
        e.stopPropagation();
        playKeyTrack();
      });

      card.querySelector(".btn-card-listen-track")?.addEventListener("click", e => {
        e.stopPropagation();
        playKeyTrack();
      });

      // 2. Play Entire Album Overlay
      card.querySelector(".btn-card-play")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.rawAlbum) {
          const detail = await call("library", { action: "tidal-collection", kind: "ALBUM", collection: String(item.rawAlbum.id) });
          if (detail.tracks?.length) playTracks(detail.tracks);
        } else if (item.trackObj) {
          playTracks([item.trackObj]);
        }
      });

      // 3. View Album Details
      card.querySelector(".btn-card-view-album")?.addEventListener("click", e => {
        e.stopPropagation();
        if (item.rawAlbum) {
          openSearchCollection(item.rawAlbum, "ALBUM");
        }
      });

      // 4. Click card body
      card.addEventListener("click", e => {
        if (e.target.closest("button") || e.target.closest(".ai-card-checkbox-wrap")) return;
        if (item.rawAlbum) {
          openSearchCollection(item.rawAlbum, "ALBUM");
        } else {
          playKeyTrack();
        }
      });

      // 5. Enqueue Action
      card.querySelector(".btn-card-enqueue")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.trackObj) {
          try {
            await call("library", {
              action: "song",
              id: item.trackObj.id,
              mode: "add",
              track: { id: item.trackObj.id, title: item.trackObj.title, artist: item.trackObj.artist, album: item.trackObj.album, cover: item.trackObj.cover }
            });
            toast(`已添加《${keyTrackName}》至播放队列`);
          } catch (err) {
            toast(`添加队列失败：${err.message}`, "i-trash");
          }
        } else {
          toast("该曲目暂无可直接入队的音源", "i-info");
        }
      });

      // 6. Download Action
      card.querySelector(".btn-card-dl")?.addEventListener("click", async e => {
        e.stopPropagation();
        if (item.rawAlbum && item.canDownload) {
          try {
            await call("downloads", { action: "add", kind: "ALBUM", id: item.rawAlbum.id });
            toast(`已添加专辑「${item.title}」到下载队列`);
          } catch (err) {
            toast(`下载添加失败：${err.message}`, "i-trash");
          }
        }
      });

      frag.appendChild(card);
    });

    container.appendChild(frag);
  }

  // --- Session Action Bar Hooks ---
  if ($("btnAiPlayAll")) {
    $("btnAiPlayAll").addEventListener("click", () => {
      const playable = lastAiRecommendations.map(c => c.trackObj).filter(Boolean);
      if (!playable.length) {
        toast("暂未匹配到可直接播放的音频", "i-trash");
        return;
      }
      toast(`正在启动畅听 ${playable.length} 首 AI 推荐高光曲目…`);
      playTracks(playable, false, 0);
    });
  }

  if ($("btnAiEnqueueAll")) {
    $("btnAiEnqueueAll").addEventListener("click", async () => {
      const playable = lastAiRecommendations.map(c => c.trackObj).filter(Boolean);
      if (!playable.length) {
        toast("暂无可加入队列的曲目", "i-trash");
        return;
      }
      let added = 0;
      for (const t of playable) {
        try {
          await call("library", {
            action: "song",
            id: t.id,
            mode: "add",
            track: { id: t.id, title: t.title, artist: t.artist, album: t.album, cover: t.cover }
          });
          added++;
        } catch {}
      }
      toast(`已将 ${added} 首推荐曲目追加至播放队列！`);
    });
  }

  if ($("btnAiSelectAll")) {
    $("btnAiSelectAll").addEventListener("click", () => {
      const downloadable = lastAiRecommendations.filter(c => c.canDownload && c.rawAlbum);
      if (!downloadable.length) {
        toast("当前推荐列表中无可下载的唱片", "i-info");
        return;
      }
      const allSelected = downloadable.every(c => selectedAiCardIds.has(c.id));
      if (allSelected) {
        selectedAiCardIds.clear();
      } else {
        downloadable.forEach(c => selectedAiCardIds.add(c.id));
      }
      document.querySelectorAll("#searchCardsGrid .card-item").forEach(cardEl => {
        const id = cardEl.dataset.cardId;
        const cb = cardEl.querySelector(".ai-card-checkbox");
        const checked = selectedAiCardIds.has(id);
        if (cb) cb.checked = checked;
        cardEl.classList.toggle("selected", checked);
      });
      updateAiSelectedCount();
    });
  }

  if ($("btnAiDownloadSelected")) {
    $("btnAiDownloadSelected").addEventListener("click", async () => {
      const toDownload = lastAiRecommendations.filter(c => selectedAiCardIds.has(c.id) && c.canDownload && c.rawAlbum);
      if (!toDownload.length) {
        toast("请先勾选需要下载的唱片（或点击全选）", "i-info");
        return;
      }
      toast(`正在将选中的 ${toDownload.length} 张专辑添加至下载队列…`);
      let count = 0;
      for (const it of toDownload) {
        try {
          await call("downloads", { action: "add", kind: "ALBUM", id: it.rawAlbum.id });
          count++;
        } catch {}
      }
      toast(`已成功添加 ${count} 张所选发烧唱片至下载队列！`);
    });
  }
