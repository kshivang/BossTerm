// BossTerm session-sharing viewer (issue #276) — window mirror.
//
// Renders the host window model: a tab bar (switch client-side), each tab's split
// tree as nested flex, and one xterm.js instance per pane (keyed by paneId). The
// host streams a Layout (tabs + split trees) + per-pane PaneSnapshot/PaneOutput so
// every pane re-emulates faithfully. With the control link, each pane's keystrokes
// route back to that pane (Input{paneId}). A single-tab share is just a 1-tab window.

(function () {
  "use strict";

  var params = new URLSearchParams(location.search);
  var token = params.get("t");

  var statusEl = document.getElementById("status");
  var tabbarEl = document.getElementById("tabbar");
  var sidebarEl = document.getElementById("sidebar");
  var stageEl = document.getElementById("stage");
  var presenceEl = document.getElementById("presence");
  var viewOnlyEl = document.getElementById("viewonly");

  var keybarEl = document.getElementById("keybar");
  var menubtnEl = document.getElementById("menubtn");
  var bodyEl = document.getElementById("body");
  var ctxEl = document.getElementById("ctxmenu");
  var tabBarOnLeft = false;       // mirror the host's tab-bar orientation
  var currentPaneId = null;       // pane the on-screen key bar targets
  function sendMsg(o) { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(o)); }

  // ☰ toggles the left tab drawer (phone); tapping a tab closes it (see switchTab).
  menubtnEl.onclick = function () { sidebarEl.classList.toggle("open"); };

  // Keep the fixed key bar just above the soft keyboard (and reserve space for it).
  function layoutForKeyboard() {
    var vv = window.visualViewport;
    if (vv) keybarEl.style.bottom = Math.max(0, window.innerHeight - vv.height - vv.offsetTop) + "px";
    bodyEl.style.paddingBottom = (keybarEl.style.display !== "none" && keybarEl.offsetHeight)
      ? keybarEl.offsetHeight + "px" : "0px";
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", layoutForKeyboard);
    window.visualViewport.addEventListener("scroll", layoutForKeyboard);
  }

  // ---- on-screen key bar (mobile control keys) ----
  var KEY_ROW = [
    ["Esc", "\x1b"], ["Tab", "\t"], ["^C", "\x03"], ["^D", "\x04"], ["^Z", "\x1a"], ["^L", "\x0c"],
    ["←", "\x1b[D"], ["↑", "\x1b[A"], ["↓", "\x1b[B"], ["→", "\x1b[C"]
  ];
  function focusCurrent() {
    if (currentPaneId && panes[currentPaneId]) { try { panes[currentPaneId].term.focus(); } catch (e) {} }
  }
  function sendKey(seq) {
    if (!controlGranted || !currentPaneId) return;
    sendMsg({ t: "input", paneId: currentPaneId, data: seq });
  }
  function buildKeybar() {
    keybarEl.innerHTML = "";
    if (!controlGranted) { keybarEl.style.display = "none"; layoutForKeyboard(); return; }
    keybarEl.style.display = "flex";
    var kb = document.createElement("button");
    kb.className = "keybtn"; kb.textContent = "⌨"; kb.title = "Show keyboard";
    kb.onclick = focusCurrent;
    keybarEl.appendChild(kb);
    KEY_ROW.forEach(function (k) {
      var b = document.createElement("button");
      b.className = "keybtn"; b.textContent = k[0];
      // Don't steal focus from the terminal's input, so the soft keyboard stays up.
      b.addEventListener("mousedown", function (e) { e.preventDefault(); });
      b.onclick = function () { sendKey(k[1]); };
      keybarEl.appendChild(b);
    });
    layoutForKeyboard(); // reserve space + position above the keyboard
  }
  // The focused pane of [node]'s tree (or the first pane), for default key-bar target.
  function firstFocusedPane(node) {
    var found = null, first = null;
    (function walk(n) {
      if (!n) return;
      if (n.t === "pane") { if (first === null) first = n.paneId; if (n.focused) found = n.paneId; }
      else { walk(n.a); walk(n.b); }
    })(node);
    return found || first;
  }

  var controlGranted = false;
  var theme = null;               // last Theme message
  var layout = null;              // last Layout message
  var activeTabId = null;         // client-side selected tab
  var panes = {};                 // paneId -> { term, host(el) }
  var ws = null;
  // Give the active single pane its NATURAL width so a wide host terminal scrolls
  // horizontally inside #stage. (xterm's own viewport is a y-scroll container that clips
  // x, so we can't get native horizontal scroll from it — instead we expose the full
  // width by sizing the pane to content and letting #stage scroll both axes. Vertical
  // scrollback stays inside xterm; pinch-zoom works on top. Splits keep the fill layout.)
  function relayoutSinglePane() {
    if (!layout) return;
    var tab = null, i;
    for (i = 0; i < layout.tabs.length; i++) if (layout.tabs[i].id === activeTabId) tab = layout.tabs[i];
    if (!tab) tab = layout.tabs[0];
    if (!tab || !tab.tree || tab.tree.t !== "pane") return;
    var p = panes[tab.tree.paneId]; if (!p) return;
    requestAnimationFrame(function () {
      var sc = p.host.querySelector(".xterm-screen");
      if (sc) { var w = Math.ceil(sc.getBoundingClientRect().width); if (w > 0) p.host.style.width = w + "px"; }
    });
  }
  window.addEventListener("resize", relayoutSinglePane);
  window.addEventListener("orientationchange", relayoutSinglePane);

  // ---- zoom (viewer-local font size) ----
  var viewerFont = 0; // 0 = use the host/theme size
  function activeTabNode() {
    if (!layout) return null;
    for (var i = 0; i < layout.tabs.length; i++) if (layout.tabs[i].id === activeTabId) return layout.tabs[i];
    return layout.tabs[0] || null;
  }
  function curFont() { return viewerFont || (theme && theme.fontSize) || 13; }
  function applyFont(px) {
    viewerFont = Math.max(6, Math.min(40, Math.round(px)));
    Object.keys(panes).forEach(function (id) { try { panes[id].term.options.fontSize = viewerFont; } catch (e) {} });
    relayoutSinglePane();
  }
  // Fit the active pane's font so the whole width shows (zoom-out to fit).
  function fitWidth() {
    var tab = activeTabNode(); if (!tab || !tab.tree || tab.tree.t !== "pane") return;
    var p = panes[tab.tree.paneId]; if (!p) return;
    var screen = p.host.querySelector(".xterm-screen"); if (!screen) return;
    var avail = stageEl.clientWidth - 2, w = screen.getBoundingClientRect().width;
    if (avail > 0 && w > 0) applyFont(curFont() * (avail / w));
  }
  document.getElementById("zoomin").onclick = function () { applyFont(curFont() + 1); };
  document.getElementById("zoomout").onclick = function () { applyFont(curFont() - 1); };
  document.getElementById("zoomfit").onclick = fitWidth;

  // ---- right-click / long-press context menu ----
  // Copy via the async Clipboard API where available (needs a secure context: https /
  // localhost), else fall back to a hidden-textarea execCommand("copy") so plain-LAN
  // http viewers can still copy.
  function copyText(t) {
    if (!t) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(t).catch(function () { copyFallback(t); });
    } else { copyFallback(t); }
  }
  function copyFallback(t) {
    try {
      var ta = document.createElement("textarea");
      ta.value = t; ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.focus(); ta.select();
      document.execCommand("copy"); document.body.removeChild(ta);
    } catch (e) {}
  }
  // AI assistants mirrored from the host menu (Tools ▸ AI). Launching one runs the
  // host's configured command for that assistant in the clicked pane (control only).
  var AI_ASSISTANTS = [
    { id: "claude-code", label: "Claude Code" },
    { id: "codex", label: "Codex" },
    { id: "gemini-cli", label: "Gemini CLI" },
    { id: "opencode", label: "OpenCode" }
  ];
  function hideContextMenu() { ctxEl.style.display = "none"; ctxEl.innerHTML = ""; }
  function ctxItem(label, enabled, onClick, opts) {
    opts = opts || {};
    var it = document.createElement("div");
    it.className = "ctxitem" + (enabled ? "" : " disabled") + (opts.sub ? " ctxsub" : "");
    it.textContent = label;
    if (enabled) it.addEventListener("mousedown", function (e) {
      e.preventDefault(); e.stopPropagation();
      if (!opts.keepOpen) hideContextMenu();
      onClick(it);
    });
    return it;
  }
  function ctxSep() { var s = document.createElement("div"); s.className = "ctxsep"; return s; }
  function showContextMenu(x, y, paneId) {
    var p = panes[paneId]; if (!p) return;
    var term = p.term;
    var hasSel = false; try { hasSel = term.hasSelection(); } catch (e) {}
    // Reposition + clamp inside the viewport (re-run when the menu's height changes,
    // e.g. the AI submenu expands).
    function clamp() {
      ctxEl.style.left = "0px"; ctxEl.style.top = "0px"; ctxEl.style.display = "block";
      var w = ctxEl.offsetWidth, h = ctxEl.offsetHeight;
      ctxEl.style.left = Math.max(0, Math.min(x, window.innerWidth - w - 4)) + "px";
      ctxEl.style.top = Math.max(0, Math.min(y, window.innerHeight - h - 4)) + "px";
    }
    ctxEl.innerHTML = "";
    ctxEl.appendChild(ctxItem("Copy", hasSel, function () {
      var s = ""; try { s = term.getSelection(); } catch (e) {}
      copyText(s); try { term.clearSelection(); } catch (e) {}
    }));
    // Paste needs control (we send the text as input) + clipboard read (secure context).
    var canRead = controlGranted && navigator.clipboard && navigator.clipboard.readText;
    ctxEl.appendChild(ctxItem("Paste", canRead, function () {
      navigator.clipboard.readText().then(function (txt) {
        if (txt) sendMsg({ t: "input", paneId: paneId, data: txt });
      }).catch(function () {});
    }));
    ctxEl.appendChild(ctxItem("Select all", true, function () { try { term.selectAll(); } catch (e) {} }));

    // Splits + AI assistant — host-mutating, so controller role only.
    if (controlGranted) {
      var tab = activeTabNode();
      var tabId = tab ? tab.id : activeTabId;
      var multiPane = !!(tab && tab.tree && tab.tree.t === "split");
      ctxEl.appendChild(ctxSep());
      ctxEl.appendChild(ctxItem("Split vertical (left / right)", true, function () {
        sendMsg({ t: "splitVertical", tabId: tabId, paneId: paneId });
      }));
      ctxEl.appendChild(ctxItem("Split horizontal (top / bottom)", true, function () {
        sendMsg({ t: "splitHorizontal", tabId: tabId, paneId: paneId });
      }));
      if (multiPane) ctxEl.appendChild(ctxItem("Close pane", true, function () {
        sendMsg({ t: "closePane", tabId: tabId, paneId: paneId });
      }));
      // AI assistant submenu: expand inline (click/tap-friendly on mobile too).
      var aiOpen = false;
      var aiBox = document.createElement("div");
      AI_ASSISTANTS.forEach(function (a) {
        aiBox.appendChild(ctxItem(a.label, true, function () {
          sendMsg({ t: "launchAI", tabId: tabId, paneId: paneId, assistantId: a.id });
        }, { sub: true }));
      });
      aiBox.style.display = "none";
      var aiParent = ctxItem("AI assistant ▸", true, function (el) {
        aiOpen = !aiOpen;
        aiBox.style.display = aiOpen ? "block" : "none";
        el.textContent = aiOpen ? "AI assistant ▾" : "AI assistant ▸";
        clamp(); // height changed
      }, { keepOpen: true });
      ctxEl.appendChild(aiParent);
      ctxEl.appendChild(aiBox);
    }

    ctxEl.appendChild(ctxSep());
    if (controlGranted) ctxEl.appendChild(ctxItem("Clear scrollback", true, function () { try { term.clear(); } catch (e) {} }));
    ctxEl.appendChild(ctxItem("Scroll to bottom", true, function () { try { term.scrollToBottom(); } catch (e) {} }));
    clamp();
  }
  document.addEventListener("mousedown", function (e) {
    if (ctxEl.style.display === "block" && !ctxEl.contains(e.target)) hideContextMenu();
  });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") hideContextMenu(); });
  window.addEventListener("blur", hideContextMenu);
  window.addEventListener("resize", hideContextMenu);
  stageEl.addEventListener("scroll", hideContextMenu, true);

  function setStatus(cls) { statusEl.className = cls; }

  if (!token) {
    document.body.textContent = "Missing share token — open the link from BossTerm's Share dialog.";
    return;
  }

  // ---- approval handshake (issue #276) ----
  // Stable per-browser id so the host recognizes this device across reconnects.
  var clientId = localStorage.getItem("bossterm.clientId");
  if (!clientId) {
    clientId = (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
             : String(Date.now()) + "-" + Math.random().toString(16).slice(2);
    localStorage.setItem("bossterm.clientId", clientId);
  }
  // A previously granted access key (per share token), replayed to skip re-approval.
  var keyStore = "bossterm.key." + token;
  function loadKey() {
    try {
      var o = JSON.parse(localStorage.getItem(keyStore) || "null");
      if (o && o.key && o.expiresAt > Date.now()) return o.key;
      localStorage.removeItem(keyStore);
    } catch (e) {}
    return null;
  }
  function saveKey(key, expiresAt) {
    try { localStorage.setItem(keyStore, JSON.stringify({ key: key, expiresAt: expiresAt })); } catch (e) {}
  }
  function clearKey() { try { localStorage.removeItem(keyStore); } catch (e) {} }

  var overlayEl = document.getElementById("overlay");
  var overlayTitleEl = document.getElementById("overlay-title");
  var overlayMsgEl = document.getElementById("overlay-msg");
  var overlaySpinnerEl = document.getElementById("overlay-spinner");
  function showOverlay(title, msg, spinning) {
    overlayTitleEl.textContent = title;
    overlayMsgEl.textContent = msg || "";
    overlaySpinnerEl.style.display = spinning ? "" : "none";
    overlayEl.style.display = "";
  }
  function hideOverlay() { overlayEl.style.display = "none"; }

  function deviceName() {
    return localStorage.getItem("bossterm.name") || navigator.platform || "browser";
  }

  // ---- xterm pool ----
  function getPane(paneId) {
    var p = panes[paneId];
    if (p) return p;
    var host = document.createElement("div");
    host.className = "termhost";
    var opts = { cursorBlink: true, convertEol: false, scrollback: 5000,
                 fontFamily: "Menlo, Monaco, monospace", fontSize: 13,
                 theme: { background: "#1e1e1e", foreground: "#f8f8f2" } };
    if (theme) applyThemeToOpts(opts);
    var term = new Terminal(opts);
    term.open(host);
    // Terminals want raw keystrokes — disable the soft keyboard's autocorrect /
    // predictive-text / autocapitalize / spellcheck on xterm's hidden input.
    var ta = term.textarea || host.querySelector(".xterm-helper-textarea");
    if (ta) {
      ta.setAttribute("autocomplete", "off");
      ta.setAttribute("autocorrect", "off");
      ta.setAttribute("autocapitalize", "off");
      ta.setAttribute("spellcheck", "false");
    }
    if (viewerFont) { try { term.options.fontSize = viewerFont; } catch (e) {} }
    term.onData(function (data) {
      if (controlGranted && ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ t: "input", paneId: paneId, data: data }));
      }
    });
    p = { term: term, host: host };
    panes[paneId] = p;
    return p;
  }

  function applyThemeToOpts(opts) {
    var a = theme.ansi || [];
    opts.theme = {
      background: theme.background, foreground: theme.foreground, cursor: theme.cursor,
      cursorAccent: theme.cursorAccent, selectionBackground: theme.selectionBackground,
      black: a[0], red: a[1], green: a[2], yellow: a[3], blue: a[4], magenta: a[5], cyan: a[6], white: a[7],
      brightBlack: a[8], brightRed: a[9], brightGreen: a[10], brightYellow: a[11],
      brightBlue: a[12], brightMagenta: a[13], brightCyan: a[14], brightWhite: a[15],
    };
    if (theme.fontFamily) opts.fontFamily = theme.fontFamily;
    if (theme.fontSize) opts.fontSize = theme.fontSize;
  }

  function applyTheme(m) {
    theme = m;
    Object.keys(panes).forEach(function (id) {
      var o = {}; applyThemeToOpts(o);
      var t = panes[id].term;
      t.options.theme = o.theme;
      if (o.fontFamily) t.options.fontFamily = o.fontFamily;
      if (o.fontSize) t.options.fontSize = o.fontSize;
    });
    if (m.background) {
      document.documentElement.style.background = m.background;
      document.body.style.background = m.background;
      stageEl.style.background = m.background;
    }
    // The host theme may carry a fontSize; keep the viewer's chosen zoom if set.
    if (viewerFont) Object.keys(panes).forEach(function (id) { try { panes[id].term.options.fontSize = viewerFont; } catch (e) {} });
    relayoutSinglePane();
  }

  // ---- layout rendering ----
  // Render the tab bar to mirror the host: a left column (Warp-style title/cwd/branch
  // chips) or a top strip. Close + new-tab affordances appear only with control.
  function renderTabBar() {
    if (tabBarOnLeft) {
      tabbarEl.classList.add("hidden");
      menubtnEl.classList.add("show");
      sidebarEl.classList.add("show"); // .open (drawer) toggled by ☰ on phones
      sidebarEl.innerHTML = "";
      if (layout) layout.tabs.forEach(function (tab) { sidebarEl.appendChild(leftChip(tab)); });
      // Action row mirroring the host's left bar: Split L/R, Split T/B, then New tab.
      if (controlGranted) {
        var actions = document.createElement("div");
        actions.className = "ltab-actions";
        actions.appendChild(splitButton("v"));
        actions.appendChild(splitButton("h"));
        actions.appendChild(newTabButton("+ New tab"));
        sidebarEl.appendChild(actions);
      }
    } else {
      tabbarEl.classList.remove("hidden");
      menubtnEl.classList.remove("show");
      sidebarEl.classList.remove("show", "open");
      tabbarEl.innerHTML = "";
      if (layout) layout.tabs.forEach(function (tab) { tabbarEl.appendChild(topChip(tab)); });
      // Split buttons sit just left of the new-tab (+), like the host's tab-bar actions.
      if (controlGranted) {
        tabbarEl.appendChild(splitButton("v"));
        tabbarEl.appendChild(splitButton("h"));
        tabbarEl.appendChild(newTabButton("+"));
      }
    }
  }

  // Inline SVGs matching the host's Material split icons: a pane outline divided by a
  // vertical line (left/right) or a horizontal line (top/bottom).
  var SVG_VSPLIT = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="1"/><line x1="12" y1="4" x2="12" y2="20"/></svg>';
  var SVG_HSPLIT = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="1"/><line x1="3" y1="12" x2="21" y2="12"/></svg>';

  // The pane a tab-bar split targets: the viewer's current pane if it's in the active
  // tab, else that tab's focused pane (matches the host splitting the focused pane).
  function activePaneId() {
    var tab = activeTabNode(); if (!tab || !tab.tree) return null;
    if (currentPaneId) { var ids = {}; collectPaneIds(tab.tree, ids); if (ids[currentPaneId]) return currentPaneId; }
    return firstFocusedPane(tab.tree);
  }
  // kind: "v" = Split Left/Right (vertical divider), "h" = Split Top/Bottom (horizontal divider).
  function splitButton(kind) {
    var b = document.createElement("div");
    b.className = "splitbtn";
    b.title = kind === "v" ? "Split vertical (left / right)" : "Split horizontal (top / bottom)";
    b.innerHTML = kind === "v" ? SVG_VSPLIT : SVG_HSPLIT;
    b.onclick = function (ev) {
      ev.stopPropagation();
      var tab = activeTabNode(), pid = activePaneId();
      if (!tab || !pid) return;
      sendMsg({ t: kind === "v" ? "splitVertical" : "splitHorizontal", tabId: tab.id, paneId: pid });
    };
    return b;
  }

  function switchTab(id) {
    activeTabId = id;
    sidebarEl.classList.remove("open"); // close the phone drawer after picking a tab
    renderTabBar();
    renderStage();
  }

  function closeBtn(tabId) {
    var x = document.createElement("span");
    x.className = "tabclose"; x.textContent = "×"; x.title = "Close tab";
    x.onclick = function (ev) { ev.stopPropagation(); sendMsg({ t: "closeTab", tabId: tabId }); };
    return x;
  }

  function newTabButton(label) {
    var el = document.createElement("div");
    el.className = "newtab"; el.textContent = label; el.title = "New tab";
    el.onclick = function () { sendMsg({ t: "newTab" }); };
    return el;
  }

  function topChip(tab) {
    var el = document.createElement("div");
    el.className = "tab" + (tab.id === activeTabId ? " active" : "");
    if (tab.color) el.style.borderLeft = "3px solid " + tab.color;
    var label = document.createElement("span");
    label.className = "tablabel"; label.textContent = tab.title || "shell";
    el.appendChild(label);
    if (controlGranted) el.appendChild(closeBtn(tab.id));
    el.onclick = function () { switchTab(tab.id); };
    return el;
  }

  function leftChip(tab) {
    var el = document.createElement("div");
    el.className = "ltab" + (tab.id === activeTabId ? " active" : "");
    if (tab.color) el.style.borderLeft = "3px solid " + tab.color;
    var row = document.createElement("div"); row.className = "ltab-row";
    var title = document.createElement("span"); title.className = "ltab-title"; title.textContent = tab.title || "shell";
    row.appendChild(title);
    if (controlGranted) row.appendChild(closeBtn(tab.id));
    el.appendChild(row);
    if (tab.cwd) { var s = document.createElement("div"); s.className = "ltab-sub"; s.textContent = abbreviateCwd(tab.cwd); el.appendChild(s); }
    if (tab.branch) { var b = document.createElement("div"); b.className = "ltab-branch"; b.textContent = "⎇ " + tab.branch; el.appendChild(b); }
    el.onclick = function () { switchTab(tab.id); };
    return el;
  }

  function abbreviateCwd(p) {
    var parts = p.split("/").filter(Boolean);
    if (parts.length <= 2) return p;
    return "…/" + parts.slice(-2).join("/");
  }

  function buildNode(node) {
    if (node.t === "pane") {
      var wrap = document.createElement("div");
      wrap.className = "pane" + (node.focused ? " focused" : "");
      wrap.appendChild(getPane(node.paneId).host);
      var pid = node.paneId;
      // Tapping a pane makes it the key-bar / typing target.
      wrap.addEventListener("pointerdown", function () { currentPaneId = pid; });
      // Desktop right-click → context menu.
      wrap.addEventListener("contextmenu", function (e) {
        e.preventDefault(); currentPaneId = pid; showContextMenu(e.clientX, e.clientY, pid);
      });
      // Mobile long-press (~500ms) → same menu, anchored at the touch point.
      var lpTimer = null, lpX = 0, lpY = 0;
      wrap.addEventListener("touchstart", function (e) {
        if (!e.touches || e.touches.length !== 1) return;
        lpX = e.touches[0].clientX; lpY = e.touches[0].clientY; currentPaneId = pid;
        lpTimer = setTimeout(function () { lpTimer = null; showContextMenu(lpX, lpY, pid); }, 500);
      }, { passive: true });
      function cancelLongPress(e) {
        if (lpTimer && e && e.touches && e.touches[0]) {
          var dx = Math.abs(e.touches[0].clientX - lpX), dy = Math.abs(e.touches[0].clientY - lpY);
          if (dx < 10 && dy < 10) return; // small jitter — keep the timer
        }
        if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
      }
      wrap.addEventListener("touchmove", cancelLongPress, { passive: true });
      wrap.addEventListener("touchend", cancelLongPress);
      wrap.addEventListener("touchcancel", cancelLongPress);
      return wrap;
    }
    // split
    var split = document.createElement("div");
    split.className = "split " + (node.dir === "h" ? "h" : "v");
    var a = buildNode(node.a), b = buildNode(node.b);
    a.style.flex = (node.ratio || 0.5) + " 1 0";
    b.style.flex = (1 - (node.ratio || 0.5)) + " 1 0";
    var div = document.createElement("div");
    div.className = "divider";
    split.appendChild(a); split.appendChild(div); split.appendChild(b);
    return split;
  }

  function renderStage() {
    stageEl.innerHTML = "";
    if (!layout) return;
    var tab = layout.tabs.filter(function (t) { return t.id === activeTabId; })[0] || layout.tabs[0];
    if (!tab) return;
    // Keep the key-bar target on a pane that's actually visible in this tab.
    var ids = {}; collectPaneIds(tab.tree, ids);
    if (!currentPaneId || !ids[currentPaneId]) currentPaneId = firstFocusedPane(tab.tree);
    var root = buildNode(tab.tree);
    if (tab.tree.t === "pane") {
      // single pane → natural width, scrollable in #stage (don't stretch/clip)
      root.style.flex = "0 0 auto";
      root.style.height = "100%";
      root.style.overflow = "visible";
      var sp = panes[tab.tree.paneId];
      if (sp) { sp.host.style.overflow = "visible"; sp.host.style.height = "100%"; }
    } else {
      root.style.flex = "1 1 0"; // splits fill the stage
    }
    stageEl.appendChild(root);
    relayoutSinglePane(); // size a single pane to its natural width for horizontal scroll
  }

  function onLayout(m) {
    layout = m;
    tabBarOnLeft = !!m.tabBarOnLeft;
    var ids = m.tabs.map(function (t) { return t.id; });
    if (activeTabId === null || ids.indexOf(activeTabId) === -1) {
      activeTabId = m.activeTabId && ids.indexOf(m.activeTabId) !== -1 ? m.activeTabId : (ids[0] || null);
    }
    // Drop xterms for panes no longer present.
    var live = {};
    m.tabs.forEach(function (t) { collectPaneIds(t.tree, live); });
    Object.keys(panes).forEach(function (id) {
      if (!live[id]) { try { panes[id].term.dispose(); } catch (e) {} delete panes[id]; }
    });
    renderTabBar();
    renderStage();
  }

  function collectPaneIds(node, out) {
    if (node.t === "pane") out[node.paneId] = true;
    else { collectPaneIds(node.a, out); collectPaneIds(node.b, out); }
  }

  // ---- websocket ----
  var wsProto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(wsProto + "://" + location.host + "/ws/" + encodeURIComponent(token));

  ws.onopen = function () {
    setStatus("live");
    ws.send(JSON.stringify({ t: "hello", name: deviceName(), clientId: clientId, key: loadKey() }));
  };

  ws.onmessage = function (ev) {
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    switch (m.t) {
      case "pending":
        showOverlay("Waiting for host approval…",
          "Ask the person sharing in BossTerm to approve this device. This window will connect automatically once they do.",
          true);
        break;
      case "grant":
        // Approved (or refreshed): persist the rolling key and dismiss the overlay.
        if (m.key) saveKey(m.key, m.expiresAt);
        hideOverlay();
        break;
      case "denied":
        clearKey();
        showOverlay("Request denied", m.reason || "The host declined this device.", false);
        break;
      case "theme": applyTheme(m); break;
      case "layout": hideOverlay(); onLayout(m); break;
      case "paneSnapshot": {
        var p = getPane(m.paneId);
        if (m.cols && m.rows) p.term.resize(m.cols, m.rows);
        p.term.reset();
        if (m.data) p.term.write(m.data);
        relayoutSinglePane();
        break;
      }
      case "paneOutput": if (m.data) getPane(m.paneId).term.write(m.data); break;
      case "paneResize":
        if (m.cols && m.rows) { getPane(m.paneId).term.resize(m.cols, m.rows); relayoutSinglePane(); }
        break;
      case "presence":
        presenceEl.textContent = m.viewers === 1 ? "1 viewer" : m.viewers + " viewers"; break;
      case "control":
        controlGranted = !!m.granted;
        viewOnlyEl.style.display = controlGranted ? "none" : "";
        renderTabBar(); // show/hide the close + new-tab affordances
        buildKeybar();  // show/hide the on-screen control-key bar
        break;
    }
  };

  ws.onclose = function () { setStatus("down"); };
  ws.onerror = function () { setStatus("down"); };
})();
