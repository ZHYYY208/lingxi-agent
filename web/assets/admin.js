(() => {
  "use strict";

  const TOKEN_KEY = "lingxi.admin.token";
  const state = {
    token: localStorage.getItem(TOKEN_KEY) || "",
    docs: [],
    stats: null,
    model: null,
    health: null,
    overview: null,
    uploads: [],
    uploading: false,
    pollTimer: null,
    pollAttempts: 0,
    docQuery: "",
    docStatus: "all",
    confirmResolver: null,
  };

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const icon = (name) => `<svg><use href="#i-${name}"></use></svg>`;
  const dom = {};

  document.addEventListener("DOMContentLoaded", init);

  function init() {
    cacheDom();
    bindEvents();
    if (state.token) {
      verifySession();
    } else {
      showLogin();
    }
  }

  function cacheDom() {
    [
      "loginView", "adminApp", "loginForm", "adminPassword", "loginError", "loginBtn",
      "logoutBtn", "adminSidebar", "mobileScrim", "mobileMenu", "sidebarClose",
      "sectionTitle", "modelModePill", "sidebarHealthDot", "sidebarHealthText",
      "sidebarModelName", "defaultPasswordWarning", "statDocs", "statReady", "statChunks",
      "statSessions", "statMessages", "statCoverage", "statIndexedAt", "recentDocs",
      "modelStatusChip", "dashboardModelName", "dashboardModelProvider", "dashboardBaseUrl",
      "dashboardApiKey", "dashboardMode", "navDocCount", "navModelDot",
      "seedBtn", "uploadBtn", "dropzone", "fileInput", "browseBtn", "uploadProgress",
      "uploadLabel", "uploadPercent", "uploadBar", "uploadFiles", "docCount", "docSearch",
      "docStatus", "docList", "docEmpty", "modelSectionStatus", "modelForm",
      "baseUrl", "modelName", "apiKey", "keyState", "clearKey", "temperature", "maxTokens",
      "testModelBtn", "saveModelBtn", "testResult", "confirmModal", "confirmTitle",
      "confirmMessage", "confirmCancel", "confirmOk", "docModal", "docModalTitle",
      "chunkList", "toastRoot",
    ].forEach((id) => dom[id] = document.getElementById(id));
    dom.navItems = $$(".admin-nav-item");
    dom.sections = $$(".admin-section");
  }

  function bindEvents() {
    dom.loginForm.addEventListener("submit", login);
    dom.logoutBtn.addEventListener("click", logout);
    dom.navItems.forEach((button) => button.addEventListener("click", () => go(button.dataset.section)));
    $$("[data-go]").forEach((button) => button.addEventListener("click", () => go(button.dataset.go)));
    dom.mobileMenu.addEventListener("click", openSidebar);
    dom.sidebarClose.addEventListener("click", closeSidebar);
    dom.mobileScrim.addEventListener("click", closeSidebar);

    dom.seedBtn.addEventListener("click", seedDocuments);
    dom.uploadBtn.addEventListener("click", () => dom.fileInput.click());
    dom.browseBtn.addEventListener("click", (event) => {
      event.stopPropagation();
      dom.fileInput.click();
    });
    dom.fileInput.addEventListener("change", () => {
      handleFiles(dom.fileInput.files);
      dom.fileInput.value = "";
    });
    dom.dropzone.addEventListener("click", (event) => {
      if (event.target.closest("button") || event.target.closest(".upload-progress")) return;
      dom.fileInput.click();
    });
    ["dragenter", "dragover"].forEach((name) => dom.dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      dom.dropzone.classList.add("dragover");
    }));
    ["dragleave", "drop"].forEach((name) => dom.dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      dom.dropzone.classList.remove("dragover");
    }));
    dom.dropzone.addEventListener("drop", (event) => {
      event.preventDefault();
      dom.dropzone.classList.remove("dragover");
      if (event.dataTransfer?.files?.length) handleFiles(event.dataTransfer.files);
    });
    dom.docSearch.addEventListener("input", () => {
      state.docQuery = dom.docSearch.value.trim().toLowerCase();
      renderDocuments();
    });
    dom.docStatus.addEventListener("change", () => {
      state.docStatus = dom.docStatus.value;
      renderDocuments();
    });
    dom.docList.addEventListener("click", handleDocAction);

    $$("input[name=provider]").forEach((input) => input.addEventListener("change", updateProviderUi));
    $$(".example-btn").forEach((button) => button.addEventListener("click", () => applyExample(button.dataset.example)));
    dom.modelForm.addEventListener("submit", saveModel);
    dom.testModelBtn.addEventListener("click", testModel);

    dom.confirmCancel.addEventListener("click", () => resolveConfirm(false));
    dom.confirmOk.addEventListener("click", () => resolveConfirm(true));
    document.addEventListener("click", (event) => {
      if (event.target.closest("[data-close-confirm]")) resolveConfirm(false);
      if (event.target.closest("[data-close-doc]")) closeDocModal();
    });
    document.addEventListener("keydown", (event) => {
      if (event.key !== "Escape") return;
      if (!dom.confirmModal.hidden) return resolveConfirm(false);
      if (!dom.docModal.hidden) return closeDocModal();
      closeSidebar();
    });
  }

  // ------------------------------------------------------------------- API
  async function api(path, options = {}, admin = true) {
    const headers = new Headers(options.headers || {});
    if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    if (admin && state.token) headers.set("X-Admin-Token", state.token);
    let response;
    try {
      response = await fetch(path, { ...options, headers });
    } catch {
      throw new Error("无法连接服务端");
    }
    const type = response.headers.get("content-type") || "";
    const payload = type.includes("application/json") ? await response.json().catch(() => null) : await response.text();
    if (!response.ok) {
      if (response.status === 401 && admin) {
        clearSession();
        showLogin("管理员登录已过期，请重新登录");
      }
      const detail = payload && typeof payload === "object" ? payload.detail : payload;
      const error = new Error(detail || `请求失败（${response.status}）`);
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  async function verifySession() {
    try {
      await api("/api/admin/me");
      showApp();
      await loadAll();
    } catch (error) {
      if (error.status !== 401) {
        showLogin(error.message);
      }
    }
  }

  async function login(event) {
    event.preventDefault();
    dom.loginError.hidden = true;
    dom.loginBtn.disabled = true;
    dom.loginBtn.textContent = "正在验证…";
    try {
      const result = await api("/api/admin/login", {
        method: "POST",
        body: JSON.stringify({ password: dom.adminPassword.value }),
      }, false);
      state.token = result.token;
      localStorage.setItem(TOKEN_KEY, state.token);
      dom.adminPassword.value = "";
      showApp();
      await loadAll();
    } catch (error) {
      dom.loginError.textContent = error.message;
      dom.loginError.hidden = false;
    } finally {
      dom.loginBtn.disabled = false;
      dom.loginBtn.textContent = "进入控制台";
    }
  }

  async function logout() {
    try {
      await api("/api/admin/logout", { method: "POST" });
    } catch {
      // Token may already be expired.
    }
    clearSession();
    showLogin();
  }

  function clearSession() {
    state.token = "";
    localStorage.removeItem(TOKEN_KEY);
  }

  function showLogin(message = "") {
    dom.adminApp.hidden = true;
    dom.loginView.hidden = false;
    if (message) {
      dom.loginError.textContent = message;
      dom.loginError.hidden = false;
    }
    setTimeout(() => dom.adminPassword.focus(), 60);
  }

  function showApp() {
    dom.loginView.hidden = true;
    dom.adminApp.hidden = false;
    closeSidebar();
  }

  async function loadAll() {
    const results = await Promise.allSettled([
      api("/api/stats"),
      api("/api/documents"),
      api("/api/admin/model"),
      api("/api/health", {}, false),
      api("/api/admin/overview"),
    ]);
    if (results[0].status === "fulfilled") state.stats = results[0].value;
    if (results[1].status === "fulfilled") state.docs = results[1].value;
    if (results[2].status === "fulfilled") state.model = results[2].value;
    if (results[3].status === "fulfilled") state.health = results[3].value;
    if (results[4].status === "fulfilled") state.overview = results[4].value;
    renderAll();
    schedulePoll();
  }

  function renderAll() {
    renderStats();
    renderRecentDocuments();
    renderDocuments();
    renderModel();
    renderHealth();
    if (state.overview) dom.defaultPasswordWarning.hidden = !state.overview.admin_password_is_default;
  }

  // ---------------------------------------------------------------- dashboard
  function renderStats() {
    const stats = state.stats;
    if (!stats) return;
    dom.statDocs.textContent = formatNumber(stats.document_count);
    dom.statReady.textContent = `${formatNumber(stats.ready_document_count)} 篇已索引`;
    dom.statChunks.textContent = formatNumber(stats.chunk_count);
    dom.statSessions.textContent = formatNumber(stats.session_count);
    dom.statMessages.textContent = `${formatNumber(stats.message_count)} 条消息`;
    dom.statCoverage.textContent = `${Math.round((stats.index_coverage || 0) * 100)}%`;
    dom.statIndexedAt.textContent = stats.last_indexed_at ? `最近索引 ${formatDate(stats.last_indexed_at)}` : "等待首次索引";
    dom.navDocCount.textContent = String(stats.document_count || 0);
  }

  function renderRecentDocuments() {
    const docs = state.docs.slice(0, 5);
    if (!docs.length) {
      dom.recentDocs.innerHTML = `<div class="empty-state" style="padding:30px 0"><strong>还没有文档</strong><p>前往文档管理上传第一份资料。</p></div>`;
      return;
    }
    dom.recentDocs.innerHTML = docs.map((doc) => `<div class="recent-row">
      <span class="recent-icon">${fileExt(doc).toUpperCase()}</span>
      <div class="recent-main"><strong>${escapeHtml(doc.title)}</strong><small>${formatNumber(doc.chunk_count)} 个片段 · ${formatDate(doc.updated_at)}</small></div>
      <span class="recent-status ${doc.status}">${statusLabel(doc.status)}</span>
    </div>`).join("");
  }

  function renderHealth() {
    const health = state.health;
    if (health) {
      const ok = health.status === "ok";
      dom.sidebarHealthDot.className = `dot ${ok ? "" : "warn"}`;
      dom.sidebarHealthText.textContent = ok ? "服务运行正常" : "部分服务降级";
    }
    const model = state.model;
    if (model) {
      const enabled = model.mode === "llm";
      dom.sidebarModelName.textContent = enabled ? model.model : "本地证据模式";
      dom.navModelDot.style.background = enabled ? "#6fd7b6" : "#c6a15a";
      dom.modelModePill.classList.toggle("demo", !enabled);
      dom.modelModePill.classList.toggle("off", false);
      dom.modelModePill.querySelector("span:last-child").textContent = enabled ? model.model : "本地证据模式";
    }
  }

  function renderModel() {
    const model = state.model;
    if (!model) return;
    const enabled = model.mode === "llm";
    dom.modelStatusChip.textContent = enabled ? "已连接" : "本地模式";
    dom.modelStatusChip.className = `status-chip ${enabled ? "ok" : "demo"}`;
    dom.modelSectionStatus.textContent = enabled ? "模型已启用" : "本地证据模式";
    dom.modelSectionStatus.className = `status-chip large ${enabled ? "ok" : "demo"}`;
    dom.dashboardModelName.textContent = model.model_label || "灵犀本地证据引擎";
    dom.dashboardModelProvider.textContent = providerLabel(model.provider);
    dom.dashboardBaseUrl.textContent = enabled ? (model.base_url || "—") : "未启用";
    dom.dashboardApiKey.textContent = enabled ? (model.api_key_set ? model.api_key_masked : "未配置") : "未配置";
    dom.dashboardMode.textContent = enabled ? "调用大模型生成" : "本地原文抽取";
    fillModelForm(model);
  }

  function fillModelForm(model) {
    const radio = document.querySelector(`input[name=provider][value="${model.provider}"]`);
    if (radio) radio.checked = true;
    dom.baseUrl.value = model.base_url || "";
    dom.modelName.value = model.model || "";
    dom.temperature.value = model.temperature;
    dom.maxTokens.value = model.max_tokens;
    dom.apiKey.value = "";
    dom.clearKey.checked = false;
    dom.keyState.textContent = model.api_key_set ? `已保存 ${model.api_key_masked}` : "未保存";
    dom.apiKey.placeholder = model.api_key_set ? "留空保持不变" : "请输入 API Key";
    updateProviderUi();
  }

  // ---------------------------------------------------------------- documents
  function renderDocuments() {
    const filtered = state.docs.filter((doc) => {
      const text = `${doc.title} ${doc.original_name}`.toLowerCase();
      return (!state.docQuery || text.includes(state.docQuery))
        && (state.docStatus === "all" || doc.status === state.docStatus);
    });
    dom.docCount.textContent = String(state.docs.length);
    dom.docList.hidden = filtered.length === 0;
    dom.docEmpty.hidden = filtered.length > 0;
    if (!filtered.length) {
      dom.docEmpty.innerHTML = state.docs.length
        ? `<span class="empty-icon">${icon("search")}</span><strong>没有匹配的文档</strong><p>尝试调整关键词或状态筛选。</p>`
        : `<span class="empty-icon">${icon("book")}</span><strong>知识库为空</strong><p>上传第一份文档，或载入演示资料。</p>`;
    }
    dom.docList.innerHTML = filtered.map((doc) => `<div class="doc-row">
      <div class="doc-main"><span class="doc-badge ${fileExt(doc)}">${fileExt(doc).toUpperCase()}</span><div class="doc-info"><strong>${escapeHtml(doc.title)}</strong><small>${doc.is_demo ? "演示资料 · " : ""}${escapeHtml(doc.original_name)}</small></div></div>
      <div><span class="doc-status ${doc.status}"><span class="dot"></span>${statusLabel(doc.status)}</span></div>
      <div class="doc-cell count">${formatNumber(doc.chunk_count)}</div>
      <div class="doc-cell">${formatBytes(doc.size_bytes)}</div>
      <div class="doc-cell">${timeAgo(doc.updated_at)}</div>
      <div class="doc-actions">
        <button class="doc-action" data-action="preview" data-id="${doc.id}" title="查看切片">${icon("search")}</button>
        <button class="doc-action" data-action="download" data-id="${doc.id}" title="下载原文">${icon("download")}</button>
        <button class="doc-action" data-action="reindex" data-id="${doc.id}" title="重建索引">${icon("refresh")}</button>
        <button class="doc-action danger" data-action="delete" data-id="${doc.id}" title="删除">${icon("trash")}</button>
      </div>
    </div>`).join("");
  }

  async function handleFiles(fileList) {
    if (state.uploading) return toast("已有文件正在上传", "info");
    const files = [...fileList];
    const allowed = [".pdf", ".docx", ".md", ".markdown", ".txt"];
    const valid = files.filter((file) => allowed.some((ext) => file.name.toLowerCase().endsWith(ext)) && file.size <= 25 * 1024 * 1024);
    if (!valid.length) return toast("请选择 PDF / DOCX / Markdown / TXT，且不超过 25 MB", "error");
    switchSection("documents");
    state.uploads = valid.map((file) => ({ name: file.name, state: "waiting" }));
    state.uploading = true;
    renderUploadProgress(0, "正在上传");
    const form = new FormData();
    valid.forEach((file) => form.append("files", file, file.name));
    try {
      const result = await uploadWithProgress(form, (percent) => renderUploadProgress(percent, "正在上传"));
      (result.documents || []).forEach((doc) => {
        const item = state.uploads.find((entry) => entry.name === doc.original_name && entry.state === "waiting");
        if (item) item.state = "ok";
      });
      (result.rejected || []).forEach((rejected) => {
        const item = state.uploads.find((entry) => entry.name === rejected.filename && entry.state === "waiting");
        if (item) { item.state = "fail"; item.reason = rejected.reason; }
      });
      state.uploads.forEach((entry) => { if (entry.state === "waiting") entry.state = "ok"; });
      renderUploadProgress(100, "上传完成");
      if (result.documents?.length) toast(`已接收 ${result.documents.length} 个文档，正在建索引`, "success");
      if (result.rejected?.length) toast(result.rejected[0].reason, "error", 4500);
      await refreshDocuments();
      await refreshStats();
      schedulePoll();
    } catch (error) {
      state.uploads.forEach((entry) => { if (entry.state === "waiting") { entry.state = "fail"; entry.reason = error.message; } });
      renderUploadProgress(0, "上传失败");
      toast(error.message, "error", 4500);
    } finally {
      state.uploading = false;
    }
  }

  function uploadWithProgress(form, progress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/documents/upload");
      if (state.token) xhr.setRequestHeader("X-Admin-Token", state.token);
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) progress(Math.round(event.loaded / event.total * 100));
      };
      xhr.onload = () => {
        let payload = null;
        try { payload = JSON.parse(xhr.responseText || "{}"); } catch { payload = null; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(payload);
        else {
          if (xhr.status === 401) { clearSession(); showLogin("管理员登录已过期"); }
          reject(new Error(payload?.detail || `上传失败（${xhr.status}）`));
        }
      };
      xhr.onerror = () => reject(new Error("网络错误，上传失败"));
      xhr.timeout = 180000;
      xhr.ontimeout = () => reject(new Error("上传超时"));
      xhr.send(form);
    });
  }

  function renderUploadProgress(percent, label) {
    dom.uploadProgress.hidden = false;
    dom.uploadLabel.textContent = label;
    dom.uploadPercent.textContent = `${percent}%`;
    dom.uploadBar.style.width = `${percent}%`;
    dom.uploadFiles.innerHTML = state.uploads.map((entry) => `<span class="upload-file ${entry.state === "ok" ? "ok" : entry.state === "fail" ? "fail" : ""}">${escapeHtml(entry.name)}${entry.reason ? ` · ${escapeHtml(shorten(entry.reason, 24))}` : ""}</span>`).join("");
  }

  async function handleDocAction(event) {
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const doc = state.docs.find((item) => item.id === button.dataset.id);
    if (!doc) return;
    if (button.dataset.action === "preview") return previewDocument(doc);
    if (button.dataset.action === "download") return downloadDocument(doc);
    if (button.dataset.action === "reindex") {
      try {
        await api(`/api/documents/${encodeURIComponent(doc.id)}/reindex`, { method: "POST" });
        toast("已提交重建索引任务", "success");
        await refreshDocuments();
        schedulePoll();
      } catch (error) { toast(error.message, "error"); }
      return;
    }
    if (button.dataset.action === "delete") {
      const confirmed = await askConfirm("删除文档？", `《${doc.title}》及其 ${doc.chunk_count} 个索引片段将被删除。`, "删除文档");
      if (!confirmed) return;
      try {
        await api(`/api/documents/${encodeURIComponent(doc.id)}`, { method: "DELETE" });
        toast("文档已删除", "success");
        await refreshDocuments();
        await refreshStats();
      } catch (error) { toast(error.message, "error"); }
    }
  }

  async function previewDocument(doc) {
    dom.docModal.hidden = false;
    dom.docModalTitle.textContent = doc.title;
    dom.chunkList.innerHTML = `<div class="empty-state"><p>正在读取切片…</p></div>`;
    try {
      const result = await api(`/api/documents/${encodeURIComponent(doc.id)}/chunks`);
      dom.chunkList.innerHTML = (result.chunks || []).map((chunk) => `<article class="chunk-card">
        <div class="chunk-head"><strong>#${chunk.ordinal + 1}${chunk.section ? ` · ${escapeHtml(chunk.section)}` : ""}</strong><span>${formatNumber(chunk.char_count)} 字${chunk.page ? ` · 第 ${chunk.page} 页` : ""}</span></div>
        <p>${escapeHtml(chunk.text)}</p>
      </article>`).join("") || `<div class="empty-state"><p>暂无切片</p></div>`;
    } catch (error) {
      dom.chunkList.innerHTML = `<div class="empty-state"><p>${escapeHtml(error.message)}</p></div>`;
    }
  }

  function closeDocModal() { dom.docModal.hidden = true; }

  async function downloadDocument(doc) {
    try {
      const headers = new Headers();
      if (state.token) headers.set("X-Admin-Token", state.token);
      const response = await fetch(`/api/documents/${encodeURIComponent(doc.id)}/download`, { headers });
      if (!response.ok) throw new Error("下载失败");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = doc.original_name;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1200);
    } catch (error) { toast(error.message, "error"); }
  }

  async function seedDocuments() {
    const hasDemo = state.docs.some((doc) => doc.is_demo);
    if (hasDemo) {
      const confirmed = await askConfirm("重新载入演示资料？", "现有演示文档会被替换，你上传的文档不受影响。", "重新载入");
      if (!confirmed) return;
    }
    dom.seedBtn.disabled = true;
    try {
      const result = await api(`/api/documents/seed?force=${hasDemo ? "true" : "false"}`, { method: "POST" });
      toast(result.message || "演示资料已载入", "success");
      await refreshDocuments();
      await refreshStats();
    } catch (error) { toast(error.message, "error"); }
    finally { dom.seedBtn.disabled = false; }
  }

  async function refreshDocuments() {
    try {
      state.docs = await api("/api/documents");
      renderDocuments();
      renderRecentDocuments();
    } catch (error) {
      if (error.status !== 401) toast(error.message, "error");
    }
  }

  async function refreshStats() {
    try {
      state.stats = await api("/api/stats");
      renderStats();
    } catch { /* ignore */ }
  }

  function schedulePoll() {
    if (!state.docs.some((doc) => doc.status === "processing")) {
      state.pollAttempts = 0;
      if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }
      return;
    }
    if (state.pollTimer || state.pollAttempts >= 80) return;
    state.pollTimer = setTimeout(async () => {
      state.pollTimer = null;
      state.pollAttempts += 1;
      await refreshDocuments();
      await refreshStats();
      schedulePoll();
    }, 1200);
  }

  // ------------------------------------------------------------------- model
  function updateProviderUi() {
    const provider = $("input[name=provider]:checked")?.value || "demo";
    const disabled = provider === "demo";
    dom.baseUrl.disabled = disabled;
    dom.modelName.disabled = disabled;
    dom.apiKey.disabled = disabled;
    dom.clearKey.disabled = disabled;
    dom.temperature.disabled = disabled;
    dom.maxTokens.disabled = disabled;
    dom.testModelBtn.disabled = disabled;
    if (provider === "openai") {
      if (!dom.baseUrl.value) dom.baseUrl.value = "https://api.deepseek.com/v1";
      if (!dom.modelName.value) dom.modelName.value = "deepseek-chat";
      dom.apiKey.placeholder = state.model?.api_key_set ? "留空保持不变" : "sk-...";
    } else if (provider === "ollama") {
      if (!dom.baseUrl.value || dom.baseUrl.value.includes("api.deepseek.com")) dom.baseUrl.value = "http://127.0.0.1:11434/v1";
      if (!dom.modelName.value || dom.modelName.value === "deepseek-chat") dom.modelName.value = "qwen2.5:7b";
      dom.apiKey.placeholder = "本地通常不需要 API Key";
    } else {
      dom.apiKey.placeholder = "本地证据模式不需要 API Key";
    }
  }

  function applyExample(name) {
    if (name === "deepseek") {
      selectProvider("openai"); dom.baseUrl.value = "https://api.deepseek.com/v1"; dom.modelName.value = "deepseek-chat";
    } else if (name === "openai") {
      selectProvider("openai"); dom.baseUrl.value = "https://api.openai.com/v1"; dom.modelName.value = "gpt-4o-mini";
    } else if (name === "ollama") {
      selectProvider("ollama"); dom.baseUrl.value = "http://127.0.0.1:11434/v1"; dom.modelName.value = "qwen2.5:7b";
    }
    updateProviderUi();
  }

  function selectProvider(provider) {
    const radio = document.querySelector(`input[name=provider][value="${provider}"]`);
    if (radio) radio.checked = true;
  }

  async function saveModel(event) {
    event.preventDefault();
    const body = modelFormBody();
    if (!body.provider) return toast("请选择接入方式", "error");
    if (body.provider !== "demo" && (!body.base_url || !body.model)) return toast("API 地址和模型名称不能为空", "error");
    dom.saveModelBtn.disabled = true;
    try {
      state.model = await api("/api/admin/model", { method: "PUT", body: JSON.stringify(body) });
      renderModel();
      toast("模型配置已保存并立即生效", "success");
      state.health = await api("/api/health", {}, false);
      renderHealth();
      await loadOverview();
    } catch (error) { toast(error.message, "error", 4500); }
    finally { dom.saveModelBtn.disabled = false; }
  }

  async function testModel() {
    const body = modelFormBody(true);
    dom.testModelBtn.disabled = true;
    dom.testModelBtn.textContent = "测试中…";
    dom.testResult.hidden = false;
    dom.testResult.className = "test-result";
    dom.testResult.textContent = "正在连接模型服务…";
    try {
      const result = await api("/api/admin/model/test", { method: "POST", body: JSON.stringify(body) });
      dom.testResult.className = `test-result ${result.available ? "ok" : "fail"}`;
      const modelText = result.models?.length ? `<br><code>${escapeHtml(result.models.slice(0, 6).join(" · "))}</code>` : "";
      dom.testResult.innerHTML = `${escapeHtml(result.message)} <small>(${result.latency_ms} ms)</small>${modelText}`;
    } catch (error) {
      dom.testResult.className = "test-result fail";
      dom.testResult.textContent = error.message;
    } finally {
      dom.testModelBtn.disabled = false;
      dom.testModelBtn.textContent = "测试连接";
    }
  }

  function modelFormBody(includeKey = true) {
    const provider = $("input[name=provider]:checked")?.value || "demo";
    const body = {
      provider,
      base_url: dom.baseUrl.value.trim(),
      model: dom.modelName.value.trim(),
      temperature: Number(dom.temperature.value || 0.2),
      max_tokens: Number(dom.maxTokens.value || 1400),
      clear_api_key: dom.clearKey.checked,
    };
    if (includeKey && dom.apiKey.value.trim()) body.api_key = dom.apiKey.value.trim();
    return body;
  }

  async function loadOverview() {
    try {
      state.overview = await api("/api/admin/overview");
      dom.defaultPasswordWarning.hidden = !state.overview.admin_password_is_default;
    } catch { /* ignore */ }
  }

  // ------------------------------------------------------------------- views
  function go(section) {
    switchSection(section);
    closeSidebar();
  }

  function switchSection(section) {
    const allowed = ["dashboard", "documents", "model"];
    if (!allowed.includes(section)) section = "dashboard";
    dom.sections.forEach((node) => node.hidden = node.id !== `${section}Section`);
    dom.navItems.forEach((button) => button.classList.toggle("active", button.dataset.section === section));
    dom.sectionTitle.textContent = { dashboard: "运营概览", documents: "知识库文档", model: "模型接入" }[section];
    if (section === "documents") refreshDocuments();
  }

  function openSidebar() {
    dom.adminSidebar.classList.add("open");
    dom.mobileScrim.hidden = false;
  }

  function closeSidebar() {
    dom.adminSidebar.classList.remove("open");
    dom.mobileScrim.hidden = true;
  }

  // ------------------------------------------------------------------ modals
  function askConfirm(title, message, confirmText) {
    dom.confirmTitle.textContent = title;
    dom.confirmMessage.textContent = message;
    dom.confirmOk.textContent = confirmText || "确认删除";
    dom.confirmModal.hidden = false;
    return new Promise((resolve) => state.confirmResolver = resolve);
  }

  function resolveConfirm(value) {
    dom.confirmModal.hidden = true;
    const resolver = state.confirmResolver;
    state.confirmResolver = null;
    if (resolver) resolver(value);
  }

  function toast(message, type = "success", duration = 3200) {
    const node = document.createElement("div");
    node.className = `toast ${type}`;
    node.innerHTML = `${icon(type === "error" ? "alert" : type === "info" ? "sparkles" : "check")}<span></span>`;
    $("span", node).textContent = message;
    dom.toastRoot.appendChild(node);
    while (dom.toastRoot.children.length > 4) dom.toastRoot.firstElementChild.remove();
    setTimeout(() => {
      node.classList.add("leaving");
      setTimeout(() => node.remove(), 200);
    }, duration);
  }

  // ----------------------------------------------------------------- helpers
  function providerLabel(provider) {
    return { openai: "第三方 OpenAI 兼容 API", ollama: "本地 LLM / Ollama", demo: "本地证据模式" }[provider] || provider;
  }

  function statusLabel(status) {
    return { ready: "已索引", processing: "索引中", failed: "失败" }[status] || status;
  }

  function fileExt(doc) {
    const ext = (doc.extension || "").replace(".", "").toLowerCase();
    if (ext === "docx") return "docx";
    if (ext === "markdown") return "md";
    return ["pdf", "md", "txt"].includes(ext) ? ext : "txt";
  }

  function parseDate(value) {
    if (!value) return null;
    let text = String(value);
    if (!/[zZ]|[+-]\d{2}:\d{2}$/.test(text)) text += "Z";
    const date = new Date(text);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function formatDate(value) {
    const date = parseDate(value);
    if (!date) return "—";
    return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  }

  function timeAgo(value) {
    const date = parseDate(value);
    if (!date) return "—";
    const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
    if (seconds < 60) return "刚刚";
    if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
    if (seconds < 86400 * 7) return `${Math.floor(seconds / 86400)} 天前`;
    return `${date.getMonth() + 1}月${date.getDate()}日`;
  }

  function formatNumber(value) { return Number(value || 0).toLocaleString("zh-CN"); }

  function formatBytes(bytes) {
    const value = Number(bytes || 0);
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  }

  function shorten(value, max) {
    const text = String(value || "");
    return text.length > max ? `${text.slice(0, max)}…` : text;
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    })[character]);
  }
})();
