  // --- Formatter & Utilities ---
  function formatTime(seconds) {
    if (isNaN(seconds) || seconds < 0) return "0:00";
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${s < 10 ? "0" : ""}${s}`;
  }

  const tidalImage = /^https:\/\/resources\.tidal\.com\/images\/[a-f0-9/]+\/\d+x\d+\.jpg$/i;
  function getArtworkUrl(item, size = 640) {
    if (!item) return "";
    let cover = "";
    if (typeof item === "string") {
      cover = item;
    } else {
      if (item.coverUrl) return item.coverUrl;
      cover = item.cover || item.picture || item.image || "";
      if (!cover && item.id && (item.isLocal || item.source === "本地音乐" || item.id < 0)) {
        return `/api/artwork/${item.id}`;
      }
    }
    if (!cover) return "";
    if (tidalImage.test(cover)) {
      return cover.replace(/\/\d+x\d+\.jpg$/i, `/${size}x${size}.jpg`);
    }
    if (/^[0-9a-fA-F-]{36}$/.test(cover)) {
      return `https://resources.tidal.com/images/${cover.replace(/-/g, "/")}/${size}x${size}.jpg`;
    }
    if (cover.includes("music.126.net") && !cover.includes("param=")) {
      return `${cover}${cover.includes("?") ? "&" : "?"}param=${size}y${size}`;
    }
    return cover;
  }

  function letterOf(title) {
    const name = (title || "").trim().replace(/\.(flac|wav|mp3|m4a|aac|ogg|oga|opus|ape|wv|aiff?|alac|wma)$/i, "");
    return name ? [...name][0].toUpperCase() : "♪";
  }

  function hueOf(text) {
    let hash = 0;
    for (const char of text || "") hash = (hash * 31 + char.codePointAt(0)) | 0;
    return Math.abs(hash * 37) % 360;
  }

  function applyArtwork(container, img, url, title) {
    const name = title || "";
    const letter = letterOf(name);
    const hue = hueOf(name);
    if (container) {
      container.dataset.letter = letter;
      container.style.setProperty("--art-hue", hue);
      container.style.setProperty("--hue", hue);
    }
    if (!img) return;
    img.loading = "lazy";
    img.decoding = "async";
    if (!url) {
      img.hidden = true;
      img.removeAttribute("src");
      if (container) {
        container.classList.remove("art-loaded");
        container.classList.add("art-failed");
      }
      return;
    }
    img.onload = () => {
      img.hidden = false;
      if (container) {
        container.classList.remove("art-failed");
        container.classList.add("art-loaded");
      }
    };
    img.onerror = () => {
      img.hidden = true;
      if (container) {
        container.classList.remove("art-loaded");
        container.classList.add("art-failed");
      }
    };
    img.src = url;
    if (img.complete && img.naturalWidth > 0) {
      img.hidden = false;
      if (container) {
        container.classList.remove("art-failed");
        container.classList.add("art-loaded");
      }
    }
  }

