(() => {
  "use strict";

  const API_KEY_STORAGE = "lingxi.api-key";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const icon = (name) => `<svg aria-hidden="true"><use href="#i-${name}"></use></svg>`;

  const state = {
    view: "chat",
    sessionId: null,
    sessions: [],
    messages: [],
    documents: [],
    stats: null,
    info: null,
    health: null,
    citations: [],
    tools: [],
    webSearch: false,
    streaming: false,
    abortController: null,
    currentAssistant: null,
    currentAssistantArticle: null,
    completedStream: false,
    uploads: [],
    uploading: false,
    pollTimer: null,
    pollAttempts: 0,
    currentDocument: null,
    activeInspectorTab: "citations",
    docQuery: "",
    docStatus: "all",
    feedback: new Map(),
    confirmResolver: null,
    renameSessionId: null,
    bootstrapped: false,
  };

  const dom = {};

  document.addEventListener("DOMContentLoaded", init);

  async function init() {
    cacheDom();
    bindEvents();
    applyRouteFromHash(false);
    restoreLocalSettings();
    setDefaultSurface();
    await bootstrapData();
    autoResizeInput();
    dom.messageInput.focus();
  }

  function cacheDom() {
    const ids = [
      "sidebar", "sidebarScrim", "sidebarCloseBtn", "mobileMenuBtn", "newChatBtn",
      "sessionList", "refreshSessionsBtn", "settingsBtn", "environmentLabel",
      "environmentMeta", "systemBadge", "systemBadgeText", "viewTitle",
      "breadcrumbLabel", "quickUploadBtn", "inspectorToggleBtn", "chatView",
      "knowledgeView", "chatScroll", "welcome", "messageList", "streamStatus",
      "streamStatusText", "streamToolCount", "chatForm", "messageInput",
      "filePicker", "attachBtn", "webToggle", "sendBtn", "sourcesNoteBtn",
      "inspector", "inspectorCloseBtn", "inspectorScrim", "citationsPane",
      "toolsPane", "citationList", "citationsEmpty", "toolList", "toolsEmpty",
      "citationCount", "toolCount", "inspectorKbStat", "openKnowledgeFromInspector",
      "statDocs", "statReadyDocs", "statChunks", "statSessions", "statMessages",
      "statCoverage", "statLastIndexed", "navDocCount", "maxUploadLabel",
      "dropzone", "uploadInput", "browseBtn", "uploadMainBtn", "quickUploadBtn",
      "emptyUploadBtn", "uploadQueue", "uploadQueueLabel", "uploadProgressText",
      "uploadProgressBar", "uploadQueueList", "seedDocumentsBtn", "documentSearch",
      "statusFilter", "documentList", "documentsEmpty", "documentCountLabel",
      "settingsModal", "settingsForm", "apiKeyInput", "clearKeyBtn", "modeBadge",
      "serviceGrid", "settingsModel", "settingsEmbedding", "settingsRetrieval",
      "settingsWeb", "settingsUpload", "confirmModal", "confirmTitle",
      "confirmMessage", "confirmCancelBtn", "confirmOkBtn", "renameModal",
      "renameForm", "renameInput", "documentModal", "docModalTitle", "docModalMeta",
      "docModalDownload", "docModalReindex", "docChunks", "toastRoot",
    ];
    ids.forEach((id) => {
      dom[id] = document.getElementById(id);
    });
    dom.suggestionCards = $$(".suggestion-card");
    dom.navItems = $$(".nav-item");
    dom.inspectorTabs = $$(".inspector-tab");
  }

  function bindEvents() {
    window.addEventListener("hashchange", () => applyRouteFromHash(false));
    window.addEventListener("beforeunload", () => {
      if (state.abortController) state.abortController.abort();
    });
    window.addEventListener("online", () => showToast("网络已恢复", "success"));
    window.addEventListener("offline", () => showToast("网络连接已断开，部分操作可能失败", "error"));

    dom.mobileMenuBtn.addEventListener("click", openSidebar);
    dom.sidebarCloseBtn.addEventListener("click", closeSidebar);
    dom.sidebarScrim.addEventListener("click", closeSidebar);
    dom.newChatBtn.addEventListener("click", newChat);
    dom.navItems.forEach((item) => item.addEventListener("click", () => switchView(item.dataset.view)));
    dom.refreshSessionsBtn.addEventListener("click", () => refreshSessions(true));
    dom.sessionList.addEventListener("click", handleSessionListClick);
    dom.settingsBtn.addEventListener("click", openSettings);

    dom.chatForm.addEventListener("submit", handleComposerSubmit);
    dom.messageInput.addEventListener("input", autoResizeInput);
    dom.messageInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        dom.chatForm.requestSubmit();
      }
    });
    dom.attachBtn.addEventListener("click", () => dom.filePicker.click());
    dom.filePicker.addEventListener("change", () => {
      handleFiles(dom.filePicker.files);
      dom.filePicker.value = "";
    });
    dom.webToggle.addEventListener("click", () => {
      if (dom.webToggle.disabled) return;
      state.webSearch = !state.webSearch;
      dom.webToggle.setAttribute("aria-pressed", String(state.webSearch));
      showToast(state.webSearch ? "本次提问将补充联网搜索" : "已关闭联网搜索", "info", 1800);
    });
    dom.quickUploadBtn.addEventListener("click", () => {
      switchView("knowledge");
      setTimeout(() => dom.uploadInput.click(), 120);
    });
    dom.sourcesNoteBtn.addEventListener("click", () => switchView("knowledge"));
    dom.suggestionCards.forEach((card) => {
      card.addEventListener("click", () => {
        const question = card.dataset.question;
        if (state.streaming) {
          showToast("请先停止当前回答", "info");
          return;
        }
        switchView("chat");
        dom.messageInput.value = question;
        autoResizeInput();
        sendMessage(question);
      });
    });
    dom.messageList.addEventListener("click", handleMessageClick);

    dom.inspectorToggleBtn.addEventListener("click", () => {
      if (dom.inspector.classList.contains("open")) closeInspector();
      else openInspector();
    });
    dom.inspectorCloseBtn.addEventListener("click", closeInspector);
    dom.inspectorScrim.addEventListener("click", closeInspector);
    dom.inspectorTabs.forEach((tab) => {
      tab.addEventListener("click", () => setInspectorTab(tab.dataset.tab));
    });
    dom.openKnowledgeFromInspector.addEventListener("click", () => {
      closeInspector();
      switchView("knowledge");
    });

    dom.browseBtn.addEventListener("click", () => dom.uploadInput.click());
    dom.uploadMainBtn.addEventListener("click", () => dom.uploadInput.click());
    dom.quickUploadBtn.dataset.bound = "true";
    dom.emptyUploadBtn.addEventListener("click", () => dom.uploadInput.click());
    dom.uploadInput.addEventListener("change", () => {
      handleFiles(dom.uploadInput.files);
      dom.uploadInput.value = "";
    });
    dom.dropzone.addEventListener("click", (event) => {
      if (event.target.closest("button") || event.target.closest(".upload-queue")) return;
      dom.uploadInput.click();
    });
    ["dragenter", "dragover"].forEach((name) => {
      dom.dropzone.addEventListener(name, (event) => {
        event.preventDefault();
        dom.dropzone.classList.add("dragover");
      });
    });
    ["dragleave", "drop"].forEach((name) => {
      dom.dropzone.addEventListener(name, (event) => {
        event.preventDefault();
        if (name === "dragleave" && dom.dropzone.contains(event.relatedTarget)) return;
        dom.dropzone.classList.remove("dragover");
      });
    });
    dom.dropzone.addEventListener("drop", (event) => {
      if (event.dataTransfer?.files?.length) handleFiles(event.dataTransfer.files);
    });
    dom.seedDocumentsBtn.addEventListener("click", seedDocuments);
    dom.documentSearch.addEventListener("input", () => {
      state.docQuery = dom.documentSearch.value.trim().toLowerCase();
      renderDocuments();
    });
    dom.statusFilter.addEventListener("change", () => {
      state.docStatus = dom.statusFilter.value;
      renderDocuments();
    });
    dom.documentList.addEventListener("click", handleDocumentAction);
    dom.docModalDownload.addEventListener("click", () => {
      if (state.currentDocument) downloadDocument(state.currentDocument);
    });
    dom.docModalReindex.addEventListener("click", () => {
      if (!state.currentDocument) return;
      const doc = state.currentDocument;
      closeDocumentModal();
      reindexDocument(doc);
    });

    dom.settingsForm.addEventListener("submit", saveSettings);
    dom.clearKeyBtn.addEventListener("click", clearApiKey);
    dom.confirmCancelBtn.addEventListener("click", () => resolveConfirm(false));
    dom.confirmOkBtn.addEventListener("click", () => resolveConfirm(true));
    dom.renameForm.addEventListener("submit", submitRename);

    document.addEventListener("click", (event) => {
      if (!event.target.closest(".session-more") && !event.target.closest(".session-menu")) {
        closeSessionMenus();
      }
      const closeSelector = event.target.closest("[data-close-modal]");
      if (closeSelector) closeSettings();
      if (event.target.closest("[data-close-confirm]")) resolveConfirm(false);
      if (event.target.closest("[data-close-rename]")) closeRename();
      if (event.target.closest("[data-close-doc-modal]")) closeDocumentModal();
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        if (!dom.confirmModal.hidden) {
          resolveConfirm(false);
          return;
        }
        if (!dom.documentModal.hidden) {
          closeDocumentModal();
          return;
        }
        if (!dom.renameModal.hidden) {
          closeRename();
          return;
        }
        if (!dom.settingsModal.hidden) {
          closeSettings();
          return;
        }
        closeSidebar();
        closeInspector();
        closeSessionMenus();
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        const tag = document.activeElement?.tagName;
        if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
        event.preventDefault();
        newChat();
      }
    });
  }

  // ------------------------------------------------------------------- API
  function getApiKey() {
    return localStorage.getItem(API_KEY_STORAGE) || "";
  }

  function authHeaders(extra = {}) {
    const headers = new Headers(extra);
    const key = getApiKey();
    if (key) headers.set("X-API-Key", key);
    return headers;
  }

  async function api(path, options = {}) {
    const headers = authHeaders(options.headers || {});
    if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    let response;
    try {
      response = await fetch(path, { ...options, headers });
    } catch (error) {
      throw new Error("无法连接服务，请检查后端是否已启动");
    }
    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
      ? await response.json().catch(() => null)
      : await response.text().catch(() => "");
    if (!response.ok) {
      const detail = payload && typeof payload === "object" ? payload.detail : payload;
      const message = Array.isArray(detail)
        ? detail.map((item) => item.msg || item).join("；")
        : detail || `请求失败（${response.status}）`;
      const error = new Error(message);
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  }

  async function bootstrapData() {
    if (!state.bootstrapped) {
      dom.sessionList.innerHTML = `<div class="session-skeleton"><span></span><span></span><span></span></div>`;
    }
    try {
      const info = await api("/api/info");
      state.info = info;
      applyInfo();
    } catch (error) {
      if (error.status === 401) {
        openSettings();
        showToast("当前服务需要 API Key，请在设置中填写", "info", 4200);
      } else {
        showToast(error.message, "error");
        setServiceBadge("offline");
      }
    }

    const results = await Promise.allSettled([
      api("/api/stats"),
      api("/api/sessions"),
      api("/api/health"),
    ]);
    if (results[0].status === "fulfilled") state.stats = results[0].value;
    if (results[1].status === "fulfilled") state.sessions = results[1].value;
    if (results[2].status === "fulfilled") state.health = results[2].value;

    state.bootstrapped = true;
    renderStats();
    renderDocuments();
    renderSessions();
    renderSystemState();
    renderInspector();
    applySettingsInfo();
    scheduleDocumentPoll();
    if (state.health) {
      setServiceBadge(state.health.status === "ok" ? "ok" : "warn");
    } else if (state.info) {
      setServiceBadge("warn");
    }
  }

  // ---------------------------------------------------------------- rendering
  function setDefaultSurface() {
    dom.inspectorKbStat.textContent = "0 篇 · 0 片段";
    dom.statLastIndexed.textContent = "等待首次索引";
    dom.maxUploadLabel.textContent = "25";
    dom.envSafe = null;
    renderInspector();
    renderStats();
  }

  function applyInfo() {
    const info = state.info;
    if (!info) return;
    dom.maxUploadLabel.textContent = String(info.limits?.max_upload_mb || 25);
    dom.settingsUpload.textContent = `${info.limits?.max_upload_mb || 25} MB`;
    dom.settingsRetrieval.textContent = `${info.limits?.chunk_size || 512} 字 / ${info.limits?.chunk_overlap || 64} 重叠 · Top ${info.limits?.retrieval_top_k || 5}`;
    dom.settingsModel.textContent = info.model || "—";
    dom.settingsEmbedding.textContent = info.embedding === "local"
      ? "本地哈希 N-gram（离线）"
      : info.embedding || "—";
    dom.settingsWeb.textContent = info.web_search_enabled ? "已启用" : "未启用";
    dom.modeBadge.textContent = info.mode === "llm" ? "大模型增强" : "本地证据模式";
    dom.environmentLabel.textContent = info.mode === "llm" ? "大模型已连接" : "本地演示环境";
    dom.environmentMeta.textContent = `${shorten(info.vector_store, 16)} · SSE · v${info.version}`;
    dom.webToggle.disabled = !info.web_search_enabled;
    dom.webToggle.title = info.web_search_enabled ? "本次回答允许联网补充外部信息" : "联网搜索未启用";
    if (!info.web_search_enabled && state.webSearch) {
      state.webSearch = false;
      dom.webToggle.setAttribute("aria-pressed", "false");
    }
    const offLabel = dom.webToggle.querySelector(".web-off");
    if (offLabel) offLabel.textContent = info.web_search_enabled ? "可选" : "未启用";
    applySettingsInfo();
  }

  function applySettingsInfo() {
    const info = state.info;
    if (!info) return;
    dom.modeBadge.textContent = info.mode === "llm" ? "大模型增强" : "本地证据模式";
    dom.serviceGrid.innerHTML = [
      serviceItem("database", "文档与会话存储", info.database),
      serviceItem("layers", "向量索引", info.vector_store),
      serviceItem("chat", "会话上下文", info.session_store),
      serviceItem("sparkles", "生成模型", info.model),
    ].join("");
    dom.settingsModel.textContent = info.model || "—";
    dom.settingsEmbedding.textContent = info.embedding === "local" ? "本地哈希 N-gram（离线）" : info.embedding;
    dom.settingsRetrieval.textContent = `${info.limits?.chunk_size || 512} 字 / ${info.limits?.chunk_overlap || 64} 重叠 · Top ${info.limits?.retrieval_top_k || 5}`;
    dom.settingsWeb.textContent = info.web_search_enabled ? "已启用" : "未启用";
    dom.settingsUpload.textContent = `${info.limits?.max_upload_mb || 25} MB`;
  }

  function serviceItem(iconName, label, value) {
    return `<div class="service-item">
      <span class="service-icon">${icon(iconName)}</span>
      <div><span>${escapeHtml(label)}</span><strong title="${escapeHtml(value || "—")}">${escapeHtml(value || "—")}</strong></div>
    </div>`;
  }

  function renderSystemState() {
    if (state.health) {
      const ok = state.health.status === "ok";
      setServiceBadge(ok ? "ok" : "warn");
      if (!ok) {
        dom.environmentMeta.textContent = `数据库 ${state.health.database} · 向量库 ${state.health.vector_store}`;
      }
    }
    if (state.info) {
      dom.environmentLabel.textContent = state.info.mode === "llm" ? "大模型已连接" : "本地演示环境";
    }
  }

  function setServiceBadge(kind) {
    const dot = dom.systemBadge.querySelector(".status-dot");
    dot.className = "status-dot";
    if (kind === "ok") {
      dot.classList.add("status-dot");
      dom.systemBadgeText.textContent = "服务正常";
      dom.systemBadge.style.opacity = "1";
    } else if (kind === "warn") {
      dot.classList.add("warn");
      dom.systemBadgeText.textContent = "部分降级";
      dom.systemBadge.style.opacity = "1";
    } else {
      dot.classList.add("error");
      dom.systemBadgeText.textContent = "连接异常";
      dom.systemBadge.style.opacity = "1";
    }
  }

  function renderStats() {
    const stats = state.stats;
    if (!stats) return;
    dom.statDocs.textContent = formatNumber(stats.document_count);
    dom.statReadyDocs.textContent = `${formatNumber(stats.ready_document_count)} 篇已完成索引`;
    dom.statChunks.textContent = formatNumber(stats.chunk_count);
    dom.statSessions.textContent = formatNumber(stats.session_count);
    dom.statMessages.textContent = `${formatNumber(stats.message_count)} 条消息`;
    dom.statCoverage.textContent = `${Math.round((stats.index_coverage || 0) * 100)}%`;
    dom.statLastIndexed.textContent = stats.last_indexed_at
      ? `最后索引 ${formatDateTime(stats.last_indexed_at)}`
      : "等待首次索引";
    dom.navDocCount.textContent = String(stats.document_count || 0);
    dom.inspectorKbStat.textContent = `${formatNumber(stats.document_count)} 篇 · ${formatNumber(stats.chunk_count)} 片段`;
  }

  function renderSessions() {
    if (!state.sessions.length) {
      dom.sessionList.innerHTML = `<div class="session-empty">还没有对话记录<br>从一个问题开始吧</div>`;
      return;
    }
    dom.sessionList.innerHTML = state.sessions.map((session) => {
      const active = session.id === state.sessionId;
      const preview = session.preview || `${session.message_count || 0} 条消息`;
      return `<div class="session-item${active ? " active" : ""}" data-session-id="${escapeHtml(session.id)}">
        <button class="session-open" data-session-action="open" title="${escapeHtml(session.title)}">
          <strong>${escapeHtml(session.title || "新对话")}</strong>
          <small>${escapeHtml(shorten(preview, 28))} · ${timeAgo(session.updated_at)}</small>
        </button>
        <button class="session-more" data-session-action="menu" aria-label="会话操作">${icon("more")}</button>
        <div class="session-menu" hidden>
          <button data-session-action="rename">${icon("edit")}重命名</button>
          <button data-session-action="delete" class="danger">${icon("trash")}删除对话</button>
        </div>
      </div>`;
    }).join("");
  }

  function renderDocuments() {
    const all = state.documents;
    const filtered = all.filter((doc) => {
      const matchesQuery = !state.docQuery
        || doc.title.toLowerCase().includes(state.docQuery)
        || doc.original_name.toLowerCase().includes(state.docQuery);
      const matchesStatus = state.docStatus === "all" || doc.status === state.docStatus;
      return matchesQuery && matchesStatus;
    });
    dom.documentCountLabel.textContent = String(all.length);
    dom.navDocCount.textContent = String(all.length);
    dom.documentsEmpty.hidden = filtered.length > 0;
    dom.documentList.hidden = filtered.length === 0;
    if (!filtered.length) {
      if (!all.length) {
        dom.documentsEmpty.innerHTML = `<div class="empty-icon">${icon("book")}</div>
          <strong>知识库还是空的</strong>
          <p>上传第一份文档，或载入内置演示资料快速体验。</p>
          <button class="btn btn-primary" id="emptyUploadBtn">上传文档</button>`;
      } else {
        dom.documentsEmpty.innerHTML = `<div class="empty-icon">${icon("search")}</div>
          <strong>没有匹配的文档</strong>
          <p>换一个关键词，或调整状态筛选。</p>`;
      }
      const upload = $("#emptyUploadBtn");
      if (upload) upload.addEventListener("click", () => dom.uploadInput.click());
      return;
    }

    dom.documentList.innerHTML = filtered.map((doc) => {
      const ext = fileExtension(doc);
      const status = statusMeta(doc.status);
      const error = doc.error ? `<span class="doc-error" title="${escapeHtml(doc.error)}">${escapeHtml(shorten(doc.error, 26))}</span>` : "";
      return `<div class="doc-row" data-document-id="${escapeHtml(doc.id)}">
        <div class="doc-main">
          <span class="file-badge ${ext.key}">${ext.label}</span>
          <div class="doc-info">
            <strong title="${escapeHtml(doc.title)}">${escapeHtml(doc.title || doc.original_name)}</strong>
            <small title="${escapeHtml(doc.original_name)}">${doc.is_demo ? "演示资料 · " : ""}${escapeHtml(shorten(doc.original_name, 34))}${doc.page_count ? ` · ${doc.page_count} 页` : ""}</small>
            ${error}
          </div>
        </div>
        <div><span class="doc-status ${doc.status}"><span class="dot"></span>${status.label}</span></div>
        <div class="doc-cell"><strong>${formatNumber(doc.chunk_count || 0)}</strong></div>
        <div class="doc-cell">${formatBytes(doc.size_bytes)}</div>
        <div class="doc-cell">${timeAgo(doc.updated_at)}</div>
        <div class="doc-actions">
          <button class="doc-action" data-doc-action="preview" data-doc-id="${escapeHtml(doc.id)}" title="查看切片">${icon("layers")}</button>
          <button class="doc-action" data-doc-action="download" data-doc-id="${escapeHtml(doc.id)}" title="下载原文">${icon("download")}</button>
          <button class="doc-action" data-doc-action="reindex" data-doc-id="${escapeHtml(doc.id)}" title="重建索引">${icon("refresh")}</button>
          <button class="doc-action danger" data-doc-action="delete" data-doc-id="${escapeHtml(doc.id)}" title="删除文档">${icon("trash")}</button>
        </div>
      </div>`;
    }).join("");
  }

  function renderInspector() {
    const citations = state.citations || [];
    const tools = state.tools || [];
    dom.citationCount.textContent = String(citations.length);
    dom.toolCount.textContent = String(tools.length);
    dom.citationsEmpty.hidden = citations.length > 0;
    dom.toolsEmpty.hidden = tools.length > 0;
    dom.citationList.innerHTML = citations.map((citation) => {
      const location = citation.section || (citation.page ? `第 ${citation.page} 页` : "");
      const score = Math.round((citation.score || 0) * 100);
      return `<article class="citation-card" data-citation-card="${citation.index}">
        <div class="citation-top">
          <span class="citation-index">${citation.index}</span>
          <span class="citation-doc" title="${escapeHtml(citation.title)}">${escapeHtml(citation.title)}</span>
          <span class="citation-score">${score}%</span>
        </div>
        <div class="citation-meta">${icon("file")}<span>${escapeHtml(shorten(citation.filename || "", 22))}${location ? ` · ${escapeHtml(shorten(location, 20))}` : ""}</span></div>
        <p class="citation-snippet">${escapeHtml(citation.snippet || citation.text)}</p>
        <details>
          <summary>查看完整片段</summary>
          <div class="citation-full">${escapeHtml(citation.text)}</div>
        </details>
        <div class="score-bar"><span style="width:${Math.max(4, Math.min(100, score))}%"></span></div>
      </article>`;
    }).join("");

    dom.toolList.innerHTML = tools.map((tool) => {
      const status = toolStatusMeta(tool.status);
      const metaChips = [];
      if (tool.meta?.candidates != null) metaChips.push(`候选 ${tool.meta.candidates}`);
      if (tool.meta?.confidence != null) metaChips.push(`相关度 ${Number(tool.meta.confidence).toFixed(2)}`);
      if (tool.meta?.row_count != null) metaChips.push(`${tool.meta.row_count} 行`);
      if (Array.isArray(tool.meta?.results) && tool.meta.results.length) metaChips.push(`${tool.meta.results.length} 条结果`);
      if (tool.meta?.query) metaChips.push(shorten(String(tool.meta.query), 34));
      return `<article class="tool-card">
        <div class="tool-top">
          <span class="tool-icon">${icon(toolIcon(tool.name))}</span>
          <span class="tool-name">${escapeHtml(tool.label || tool.name)}</span>
          <span class="tool-status ${tool.status}"><span class="dot"></span>${status.label}</span>
          <span class="tool-time">${tool.duration_ms ? `${tool.duration_ms}ms` : ""}</span>
        </div>
        ${tool.output ? `<p class="tool-output">${escapeHtml(tool.output)}</p>` : ""}
        ${metaChips.length ? `<div class="tool-meta">${metaChips.map((chip) => `<span>${escapeHtml(chip)}</span>`).join("")}</div>` : ""}
      </article>`;
    }).join("");
  }

  function renderMessages() {
    dom.messageList.innerHTML = "";
    if (!state.messages.length) {
      dom.welcome.hidden = false;
      return;
    }
    dom.welcome.hidden = true;
    state.messages.forEach((message) => {
      dom.messageList.appendChild(buildMessageElement(message));
    });
  }

  function buildMessageElement(message, options = {}) {
    const article = document.createElement("article");
    article.className = `message ${message.role}`;
    if (message.id) article.dataset.id = message.id;
    const avatar = message.role === "assistant" ? icon("logo") : icon("user");
    const meta = message.role === "assistant"
      ? `<strong>灵犀</strong><span class="model-label">${escapeHtml(options.model || state.info?.model || "本地证据引擎")}</span>`
      : `<strong>你</strong>`;
    article.innerHTML = `
      <div class="message-avatar">${avatar}</div>
      <div class="message-main">
        <div class="message-meta">${meta}<span>${escapeHtml(timeOnly(message.created_at))}</span></div>
        <div class="message-body ${message.role === "assistant" ? "markdown" : ""}"></div>
      </div>`;
    const body = $(".message-body", article);
    if (message.role === "user") {
      body.textContent = message.content || "";
    } else if (message.content) {
      body.innerHTML = renderMarkdown(message.content);
    } else {
      body.innerHTML = thinkingHtml("正在思考");
    }
    if (message.role === "assistant" && message.content) {
      appendAssistantExtras(article, message, options.suggestions || []);
    }
    if (message.role === "assistant" && message.error) body.classList.add("error");
    return article;
  }

  function appendAssistantExtras(article, message, suggestions = []) {
    $(".message-footer", article)?.remove();
    $(".message-actions", article)?.remove();
    $(".followups", article)?.remove();
    const main = $(".message-main", article);
    const citations = message.citations || [];
    const tools = message.tools || [];
    const footer = document.createElement("div");
    footer.className = "message-footer";
    const sourceGroups = [];
    citations.forEach((citation) => {
      let group = sourceGroups.find((item) => item.documentId === citation.document_id);
      if (!group) {
        group = {
          documentId: citation.document_id,
          title: citation.title,
          firstIndex: citation.index,
          count: 0,
        };
        sourceGroups.push(group);
      }
      group.count += 1;
    });
    sourceGroups.slice(0, 3).forEach((group) => {
      const button = document.createElement("button");
      button.className = "source-pill";
      button.dataset.citation = String(group.firstIndex);
      button.title = `${group.title}${group.count > 1 ? ` · ${group.count} 个片段` : ""}`;
      button.innerHTML = `${icon("file")}<span>${escapeHtml(shorten(group.title, 19))}${group.count > 1 ? ` · ${group.count}` : ""}</span>`;
      footer.appendChild(button);
    });
    if (sourceGroups.length > 3) {
      const more = document.createElement("span");
      more.className = "source-pill";
      more.innerHTML = `<span>+${sourceGroups.length - 3} 篇文档</span>`;
      footer.appendChild(more);
    }
    if (tools.length) {
      const button = document.createElement("button");
      button.className = "tool-summary-btn";
      button.dataset.openTools = "true";
      const success = tools.filter((tool) => tool.status === "success").length;
      button.innerHTML = `${icon("sparkles")}<span>${tools.length} 次工具调用${success ? ` · ${success} 成功` : ""}</span>`;
      footer.appendChild(button);
    }
    if (footer.children.length) main.appendChild(footer);

    const actions = document.createElement("div");
    actions.className = "message-actions";
    const copy = document.createElement("button");
    copy.className = "message-action";
    copy.dataset.copyMessage = "true";
    copy.title = "复制回答";
    copy.setAttribute("aria-label", "复制回答");
    copy.innerHTML = icon("copy");
    actions.appendChild(copy);
    if (message.role === "assistant" && message.id) {
      [-1, 1].forEach((rating) => {
        const button = document.createElement("button");
        button.className = "message-action feedback-btn";
        button.dataset.feedback = String(rating);
        button.dataset.messageId = message.id;
        button.title = rating === 1 ? "回答有帮助" : "回答需改进";
        button.innerHTML = icon(rating === 1 ? "thumb-up" : "thumb-down");
        if (state.feedback.get(`${message.id}:${rating}`)) button.classList.add("active");
        actions.appendChild(button);
      });
    }
    main.appendChild(actions);

    if (suggestions.length) {
      const followups = document.createElement("div");
      followups.className = "followups";
      suggestions.forEach((suggestion) => {
        const button = document.createElement("button");
        button.className = "followup-btn";
        button.dataset.question = suggestion;
        button.textContent = suggestion;
        followups.appendChild(button);
      });
      main.appendChild(followups);
    }
  }

  function thinkingHtml(label) {
    return `<div class="thinking-block"><span class="thinking-dots"><i></i><i></i><i></i></span><span>${escapeHtml(label)}</span></div>`;
  }

  // ------------------------------------------------------------------ views
  function switchView(view, updateHash = true) {
    view = "chat";
    state.view = view;
    const isChat = true;
    dom.chatView.hidden = false;
    dom.knowledgeView.hidden = true;
    dom.viewTitle.textContent = "智能问答";
    dom.breadcrumbLabel.textContent = "企业知识空间";
    dom.navItems.forEach((item) => item.classList.toggle("active", item.dataset.view === "chat"));
    if (updateHash) {
      const target = "#chat";
      if (window.location.hash !== target) history.pushState(null, "", target);
    }
    closeSidebar();
  }

  function applyRouteFromHash() {
    switchView("chat", false);
    if (window.location.hash !== "#chat") {
      history.replaceState(null, "", "#chat");
    }
  }

  function openSidebar() {
    dom.sidebar.classList.add("open");
    dom.sidebarScrim.hidden = false;
    document.body.classList.add("overlay-open");
  }

  function closeSidebar() {
    dom.sidebar.classList.remove("open");
    if (dom.inspector.classList.contains("open")) return;
    dom.sidebarScrim.hidden = true;
    document.body.classList.remove("overlay-open");
  }

  function openInspector() {
    dom.inspector.classList.add("open");
    if (window.innerWidth <= 1080) {
      dom.inspectorScrim.hidden = false;
      document.body.classList.add("overlay-open");
    }
  }

  function closeInspector() {
    dom.inspector.classList.remove("open");
    dom.inspectorScrim.hidden = true;
    if (!dom.sidebar.classList.contains("open")) document.body.classList.remove("overlay-open");
  }

  function setInspectorTab(tab) {
    state.activeInspectorTab = tab;
    dom.inspectorTabs.forEach((button) => button.classList.toggle("active", button.dataset.tab === tab));
    dom.citationsPane.hidden = tab !== "citations";
    dom.toolsPane.hidden = tab !== "tools";
  }

  function selectCitation(index) {
    setInspectorTab("citations");
    openInspector();
    const card = $(`[data-citation-card="${Number(index)}"]`, dom.citationList);
    if (!card) return;
    card.scrollIntoView({ behavior: "smooth", block: "center" });
    card.classList.remove("flash");
    void card.offsetWidth;
    card.classList.add("flash");
  }

  function selectTools() {
    setInspectorTab("tools");
    openInspector();
  }

  // --------------------------------------------------------------- sessions
  async function refreshSessions(explicit = false) {
    try {
      state.sessions = await api("/api/sessions");
      renderSessions();
      if (explicit) showToast("会话列表已刷新", "success", 1600);
    } catch (error) {
      if (explicit) showToast(error.message, "error");
    }
  }

  async function refreshStats() {
    try {
      state.stats = await api("/api/stats");
      renderStats();
    } catch (error) {
      // Non-critical background refresh.
    }
  }

  function handleSessionListClick(event) {
    const actionButton = event.target.closest("[data-session-action]");
    if (!actionButton) return;
    const item = event.target.closest(".session-item");
    const session = state.sessions.find((entry) => entry.id === item?.dataset.sessionId);
    if (!session) return;
    const action = actionButton.dataset.sessionAction;
    if (action === "open") {
      closeSessionMenus();
      loadSession(session.id);
    } else if (action === "menu") {
      event.stopPropagation();
      const menu = $(".session-menu", item);
      const willOpen = menu.hidden;
      closeSessionMenus();
      menu.hidden = !willOpen;
      actionButton.setAttribute("aria-expanded", String(willOpen));
    } else if (action === "rename") {
      closeSessionMenus();
      openRename(session);
    } else if (action === "delete") {
      closeSessionMenus();
      deleteSession(session);
    }
  }

  function closeSessionMenus() {
    $$(".session-menu").forEach((menu) => {
      menu.hidden = true;
    });
  }

  async function loadSession(sessionId) {
    if (state.streaming) {
      showToast("请先停止当前回答，再切换对话", "info");
      return;
    }
    try {
      const detail = await api(`/api/sessions/${encodeURIComponent(sessionId)}`);
      state.sessionId = detail.id;
      state.messages = detail.messages || [];
      const lastAssistant = [...state.messages].reverse().find((message) => message.role === "assistant");
      state.citations = lastAssistant?.citations || [];
      state.tools = lastAssistant?.tools || [];
      renderMessages();
      renderSessions();
      renderInspector();
      switchView("chat");
      if (state.messages.length) scrollToBottom(true);
      else dom.chatScroll.scrollTop = 0;
      closeSidebar();
      dom.messageInput.focus();
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  function newChat() {
    if (state.streaming) {
      showToast("请先停止当前回答", "info");
      return;
    }
    state.sessionId = null;
    state.messages = [];
    state.citations = [];
    state.tools = [];
    state.webSearch = false;
    dom.webToggle.setAttribute("aria-pressed", "false");
    dom.messageInput.value = "";
    autoResizeInput();
    dom.messageList.innerHTML = "";
    dom.welcome.hidden = false;
    dom.streamStatus.hidden = true;
    dom.chatScroll.scrollTop = 0;
    renderSessions();
    renderInspector();
    setInspectorTab("citations");
    switchView("chat");
    closeInspector();
    window.scrollTo({ top: 0 });
    dom.messageInput.focus();
  }

  async function deleteSession(session) {
    const confirmed = await askConfirm({
      title: "删除这段对话？",
      message: `“${session.title}”及其中的全部消息将被永久删除，此操作不可撤销。`,
      confirmText: "删除对话",
    });
    if (!confirmed) return;
    try {
      await api(`/api/sessions/${encodeURIComponent(session.id)}`, { method: "DELETE" });
      if (state.sessionId === session.id) {
        state.sessionId = null;
        state.messages = [];
        state.citations = [];
        state.tools = [];
        renderMessages();
        renderInspector();
      }
      await refreshSessions();
      await refreshStats();
      showToast("对话已删除", "success");
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  function openRename(session) {
    state.renameSessionId = session.id;
    dom.renameInput.value = session.title || "";
    dom.renameModal.hidden = false;
    document.body.classList.add("modal-open");
    setTimeout(() => {
      dom.renameInput.focus();
      dom.renameInput.select();
    }, 50);
  }

  function closeRename() {
    dom.renameModal.hidden = true;
    state.renameSessionId = null;
    if (dom.settingsModal.hidden && dom.confirmModal.hidden && dom.documentModal.hidden) {
      document.body.classList.remove("modal-open");
    }
  }

  async function submitRename(event) {
    event.preventDefault();
    const title = dom.renameInput.value.trim();
    if (!state.renameSessionId || !title) return;
    try {
      await api(`/api/sessions/${encodeURIComponent(state.renameSessionId)}`, {
        method: "PATCH",
        body: JSON.stringify({ title }),
      });
      closeRename();
      await refreshSessions();
      showToast("对话已重命名", "success");
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  // -------------------------------------------------------------------- chat
  function handleComposerSubmit(event) {
    event.preventDefault();
    if (state.streaming) {
      stopStreaming();
      return;
    }
    const question = dom.messageInput.value.trim();
    if (!question) {
      dom.messageInput.focus();
      return;
    }
    sendMessage(question);
  }

  async function sendMessage(question) {
    if (state.streaming) {
      showToast("请先停止当前回答", "info");
      return;
    }
    const text = question.trim();
    if (!text) return;

    dom.welcome.hidden = true;
    dom.messageInput.value = "";
    autoResizeInput();

    const userMessage = {
      role: "user",
      content: text,
      citations: [],
      tools: [],
      created_at: new Date().toISOString(),
    };
    const assistantMessage = {
      role: "assistant",
      content: "",
      citations: [],
      tools: [],
      created_at: new Date().toISOString(),
    };
    state.messages.push(userMessage, assistantMessage);
    state.citations = [];
    state.tools = [];
    state.completedStream = false;
    state.currentAssistant = assistantMessage;
    renderInspector();
    setInspectorTab("citations");

    dom.messageList.appendChild(buildMessageElement(userMessage));
    const assistantArticle = buildMessageElement(assistantMessage, {
      model: state.info?.model || "本地证据引擎",
    });
    dom.messageList.appendChild(assistantArticle);
    state.currentAssistantArticle = assistantArticle;
    scrollToBottom(true);

    state.abortController = new AbortController();
    setStreaming(true, "正在理解问题");
    updateStreamToolCount(0);

    try {
      await streamChat(text, state.abortController.signal, (event, data) => {
        if (event === "meta") {
          state.sessionId = data.session_id;
          userMessage.id = data.user_message_id;
          const userArticle = dom.messageList.querySelectorAll(".message")[dom.messageList.querySelectorAll(".message").length - 2];
          if (userArticle) userArticle.dataset.id = data.user_message_id || "";
          renderSessions();
        } else if (event === "status") {
          updateStreamingStatus(data.label || "正在处理");
          updateAssistantThinking(data.label || "正在处理");
        } else if (event === "tool") {
          upsertTool(data);
          updateStreamToolCount(state.tools.length);
          if (data.status === "running") {
            updateStreamingStatus(`正在调用 · ${data.label || data.name}`);
            updateAssistantThinking(`正在调用 ${data.label || data.name}`);
          }
          renderInspector();
        } else if (event === "citations") {
          state.citations = data.citations || [];
          assistantMessage.citations = state.citations;
          renderInspector();
          if (state.citations.length) updateStreamingStatus(`已找到 ${state.citations.length} 条可引用证据`);
        } else if (event === "token") {
          const token = data.text || "";
          if (!assistantMessage.content && token) {
            const body = $(".message-body", assistantArticle);
            if (body) {
              body.innerHTML = "";
              body.classList.add("streaming");
            }
          }
          assistantMessage.content += token;
          const body = $(".message-body", assistantArticle);
          if (body) {
            body.textContent = assistantMessage.content;
          }
          updateStreamingStatus("正在生成回答");
          scrollToBottom(false);
        } else if (event === "done") {
          state.completedStream = true;
          finalizeAssistant(assistantArticle, assistantMessage, data);
        } else if (event === "error") {
          throw new Error(data?.detail || data?.message || "回答生成失败");
        }
      });
      if (!state.completedStream) {
        finalizeAssistantError(assistantArticle, assistantMessage, "连接提前结束，当前内容可能不完整。");
      }
    } catch (error) {
      if (error.name === "AbortError") {
        finalizeAssistantPartial(assistantArticle, assistantMessage);
      } else {
        finalizeAssistantError(assistantArticle, assistantMessage, error.message || "回答生成失败");
        if (error.status === 401) openSettings();
      }
    } finally {
      setStreaming(false);
      dom.streamStatus.hidden = true;
      state.abortController = null;
      state.currentAssistant = null;
      state.currentAssistantArticle = null;
      refreshSessions();
      refreshStats();
      dom.messageInput.focus();
    }
  }

  async function streamChat(message, signal, onEvent) {
    const response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: authHeaders({
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
      }),
      body: JSON.stringify({ message, session_id: state.sessionId, web_search: state.webSearch }),
      signal,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      const error = new Error(payload?.detail || `请求失败（${response.status}）`);
      error.status = response.status;
      throw error;
    }
    if (!response.body) throw new Error("当前浏览器不支持流式响应");
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    const consume = (block) => {
      const lines = block.split(/\r?\n/);
      let eventName = "message";
      const dataLines = [];
      lines.forEach((line) => {
        if (line.startsWith("event:")) eventName = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      });
      if (!dataLines.length) return;
      const raw = dataLines.join("\n");
      let data;
      try {
        data = JSON.parse(raw);
      } catch {
        return;
      }
      onEvent(eventName, data);
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        consume(block);
        boundary = buffer.indexOf("\n\n");
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) consume(buffer);
  }

  function setStreaming(value, label = "") {
    state.streaming = value;
    dom.sendBtn.classList.toggle("is-streaming", value);
    dom.sendBtn.setAttribute("aria-label", value ? "停止生成" : "发送问题");
    dom.streamStatus.hidden = !value;
    if (value) {
      updateStreamingStatus(label || "正在思考");
      dom.streamToolCount.hidden = true;
    }
    dom.attachBtn.disabled = false;
    document.body.classList.toggle("is-streaming", value);
  }

  function updateStreamingStatus(label) {
    dom.streamStatusText.textContent = label;
    dom.streamStatus.hidden = false;
  }

  function updateStreamToolCount(count) {
    if (!count) {
      dom.streamToolCount.hidden = true;
      return;
    }
    dom.streamToolCount.hidden = false;
    dom.streamToolCount.textContent = `${count} 个工具`;
  }

  function updateAssistantThinking(label) {
    if (!state.currentAssistant || state.currentAssistant.content) return;
    const article = state.currentAssistantArticle;
    if (!article) return;
    const body = $(".message-body", article);
    if (body && !body.classList.contains("streaming")) body.innerHTML = thinkingHtml(label);
  }

  function upsertTool(tool) {
    const index = state.tools.findIndex((item) => item.id === tool.id);
    if (index >= 0) state.tools[index] = tool;
    else state.tools.push(tool);
    if (state.currentAssistant) state.currentAssistant.tools = state.tools;
  }

  function stopStreaming() {
    if (!state.abortController) return;
    updateStreamingStatus("正在停止生成");
    state.abortController.abort();
  }

  function finalizeAssistant(article, message, data) {
    message.id = data.message_id || "";
    message.content = message.content || "本次回答未返回文本内容。";
    message.citations = state.citations;
    message.tools = state.tools;
    message.latency_ms = data.latency_ms || 0;
    message.mode = data.mode;
    article.dataset.id = message.id;
    const body = $(".message-body", article);
    body.classList.remove("streaming", "error");
    body.innerHTML = renderMarkdown(message.content);
    const meta = $(".message-meta", article);
    if (meta && message.latency_ms) {
      const latency = document.createElement("span");
      latency.className = "model-label";
      latency.textContent = `${(message.latency_ms / 1000).toFixed(1)}s`;
      meta.appendChild(latency);
    }
    const modelLabel = data.model || state.info?.model || "灵犀证据引擎";
    if (meta) {
      const modelSpan = $(".model-label", meta);
      if (modelSpan) modelSpan.textContent = modelLabel;
    }
    appendAssistantExtras(article, message, data.suggestions || []);
    updateStreamingStatus("回答完成");
    dom.streamStatus.hidden = true;
    scrollToBottom(false);
  }

  function finalizeAssistantPartial(article, message) {
    message.content = message.content
      ? `${message.content}\n\n> 已停止生成，以上为当前已输出内容。`
      : "已停止生成。";
    const body = $(".message-body", article);
    body.classList.remove("streaming");
    body.innerHTML = renderMarkdown(message.content);
    appendAssistantExtras(article, message, []);
    updateStreamingStatus("已停止生成");
    showToast("已停止生成", "info", 2000);
  }

  function finalizeAssistantError(article, message, errorText) {
    const note = `\n\n> **生成未完成**：${errorText}`;
    if (message.content) message.content += note;
    else message.content = `抱歉，${errorText}`;
    const body = $(".message-body", article);
    body.classList.remove("streaming");
    body.classList.add("error");
    body.innerHTML = renderMarkdown(message.content);
    appendAssistantExtras(article, message, []);
    updateStreamingStatus("生成失败");
    showToast(errorText, "error", 4200);
  }

  function handleMessageClick(event) {
    const citation = event.target.closest("[data-citation]");
    if (citation) {
      selectCitation(citation.dataset.citation);
      return;
    }
    const tools = event.target.closest("[data-open-tools]");
    if (tools) {
      selectTools();
      return;
    }
    const followup = event.target.closest("[data-question]");
    if (followup) {
      if (state.streaming) return showToast("请先停止当前回答", "info");
      const question = followup.dataset.question;
      dom.messageInput.value = question;
      autoResizeInput();
      sendMessage(question);
      return;
    }
    const copy = event.target.closest("[data-copy-message]");
    if (copy) {
      const article = copy.closest(".message");
      const index = [...dom.messageList.children].indexOf(article);
      const message = state.messages[index];
      if (message) copyText(message.content);
      return;
    }
    const feedback = event.target.closest("[data-feedback]");
    if (feedback) {
      submitFeedback(feedback);
    }
  }

  async function submitFeedback(button) {
    const messageId = button.dataset.messageId;
    const rating = Number(button.dataset.feedback);
    if (!messageId) return;
    try {
      await api("/api/feedback", {
        method: "POST",
        body: JSON.stringify({ message_id: messageId, rating, comment: "" }),
      });
      state.feedback.set(`${messageId}:${rating}`, true);
      const parent = button.parentElement;
      $$(".feedback-btn", parent).forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      showToast(rating === 1 ? "感谢反馈，已记录下来" : "已记录，我们会持续改进", "success", 2000);
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  function scrollToBottom(force = false) {
    const nearBottom = dom.chatScroll.scrollHeight - dom.chatScroll.scrollTop - dom.chatScroll.clientHeight < 160;
    if (force || nearBottom) {
      requestAnimationFrame(() => {
        dom.chatScroll.scrollTop = dom.chatScroll.scrollHeight;
      });
    }
  }

  function autoResizeInput() {
    dom.messageInput.style.height = "auto";
    dom.messageInput.style.height = `${Math.min(180, Math.max(54, dom.messageInput.scrollHeight))}px`;
  }

  // ----------------------------------------------------------------- uploads
  async function handleFiles(fileList) {
    if (state.uploading) {
      showToast("已有文件正在上传，请稍候", "info");
      return;
    }
    const files = [...(fileList || [])];
    if (!files.length) return;
    if (files.length > 10) {
      showToast("单次最多上传 10 个文件", "error");
      return;
    }
    const allowed = [".pdf", ".docx", ".md", ".markdown", ".txt"];
    const maxMb = state.info?.limits?.max_upload_mb || 25;
    const valid = [];
    const rejectedLocal = [];
    files.forEach((file) => {
      const extension = `.${file.name.split(".").pop().toLowerCase()}`;
      if (!allowed.includes(extension)) {
        rejectedLocal.push({ file, reason: "不支持的文件格式" });
      } else if (file.size === 0) {
        rejectedLocal.push({ file, reason: "文件为空" });
      } else if (file.size > maxMb * 1024 * 1024) {
        rejectedLocal.push({ file, reason: `文件超过 ${maxMb} MB` });
      } else {
        valid.push(file);
      }
    });
    if (!valid.length) {
      showToast(rejectedLocal[0]?.reason || "没有可上传的文件", "error");
      return;
    }

    switchView("knowledge");
    state.uploads = files.map((file) => ({
      name: file.name,
      size: file.size,
      status: valid.includes(file) ? "waiting" : "error",
      reason: rejectedLocal.find((item) => item.file === file)?.reason || "",
    }));
    state.uploading = true;
    renderUploadQueue(0, "正在上传");
    const form = new FormData();
    valid.forEach((file) => form.append("files", file, file.name));
    try {
      const response = await uploadWithProgress(form, (percent) => renderUploadQueue(percent, "正在上传"));
      const accepted = response.documents || [];
      const rejected = response.rejected || [];
      accepted.forEach((doc) => {
        const entry = state.uploads.find((item) => item.name === doc.original_name && item.status === "waiting");
        if (entry) entry.status = "accepted";
      });
      rejected.forEach((item) => {
        const entry = state.uploads.find((entryItem) => entryItem.name === item.filename && entryItem.status === "waiting");
        if (entry) {
          entry.status = "error";
          entry.reason = item.reason;
        }
      });
      state.uploads.forEach((entry) => {
        if (entry.status === "waiting") entry.status = "accepted";
      });
      renderUploadQueue(100, "上传完成");
      if (accepted.length) {
        showToast(`已接收 ${accepted.length} 个文档，正在建立索引`, "success", 3500);
      }
      if (rejected.length) {
        showToast(`${rejected.length} 个文件未导入：${rejected[0].reason}`, "error", 4800);
      }
      await Promise.all([refreshDocuments(), refreshStats()]);
      scheduleDocumentPoll();
      setTimeout(() => {
        if (!state.uploading) dom.uploadQueue.hidden = true;
      }, 7000);
    } catch (error) {
      state.uploads.forEach((entry) => {
        if (entry.status === "waiting") {
          entry.status = "error";
          entry.reason = error.message;
        }
      });
      renderUploadQueue(0, "上传失败");
      showToast(error.message, "error", 4500);
      if (error.status === 401) openSettings();
    } finally {
      state.uploading = false;
    }
  }

  function uploadWithProgress(form, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/documents/upload");
      const key = getApiKey();
      if (key) xhr.setRequestHeader("X-API-Key", key);
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
      };
      xhr.onload = () => {
        let payload = null;
        try {
          payload = JSON.parse(xhr.responseText || "{}");
        } catch {
          payload = null;
        }
        if (xhr.status >= 200 && xhr.status < 300) resolve(payload);
        else {
          const error = new Error(payload?.detail || `上传失败（${xhr.status}）`);
          error.status = xhr.status;
          reject(error);
        }
      };
      xhr.onerror = () => reject(new Error("网络错误，上传失败"));
      xhr.ontimeout = () => reject(new Error("上传超时，请重试"));
      xhr.timeout = 120000;
      xhr.send(form);
    });
  }

  function renderUploadQueue(percent, label) {
    dom.uploadQueue.hidden = false;
    dom.uploadQueueLabel.textContent = label;
    dom.uploadProgressText.textContent = `${percent}%`;
    dom.uploadProgressBar.style.width = `${percent}%`;
    dom.uploadQueueList.innerHTML = state.uploads.map((entry) => {
      const statusClass = entry.status === "error" ? "fail" : entry.status === "accepted" ? "ok" : "";
      const statusText = entry.status === "error"
        ? entry.reason || "失败"
        : entry.status === "accepted" ? "已接收" : "准备中";
      return `<div class="queue-file">${icon("file")}<span title="${escapeHtml(entry.name)}">${escapeHtml(entry.name)}</span><span class="${statusClass}">${escapeHtml(shorten(statusText, 26))}</span></div>`;
    }).join("");
  }

  async function refreshDocuments() {
    try {
      state.documents = await api("/api/documents");
      renderDocuments();
      scheduleDocumentPoll();
    } catch (error) {
      if (error.status === 401) openSettings();
    }
  }

  function scheduleDocumentPoll() {
    const processing = state.documents.some((doc) => doc.status === "processing");
    if (!processing) {
      state.pollAttempts = 0;
      if (state.pollTimer) {
        clearTimeout(state.pollTimer);
        state.pollTimer = null;
      }
      return;
    }
    if (state.pollTimer || state.pollAttempts >= 80) return;
    state.pollTimer = setTimeout(async () => {
      state.pollTimer = null;
      state.pollAttempts += 1;
      await refreshDocuments();
      await refreshStats();
      scheduleDocumentPoll();
    }, 1200);
  }

  async function seedDocuments() {
    if (state.uploading) return;
    const hasDemo = state.documents.some((doc) => doc.is_demo);
    if (hasDemo) {
      const confirmed = await askConfirm({
        title: "重新载入演示资料？",
        message: "现有的演示文档会被替换并重新索引；你自己上传的文档不会受影响。",
        confirmText: "重新载入",
      });
      if (!confirmed) return;
    }
    const original = dom.seedDocumentsBtn.innerHTML;
    dom.seedDocumentsBtn.disabled = true;
    dom.seedDocumentsBtn.innerHTML = `${icon("refresh")}<span>正在载入…</span>`;
    try {
      const result = await api(`/api/documents/seed?force=${hasDemo ? "true" : "false"}`, { method: "POST" });
      state.documents = result.documents || [];
      renderDocuments();
      await refreshStats();
      showToast(result.message || "演示资料已载入", "success", 3500);
    } catch (error) {
      showToast(error.message, "error");
    } finally {
      dom.seedDocumentsBtn.disabled = false;
      dom.seedDocumentsBtn.innerHTML = original;
    }
  }

  // --------------------------------------------------------------- documents
  function handleDocumentAction(event) {
    const button = event.target.closest("[data-doc-action]");
    if (!button) return;
    const doc = state.documents.find((item) => item.id === button.dataset.docId);
    if (!doc) return;
    const action = button.dataset.docAction;
    if (action === "preview") openDocumentModal(doc);
    else if (action === "download") downloadDocument(doc);
    else if (action === "reindex") reindexDocument(doc);
    else if (action === "delete") deleteDocument(doc);
  }

  async function openDocumentModal(doc) {
    state.currentDocument = doc;
    dom.documentModal.hidden = false;
    document.body.classList.add("modal-open");
    dom.docModalTitle.textContent = doc.title || doc.original_name;
    dom.docModalMeta.innerHTML = [
      `格式 ${escapeHtml((doc.extension || "").replace(".", "").toUpperCase())}`,
      `状态 ${escapeHtml(statusMeta(doc.status).label)}`,
      `${formatNumber(doc.chunk_count || 0)} 个片段`,
      formatBytes(doc.size_bytes),
      `${formatNumber(doc.char_count || 0)} 字`,
    ].map((item) => `<span>${item}</span>`).join("");
    dom.docChunks.innerHTML = `<div class="chunk-empty">正在读取文档切片…</div>`;
    try {
      const result = await api(`/api/documents/${encodeURIComponent(doc.id)}/chunks`);
      if (!result.chunks?.length) {
        dom.docChunks.innerHTML = `<div class="chunk-empty">该文档还没有可用切片${doc.status === "failed" ? "，请重建索引" : "，请稍候"}。</div>`;
        return;
      }
      dom.docChunks.innerHTML = result.chunks.map((chunk) => `
        <div class="chunk-card">
          <div class="chunk-head"><strong>片段 #${chunk.ordinal + 1}${chunk.section ? ` · ${escapeHtml(chunk.section)}` : ""}</strong><span>${formatNumber(chunk.char_count)} 字${chunk.page ? ` · 第 ${chunk.page} 页` : ""}</span></div>
          <p>${escapeHtml(chunk.text)}</p>
        </div>`).join("");
    } catch (error) {
      dom.docChunks.innerHTML = `<div class="chunk-empty">${escapeHtml(error.message)}</div>`;
    }
  }

  function closeDocumentModal() {
    dom.documentModal.hidden = true;
    state.currentDocument = null;
    if (dom.settingsModal.hidden && dom.confirmModal.hidden && dom.renameModal.hidden) {
      document.body.classList.remove("modal-open");
    }
  }

  async function reindexDocument(doc) {
    try {
      await api(`/api/documents/${encodeURIComponent(doc.id)}/reindex`, { method: "POST" });
      showToast("已提交重建索引任务", "success");
      await refreshDocuments();
      scheduleDocumentPoll();
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  async function deleteDocument(doc) {
    const confirmed = await askConfirm({
      title: "从知识库删除文档？",
      message: `《${doc.title}》及其 ${doc.chunk_count || 0} 个索引片段将被删除，后续提问将无法检索到该内容。`,
      confirmText: "删除文档",
    });
    if (!confirmed) return;
    try {
      await api(`/api/documents/${encodeURIComponent(doc.id)}`, { method: "DELETE" });
      await Promise.all([refreshDocuments(), refreshStats()]);
      showToast("文档已删除", "success");
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  async function downloadDocument(doc) {
    try {
      const response = await fetch(`/api/documents/${encodeURIComponent(doc.id)}/download`, {
        headers: authHeaders(),
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => null);
        throw new Error(payload?.detail || "下载失败");
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = doc.original_name || doc.title;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
    } catch (error) {
      showToast(error.message, "error");
    }
  }

  // ----------------------------------------------------------------- modals
  function openSettings() {
    dom.apiKeyInput.value = getApiKey();
    applySettingsInfo();
    dom.settingsModal.hidden = false;
    document.body.classList.add("modal-open");
    setTimeout(() => dom.apiKeyInput.focus(), 50);
  }

  function closeSettings() {
    dom.settingsModal.hidden = true;
    if (dom.confirmModal.hidden && dom.renameModal.hidden && dom.documentModal.hidden) {
      document.body.classList.remove("modal-open");
    }
  }

  function restoreLocalSettings() {
    dom.apiKeyInput.value = getApiKey();
  }

  function saveSettings(event) {
    event.preventDefault();
    const key = dom.apiKeyInput.value.trim();
    if (key) localStorage.setItem(API_KEY_STORAGE, key);
    else localStorage.removeItem(API_KEY_STORAGE);
    closeSettings();
    showToast(key ? "API Key 已保存，正在重新连接" : "本地设置已清除", "success", 2200);
    bootstrapData();
  }

  function clearApiKey() {
    dom.apiKeyInput.value = "";
    localStorage.removeItem(API_KEY_STORAGE);
    showToast("已清除本地 API Key", "info", 1800);
  }

  function askConfirm({ title, message, confirmText = "确认删除" }) {
    if (state.confirmResolver) {
      state.confirmResolver(false);
      state.confirmResolver = null;
    }
    dom.confirmTitle.textContent = title;
    dom.confirmMessage.textContent = message;
    dom.confirmOkBtn.textContent = confirmText;
    dom.confirmModal.hidden = false;
    document.body.classList.add("modal-open");
    return new Promise((resolve) => {
      state.confirmResolver = resolve;
      setTimeout(() => dom.confirmCancelBtn.focus(), 30);
    });
  }

  function resolveConfirm(value) {
    dom.confirmModal.hidden = true;
    if (dom.settingsModal.hidden && dom.renameModal.hidden && dom.documentModal.hidden) {
      document.body.classList.remove("modal-open");
    }
    const resolver = state.confirmResolver;
    state.confirmResolver = null;
    if (resolver) resolver(value);
  }

  // -------------------------------------------------------------- utilities
  function renderMarkdown(raw) {
    if (!raw) return "";
    const text = String(raw).replace(/\r\n/g, "\n");
    let result = "";
    let cursor = 0;
    const fence = /```([\w+-]*)\n?([\s\S]*?)```/g;
    let match;
    while ((match = fence.exec(text))) {
      result += renderMarkdownText(text.slice(cursor, match.index));
      const language = match[1] ? `<div class="code-label">${escapeHtml(match[1])}</div>` : "";
      result += `<pre>${language}<code>${escapeHtml(match[2].replace(/\n$/, ""))}</code></pre>`;
      cursor = fence.lastIndex;
    }
    result += renderMarkdownText(text.slice(cursor));
    return result;
  }

  function renderMarkdownText(text) {
    const lines = text.split("\n");
    const output = [];
    let i = 0;
    const flushParagraph = (paragraph) => {
      if (!paragraph.length) return;
      output.push(`<p>${paragraph.map(inlineMarkdown).join("<br>")}</p>`);
    };
    while (i < lines.length) {
      const line = lines[i];
      if (!line.trim()) {
        i += 1;
        continue;
      }
      const heading = line.match(/^(#{1,6})\s+(.+)$/);
      if (heading) {
        const level = Math.min(6, heading[1].length);
        output.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
        i += 1;
        continue;
      }
      if (/^---+$/.test(line.trim())) {
        output.push("<hr>");
        i += 1;
        continue;
      }
      if (/^\s*>/.test(line)) {
        const quote = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) {
          quote.push(lines[i].replace(/^\s*>\s?/, ""));
          i += 1;
        }
        output.push(`<blockquote><p>${quote.map(inlineMarkdown).join("<br>")}</p></blockquote>`);
        continue;
      }
      if (line.includes("|") && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
        const tableLines = [line, lines[i + 1]];
        i += 2;
        while (i < lines.length && lines[i].includes("|") && lines[i].trim()) {
          tableLines.push(lines[i]);
          i += 1;
        }
        output.push(renderTable(tableLines));
        continue;
      }
      if (/^\s*([-*•]|\d+[.)])\s+/.test(line)) {
        const ordered = /^\s*\d+[.)]\s+/.test(line);
        const items = [];
        while (i < lines.length && /^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) {
          items.push(lines[i].replace(/^\s*([-*•]|\d+[.)])\s+/, ""));
          i += 1;
        }
        const tag = ordered ? "ol" : "ul";
        output.push(`<${tag}>${items.map((item) => `<li>${inlineMarkdown(item)}</li>`).join("")}</${tag}>`);
        continue;
      }
      const paragraph = [];
      while (i < lines.length && lines[i].trim() && !/^(#{1,6})\s+/.test(lines[i]) && !/^---+$/.test(lines[i].trim()) && !/^\s*>/.test(lines[i]) && !/^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) {
        if (lines[i].includes("|") && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) break;
        paragraph.push(lines[i]);
        i += 1;
      }
      flushParagraph(paragraph);
    }
    return output.join("");
  }

  function renderTable(lines) {
    const parseRow = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((cell) => cell.trim());
    const headers = parseRow(lines[0]);
    const rows = lines.slice(2).map(parseRow);
    const head = headers.map((cell) => `<th>${inlineMarkdown(cell)}</th>`).join("");
    const body = rows.map((row) => `<tr>${headers.map((_, index) => `<td>${inlineMarkdown(row[index] || "")}</td>`).join("")}</tr>`).join("");
    return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
  }

  function inlineMarkdown(value) {
    const codeParts = [];
    let result = escapeHtml(value);
    result = result.replace(/`([^`]+)`/g, (_, code) => {
      codeParts.push(`<code>${code}</code>`);
      return `@@INLINECODE${codeParts.length - 1}@@`;
    });
    result = result.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => {
      return `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`;
    });
    result = result.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    result = result.replace(/(?<!\*)\*([^*\n]+)\*(?!\*)/g, "<em>$1</em>");
    result = result.replace(/\[(\d{1,2})\]/g, (_, number) => `<button class="citation-ref" data-citation="${number}" title="查看引用 ${number}">${number}</button>`);
    codeParts.forEach((html, index) => {
      result = result.replace(`@@INLINECODE${index}@@`, html);
    });
    return result;
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    })[character]);
  }

  function shorten(value, max) {
    const text = String(value ?? "");
    return text.length > max ? `${text.slice(0, max)}…` : text;
  }

  function formatNumber(value) {
    return Number(value || 0).toLocaleString("zh-CN");
  }

  function formatBytes(bytes) {
    const size = Number(bytes || 0);
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  }

  function parseDate(value) {
    if (!value) return null;
    let normalized = String(value);
    if (!/[zZ]|[+-]\d{2}:\d{2}$/.test(normalized)) normalized += "Z";
    const date = new Date(normalized);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function formatDateTime(value) {
    const date = parseDate(value);
    if (!date) return "—";
    return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  }

  function timeOnly(value) {
    const date = parseDate(value);
    if (!date) return "";
    return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  }

  function timeAgo(value) {
    const date = parseDate(value);
    if (!date) return "刚刚";
    const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
    if (seconds < 60) return "刚刚";
    if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
    if (seconds < 86400 * 7) return `${Math.floor(seconds / 86400)} 天前`;
    return `${date.getMonth() + 1}月${date.getDate()}日`;
  }

  function fileExtension(doc) {
    const ext = (doc.extension || "").replace(".", "").toLowerCase();
    if (ext === "pdf") return { key: "pdf", label: "PDF" };
    if (ext === "docx" || ext === "doc") return { key: "docx", label: "DOC" };
    if (ext === "md" || ext === "markdown") return { key: "md", label: "MD" };
    return { key: "txt", label: "TXT" };
  }

  function statusMeta(status) {
    return {
      ready: { label: "已索引" },
      processing: { label: "索引中" },
      failed: { label: "失败" },
    }[status] || { label: status || "未知" };
  }

  function toolStatusMeta(status) {
    return {
      queued: { label: "等待中" },
      running: { label: "执行中" },
      success: { label: "已完成" },
      error: { label: "失败" },
      skipped: { label: "已跳过" },
    }[status] || { label: status || "未知" };
  }

  function toolIcon(name) {
    return {
      knowledge_search: "search",
      database_query: "database",
      web_search: "globe",
    }[name] || "sparkles";
  }

  function showToast(message, type = "success", duration = 3200) {
    if (!dom.toastRoot) return;
    const toast = document.createElement("div");
    toast.className = `toast ${type}`;
    toast.innerHTML = `${icon(type === "error" ? "alert" : type === "info" ? "info" : "check-circle")}<span></span>`;
    $("span", toast).textContent = message;
    dom.toastRoot.appendChild(toast);
    while (dom.toastRoot.children.length > 4) dom.toastRoot.firstElementChild.remove();
    setTimeout(() => {
      toast.classList.add("leaving");
      setTimeout(() => toast.remove(), 220);
    }, duration);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      showToast("已复制到剪贴板", "success", 1800);
    } catch {
      const area = document.createElement("textarea");
      area.value = text;
      document.body.appendChild(area);
      area.select();
      document.execCommand("copy");
      area.remove();
      showToast("已复制到剪贴板", "success", 1800);
    }
  }
})();
