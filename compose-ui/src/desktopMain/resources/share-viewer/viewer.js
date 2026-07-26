// BossTerm session-sharing viewer (issue #276) — window mirror.
//
// Renders the host window model: a tab bar (switch client-side), each tab's split
// tree as nested flex, and one xterm.js instance per pane (keyed by paneId). The
// host streams a Layout (tabs + split trees) + per-pane PaneSnapshot/PaneOutput so
// every pane re-emulates faithfully. With the control link, each pane's keystrokes
// route back to that pane (Input{paneId}). A single-tab share is just a 1-tab window.

(function () {
  "use strict";

  var viewerLogic = window.BossTermViewerLogic;
  if (!viewerLogic) {
    // Same origin and same cache policy as this file, so this should be unreachable — but every
    // reconnect/graphics path below dereferences it, and a bare TypeError deep inside a socket
    // callback reads as "the share is broken". Fail once, legibly, instead.
    var logicError = document.createElement("div");
    logicError.style.padding = "24px";
    logicError.style.font = "13px -apple-system, Menlo, monospace";
    logicError.textContent = "The viewer failed to load (viewer-logic.js). Reload to try again.";
    (document.body || document.documentElement).appendChild(logicError);
    return;
  }
  var params = new URLSearchParams(location.search);
  var token = params.get("t");

  // ---- end-to-end encryption ----
  // The session secret rides in the URL fragment (#k=…), which the browser never sends to any
  // server — so the relay (e.g. a Cloudflare tunnel that terminates TLS) never sees it. From it
  // we derive per-connection AES-GCM keys and encrypt every frame, exactly mirroring the host's
  // SessionCrypto.kt. Requires a secure context (https / localhost) for crypto.subtle; a
  // plain-LAN http link (no #k, no subtle) stays on the legacy plaintext path unchanged.
  var hashParams = new URLSearchParams((location.hash || "").replace(/^#/, ""));
  var secretB64 = hashParams.get("k");
  var canE2E = !!(secretB64 && window.isSecureContext && window.crypto && crypto.subtle);
  // Secure context + crypto, but the link lost its #k → a truncated link. A current host always
  // mints #k for https, so this isn't an old-host case (the host serves this very script). Refuse
  // rather than silently downgrade to plaintext over the relay.
  var e2eMissing = !secretB64 && window.isSecureContext && !!(window.crypto && crypto.subtle);
  var enc = new TextEncoder(), dec = new TextDecoder();
  var crypState = { ready: false, kc2s: null, ks2c: null }; // AES-GCM CryptoKeys, post-handshake
  var saltC = null, secretBytes = null;
  var sendChain = Promise.resolve(), recvChain = Promise.resolve(); // ORDERING queues (see sendMsg/onmessage)
  function b64urlToBytes(s) {
    s = String(s).replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4) s += "=";
    var bin = atob(s), b = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
    return b;
  }
  function bytesToB64url(b) {
    var s = "";
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function randBytes(n) { var b = new Uint8Array(n); crypto.getRandomValues(b); return b; }
  function concatBytes(a, b) { var c = new Uint8Array(a.length + b.length); c.set(a, 0); c.set(b, a.length); return c; }
  function hkdf256(baseKey, salt, infoStr) {
    return crypto.subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: salt, info: enc.encode(infoStr) }, baseKey, 256);
  }
  // Derive {kc2s, ks2c, confirm} from secret + both salts — same labels as the host.
  function deriveSessionKeys(secret, sc, ss) {
    var salt = concatBytes(sc, ss);
    return crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]).then(function (base) {
      return Promise.all([
        hkdf256(base, salt, "bossterm-c2s-v1"),
        hkdf256(base, salt, "bossterm-s2c-v1"),
        hkdf256(base, salt, "bossterm-kc-v1"),
      ]).then(function (r) {
        return Promise.all([
          crypto.subtle.importKey("raw", r[0], "AES-GCM", false, ["encrypt"]),
          crypto.subtle.importKey("raw", r[1], "AES-GCM", false, ["decrypt"]),
        ]).then(function (keys) {
          return { kc2s: keys[0], ks2c: keys[1], confirmB64: bytesToB64url(new Uint8Array(r[2])) };
        });
      });
    });
  }
  // Frame = nonce(12) || AES-256-GCM(ciphertext||tag), AAD = 1 direction byte (0x00 c2s, 0x01 s2c).
  function encryptFrame(text, state) {
    var iv = randBytes(12);
    return crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv, additionalData: new Uint8Array([0x00]), tagLength: 128 },
      state.kc2s, enc.encode(text)
    ).then(function (ct) { return concatBytes(iv, new Uint8Array(ct)).buffer; });
  }
  function decryptFrame(buf, state) {
    var u = new Uint8Array(buf), iv = u.subarray(0, 12), body = u.subarray(12);
    return crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv, additionalData: new Uint8Array([0x01]), tagLength: 128 },
      state.ks2c, body
    ).then(function (pt) { return dec.decode(pt); });
  }
  // Show the verification code (first 8 hex of SHA-256 of the secret) so the user can compare
  // it against the host's Share dialog — matching codes confirm the same untampered key.
  function showE2EBadge() {
    var el = document.getElementById("e2e");
    if (!el || !secretBytes) return;
    crypto.subtle.digest("SHA-256", secretBytes).then(function (h) {
      var b = new Uint8Array(h), s = "";
      for (var i = 0; i < 4; i++) s += ("0" + b[i].toString(16)).slice(-2);
      el.textContent = "🔒 " + s;
      el.style.display = "";
    }).catch(function () {});
  }
  // Length-checked constant-time string compare for the key-confirmation tag (matches the
  // host's MessageDigest.isEqual). No network oracle exists here, but it costs nothing.
  function constantTimeEq(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
  }
  var cryptoFailed = false;
  function onCryptoFailure(socket) {
    // A promise from a socket that already dropped must not poison its replacement.
    if (socket && ws !== socket) return;
    if (cryptoFailed) return;
    cryptoFailed = true;
    sessionEnded = true; // terminal — don't let "Disconnected" overwrite it
    showOverlay("This link is missing its key",
      "Re-copy the full share link from BossTerm — it must include the part after #.", false);
    try { (socket || ws).close(); } catch (e) {}
  }

  var statusEl = document.getElementById("status");
  var tabbarEl = document.getElementById("tabbar");
  var sidebarEl = document.getElementById("sidebar");
  var stageEl = document.getElementById("stage");
  var presenceEl = document.getElementById("presence");
  var viewOnlyEl = document.getElementById("viewonly");
  // Host MCP state (the "MCP" pill) — null until the host's mcpStatus arrives. Mirrors the
  // host's StatusStrip: dot green when the MCP server is running; click (control only) opens
  // a Turn on/off + Attach▸ menu relayed to the host. attached = McpAttachTarget persistence keys.
  var mcp = null;
  var mcpPillEl = document.getElementById("mcppill");
  // Attach targets mirror the host's McpAttachTarget enum (persistenceKey → label).
  var MCP_TARGETS = [
    { key: "CLAUDE_CODE", label: "Claude Code" },
    { key: "CODEX", label: "Codex" },
    { key: "GEMINI", label: "Gemini CLI" },
    { key: "OPENCODE", label: "OpenCode" },
  ];
  function updateMcpPill() {
    if (!mcp) { mcpPillEl.style.display = "none"; return; }
    mcpPillEl.style.display = "";
    mcpPillEl.className = "badge" + (mcp.running ? " on" : "");
  }
  // Menu (reuses the context-menu primitives): Turn MCP on/off + Attach▸ targets (✓ attached).
  function showMcpMenu(x, y) {
    var attached = (mcp.attached || []);
    ctxEl.innerHTML = "";
    ctxEl.appendChild(ctxItem(mcp.enabled ? "Turn MCP off" : "Turn MCP on", true, function () {
      sendMsg({ t: "setMcpEnabled", enabled: !mcp.enabled });
    }));
    ctxEl.appendChild(ctxSep());
    MCP_TARGETS.forEach(function (t) {
      var mark = attached.indexOf(t.key) >= 0 ? "✓ " : "";
      // Attach is a no-op while the server is off — disable it (mirrors the host indicator).
      ctxEl.appendChild(ctxItem(mark + "Attach " + t.label, !!mcp.running, function () {
        sendMsg({ t: "attachMcp", target: t.key });
      }));
    });
    positionMenu(x, y);
  }
  mcpPillEl.onclick = function (e) {
    if (!mcp) return;
    if (viewOnlyGate()) return; // view-only → request-control prompt
    showMcpMenu(e.clientX, e.clientY);
  };
  // Same menu for an upstream "via host" group's MCP — toggle/attach are relayed by the host
  // to the origin via the named tabId (anchorTab(g)).
  function showUpstreamMcpMenu(x, y, g) {
    var tabId = anchorTab(g).id;
    var attached = (g.mcp.attached || []);
    ctxEl.innerHTML = "";
    ctxEl.appendChild(ctxItem(g.mcp.enabled ? "Turn MCP off" : "Turn MCP on", true, function () {
      sendMsg({ t: "setMcpEnabled", enabled: !g.mcp.enabled, tabId: tabId });
    }));
    ctxEl.appendChild(ctxSep());
    MCP_TARGETS.forEach(function (t) {
      var mark = attached.indexOf(t.key) >= 0 ? "✓ " : "";
      ctxEl.appendChild(ctxItem(mark + "Attach " + t.label, !!g.mcp.running, function () {
        sendMsg({ t: "attachMcp", target: t.key, tabId: tabId });
      }));
    });
    positionMenu(x, y);
  }
  // The "view only" badge doubles as the request-control affordance (confirm-first, like
  // the native client's dialog) — the host's user sees its approval toast.
  viewOnlyEl.style.cursor = "pointer";
  viewOnlyEl.title = "Request control";
  viewOnlyEl.onclick = function () {
    if (controlGranted) return;
    if (window.confirm("You're viewing this session read-only. Ask the host for control?"))
      sendMsg({ t: "requestControl" });
  };
  // Floating read-only pill over the stage (native parity): our own view-only connection, or
  // — with control — an active tab whose upstream is read-only for the host. Click = request.
  var viewPillEl = document.getElementById("viewpill");
  function updateViewPill() {
    if (!controlGranted) {
      viewPillEl.textContent = "View only — click to request control";
      viewPillEl.style.display = "";
      viewPillEl.onclick = function () {
        if (window.confirm("You're viewing this session read-only. Ask the host for control?"))
          sendMsg({ t: "requestControl" });
      };
      return;
    }
    var t = null;
    if (layout) for (var i = 0; i < layout.tabs.length; i++)
      if (layout.tabs[i].id === activeTabId) { t = layout.tabs[i]; break; }
    if (t && t.origin && t.originReadOnly) {
      var name = t.originName || "remote";
      viewPillEl.textContent = "View only — click to request control of " + name;
      viewPillEl.style.display = "";
      viewPillEl.onclick = function () { requestUpstreamControl(t.id, name); };
      return;
    }
    viewPillEl.style.display = "none";
  }

  // ---- Boss Calling (voice agent) ----
  // The host mints an ephemeral OpenAI Realtime session (voiceSession); this browser talks
  // WebRTC directly to OpenAI (audio never rides the tunnel/share socket) and forwards the
  // agent's function calls to the host over THIS share socket (voiceToolCall → executed
  // against the shared session → voiceToolResult → back onto the data channel).
  // state is the CONNECTION lifecycle only ("idle" | "connecting" | "live"); what the agent is
  // doing rides alongside it (speaking / pending tool calls), because those overlap — a tool can
  // start while audio is still playing, and collapsing them into one field desynced the bar.
  var voice = { status: null, state: "idle", pc: null, dc: null, mic: null, muted: false,
                seenCalls: {}, watchdog: null, model: null, callToken: null,
                speaking: false, pending: {}, pendingCount: 0, timers: {},
                responseOpen: false, outputsOwed: 0, lastActivity: 0, idleTimer: null };
  // The meter's bars are static markup — query once instead of every animation frame.
  var voiceBars = null;
  var voiceMicOk = !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  var voiceBarEl = document.getElementById("voicebar");
  var voiceCallBtnEl = document.getElementById("voicecallbtn");
  var voiceLabelEl = document.getElementById("voicelabel");
  var voiceCallEl = document.getElementById("voicecall");
  var voiceLevelEl = document.getElementById("voicelevel");
  var voiceStateEl = document.getElementById("voicestate");
  var voiceMuteEl = document.getElementById("voicemute");
  var voiceHangEl = document.getElementById("voicehang");
  var voiceToastEl = document.getElementById("voicetoast");
  var voiceAudioEl = null; // hidden <audio> for the agent's voice, created on first call
  var voiceToastTimer = null;
  function toast(text, ms) {
    voiceToastEl.textContent = text;
    // Sit above the call bar rather than under it.
    voiceToastEl.style.bottom = (voiceBarEl.classList.contains("on") ? voiceBarEl.offsetHeight + 12 : 18) + "px";
    voiceToastEl.style.display = "block";
    if (voiceToastTimer) clearTimeout(voiceToastTimer);
    voiceToastTimer = setTimeout(function () { voiceToastEl.style.display = "none"; }, ms || 4000);
  }
  // One renderer for the whole bottom bar: which half shows, the call's state label, and the
  // meter's colour class. Called on every state change (status, connect, speaking, tool, mute).
  function updateVoiceBar() {
    var available = !!(voice.status && voice.status.available);
    voiceBarEl.classList.toggle("on", available);
    if (!available) { voiceCallEl.classList.remove("on"); layoutForKeyboard(); return; }
    var inCall = voice.state !== "idle";
    voiceCallBtnEl.style.display = inCall ? "none" : "inline-flex";
    voiceCallEl.classList.toggle("on", inCall);
    if (!inCall) {
      voiceCallBtnEl.className = voiceMicOk ? "" : "disabled";
      voiceCallBtnEl.title = voiceMicOk
        ? "Talk to this session's BossTerm agent"
        : "Voice calls need an https share link — open the remote (tunnel) link";
      voiceLabelEl.textContent = "Call BossTerm";
    } else {
      voiceCallBtnEl.className = "";
      var working = voice.pendingCount > 0;
      voiceCallEl.className = "on" +
        (!working && voice.speaking ? " speaking" : "") +
        (working ? " tool" : "") +
        (voice.muted ? " muted" : "");
      voiceStateEl.textContent =
        voice.state === "connecting" ? "Connecting…" :
        working ? "Working…" :
        voice.speaking ? "Agent speaking" :
        voice.muted ? "Muted" : "Listening…";
      voiceStateEl.title = voice.model ? "In a call with " + voice.model : "";
      voiceMuteEl.textContent = voice.muted ? "Unmute" : "Mute";
    }
    layoutForKeyboard(); // the bar changed height → re-reserve space at the bottom
  }
  voiceCallBtnEl.onclick = function () {
    if (!voice.status || !voice.status.available || voice.state !== "idle") return;
    if (!voiceMicOk) { toast("Voice calls need an https share link — open the remote (tunnel) link."); return; }
    if (viewOnlyGate()) return; // view-only → request-control prompt
    // Claim the call SYNCHRONOUSLY, before awaiting the mic: the guard above runs again on a second
    // click while getUserMedia is still pending, and two clicks used to mean two mic streams and two
    // voiceStarts — the second tripping the host's rate limit, whose voiceError then tore down the
    // call the first one had just established.
    voice.state = "connecting";
    voice.muted = false;
    voice.seenCalls = {};
    updateVoiceBar();
    // Mic inside the click gesture (permission prompt), so no minted secret is wasted on a
    // denied microphone.
    navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
      if (voice.state === "idle") { // hung up while the prompt was open
        try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
        return;
      }
      voice.mic = stream;
      sendMsg({ t: "voiceStart", activeTabId: activeTabId });
      voice.watchdog = setTimeout(function () {
        if (voice.state === "connecting") {
          toast("Couldn't establish the voice connection — your network may block WebRTC.");
          endCall(true);
        }
      }, 15000);
    }).catch(function () {
      voice.state = "idle"; // release the claim so the button comes back
      updateVoiceBar();
      toast("Microphone access was denied — allow the mic for this site and try again.");
    });
  };
  voiceMuteEl.onclick = function () {
    if (!voice.mic) return;
    voice.muted = !voice.muted;
    voice.mic.getAudioTracks().forEach(function (t) { t.enabled = !voice.muted; });
    updateVoiceBar();
  };
  voiceHangEl.onclick = function () { endCall(true); toast("Call ended."); };

  // ---- level meter ----
  // Real audio levels, not a canned animation: an AnalyserNode on the mic while you speak and on
  // the agent's own track while it answers, so "it can't hear me" is visible at a glance. Absent
  // WebAudio (or a browser that refuses the context) the bars just stay flat — never fatal.
  var voiceMeter = { ctx: null, mic: null, remote: null, raf: null, buf: null };
  function voiceMeterAttach(kind, stream) {
    try {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC || !stream) return;
      if (!voiceMeter.ctx) {
        voiceMeter.ctx = new AC();
        // Created from a socket message rather than a user gesture, so iOS Safari starts it
        // suspended and the meter would sit flat for the whole call — the one affordance a phone
        // caller has for "can it hear me?".
        if (voiceMeter.ctx.state === "suspended") {
          try { voiceMeter.ctx.resume(); } catch (e) {}
        }
      }
      var an = voiceMeter.ctx.createAnalyser();
      an.fftSize = 256;
      an.smoothingTimeConstant = 0.7;
      voiceMeter.ctx.createMediaStreamSource(stream).connect(an);
      voiceMeter[kind] = an;
      if (!voiceMeter.buf) voiceMeter.buf = new Uint8Array(an.frequencyBinCount);
      voiceMeterRun();
    } catch (e) {}
  }
  function voiceMeterBars() {
    if (!voiceBars) voiceBars = voiceLevelEl.querySelectorAll("i");
    return voiceBars;
  }
  function voiceMeterFlat() {
    var bars = voiceMeterBars();
    for (var i = 0; i < bars.length; i++) bars[i].style.height = "3px";
  }
  function voiceMeterRun() {
    if (voiceMeter.raf) return;
    var tick = function () {
      voiceMeter.raf = null;
      if (voice.state === "idle") { voiceMeterFlat(); return; }
      // While the agent talks, meter ITS track; otherwise the mic (flat when muted).
      var an = voice.speaking ? (voiceMeter.remote || voiceMeter.mic)
             : (voice.muted ? null : voiceMeter.mic);
      var bars = voiceMeterBars();
      if (an && voiceMeter.buf) {
        an.getByteFrequencyData(voiceMeter.buf);
        // Speech energy lives low in the spectrum, so only sample the bottom ~60% of the bins.
        var span = Math.max(1, Math.floor(voiceMeter.buf.length * 0.6 / bars.length));
        for (var i = 0; i < bars.length; i++) {
          var sum = 0;
          for (var j = 0; j < span; j++) sum += voiceMeter.buf[i * span + j] || 0;
          bars[i].style.height = Math.max(3, Math.round(3 + (sum / span / 255) * 21)) + "px";
        }
      } else {
        voiceMeterFlat();
      }
      voiceMeter.raf = requestAnimationFrame(tick);
    };
    voiceMeter.raf = requestAnimationFrame(tick);
  }
  function voiceMeterStop() {
    if (voiceMeter.raf) { try { cancelAnimationFrame(voiceMeter.raf); } catch (e) {} voiceMeter.raf = null; }
    if (voiceMeter.ctx) { try { voiceMeter.ctx.close(); } catch (e) {} }
    voiceMeter.ctx = null; voiceMeter.mic = null; voiceMeter.remote = null; voiceMeter.buf = null;
    voiceMeterFlat();
  }

  // The host bills for this call and cannot hang up the audio (it is browser↔OpenAI), so an
  // abandoned tab would otherwise run to the server's ceiling. Anything that means the call is in
  // use — the agent talking, the user talking, a tool running — pushes the deadline out.
  var VOICE_IDLE_MS = 10 * 60 * 1000;
  function voiceTouch() {
    voice.lastActivity = Date.now();
  }
  function voiceStartIdleWatch() {
    voiceTouch();
    if (voice.idleTimer) clearInterval(voice.idleTimer);
    voice.idleTimer = setInterval(function () {
      if (voice.state === "idle") return;
      if (Date.now() - voice.lastActivity >= VOICE_IDLE_MS) {
        endCall(true);
        toast("Call ended after 10 minutes with nothing happening.", 6000);
      }
    }, 15000);
  }
  function endCall(sendEnd) {
    if (voice.watchdog) { clearTimeout(voice.watchdog); voice.watchdog = null; }
    if (voice.idleTimer) { clearInterval(voice.idleTimer); voice.idleTimer = null; }
    voiceMeterStop();
    try { if (voice.dc) voice.dc.close(); } catch (e) {}
    try { if (voice.pc) voice.pc.close(); } catch (e) {}
    if (voice.mic) { try { voice.mic.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} }
    // Drop the remote stream too: the <audio> element is permanent, so holding srcObject would keep
    // the ended call's stream referenced for the life of the page.
    if (voiceAudioEl) { try { voiceAudioEl.srcObject = null; } catch (e) {} }
    var wasLive = voice.state !== "idle";
    voice.dc = null; voice.pc = null; voice.mic = null; voice.muted = false; voice.model = null;
    voice.callToken = null; voice.speaking = false;
    Object.keys(voice.timers).forEach(function (id) { clearTimeout(voice.timers[id]); });
    voice.timers = {};
    voice.pending = {}; voice.pendingCount = 0; voice.responseOpen = false; voice.outputsOwed = 0;
    voice.state = "idle";
    if (sendEnd && wasLive) sendMsg({ t: "voiceEnd" });
    updateVoiceBar();
  }
  function voiceErrorText(m) {
    switch (m.code) {
      case "no_key": case "disabled":
        return "Voice isn't set up on the host — enable Boss Calling in BossTerm Settings.";
      case "unauthorized":
        return "The host's OpenAI key was rejected — check it in BossTerm Settings.";
      case "not_controller":
        return "You need control of this session to call — request control first.";
      case "rate_limited":
        return "Too many call attempts — wait a moment and try again.";
      case "stale_call":
        return "That call is no longer active — start a new one.";
      case "too_many_calls":
        return "This session already has as many voice calls as it allows.";
      default:
        return "Couldn't start the call" + (m.message ? ": " + m.message : ".");
    }
  }
  function voiceDescribeTool(name, argsJson) {
    try {
      var a = JSON.parse(argsJson || "{}");
      if (name === "run_command" && a.script)
        return "Running: " + a.script.slice(0, 60) + (a.script.length > 60 ? "…" : "");
      if (name === "read_scrollback") return "Reading the terminal…";
      if (name === "search_output" && a.pattern) return "Searching for “" + a.pattern.slice(0, 40) + "”…";
      if (name === "send_input" && a.text)
        return "Typing: " + a.text.slice(0, 40).replace(/\n/g, "⏎") + "…";
      if (name === "send_signal" && a.signal) return "Sending " + a.signal + "…";
    } catch (e) {}
    return "Running: " + name + "…";
  }
  // Function calls surface on the data channel BOTH as function_call_arguments.done and inside
  // response.done's output[] — handle both, dedupe by call_id.
  function voiceHandleFunctionCall(callId, name, argsJson) {
    if (!callId || voice.seenCalls[callId]) return;
    // Dedupe set, trimmed so a long call can't grow it without bound: ids only need to outlive
    // their own round, and anything still pending is preserved.
    if (Object.keys(voice.seenCalls).length > 400) {
      var keep = {};
      Object.keys(voice.pending).forEach(function (id) { keep[id] = true; });
      voice.seenCalls = keep;
    }
    voice.seenCalls[callId] = true;
    voice.pending[callId] = true;
    voice.pendingCount += 1;
    // A reply that never arrives would otherwise pin the call at "Working…" with the agent mute
    // and no way out but End call. Answer ourselves so it can say so out loud. run_command gets a
    // long leash (its own host-side clamp is 600s); reads should be near-instant.
    voice.timers[callId] = setTimeout(function () {
      voiceToolTimedOut(callId);
      // search_output over a large scrollback with an expensive regex can legitimately take a
      // while, so reads get 120s rather than 45.
    }, name === "run_command" ? 630000 : 120000);
    voice.responseOpen = true; // a function call only exists inside a response
    voiceTouch();
    updateVoiceBar();
    toast(voiceDescribeTool(name, argsJson));
    sendMsg({
      t: "voiceToolCall", callId: callId, name: name, argsJson: argsJson || "{}",
      callToken: voice.callToken,
    });
  }
  /**
   * Ask for the follow-up turn exactly once per tool round.
   *
   * Realtime rejects a response.create while one is still generating
   * (conversation_already_has_active_response), and it can request SEVERAL function calls in one
   * response — so answering each result immediately would fire mid-response and once per call.
   * Wait until the response has finished AND every call it asked for has come back.
   */
  function voiceMaybeRequestResponse() {
    if (voice.responseOpen || voice.pendingCount > 0 || voice.outputsOwed === 0) return;
    voice.outputsOwed = 0;
    voiceDcSend({ type: "response.create" });
  }
  /** Hand the model an error result for a call the host never answered, and unblock the round. */
  function voiceToolTimedOut(callId) {
    if (!voice.pending[callId]) return;
    delete voice.pending[callId];
    delete voice.timers[callId];
    voice.pendingCount = Math.max(0, voice.pendingCount - 1);
    voice.outputsOwed += 1;
    voiceDcSend({
      type: "conversation.item.create",
      item: {
        type: "function_call_output", call_id: callId,
        output: JSON.stringify({ error: "The host did not answer this tool call in time." }),
      },
    });
    toast("A tool call timed out — the agent will say so.");
    updateVoiceBar();
    voiceMaybeRequestResponse();
  }
  function voiceDcSend(o) {
    try { if (voice.dc && voice.dc.readyState === "open") voice.dc.send(JSON.stringify(o)); } catch (e) {}
  }
  function connectRealtime(m) {
    if (voice.state !== "connecting") return; // user hung up while the host was minting
    // The mic resolves before voiceStart on every path the host drives today, but a voiceSession
    // arriving while the permission prompt is still open would throw inside the message handler.
    if (!voice.mic) return;
    var pc;
    try { pc = new RTCPeerConnection(); } catch (e) {
      toast("This browser doesn't support WebRTC calls."); endCall(true); return;
    }
    voice.pc = pc;
    voice.model = m.model || null; // host-chosen model, shown on the bar while in a call
    voice.callToken = m.callToken || null; // proves to the host that this call was minted
    voice.mic.getAudioTracks().forEach(function (t) { pc.addTrack(t, voice.mic); });
    voiceMeterAttach("mic", voice.mic);
    if (!voiceAudioEl) {
      voiceAudioEl = document.createElement("audio");
      voiceAudioEl.autoplay = true;
      voiceAudioEl.style.display = "none";
      document.body.appendChild(voiceAudioEl);
    }
    pc.ontrack = function (e) {
      voiceAudioEl.srcObject = e.streams[0];
      voiceMeterAttach("remote", e.streams[0]); // so the meter shows the agent talking too
    };
    pc.onconnectionstatechange = function () {
      if (voice.pc !== pc) return; // a stale pc from an earlier call
      var st = pc.connectionState;
      if ((st === "failed" || st === "disconnected" || st === "closed") && voice.state !== "idle") {
        toast("Voice connection lost.");
        endCall(true);
      }
    };
    var dc = pc.createDataChannel("oai-events");
    voice.dc = dc;
    dc.onopen = function () {
      if (voice.watchdog) { clearTimeout(voice.watchdog); voice.watchdog = null; }
      voiceStartIdleWatch();
      voice.state = "live";
      updateVoiceBar();
      toast("Connected — say something.");
    };
    dc.onmessage = function (ev) {
      var e; try { e = JSON.parse(ev.data); } catch (x) { return; }
      switch (e.type) {
        case "response.created":
          voice.responseOpen = true;
          break;
        case "response.function_call_arguments.done":
          voiceHandleFunctionCall(e.call_id, e.name, e.arguments);
          break;
        case "response.done": {
          var out = (e.response && e.response.output) || [];
          for (var i = 0; i < out.length; i++) {
            if (out[i].type === "function_call")
              voiceHandleFunctionCall(out[i].call_id, out[i].name, out[i].arguments);
          }
          voice.responseOpen = false;
          voiceMaybeRequestResponse(); // safe now: nothing is generating
          break;
        }
        // The user talking is activity too — without this, a caller who listens for ten minutes
        // without the agent answering would be hung up on as idle.
        case "input_audio_buffer.speech_started":
          voiceTouch();
          break;
        case "output_audio_buffer.started":
          voiceTouch();
          voice.speaking = true;
          updateVoiceBar();
          break;
        case "output_audio_buffer.stopped":
          voice.speaking = false;
          updateVoiceBar();
          break;
        case "error":
          if (e.error && e.error.message) toast("Agent error: " + String(e.error.message).slice(0, 80));
          break;
      }
    };
    pc.createOffer().then(function (offer) {
      return pc.setLocalDescription(offer).then(function () {
        // No query string: the GA SDP endpoint rejects any URL parameter with an empty 400, and
        // the model is already bound to the ephemeral secret the host minted.
        return fetch("https://api.openai.com/v1/realtime/calls", {
          method: "POST",
          headers: { "Authorization": "Bearer " + m.clientSecret, "Content-Type": "application/sdp" },
          body: offer.sdp,
        });
      });
    }).then(function (resp) {
      if (!resp.ok) throw new Error("sdp " + resp.status);
      return resp.text();
    }).then(function (answer) {
      return pc.setRemoteDescription({ type: "answer", sdp: answer });
    }).catch(function () {
      if (voice.state !== "idle") {
        toast("Couldn't establish the voice connection — your network may block WebRTC.");
        endCall(true);
      }
    });
  }
  // The tool bridge rides the share socket — when it drops mid-call the agent goes blind,
  // so end the call rather than leave audio-only limbo.
  function voiceOnSocketDown() {
    if (voice.state !== "idle") {
      endCall(false);
      toast("Share connection lost — call ended.");
    }
  }

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
  // A touch gesture has been claimed as a scroll/pan → suppress long-press + contextmenu
  // (Android fires contextmenu during our preventDefault'ed scrolls; iOS slow drags could
  // outlive the long-press timer). Cleared shortly after the finger lifts.
  var touchScrollActive = false, tsaClearTimer = null;
  function markTouchScroll() {
    touchScrollActive = true;
    if (tsaClearTimer) { clearTimeout(tsaClearTimer); tsaClearTimer = null; }
  }
  function unmarkTouchScrollSoon() {
    if (tsaClearTimer) clearTimeout(tsaClearTimer);
    tsaClearTimer = setTimeout(function () { touchScrollActive = false; tsaClearTimer = null; }, 250);
  }
  // A long-press has armed a touch text-selection drag → scrolling is suppressed for the
  // rest of this gesture (see attachTouchScroll) and finger moves extend the selection.
  var touchSelecting = false;
  // Drive xterm's OWN selection model with synthetic mouse events: coordinate-safe (pixels),
  // and it inherits cross-row extension, auto-scroll-at-edges, and WebGL/DOM rendering for
  // free — the same trick attachTouchScroll uses for wheel scrolling. Move/up go to the
  // document, where xterm registers its drag listeners after a mousedown on the screen.
  // downTarget is only used for mousedown (xterm registers its drag move/up listeners on
  // the document after a mousedown on the screen), so move/up callers pass null.
  function dispatchMouse(type, downTarget, x, y, detail, buttons) {
    var target = (type === "mousedown") ? downTarget : document;
    try {
      target.dispatchEvent(new MouseEvent(type, {
        clientX: x, clientY: y, button: 0, buttons: buttons || 0, detail: detail || 1,
        bubbles: true, cancelable: true, view: window,
      }));
    } catch (e) {}
  }
  // Send a client message. E2E: encrypt then send, through an ORDERED promise chain so two
  // rapid keystrokes can't be reordered by async encrypt resolution. Plaintext: send directly.
  function sendMsg(o) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    var socket = ws;
    if (canE2E) {
      var state = crypState;
      sendChain = sendChain.then(function () { return encryptFrame(JSON.stringify(o), state); })
        .then(function (buf) {
          if (ws === socket && socket.readyState === WebSocket.OPEN) socket.send(buf);
        })
        .catch(function () { onCryptoFailure(socket); }); // never silently drop a keystroke
    } else {
      socket.send(JSON.stringify(o));
    }
  }

  // ☰ toggles the left tab drawer (phone); tapping a tab closes it (see selectPane).
  menubtnEl.onclick = function () { sidebarEl.classList.toggle("open"); };
  // Any interaction with the terminal area also closes the drawer (capture phase so it
  // fires even when the pane swallows the event).
  stageEl.addEventListener("pointerdown", function () { sidebarEl.classList.remove("open"); }, true);
  stageEl.addEventListener("touchstart", function () { sidebarEl.classList.remove("open"); }, { passive: true, capture: true });

  // Install banner: shown until dismissed (persists across visits).
  (function () {
    var banner = document.getElementById("installbanner");
    var dismissed = null;
    try { dismissed = localStorage.getItem("bossterm-install-banner"); } catch (e) {}
    if (dismissed !== "dismissed") banner.style.display = "flex";
    document.getElementById("installbanner-close").onclick = function () {
      banner.style.display = "none";
      try { localStorage.setItem("bossterm-install-banner", "dismissed"); } catch (e) {}
    };
  })();

  // Soft keyboard: PUSH the UI up — no font/zoom changes, just a translate — and only
  // as far as the CURSOR needs. If the blinking cursor already sits above the keyboard
  // (and the key bar), nothing moves; if it's hidden, shift by exactly the overlap,
  // capped at the keyboard inset. Re-evaluated as the cursor moves while typing.
  // iOS Safari never resizes the layout viewport for the keyboard, so the visualViewport
  // inset is the only truth; Android shrinks the window itself (inset 0 → no-op).
  var appliedShiftPx = 0;
  var keyboardOpen = false;
  var followRaf = 0;
  // Rewriting document.body's transform blurs the focused textarea on iOS — and re-focusing
  // outside a user gesture can't re-summon the keyboard. So the open/close push lives in
  // layoutForKeyboard (driven by visualViewport geometry, which output can't trigger). The one
  // other writer is followCursor (below), which ONLY ever increases the push to lift a cursor
  // that moved BELOW the keyboard fold (e.g. a TUI taking over and dropping its prompt to the
  // bottom) — it never churns the shift back down on ordinary output moves, so a thinking TUI
  // with a visible cursor writes nothing and the keyboard stays put.

  // Bottom edge (layout-viewport px, with the current shift un-applied) of the focused
  // pane's cursor line — null when the cursor is scrolled off-screen / nothing focused.
  function cursorBottomPx() {
    var p = currentPaneId && panes[currentPaneId]; if (!p) return null;
    var b = p.term.buffer && p.term.buffer.active; if (!b) return null;
    var sc = p.host.querySelector(".xterm-screen"); if (!sc) return null;
    var r = sc.getBoundingClientRect();
    if (!(r.height > 0) || !(p.term.rows > 0)) return null;
    var visRow = b.baseY + b.cursorY - b.viewportY;
    if (visRow < 0 || visRow >= p.term.rows) return null;
    return r.top + appliedShiftPx + (visRow + 1) * (r.height / p.term.rows);
  }
  function layoutForKeyboard() {
    var vv = window.visualViewport;
    // Keyboard height from the visual viewport, independent of scroll: window.innerHeight is the
    // keyboard-invariant layout height; vv.height shrinks only for the keyboard. Crucially this
    // EXCLUDES vv.offsetTop — iOS changes offsetTop when it auto-scrolls to keep the caret visible
    // as a TUI streams output, and folding that in made plain output look like a geometry change.
    var kbH = vv ? Math.max(0, window.innerHeight - vv.height) : 0;
    var open = kbH > 60;
    // Rewrite the body transform ONLY on an actual open↔close transition. Terminal output can't
    // change kbH, so it can't flip `open`, so a thinking TUI never disturbs the focused textarea
    // (the keyboard stays up). We forgo re-pushing for mid-open inset changes (predictive bar,
    // etc.) — rare, and not worth risking a blur.
    if (open !== keyboardOpen) {
      keyboardOpen = open;
      var shift = 0;
      if (open) {
        var visibleBottom = vv.offsetTop + vv.height;
        var clear = (keybarEl.style.display !== "none" ? keybarEl.offsetHeight : 0) + 8;
        var cb = cursorBottomPx();
        // Unknown cursor → fall back to the full push (old behavior).
        shift = cb === null ? Math.round(kbH)
          : Math.round(Math.max(0, Math.min(kbH, cb - visibleBottom + clear)));
      }
      if (shift !== appliedShiftPx) {
        appliedShiftPx = shift;
        // Transforming the focused textarea's ancestor blurs it on iOS. This now runs only on a
        // genuine, gesture-adjacent open/close; re-assert focus when the keyboard is up and the
        // textarea was active.
        var wasFocused = open && document.activeElement === activeTextarea();
        document.body.style.transform = shift ? "translateY(-" + shift + "px)" : "";
        if (wasFocused) { var ta = activeTextarea(); if (ta) try { ta.focus({ preventScroll: true }); } catch (e) {} }
      }
      // Keyboard just closed → apply any auto-fit we deferred while it was up.
      if (!open) maybeAutoFit();
    }
    // Blur-safe (touches no textarea ancestor): keep the key bar riding the keyboard top and
    // reserve body padding. Uses the scroll-invariant kbH so it doesn't jitter, and is safe to
    // run on every event — including output-driven scroll events.
    positionBottomBars(Math.max(0, Math.round(kbH) - appliedShiftPx));
  }
  // The two fixed bars stack: #keybar rides the keyboard top, the Boss Calling bar sits on top of
  // it, and #body reserves both so the terminal is never hidden behind them.
  function positionBottomBars(kbBottomPx) {
    keybarEl.style.bottom = kbBottomPx + "px";
    var keyH = (keybarEl.style.display !== "none" && keybarEl.offsetHeight) ? keybarEl.offsetHeight : 0;
    voiceBarEl.style.bottom = (kbBottomPx + keyH) + "px";
    var voiceH = voiceBarEl.classList.contains("on") ? voiceBarEl.offsetHeight : 0;
    bodyEl.style.paddingBottom = (keyH + voiceH) + "px";
  }
  // While the keyboard is up, keep the cursor visible above it. Only pushes FURTHER up (never
  // reduces the shift) and only when the cursor sits below the visible fold — so the common
  // case (cursor already visible, output streaming) writes nothing. This is what brings the
  // input line back into view when a TUI takes over and moves the cursor to the bottom after
  // the keyboard was already raised; without it the input hides until the keyboard is toggled.
  function followCursor() {
    if (!keyboardOpen) return;
    var vv = window.visualViewport; if (!vv) return;
    var cb = cursorBottomPx(); if (cb === null) return;
    var kbH = Math.max(0, window.innerHeight - vv.height);
    var visibleBottom = vv.offsetTop + vv.height;
    var clear = (keybarEl.style.display !== "none" ? keybarEl.offsetHeight : 0) + 8;
    var want = Math.round(Math.max(0, Math.min(kbH, cb - visibleBottom + clear)));
    if (want <= appliedShiftPx) return; // cursor already clear of the keyboard — don't churn
    appliedShiftPx = want;
    var wasFocused = document.activeElement === activeTextarea();
    document.body.style.transform = "translateY(-" + want + "px)";
    if (wasFocused) { var ta = activeTextarea(); if (ta) try { ta.focus({ preventScroll: true }); } catch (e) {} }
    positionBottomBars(Math.max(0, Math.round(kbH) - appliedShiftPx));
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", layoutForKeyboard);
    window.visualViewport.addEventListener("scroll", layoutForKeyboard);
  }

  // ---- on-screen key bar (mobile control keys) ----
  var KEY_ROW = [
    ["Esc", "\x1b"], ["Tab", "\t"], ["⏎", "\r"], ["^C", "\x03"], ["^D", "\x04"], ["^Z", "\x1a"], ["^L", "\x0c"],
    ["←", "\x1b[D"], ["↑", "\x1b[A"], ["↓", "\x1b[B"], ["→", "\x1b[C"]
  ];
  function activeTextarea() {
    var p = currentPaneId && panes[currentPaneId];
    return p ? (p.term.textarea || p.host.querySelector(".xterm-helper-textarea")) : null;
  }
  function focusCurrent() {
    // Focus the hidden textarea directly (term.focus() doesn't always re-summon the iOS
    // soft keyboard); must run inside a user gesture for iOS to show the keyboard.
    var ta = activeTextarea();
    if (ta) { try { ta.focus({ preventScroll: true }); } catch (e) { try { ta.focus(); } catch (e2) {} } }
    else if (currentPaneId && panes[currentPaneId]) { try { panes[currentPaneId].term.focus(); } catch (e) {} }
  }
  // The ⌨ keybar button toggles the soft keyboard: blur the focused textarea to dismiss it when
  // it's up, or focus to summon it when it's down. Runs inside the button's tap gesture, so iOS
  // honours the focus() to re-show. "Up" = a keyboard inset OR the textarea already focused.
  function toggleKeyboard() {
    var ta = activeTextarea();
    var up = softKeyboardUp() || (ta != null && document.activeElement === ta);
    if (up) { if (ta) try { ta.blur(); } catch (e) {} }
    else focusCurrent();
  }
  function sendKey(seq) {
    if (!currentPaneId) return;
    sendInput(currentPaneId, seq);
  }
  // Wire a key-strip button so pressing it NEVER dismisses the soft keyboard: preventDefault
  // on pointerdown stops the button from stealing focus off the terminal's hidden textarea
  // (a plain click — esp. Enter/⏎ — otherwise blurs it and drops the keyboard). Fire on
  // pointerup with a move-guard so a horizontal scroll of the bar doesn't send a key; refocus
  // the textarea defensively to keep the keyboard up.
  function wireKeyButton(b, seq, onTap) {
    function hl(on) {
      b.style.background = on ? "#4a90e2" : ""; b.style.color = on ? "#fff" : "";
      b.style.borderColor = on ? "#4a90e2" : "";
    }
    // onTap overrides the default action (used by the ⌨ toggle, which manages focus itself).
    function fire() { if (onTap) { onTap(); return; } if (seq != null) sendKey(seq); focusCurrent(); }
    var sx = 0, sy = 0, moved = false, touched = false;
    // The keys are <div>s, NOT <button>s: a non-focusable element doesn't steal focus from
    // the terminal's hidden textarea on iOS, so the soft keyboard stays up WITHOUT a
    // touchstart preventDefault — which means a horizontal swipe still scrolls the key bar
    // natively (overflow-x). A 10px move-guard tells a swipe from a tap.
    b.addEventListener("touchstart", function (e) {
      touched = true; moved = false;
      var t = e.touches[0]; if (t) { sx = t.clientX; sy = t.clientY; }
      hl(true);
    }, { passive: true });
    b.addEventListener("touchmove", function (e) {
      var t = e.touches[0]; if (!t) return;
      if (Math.abs(t.clientX - sx) > 10 || Math.abs(t.clientY - sy) > 10) { moved = true; hl(false); }
    }, { passive: true });
    b.addEventListener("touchend", function (e) {
      hl(false);
      if (!moved) { e.preventDefault(); fire(); } // preventDefault stops the synthetic click
    }, { passive: false });
    b.addEventListener("touchcancel", function () { hl(false); });
    // Desktop mouse: keep focus on mousedown; the click fires. (Suppressed after a touch
    // sequence so a touch device doesn't double-fire via the synthetic click.)
    b.addEventListener("mousedown", function (e) { e.preventDefault(); });
    b.addEventListener("click", function () {
      if (touched) { touched = false; return; }
      fire();
    });
  }
  function buildKeybar() {
    keybarEl.innerHTML = "";
    if (!controlGranted) { keybarEl.style.display = "none"; layoutForKeyboard(); return; }
    keybarEl.style.display = "flex";
    var kb = document.createElement("div");
    kb.className = "keybtn"; kb.textContent = "⌨"; kb.title = "Show / hide keyboard";
    wireKeyButton(kb, null, toggleKeyboard); // toggles the soft keyboard up/down
    keybarEl.appendChild(kb);
    KEY_ROW.forEach(function (k) {
      var b = document.createElement("div");
      b.className = "keybtn"; b.textContent = k[0];
      wireKeyButton(b, k[1]);
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
  var DEFAULT_TERMINAL_FONT_FAMILY =
    '"BossTerm Nerd Font", "Apple Color Emoji", "Segoe UI Emoji", ' +
    '"Noto Color Emoji", "BossTerm Symbols", monospace';

  // xterm's WebGL renderer caches glyphs. Let CSS fetch the primary/fallback faces only when
  // content needs them, then invalidate the atlas so icons do not remain missing-glyph boxes.
  function refreshTerminalFonts(remeasure) {
    Object.keys(panes).forEach(function (id) {
      var p = panes[id], t = p.term;
      try {
        if (remeasure) {
          var family = t.options.fontFamily;
          t.options.fontFamily = family + " ";
          t.options.fontFamily = family;
        }
        t.clearTextureAtlas();
        t.refresh(0, t.rows - 1);
      } catch (e) {}
      scheduleGraphicsDraw(p);
    });
    relayoutSinglePane();
  }
  if (document.fonts) {
    document.fonts.ready.then(function () { refreshTerminalFonts(true); })
      .catch(function () { /* system fallbacks remain available if a font cannot load */ });
    if (typeof document.fonts.addEventListener === "function") {
      document.fonts.addEventListener("loadingdone", function () { refreshTerminalFonts(false); });
    }
  }
  // Give the active single pane its NATURAL width so a wide host terminal scrolls
  // horizontally inside #stage. (xterm's own viewport is a y-scroll container that clips
  // x, so we can't get native horizontal scroll from it — instead we expose the full
  // width by sizing the pane to content and letting #stage scroll both axes. Vertical
  // scrollback stays inside xterm; pinch-zoom works on top. Splits keep the fill layout.)
  // Phone = the same breakpoint as the drawer/splits-as-tabs defaults (≤700px).
  function isPhone() {
    return !!(window.matchMedia && window.matchMedia("(max-width: 700px)").matches);
  }
  function relayoutSinglePane() {
    if (!layout) return;
    var tab = null, i;
    for (i = 0; i < layout.tabs.length; i++) if (layout.tabs[i].id === activeTabId) tab = layout.tabs[i];
    if (!tab) tab = layout.tabs[0];
    var pid = displayedSinglePaneId(tab); // single-pane tab, or the shown pane in splits-as-tabs
    if (!pid) return;
    var p = panes[pid]; if (!p) return;
    requestAnimationFrame(function () {
      var sc = p.host.querySelector(".xterm-screen");
      if (sc) { var w = Math.ceil(sc.getBoundingClientRect().width); if (w > 0) p.host.style.width = w + "px"; }
      scheduleGraphicsDraw(p);
    });
  }
  function softKeyboardUp() {
    var vv = window.visualViewport;
    return !!vv && (window.innerHeight - vv.height) > 60;
  }
  function onViewportChange() {
    relayoutSinglePane();
    Object.keys(panes).forEach(function (id) { scheduleGraphicsDraw(panes[id]); });
    autoFitPending = true;
    maybeAutoFit();
  }
  window.addEventListener("resize", onViewportChange);
  window.addEventListener("orientationchange", onViewportChange);

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
    Object.keys(panes).forEach(function (id) {
      try { panes[id].term.options.fontSize = viewerFont; } catch (e) {}
      scheduleGraphicsDraw(panes[id]);
    });
    relayoutSinglePane();
  }
  // Fit the active pane's font so the whole width shows (zoom-out to fit).
  function fitWidth() {
    var tab = activeTabNode(); if (!tab) return;
    var pid = displayedSinglePaneId(tab); if (!pid) return;
    var p = panes[pid]; if (!p) return;
    var screen = p.host.querySelector(".xterm-screen"); if (!screen) return;
    var avail = stageEl.clientWidth - 2, w = screen.getBoundingClientRect().width;
    if (avail > 0 && w > 0) applyFont(curFont() * (avail / w));
  }
  // Fit the active pane's font so the whole GRID (cols AND rows) fits the stage box —
  // the phone default: nothing overflows, so a vertical swipe scrolls SCROLLBACK.
  function fitScreen() {
    var tab = activeTabNode(); if (!tab) return;
    var pid = displayedSinglePaneId(tab); if (!pid) return;
    var p = panes[pid]; if (!p) return;
    var screen = p.host.querySelector(".xterm-screen"); if (!screen) return;
    var r = screen.getBoundingClientRect();
    var availW = stageEl.clientWidth - 2, availH = stageEl.clientHeight - 2;
    if (!(r.width > 0) || !(r.height > 0) || availW <= 0 || availH <= 0) return;
    applyFont(curFont() * Math.min(availW / r.width, availH / r.height));
  }
  // Phones default to fit-screen; the user's explicit choice persists (like splits-as-tabs).
  var fitMode = (function () {
    var saved = null;
    try { saved = localStorage.getItem("bossterm-fit-mode"); } catch (e) {}
    if (saved === "screen" || saved === "off") return saved;
    return isPhone() ? "screen" : "off";
  })();
  var zoomfitBtn = document.getElementById("zoomfit");
  function refreshFitBtn() {
    var on = isPhone() && fitMode === "screen";
    zoomfitBtn.style.background = on ? "#4a90e2" : "";
    zoomfitBtn.style.color = on ? "#fff" : "";
    zoomfitBtn.style.borderColor = on ? "#4a90e2" : "";
  }
  function setFitMode(m) {
    fitMode = m;
    try { localStorage.setItem("bossterm-fit-mode", m); } catch (e) {}
    refreshFitBtn();
  }
  // Re-fit once per fresh-geometry event (layout / snapshot / host resize / rotation) —
  // never on plain output, and never against a manual zoom (zoom +/- flips fitMode off).
  var autoFitPending = false;
  var fithostPrompted = false; // one-time "fit host to this phone?" offer when control lands
  function maybeAutoFit() {
    if (fitMode !== "screen" || !autoFitPending) return;
    // Never refit while the soft keyboard is up: fitScreen→applyFont changes the font size, which
    // re-renders xterm and blurs the focused textarea, dropping the keyboard mid-typing. Safari
    // fires window.resize (toolbar/keyboard) on the user's cadence, so this is THE drop trigger.
    // Keep the pending flag and refit when the keyboard closes (layoutForKeyboard calls back).
    if (softKeyboardUp()) return;
    autoFitPending = false;
    requestAnimationFrame(fitScreen);
  }
  document.getElementById("zoomin").onclick = function () { setFitMode("off"); applyFont(curFont() + 1); };
  document.getElementById("zoomout").onclick = function () { setFitMode("off"); applyFont(curFont() - 1); };
  zoomfitBtn.onclick = function () {
    if (isPhone()) {
      // Toggle fit-screen: on = fit now (and track geometry changes); off = host font size.
      if (fitMode === "screen") { setFitMode("off"); viewerFont = 0; applyFont(curFont()); }
      else { setFitMode("screen"); fitScreen(); }
    } else fitWidth();
  };
  refreshFitBtn();

  // ---- "splits as tabs" (viewer-local) ----
  // Render only the selected pane of a split tab, full-screen — switch panes via the
  // sub-tab chips — instead of the side-by-side split. Defaults ON for phone screens;
  // the user's explicit choice persists.
  var splitsAsTabs = (function () {
    var saved = null;
    try { saved = localStorage.getItem("bossterm-splits-as-tabs"); } catch (e) {}
    if (saved === "1") return true;
    if (saved === "0") return false;
    return isPhone();
  })();
  var splitTabsBtn = document.getElementById("splittabs");
  function refreshSplitTabsBtn() {
    splitTabsBtn.style.background = splitsAsTabs ? "#4a90e2" : "";
    splitTabsBtn.style.color = splitsAsTabs ? "#fff" : "";
    splitTabsBtn.style.borderColor = splitsAsTabs ? "#4a90e2" : "";
  }
  splitTabsBtn.onclick = function () {
    splitsAsTabs = !splitsAsTabs;
    try { localStorage.setItem("bossterm-splits-as-tabs", splitsAsTabs ? "1" : "0"); } catch (e) {}
    refreshSplitTabsBtn();
    renderTabBar();
    renderStage();
  };
  refreshSplitTabsBtn();
  // The single pane the stage currently shows for [tab], or null when a split renders as a grid.
  function displayedSinglePaneId(tab) {
    if (!tab || !tab.tree) return null;
    if (tab.tree.t === "pane") return tab.tree.paneId;
    if (splitsAsTabs && tab.tree.t === "split") return currentPaneId || defaultPaneId(tab.tree);
    return null;
  }
  function findPaneNode(node, paneId) {
    if (!node) return null;
    if (node.t === "pane") return node.paneId === paneId ? node : null;
    return findPaneNode(node.a, paneId) || findPaneNode(node.b, paneId);
  }

  // Show the host terminal's grid size (cols × rows) so its bounds are explicit.
  function updateDims() {
    var id = activePaneId(), p = id && panes[id];
    if (p && p.term && p.term.cols) { dimsEl.textContent = p.term.cols + "×" + p.term.rows; dimsEl.style.display = ""; }
    else dimsEl.style.display = "none";
  }
  // "Fit host to my screen": measure the client's cell size + viewport, work out the grid
  // that fills it, and ask the host to resize its window to match (control only).
  function fitHostGrid() {
    var id = activePaneId(), p = id && panes[id];
    if (!p || !p.term || !p.term.cols) return null;
    var sc = p.host.querySelector(".xterm-screen"); if (!sc) return null;
    var r = sc.getBoundingClientRect();
    // Normalize cell metrics to the HOST's font size: "fit host to my screen" means a
    // grid this screen can show at a READABLE font. With fit-screen active (the phone
    // default) the current font is shrunk so the whole grid fits — measuring at THAT
    // font would conclude the grid "already fits" and ask for nothing.
    var scale = (((theme && theme.fontSize) || 13) / curFont()) || 1;
    var cellW = (r.width / p.term.cols) * scale, cellH = (r.height / p.term.rows) * scale;
    if (!(cellW > 0) || !(cellH > 0)) return null;
    var CHROME = 8; // leave room for the pane border (1px/side) + a little slack so it fits
    return {
      cols: Math.max(20, Math.floor((stageEl.clientWidth - CHROME) / cellW)),
      rows: Math.max(6, Math.floor((stageEl.clientHeight - CHROME) / cellH)),
      curCols: p.term.cols, curRows: p.term.rows,
    };
  }
  function fitHostNow() {
    var g = fitHostGrid(); if (!g) return;
    sendMsg({ t: "resizeHost", tabId: activeTabId, cols: g.cols, rows: g.rows });
  }
  fithostEl.onclick = fitHostNow;

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
        if (txt) sendInput(paneId, txt);
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
    var t = null, sx = 0, sy = 0, moved = 0;
    el.addEventListener("touchstart", function (e) {
      if (!e.touches || e.touches.length !== 1) {
        if (t) { clearTimeout(t); t = null; }
        return;
      }
      sx = e.touches[0].clientX; sy = e.touches[0].clientY; moved = 0;
      t = setTimeout(function () {
        t = null;
        if (touchScrollActive || moved >= 6) return; // scrolling, not pressing
        showTabMenu(sx, sy, tab, pane);
      }, 500);
    }, { passive: true });
    function cancel(e) {
      if (e && e.touches && e.touches.length === 1 && e.touches[0]) {
        var dx = Math.abs(e.touches[0].clientX - sx), dy = Math.abs(e.touches[0].clientY - sy);
        moved = Math.max(moved, dx, dy);
        if (t && dx < 6 && dy < 6) return;
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
  var disconnectShown = false; // final prompt is shown at most once
  var reconnectAttempt = 0;
  var reconnectTimer = null;
  var reconnectStableTimer = null;
  var MAX_AUTO_RECONNECTS = 3;
  var RECONNECT_STABLE_MS = 30000;
  // The automatic budget is exhausted: offer a manual retry (which reloads the page and gives
  // it a fresh budget) or close. Terminal failures such as denial / bad E2E keys never get here.
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
  // A reconnect only earns a fresh budget after remaining healthy continuously. A host that
  // accepts, sends Layout, then immediately drops therefore still exhausts the three attempts.
  function markConnectionHealthy(socket) {
    if (!socket || reconnectAttempt === 0 || reconnectStableTimer) return;
    reconnectStableTimer = setTimeout(function () {
      reconnectStableTimer = null;
      if (ws === socket && socket.readyState === 1) reconnectAttempt = 0;
    }, RECONNECT_STABLE_MS);
  }

  // Drop a pending "this connection settled" timer. A session that ended for good must not leave
  // one armed to hand a dead socket a fresh retry budget.
  function disarmConnectionHealth() {
    if (reconnectStableTimer) clearTimeout(reconnectStableTimer);
    reconnectStableTimer = null;
  }

  // Retry a transient drop before asking the user.
  function handleConnectionLost(socket) {
    if (socket && ws !== socket) return; // stale event from a superseded connection
    if (socket) ws = null;               // disarm its pending encrypt/decrypt callbacks
    setStatus("down");
    if (sessionEnded || reconnectTimer) return;
    disarmConnectionHealth();
    var decision = viewerLogic.nextReconnectAttempt(reconnectAttempt, MAX_AUTO_RECONNECTS);
    if (!decision.retry) {
      showDisconnected();
      return;
    }
    reconnectAttempt = decision.attempt;
    var attempt = reconnectAttempt;
    showOverlay(
      "Reconnecting…",
      "The connection was lost. Automatic attempt " + attempt + " of " + MAX_AUTO_RECONNECTS + ".",
      true
    );
    var retryDelay = Math.max(500, attempt * 1500 + Math.floor(Math.random() * 501) - 250);
    reconnectTimer = setTimeout(function () {
      reconnectTimer = null;
      if (!sessionEnded) connectWebSocket();
    }, retryDelay);
  }

  function deviceName() {
    return localStorage.getItem("bossterm.name") || navigator.platform || "browser";
  }

  // ---- host-decoded Sixel / Kitty graphics ----
  // xterm.js 5 does not understand Kitty and cannot safely resolve file-backed Kitty transfers
  // from the host machine. BossTerm therefore sends normalized PNG/image bytes plus its exact
  // ImageCell placement grid; this transparent canvas mirrors the native renderer cell-for-cell.
  // The host separately caps encoded wire rasters at 16 MiB per pane. Browser memory also holds
  // decoded RGBA, which can be much larger than PNG/JPEG bytes, so its bounded heap limits must
  // leave room for normal compression ratios.
  var MAX_PANE_GRAPHICS_BYTES = 96 * 1024 * 1024;
  var MAX_VIEWER_GRAPHICS_BYTES = 192 * 1024 * 1024;
  // PaneSnapshot replaces this compatibility fallback with the pane's real host history cap
  // before painting. Matching caps keep SharedImageCellRun absolute rows aligned after trims.
  var DEFAULT_WEB_VIEWER_SCROLLBACK_LINES = 10000;
  var MAX_WEB_VIEWER_SCROLLBACK_LINES = 20000;
  var ALLOWED_GRAPHICS_MIME_TYPES = {
    "image/png": true, "image/jpeg": true, "image/gif": true,
    "image/bmp": true, "image/webp": true
  };
  var MAX_GRAPHICS_RESYNCS = 3;
  var GRAPHICS_RESYNC_TIMEOUT_MS = 3000;
  var viewerGraphicsBytes = 0;

  function newGraphicsState() {
    return { revision: null, cells: [], cellsByRow: {}, images: {}, pending: {},
             bytes: 0, canvas: null, raf: 0,
             historyLines: null, rowOffset: 0,
             rejected: {}, resyncPending: false, resyncAttempts: 0, resyncTimer: null,
             resyncDegraded: false };
  }

  function disposeGraphics(paneId, p) {
    if (!p || !p.graphics) return;
    var g = p.graphics;
    if (g.raf) cancelAnimationFrame(g.raf);
    if (g.resyncTimer) clearTimeout(g.resyncTimer);
    clearGraphicsDegraded(paneId, g); // a closed pane must not keep warning about its graphics
    Object.keys(g.pending).forEach(function (id) { removePendingGraphicsImage(g, id); });
    Object.keys(g.images).forEach(function (id) { removeGraphicsImage(g, id); });
    if (g.canvas && g.canvas.parentNode) g.canvas.parentNode.removeChild(g.canvas);
    g.images = {}; g.pending = {}; g.cells = []; g.cellsByRow = {};
    g.bytes = 0; g.canvas = null; g.raf = 0;
    g.resyncTimer = null;
    setPaneGraphicsMode(p, false);
  }

  function removeGraphicsImage(g, id) {
    var item = g.images[id];
    if (!item) return;
    if (item.image) { item.image.onload = null; item.image.onerror = null; }
    g.bytes = Math.max(0, g.bytes - item.bytes);
    viewerGraphicsBytes = Math.max(0, viewerGraphicsBytes - item.bytes);
    delete g.images[id];
  }

  function removePendingGraphicsImage(g, id) {
    var item = g.pending[id];
    if (!item) return;
    if (item.image) { item.image.onload = null; item.image.onerror = null; }
    g.bytes = Math.max(0, g.bytes - item.bytes);
    viewerGraphicsBytes = Math.max(0, viewerGraphicsBytes - item.bytes);
    delete g.pending[id];
  }

  function graphicsMemoryFits(g, addedBytes, replacedBytes) {
    return viewerLogic.graphicsMemoryFits(
      g.bytes,
      viewerGraphicsBytes,
      addedBytes,
      replacedBytes,
      MAX_PANE_GRAPHICS_BYTES,
      MAX_VIEWER_GRAPHICS_BYTES
    );
  }

  function base64Bytes(data) {
    if (!data) return 0;
    var padding = data.length > 1 && data.charAt(data.length - 2) === "=" ? 2 :
      (data.charAt(data.length - 1) === "=" ? 1 : 0);
    return Math.max(0, Math.floor(data.length * 3 / 4) - padding);
  }

  function indexGraphicsCells(cells) {
    var byRow = {};
    cells.forEach(function (run) {
      var row = String(run.row);
      if (!byRow[row]) byRow[row] = [];
      byRow[row].push(run);
    });
    return byRow;
  }

  function setPaneGraphicsMode(p, enabled) {
    if (!p || p.graphicsTransparent === enabled) return;
    var current = p.term.options.theme || {};
    var next = {}, key;
    for (key in current) if (Object.prototype.hasOwnProperty.call(current, key)) next[key] = current[key];
    next.background = enabled ? "rgba(0,0,0,0)" : ((theme && theme.background) || "#1e1e1e");
    try {
      if (enabled) {
        p.term.options.allowTransparency = true;
        p.term.options.theme = next;
      } else {
        p.term.options.theme = next;
        p.term.options.allowTransparency = false;
      }
      p.graphicsTransparent = enabled;
    } catch (e) {}
  }

  function ensureGraphicsCanvas(p) {
    var screen = p.host.querySelector(".xterm-screen");
    if (!screen) return null;
    var g = p.graphics;
    if (!g.canvas) {
      var canvas = document.createElement("canvas");
      canvas.className = "bossterm-graphics";
      canvas.setAttribute("aria-hidden", "true");
      canvas.style.position = "absolute";
      canvas.style.inset = "0";
      canvas.style.pointerEvents = "none";
      g.canvas = canvas;
    }
    if (g.canvas.parentNode !== screen) {
      // Keep images below xterm's glyph/selection/cursor renderer. The terminal's default
      // background becomes transparent only while this pane has drawable graphics; the term
      // host supplies the opaque theme background behind both layers.
      screen.insertBefore(g.canvas, screen.querySelector("canvas"));
    }
    return g.canvas;
  }

  function scheduleGraphicsDraw(p) {
    if (!p || !p.graphics || p.graphics.raf) return;
    var g = p.graphics;
    // onRender/onScroll/onResize fire for every pane, and the overwhelming majority never carry
    // graphics. With nothing placed, nothing decoding, and no canvas left to tear down there is
    // no frame to draw — skip it rather than queue one rAF per render per pane.
    if (!g.cells.length && !g.canvas && !Object.keys(g.pending).length) return;
    g.raf = requestAnimationFrame(function () {
      g.raf = 0;
      drawPaneGraphics(p);
    });
  }

  function drawPaneGraphics(p) {
    var g = p.graphics;
    var hasDrawableImage = Object.keys(g.images).some(function (id) {
      var image = g.images[id].image;
      return image && image.complete && image.naturalWidth && image.naturalHeight;
    });
    if (!g.cells.length || !hasDrawableImage) {
      // Keep the last decoded frame visible while a replacement raster is in flight. Clearing
      // here would flash the terminal background and rebuild xterm's texture atlas twice.
      if (g.canvas && Object.keys(g.pending).length) return;
      if (g.canvas && g.canvas.parentNode) g.canvas.parentNode.removeChild(g.canvas);
      g.canvas = null;
      setPaneGraphicsMode(p, false);
      return;
    }
    setPaneGraphicsMode(p, true);
    var canvas = ensureGraphicsCanvas(p);
    if (!canvas) return;
    var screen = p.host.querySelector(".xterm-screen");
    var rect = screen && screen.getBoundingClientRect();
    if (!rect || !(rect.width > 0) || !(rect.height > 0)) return;
    var scale = window.devicePixelRatio || 1;
    var pixelW = Math.max(1, Math.round(rect.width * scale));
    var pixelH = Math.max(1, Math.round(rect.height * scale));
    if (canvas.width !== pixelW) canvas.width = pixelW;
    if (canvas.height !== pixelH) canvas.height = pixelH;
    canvas.style.width = rect.width + "px";
    canvas.style.height = rect.height + "px";
    var ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.clearRect(0, 0, rect.width, rect.height);
    if (!p.term.cols || !p.term.rows) return;

    var buffer = p.term.buffer.active;
    var viewportY = buffer.viewportY || 0;
    var cellW = rect.width / p.term.cols;
    var cellH = rect.height / p.term.rows;
    var visibleRows = viewerLogic.visibleHostRowRange(viewportY, g.rowOffset, p.term.rows);
    for (var row = visibleRows.first; row <= visibleRows.last; row += 1) {
      (g.cellsByRow[String(row)] || []).forEach(function (run) {
        var cached = g.images[String(run.imageId)];
        var image = cached && cached.image;
        if (!image || !image.complete || !image.naturalWidth || !image.naturalHeight) return;
        // Anchor host absolute rows to xterm's current baseY. If best-effort output was dropped,
        // the overlay remains aligned to the live screen instead of drifting by the missing rows.
        var visibleRow = viewerLogic.visibleImageRow(run.row, g.rowOffset, viewportY);
        if (visibleRow < 0 || visibleRow >= p.term.rows) return;
        var anchorCol = run.col - run.cellX;
        var availableCols = Math.max(1, p.term.cols - anchorCol);
        var effectiveCols = Math.min(Math.max(1, run.totalCellsX), availableCols);
        var effectiveRows = Math.max(1, Math.round(Math.max(1, run.totalCellsY) *
          effectiveCols / Math.max(1, run.totalCellsX)));
        if (run.cellY < 0 || run.cellY >= effectiveRows) return;
        var length = Math.min(
          run.length,
          p.term.cols - run.col,
          effectiveCols - run.cellX
        );
        if (length <= 0) return;
        var sourceCellX = run.cellX;
        var destCol = run.col;
        var sx1 = Math.floor(sourceCellX * image.naturalWidth / effectiveCols);
        var sx2 = Math.floor((sourceCellX + length) * image.naturalWidth / effectiveCols);
        var sy1 = Math.floor(run.cellY * image.naturalHeight / effectiveRows);
        var sy2 = Math.floor((run.cellY + 1) * image.naturalHeight / effectiveRows);
        ctx.drawImage(
          image, sx1, sy1, Math.max(1, sx2 - sx1), Math.max(1, sy2 - sy1),
          destCol * cellW, visibleRow * cellH, length * cellW, cellH
        );
      });
    }
  }

  // The status dot is shared by every pane, so its tooltip tracks the SET of degraded panes:
  // one pane recovering must not clear the warning while another is still out of sync.
  var degradedGraphicsPanes = {};
  function updateGraphicsStatusTitle() {
    var degraded = Object.keys(degradedGraphicsPanes).length;
    statusEl.title = degraded
      ? (degraded === 1 ? "Terminal graphics are temporarily out of sync in one pane; live output is unaffected."
                        : "Terminal graphics are temporarily out of sync in " + degraded +
                          " panes; live output is unaffected.")
      : "";
  }

  function requestGraphicsResync(paneId, g) {
    if (g.resyncPending) return;
    if (g.resyncAttempts >= MAX_GRAPHICS_RESYNCS) {
      if (!g.resyncDegraded) {
        g.resyncDegraded = true;
        degradedGraphicsPanes[paneId] = true;
        updateGraphicsStatusTitle();
      }
      return;
    }
    g.resyncPending = true;
    g.resyncAttempts += 1;
    sendMsg({ t: "graphicsResync", paneId: paneId });
    g.resyncTimer = setTimeout(function () {
      g.resyncTimer = null;
      g.resyncPending = false;
      requestGraphicsResync(paneId, g);
    }, GRAPHICS_RESYNC_TIMEOUT_MS * g.resyncAttempts);
  }

  function handleGraphicsResyncDenied(m) {
    var p = panes[m.paneId];
    if (!p) return;
    var g = p.graphics;
    if (g.resyncTimer) clearTimeout(g.resyncTimer);
    g.resyncTimer = null;
    // A host throttle is an acknowledgement, not a failed transport attempt. Give the attempt
    // back and keep one pending retry so repeated denials cannot force permanent degradation.
    g.resyncAttempts = viewerLogic.graphicsAttemptAfterDenied(
      g.resyncAttempts,
      g.resyncPending
    );
    g.resyncPending = true;
    var retryAfterMs = Number(m.retryAfterMs);
    if (!isFinite(retryAfterMs)) retryAfterMs = GRAPHICS_RESYNC_TIMEOUT_MS;
    retryAfterMs = Math.max(1, Math.min(60000, Math.floor(retryAfterMs)));
    g.resyncTimer = setTimeout(function () {
      g.resyncTimer = null;
      g.resyncPending = false;
      requestGraphicsResync(m.paneId, g);
    }, retryAfterMs);
  }

  function resetGraphicsResync(paneId, g) {
    if (g.resyncTimer) clearTimeout(g.resyncTimer);
    g.resyncTimer = null;
    g.resyncPending = false;
    g.resyncAttempts = 0;
    clearGraphicsDegraded(paneId, g);
  }

  function clearGraphicsDegraded(paneId, g) {
    if (!g.resyncDegraded) return;
    g.resyncDegraded = false;
    delete degradedGraphicsPanes[paneId];
    updateGraphicsStatusTitle();
  }

  function retryBudgetRejectedImages(skipPaneId, skipImageId) {
    Object.keys(panes).forEach(function (paneId) {
      var g = panes[paneId].graphics, cleared = false;
      Object.keys(g.rejected).forEach(function (id) {
        if (paneId === skipPaneId && id === skipImageId) return;
        var rejected = g.rejected[id];
        var current = g.images[id];
        var replacedBytes = current && current.hash === rejected.replacedHash
          ? current.bytes
          : 0;
        if (rejected.reason === "budget" &&
            graphicsMemoryFits(g, rejected.requiredBytes || 0, replacedBytes)) {
          delete g.rejected[id];
          cleared = true;
        }
      });
      if (cleared) requestGraphicsResync(paneId, g);
    });
  }

  function applyPaneGraphics(m) {
    // Layout + PaneSnapshot create every live pane before graphics arrive. Ignore a stale frame
    // instead of materializing an orphan xterm for an id that has already left the layout.
    var p = panes[m.paneId];
    if (!p) return;
    var g = p.graphics;
    if (m.resyncRequired) {
      requestGraphicsResync(m.paneId, g);
      return;
    }
    var required = {};
    (m.requiredImageIds || []).forEach(function (id) { required[String(id)] = true; });
    var revisionGap = !m.full && g.revision !== null && m.revision !== g.revision + 1;
    var freedBudget = false;

    if (m.full) {
      Object.keys(g.images).forEach(function (id) {
        if (!required[id]) {
          freedBudget = true;
          removeGraphicsImage(g, id);
        }
      });
      Object.keys(g.pending).forEach(function (id) {
        if (!required[id]) {
          freedBudget = true;
          removePendingGraphicsImage(g, id);
        }
      });
      Object.keys(g.rejected).forEach(function (id) {
        if (!required[id]) delete g.rejected[id];
      });
    }
    (m.removedImageIds || []).forEach(function (id) {
      id = String(id);
      if (g.images[id] || g.pending[id]) freedBudget = true;
      removeGraphicsImage(g, id);
      removePendingGraphicsImage(g, id);
      delete g.rejected[id];
    });
    (m.images || []).forEach(function (wire) {
      var id = String(wire.id), old = g.images[id], pending = g.pending[id];
      if (old && old.hash === wire.contentHash) return;
      if (pending && pending.hash === wire.contentHash) return;
      var rejected = g.rejected[id];
      if (rejected && rejected.reason === "decode" && rejected.hash === wire.contentHash) return;
      if (pending) removePendingGraphicsImage(g, id);
      delete g.rejected[id];
      var bytes = base64Bytes(wire.data);
      // The pending encoded source and decoded RGBA backing store both count against the bound.
      // Keep the old decoded raster live until the replacement has decoded successfully.
      if (!graphicsMemoryFits(g, bytes, old ? old.bytes : 0)) {
        g.rejected[id] = {
          reason: "budget",
          hash: wire.contentHash,
          requiredBytes: bytes,
          replacedHash: old ? old.hash : null
        };
        return;
      }
      var image = new Image();
      var item = { image: image, hash: wire.contentHash, bytes: bytes };
      g.pending[id] = item;
      g.bytes += bytes;
      viewerGraphicsBytes += bytes;
      image.onload = function () {
        if (g.pending[id] !== item) return;
        var decodedBytes = image.naturalWidth * image.naturalHeight * 4;
        if (!graphicsMemoryFits(g, decodedBytes, old ? old.bytes : 0)) {
          removePendingGraphicsImage(g, id);
          g.rejected[id] = {
            reason: "budget",
            hash: wire.contentHash,
            requiredBytes: bytes + decodedBytes,
            replacedHash: old ? old.hash : null
          };
          retryBudgetRejectedImages(m.paneId, id);
          scheduleGraphicsDraw(p);
          return;
        }
        if (g.images[id] === old) removeGraphicsImage(g, id);
        removePendingGraphicsImage(g, id);
        item.bytes = bytes + decodedBytes;
        g.images[id] = item;
        g.bytes += item.bytes;
        viewerGraphicsBytes += item.bytes;
        if (old && old.bytes > item.bytes) retryBudgetRejectedImages(m.paneId, id);
        scheduleGraphicsDraw(p);
      };
      image.onerror = function () {
        if (g.pending[id] === item) {
          removePendingGraphicsImage(g, id);
          // The same bytes will not become decodable after a full-state resend. Keep the rejected
          // hash until the host replaces or removes it, avoiding an infinite full-payload loop.
          g.rejected[id] = { reason: "decode", hash: wire.contentHash };
        }
        retryBudgetRejectedImages();
        scheduleGraphicsDraw(p);
      };
      var mimeType = ALLOWED_GRAPHICS_MIME_TYPES[wire.mimeType] ? wire.mimeType : "image/png";
      image.src = "data:" + mimeType + ";base64," + wire.data;
    });
    if (freedBudget) retryBudgetRejectedImages();
    g.cells = m.cells || [];
    g.cellsByRow = indexGraphicsCells(g.cells);
    if (typeof m.historyLines === "number" && m.historyLines >= 0) {
      g.historyLines = m.historyLines;
    }
    g.revision = m.revision;
    if (g.historyLines !== null) {
      var appliedGraphicsRevision = g.revision;
      g.rowOffset = viewerLogic.captureRowOffset(
        p.term.buffer.active.baseY,
        g.historyLines
      );
      // Capture once after all previously queued xterm writes have drained. Recomputing this from
      // live baseY during every draw would pin old images to the viewport as ordinary output scrolls.
      p.term.write("", function () {
        if (g.revision !== appliedGraphicsRevision) return;
        g.rowOffset = viewerLogic.captureRowOffset(
          p.term.buffer.active.baseY,
          g.historyLines
        );
        scheduleGraphicsDraw(p);
      });
    }
    var missing = Object.keys(required).some(function (id) {
      return !g.images[id] && !g.pending[id] && !g.rejected[id];
    });
    if (revisionGap || missing) requestGraphicsResync(m.paneId, g);
    else resetGraphicsResync(m.paneId, g);
    scheduleGraphicsDraw(p);
  }

  // ---- xterm pool ----
  function getPane(paneId) {
    var p = panes[paneId];
    if (p) return p;
    var host = document.createElement("div");
    host.className = "termhost";
    var opts = { cursorBlink: true, convertEol: false, scrollback: DEFAULT_WEB_VIEWER_SCROLLBACK_LINES,
                 allowTransparency: false,
                 fontFamily: DEFAULT_TERMINAL_FONT_FAMILY, fontSize: 13,
                 theme: { background: (theme && theme.background) || "#1e1e1e", foreground: "#f8f8f2" } };
    if (theme) applyThemeToOpts(opts);
    host.style.background = (theme && theme.background) || "#1e1e1e";
    var term = new Terminal(opts);
    term.open(host);
    // GPU rendering (WebGL addon) — the DOM renderer rebuilds row nodes on every scroll
    // step, which reads as jank next to the native client's Skia pipeline. Best-effort:
    // no WebGL2 (old devices) or a lost context falls back to the DOM renderer.
    try {
      if (window.WebglAddon && window.WebglAddon.WebglAddon) {
        var gl = new window.WebglAddon.WebglAddon();
        gl.onContextLoss(function () { try { gl.dispose(); } catch (e) {} });
        term.loadAddon(gl);
      }
    } catch (e) { /* DOM renderer fallback */ }
    // Clickable URLs: detect http(s) links and open them in a new tab on click/tap. The
    // link opens in the VIEWER's browser (client-side) — nothing is sent to the host.
    try {
      if (window.WebLinksAddon && window.WebLinksAddon.WebLinksAddon) {
        term.loadAddon(new window.WebLinksAddon.WebLinksAddon(function (event, uri) {
          window.open(uri, "_blank", "noopener,noreferrer");
        }));
      }
    } catch (e) { /* no link support */ }
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
    term.onData(function (data) { sendInput(paneId, data); });
    // Keep the cursor above the keyboard as it moves (e.g. a TUI dropping its prompt to the
    // bottom after the keyboard was raised). followCursor only ever pushes further up and only
    // when the cursor is hidden, so a thinking TUI with a visible cursor never rewrites the
    // transform — which is what dropped the keyboard before (that, plus the now-fixed renderStage
    // detach / autofit re-render). Coalesced to one check per frame.
    term.onCursorMove(function () {
      if (!keyboardOpen || paneId !== currentPaneId || followRaf) return;
      followRaf = requestAnimationFrame(function () { followRaf = 0; followCursor(); });
    });
    attachTouchScroll(host, term);
    p = { term: term, host: host, graphics: newGraphicsState(), graphicsTransparent: false };
    term.onRender(function () { scheduleGraphicsDraw(p); });
    term.onScroll(function () { scheduleGraphicsDraw(p); });
    term.onResize(function () { scheduleGraphicsDraw(p); });
    panes[paneId] = p;
    return p;
  }

  // ---- touch scrolling (phones) ----
  // A vertical swipe synthesizes WHEEL events at the touch point, so xterm's own wheel
  // pipeline decides what scrolling means — exactly like a desktop mouse wheel:
  //  · normal buffer → scrollback scrolls,
  //  · TUI app with mouse reporting (claude, vim, htop) → wheel escape codes to the app,
  //  · alternateScroll mode (1007) → arrow keys.
  // (term.scrollLines() alone only covered the first case — TUIs never scrolled.)
  // Horizontal swipes are left to native pan (wide grids when zoomed past fit).
  function attachTouchScroll(host, term) {
    var startX = 0, startY = 0, lastX = 0, lastY = 0, lastT = 0, axis = null, vel = 0, momentum = null;
    function stopMomentum() { if (momentum) { cancelAnimationFrame(momentum); momentum = null; } }
    function dispatchWheel(dy) {
      // Target the screen element (what a real wheel event hits) so coordinates map to
      // the right cell for mouse reporting; bubbles to wherever xterm's listener sits.
      var target = host.querySelector(".xterm-screen") || host;
      try {
        target.dispatchEvent(new WheelEvent("wheel", {
          deltaY: dy, deltaMode: 0, clientX: lastX, clientY: lastY,
          bubbles: true, cancelable: true,
        }));
      } catch (e) {
        // Ancient browser without the WheelEvent constructor: scroll the buffer directly.
        try { term.scrollLines(dy > 0 ? 1 : -1); } catch (e2) {}
      }
    }
    // Capture phase: see the touches even if something inside xterm stops propagation.
    host.addEventListener("touchstart", function (e) {
      if (!isPhone() || e.touches.length !== 1) { axis = "skip"; return; }
      // Catching a fling is part of the scroll, not a press — keep menus suppressed.
      if (momentum) markTouchScroll();
      stopMomentum();
      axis = null; vel = 0;
      startX = lastX = e.touches[0].clientX; startY = lastY = e.touches[0].clientY;
      lastT = e.timeStamp;
    }, { passive: true, capture: true });
    host.addEventListener("touchmove", function (e) {
      if (touchSelecting) return;             // a selection drag owns the gesture
      if (axis === "skip" || e.touches.length !== 1) return;
      var x = e.touches[0].clientX, y = e.touches[0].clientY;
      if (axis === null) {
        var ax = Math.abs(x - startX), ay = Math.abs(y - startY);
        if (ax < 6 && ay < 6) return;          // not decided yet
        axis = ay >= ax ? "y" : "skip";        // horizontal → native pan
        markTouchScroll();                      // the gesture is a scroll/pan, not a press
        if (axis === "y") { lastX = x; lastY = y; }
      }
      if (axis !== "y") return;
      if (e.cancelable) e.preventDefault();     // we own the vertical gesture
      var dy = lastY - y;                       // finger up = scroll toward bottom
      var dt = Math.max(1, e.timeStamp - lastT);
      vel = 0.8 * vel + 0.2 * (dy / dt);        // smoothed px/ms for the flick
      lastX = x; lastY = y; lastT = e.timeStamp;
      dispatchWheel(dy);
    }, { passive: false, capture: true });
    host.addEventListener("touchend", function (e) {
      unmarkTouchScrollSoon();
      if (axis !== "y") { axis = null; return; }
      axis = null;
      var v = vel; vel = 0;
      if (Math.abs(v) < 0.25) return;           // no flick — just a drag
      var prev = e.timeStamp || performance.now();
      function step(now) {
        var dt = Math.min(48, now - prev); prev = now;
        dispatchWheel(v * dt);
        v *= Math.pow(0.95, dt / 16);           // frame-rate-independent decay
        momentum = Math.abs(v) >= 0.03 ? requestAnimationFrame(step) : null;
      }
      momentum = requestAnimationFrame(step);
    }, { passive: true, capture: true });
    host.addEventListener("touchcancel", function () { axis = null; vel = 0; unmarkTouchScrollSoon(); }, { passive: true, capture: true });
  }

  var themeColorProbe = document.createElement("span");

  function safeCssColor(value, fallback) {
    if (typeof value !== "string") return fallback;
    themeColorProbe.style.color = "";
    themeColorProbe.style.color = value;
    return themeColorProbe.style.color ? value : fallback;
  }

  function validatedTheme(m) {
    m = m || {};
    var next = {
      background: safeCssColor(m.background, "#1e1e1e"),
      foreground: safeCssColor(m.foreground, "#f8f8f2"),
      cursor: safeCssColor(m.cursor, "#f8f8f2"),
      cursorAccent: safeCssColor(m.cursorAccent, "#1e1e1e"),
      selectionBackground: safeCssColor(m.selectionBackground, "rgba(255,255,255,0.25)"),
      ansi: [],
      fontFamily: typeof m.fontFamily === "string" ? m.fontFamily : DEFAULT_TERMINAL_FONT_FAMILY,
      fontSize: isFinite(Number(m.fontSize))
        ? Math.max(8, Math.min(72, Math.floor(Number(m.fontSize))))
        : 13
    };
    var ansi = Array.isArray(m.ansi) ? m.ansi : [];
    for (var i = 0; i < 16; i += 1) next.ansi[i] = safeCssColor(ansi[i], undefined);
    return next;
  }

  function applyThemeToOpts(opts) {
    var a = theme.ansi || [];
    opts.theme = {
      background: theme.background || "#1e1e1e", foreground: theme.foreground, cursor: theme.cursor,
      cursorAccent: theme.cursorAccent, selectionBackground: theme.selectionBackground,
      black: a[0], red: a[1], green: a[2], yellow: a[3], blue: a[4], magenta: a[5], cyan: a[6], white: a[7],
      brightBlack: a[8], brightRed: a[9], brightGreen: a[10], brightYellow: a[11],
      brightBlue: a[12], brightMagenta: a[13], brightCyan: a[14], brightWhite: a[15],
    };
    if (theme.fontFamily) opts.fontFamily = theme.fontFamily;
    if (theme.fontSize) opts.fontSize = theme.fontSize;
  }

  function applyTheme(m) {
    theme = validatedTheme(m);
    Object.keys(panes).forEach(function (id) {
      var o = {}; applyThemeToOpts(o);
      var t = panes[id].term;
      if (panes[id].graphicsTransparent) o.theme.background = "rgba(0,0,0,0)";
      t.options.theme = o.theme;
      panes[id].host.style.background = theme.background;
      if (o.fontFamily) t.options.fontFamily = o.fontFamily;
      if (o.fontSize) t.options.fontSize = o.fontSize;
    });
    document.documentElement.style.background = theme.background;
    document.body.style.background = theme.background;
    stageEl.style.background = theme.background;
    // The host theme may carry a fontSize; keep the viewer's chosen zoom if set.
    if (viewerFont) Object.keys(panes).forEach(function (id) { try { panes[id].term.options.fontSize = viewerFont; } catch (e) {} });
    Object.keys(panes).forEach(function (id) { scheduleGraphicsDraw(panes[id]); });
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
        if ((!summaryMode || splitsAsTabs) && tab.tree && tab.tree.t === "split") {
          ps.forEach(function (pane) { group.appendChild(leftChip(tab, pane)); });
        } else {
          group.appendChild(leftChip(tab, null));
        }
        return group;
      }
      // Render one set of tabs into [container], partitioned like the native client: the
      // host's own tabs as direct chips + an action row; tabs the host itself mirrors from
      // OTHER sessions as boxed "via host" groups below. [windowBox] = the set is one window
      // of an all-windows share, so split/new-tab must target THAT window (by tabId — the
      // host routes actions to the named tab's owning window).
      function renderTabSet(container, tabs, windowBox) {
        var own = [], upstreams = {}, upOrder = [];
        tabs.forEach(function (t) {
          if (t.origin) {
            if (!upstreams[t.origin]) {
              upstreams[t.origin] = {
                name: t.originName, readOnly: !!t.originReadOnly, offline: !!t.originOffline, tabs: [],
                // The origin's MCP, forwarded by the host (null = never reported → no pill).
                mcp: (typeof t.originMcpRunning === "boolean")
                  ? { enabled: !!t.originMcpEnabled, running: !!t.originMcpRunning, attached: t.originMcpAttached || [] }
                  : null,
              };
              upOrder.push(t.origin);
            }
            upstreams[t.origin].tabs.push(t);
          } else own.push(t);
        });
        own.forEach(function (tab) { container.appendChild(tabCluster(tab)); });
        // Action row mirroring the host's left bar: Split L/R, Split T/B, then New tab.
        // Always shown — when view-only, clicking offers to request control (viewOnlyGate).
        // A window box whose tabs are ALL upstream mirrors gets none (a bare "new tab in
        // this window" can't be expressed — the mirrors' ids route upstream instead).
        if (!windowBox || own.length) {
          var actions = document.createElement("div");
          actions.className = "ltab-actions";
          if (windowBox) {
            var wg = { tabs: own };
            actions.appendChild(groupSplitButton("v", wg));
            actions.appendChild(groupSplitButton("h", wg));
            var wnt = document.createElement("div");
            wnt.className = "newtab"; wnt.textContent = "+ New tab";
            wnt.title = "New tab in this window";
            wnt.onclick = function () {
              if (viewOnlyGate()) return;
              sendMsg({ t: "newTab", tabId: anchorTab(wg).id });
            };
            actions.appendChild(wnt);
          } else {
            actions.appendChild(splitButton("v"));
            actions.appendChild(splitButton("h"));
            actions.appendChild(newTabButton("+ New tab"));
          }
          container.appendChild(actions);
        }
        // Upstream groups: tether + bordered box with header (name · via host, offline/read-only
        // badges, ✕ = ask host to disconnect it), chips, and a relayed action row.
        upOrder.forEach(function (key) {
          var g = upstreams[key];
          var tether = document.createElement("div");
          tether.style.cssText = "width:2px;height:10px;margin-left:13px;background:#4FC3F7;";
          container.appendChild(tether);
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
          if (g.mcp) {
            // This origin's MCP pill — dot (green = running) + "MCP"; click opens the
            // toggle/attach menu relayed through the host to the origin. Read-only via the
            // host → request control first.
            var mp = document.createElement("span");
            mp.style.cssText = "cursor:pointer;display:inline-flex;align-items:center;gap:4px;font-size:10px;color:#b0b0b0;";
            var mpdot = document.createElement("span");
            mpdot.style.cssText = "width:6px;height:6px;border-radius:50%;background:" + (g.mcp.running ? "#4caf50" : "#6b6b6b") + ";";
            mp.appendChild(mpdot);
            mp.appendChild(document.createTextNode("MCP"));
            mp.onclick = function (ev) {
              ev.stopPropagation();
              if (viewOnlyGate()) return;
              if (g.readOnly) { requestUpstreamControl(anchorTab(g).id, g.name || "remote"); return; }
              showUpstreamMcpMenu(ev.clientX, ev.clientY, g);
            };
            hd.appendChild(mp);
          }
          if (g.readOnly) {
            var eye = document.createElement("span");
            eye.textContent = "👁"; eye.title = "Read-only via this host — click to request control";
            eye.style.cursor = "pointer";
            eye.onclick = function (ev) { ev.stopPropagation(); requestUpstreamControl(anchorTab(g).id, g.name || "remote"); };
            hd.appendChild(eye);
          }
          var x = document.createElement("span");
          x.textContent = "×"; x.title = "Ask the host to disconnect this upstream";
          x.style.cssText = "cursor:pointer;color:#808080;padding:0 2px;";
          x.onclick = function (ev) {
            ev.stopPropagation();
            if (viewOnlyGate()) return;
            if (window.confirm("Ask the host to disconnect from " + (g.name || "this upstream") + "?"))
              sendMsg({ t: "disconnectUpstream", tabId: g.tabs[0].id });
          };
          hd.appendChild(x);
          box.appendChild(hd);
          // Relayed action row (split/new-tab targeting [sg]'s tabs). Always shown;
          // view-only clicks route to the request-control dialog (viewOnlyGate).
          function upstreamActions(sg) {
            var act = document.createElement("div");
            act.className = "ltab-actions";
            act.appendChild(groupSplitButton("v", sg));
            act.appendChild(groupSplitButton("h", sg));
            var nt = document.createElement("div");
            nt.className = "newtab"; nt.textContent = "+ New tab";
            nt.title = "New tab in " + (g.name || "remote");
            nt.onclick = function () {
              if (viewOnlyGate()) return;
              if (g.readOnly) { requestUpstreamControl(anchorTab(sg).id, g.name || "remote"); return; }
              sendMsg({ t: "newTab", tabId: anchorTab(sg).id });
            };
            act.appendChild(nt);
            return act;
          }
          // The upstream itself may share ALL its windows — those tabs carry the ORIGIN's
          // window identity; section them (dim sub-title + per-window actions), like the
          // native client. Unstamped tabs render flat with one box-level action row.
          var wsecs = {}, wsecOrder = [], wflat = [];
          g.tabs.forEach(function (tab) {
            if (tab.originWindowId) {
              if (!wsecs[tab.originWindowId]) {
                wsecs[tab.originWindowId] = { name: tab.originWindowName, tabs: [] };
                wsecOrder.push(tab.originWindowId);
              }
              wsecs[tab.originWindowId].tabs.push(tab);
            } else wflat.push(tab);
          });
          wflat.forEach(function (tab) { box.appendChild(tabCluster(tab)); });
          if (wflat.length || !wsecOrder.length) box.appendChild(upstreamActions({ tabs: wflat.length ? wflat : g.tabs, readOnly: g.readOnly, name: g.name }));
          wsecOrder.forEach(function (sk) {
            var sec = wsecs[sk];
            var sh = document.createElement("div");
            sh.style.cssText = "display:flex;align-items:center;gap:4px;padding:2px 2px 0;font-size:10px;color:#8a8a8a;";
            var sl = document.createElement("span");
            sl.style.cssText = "overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
            sl.textContent = sec.name || "Window";
            sh.appendChild(sl);
            var hr = document.createElement("span");
            hr.style.cssText = "flex:1;height:1px;background:#3a3a3a;";
            sh.appendChild(hr);
            box.appendChild(sh);
            sec.tabs.forEach(function (tab) { box.appendChild(tabCluster(tab)); });
            box.appendChild(upstreamActions({ tabs: sec.tabs, readOnly: g.readOnly, name: g.name }));
          });
          container.appendChild(box);
        });
      }
      // An all-windows share stamps each tab with its owning window — group those into
      // neutral-bordered boxes (one per window); unstamped tabs render flat as before.
      var flat = [], wins = {}, winOrder = [];
      if (layout) layout.tabs.forEach(function (t) {
        if (t.windowId) {
          if (!wins[t.windowId]) { wins[t.windowId] = { name: t.windowName, tabs: [] }; winOrder.push(t.windowId); }
          wins[t.windowId].tabs.push(t);
        } else flat.push(t);
      });
      if (flat.length || !winOrder.length) renderTabSet(sidebarEl, flat, false);
      winOrder.forEach(function (wk) {
        var w = wins[wk];
        var wbox = document.createElement("div");
        wbox.style.cssText = "border:1px solid #555;border-radius:8px;padding:4px;display:flex;flex-direction:column;gap:4px;margin-top:6px;";
        var whd = document.createElement("div");
        whd.style.cssText = "display:flex;align-items:center;gap:4px;padding:2px;font-size:11px;color:#b0b0b0;";
        var wlbl = document.createElement("span");
        wlbl.style.cssText = "flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
        wlbl.textContent = "🗔 " + (w.name || "Window");
        whd.appendChild(wlbl);
        var wx = document.createElement("span");
        wx.textContent = "×"; wx.title = "Close this window on the host";
        wx.style.cssText = "cursor:pointer;color:#808080;padding:0 2px;";
        wx.onclick = function (ev) {
          ev.stopPropagation();
          if (viewOnlyGate()) return;
          if (window.confirm("Close " + (w.name || "this window") + " on the host? All its tabs will close."))
            sendMsg({ t: "closeWindow", windowId: wk });
        };
        whd.appendChild(wx);
        wbox.appendChild(whd);
        renderTabSet(wbox, w.tabs, true);
        sidebarEl.appendChild(wbox);
      });
      // Bottom: ask the host to mirror another BossTerm share here (native "Add remote").
      var add = document.createElement("div");
      add.className = "newtab";
      add.textContent = "☁ Add remote";
      add.title = "Ask the host to mirror another BossTerm share link";
      add.onclick = function () {
        if (viewOnlyGate()) return;
        var link = window.prompt("Paste a BossTerm share link — the host will mirror its tabs:");
        if (link && link.trim()) sendMsg({ t: "offerShare", link: link.trim() });
      };
      sidebarEl.appendChild(add);
    } else {
      tabbarEl.classList.remove("hidden");
      menubtnEl.classList.remove("show");
      sidebarEl.classList.remove("show", "open");
      tabbarEl.innerHTML = "";
      if (layout) layout.tabs.forEach(function (tab) {
        var grp = document.createElement("div"); grp.className = "tab-group";
        var ps = []; panesInOrder(tab.tree, ps);
        if ((!summaryMode || splitsAsTabs) && tab.tree && tab.tree.t === "split") {
          ps.forEach(function (pane) { grp.appendChild(topChip(tab, pane)); });
        } else {
          grp.appendChild(topChip(tab, null));
        }
        tabbarEl.appendChild(grp);
      });
      // Split buttons sit just left of the new-tab (+), like the host's tab-bar actions.
      // Always shown — when view-only, clicking offers to request control (viewOnlyGate).
      tabbarEl.appendChild(splitButton("v"));
      tabbarEl.appendChild(splitButton("h"));
      tabbarEl.appendChild(newTabButton("+"));
    }
    updateViewPill(); // runs on layout/control/selection changes — keeps the pill current
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
    // Mid voice call: tell the host which tab we're looking at now — it's the agent's
    // default tool target (old hosts ignore focus, so this is safe to send).
    if (voice.state !== "idle") sendMsg({ t: "focus", tabId: tabId, paneId: paneId || tabId });
    sidebarEl.classList.remove("open"); // close the phone drawer after picking
    renderTabBar();
    renderStage();
  }
  // Client-side pane focus: move the focus border to [paneId] and reflect it in the sub-tab
  // chips, without rebuilding the stage (so xterms/selection survive). Independent of the host.
  function setClientFocus(paneId) {
    if (currentPaneId === paneId) return;
    currentPaneId = paneId;
    if (splitsAsTabs) { renderTabBar(); renderStage(); return; } // shown pane follows focus
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
  // View-only fallback for action buttons (the native client's confirm dialog): when we lack
  // control, offer to request it instead of silently doing nothing. True = handled, caller bails.
  function viewOnlyGate() {
    if (controlGranted) return false;
    if (window.confirm("You're viewing this session read-only — this action needs control. Ask the host for it?"))
      sendMsg({ t: "requestControl" });
    return true;
  }

  // Typing prompt throttle: buffered keystrokes must not spam confirms — one prompt, then
  // quiet for 30s (a grant clears the read-only state and re-enables input anyway).
  var inputPromptQuietUntil = 0;
  function promptControlForInput(tabId, name) {
    var now = Date.now();
    if (now < inputPromptQuietUntil) return;
    inputPromptQuietUntil = now + 30000;
    if (tabId) { requestUpstreamControl(tabId, name); return; }
    if (window.confirm("You're viewing this session read-only — typing needs control. Ask the host for it?"))
      sendMsg({ t: "requestControl" });
  }
  function tabOfPane(paneId) {
    if (!layout) return null;
    for (var i = 0; i < layout.tabs.length; i++) {
      var ids = {}; collectPaneIds(layout.tabs[i].tree, ids);
      if (ids[paneId]) return layout.tabs[i];
    }
    return null;
  }
  // Central input path: typing into a read-only context (no control, or the pane's tab is
  // read-only via an upstream host) prompts to request control instead of vanishing.
  function sendInput(paneId, data) {
    if (!controlGranted) { promptControlForInput(null, null); return; }
    var t = tabOfPane(paneId);
    if (t && t.origin && t.originReadOnly) { promptControlForInput(t.id, t.originName || "remote"); return; }
    sendMsg({ t: "input", paneId: paneId, data: data });
  }

  // kind: "v" = Split Left/Right (vertical divider), "h" = Split Top/Bottom (horizontal divider).
  function splitButton(kind) {
    var b = document.createElement("div");
    b.className = "splitbtn";
    b.title = kind === "v" ? "Split vertical (left / right)" : "Split horizontal (top / bottom)";
    b.innerHTML = kind === "v" ? SVG_VSPLIT : SVG_HSPLIT;
    b.onclick = function (ev) {
      ev.stopPropagation();
      if (viewOnlyGate()) return;
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
      if (viewOnlyGate()) return;
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
    el.onclick = function () {
      if (viewOnlyGate()) return;
      sendMsg({ t: "newTab" });
    };
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
      // Desktop right-click → context menu. (Android also synthesizes contextmenu on
      // long-press — ignore it while a touch scroll owns the gesture.)
      wrap.addEventListener("contextmenu", function (e) {
        e.preventDefault();
        if (touchScrollActive) return;
        setClientFocus(pid); showContextMenu(e.clientX, e.clientY, pid);
      });
      // Mobile long-press (500ms) ARMS a text selection (iOS-style): it word-selects under
      // the finger, then a drag extends the selection and lifting opens the context menu
      // (Copy now has something to copy). A long-press that doesn't move still opens the
      // menu on lift. Pre-fire movement (>6px) means it was a scroll → no selection, no menu.
      var lpTimer = null, lpX = 0, lpY = 0, lpMoved = 0;
      function screenEl() { return getPane(pid).host.querySelector(".xterm-screen"); }
      function endSelectionGesture(showMenu, mx, my) {
        if (!touchSelecting) return;
        dispatchMouse("mouseup", null, mx, my, 1, 0); // up goes to document; no element needed
        if (showMenu) showContextMenu(mx, my, pid);
        // Clear after a tick so the synthesized click that follows touchend can't re-fire
        // scrolling or dismiss the just-opened menu.
        setTimeout(function () { touchSelecting = false; }, 0);
      }
      wrap.addEventListener("touchstart", function (e) {
        if (!e.touches || e.touches.length !== 1) {
          if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
          endSelectionGesture(false, lpX, lpY); // a 2nd finger (e.g. pinch) cancels a selection
          return;
        }
        lpX = e.touches[0].clientX; lpY = e.touches[0].clientY; lpMoved = 0;
        setClientFocus(pid);
        lpTimer = setTimeout(function () {
          lpTimer = null;
          if (touchScrollActive || lpMoved >= 6) return; // scrolling, not pressing
          // Arm selection: a double-click-flavored mousedown word-selects at the point;
          // subsequent moves extend it. xterm renders the highlight (DOM or WebGL).
          var sc = screenEl(); if (!sc) return;
          touchSelecting = true;
          dispatchMouse("mousedown", sc, lpX, lpY, 2, 1);
        }, 500);
      }, { passive: true });
      wrap.addEventListener("touchmove", function (e) {
        var t = e.touches && e.touches[0];
        if (touchSelecting) {
          if (e.cancelable) e.preventDefault();   // own the gesture; don't scroll
          if (t) dispatchMouse("mousemove", null, t.clientX, t.clientY, 1, 1);
          return;
        }
        if (t) {
          var dx = Math.abs(t.clientX - lpX), dy = Math.abs(t.clientY - lpY);
          lpMoved = Math.max(lpMoved, dx, dy);
          if (lpTimer && dx < 6 && dy < 6) return; // jitter (6px = the scroll axis-lock slop)
        }
        if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
      }, { passive: false });
      wrap.addEventListener("touchend", function (e) {
        if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
        if (touchSelecting) {
          var t = (e.changedTouches && e.changedTouches[0]) || null;
          endSelectionGesture(true, t ? t.clientX : lpX, t ? t.clientY : lpY);
        }
      });
      wrap.addEventListener("touchcancel", function () {
        if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
        endSelectionGesture(false, lpX, lpY);
      });
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

  // Signature of what renderStage would draw: the active tab, the shown-pane mode, and the
  // pane tree's STRUCTURE (pane ids + split nesting) only. Volatile per-tab fields (title,
  // cursor, mcp flags…) must be excluded — the host re-broadcasts layout ~1/s during streaming
  // with those fields changing, and a full-tree JSON compare would rebuild every time, detaching
  // the focused textarea and dropping the soft keyboard (no blur on iOS). See onLayout.
  var lastStageSig = "";
  function treeSkeleton(n) {
    if (!n) return "";
    // Pane: id only — title/cwd/focused/color change during streaming and must NOT count.
    if (n.t === "pane") return "p" + n.paneId;
    // Split: dir + ratio (change only on a real resize, so safe to compare and keeps the
    // divider in sync) + recursive children.
    return "s" + n.dir + (n.ratio != null ? Number(n.ratio).toFixed(3) : "") +
      "[" + treeSkeleton(n.a) + "," + treeSkeleton(n.b) + "]";
  }
  function stageSignature() {
    if (!layout) return "";
    var tab = null;
    for (var i = 0; i < layout.tabs.length; i++) if (layout.tabs[i].id === activeTabId) tab = layout.tabs[i];
    if (!tab) tab = layout.tabs[0];
    if (!tab) return "";
    return activeTabId + "|" + (splitsAsTabs ? "S" : "F") + "|" + currentPaneId + "|" + treeSkeleton(tab.tree);
  }
  function renderStage() {
    stageEl.innerHTML = "";
    if (!layout) return;
    var tab = layout.tabs.filter(function (t) { return t.id === activeTabId; })[0] || layout.tabs[0];
    if (!tab) return;
    // Keep the key-bar target on a pane that's actually visible in this tab.
    var ids = {}; collectPaneIds(tab.tree, ids);
    if (!currentPaneId || !ids[currentPaneId]) currentPaneId = defaultPaneId(tab.tree);
    // "Splits as tabs": show only the selected pane of a split, full-screen — the sub-tab
    // chips switch between panes (each pane keeps its own xterm; nothing is lost).
    var tree = tab.tree;
    if (splitsAsTabs && tree && tree.t === "split") {
      tree = findPaneNode(tree, currentPaneId) || tree;
    }
    var root = buildNode(tree);
    if (tree.t === "pane") {
      // Single pane → natural width so a wide grid pans left/right inside #stage
      // (horizontal swipes stay native on phones — the axis-lock skips them).
      var sp = panes[tree.paneId];
      root.style.flex = "0 0 auto";
      root.style.height = "100%";
      if (isPhone()) {
        // Phone: clip vertical spill — vertical touch is owned by the wheel-synthesis
        // scroll (scrollback / TUI wheel reports), never by #stage panning the page.
        root.style.overflow = "hidden";
        if (sp) { sp.host.style.overflow = "hidden"; sp.host.style.height = "100%"; }
      } else {
        root.style.overflow = "visible";
        if (sp) { sp.host.style.overflow = "visible"; sp.host.style.height = "100%"; }
      }
    } else {
      root.style.flex = "1 1 0"; // splits fill the stage
    }
    stageEl.appendChild(root);
    relayoutSinglePane(); // size a single pane to its natural width for horizontal scroll
    updateDims();
    lastStageSig = stageSignature();
  }

  function onLayout(m) {
    markConnectionHealthy(ws);
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
      if (!live[id]) {
        disposeGraphics(id, panes[id]);
        try { panes[id].term.dispose(); } catch (e) {}
        delete panes[id];
      }
    });
    renderTabBar();
    // Mid divider-drag, the host echoes ratio changes back as layouts — don't rebuild the
    // stage (it would destroy the divider under the pointer); onUp re-renders to settle.
    // Also skip the rebuild when the rendered structure is unchanged: the host re-broadcasts
    // layout during streaming, and renderStage's innerHTML="" detaches the focused textarea,
    // which SILENTLY drops the soft keyboard on iOS (removing a focused node fires no blur).
    if (!splitDragging) {
      var sig = stageSignature();
      if (sig !== lastStageSig) renderStage();
    }
    autoFitPending = true;
    maybeAutoFit();
  }

  function collectPaneIds(node, out) {
    if (node.t === "pane") out[node.paneId] = true;
    else { collectPaneIds(node.a, out); collectPaneIds(node.b, out); }
  }

  // ---- websocket ----
  var wsProto = location.protocol === "https:" ? "wss" : "ws";
  function connectWebSocket() {
    // Every reconnect gets fresh ordered queues and E2E keys/salts. Pending work from the old
    // socket captures that socket/state below and is ignored once [ws] points elsewhere.
    crypState = { ready: false, kc2s: null, ks2c: null };
    sendChain = Promise.resolve();
    recvChain = Promise.resolve();
    saltC = null;
    var socket;
    try {
      socket = new WebSocket(wsProto + "://" + location.host + "/ws/" + encodeURIComponent(token));
    } catch (e) {
      ws = null;
      handleConnectionLost(null);
      return;
    }
    ws = socket;
    var state = crypState;
    var sentHello = false;
    if (canE2E) {
      socket.binaryType = "arraybuffer";
      secretBytes = b64urlToBytes(secretB64);
      saltC = randBytes(16);
    }
    var connectionSalt = saltC;
    function sendHello() {
      if (ws !== socket || sentHello) return;
      sentHello = true;
      sendMsg({
        t: "hello",
        name: deviceName(),
        clientId: clientId,
        key: loadKey(),
        capabilities: ["paneGraphicsV1"]
      });
    }

    socket.onopen = function () {
      if (ws !== socket) return;
      if (e2eMissing) { onCryptoFailure(socket); return; } // truncated link — don't downgrade to plaintext
      setStatus("live");
      // E2E: open with a plaintext Kex (our salt); the Hello waits until keys are derived from
      // the host's reply. Plaintext: send the Hello immediately, as before.
      if (canE2E) socket.send(JSON.stringify({ t: "kex", v: 1, salt: bytesToB64url(connectionSalt) }));
      else sendHello();
    };

    socket.onmessage = function (ev) {
      if (ws !== socket) return;
      // E2E handshake: the host's reply is a plaintext Kex (a string frame). Derive keys, verify
      // its confirmation tag (wrong/missing #k ⇒ fail loudly), then send the encrypted Hello.
      if (canE2E && !state.ready) {
        if (typeof ev.data !== "string") return;
        var k; try { k = JSON.parse(ev.data); } catch (e) { return; }
        if (k.salt == null) return;
        if (k.v && k.v !== 1) { // a newer host we can't speak to
          sessionEnded = true;
          showOverlay("Update BossTerm", "This session uses a newer encryption version than this viewer.", false);
          try { socket.close(); } catch (e) {}
          return;
        }
        deriveSessionKeys(secretBytes, connectionSalt, b64urlToBytes(k.salt)).then(function (keys) {
          if (ws !== socket) return;
          if (!constantTimeEq(keys.confirmB64, k.confirm)) { onCryptoFailure(socket); return; }
          state.kc2s = keys.kc2s; state.ks2c = keys.ks2c; state.ready = true;
          showE2EBadge();
          sendHello();
        }).catch(function () { onCryptoFailure(socket); });
        return;
      }
      // E2E steady state: decrypt (ORDERED, so PaneOutput applies in order) then dispatch.
      if (canE2E) {
        recvChain = recvChain.then(function () { return decryptFrame(ev.data, state); })
          .then(function (text) {
            if (ws !== socket) return;
            var m; try { m = JSON.parse(text); } catch (e) { return; }
            dispatch(m);
          })
          // A decrypt failure ends the session (onCryptoFailure closes the socket, so no further
          // frames arrive); don't rethrow — that would leave a dangling unhandled rejection.
          .catch(function () { onCryptoFailure(socket); });
        return;
      }
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      dispatch(m);
    };

    // WebSocket failures emit error then close. Only close consumes a retry so the pair cannot
    // double-count one failure; error updates the status immediately while close schedules it.
    socket.onerror = function () { if (ws === socket) setStatus("down"); };
    socket.onclose = function (ev) {
      if (ws !== socket) return;
      // Any real drop kills the tool bridge, so end the call — even when an automatic reconnect
      // follows: the agent would otherwise sit blind waiting for a tool result sendMsg dropped.
      voiceOnSocketDown();
      if (viewerLogic.isTerminalWebSocketClose(ev && ev.code)) {
        disarmConnectionHealth();
        ws = null;
        sessionEnded = true;
        setStatus("down");
        showOverlay(
          "Connection ended",
          (ev && ev.reason) || "The shared session is unavailable or no longer accepts this link.",
          false
        );
        return;
      }
      handleConnectionLost(socket);
    };
  }

  function dispatch(m) {
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
        if (typeof m.scrollbackLines === "number" && m.scrollbackLines >= 0) {
          p.term.options.scrollback = Math.min(
            MAX_WEB_VIEWER_SCROLLBACK_LINES,
            Math.floor(m.scrollbackLines)
          );
        }
        if (m.cols && m.rows) p.term.resize(m.cols, m.rows);
        p.term.reset();
        if (m.data) p.term.write(m.data, function () { scheduleGraphicsDraw(p); });
        else scheduleGraphicsDraw(p);
        relayoutSinglePane();
        updateDims();
        autoFitPending = true;
        maybeAutoFit();
        break;
      }
      case "paneOutput":
        if (m.data) {
          var outputPane = getPane(m.paneId);
          outputPane.term.write(m.data, function () {
            scheduleGraphicsDraw(outputPane);
          });
        }
        break;
      case "paneRepaint":
        if (m.data) {
          var repaintPane = getPane(m.paneId);
          viewerLogic.queuePaneRepaint(
            function (data, callback) { repaintPane.term.write(data, callback); },
            function () { return repaintPane.term.buffer.active; },
            function (line) { repaintPane.term.scrollToLine(line); },
            m.data,
            function () { scheduleGraphicsDraw(repaintPane); }
          );
        }
        break;
      case "paneGraphics": applyPaneGraphics(m); break;
      case "graphicsResyncDenied": handleGraphicsResyncDenied(m); break;
      case "paneResize":
        if (m.cols && m.rows) {
          var resizedPane = getPane(m.paneId);
          resizedPane.term.resize(m.cols, m.rows);
          scheduleGraphicsDraw(resizedPane);
          relayoutSinglePane(); updateDims();
          autoFitPending = true;
          maybeAutoFit();
        }
        break;
      case "presence":
        presenceEl.textContent = m.viewers === 1 ? "1 viewer" : m.viewers + " viewers"; break;
      case "mcpStatus":
        mcp = m; updateMcpPill(); break;
      case "voiceStatus":
        voice.status = m;
        // The host's master switch is a kill switch: don't just hide the bar under a live call.
        if (!m.available && voice.state !== "idle") {
          endCall(true);
          toast("Boss Calling was turned off on the host — call ended.");
        }
        updateVoiceBar();
        break;
      case "voiceSession":
        connectRealtime(m); break;
      case "voiceError":
        toast(voiceErrorText(m));
        // A refusal for a DUPLICATE start (rate limit) must not tear down the call that is already
        // running — only errors that mean "this call can't happen" end it.
        if (voice.state !== "idle" && !(m.code === "rate_limited" && voice.dc)) endCall(false);
        break;
      case "voiceToolResult":
        // Claim FIRST, then send. Only a call still pending may be answered: voiceToolTimedOut may
        // already have supplied an output for this id, and a second function_call_output for one
        // call_id is a protocol error — which also lands with no turn requested for it.
        if (voice.pending[m.callId]) {
          delete voice.pending[m.callId];
          if (voice.timers[m.callId]) { clearTimeout(voice.timers[m.callId]); delete voice.timers[m.callId]; }
          voice.pendingCount = Math.max(0, voice.pendingCount - 1);
          voice.outputsOwed += 1;
          voiceDcSend({ type: "conversation.item.create",
            item: { type: "function_call_output", call_id: m.callId, output: m.resultJson } });
        }
        updateVoiceBar();
        voiceMaybeRequestResponse();
        if (m.isError) toast("Tool failed — the agent will explain.");
        break;
      case "control":
        controlGranted = !!m.granted;
        viewOnlyEl.style.display = controlGranted ? "none" : "";
        fithostEl.style.display = controlGranted ? "" : "none"; // resizing the host needs control
        // Phone + control: a phone-sized HOST grid is the best experience — offer it
        // once with an explicit confirm (resizing the host stays user-approved). Skips
        // when the grid already (roughly) fits; declining highlights the Fit-host
        // button instead, as a reminder it's there.
        if (controlGranted && isPhone() && !fithostPrompted) {
          fithostPrompted = true;
          var offerFitHost = function (retriesLeft) {
            var g = fitHostGrid();
            if (!g) { // panes not measurable yet — try again shortly
              if (retriesLeft > 0) setTimeout(function () { offerFitHost(retriesLeft - 1); }, 1200);
              return;
            }
            if (g.curCols <= g.cols + 2 && g.curRows <= g.rows + 2) return; // already fits
            if (window.confirm("Fit the host's window to this phone screen? Its BossTerm window will resize."))
              sendMsg({ t: "resizeHost", tabId: activeTabId, cols: g.cols, rows: g.rows });
            else {
              fithostEl.style.boxShadow = "0 0 0 2px #4a90e2";
              setTimeout(function () { fithostEl.style.boxShadow = ""; }, 4000);
            }
          };
          // Let the layout/snapshot settle so the grid measurement is real.
          setTimeout(function () { offerFitHost(2); }, 800);
        }
        // Second hop of a chained upstream request (view-only → control → relay upstream).
        if (controlGranted && pendingUpstreamControlTab) {
          sendMsg({ t: "requestControl", tabId: pendingUpstreamControlTab });
          pendingUpstreamControlTab = null;
        }
        renderTabBar(); // show/hide the close + new-tab affordances
        buildKeybar();  // show/hide the on-screen control-key bar
        updateViewPill(); // also directly, in case layout hasn't arrived yet
        break;
    }
  };

  connectWebSocket();
})();
