  // --- Floating Toasts ---
  function toast(message, icon = "i-music") {
    const container = $("toastContainer");
    if (!container) return;
    const t = document.createElement("div");
    t.className = "toast";
    t.innerHTML = `<svg class="icon" style="width:18px;height:18px;"><use href="#${icon}"/></svg><span></span>`;
    // 消息里常带曲名、专辑名和 AI 生成的文字，只作为纯文本插入。
    t.querySelector("span").textContent = message;
    container.appendChild(t);
    setTimeout(() => {
      t.style.opacity = "0";
      t.style.transform = "translateY(-10px) scale(0.95)";
      t.style.transition = "all 0.25s ease";
      setTimeout(() => t.remove(), 250);
    }, 2400);
  }

  // --- Custom Modal Dialogs ---
  let modalCallback = null;
  function openCustomModal(title, lead, initialValue, onConfirm) {
    $("customModalTitle").textContent = title;
    $("customModalLead").textContent = lead;
    $("customModalInput").style.display = "";
    $("customModalInput").value = initialValue || "";
    $("customModalOverlay").classList.add("active");
    $("customModalInput").focus();
    modalCallback = onConfirm;
  }
  function openConfirmModal(title, lead, onConfirm) {
    $("customModalTitle").textContent = title;
    $("customModalLead").textContent = lead;
    $("customModalInput").style.display = "none";
    $("customModalOverlay").classList.add("active");
    modalCallback = () => {
      $("customModalInput").style.display = "";
      if (onConfirm) onConfirm();
    };
  }
  function closeCustomModal() {
    $("customModalOverlay").classList.remove("active");
    $("customModalInput").style.display = "";
    modalCallback = null;
  }
  $("btnCustomModalCancel").addEventListener("click", closeCustomModal);
  $("btnCustomModalConfirm").addEventListener("click", () => {
    const val = $("customModalInput").value.trim();
    if (modalCallback) modalCallback(val);
    closeCustomModal();
  });
  $("customModalOverlay").addEventListener("click", e => {
    if (e.target === $("customModalOverlay")) closeCustomModal();
  });

