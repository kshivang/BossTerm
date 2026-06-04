// BossTerm session-sharing viewer (issue #276, Phase 1 — read-only).
//
// Reads the share token from ?t=..., opens a WebSocket to /ws/<token>, and
// renders the host terminal with xterm.js. The host streams a one-time
// `snapshot` then live `output`; we feed both straight into xterm so all
// escape sequences (colors, vim, htop, claude) render faithfully.
//
// Phase 1 is view-only: we do NOT forward keystrokes. Phase 2 will enable
// control once the host grants it (the `control` message carries that flag).

(function () {
  "use strict";

  var params = new URLSearchParams(location.search);
  var token = params.get("t");

  var statusEl = document.getElementById("status");
  var presenceEl = document.getElementById("presence");
  var viewOnlyEl = document.getElementById("viewonly");

  var term = new Terminal({
    cursorBlink: true,
    convertEol: false,
    fontFamily: "Menlo, Monaco, 'Courier New', monospace",
    fontSize: 13,
    scrollback: 5000,
    theme: { background: "#1e1e1e", foreground: "#f8f8f2" },
  });
  term.open(document.getElementById("terminal"));

  if (!token) {
    term.write("\r\n  \x1b[31mMissing share token.\x1b[0m Open the link from BossTerm's Share dialog.\r\n");
    statusEl.className = "down";
    return;
  }

  var controlGranted = false;

  function setStatus(cls) { statusEl.className = cls; }

  var wsProto = location.protocol === "https:" ? "wss" : "ws";
  var ws = new WebSocket(wsProto + "://" + location.host + "/ws/" + encodeURIComponent(token));

  ws.onopen = function () {
    setStatus("live");
    ws.send(JSON.stringify({ t: "hello", name: navigator.platform || "browser" }));
  };

  ws.onmessage = function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    switch (m.t) {
      case "snapshot":
        if (m.cols && m.rows) term.resize(m.cols, m.rows);
        term.reset();
        if (m.data) term.write(m.data);
        break;
      case "output":
        if (m.data) term.write(m.data);
        break;
      case "resize":
        if (m.cols && m.rows) term.resize(m.cols, m.rows);
        break;
      case "presence":
        presenceEl.textContent = m.viewers === 1 ? "1 viewer" : m.viewers + " viewers";
        break;
      case "control":
        controlGranted = !!m.granted;
        viewOnlyEl.style.display = controlGranted ? "none" : "";
        break;
    }
  };

  ws.onclose = function () {
    setStatus("down");
    term.write("\r\n\x1b[33m— disconnected —\x1b[0m\r\n");
  };
  ws.onerror = function () { setStatus("down"); };

  // Phase 2 hook (inert in P1): forward keystrokes only when control is granted.
  term.onData(function (data) {
    if (controlGranted && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ t: "input", data: data }));
    }
  });
})();
