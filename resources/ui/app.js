// clj-shelley web UI.
//
// Loaded as an ES module from /ui/app.js. Vue, markdown-it and highlight.js
// arrive as globals from the CDN <script> tags in index.html; this file
// expects them (and says so if they are missing). Markup lives in
// resources/index.html as <template> blocks, so HTML stays HTML.

if (!window.Vue) {
  document.getElementById("app").innerHTML =
    '<div class="boot"><div class="fatal">Vue failed to load from the CDN. ' +
    "clj-shelley's UI needs one network fetch on first load (or a vendored copy of " +
    "vue.global.prod.js).</div></div>";
  throw new Error("vue missing");
}

const { createApp, reactive, ref, computed, watch, nextTick, onMounted, onUnmounted } = Vue;

  // ===================================================================== utils
  /**
   * An image that will not load is replaced by a line of text: the endpoint may
   * not exist yet, and a broken-image icon says less than a reason does.
   */
  function imgFailed(e) {
    const img = e && e.target;
    if (!img || !img.parentNode) return;
    const note = document.createElement("div");
    note.className = "dim";
    note.style.fontSize = "11.5px";
    note.textContent = "image unavailable";
    img.parentNode.replaceChild(note, img);
  }

  const esc = (s) =>
    String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  const fmtNum = (n) => {
    if (n == null || n === "" || Number.isNaN(Number(n))) return "—";
    n = Number(n);
    if (n < 1000) return String(n);
    if (n < 1e6) return (n / 1000).toFixed(n < 1e4 ? 1 : 0) + "k";
    return (n / 1e6).toFixed(1) + "M";
  };

  const fmtCost = (c) => {
    if (c == null || c === "" || Number.isNaN(Number(c))) return "—";
    c = Number(c);
    if (c === 0) return "$0";
    if (c < 0.01) return "$" + c.toFixed(4);
    if (c < 1) return "$" + c.toFixed(3);
    return "$" + c.toFixed(2);
  };

  const fmtSize = (b) => (b == null ? "" : b < 1024 ? b + "B" : b < 1048576 ? (b / 1024).toFixed(1) + "K" : (b / 1048576).toFixed(1) + "M");

  /** Accepts epoch seconds, epoch ms, ISO strings and Go time strings. */
  function toMillis(ts) {
    if (ts == null || ts === "") return null;
    if (typeof ts === "number") return ts < 1e12 ? ts * 1000 : ts;
    const n = Number(ts);
    if (!Number.isNaN(n) && String(ts).trim() !== "") return n < 1e12 ? n * 1000 : n;
    const t = Date.parse(ts);
    return Number.isNaN(t) ? null : t;
  }

  function relTime(ts) {
    const ms = toMillis(ts);
    if (ms == null) return "";
    const d = Date.now() - ms;
    if (d < 0) return "now";
    if (d < 45e3) return "now";
    if (d < 3600e3) return Math.round(d / 60e3) + "m";
    if (d < 86400e3) return Math.round(d / 3600e3) + "h";
    if (d < 7 * 86400e3) return Math.round(d / 86400e3) + "d";
    if (d < 30 * 86400e3) return Math.round(d / (7 * 86400e3)) + "w";
    return new Date(ms).toLocaleDateString();
  }

  function clockTime(ts) {
    const ms = toMillis(ts);
    if (ms == null) return "";
    const d = new Date(ms);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function dayBucket(ts) {
    const ms = toMillis(ts);
    if (ms == null) return "earlier";
    const now = new Date();
    const d = new Date(ms);
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) return "today";
    const y = new Date(now.getTime() - 86400e3);
    if (d.toDateString() === y.toDateString()) return "yesterday";
    if (now - ms < 7 * 86400e3) return "this week";
    if (now - ms < 30 * 86400e3) return "this month";
    return "earlier";
  }

  function debounce(fn, ms) {
    let t = null;
    return function (...args) {
      clearTimeout(t);
      t = setTimeout(() => fn.apply(this, args), ms);
    };
  }

  function parseMaybeJSON(v) {
    if (v == null) return null;
    if (typeof v !== "string") return v;
    const s = v.trim();
    if (!s) return null;
    if (s[0] !== "{" && s[0] !== "[" && s[0] !== '"') return v;
    try { return JSON.parse(s); } catch (_) { return v; }
  }

  const truthy = (v) => v === true || v === "true" || v === 1 || v === "1" || v === "on";
  const fmtFlag = (v) => (typeof v === "object" ? JSON.stringify(v) : String(v));

  // ================================================================ markdown
  const md = window.markdownit
    ? window.markdownit({
        html: false,        // model output is escaped; only markdown is rendered
        linkify: true,
        breaks: true,
        highlight(str, lang) {
          if (!window.hljs) return "";
          try {
            if (lang && window.hljs.getLanguage(lang)) {
              return '<pre class="hljs"><code>' + window.hljs.highlight(str, { language: lang, ignoreIllegals: true }).value + "</code></pre>";
            }
            return '<pre class="hljs"><code>' + window.hljs.highlightAuto(str).value + "</code></pre>";
          } catch (_) { return ""; }
        },
      })
    : null;

  if (md) {
    // Links open in a new tab; the model never gets to script this page.
    const renderLink = md.renderer.rules.link_open || function (tokens, idx, options, env, self) {
      return self.renderToken(tokens, idx, options);
    };
    md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
      tokens[idx].attrSet("target", "_blank");
      tokens[idx].attrSet("rel", "noopener noreferrer");
      return renderLink(tokens, idx, options, env, self);
    };
  }

  const mdCache = new Map();
  function renderMarkdown(text) {
    const src = String(text == null ? "" : text);
    if (!md) return '<pre class="tool-pre">' + esc(src) + "</pre>";
    if (mdCache.has(src)) return mdCache.get(src);
    let html;
    try { html = md.render(src); } catch (_) { html = '<pre class="tool-pre">' + esc(src) + "</pre>"; }
    if (mdCache.size > 400) mdCache.clear();
    mdCache.set(src, html);
    return html;
  }

  // =================================================================== diffs
  const DIFF_META = /^(diff --git |index |--- |\+\+\+ |new file mode|deleted file mode|old mode|new mode|similarity index|rename (from|to) |Binary files )/;

  function diffLines(text) {
    return String(text == null ? "" : text).split("\n").map((line) => {
      let cls = "ctx";
      if (DIFF_META.test(line)) cls = "meta";
      else if (line.startsWith("@@")) cls = "hunk";
      else if (line.startsWith("+")) cls = "add";
      else if (line.startsWith("-")) cls = "del";
      else if (line.startsWith("\\")) cls = "meta";
      return { text: line, cls };
    });
  }

  function looksLikeDiff(text) {
    const s = String(text == null ? "" : text);
    if (s.length < 8 || s.indexOf("\n") < 0) return false;
    return /^(diff --git |@@ -|\+\+\+ |--- )/m.test(s);
  }

  // ============================================================== api client
  // Every read has candidate paths: API.md first, the older plural shape second.
  // The winner is remembered per key so we probe once, not on every request.
  const CLIENT = { "content-type": "application/json" };
  const routeWin = {};
  const routeNote = reactive({});

  async function httpJson(url, opts) {
    const r = await fetch(url, opts);
    const text = await r.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (_) { data = { raw: text }; } }
    return { r, data };
  }

  /**
   * Request a logical endpoint. `candidates` is an array of URL strings.
   * 404/405 means "this server does not have that shape" — try the next.
   * A network failure or a 5xx stops immediately: the endpoint exists and is
   * broken, and silently retrying another path would hide the real error.
   */
  async function request(key, candidates, opts) {
    opts = opts || {};
    const method = opts.method || "GET";
    const body = opts.body === undefined ? undefined : JSON.stringify(opts.body);
    const start = typeof routeWin[key] === "number" ? routeWin[key] : 0;
    const order = [start].concat(candidates.map((_, i) => i).filter((i) => i !== start));
    let lastErr = null;
    for (const i of order) {
      const url = candidates[i];
      let res;
      try {
        res = await httpJson(url, { method, body, headers: Object.assign({}, CLIENT, opts.headers || {}), signal: opts.signal });
      } catch (e) {
        if (e && e.name === "AbortError") throw e;
        throw new Error(e && e.message ? e.message : "network error");
      }
      const { r, data } = res;
      if (r.status === 404 || r.status === 405) {
        lastErr = new Error(method + " " + url + " → " + r.status);
        continue;
      }
      if (!r.ok) {
        const msg = (data && (data.error || data.message)) || r.status + " " + r.statusText;
        throw new Error(method + " " + url + " → " + msg);
      }
      routeWin[key] = i;
      routeNote[key] = url;
      return data;
    }
    routeNote[key] = "unavailable";
    const err = lastErr || new Error(method + " " + candidates[0] + " unavailable");
    err.notFound = true;
    throw err;
  }

  const enc = encodeURIComponent;

  /**
   * The files API joins `path` onto `cwd`, so an absolute path must be paired
   * with "/" or the two get concatenated into a path that does not exist.
   */
  function rootFor(p) {
    if (p && p[0] === "/") return "/";
    return (S.conversation && S.conversation.cwd) || "";
  }
  const EP = {
    version: ["/version", "/api/version"],
    health: ["/api/health"],
    list: ["/api/conversations"],
    archived: ["/api/conversations/archived"],
    archiveAll: ["/api/conversations/archive-all"],
    create: ["/api/conversations/new", "/api/conversations"],
    get: (id) => ["/api/conversation/" + enc(id), "/api/conversations/" + enc(id)],
    stream: (id) => ["/api/conversation/" + enc(id) + "/stream", "/api/conversations/" + enc(id) + "/events"],
    chat: (id) => ["/api/conversation/" + enc(id) + "/chat", "/api/conversations/" + enc(id) + "/messages"],
    cancel: (id) => ["/api/conversation/" + enc(id) + "/cancel", "/api/conversations/" + enc(id) + "/cancel"],
    archive: (id) => ["/api/conversation/" + enc(id) + "/archive"],
    tags: (id) => ["/api/conversation/" + enc(id) + "/tags"],
    changeDir: (id) => ["/api/conversation/" + enc(id) + "/change-dir"],
    compact: (id) => ["/api/conversation/" + enc(id) + "/compact"],
    retry: (id) => ["/api/conversation/" + enc(id) + "/retry"],
    options: (id) => ["/api/conversation/" + enc(id) + "/options"],
    remove: (id) => ["/api/conversation/" + enc(id), "/api/conversations/" + enc(id)],
    usage: (id) => ["/api/conversation/" + enc(id) + "/usage", "/api/usage?conversation=" + enc(id)],
    findFiles: (q, cwd) => ["/api/find-files?q=" + enc(q) + "&cwd=" + enc(cwd || "") + "&limit=60"],
    listDir: (p, cwd) => ["/api/list-directory?path=" + enc(p || ".") + "&cwd=" + enc(cwd || "")],
    file: (p) => ["/api/file?path=" + enc(p) + "&cwd=" + enc(rootFor(p))],
    image: (p) => ["/api/workspace/image?path=" + enc(p) + "&cwd=" + enc(rootFor(p))],
    gitStatus: (cwd) => ["/api/git/status?cwd=" + enc(cwd || "")],
    gitDiff: (cwd, p) => ["/api/git/diff?cwd=" + enc(cwd || "") + (p ? "&path=" + enc(p) : "")],
    providers: ["/api/model-providers"],
    customModels: ["/api/custom-models"],
    costs: ["/api/model-costs"],
    settings: ["/api/settings"],
    profiles: ["/api/profiles"],
    flags: ["/api/feature-flags"],
    integrations: ["/api/integrations"],
    slackTest: ["/api/integrations/slack/test"],
    mcpServers: ["/api/mcp/servers"],
    tools: ["/api/tools"],
    upload: ["/api/upload", "/api/files/upload"],
  };

  const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];

  const api = {
    get: (key, candidates) => request(key, candidates),
    post: (key, candidates, body) => request(key, candidates, { method: "POST", body: body || {} }),
    put: (key, candidates, body) => request(key, candidates, { method: "PUT", body: body || {} }),
    del: (key, candidates) => request(key, candidates, { method: "DELETE" }),
  };

  // ================================================================== toasts
  let toastSeq = 0;

  // =================================================================== store
  const S = reactive({
    // shell
    theme: localStorage.getItem("shell.theme") || "dark",
    drawerOpen: window.innerWidth > 860,
    modal: null,
    showSearch: false,
    query: "",
    tagFilter: "",
    archivedMode: false,
    archivedUnavailable: false,
    loadingConvs: false,

    // conversations
    conversations: [],
    currentId: null,
    conversation: {},
    messages: [],
    running: false,
    status: "ready",
    streamState: "idle",
    queue: [],
    live: null,

    // composer
    draft: "",
    attachments: [],
    models: [],
    model: localStorage.getItem("shell.model") || "",
    thinking: localStorage.getItem("shell.thinking") || "",
    systemPrompt: "",
    toolOverrides: {},
    disableAllTools: false,

    // panes
    usage: null,
    git: { branch: "", commit: "", subject: "", dirty: false, repo_root: "", error: "", diff: "", diffLoading: false },
    file: { open: false, path: "", content: "", truncated: false, loading: false, editing: false, error: "", saving: false },
    findState: "idle",
    term: { exit: false },

    // settings payloads
    profiles: [],
    flags: {},
    integrations: [],
    costs: {},
    version: {},
    health: {},
    mcp: { servers: [], tools: [], toolsFor: "" },

    frame: {},
    toasts: [],
  });

  const isMobile = ref(window.innerWidth <= 860);
  window.addEventListener("resize", () => {
    const was = isMobile.value;
    isMobile.value = window.innerWidth <= 860;
    // Leaving the desktop layout puts the drawer back over the chat; hide it so
    // the transcript is what a phone shows first.
    if (isMobile.value && !was && S.currentId) S.drawerOpen = false;
  });

  /**
   * Toasts for the same failure would otherwise arrive once per retry and bury
   * the transcript. A repeat of a message already on screen increments its
   * count instead of adding a second row.
   */
  function toast(text, kind, ms) {
    const body = String(text || "");
    const existing = S.toasts.find((t) => t.text === body);
    if (existing) {
      existing.count = (existing.count || 1) + 1;
      existing.id = ++toastSeq;         // restarts this toast's dismiss timer
      setTimeout(() => dismissToast(existing.id), ms || (kind === "error" ? 9000 : 5000));
      return existing.id;
    }
    const id = ++toastSeq;
    S.toasts.push({ id, text: body, kind: kind || "warn", count: 1 });
    setTimeout(() => dismissToast(id), ms || (kind === "error" ? 9000 : 5000));
    return id;
  }
  function dismissToast(id) {
    const i = S.toasts.findIndex((t) => t.id === id);
    if (i >= 0) S.toasts.splice(i, 1);
  }

  function applyTheme(t) {
    S.theme = t;
    document.documentElement.setAttribute("data-theme", t);
    localStorage.setItem("shell.theme", t);
  }

  // =========================================================== normalisation
  // The wire carries two vocabularies: our Clojure blocks ({type:"tool-use"})
  // and the original's Go structs ({Type:5, ToolName:…}). Both are accepted so
  // the UI does not care which server is behind it.
  const NUM_KIND = { 0: "text", 1: "thinking", 2: "text", 3: "thinking", 4: "redacted-thinking", 5: "tool-use", 6: "tool-result", 7: "tool-use", 8: "tool-result", 9: "tool-result" };

  // Both spellings appear on the wire: our Clojure server emits kebab-case
  // keys (message-id, tool-use-id, created-at), API.md names snake_case, and
  // the Go original used CamelCase. One lookup covers all three.
  function pick(obj, names) {
    for (const n of names) if (obj[n] !== undefined && obj[n] !== null) return obj[n];
    return undefined;
  }

  function normalizeBlock(raw) {
    if (raw == null) return null;
    if (typeof raw === "string") return { kind: "text", text: raw };
    if (typeof raw !== "object") return { kind: "text", text: String(raw) };

    const t = pick(raw, ["type", "Type"]);
    let kind;
    if (typeof t === "number") kind = NUM_KIND[t] || "unknown";
    else if (typeof t === "string") kind = t.toLowerCase().replace(/[\s_]/g, "-");

    const hasResult = pick(raw, ["toolResult", "ToolResult", "isError", "is-error", "ToolError"]) !== undefined;
    const name = pick(raw, ["name", "toolName", "ToolName", "tool_name"]);
    const input = pick(raw, ["input", "toolInput", "ToolInput", "tool_input"]);

    // Field sniffing beats the enum when the vocabulary is unknown.
    if (!kind || kind === "unknown") {
      if (name && (hasResult || pick(raw, ["toolUseId", "toolUseID", "tool-use-id", "tool_use_id", "ToolUseID"]))) kind = "tool-result";
      else if (name && input !== undefined) kind = "tool-use";
      else if (pick(raw, ["thinking", "Thinking"])) kind = "thinking";
      else if (pick(raw, ["text", "Text"])) kind = "text";
      else kind = "unknown";
    }
    if (kind === "tool-use" || kind === "tooluse") kind = "tool-use";
    if (kind === "tool-result" || kind === "toolresult") kind = "tool-result";

    if (kind === "text") {
      const text = pick(raw, ["text", "Text"]);
      return { kind: "text", text: typeof text === "string" ? text : text == null ? "" : String(text) };
    }
    if (kind === "thinking" || kind === "redacted-thinking") {
      const text = pick(raw, ["thinking", "Thinking", "text", "Text", "data", "Data"]);
      return { kind: "thinking", text: String(text == null ? "" : text) };
    }
    if (kind === "tool-use") {
      return {
        kind: "tool-use",
        id: String(pick(raw, ["id", "ID", "toolUseId", "toolUseID", "tool-use-id", "tool_use_id", "ToolUseID"]) || ""),
        name: String(name || "tool"),
        input: input === undefined ? null : input,
        startedAt: pick(raw, ["toolUseStartTime", "ToolUseStartTime", "startedAt"]),
      };
    }
    if (kind === "tool-result") {
      const content = pick(raw, ["content", "Content", "toolResult", "ToolResult"]);
      let blocks = [];
      if (Array.isArray(content)) blocks = content.map(normalizeBlock).filter(Boolean);
      else if (typeof content === "string") blocks = [{ kind: "text", text: content }];
      else if (content && typeof content === "object") blocks = [normalizeBlock(content)].filter(Boolean);
      const flat = pick(raw, ["text", "Text"]);
      if (!blocks.length && typeof flat === "string") blocks = [{ kind: "text", text: flat }];
      return {
        kind: "tool-result",
        id: String(pick(raw, ["toolUseId", "toolUseID", "tool-use-id", "tool_use_id", "ToolUseID", "id", "ID"]) || ""),
        name: name ? String(name) : "",
        isError: truthy(pick(raw, ["isError", "is_error", "is-error", "ToolError"])),
        blocks,
        elapsedMs: Number(pick(raw, ["elapsedMs", "elapsed-ms", "elapsed_ms"]) || 0),
      };
    }
    if (kind === "image") {
      const mt = pick(raw, ["mediaType", "MediaType", "media_type"]) || "image/png";
      const data = pick(raw, ["data", "Data"]);
      const url = pick(raw, ["displayImageUrl", "DisplayImageURL", "url", "URL"]);
      const src = url ? String(url) : data ? (String(data).startsWith("data:") ? String(data) : "data:" + mt + ";base64," + data) : "";
      return { kind: "image", src };
    }
    const loose = pick(raw, ["text", "Text", "data", "Data"]);
    return { kind: "unknown", text: typeof loose === "string" ? loose : JSON.stringify(raw).slice(0, 4000) };
  }

  const SKIP_TYPES = new Set(["system", "slug", "livetranscript"]);

  function normalizeMessage(raw) {
    const rawType = String(pick(raw, ["type", "role"]) || "agent").toLowerCase();
    const llm = parseMaybeJSON(pick(raw, ["llm_data", "llmData", "llm-data", "llm"]));
    const userData = parseMaybeJSON(pick(raw, ["user_data", "userData", "user-data"]));

    let blocks = [];
    if (Array.isArray(raw.content)) blocks = raw.content.map(normalizeBlock).filter(Boolean);
    else if (Array.isArray(llm)) blocks = llm.map(normalizeBlock).filter(Boolean);
    else if (llm && Array.isArray(llm.Content || llm.content)) blocks = (llm.Content || llm.content).map(normalizeBlock).filter(Boolean);

    let type = rawType;
    if (type === "assistant") type = "agent";
    if (SKIP_TYPES.has(type)) type = "skip";
    else if (type === "gitinfo") type = "gitinfo";
    else if (type === "modelchange") type = "modelchange";
    else if (type === "inplacecompaction") type = "note";
    else if (type === "cwdchange" || type === "cwd-change") type = "note";
    else if (type === "warning") type = "warning";
    else if (type === "error") type = "error";
    else if (type === "user") type = "user";
    else if (type === "tool") type = "tool";
    else if (type === "agent") type = "agent";
    else type = "agent";

    let text = blocks.filter((b) => b.kind === "text").map((b) => b.text).join("");
    if (!text && userData && typeof userData === "object") {
      text = String(userData.text || userData.message || userData.content || "");
    }
    // The server records a failed turn as an ordinary agent message whose text
    // begins "The request failed:". Show it as an error, with a way to retry.
    if (type === "agent" && /^\s*the request failed/i.test(text || "") && !blocks.some((b) => b.kind === "tool-use")) {
      type = "error";
    }

    if (type === "error" && !text) {
      const e = parseMaybeJSON(pick(raw, ["llm_data", "llmData", "llm-data"]));
      text = (e && (e.error || e.text)) || String(pick(raw, ["error", "text"]) || "unknown error");
    }

    // A tool result is stored as a message whose role is the *user's* — the
    // result is what the user turn feeds back to the model. Rendering it as a
    // chat bubble would be wrong, so a row made only of result blocks is a tool
    // row for display purposes.
    const hasResultBlocks = blocks.some((b) => b.kind === "tool-result");
    const hasOtherBlocks = blocks.some((b) => b.kind !== "tool-result");
    if (type === "user" && hasResultBlocks && !hasOtherBlocks) type = "tool";

    const usage = parseMaybeJSON(pick(raw, ["usage_data", "usageData", "usage-data"])) || null;
    const id = String(pick(raw, ["message_id", "messageId", "message-id", "id", "sequence_id", "sequence-id"]) || Math.random().toString(36).slice(2));

    // An image block normally carries no bytes in the row: the row's id and the
    // block's position address the bytes at /api/message/:id/image/:i/:sub, so
    // remember that address rather than the payload.
    blocks.forEach((b, i) => {
      b.messageId = id;
      b.index = i;
    });

    return {
      id,
      seq: Number(pick(raw, ["sequence_id", "sequence-id", "seq"]) || 0),
      type,
      blocks,
      text,
      usage,
      retryable: truthy(pick(raw, ["retryable", "Retryable"])) || (type === "error"),
      author: userData && userData.author ? String(userData.author) : "",
      createdAt: pick(raw, ["created_at", "createdAt", "created-at"]),
      raw,
    };
  }

  // ------------------------------------------------------- transcript shaping
  function resultFromBlock(b) {
    const texts = [];
    const images = [];
    for (const blk of b.blocks || []) {
      if (!blk) continue;
      if (blk.kind === "text") texts.push(blk.text);
      else if (blk.kind === "image" && blk.src) images.push(blk.src);
      else if (blk.kind === "unknown" && blk.text) texts.push(blk.text);
      else if (blk.kind === "tool-result") texts.push((blk.blocks || []).map((x) => x.text || "").join(""));
    }
    const text = texts.join("\n").replace(/\s+$/, "");
    return {
      text,
      images,
      isError: !!b.isError,
      elapsedMs: b.elapsedMs || 0,
      isDiff: looksLikeDiff(text),
      progress: "",
    };
  }

  const COALESCE_NAMES = new Set(["bash", "read", "list", "glob", "grep", "search", "ls", "cat"]);

  function coalesce(items) {
    const out = [];
    for (const it of items) {
      const prev = out[out.length - 1];
      if (
        it.kind === "tool" && prev && prev.kind === "tool" &&
        prev.name === it.name &&
        JSON.stringify(prev.input) === JSON.stringify(it.input) &&
        COALESCE_NAMES.has(it.name)
      ) {
        prev.count = (prev.count || 1) + 1;
        prev.extraResults = prev.extraResults || [];
        if (it.result) prev.extraResults.push(it.result);
        if (prev.result && it.result) {
          prev.result.elapsedMs = (prev.result.elapsedMs || 0) + (it.result.elapsedMs || 0);
        } else if (!prev.result) {
          prev.result = it.result;
        }
        continue;
      }
      out.push(it);
    }
    // Fold duplicate outputs into the primary result (capped so a loop of
    // identical failures does not paste 40 copies into the DOM).
    for (const it of out) {
      if (it.kind === "tool" && it.extraResults && it.extraResults.length && it.result) {
        const extra = it.extraResults.filter((r) => r && r.text && r.text !== it.result.text).slice(0, 2);
        if (extra.length) it.result.text += "\n\n… repeated output ×" + (it.extraResults.length + 1);
        if (it.extraResults.some((r) => r && r.isError)) it.result.isError = true;
        for (const r of it.extraResults) if (r && r.images) it.result.images = (it.result.images || []).concat(r.images).slice(0, 6);
      }
    }
    return out;
  }

  // The tag beside a tool call. Short, because it repeats on every step and the
  // transcript should read as a list of actions, not a list of nouns.
  const TOOL_TAGS = {
    bash: "bash", patch: "patch", change_dir: "cd", read_image: "image",
    llm_one_shot: "llm", output_iframe: "iframe", subagent: "agent",
    message_user: "ask", browser: "browser", compact_in_place: "compact",
  };
  function toolTag(name) {
    const n = String(name || "tool");
    return TOOL_TAGS[n] || n.replace(/^mcp__/, "").replace(/_/g, "-");
  }

  function summaryOfInput(name, input) {
    if (input == null) return "";
    if (typeof input === "string") return input.replace(/\s+/g, " ").slice(0, 140);
    const one = input.command || input.path || input.file_path || input.pattern || input.prompt || input.query || input.url || input.name;
    if (typeof one === "string") return one.replace(/\s+/g, " ").slice(0, 140);
    try { return JSON.stringify(input).slice(0, 140); } catch (_) { return ""; }
  }

  function inputText(input) {
    if (input == null) return "";
    if (typeof input === "string") return input;
    try { return JSON.stringify(input, null, 2); } catch (_) { return String(input); }
  }

  /**
   * Turn stored messages into render groups. Two passes because a tool result
   * lives in a later row than the tool call it belongs to; pairing them means
   * the reader sees one card and never an orphan "tool" bubble.
   */
  function buildGroups(messages) {
    const norm = messages.map((m) => (m && m.blocks ? m : normalizeMessage(m)));
    const results = new Map();
    const useIds = new Set();

    for (const m of norm) {
      for (const b of m.blocks) {
        if (b.kind === "tool-use" && b.id) useIds.add(b.id);
      }
    }
    for (const m of norm) {
      for (const b of m.blocks) {
        if (b.kind !== "tool-result") continue;
        if (b.id && useIds.has(b.id) && !results.has(b.id)) results.set(b.id, resultFromBlock(b));
      }
    }

    const groups = [];
    for (const m of norm) {
      if (m.type === "skip") continue;

      const g = {
        key: m.id,
        id: m.id,
        type: m.type,
        time: m.createdAt ? clockTime(m.createdAt) : "",
        text: m.text,
        items: [],
        images: [],
        badge: "",
        retryable: m.retryable,
        raw: m.raw,
      };

      if (m.type === "user" || m.type === "agent") {
        for (const b of m.blocks) {
          if (b.kind === "text") {
            if (!b.text) continue;
            const last = g.items[g.items.length - 1];
            if (last && last.kind === "text") last.text += b.text;
            else g.items.push({ kind: "text", text: b.text });
          } else if (b.kind === "thinking") {
            if (!b.text) continue;
            const last = g.items[g.items.length - 1];
            if (last && last.kind === "thinking") last.text += b.text;
            else g.items.push({ kind: "thinking", text: b.text, open: false });
          } else if (b.kind === "tool-use") {
            g.items.push({ kind: "tool", id: b.id, name: b.name, input: b.input, result: results.get(b.id) || null, count: 1 });
          } else if (b.kind === "tool-result") {
            // A result with no matching call still deserves a card.
            if (!b.id || !useIds.has(b.id)) {
              g.items.push({ kind: "tool", id: b.id, name: b.name || "tool", input: null, result: resultFromBlock(b), count: 1 });
            }
          } else if (b.kind === "image") {
            const src = b.src || (b.messageId ? "/api/message/" + enc(b.messageId) + "/image/" + (b.index || 0) + "/0" : "");
            if (src) g.images.push(src);
          }
        }
        if (!g.text) g.text = g.items.filter((i) => i.kind === "text").map((i) => i.text).join("");
        if (m.usage && (m.usage.cost_usd || m.usage.output_tokens)) {
          g.badge = fmtCost(m.usage.cost_usd);
        }
      } else if (m.type === "tool") {
        for (const b of m.blocks) {
          if (b.kind !== "tool-result") continue;
          if (b.id && useIds.has(b.id)) continue; // already merged into its card
          g.items.push({
            kind: "tool",
            id: b.id,
            name: b.name || "tool",
            input: null,
            result: resultFromBlock(b),
            count: 1,
          });
        }
      } else if (m.type === "gitinfo") {
        const d = parseMaybeJSON(m.raw.llm_data || m.raw["llm-data"]) || {};
        g.text = "git " + (d.commit || d.Commit || "?").toString().slice(0, 8) +
          (d.branch || d.Branch ? " on " + (d.branch || d.Branch) : "") +
          (d.subject || d.Subject ? " — " + (d.subject || d.Subject) : "");
      } else if (m.type === "modelchange") {
        const d = parseMaybeJSON(m.raw.llm_data || m.raw["llm-data"]) || {};
        g.text = "model → " + (d.model || d.Model || m.text || "?");
      }

      const empty =
        (g.type === "agent" || g.type === "tool") && !g.items.length && !g.images.length;
      if (empty) continue;
      if ((g.type === "warning" || g.type === "error" || g.type === "note") && !g.text) continue;

      // One turn stores a row per model call, so a tool-using turn arrives as
      // several agent rows. A row with no prose of its own continues the
      // previous one instead of repeating the author header.
      const prev = groups[groups.length - 1];
      const toolOnly = g.type === "agent" && !g.items.some((i) => i.kind === "text");
      if (prev && prev.type === "agent" && toolOnly) {
        prev.items = prev.items.concat(g.items);
        prev.images = prev.images.concat(g.images);
        if (g.usage && !prev.badge) prev.badge = fmtCost(g.usage.cost_usd);
      } else {
        groups.push(g);
      }
    }
    for (const g of groups) g.items = coalesce(g.items);
    return groups;
  }

  // ----------------------------------------------------------- live (stream)
  function liveGroup(startedAt) {
    const l = S.live;
    if (!l || !l.items.length) return null;
    return {
      key: "live",
      id: "live",
      type: "agent",
      time: startedAt ? clockTime(startedAt) : "",
      text: "",
      items: coalesce(l.items.map((i) => Object.assign({}, i))),
      images: [],
      badge: "",
    };
  }

  function handleEvent(ev) {
    if (!ev || typeof ev !== "object") return;
    // Servers disagree on whether the id is snake_case or camelCase.
    const cid = ev.conversation_id || ev.conversationId;
    if (cid && S.currentId && cid !== S.currentId) return;
    const type = String(ev.type || ev.Type || ev.event || "").toLowerCase();

    switch (type) {
      case "history": {
        const msgs = ev.messages || ev.Messages || [];
        S.messages = msgs.map(normalizeMessage);
        S.live = null;
        S.running = !!(ev.agent_working || ev.agentWorking || ev.running);
        renderSoon();
        break;
      }
      case "text-delta":
      case "text": {
        ensureLive();
        const t = ev.text == null ? "" : String(ev.text);
        if (!t) break;
        const last = S.live.items[S.live.items.length - 1];
        if (last && last.kind === "text") last.text += t;
        else S.live.items.push({ kind: "text", text: t });
        S.running = true;
        renderSoon();
        break;
      }
      case "thinking-delta":
      case "thinking": {
        ensureLive();
        const t = ev.text == null ? "" : String(ev.text);
        if (!t) break;
        S.live.thinking = (S.live.thinking || "") + t;
        const idx = S.live.thinkingIdx;
        if (idx != null && S.live.items[idx]) {
          S.live.items[idx].text += t;
          S.live.items[idx].open = true;
        } else {
          S.live.items.push({ kind: "thinking", text: t, open: true });
          S.live.thinkingIdx = S.live.items.length - 1;
        }
        S.running = true;
        renderSoon();
        break;
      }
      case "tool-start":
      case "tool-use-start": {
        // The LLM layer announces a call (`tool-use-start`, no input) before
        // the loop dispatches it (`tool-start`, with input). Both carry the
        // same id, so the card is created once and completed in place.
        ensureLive();
        const id = String(ev.id || ev.tool_use_id || ev.toolUseId || Math.random().toString(36).slice(2));
        const name = ev.name || ev.tool_name || ev.toolName || "tool";
        const existing = findLiveTool(id);
        if (existing) {
          if (name && (!existing.name || existing.name === "tool")) existing.name = String(name);
          if (ev.input !== undefined) existing.input = ev.input;
        } else {
          S.live.items.push({
            kind: "tool",
            id: id,
            name: String(name),
            input: ev.input === undefined ? null : ev.input,
            result: null,
            progress: "",
            count: 1,
          });
        }
        S.live.thinkingIdx = null;
        S.running = true;
        S.status = "running " + name + "…";
        renderSoon();
        break;
      }
      case "tool-progress": {
        if (!S.live) break;
        // Progress frames are not always correlated; fall back to the newest
        // call that has not finished.
        let it = findLiveTool(ev.id || ev.tool_use_id);
        if (!it) {
          for (let i = S.live.items.length - 1; i >= 0; i--) {
            if (S.live.items[i].kind === "tool" && !S.live.items[i].result) { it = S.live.items[i]; break; }
          }
        }
        if (it) { it.progress = (it.progress || "") + String(ev.text || ev.delta || ""); renderSoon(); }
        break;
      }
      case "tool-end":
      case "tool-result": {
        const it = S.live ? findLiveTool(ev.id || ev.tool_use_id) : null;
        const content = ev.content;
        let text = "";
        const images = [];
        if (Array.isArray(content)) {
          for (const c of content) {
            if (typeof c === "string") text += c;
            else if (c && typeof c === "object") {
              if (c.type === "image" || c.Type === "image" || c.mediaType || c.MediaType) {
                const nb = normalizeBlock(c);
                if (nb && nb.src) images.push(nb.src);
              } else text += c.text || c.Text || "";
            }
          }
        } else if (typeof content === "string") text = content;
        if (!text && typeof ev.text === "string") text = ev.text;
        const result = {
          text: text.replace(/\s+$/, ""),
          images,
          isError: truthy(ev.is_error || ev.isError || ev.error),
          elapsedMs: Number(ev["elapsed-ms"] || ev.elapsedMs || 0),
          isDiff: looksLikeDiff(text),
          progress: it ? it.progress : "",
        };
        if (it) { it.result = result; it.progress = ""; }
        else if (S.live) S.live.items.push({ kind: "tool", id: String(ev.id || ""), name: String(ev.name || "tool"), input: null, result, count: 1 });
        if (ev.ui && (ev.ui.url || ev.ui.id)) {
          S.frame = { title: ev.ui.title || ev.ui.name || "artefact", url: ev.ui.url || "/iframe/" + ev.ui.id };
        }
        S.status = "working…";
        renderSoon();
        break;
      }
      case "message-user": {
        const text = String(ev.text || "");
        if (text) {
          S.messages.push({ id: "user-" + Date.now(), type: "user", blocks: [{ kind: "text", text }], text, usage: null, retryable: false, createdAt: Date.now() });
          // Follow only if the reader is already at the bottom. A message can
          // arrive from another tab, a queued send, or a finished background
          // job; none of those is a reason to drag someone away from what they
          // are reading.
          renderSoon();
        }
        break;
      }
      case "queued": {
        // The POST response and this event both announce the same queued
        // message; only the first of them adds a row.
        const q = String(ev.text || ev.message || "");
        if (q && !S.queue.includes(q)) S.queue.push(q);
        break;
      }
      case "warning":
        toast(ev.text || "warning from the server", "warn");
        break;
      case "error":
      case "fatal": {
        const text = String(ev.text || ev.message || "the turn failed");
        S.messages.push({
          id: "err-" + Date.now(), type: "error", blocks: [], text,
          usage: null, retryable: ev.retryable !== false, createdAt: Date.now(),
        });
        S.running = false;
        S.live = null;
        toast(text, "error");
        renderSoon();
        break;
      }
      case "compaction-start":
        S.status = "compacting context…";
        break;
      case "compaction-end":
        S.status = "ready";
        break;
      case "state": {
        const w = ev.agent_working !== undefined ? ev.agent_working : ev.agentWorking;
        if (w !== undefined) {
          S.running = truthy(w);
          if (!S.running) S.live = null;
        }
        break;
      }
      case "turn-end":
      case "done": {
        const status = String(ev.status || (ev.error ? "error" : "complete"));
        S.running = false;
        S.status = status === "complete" ? "ready" : "turn " + status;
        S.live = null;
        S.queue = [];
        // The database is the truth: re-read the transcript rather than trusting
        // the deltas we accumulated.
        // Not forced: a turn ending must not drag a reader who scrolled up back
        // to the bottom. If they are at the bottom, renderSoon re-pins anyway.
        refreshTranscript(false);
        refreshUsage();
        loadConversations();
        break;
      }
      default:
        // Unknown event types must never crash the UI.
        break;
    }
  }

  function findLiveTool(id) {
    if (!S.live || !id) return null;
    for (let i = S.live.items.length - 1; i >= 0; i--) {
      const it = S.live.items[i];
      if (it.kind === "tool" && it.id === String(id)) return it;
    }
    return null;
  }

  function ensureLive() {
    if (!S.live) S.live = { items: [], thinkingIdx: null, startedAt: Date.now() };
    S.running = true;
  }

  // =================================================================== sse
  const sse = {
    fails: 0,
    probed: false, es: null, id: null, urlIdx: 0, lastId: null, timer: null, stopped: false };
  const KNOWN_EVENTS = [
    "history", "text-delta", "thinking-delta", "tool-start", "tool-progress", "tool-end",
    "message-user", "queued", "warning", "error", "compaction-start", "compaction-end",
    "turn-end", "state", "done",
  ];

  function disconnectStream() {
    sse.stopped = true;
    if (sse.timer) { clearTimeout(sse.timer); sse.timer = null; }
    if (sse.es) { try { sse.es.close(); } catch (_) {} sse.es = null; }
    S.streamState = "idle";
  }

  function connectStream(id) {
    disconnectStream();
    sse.stopped = false;
    sse.id = id;
    sse.lastId = null;
    sse.urlIdx = typeof routeWin["stream"] === "number" ? routeWin["stream"] : 0;
    sse.fails = 0;
    sse.probed = false;
    openStream();
  }

  function openStream() {
    if (sse.stopped || !sse.id) return;
    const candidates = EP.stream(sse.id);
    const base = candidates[Math.min(sse.urlIdx, candidates.length - 1)];
    const url = sse.lastId
      ? base + (base.indexOf("?") >= 0 ? "&" : "?") + "last_event_id=" + enc(sse.lastId)
      : base;
    let es;
    try { es = new EventSource(url); } catch (e) { S.streamState = "failed"; return; }
    sse.es = es;
    S.streamState = "connecting";

    es.onopen = () => {
      const rejoined = sse.fails > 0;
      S.streamState = "open";
      sse.fails = 0;
      // Events published while the connection was down are gone, so a
      // reconnection re-reads the transcript instead of trusting its own tail.
      if (rejoined) { refreshTranscript(false); refreshUsage(); }
    };
    es.onmessage = (e) => onStreamMessage(e);
    for (const name of KNOWN_EVENTS) {
      es.addEventListener(name, (e) => onStreamMessage(e, name));
    }
    es.onerror = () => {
      if (!sse.es || sse.es !== es) return;
      sse.fails = (sse.fails || 0) + 1;
      // A browser will not open an EventSource whose response is not
      // text/event-stream, and it reports that as a bare error. After a few
      // failures, ask the server what it is actually sending so the reason is
      // visible instead of looking like a flapping connection.
      if (sse.fails === 3 && !sse.probed) {
        sse.probed = true;
        probeStream(base);
      }
      if (es.readyState === 2 /* CLOSED */) {
        if (sse.urlIdx + 1 < candidates.length) {
          sse.urlIdx += 1;
          openStream();
          return;
        }
        S.streamState = "disconnected";
        if (!sse.stopped) {
          sse.timer = setTimeout(() => { sse.timer = null; if (!sse.stopped) openStream(); }, 3000);
        }
      } else {
        S.streamState = "reconnecting";
      }
    };
  }

  async function probeStream(url) {
    try {
      const ctl = new AbortController();
      const res = await fetch(url, { headers: { accept: "text/event-stream" }, signal: ctl.signal });
      const ct = (res.headers.get("content-type") || "").toLowerCase();
      ctl.abort();
      if (!ct.includes("text/event-stream")) {
        toast("live updates are off: the stream endpoint answered “" + (ct || "no content type") +
              "”, and a browser needs text/event-stream. The transcript still works.", "warn", 9000);
      }
    } catch (_) { /* the server is unreachable; the retry loop already says so */ }
  }

  function onStreamMessage(e, named) {
    // A server that names its events delivers them only to the named listener;
    // one that sends bare `data:` lines delivers them to onmessage. Either way
    // the payload is the same JSON and is handled exactly once.
    if (e && e.lastEventId) sse.lastId = e.lastEventId;
    if (!e || !e.data) return;
    let ev;
    try { ev = JSON.parse(e.data); } catch (_) { return; }
    if (named && !ev.type) ev.type = named;
    handleEvent(ev);
  }

  // --------------------------------------------------------------- rendering
  const atBottom = ref(true);
  let scrollQueued = false;

  function renderSoon(force) {
    if (scrollQueued) return;
    scrollQueued = true;
    nextTick(() => {
      scrollQueued = false;
      if (atBottom.value || force) scrollToBottom(false);
    });
  }

  const logEl = ref(null);

  function scrollToBottom(smooth) {
    const el = logEl.value;
    if (!el) return;
    el.style.scrollBehavior = smooth ? "smooth" : "auto";
    el.scrollTop = el.scrollHeight;
    atBottom.value = true;
  }

  function onLogScroll() {
    const el = logEl.value;
    if (!el) return;
    atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  function jumpToBottom() {
    // A deliberate jump is the one case where scrolling smooth is right: the
    // reader asked to go somewhere and should see the motion.
    atBottom.value = true;
    scrollToBottom(true);
    nextTick(() => renderSoon(true));
  }

  // The transcript changes height without any event we emit: an image loads, a
  // tool card is expanded, a long line wraps when the window narrows. Watching
  // the element itself is the only way to keep the bottom pinned through that,
  // and it is what "tracks the bottom" means in practice.
  let logObserver = null;
  function watchLogSize() {
    const el = logEl.value;
    if (!el || typeof ResizeObserver === "undefined") return;
    if (logObserver) logObserver.disconnect();
    const inner = el.firstElementChild || el;
    logObserver = new ResizeObserver(() => {
      if (atBottom.value) scrollToBottom(false);
    });
    logObserver.observe(inner);
  }

  // ================================================================ lifecycle
  async function loadConversations(opts) {
    opts = opts || {};
    if (!opts.quiet) S.loadingConvs = true;
    try {
      const key = S.archivedMode ? "archived" : "list";
      let data = await api.get(key, EP[key]);
      let rows = (data && (data.conversations || data.rows)) || [];
      if (S.query) {
        // Search is server-side (q=), and archived rows never appear with it.
        data = await api.get("list", [EP.list[0] + "?q=" + enc(S.query) + "&limit=100"]);
        rows = (data && (data.conversations || data.rows)) || [];
        // A server that ignores q returns everything, so the filter also runs
        // here. Matching twice is harmless; matching never is not.
        const hits = filterRows(rows, S.query);
        if (hits.length) rows = hits;
      }
      S.conversations = rows.map(normalizeRow);
      S.archivedUnavailable = false;
      for (const r of S.conversations) {
        const key = r.conversation_id + ":" + (r.updated_at || "");
        if (previewCache.has(key)) S.previews[key] = previewCache.get(key);
      }
      // Fill in previews after the list is on screen.
      if (!opts.quiet) loadPreviews(S.conversations);
    } catch (e) {
      if (S.archivedMode) {
        // Showing the live list under an "archived" heading would be a lie.
        S.conversations = [];
        S.archivedUnavailable = true;
      } else {
        S.conversations = S.conversations.length ? S.conversations : [];
      }
      toast((S.archivedMode ? "archived: " : "conversations: ") + e.message, "error");
    } finally {
      S.loadingConvs = false;
    }
  }

  /**
   * The list endpoint returns a title but no preview, so the last thing said in
   * a conversation is fetched for the few rows that are actually on screen. One
   * request per row is affordable at that number and wrong at a hundred, hence
   * the cap; results are cached against the row's updated_at so a new turn
   * refreshes only its own row.
   */
  const previewCache = new Map();
  let previewRun = 0;

  async function loadPreviews(rows) {
    const wanted = rows.filter((r) => {
      if (r.preview) return false;
      const key = r.conversation_id + ":" + (r.updated_at || "");
      return !previewCache.has(key);
    }).slice(0, 8);
    if (!wanted.length) return;
    const run = ++previewRun;
    const queue = wanted.slice();
    const worker = async () => {
      while (queue.length && run === previewRun) {
        const r = queue.shift();
        const key = r.conversation_id + ":" + (r.updated_at || "");
        try {
          const d = await api.get("get", EP.get(r.conversation_id));
          const msgs = (d && d.messages) || [];
          let text = "";
          for (let i = msgs.length - 1; i >= 0 && !text; i--) {
            const m = normalizeMessage(msgs[i]);
            if (m.text && m.type !== "error" && m.type !== "skip") text = m.text;
          }
          previewCache.set(key, text.replace(/\s+/g, " ").slice(0, 140));
          S.previews[key] = previewCache.get(key);
        } catch (_) {
          previewCache.set(key, "");      // do not retry a row that will not load
        }
      }
    };
    await Promise.all([worker(), worker(), worker()]);
  }

  function filterRows(rows, q) {
    const needle = String(q || "").toLowerCase().trim();
    if (!needle) return rows;
    const words = needle.split(/\s+/).filter(Boolean);
    return rows.filter((r) => {
      const hay = [r.title, r.slug, r.preview, r.summary, (r.tags || []).join(" ")]
        .filter(Boolean).join(" ").toLowerCase();
      return words.every((w) => hay.includes(w));
    });
  }

  function normalizeRow(row) {
    const r = Object.assign({}, row);
    r.conversation_id = String(r.conversation_id || r.id || "");
    r.tags = Array.isArray(r.tags) ? r.tags : r.tags ? [String(r.tags)] : [];
    r.title = r.title || r.slug || r.conversation_id.slice(0, 8);
    r.updated_at = r.updated_at || r.updatedAt || r.created_at;
    r.parent_conversation_id = r.parent_conversation_id || r.parentConversationId || "";
    return r;
  }

  const rootConversations = computed(() => {
    const rows = S.conversations.filter((c) => !S.tagFilter || (c.tags || []).includes(S.tagFilter));
    const ids = new Set(rows.map((c) => c.conversation_id));
    return rows.filter((c) => !c.parent_conversation_id || !ids.has(c.parent_conversation_id));
  });

  const childrenOf = (id) =>
    S.conversations.filter((c) => c.parent_conversation_id === id && (!S.tagFilter || (c.tags || []).includes(S.tagFilter)));

  const conversationGroups = computed(() => {
    const roots = rootConversations.value;
    const groups = new Map();
    for (const c of roots) {
      const label = S.archivedMode ? "" : dayBucket(c.updated_at);
      if (!groups.has(label)) groups.set(label, []);
      groups.get(label).push(c);
    }
    const order = ["today", "yesterday", "this week", "this month", "earlier", ""];
    return order.filter((l) => groups.has(l)).map((label) => ({ label: label || "", rows: groups.get(label) }));
  });

  const allTags = computed(() => {
    const set = new Set();
    for (const c of S.conversations) for (const t of c.tags || []) set.add(t);
    return Array.from(set).sort();
  });

  async function openConversation(id, opts) {
    opts = opts || {};
    S.currentId = id;
    localStorage.setItem("shell.lastConv", id);
    S.messages = [];
    S.live = null;
    S.queue = [];
    S.usage = null;
    S.status = "loading…";
    S.selections = [];
    if (isMobile.value) S.drawerOpen = false;
    try {
      const data = await api.get("get", EP.get(id));
      const conv = (data && (data.conversation || data)) || {};
      S.conversation = normalizeRow(conv);
      S.conversation.cwd = conv.cwd || conv.working_dir || conv.workingDir || "";
      S.messages = ((data && data.messages) || []).map(normalizeMessage);
      S.running = !!(data && (data.running || data.agent_working || (S.conversation && S.conversation.working)));
      S.status = S.running ? "working…" : "ready";
      if (conv.model) S.model = conv.model;
      const o = parseMaybeJSON(conv.conversation_options) || conv.conversation_options;
      if (o && typeof o === "object") {
        if (o.thinking_level) S.thinking = o.thinking_level;
        S.toolOverrides = o.tool_overrides || S.toolOverrides;
        S.disableAllTools = !!o.disable_all_tools;
        if (o.system_prompt) S.systemPrompt = o.system_prompt;
      }
    } catch (e) {
      toast("conversation: " + e.message, "error");
      S.status = "error loading conversation";
      S.conversation = Object.assign({}, S.conversation, { conversation_id: id });
    }
    connectStream(id);
    refreshUsage();
    if (!opts.noGit) refreshGit(true);
    renderSoon(true);
  }

  async function refreshTranscript(force) {
    if (!S.currentId) return;
    try {
      const data = await api.get("get", EP.get(S.currentId));
      S.messages = ((data && data.messages) || []).map(normalizeMessage);
      // A queued message stops being queued once it is a stored user row.
      const stored = new Set(S.messages.filter((m) => m.type === "user").map((m) => m.text.trim()));
      if (S.queue.length) S.queue = S.queue.filter((q) => !stored.has(String(q).trim()));
      const conv = (data && data.conversation) || {};
      if (conv && conv.conversation_id) S.conversation = Object.assign({}, S.conversation, normalizeRow(conv));
      if (data && (data.running !== undefined || data.agent_working !== undefined)) {
        S.running = !!(data.running || data.agent_working);
      }
      S.live = null;
      renderSoon(force);
    } catch (e) {
      toast("transcript refresh: " + e.message, "error");
    }
  }

  async function refreshUsage() {
    if (!S.currentId) return;
    try {
      const data = await api.get("usage", EP.usage(S.currentId));
      S.usage = data && (data.usage || data);
      S.usageMeta = {
        estimated: num(data && data.estimated_tokens, data && data["estimated-tokens"]),
        messages: num(data && data.message_count, data && data["message-count"]),
      };
    } catch (e) {
      // A usage endpoint may not exist yet; the bar shows the last known values.
      if (!e.notFound) toast("usage: " + e.message, "error");
    }
  }

  const usage = computed(() => {
    const u = S.usage || {};
    const meta = S.usageMeta || {};
    const input = num(u.input_tokens, u["input-tokens"], u.inputTokens, u.prompt_tokens, u.tokens_in, u.input);
    const output = num(u.output_tokens, u["output-tokens"], u.outputTokens, u.completion_tokens, u.tokens_out, u.output);
    const cached = num(u.cache_read_input_tokens, u.cache_read_tokens, u["cache-read-tokens"],
                       u.cached_tokens, u.cache_read, u.cached) +
      num(u.cache_creation_input_tokens, u.cache_write_tokens, u["cache-write-tokens"], u.cache_write);
    // A model with no list price reports null, and that is not "$0.00": the
    // difference between free and unknown matters to whoever pays the bill.
    const costRaw = [u.cost_usd, u["cost-usd"], u.cost, u.total_cost, u.costUSD]
      .find((v) => v !== undefined && v !== null && v !== "");
    const cost = costRaw === undefined ? null : Number(costRaw);
    const recorded = input + output + cached + (cost || 0) > 0;
    // A server-side estimate of 0 is "unknown", not "empty": fall through to the
    // tokens we actually counted, or the meter reads ctx 0 beside 8.3k in.
    const contextTokens = num(u.context_tokens, u.contextTokens, u.context)
      || num(meta.estimated)
      || (input + output);
    const modelRow = S.models.find((m) => m.id === S.model) || {};
    const contextLimit = num(u.context_limit, u.contextLimit, u.max_context_tokens, modelRow.max_context_tokens) || 0;
    const calls = num(u.llm_calls, u.calls, u.request_count, u["llm-calls"]) || 0;
    return { input, output, cached, cost, contextTokens, contextLimit, calls, recorded, known: Object.keys(u).length > 0 };
  });

  function num() {
    for (const v of arguments) {
      if (v !== undefined && v !== null && v !== "" && !Number.isNaN(Number(v))) return Number(v);
    }
    return 0;
  }

  const ctxPct = computed(() => {
    const u = usage.value;
    if (!u.contextLimit) return 0;
    return Math.min(100, Math.round((u.contextTokens / u.contextLimit) * 100));
  });
  const ctxClass = computed(() => (ctxPct.value > 90 ? "hot" : ctxPct.value > 70 ? "warn" : ""));

  // ================================================================ messages
  const groups = computed(() => buildGroups(S.messages));
  const live = computed(() => liveGroup(S.live && S.live.startedAt));
  const headerTitle = computed(() => S.conversation.title || (S.currentId ? "Conversation" : "clj-shelley"));

  const canSend = computed(() => (S.draft.trim().length > 0 || S.attachments.length > 0));
  const placeholder = computed(() =>
    S.running ? "The turn is running — this will be queued for the next round…" : "Ask for something to be done…");

  const shortPath = (p) => {
    if (!p) return "";
    const m = /^\/home\/[^/]+/.exec(p);
    return m ? "~" + p.slice(m[0].length) : p;
  };

  // ================================================================= actions
  async function newConversation() {
    S.draft = "";
    S.attachments = [];
    S.currentId = null;
    S.messages = [];
    S.conversation = {};
    S.live = null;
    S.usage = null;
    disconnectStream();
    if (isMobile.value) S.drawerOpen = false;
    nextTick(() => { const el = document.getElementById("composer-input"); if (el) el.focus(); });
    const ta = document.querySelector(".composer textarea");
    if (ta) ta.focus();
    S.status = "new conversation — send a message to start";
    // The conversation is created by the first message so an empty one never
    // clutters the drawer.
    S.pendingNew = true;
  }

  async function send() {
    const text = S.draft.trim();
    if (!text && !S.attachments.length) return;
    let body = text;
    if (S.attachments.length) {
      const refs = await uploadAttachments();
      if (refs.length) body = (text ? text + "\n\n" : "") + refs.join("\n");
    }
    if (!body.trim()) { toast("nothing to send — the attachments could not be uploaded", "error"); return; }

    S.draft = "";
    S.attachments = [];
    autoGrowNow();

    const opts = conversationOptions();

    try {
      if (!S.currentId) {
        const data = await api.post("create", EP.create, {
          message: body, text: body, model: S.model || undefined, cwd: S.conversation.cwd || undefined,
          conversation_options: opts,
        });
        const conv = (data && (data.conversation || data)) || {};
        const id = conv.conversation_id || conv.id || data.conversation_id;
        if (!id) throw new Error("the server did not return a conversation id");
        await loadConversations({ quiet: true });
        await openConversation(id);
        // Some servers open a conversation and start the turn in one call;
        // others only create the row. If the transcript is still empty, send the
        // message now rather than leaving a silent conversation behind.
        const stored = S.messages.some((m) => m.type === "user");
        if (!stored) {
          try {
            const r = await api.post("chat", EP.chat(id), {
              message: body, text: body, conversation_options: opts,
            });
            if (r && (r.status === "queued" || r.queued)) {
              if (!S.queue.includes(body)) S.queue.push(body);
            }
          } catch (e2) {
            toast("send failed: " + e2.message, "error");
            S.status = "send failed";
            S.draft = body;
          }
        }
        S.running = true;
        return;
      }

      // optimistic echo; the DB row replaces it on the next refresh
      S.messages.push({
        id: "local-" + Date.now(), type: "user",
        blocks: [{ kind: "text", text: body }], text: body, usage: null, retryable: false, createdAt: Date.now(),
      });
      renderSoon(true);

      const data = await api.post("chat", EP.chat(S.currentId), {
        message: body, text: body, conversation_options: opts,
      });
      if (data && (data.status === "queued" || data.queued)) {
        if (!S.queue.includes(body)) S.queue.push(body);
        S.status = "queued for the next round";
      } else {
        S.running = true;
        S.status = "working…";
      }
    } catch (e) {
      toast("send failed: " + e.message, "error");
      S.draft = S.draft || body;
      S.status = "send failed";
      S.running = false;
    }
  }

  function conversationOptions() {
    const o = {};
    if (S.thinking) o.thinking_level = S.thinking;
    if (Object.keys(S.toolOverrides).length) o.tool_overrides = S.toolOverrides;
    if (S.disableAllTools) o.disable_all_tools = true;
    if (S.systemPrompt && S.systemPrompt.trim()) o.system_prompt = S.systemPrompt;
    return o;
  }

  async function uploadAttachments() {
    const refs = [];
    for (const a of S.attachments) {
      try {
        if (a.path) { refs.push("Attached file: " + a.path); continue; }
        const data = await api.post("upload", EP.upload, { name: a.name, content: a.dataUrl || a.text || "", data: a.dataUrl });
        const p = (data && (data.path || data.file || data.url)) || "";
        if (p) { refs.push("Attached file: " + p); continue; }
        throw new Error("no path returned");
      } catch (e) {
        // No upload endpoint: a text attachment can still be written into the
        // workspace through the file API, which the agent can then read.
        if (a.text != null && !a.dataUrl) {
          const p = (S.conversation.cwd || ".") + "/.shelley-attachments/" + Date.now() + "-" + a.name;
          try {
            await api.put("file", EP.file(p), { cwd: rootFor(p), path: p, content: a.text });
            refs.push("Attached file: " + p);
            continue;
          } catch (_) { /* fall through to the toast */ }
        }
        toast("this server cannot accept “" + a.name + "” (no upload endpoint)", "warn");
      }
    }
    return refs;
  }

  async function cancelTurn() {
    if (!S.currentId) return;
    S.status = "cancelling…";
    try {
      await api.post("cancel", EP.cancel(S.currentId), {});
    } catch (e) {
      toast("cancel failed: " + e.message, "error");
    }
  }

  async function retry() {
    if (!S.currentId) return;
    S.status = "retrying…";
    try {
      await api.post("retry", EP.retry(S.currentId), {});
      S.running = true;
    } catch (e) {
      // No retry endpoint: re-send the last thing the user asked for.
      const last = [...S.messages].reverse().find((m) => m.type === "user" && String(m.text || "").trim());
      const text = last ? last.text : "Continue.";
      try {
        await api.post("chat", EP.chat(S.currentId), { message: text, text });
        S.running = true;
      } catch (e2) {
        toast("retry failed: " + e.message, "error");
      }
    }
  }

  async function compactNow() {
    if (!S.currentId) return;
    try {
      await api.post("compact", EP.compact(S.currentId), {});
      toast("compaction requested", "ok");
    } catch (e) {
      toast("compact: " + e.message, "error");
    }
  }

  async function toggleArchive(conv) {
    const id = conv.conversation_id;
    const next = !conv.archived;
    try {
      await api.post("archive", EP.archive(id), { archived: next });
      conv.archived = next;
      S.conversations = S.conversations.filter((c) => c.conversation_id !== id || S.archivedMode === next);
      await loadConversations({ quiet: true });
      toast(next ? "archived" : "restored", "ok");
    } catch (e) {
      toast("archive: " + e.message, "error");
    }
  }

  async function archiveAllConversations() {
    if (!confirm("Archive all conversations? They move to the archived view, where you can restore them one by one.")) return;
    try {
      const r = await api.post("archive-all", EP.archiveAll, {});
      const n = (r && r.count) || 0;
      // Archiving everything leaves nothing to show, so drop the open transcript
      // rather than leave a conversation the server would now refuse to chat in.
      if (S.currentId) { S.currentId = null; S.messages = []; disconnectStream(); }
      S.archivedMode = false;
      await loadConversations({ quiet: true });
      toast(n ? "archived " + n + " conversation" + (n === 1 ? "" : "s") : "nothing to archive", n ? "ok" : "info");
    } catch (e) {
      toast("archive all: " + e.message, "error");
    }
  }

  async function removeConversation(conv) {
    const id = conv.conversation_id;
    if (!confirm("Delete “" + (conv.title || id) + "”? This cannot be undone.")) return;
    try {
      await api.del("remove", EP.remove(id));
    } catch (e) {
      if (e.notFound) {
        toast("this server has no delete endpoint — archiving instead", "warn");
        try { await api.post("archive", EP.archive(id), { archived: true }); } catch (_) {}
      } else {
        toast("delete: " + e.message, "error");
        return;
      }
    }
    if (S.currentId === id) { S.currentId = null; S.messages = []; disconnectStream(); }
    await loadConversations({ quiet: true });
  }

  function setModel() {
    localStorage.setItem("shell.model", S.model || "");
    pushOptions();
  }
  function setThinking() {
    localStorage.setItem("shell.thinking", S.thinking || "");
    pushOptions();
  }

  /** Options that are not part of the chat body are pushed best-effort. */
  const pushOptions = debounce(async () => {
    if (!S.currentId) return;
    try { await api.post("options", EP.options(S.currentId), Object.assign({ model: S.model }, conversationOptions())); }
    catch (_) { /* API.md has no options endpoint yet; chat carries them */ }
  }, 200);

  async function saveSettings() {
    try {
      await api.post("settings", EP.settings, {
        model: S.model, thinking_level: S.thinking, system_prompt: S.systemPrompt,
        tool_overrides: S.toolOverrides, disable_all_tools: S.disableAllTools,
      });
      toast("settings saved", "ok");
    } catch (e) {
      toast("settings: " + e.message, "error");
    }
    pushOptions();
  }

  async function loadSettings() {
    try {
      const d = await api.get("settings", EP.settings);
      const s = (d && (d.settings || d)) || {};
      if (s.model && !S.model) S.model = s.model;
      if (s.thinking_level && !S.thinking) S.thinking = s.thinking_level;
      if (s.system_prompt) S.systemPrompt = s.system_prompt;
      if (s.tool_overrides) S.toolOverrides = s.tool_overrides;
      if (s.disable_all_tools) S.disableAllTools = true;
    } catch (e) { if (!e.notFound) toast("settings: " + e.message, "error"); }
  }

  async function loadModels() {
    try {
      const d = await api.get("providers", EP.providers);
      const list = [];
      const push = (m) => {
        if (!m) return;
        if (typeof m === "string") { list.push({ id: m, display_name: m }); return; }
        const id = m.id || m.name || m.model;
        if (!id) return;
        if (Array.isArray(m.models)) { m.models.forEach(push); return; }
        list.push(Object.assign({}, m, { id, display_name: m.display_name || m.displayName || id }));
      };
      if (Array.isArray(d)) d.forEach(push);
      else if (d) {
        if (Array.isArray(d.models)) d.models.forEach(push);
        if (Array.isArray(d.providers)) d.providers.forEach(push);
        if (d.providers && !Array.isArray(d.providers)) Object.values(d.providers).forEach(push);
      }
      S.models = list;
      if (!S.model && list.length) {
        const def = list.find((m) => m.is_default) || list[0];
        S.model = def.id;
      }
    } catch (e) {
      // No provider catalogue: still offer the model the server reports so the
      // picker is not empty, and let Settings take a free-form id.
      if (S.health.model && !S.models.length) {
        S.models = [{ id: S.health.model, display_name: S.health.model + " (server default)" }];
      }
      if (!e.notFound) toast("models: " + e.message, "error");
    }
  }

  async function loadCapabilities() {
    try { S.version = (await api.get("version", EP.version)) || {}; } catch (_) { S.version = {}; }
    try { S.health = (await api.get("health", EP.health)) || {}; } catch (_) { S.health = {}; }
    if (!S.model && S.health.model) S.model = S.health.model;
  }

  async function loadProfiles() {
    try {
      const d = await api.get("profiles", EP.profiles);
      S.profiles = (d && (d.profiles || d)) || [];
    } catch (_) { S.profiles = []; }
  }

  async function activateProfile(p) {
    const name = p.name || p;
    try {
      await api.post("profile", ["/api/profiles/" + enc(name)], {});
      toast("profile " + name + " activated", "ok");
      loadProfiles();
    } catch (e) { toast("profile: " + e.message, "error"); }
  }

  async function loadFlags() {
    try {
      const d = await api.get("flags", EP.flags);
      S.flags = (d && (d.flags || d)) || {};
    } catch (_) { S.flags = {}; }
  }

  async function toggleFlag(name, current) {
    const next = !truthy(current);
    try {
      await api.post("flag", ["/api/feature-flags/" + enc(name)], { enabled: next });
      S.flags[name] = next;
    } catch (e) { toast("flag: " + e.message, "error"); }
  }

  async function loadIntegrations() {
    try {
      const d = await api.get("integrations", EP.integrations);
      const list = (d && (d.integrations || d.channels || d)) || [];
      S.integrations = Array.isArray(list) ? list : Object.entries(list).map(([k, v]) => Object.assign({ name: k }, v));
    } catch (_) { S.integrations = []; }
  }

  async function testSlack() {
    try { await api.post("slackTest", EP.slackTest, {}); toast("slack test sent", "ok"); }
    catch (e) { toast("slack: " + e.message, "error"); }
  }

  async function loadCosts() {
    try { S.costs = (await api.get("costs", EP.costs)) || {}; } catch (_) { S.costs = {}; }
  }

  // ------------------------------------------------------------------- MCP
  async function loadMcp() {
    try {
      const d = await api.get("mcpServers", EP.mcpServers);
      const list = (d && (d.servers || d)) || [];
      S.mcp.servers = Array.isArray(list) ? list : Object.entries(list).map(([k, v]) => Object.assign({ name: k }, v));
    } catch (_) { S.mcp.servers = []; }
  }

  async function addMcp() {
    const env = {};
    for (const line of String(mcpForm.env || "").split("\n")) {
      const i = line.indexOf("=");
      if (i > 0) env[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    const body = {
      name: mcpForm.name.trim(),
      command: mcpForm.command.trim(),
      args: mcpForm.args.split(/\s+/).filter(Boolean),
      env,
    };
    try {
      await api.post("mcpServers", EP.mcpServers, body);
      mcpForm.name = ""; mcpForm.command = ""; mcpForm.args = ""; mcpForm.env = "";
      await loadMcp();
      toast("MCP server added", "ok");
    } catch (e) { toast("mcp: " + e.message, "error"); }
  }

  async function removeMcp(s) {
    if (!confirm("Remove MCP server " + s.name + "?")) return;
    try {
      await api.del("mcp", ["/api/mcp/servers/" + enc(s.name)]);
      await loadMcp();
    } catch (e) { toast("mcp: " + e.message, "error"); }
  }

  async function testMcp(s) {
    try {
      const d = await api.get("mcpTools", ["/api/mcp/servers/" + enc(s.name) + "/tools"]);
      const tools = (d && (d.tools || d)) || [];
      S.mcp.tools = Array.isArray(tools) ? tools : [];
      S.mcp.toolsFor = s.name;
      toast(s.name + ": " + S.mcp.tools.length + " tool(s)", "ok");
    } catch (e) { toast("mcp " + s.name + ": " + e.message, "error"); }
  }

  // ------------------------------------------------------------------ files
  /**
   * Rank paths against a query: substring hits first, then paths that contain
   * the query's characters in order. Applied client-side because a server may
   * ignore the q parameter (ours did), which would otherwise show the whole
   * workspace for every query.
   */
  function rankPaths(paths, q) {
    const needle = String(q || "").toLowerCase();
    if (!needle) return paths;
    const scored = [];
    for (const p of paths) {
      const hay = String(p.path || p).toLowerCase();
      const at = hay.indexOf(needle);
      if (at >= 0) { scored.push([1, at, p]); continue; }
      let i = 0;
      for (const ch of hay) { if (ch === needle[i]) i++; if (i === needle.length) break; }
      if (i === needle.length) scored.push([2, hay.length, p]);
    }
    scored.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    return scored.map((x) => x[2]);
  }

  async function findFiles(q) {
    S.findState = "loading";
    S.findQuery = q;
    try {
      const d = await api.get("findFiles", EP.findFiles(q, S.conversation.cwd));
      let list = (d && (d.files || d.results || d.paths || d)) || [];
      list = Array.isArray(list) ? list : [];
      const all = list.map((f) => (typeof f === "string" ? { path: f } : f));
      const ranked = rankPaths(all, q);
      // If the ranking produced nothing, the server's own ordering is all we
      // have; show it rather than an empty list.
      S.files = (ranked.length ? ranked : all).slice(0, 200);
      S.findState = S.files.length ? "ok" : "empty";
    } catch (e) {
      S.files = [];
      S.findState = "error";
      toast("find files: " + e.message, "error");
    }
  }

  async function openFile(path) {
    S.modal = null;
    S.file = { open: true, path, content: "", truncated: false, loading: true, editing: false, error: "", saving: false, original: "" };
    try {
      const d = await api.get("file", EP.file(path));
      const c = (d && (d.content !== undefined ? d.content : d.text)) || "";
      S.file.content = c;
      S.file.original = c;
      S.file.truncated = !!(d && d.truncated);
    } catch (e) {
      S.file.error = e.message;
    } finally { S.file.loading = false; }
  }

  async function saveFile() {
    S.file.saving = true;
    try {
      await api.put("file", EP.file(S.file.path), {
        cwd: rootFor(S.file.path), path: S.file.path, content: S.file.content,
      });
      S.file.original = S.file.content;
      S.file.editing = false;
      toast("saved " + S.file.path, "ok");
    } catch (e) { toast("save: " + e.message, "error"); }
    finally { S.file.saving = false; }
  }

  async function listDirectory(path) {
    try {
      const d = await api.get("listDir", EP.listDir(path, S.conversation.cwd));
      const entries = (d && (d.entries || d.dirs || d.files || d)) || [];
      const arr = Array.isArray(entries) ? entries : [];
      S.dirEntries = arr.map((e) => (typeof e === "string" ? { name: e, dir: true } : e));
      S.dirPath = (d && d.path) || path;
      // The server echoes the directory it actually listed. A mismatch means it
      // ignored the path parameter, and the picker should say so instead of
      // appearing to be stuck on one directory.
      S.dirIgnored = !!(d && d.path && path && path !== "." && !String(d.path).replace(/\/$/, "").endsWith(String(path).replace(/^\.\//, "").replace(/\/$/, "")));
    } catch (e) {
      toast("list directory: " + e.message, "error");
      S.dirEntries = [];
    }
  }

  // -------------------------------------------------------------------- git
  async function refreshGit(quiet) {
    const cwd = S.conversation.cwd || "";
    try {
      const d = (await api.get("gitStatus", EP.gitStatus(cwd))) || {};
      S.git.branch = d.branch || "";
      S.git.commit = d.commit || d.head || "";
      S.git.subject = d.subject || d.commit_subject || "";
      S.git.dirty = !!d.dirty;
      S.git.repo_root = d.repo_root || "";
      S.git.error = "";
      S.git.filesRaw = d.files || d.changes || d.changed || null;
    } catch (e) {
      S.git.branch = "";
      if (!e.notFound && !quiet) S.git.error = e.message;
      else S.git.error = e.notFound ? "git status is not available on this server" : e.message;
    }
  }

  async function loadGitDiff(path) {
    S.git.diffLoading = true;
    S.git.diff = "";
    try {
      const d = await api.get("gitDiff", EP.gitDiff(S.conversation.cwd, path));
      S.git.diff = typeof d === "string" ? d : (d && (d.diff || d.patch || d.text)) || "";
    } catch (e) {
      S.git.diff = "";
      S.git.error = e.message;
    } finally { S.git.diffLoading = false; }
  }

  // -------------------------------------------------------------- terminal
  async function exportMarkdown() {
    const rows = groups.value;
    if (!rows.length) { toast("nothing to export", "warn"); return; }
    const lines = ["# " + (S.conversation.title || S.currentId || "conversation"), ""];
    lines.push("*exported " + new Date().toISOString() + "*\n");
    for (const g of rows) {
      if (g.type === "user") {
        lines.push("## you\n");
        lines.push(g.text + "\n");
      } else if (g.type === "agent") {
        for (const it of g.items) {
          if (it.kind === "text") lines.push(it.text + "\n");
          else if (it.kind === "thinking") lines.push("<details><summary>thinking</summary>\n\n" + it.text + "\n\n</details>\n");
          else if (it.kind === "tool") {
            lines.push("**tool: " + it.name + "**\n");
            if (it.input != null) lines.push("```json\n" + inputText(it.input) + "\n```\n");
            if (it.result) lines.push("```\n" + (it.result.text || "(no output)") + "\n```\n");
          }
        }
      } else if (g.type === "error") lines.push("> **error:** " + g.text + "\n");
      else if (g.type === "warning") lines.push("> **warning:** " + g.text + "\n");
    }
    const blob = new Blob([lines.join("\n")], { type: "text/markdown" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = (S.conversation.slug || S.currentId || "conversation") + ".md";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast("exported", "ok");
  }

  // =================================================================== boot
  const mcpForm = reactive({ name: "", command: "", args: "", env: "" });
  Object.assign(S, {
    files: [], dirEntries: [], dirPath: "", dirIgnored: false, findQuery: "", selections: [], pendingNew: false,
    previews: {},
    newTool: "", modelMeta: "",
  });

  const searchDebounced = debounce(() => loadConversations({ quiet: true }), 250);
  const findDebounced = debounce((q) => findFiles(q), 180);

  function autoGrowNow() {
    const ta = document.querySelector(".composer textarea");
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 220) + "px";
  }

  // ---------------------------------------------------------------- helpers
  function openModal(name) {
    S.modal = name;
    if (name === "settings") { loadSettings(); loadProfiles(); loadFlags(); loadIntegrations(); loadMcp(); loadCosts(); loadCapabilities(); }
    if (name === "finder") nextTick(() => findFiles(""));
  }

  function toggleTheme() { applyTheme(S.theme === "dark" ? "light" : "dark"); }

  function toggleSearch() {
    S.showSearch = !S.showSearch;
    if (S.showSearch) nextTick(() => { const el = document.querySelector(".searchbox input"); if (el) el.focus(); });
    else clearSearch();
  }

  function clearSearch() {
    S.query = "";
    S.showSearch = false;
    loadConversations({ quiet: true });
  }

  function onSearchInput() { searchDebounced(); }

  function toggleArchived() {
    S.archivedMode = !S.archivedMode;
    S.query = "";
    loadConversations();
  }

  // ------------------------------------------------------------- attachments
  const MAX_ATTACH = 6 * 1024 * 1024;

  function addAttachment(file) {
    if (!file) return;
    if (file.size > MAX_ATTACH) { toast("“" + file.name + "” is too large to attach (6 MB limit)", "warn"); return; }
    const isImage = (file.type || "").startsWith("image/");
    const reader = new FileReader();
    reader.onload = () => {
      const a = { name: file.name || "pasted-image", size: file.size, type: file.type || "" };
      if (isImage) { a.dataUrl = String(reader.result); a.preview = a.dataUrl; }
      else a.text = typeof reader.result === "string" ? reader.result : "";
      S.attachments.push(a);
    };
    reader.onerror = () => toast("could not read " + (file.name || "file"), "error");
    if (isImage) reader.readAsDataURL(file);
    else reader.readAsText(file);
  }

  function onFiles(ev) {
    for (const f of Array.from((ev.target && ev.target.files) || [])) addAttachment(f);
    if (ev.target) ev.target.value = "";
  }

  function onPaste(ev) {
    const items = (ev.clipboardData && ev.clipboardData.items) || [];
    let handled = false;
    for (const it of items) {
      if (it.kind === "file") {
        const f = it.getAsFile();
        if (f) { addAttachment(f); handled = true; }
      }
    }
    if (handled) ev.preventDefault();
  }

  function onDrop(ev) {
    const files = (ev.dataTransfer && ev.dataTransfer.files) || [];
    for (const f of Array.from(files)) addAttachment(f);
  }

  function onKeydown(ev) {
    if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
      ev.preventDefault();
      if (canSend.value) send();
    } else if (ev.key === "Escape") {
      ev.target.blur();
    }
  }

  function autoGrow(ev) {
    const ta = ev.target;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 220) + "px";
  }

  function copyText(text) {
    const t = String(text == null ? "" : text);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(t).then(() => toast("copied", "ok"), () => toast("copy failed", "error"));
    } else {
      const ta = document.createElement("textarea");
      ta.value = t; document.body.appendChild(ta); ta.select();
      try { document.execCommand("copy"); toast("copied", "ok"); } catch (_) { toast("copy failed", "error"); }
      document.body.removeChild(ta);
    }
  }

  const thinkingLevels = THINKING_LEVELS;

  const compatSummary = computed(() => {
    const keys = Object.keys(routeNote);
    if (!keys.length) return "no requests yet";
    return keys.sort().map((k) => k + " → " + routeNote[k]).join("\n");
  });

  const modelMeta = computed(() => {
    const m = S.models.find((x) => x.id === S.model);
    if (!m) return "";
    const bits = [];
    if (m.source) bits.push(m.source);
    if (m.max_context_tokens) bits.push(fmtNum(m.max_context_tokens) + " ctx");
    if (m.supports_reasoning) bits.push("reasoning");
    if (m.tier === 2) bits.push("secondary tier");
    return bits.join(" · ");
  });

  // ------------------------------------------------------------------- ANSI
  // A terminal only needs to be good enough to read command output. Colours and
  // carriage returns are honoured; cursor addressing is not.
  const ANSI_FG = {
    30: "#5c6370", 31: "#e06c75", 32: "#98c379", 33: "#e5c07b", 34: "#61afef",
    35: "#c678dd", 36: "#56b6c2", 37: "#c8ccd4", 90: "#7f848e", 91: "#e06c75",
    92: "#98c379", 93: "#e5c07b", 94: "#61afef", 95: "#c678dd", 96: "#56b6c2", 97: "#ffffff",
  };

  function ansiToHtml(input) {
    const s = String(input || "");
    let out = "";
    let buf = "";
    const st = { fg: null, bold: false, dim: false };
    const style = () => {
      const bits = [];
      if (st.fg) bits.push("color:" + st.fg);
      if (st.bold) bits.push("font-weight:700");
      if (st.dim) bits.push("opacity:.7");
      return bits.join(";");
    };
    const flush = () => {
      if (!buf) return;
      const ss = style();
      out += ss ? '<span style="' + ss + '">' + esc(buf) + "</span>" : esc(buf);
      buf = "";
    };
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (c === "\x1b") {
        const m = /^\x1b\[([0-9;?]*)([A-Za-z])/.exec(s.slice(i));
        if (m) {
          const params = m[1], cmd = m[2];
          if (cmd === "m") {
            flush();
            const codes = params === "" ? [0] : params.split(";").map(Number);
            for (const code of codes) {
              if (code === 0) { st.fg = null; st.bold = false; st.dim = false; }
              else if (code === 1) st.bold = true;
              else if (code === 2) st.dim = true;
              else if (code === 22) { st.bold = false; st.dim = false; }
              else if (code === 39) st.fg = null;
              else if (ANSI_FG[code]) st.fg = ANSI_FG[code];
            }
          } else if (cmd === "J" && params === "2") {
            flush(); out = "";
          } else if (cmd === "K") {
            flush();
            const nl = out.lastIndexOf("\n");
            out = out.slice(0, nl + 1);
          }
          i += m[0].length - 1;
          continue;
        }
        const m2 = /^\x1b[\]P^_][^\x07\x1b]*(\x07|\x1b\\)?/.exec(s.slice(i));
        if (m2) { i += m2[0].length - 1; continue; }
        i += 1;
        continue;
      }
      if (c === "\r") { flush(); continue; }
      if (c === "\b") { buf = buf.slice(0, -1); continue; }
      if (c === "\x07") continue;
      buf += c;
    }
    flush();
    return out;
  }

  // ============================================================== components
  const MdView = {
    name: "MdView",
    template: "#tpl-md",
    props: { text: { type: String, default: "" } },
    computed: { html() { return renderMarkdown(this.text); } },
  };

  const DiffView = {
    name: "DiffView",
    template: "#tpl-diff",
    props: { text: { type: String, default: "" } },
    computed: { lines() { return diffLines(this.text); } },
  };

  const ToolCard = {
    name: "ToolCard",
    template: "#tpl-tool",
    props: { item: { type: Object, required: true } },
    data() { return { manual: null }; },
    computed: {
      open() {
        // Collapsed unless the reader opened it. A running tool is not a reason
        // to take over the transcript: the tag and a pulsing dot say enough.
        if (this.manual !== null) return this.manual;
        return Boolean(this.item.result && this.item.result.isError);
      },
      tag() { return toolTag(this.item.name); },
      preview() { return summaryOfInput(this.item.name, this.item.input); },
      inputText() { return inputText(this.item.input); },
      inputDiff() { return looksLikeDiff(this.inputText); },
      images() { return (this.item.result && this.item.result.images) || []; },
      progressText() {
        return this.item.progress || (this.item.result && this.item.result.progress) || "";
      },
      elapsed() {
        const ms = (this.item.result && this.item.result.elapsedMs) || 0;
        if (!ms) return "";
        return ms < 1000 ? ms + "ms" : (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + "s";
      },
    },
    methods: {
      imgFailed,
      onToggle(ev) { this.manual = ev.target.open; },
    },
  };

  const MsgItem = {
    name: "MsgItem",
    template: "#tpl-msg",
    props: { g: { type: Object, required: true }, live: { type: Boolean, default: false } },
    computed: {
      streaming() { return S.running; },
    },
    methods: {
      retry,
      copyRaw() {
        try { copyText(JSON.stringify(this.g.raw, null, 2)); } catch (_) { copyText(this.g.text || ""); }
      },
    },
  };

  const ConvRow = {
    name: "ConvRow",
    template: "#tpl-conv",
    props: { conv: { type: Object, required: true }, depth: { type: Number, default: 0 } },
    computed: {
      S() { return S; },
      title() { return this.conv.title || this.conv.slug || this.conv.conversation_id.slice(0, 8); },
      preview() {
        const key = this.conv.conversation_id + ":" + (this.conv.updated_at || "");
        const fetched = S.previews[key] || "";
        return String(this.conv.preview || fetched || "").replace(/\s+/g, " ").slice(0, 120);
      },
      rel() { return relTime(this.conv.updated_at); },
      kids() { return childrenOf(this.conv.conversation_id); },
    },
    methods: {
      imgFailed,
      select() { openConversation(this.conv.conversation_id); },
      toggleArchive() { toggleArchive(this.conv); },
      remove() { removeConversation(this.conv); },
    },
  };

  const PaletteModal = {
    name: "PaletteModal",
    template: "#tpl-palette",
    emits: ["close"],
    data() { return { q: "", sel: 0 }; },
    computed: {
      actions() {
        const a = [
          { id: "new", icon: "+", label: "New conversation", key: "", run: () => newConversation() },
          { id: "search", icon: "⌕", label: "Search conversations", key: "", run: () => { S.showSearch = true; nextTick(() => { const el = document.querySelector(".searchbox input"); if (el) el.focus(); }); } },
          { id: "files", icon: "📄", label: "Find file", key: "⌘P", run: () => openModal("finder") },
          { id: "dir", icon: "📁", label: "Change working directory", key: "", run: () => openModal("dirpicker") },
          { id: "git", icon: "⎇", label: "Git status and diffs", key: "⌘⇧G", run: () => openModal("git") },
          { id: "term", icon: "▤", label: "Terminal", key: "⌘J", run: () => openModal("terminal") },
          { id: "settings", icon: "⚙", label: "Settings", key: "⌘,", run: () => openModal("settings") },
          { id: "theme", icon: S.theme === "dark" ? "☀" : "☾", label: "Toggle theme", key: "", run: () => toggleTheme() },
          { id: "export", icon: "↓", label: "Export conversation as markdown", key: "", run: () => exportMarkdown() },
          { id: "archived", icon: "🗄", label: S.archivedMode ? "Show active conversations" : "Show archived conversations", key: "", run: () => toggleArchived() },
          { id: "archive-all", icon: "🗃", label: "Archive all conversations", key: "", run: () => archiveAllConversations() },
        ];
        if (S.currentId) {
          a.push({ id: "compact", icon: "⤓", label: "Compact context now", key: "", run: () => compactNow() });
          a.push({ id: "cancel", icon: "■", label: "Stop the running turn", key: "", run: () => cancelTurn() });
          a.push({ id: "archive", icon: "🗄", label: "Archive this conversation", key: "", run: () => {
            const row = S.conversations.find((c) => c.conversation_id === S.currentId);
            if (row) toggleArchive(row);
            else api.post("archive", EP.archive(S.currentId), { archived: true }).catch((e) => toast(e.message, "error"));
          } });
        }
        for (const c of S.conversations.slice(0, 8)) {
          if (c.conversation_id === S.currentId) continue;
          a.push({ id: "conv-" + c.conversation_id, icon: "↗", label: c.title || c.conversation_id.slice(0, 8),
                   key: relTime(c.updated_at), run: () => openConversation(c.conversation_id) });
        }
        return a;
      },
      shown() {
        const q = this.q.trim().toLowerCase();
        if (!q) return this.actions;
        return this.actions.filter((x) => x.label.toLowerCase().includes(q));
      },
    },
    mounted() { nextTick(() => this.$refs.inp && this.$refs.inp.focus()); },
    watch: { q() { this.sel = 0; } },
    methods: {
      move(d) {
        const n = this.shown.length;
        if (!n) return;
        this.sel = (this.sel + d + n) % n;
      },
      run() {
        const it = this.shown[this.sel];
        this.$emit("close");
        if (it) nextTick(() => it.run());
      },
    },
  };

  const FinderModal = {
    name: "FinderModal",
    template: "#tpl-finder",
    emits: ["close"],
    data() { return { q: "", sel: 0 }; },
    computed: {
      S() { return S; },
      shown() { return S.files || []; },
    },
    mounted() {
      nextTick(() => this.$refs.inp && this.$refs.inp.focus());
      findFiles("");
    },
    methods: {
      openModal,
      debouncedSearch() { findDebounced(this.q); this.sel = 0; },
      move(d) { const n = this.shown.length; if (n) this.sel = (this.sel + d + n) % n; },
      pick() {
        const f = this.shown[this.sel];
        if (!f) return;
        const p = typeof f === "string" ? f : f.path || f.name;
        this.$emit("close");
        openFile(p);
      },
      fmtSize,
    },
  };

  const DirPicker = {
    name: "DirPicker",
    template: "#tpl-dir",
    emits: ["close"],
    data() { return { path: S.conversation.cwd || "", sel: 0 }; },
    computed: {
      dirs() {
        const rows = (S.dirEntries || []).filter((e) => e.dir !== false && e.is_dir !== false);
        return rows.map((e) => ({ name: e.name || e.path || String(e) }));
      },
    },
    mounted() { this.load(this.path); },
    methods: {
      async load(p) {
        this.path = p || "";
        await listDirectory(this.path);
        this.sel = 0;
      },
      enter(name) {
        const base = (S.dirPath || this.path || "").replace(/\/+$/, "");
        this.load(base + "/" + name);
      },
      up() {
        const p = (this.path || "").replace(/\/+$/, "");
        const i = p.lastIndexOf("/");
        this.load(i > 0 ? p.slice(0, i) : "/");
      },
      async useThis() {
        const p = S.dirPath || this.path;
        if (S.currentId) {
          try { await api.post("changeDir", EP.changeDir(S.currentId), { path: p }); toast("working directory: " + p, "ok"); }
          catch (e) { toast("change dir: " + e.message, "error"); }
        } else {
          S.conversation = Object.assign({}, S.conversation, { cwd: p });
          toast("working directory: " + p, "ok");
        }
        this.$emit("close");
      },
    },
  };

  const FileViewer = {
    name: "FileViewer",
    template: "#tpl-file",
    emits: ["close"],
    computed: {
      S() { return S; },
      isDiff() { return looksLikeDiff(S.file.content) || /\.(diff|patch)$/.test(S.file.path || ""); },
    },
    methods: {
      startEdit() {
        S.file.editing = true;
        if (S.file.truncated) toast("this file was truncated by the server — saving would drop the rest", "warn");
      },
      save() { saveFile(); },
      copyContent() { copyText(S.file.content); },
      openGitForFile() { S.file.open = false; openModal("git"); },
    },
  };

  const GitPanel = {
    name: "GitPanel",
    template: "#tpl-git",
    emits: ["close"],
    data() { return { files: [], sel: "" }; },
    computed: { S() { return S; } },
    mounted() { this.load(); },
    methods: {
      async load() {
        await refreshGit();
        const raw = S.git.filesRaw;
        let files = [];
        if (Array.isArray(raw)) {
          files = raw.map((f) =>
            typeof f === "string"
              ? { path: f, status: "M" }
              : { path: f.path || f.file || f.name || "", status: normStatus(f.status || f.index || f.worktree || f.state || "M") });
        } else if (raw && typeof raw === "object") {
          files = Object.entries(raw).map(([p, v]) => ({
            path: p,
            status: normStatus(typeof v === "string" ? v : (v && (v.status || v.index || v.state)) || "M"),
          }));
        }
        if (!files.length) {
          // /api/git/status carries no file list in API.md, so a full diff is the
          // only way to enumerate changed files. Parse its headers.
          try {
            const d = await api.get("gitDiff", EP.gitDiff(S.conversation.cwd));
            const text = typeof d === "string" ? d : (d && (d.diff || d.patch || d.text)) || "";
            files = parseDiffFiles(text);
            if (files.length === 1) S.git.diff = text;
          } catch (_) { /* nothing to show */ }
        }
        this.files = files.filter((f) => f.path);
        if (!this.sel && this.files.length) this.select(this.files[0].path);
      },
      select(p) { this.sel = p; loadGitDiff(p); },
      openFile(p) { openFile(p); },
    },
  };

  function normStatus(s) {
    const t = String(s || "").trim();
    if (!t) return "M";
    if (t === "??" || t === "?" || /untracked/i.test(t)) return "A";
    const c = t[0];
    return "MADRCU".includes(c) ? c : "M";
  }

  function parseDiffFiles(text) {
    const out = [];
    const seen = new Set();
    const lines = String(text || "").split("\n");
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      let m = /^diff --git a\/(.+?) b\/(.+)$/.exec(line);
      let path = null;
      let status = "M";
      if (m) path = m[2];
      else if ((m = /^\+\+\+ b\/(.+)$/.exec(line))) path = m[1];
      if (!path) continue;
      for (let j = i + 1; j < Math.min(i + 5, lines.length); j++) {
        if (/^new file mode/.test(lines[j])) status = "A";
        else if (/^deleted file mode/.test(lines[j])) status = "D";
      }
      if (!seen.has(path)) { seen.add(path); out.push({ path, status }); }
    }
    return out;
  }

  const TerminalPanel = {
    name: "TerminalPanel",
    template: "#tpl-term",
    emits: ["close"],
    data() { return { raw: "", connected: false, focused: false, ws: null }; },
    computed: {
      S() { return S; },
      rendered() { return ansiToHtml(this.raw); },
    },
    mounted() {
      this.connect();
      this.onWinResize = () => { if (this.connected) this.sendResize(); };
      window.addEventListener("resize", this.onWinResize);
      nextTick(() => this.focusInput());
    },
    beforeUnmount() {
      this.disconnect();
      window.removeEventListener("resize", this.onWinResize);
    },
    methods: {
      connect() {
        const proto = location.protocol === "https:" ? "wss:" : "ws:";
        const cwd = S.conversation.cwd || "";
        const url = proto + "//" + location.host + "/api/exec-ws" + (cwd ? "?cwd=" + enc(cwd) : "");
        try { this.ws = new WebSocket(url); }
        catch (e) {
          this.raw += "[this browser refused the websocket: " + e.message + "]\n";
          return;
        }
        S.term.exit = false;
        this.ws.onopen = () => {
          this.connected = true;
          this.send({ type: "init", cmd: "bash", cwd: cwd });
          this.sendResize();
        };
        this.ws.onmessage = (ev) => {
          let m = null;
          try { m = JSON.parse(ev.data); } catch (_) { this.appendRaw(String(ev.data)); return; }
          if (!m || typeof m !== "object") { this.appendRaw(String(ev.data)); return; }
          const t = String(m.type || "");
          if (t === "output" || t === "data") this.appendRaw(String(m.data != null ? m.data : m.text != null ? m.text : ""));
          else if (t === "exit") { S.term.exit = true; this.connected = false; this.appendRaw("\n[process exited]\n"); }
          else if (t === "error") this.appendRaw("\n[" + (m.text || m.error || m.message || "error") + "]\n");
          else if (typeof m.data === "string") this.appendRaw(m.data);
        };
        this.ws.onclose = () => {
          this.connected = false;
          if (!S.term.exit) this.appendRaw("\n[disconnected — /api/exec-ws may not be implemented by this server]\n");
        };
        this.ws.onerror = () => {
          if (!this.connected) this.appendRaw("\n[terminal unavailable — the server did not accept /api/exec-ws]\n");
        };
      },
      disconnect() {
        if (this.ws) { try { this.ws.close(); } catch (_) {} this.ws = null; }
        this.connected = false;
      },
      reconnect() {
        this.disconnect();
        this.raw = "";
        S.term.exit = false;
        this.connect();
      },
      send(obj) {
        if (!this.ws || this.ws.readyState !== 1) return false;
        try { this.ws.send(JSON.stringify(obj)); return true; } catch (_) { return false; }
      },
      appendRaw(s) {
        this.raw += s;
        if (this.raw.length > 400000) this.raw = this.raw.slice(-300000);
        nextTick(() => { const el = this.$refs.term; if (el) el.scrollTop = el.scrollHeight; });
      },
      focusInput() { if (this.$refs.input) this.$refs.input.focus(); },
      sendRaw(data) {
        if (!this.send({ type: "input", data })) this.appendRaw("[not connected]");
      },
      /**
       * Control keys are translated here; printable text arrives through
       * `onInput` (which also covers pastes). The shell is a real pty, so the
       * bytes have to look like a terminal's.
       */
      onKey(e) {
        if (e.metaKey) return;                      // leave browser shortcuts alone
        if (e.ctrlKey && e.key === "v") return;     // paste arrives as input
        const k = e.key;
        const seq = this.sequenceFor(e);
        if (seq === null) return;                   // not ours
        e.preventDefault();
        if (seq) this.sendRaw(seq);
      },
      sequenceFor(e) {
        const k = e.key;
        const arrows = { ArrowUp: "A", ArrowDown: "B", ArrowRight: "C", ArrowLeft: "D" };
        if (arrows[k]) return "\u001b[" + arrows[k];
        if (k === "Enter") return "\r";
        if (k === "Tab") return e.shiftKey ? "\u001b[Z" : "\t";
        if (k === "Backspace") return e.ctrlKey ? "\u0017" : "\u007f";
        if (k === "Escape") return "\u001b";
        if (k === "Delete") return "\u001b[3~";
        if (k === "Home") return "\u001b[H";
        if (k === "End") return "\u001b[F";
        if (k === "PageUp") return "\u001b[5~";
        if (k === "PageDown") return "\u001b[6~";
        if (k === "Insert") return "\u001b[2~";
        if (e.ctrlKey && k.length === 1 && /[a-zA-Z]/.test(k)) {
          return String.fromCharCode(k.toLowerCase().charCodeAt(0) - 96);
        }
        if (e.ctrlKey && k === " ") return "\u0000";
        if (e.altKey && k.length === 1) return "\u001b" + k;
        if (k.length > 1) return e.ctrlKey || e.altKey ? "" : null;
        return null;                                // printable: handled by input
      },
      onInput(e) {
        const el = e.target;
        const text = el.value;
        el.value = "";
        if (!text) return;
        this.sendRaw(text.replace(/\r?\n/g, "\r"));
      },
      sendResize() {
        const el = this.$refs.term;
        if (!el) return;
        const cols = Math.max(40, Math.min(220, Math.round(el.clientWidth / 7.35)));
        const rows = Math.max(10, Math.min(90, Math.round(el.clientHeight / 17)));
        this.send({ type: "resize", cols, rows });
      },
    },
  };

  const SettingsModal = {
    name: "SettingsModal",
    template: "#tpl-settings",
    emits: ["close"],
    data() {
      return {
        tab: "model",
        newTool: "",
        tabs: [
          { id: "model", label: "Model & tools" },
          { id: "mcp", label: "MCP" },
          { id: "flags", label: "Feature flags" },
          { id: "integrations", label: "Integrations" },
          { id: "about", label: "About" },
        ],
      };
    },
    computed: {
      S() { return S; },
      thinkingLevels() { return THINKING_LEVELS; },
      modelMeta() { return modelMeta.value; },
      compatSummary() { return compatSummary.value; },
      mcpForm() { return mcpForm; },
    },
    mounted() {
      loadModels(); loadSettings(); loadProfiles(); loadFlags();
      loadIntegrations(); loadMcp(); loadCosts(); loadCapabilities();
    },
    methods: {
      fmtFlag, truthy, activateProfile, saveSettings, testMcp, removeMcp, addMcp, toggleFlag, testSlack,
      setModel() { setModel(); },
      setThinking() { setThinking(); },
      setTool(name, v) { S.toolOverrides = Object.assign({}, S.toolOverrides, { [name]: v }); pushOptions(); },
      delTool(name) { const o = Object.assign({}, S.toolOverrides); delete o[name]; S.toolOverrides = o; pushOptions(); },
      addTool() {
        const n = String(this.newTool || "").trim();
        if (!n) return;
        S.toolOverrides = Object.assign({}, S.toolOverrides, { [n]: "off" });
        this.newTool = "";
      },
    },
  };

  // ==================================================================== root
  const Root = {
    name: "Root",
    template: "#tpl-root",
    setup() {
      const input = ref(null);
      const timers = [];

      function closeTop() {
        if (S.frame && S.frame.url) { S.frame = {}; return; }
        if (S.file.open) { S.file.open = false; return; }
        if (S.modal) { S.modal = null; return; }
        if (isMobile.value) S.drawerOpen = false;
      }

      function onGlobalKey(e) {
        const meta = e.metaKey || e.ctrlKey;
        if (!meta) {
          if (e.key === "Escape") closeTop();
          return;
        }
        const k = String(e.key || "").toLowerCase();
        if (k === "k") { e.preventDefault(); S.modal = S.modal === "palette" ? null : "palette"; }
        else if (k === "p" && !e.shiftKey) { e.preventDefault(); openModal("finder"); }
        else if (k === "g" && e.shiftKey) { e.preventDefault(); openModal("git"); }
        else if (k === "j") { e.preventDefault(); openModal("terminal"); }
        else if (k === ",") { e.preventDefault(); openModal("settings"); }
        else if (k === "b") { e.preventDefault(); S.drawerOpen = !S.drawerOpen; }
        else if (k === "e" && e.shiftKey) { e.preventDefault(); exportMarkdown(); }
      }

      onMounted(async () => {
        applyTheme(S.theme);
        nextTick(watchLogSize);
        window.addEventListener("keydown", onGlobalKey);
        // Usage climbs during a turn; poll it only while one is running.
        timers.push(setInterval(() => { if (S.running) refreshUsage(); }, 3000));
        timers.push(setInterval(() => { if (!S.running && !S.modal) loadConversations({ quiet: true }); }, 25000));
        await boot();
      });

      onUnmounted(() => {
        window.removeEventListener("keydown", onGlobalKey);
        for (const t of timers) clearInterval(t);
        disconnectStream();
      });

      return {
        S, isMobile, log: logEl, input,
        // drawer
        toggleSearch, toggleArchived, onSearchInput, clearSearch, newConversation,
        archiveAllConversations,
        allTags, rootConversations, conversationGroups, openModal, toggleTheme,
        // header / usage
        headerTitle, shortPath, cancelTurn, exportMarkdown, fmtNum, fmtCost,
        usage, ctxClass, ctxPct, compactNow,
        // transcript
        groups, liveGroup: live, atBottom, onLogScroll, scrollToBottom, jumpToBottom, dismissToast,
        // composer
        placeholder, canSend, send, autoGrow, onKeydown, onPaste, onDrop, onFiles,
        setModel, setThinking, thinkingLevels,
      };
    },
  };

  async function boot() {
    S.status = "connecting…";
    await loadCapabilities();
    await loadModels();
    if (!S.model && S.health.model) S.model = S.health.model;
    await loadConversations();
    const params = new URLSearchParams(location.search);
    const wanted = params.get("c") || params.get("conversation") || localStorage.getItem("shell.lastConv");
    if (wanted) {
      try { await openConversation(wanted); } catch (_) {}
    }
    S.status = "ready";
    nextTick(() => {
      const ta = document.querySelector(".composer textarea");
      if (ta) ta.focus();
    });
  }

  // A conversation opened or switched is reflected in the URL so a reload (and
  // a shared link) lands on the same transcript.
  watch(() => S.currentId, (id) => {
    try {
      const u = new URL(location.href);
      if (id) u.searchParams.set("c", id); else u.searchParams.delete("c");
      history.replaceState(null, "", u.toString());
    } catch (_) {}
  });

  const app = createApp(Root);
  app.component("conv-row", ConvRow);
  app.component("md-view", MdView);
  app.component("diff-view", DiffView);
  app.component("tool-card", ToolCard);
  app.component("msg-item", MsgItem);
  app.component("palette-modal", PaletteModal);
  app.component("finder-modal", FinderModal);
  app.component("dir-picker", DirPicker);
  app.component("file-viewer", FileViewer);
  app.component("git-panel", GitPanel);
  app.component("terminal-panel", TerminalPanel);
  app.component("settings-modal", SettingsModal);
  app.config.errorHandler = (err, vm, info) => {
    // A render error must never blank the page.
    console.error("ui error", err, info);
    toast("UI error: " + (err && err.message ? err.message : err), "error");
  };
  try {
    app.mount("#app");
  } catch (e) {
    document.getElementById("app").innerHTML =
      '<div class="boot"><div class="fatal">The UI failed to start: ' + esc(e && e.message) + "</div></div>";
    throw e;
  }
