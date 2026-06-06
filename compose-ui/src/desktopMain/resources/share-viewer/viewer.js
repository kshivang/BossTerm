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
  // The "view only" badge doubles as the request-control affordance (confirm-first, like
  // the native client's dialog) — the host's user sees its approval toast.
  viewOnlyEl.style.cursor = "pointer";
  viewOnlyEl.title = "Request control";
  viewOnlyEl.onclick = function () {
    if (controlGranted) return;
    if (window.confirm("You're viewing this session read-only. Ask the host for control?"))
      sendMsg({ t: "requestControl" });
  };

  var keybarEl = document.getElementById("keybar");
  var menubtnEl = document.getElementById("menubtn");
  var bodyEl = document.getElementById("body");
  var ctxEl = document.getElementById("ctxmenu");
  var dimsEl = document.getElementById("dims");
  var fithostEl = document.getElementById("fithost");
  var tabBarOnLeft = false;       // mirror the host's tab-bar orientation
  var summaryMode = false;        // host's tabBarSummaryMode: 1 chip/tab vs 1 chip/pane
  var splitDragging = false;      // a divider is being dragged → suppress layout re-renders
  var currentPaneId = null;       // pane the on-screen key bar targets
  function sendMsg(o) { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(o)); }

  // ☰ toggles the left tab drawer (phone); tapping a tab closes it (see selectPane).
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
  // The first pane of [node]'s tree (tree order) — the client's default focus / key-bar
  // target. Host focus is intentionally NOT reflected on the client; the client picks its own.
  function defaultPaneId(node) {
    var first = null;
    (function walk(n) {
      if (!n || first !== null) return;
      if (n.t === "pane") first = n.paneId;
      else { walk(n.a); walk(n.b); }
    })(node);
    return first;
  }

  var controlGranted = false;
  // An upstream control request queued until OUR control is granted (the host only relays
  // upstream requests from controlling clients) — fired from the "control" handler.
  var pendingUpstreamControlTab = null;
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

  // Show the host terminal's grid size (cols × rows) so its bounds are explicit.
  function updateDims() {
    var id = activePaneId(), p = id && panes[id];
    if (p && p.term && p.term.cols) { dimsEl.textContent = p.term.cols + "×" + p.term.rows; dimsEl.style.display = ""; }
    else dimsEl.style.display = "none";
  }
  // "Fit host to my screen": measure the client's cell size + viewport, work out the grid
  // that fills it, and ask the host to resize its window to match (control only).
  fithostEl.onclick = function () {
    var id = activePaneId(), p = id && panes[id];
    if (!p || !p.term || !p.term.cols) return;
    var sc = p.host.querySelector(".xterm-screen"); if (!sc) return;
    var r = sc.getBoundingClientRect();
    var cellW = r.width / p.term.cols, cellH = r.height / p.term.rows;
    if (!(cellW > 0) || !(cellH > 0)) return;
    var CHROME = 8; // leave room for the pane border (1px/side) + a little slack so it fits
    var cols = Math.max(20, Math.floor((stageEl.clientWidth - CHROME) / cellW));
    var rows = Math.max(6, Math.floor((stageEl.clientHeight - CHROME) / cellH));
    sendMsg({ t: "resizeHost", tabId: activeTabId, cols: cols, rows: rows });
  };

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
  // Tab accent presets — same names/colors as the host chip menu's Color ▸ submenu.
  var TAB_COLORS = [
    { name: "Red", css: "#E06C75" }, { name: "Orange", css: "#D19A66" }, { name: "Yellow", css: "#E5C07B" },
    { name: "Green", css: "#98C379" }, { name: "Blue", css: "#61AFEF" }, { name: "Purple", css: "#C678DD" },
    { name: "Gray", css: "#888888" }
  ];
  var menuJustOpened = false; // suppress the synthesized mouse event right after open (mobile long-press)
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
  // Show ctxEl at (x,y), clamped inside the viewport. Re-callable when its height changes.
  function positionMenu(x, y) {
    ctxEl.style.left = "0px"; ctxEl.style.top = "0px"; ctxEl.style.display = "block";
    var w = ctxEl.offsetWidth, h = ctxEl.offsetHeight;
    ctxEl.style.left = Math.max(0, Math.min(x, window.innerWidth - w - 4)) + "px";
    ctxEl.style.top = Math.max(0, Math.min(y, window.innerHeight - h - 4)) + "px";
    menuJustOpened = true; setTimeout(function () { menuJustOpened = false; }, 350);
  }
  function showContextMenu(x, y, paneId) {
    var p = panes[paneId]; if (!p) return;
    var term = p.term;
    var hasSel = false; try { hasSel = term.hasSelection(); } catch (e) {}
    // Reposition (re-run when the menu's height changes, e.g. the AI submenu expands).
    function clamp() { positionMenu(x, y); }
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

  // Tab-chip context menu — mirrors the host's chip menu (New Tab, Rename…, Color ▸,
  // Duplicate, Close, Close Other Tabs, Close Tabs Below). All mutate the host, so it's
  // only attached with control. [pane] null = a whole-tab chip; else a per-split chip.
  function showTabMenu(x, y, tab, pane) {
    var pid = pane ? pane.paneId : tab.id;
    var curTitle = (pane ? pane.title : tab.title) || "";
    ctxEl.innerHTML = "";
    ctxEl.appendChild(ctxItem("New Tab", true, function () { sendMsg({ t: "newTab" }); }));
    ctxEl.appendChild(ctxItem("Rename…", true, function () {
      var nv = window.prompt("Rename", curTitle);
      if (nv !== null) sendMsg({ t: "renameTab", tabId: tab.id, paneId: pid, title: nv.trim() });
    }));
    // Color ▸ — inline-expanding swatch list + Clear (tap-friendly on mobile).
    var colorOpen = false, colorBox = document.createElement("div");
    TAB_COLORS.forEach(function (c) {
      var it = ctxItem(c.name, true, function () { sendMsg({ t: "setTabColor", tabId: tab.id, paneId: pid, color: c.css }); }, { sub: true });
      var dot = document.createElement("span"); dot.className = "ctxswatch"; dot.style.background = c.css;
      it.insertBefore(dot, it.firstChild);
      colorBox.appendChild(it);
    });
    colorBox.appendChild(ctxItem("Clear", true, function () { sendMsg({ t: "setTabColor", tabId: tab.id, paneId: pid, color: null }); }, { sub: true }));
    colorBox.style.display = "none";
    var colorParent = ctxItem("Color ▸", true, function (el) {
      colorOpen = !colorOpen;
      colorBox.style.display = colorOpen ? "block" : "none";
      el.textContent = colorOpen ? "Color ▾" : "Color ▸";
      positionMenu(x, y);
    }, { keepOpen: true });
    ctxEl.appendChild(colorParent); ctxEl.appendChild(colorBox);
    ctxEl.appendChild(ctxSep());
    ctxEl.appendChild(ctxItem("Duplicate Tab", true, function () { sendMsg({ t: "duplicateTab", tabId: tab.id }); }));
    ctxEl.appendChild(ctxItem("Close", true, function () {
      if (pane) sendMsg({ t: "closePane", tabId: tab.id, paneId: pane.paneId });
      else sendMsg({ t: "closeTab", tabId: tab.id });
    }));
    ctxEl.appendChild(ctxItem("Close Other Tabs", true, function () { sendMsg({ t: "closeOtherTabs", tabId: tab.id }); }));
    ctxEl.appendChild(ctxItem("Close Tabs Below", true, function () { sendMsg({ t: "closeTabsBelow", tabId: tab.id }); }));
    positionMenu(x, y);
  }

  // Open the tab menu on right-click (desktop) or long-press (mobile) of a chip.
  function attachChipMenu(el, tab, pane) {
    if (!controlGranted) return; // every item mutates the host
    el.addEventListener("contextmenu", function (e) {
      e.preventDefault(); e.stopPropagation(); showTabMenu(e.clientX, e.clientY, tab, pane);
    });
    var t = null, sx = 0, sy = 0;
    el.addEventListener("touchstart", function (e) {
      if (!e.touches || e.touches.length !== 1) return;
      sx = e.touches[0].clientX; sy = e.touches[0].clientY;
      t = setTimeout(function () { t = null; showTabMenu(sx, sy, tab, pane); }, 500);
    }, { passive: true });
    function cancel(e) {
      if (t && e && e.touches && e.touches[0]) {
        var dx = Math.abs(e.touches[0].clientX - sx), dy = Math.abs(e.touches[0].clientY - sy);
        if (dx < 10 && dy < 10) return;
      }
      if (t) { clearTimeout(t); t = null; }
    }
    el.addEventListener("touchmove", cancel, { passive: true });
    el.addEventListener("touchend", cancel);
    el.addEventListener("touchcancel", cancel);
  }

  document.addEventListener("mousedown", function (e) {
    if (menuJustOpened) return; // ignore the synthesized click that follows a long-press
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
  var overlayActionsEl = document.getElementById("overlay-actions");
  // [actions] = optional [{label, primary, onClick}] rendered as buttons under the message.
  function showOverlay(title, msg, spinning, actions) {
    overlayTitleEl.textContent = title;
    overlayMsgEl.textContent = msg || "";
    overlaySpinnerEl.style.display = spinning ? "" : "none";
    overlayActionsEl.innerHTML = "";
    (actions || []).forEach(function (a) {
      var b = document.createElement("button");
      b.textContent = a.label;
      if (a.primary) b.className = "primary";
      b.onclick = a.onClick;
      overlayActionsEl.appendChild(b);
    });
    overlayEl.style.display = "";
  }
  function hideOverlay() { overlayEl.style.display = "none"; }

  var sessionEnded = false;   // host denied/expired → don't offer a pointless reconnect
  var disconnectShown = false; // de-dupe onerror + onclose firing together
  // The link dropped (host stopped sharing, network blip, etc.): tell the user instead of
  // just flipping the status dot red, and offer to reconnect (reload) or close.
  function showDisconnected() {
    setStatus("down");
    if (sessionEnded || disconnectShown) return;
    disconnectShown = true;
    showOverlay("Disconnected", "The connection to the host was lost.", false, [
      { label: "Reconnect", primary: true, onClick: function () { location.reload(); } },
      { label: "Close", onClick: function () {
          window.close(); // ignored for user-opened tabs — fall back to a hint
          showOverlay("Disconnected", "You can close this tab.", false, []);
        } }
    ]);
  }

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
      // One cluster per tab: per-pane sub-tab chips when the tab is split (and the host
      // isn't in summary mode), otherwise a single tab-level chip — like the host.
      function tabCluster(tab) {
        var group = document.createElement("div"); group.className = "ltab-group";
        var ps = []; panesInOrder(tab.tree, ps);
        if (!summaryMode && tab.tree && tab.tree.t === "split") {
          ps.forEach(function (pane) { group.appendChild(leftChip(tab, pane)); });
        } else {
          group.appendChild(leftChip(tab, null));
        }
        return group;
      }
      // Partition like the native client: the host's own tabs render directly; tabs the host
      // itself mirrors from OTHER sessions render as boxed "via host" groups below.
      var own = [], upstreams = {}, upOrder = [];
      if (layout) layout.tabs.forEach(function (t) {
        if (t.origin) {
          if (!upstreams[t.origin]) {
            upstreams[t.origin] = { name: t.originName, readOnly: !!t.originReadOnly, offline: !!t.originOffline, tabs: [] };
            upOrder.push(t.origin);
          }
          upstreams[t.origin].tabs.push(t);
        } else own.push(t);
      });
      own.forEach(function (tab) { sidebarEl.appendChild(tabCluster(tab)); });
      // Action row mirroring the host's left bar: Split L/R, Split T/B, then New tab.
      if (controlGranted) {
        var actions = document.createElement("div");
        actions.className = "ltab-actions";
        actions.appendChild(splitButton("v"));
        actions.appendChild(splitButton("h"));
        actions.appendChild(newTabButton("+ New tab"));
        sidebarEl.appendChild(actions);
      }
      // Upstream groups: tether + bordered box with header (name · via host, offline/read-only
      // badges, ✕ = ask host to disconnect it), chips, and a relayed action row.
      upOrder.forEach(function (key) {
        var g = upstreams[key];
        var tether = document.createElement("div");
        tether.style.cssText = "width:2px;height:10px;margin-left:13px;background:#4FC3F7;";
        sidebarEl.appendChild(tether);
        var box = document.createElement("div");
        box.style.cssText = "border:1px solid #4FC3F7;border-radius:8px;padding:4px;display:flex;flex-direction:column;gap:4px;";
        var hd = document.createElement("div");
        hd.style.cssText = "display:flex;align-items:center;gap:4px;padding:2px;font-size:11px;color:#b0b0b0;";
        var lbl = document.createElement("span");
        lbl.style.cssText = "flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
        lbl.textContent = "☁ " + (g.name || "remote") + " · via " + ((layout && layout.sessionName) || "host");
        hd.appendChild(lbl);
        if (g.offline) {
          var off = document.createElement("span");
          off.textContent = "· offline"; off.title = "The host lost its connection to this session — content is frozen";
          off.style.cssText = "color:#E57373;font-size:10px;";
          hd.appendChild(off);
        }
        if (g.readOnly) {
          var eye = document.createElement("span");
          eye.textContent = "👁"; eye.title = "Read-only via this host — click to request control";
          eye.style.cursor = "pointer";
          eye.onclick = function (ev) { ev.stopPropagation(); requestUpstreamControl(anchorTab(g).id, g.name || "remote"); };
          hd.appendChild(eye);
        }
        if (controlGranted) {
          var x = document.createElement("span");
          x.textContent = "×"; x.title = "Ask the host to disconnect this upstream";
          x.style.cssText = "cursor:pointer;color:#808080;padding:0 2px;";
          x.onclick = function (ev) {
            ev.stopPropagation();
            if (window.confirm("Ask the host to disconnect from " + (g.name || "this upstream") + "?"))
              sendMsg({ t: "disconnectUpstream", tabId: g.tabs[0].id });
          };
          hd.appendChild(x);
        }
        box.appendChild(hd);
        g.tabs.forEach(function (tab) { box.appendChild(tabCluster(tab)); });
        if (controlGranted) {
          var act = document.createElement("div");
          act.className = "ltab-actions";
          act.appendChild(groupSplitButton("v", g));
          act.appendChild(groupSplitButton("h", g));
          var nt = document.createElement("div");
          nt.className = "newtab"; nt.textContent = "+ New tab";
          nt.title = "New tab in " + (g.name || "remote");
          nt.onclick = function () {
            if (g.readOnly) { requestUpstreamControl(anchorTab(g).id, g.name || "remote"); return; }
            sendMsg({ t: "newTab", tabId: anchorTab(g).id });
          };
          act.appendChild(nt);
          box.appendChild(act);
        }
        sidebarEl.appendChild(box);
      });
      // Bottom: ask the host to mirror another BossTerm share here (native "Add remote").
      if (controlGranted) {
        var add = document.createElement("div");
        add.className = "newtab";
        add.textContent = "☁ Add remote";
        add.title = "Ask the host to mirror another BossTerm share link";
        add.onclick = function () {
          var link = window.prompt("Paste a BossTerm share link — the host will mirror its tabs:");
          if (link && link.trim()) sendMsg({ t: "offerShare", link: link.trim() });
        };
        sidebarEl.appendChild(add);
      }
    } else {
      tabbarEl.classList.remove("hidden");
      menubtnEl.classList.remove("show");
      sidebarEl.classList.remove("show", "open");
      tabbarEl.innerHTML = "";
      if (layout) layout.tabs.forEach(function (tab) {
        var grp = document.createElement("div"); grp.className = "tab-group";
        var ps = []; panesInOrder(tab.tree, ps);
        if (!summaryMode && tab.tree && tab.tree.t === "split") {
          ps.forEach(function (pane) { grp.appendChild(topChip(tab, pane)); });
        } else {
          grp.appendChild(topChip(tab, null));
        }
        tabbarEl.appendChild(grp);
      });
      // Split buttons sit just left of the new-tab (+), like the host's tab-bar actions.
      if (controlGranted) {
        tabbarEl.appendChild(splitButton("v"));
        tabbarEl.appendChild(splitButton("h"));
        tabbarEl.appendChild(newTabButton("+"));
      }
    }
  }

  // Panes of a tab in split-tree order (left/top before right/bottom).
  function panesInOrder(node, out) {
    if (!node) return;
    if (node.t === "pane") out.push(node);
    else { panesInOrder(node.a, out); panesInOrder(node.b, out); }
  }
  // Select a tab (and, for a sub-tab chip, the specific pane) as the viewer's target.
  function selectPane(tabId, paneId) {
    activeTabId = tabId;
    if (paneId) currentPaneId = paneId;
    sidebarEl.classList.remove("open"); // close the phone drawer after picking
    renderTabBar();
    renderStage();
  }
  // Client-side pane focus: move the focus border to [paneId] and reflect it in the sub-tab
  // chips, without rebuilding the stage (so xterms/selection survive). Independent of the host.
  function setClientFocus(paneId) {
    if (currentPaneId === paneId) return;
    currentPaneId = paneId;
    refreshPaneFocus();
    renderTabBar(); // per-split sub-tab chip highlight follows the client's focus
  }
  function refreshPaneFocus() {
    var els = stageEl.querySelectorAll(".pane");
    for (var i = 0; i < els.length; i++) {
      if (els[i].dataset.paneId === currentPaneId) els[i].classList.add("focused");
      else els[i].classList.remove("focused");
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
    return defaultPaneId(tab.tree);
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

  // ---- upstream ("via host") group helpers — native-client parity ----

  // Footer actions of an upstream group target the active tab when it's in the group, else
  // the group's first tab (the host relays them to the origin session).
  function anchorTab(g) {
    for (var i = 0; i < g.tabs.length; i++) if (g.tabs[i].id === activeTabId) return g.tabs[i];
    return g.tabs[0];
  }

  // Confirm-first control request for an upstream session. If we don't control the host yet,
  // chain: request that first, then the relayed request fires when the grant arrives.
  function requestUpstreamControl(tabId, name) {
    if (!window.confirm("Ask for control of " + name + "? Each host along the path approves in turn.")) return;
    if (!controlGranted) {
      pendingUpstreamControlTab = tabId;
      sendMsg({ t: "requestControl" });
      return;
    }
    sendMsg({ t: "requestControl", tabId: tabId });
  }

  // Split button for an upstream group: relayed by the host to the origin; when the host is
  // view-only on it, routes to the control request instead of a silent no-op.
  function groupSplitButton(kind, g) {
    var b = document.createElement("div");
    b.className = "splitbtn";
    b.title = kind === "v" ? "Split vertical (left / right)" : "Split horizontal (top / bottom)";
    b.innerHTML = kind === "v" ? SVG_VSPLIT : SVG_HSPLIT;
    b.onclick = function (ev) {
      ev.stopPropagation();
      if (g.readOnly) { requestUpstreamControl(anchorTab(g).id, g.name || "remote"); return; }
      var tab = anchorTab(g);
      var pid = (tab.id === activeTabId ? activePaneId() : null) || defaultPaneId(tab.tree);
      if (pid) sendMsg({ t: kind === "v" ? "splitVertical" : "splitHorizontal", tabId: tab.id, paneId: pid });
    };
    return b;
  }

  // Close affordance: closes a single pane (sub-tab chip) or the whole tab (tab chip).
  function closeBtn(tabId, paneId) {
    var x = document.createElement("span");
    x.className = "tabclose"; x.textContent = "×"; x.title = paneId ? "Close pane" : "Close tab";
    x.onclick = function (ev) {
      ev.stopPropagation();
      if (paneId) sendMsg({ t: "closePane", tabId: tabId, paneId: paneId });
      else sendMsg({ t: "closeTab", tabId: tabId });
    };
    return x;
  }

  function newTabButton(label) {
    var el = document.createElement("div");
    el.className = "newtab"; el.textContent = label; el.title = "New tab";
    el.onclick = function () { sendMsg({ t: "newTab" }); };
    return el;
  }

  // A top-bar chip. [pane] null = whole-tab chip; otherwise a per-split sub-tab chip.
  function topChip(tab, pane) {
    var isPane = !!pane;
    var color = isPane ? pane.color : tab.color;
    var active = isPane ? (tab.id === activeTabId && pane.paneId === currentPaneId)
                        : (tab.id === activeTabId);
    var el = document.createElement("div");
    el.className = "tab" + (active ? " active" : "");
    if (color) el.style.borderLeft = "3px solid " + color;
    var label = document.createElement("span");
    label.className = "tablabel"; label.textContent = (isPane ? pane.title : tab.title) || "shell";
    el.appendChild(label);
    if (controlGranted) el.appendChild(closeBtn(tab.id, isPane ? pane.paneId : null));
    el.onclick = function () { selectPane(tab.id, isPane ? pane.paneId : null); };
    attachChipMenu(el, tab, isPane ? pane : null);
    return el;
  }

  // A left-bar (Warp-style) chip. [pane] null = whole-tab chip; otherwise a per-split
  // sub-tab chip (title / cwd / branch / accent come from that pane).
  function leftChip(tab, pane) {
    var isPane = !!pane;
    var color = isPane ? pane.color : tab.color;
    var cwd = isPane ? pane.cwd : tab.cwd;
    var branch = isPane ? pane.branch : tab.branch;
    var active = isPane ? (tab.id === activeTabId && pane.paneId === currentPaneId)
                        : (tab.id === activeTabId);
    var el = document.createElement("div");
    el.className = "ltab" + (active ? " active" : "") + (isPane ? " ltab-pane" : "");
    if (color) el.style.borderLeft = "3px solid " + color;
    var row = document.createElement("div"); row.className = "ltab-row";
    var title = document.createElement("span"); title.className = "ltab-title";
    title.textContent = (isPane ? pane.title : tab.title) || "shell";
    row.appendChild(title);
    if (controlGranted) row.appendChild(closeBtn(tab.id, isPane ? pane.paneId : null));
    el.appendChild(row);
    if (cwd) { var s = document.createElement("div"); s.className = "ltab-sub"; s.textContent = abbreviateCwd(cwd); el.appendChild(s); }
    if (branch) { var b = document.createElement("div"); b.className = "ltab-branch"; b.textContent = "⎇ " + branch; el.appendChild(b); }
    el.onclick = function () { selectPane(tab.id, isPane ? pane.paneId : null); };
    attachChipMenu(el, tab, isPane ? pane : null);
    return el;
  }

  function abbreviateCwd(p) {
    var parts = p.split("/").filter(Boolean);
    if (parts.length <= 2) return p;
    return "…/" + parts.slice(-2).join("/");
  }

  function buildNode(node) {
    if (node.t === "pane") {
      var pid = node.paneId;
      var wrap = document.createElement("div");
      // Focus is the CLIENT's own (currentPaneId) — not the host's — and follows clicks.
      wrap.className = "pane" + (pid === currentPaneId ? " focused" : "");
      wrap.dataset.paneId = pid;
      wrap.appendChild(getPane(pid).host);
      // Clicking/tapping a pane focuses it on the client (border + key-bar / typing target).
      wrap.addEventListener("pointerdown", function () { setClientFocus(pid); });
      // Desktop right-click → context menu.
      wrap.addEventListener("contextmenu", function (e) {
        e.preventDefault(); setClientFocus(pid); showContextMenu(e.clientX, e.clientY, pid);
      });
      // Mobile long-press (~500ms) → same menu, anchored at the touch point.
      var lpTimer = null, lpX = 0, lpY = 0;
      wrap.addEventListener("touchstart", function (e) {
        if (!e.touches || e.touches.length !== 1) return;
        lpX = e.touches[0].clientX; lpY = e.touches[0].clientY; setClientFocus(pid);
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
    div.className = "divider " + (node.dir === "h" ? "h" : "v");
    if (controlGranted && node.id) attachDividerDrag(div, split, a, b, node);
    split.appendChild(a); split.appendChild(div); split.appendChild(b);
    return split;
  }

  // Drag a split divider to re-ratio the split — mirrors dragging it on the host. Updates
  // the local layout live for smoothness and streams the ratio to the host (throttled);
  // re-renders are suppressed mid-drag so the divider isn't rebuilt under the pointer.
  function attachDividerDrag(div, split, a, b, node) {
    var horiz = node.dir === "h"; // h = stacked → drag vertically; v = side-by-side → horizontally
    div.classList.add("draggable");
    div.style.cursor = horiz ? "row-resize" : "col-resize";
    var lastSent = 0;
    function ratioAt(e) {
      var r = split.getBoundingClientRect();
      var v = horiz ? (e.clientY - r.top) / r.height : (e.clientX - r.left) / r.width;
      return Math.max(0.1, Math.min(0.9, v));
    }
    function onMove(e) {
      var ratio = ratioAt(e);
      a.style.flex = ratio + " 1 0";
      b.style.flex = (1 - ratio) + " 1 0";
      var now = Date.now();
      if (now - lastSent > 60) { lastSent = now; sendMsg({ t: "resizeSplit", tabId: activeTabId, splitId: node.id, ratio: ratio }); }
      e.preventDefault();
    }
    // End the drag for any reason. pointercancel (gesture interrupted / scroll takeover on
    // touch) MUST be handled too, or splitDragging would stick true and onLayout would stop
    // re-rendering for the rest of the session (frozen viewer) and the window listeners leak.
    function endDrag(commit, e) {
      if (!splitDragging) return;
      splitDragging = false;
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onCancel);
      if (commit) sendMsg({ t: "resizeSplit", tabId: activeTabId, splitId: node.id, ratio: ratioAt(e) }); // final
      renderStage(); // settle to the host layout + resume normal re-renders
    }
    function onUp(e) { endDrag(true, e); }
    function onCancel() { endDrag(false, null); } // interrupted — keep the last-sent ratio
    div.addEventListener("pointerdown", function (e) {
      splitDragging = true;
      e.preventDefault(); e.stopPropagation();
      window.addEventListener("pointermove", onMove);
      window.addEventListener("pointerup", onUp);
      window.addEventListener("pointercancel", onCancel);
    });
  }

  function renderStage() {
    stageEl.innerHTML = "";
    if (!layout) return;
    var tab = layout.tabs.filter(function (t) { return t.id === activeTabId; })[0] || layout.tabs[0];
    if (!tab) return;
    // Keep the key-bar target on a pane that's actually visible in this tab.
    var ids = {}; collectPaneIds(tab.tree, ids);
    if (!currentPaneId || !ids[currentPaneId]) currentPaneId = defaultPaneId(tab.tree);
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
    updateDims();
  }

  function onLayout(m) {
    layout = m;
    tabBarOnLeft = !!m.tabBarOnLeft;
    summaryMode = !!m.summaryMode;
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
    // Mid divider-drag, the host echoes ratio changes back as layouts — don't rebuild the
    // stage (it would destroy the divider under the pointer); onUp re-renders to settle.
    if (!splitDragging) renderStage();
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
        sessionEnded = true; // terminal — keep this message, don't replace with "Disconnected"
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
        updateDims();
        break;
      }
      case "paneOutput": if (m.data) getPane(m.paneId).term.write(m.data); break;
      case "paneResize":
        if (m.cols && m.rows) { getPane(m.paneId).term.resize(m.cols, m.rows); relayoutSinglePane(); updateDims(); }
        break;
      case "presence":
        presenceEl.textContent = m.viewers === 1 ? "1 viewer" : m.viewers + " viewers"; break;
      case "control":
        controlGranted = !!m.granted;
        viewOnlyEl.style.display = controlGranted ? "none" : "";
        fithostEl.style.display = controlGranted ? "" : "none"; // resizing the host needs control
        // Second hop of a chained upstream request (view-only → control → relay upstream).
        if (controlGranted && pendingUpstreamControlTab) {
          sendMsg({ t: "requestControl", tabId: pendingUpstreamControlTab });
          pendingUpstreamControlTab = null;
        }
        renderTabBar(); // show/hide the close + new-tab affordances
        buildKeybar();  // show/hide the on-screen control-key bar
        break;
    }
  };

  ws.onclose = showDisconnected;
  ws.onerror = showDisconnected;
})();
