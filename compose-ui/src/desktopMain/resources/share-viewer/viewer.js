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
      if (controlGranted) sidebarEl.appendChild(newTabButton("+ New tab"));
    } else {
      tabbarEl.classList.remove("hidden");
      menubtnEl.classList.remove("show");
      sidebarEl.classList.remove("show", "open");
      tabbarEl.innerHTML = "";
      if (layout) layout.tabs.forEach(function (tab) { tabbarEl.appendChild(topChip(tab)); });
      if (controlGranted) tabbarEl.appendChild(newTabButton("+"));
    }
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
      // Tapping a pane makes it the key-bar / typing target.
      wrap.addEventListener("pointerdown", function () { currentPaneId = node.paneId; });
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
    root.style.flex = "1 1 0";
    stageEl.appendChild(root);
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
        break;
      }
      case "paneOutput": if (m.data) getPane(m.paneId).term.write(m.data); break;
      case "paneResize": if (m.cols && m.rows) getPane(m.paneId).term.resize(m.cols, m.rows); break;
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
