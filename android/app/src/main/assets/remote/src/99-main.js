  // --- Init ---
  window.addEventListener("DOMContentLoaded", () => {
    startProgressTicker();
    if (sharedCode) {
      pair(sharedCode);
    } else if (!key) {
      requireLanPair("请先在手机配对");
    } else {
      pollState();
      loadDownloads();
      applyRouteFromHash(location.hash);
    }
  });

})();

