// Behavioural harness for the SHIPPED share-viewer/viewer.js.
//
// viewer.js is a browser IIFE with no exports, so the only way to test it is to give it a browser:
// a fake DOM, a fake xterm Terminal, a fake WebSocket, and a controllable clock / rAF queue. Every
// scenario below then drives it the way a real session does — frames in, DOM and socket state out.
//
// This is deliberately NOT a source-text test. Grepping viewer.js for identifiers proves nothing
// about execution and cannot catch an undefined global on a live code path; loading and running it
// catches both. Usage: node viewer-harness.cjs <dir containing viewer-logic.js and viewer.js>

"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const assetDir = process.argv[2];
const readAsset = (name) => fs.readFileSync(path.join(assetDir, name), "utf8");

// ---------------------------------------------------------------- clock + frames

let now = 0;
let timerSeq = 0;
let timers = new Map();

function installClock() {
  now = 0;
  timerSeq = 0;
  timers = new Map();
}

function fakeSetTimeout(fn, ms) {
  const id = ++timerSeq;
  timers.set(id, { at: now + (Number(ms) || 0), fn });
  return id;
}

function fakeClearTimeout(id) {
  timers.delete(id);
}

/** Run every timer due within [ms], in due order, so retry ladders unfold deterministically. */
function advance(ms) {
  const target = now + ms;
  for (;;) {
    let due = null;
    for (const [id, timer] of timers) {
      if (timer.at <= target && (due === null || timer.at < due.timer.at)) due = { id, timer };
    }
    if (due === null) break;
    timers.delete(due.id);
    now = due.timer.at;
    due.timer.fn();
  }
  now = target;
}

let rafSeq = 0;
let frames = new Map();

function fakeRequestAnimationFrame(fn) {
  const id = ++rafSeq;
  frames.set(id, fn);
  return id;
}

function fakeCancelAnimationFrame(id) {
  frames.delete(id);
}

function flushFrames() {
  const pending = Array.from(frames.values());
  frames.clear();
  pending.forEach((fn) => fn(now));
}

// ---------------------------------------------------------------- DOM

class ClassList {
  constructor(node) {
    this.node = node;
  }
  _read() {
    return String(this.node.className || "").split(/\s+/).filter(Boolean);
  }
  _write(names) {
    this.node.className = names.join(" ");
  }
  add(...names) {
    const set = new Set(this._read());
    names.forEach((n) => set.add(n));
    this._write(Array.from(set));
  }
  remove(...names) {
    const set = new Set(this._read());
    names.forEach((n) => set.delete(n));
    this._write(Array.from(set));
  }
  toggle(name, force) {
    const set = new Set(this._read());
    const want = force === undefined ? !set.has(name) : !!force;
    if (want) set.add(name);
    else set.delete(name);
    this._write(Array.from(set));
    return want;
  }
  contains(name) {
    return this._read().indexOf(name) >= 0;
  }
}

function newStyle() {
  const style = {
    cssText: "",
    setProperty(key, value) {
      style[key] = value;
    },
    removeProperty(key) {
      delete style[key];
    },
  };
  return style;
}

class FakeElement {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.className = "";
    this.classList = new ClassList(this);
    this.style = newStyle();
    this.attributes = {};
    this.childNodes = [];
    this.parentNode = null;
    this.listeners = {};
    this._text = "";
    this._html = "";
    this.title = "";
    this.value = "";
    // Non-zero so viewer.js measurement paths (fit / graphics draw) run instead of bailing out.
    this.clientWidth = 800;
    this.clientHeight = 480;
    this.offsetWidth = 800;
    this.offsetHeight = 480;
    this.rect = { left: 0, top: 0, width: 800, height: 480, right: 800, bottom: 480 };
    this.dataset = {};
  }

  get children() {
    return this.childNodes.filter((n) => n instanceof FakeElement);
  }
  get firstChild() {
    return this.childNodes[0] || null;
  }

  get textContent() {
    if (this.childNodes.length === 0) return this._text;
    return this.childNodes.map((n) => n.textContent || "").join("");
  }
  set textContent(value) {
    this.childNodes = [];
    this._text = String(value);
  }

  get innerHTML() {
    return this._html;
  }
  set innerHTML(value) {
    // Every assignment in viewer.js is either "" (a clear) or a static SVG on a fresh button, so
    // recording the markup and dropping children is faithful enough for behavioural assertions.
    this.childNodes = [];
    this._html = String(value);
  }

  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  }
  insertBefore(child, reference) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    const at = reference ? this.childNodes.indexOf(reference) : -1;
    if (at < 0) this.childNodes.push(child);
    else this.childNodes.splice(at, 0, child);
    return child;
  }
  removeChild(child) {
    const at = this.childNodes.indexOf(child);
    if (at >= 0) this.childNodes.splice(at, 1);
    child.parentNode = null;
    return child;
  }
  remove() {
    if (this.parentNode) this.parentNode.removeChild(this);
  }
  contains(node) {
    for (let n = node; n; n = n.parentNode) if (n === this) return true;
    return false;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === "class") this.className = String(value);
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  removeAttribute(name) {
    delete this.attributes[name];
  }

  addEventListener(type, fn) {
    (this.listeners[type] || (this.listeners[type] = [])).push(fn);
  }
  removeEventListener(type, fn) {
    const list = this.listeners[type];
    if (!list) return;
    const at = list.indexOf(fn);
    if (at >= 0) list.splice(at, 1);
  }
  dispatchEvent(event) {
    (this.listeners[event.type] || []).forEach((fn) => fn(event));
    const inline = this["on" + event.type];
    if (typeof inline === "function") inline(event);
    return true;
  }

  getBoundingClientRect() {
    return this.rect;
  }
  focus() {
    fakeDocument.activeElement = this;
  }
  blur() {
    if (fakeDocument.activeElement === this) fakeDocument.activeElement = fakeDocument.body;
  }
  scrollIntoView() {}

  matchesSelector(selector) {
    if (selector.charAt(0) === ".") return this.classList.contains(selector.slice(1));
    if (selector.charAt(0) === "#") return this.attributes.id === selector.slice(1) || this.id === selector.slice(1);
    return this.tagName === selector.toUpperCase();
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  querySelectorAll(selector) {
    const found = [];
    const walk = (node) => {
      node.children.forEach((child) => {
        if (child.matchesSelector(selector)) found.push(child);
        walk(child);
      });
    };
    walk(this);
    return found;
  }

  // Canvas surface — records draw calls so graphics assertions can look at real output.
  getContext(kind) {
    if (kind !== "2d") return null;
    if (!this.ctx) {
      const calls = [];
      this.drawCalls = calls;
      this.ctx = {
        calls: calls,
        setTransform() {},
        clearRect() {},
        drawImage(...args) {
          calls.push(args);
        },
      };
    }
    return this.ctx;
  }
}

let fakeDocument = null;

function installDocument() {
  const byId = {};
  const documentElement = new FakeElement("html");
  const body = new FakeElement("body");
  documentElement.appendChild(body);
  fakeDocument = {
    documentElement: documentElement,
    body: body,
    activeElement: body,
    // Undefined `fonts` exercises the same branch as a browser without the Font Loading API.
    fonts: undefined,
    listeners: {},
    createElement(tag) {
      return new FakeElement(tag);
    },
    createTextNode(text) {
      const node = new FakeElement("#text");
      node.textContent = text;
      return node;
    },
    getElementById(id) {
      if (!byId[id]) {
        const el = new FakeElement("div");
        el.id = id;
        el.setAttribute("id", id);
        byId[id] = el;
        body.appendChild(el);
      }
      return byId[id];
    },
    querySelector(selector) {
      return documentElement.querySelector(selector);
    },
    addEventListener(type, fn) {
      (fakeDocument.listeners[type] || (fakeDocument.listeners[type] = [])).push(fn);
    },
    removeEventListener() {},
    execCommand() {
      return true;
    },
    get title() {
      return this._title || "";
    },
    set title(value) {
      this._title = value;
    },
  };
  return fakeDocument;
}

// ---------------------------------------------------------------- xterm + WebSocket doubles

class FakeTerminal {
  constructor(options) {
    FakeTerminal.created.push(this);
    this.options = Object.assign({ scrollback: 1000, theme: {} }, options);
    this.cols = 80;
    this.rows = 24;
    this.written = [];
    this.buffer = { active: { baseY: 0, viewportY: 0, cursorY: 0, cursorX: 0, length: 24 } };
    this.handlers = { render: [], scroll: [], resize: [], cursorMove: [], data: [] };
    this.disposed = false;
    this.scrolledToLine = null;
    this.textarea = new FakeElement("textarea");
    this.textarea.classList.add("xterm-helper-textarea");
  }
  open(host) {
    this.host = host;
    const wrapper = new FakeElement("div");
    wrapper.classList.add("xterm");
    const screen = new FakeElement("div");
    screen.classList.add("xterm-screen");
    const glyphs = new FakeElement("canvas");
    screen.appendChild(glyphs);
    wrapper.appendChild(screen);
    wrapper.appendChild(this.textarea);
    host.appendChild(wrapper);
    this.screen = screen;
  }
  write(data, callback) {
    if (data) this.written.push(data);
    if (typeof callback === "function") callback();
  }
  reset() {
    this.written = [];
  }
  resize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    this.handlers.resize.forEach((fn) => fn({ cols: cols, rows: rows }));
  }
  scrollToLine(line) {
    this.scrolledToLine = line;
  }
  scrollToBottom() {}
  scrollLines() {}
  clear() {}
  selectAll() {}
  clearSelection() {}
  hasSelection() {
    return false;
  }
  getSelection() {
    return "";
  }
  focus() {}
  loadAddon() {}
  dispose() {
    this.disposed = true;
  }
  onRender(fn) {
    this.handlers.render.push(fn);
    return { dispose() {} };
  }
  onScroll(fn) {
    this.handlers.scroll.push(fn);
    return { dispose() {} };
  }
  onResize(fn) {
    this.handlers.resize.push(fn);
    return { dispose() {} };
  }
  onCursorMove(fn) {
    this.handlers.cursorMove.push(fn);
    return { dispose() {} };
  }
  onData(fn) {
    this.handlers.data.push(fn);
    return { dispose() {} };
  }
  /** What xterm fires after painting — the hook whose rAF cost this suite guards. */
  emitRender() {
    this.handlers.render.forEach((fn) => fn({ start: 0, end: this.rows - 1 }));
  }
}
FakeTerminal.created = [];

class FakeImage {
  constructor() {
    this.complete = false;
    this.naturalWidth = 0;
    this.naturalHeight = 0;
    this._src = "";
    FakeImage.pending.push(this);
  }
  get src() {
    return this._src;
  }
  set src(value) {
    this._src = value;
  }
  /** Simulate a successful decode of a [width]×[height] raster. */
  decodeAs(width, height) {
    this.complete = true;
    this.naturalWidth = width;
    this.naturalHeight = height;
    if (this.onload) this.onload();
  }
  failDecode() {
    if (this.onerror) this.onerror();
  }
}
FakeImage.pending = [];

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }
  send(data) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
  }
  /** Complete the handshake the way a live host does. */
  open() {
    this.readyState = 1;
    if (this.onopen) this.onopen({});
  }
  deliver(message) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(message) });
  }
  /** 1006 = abnormal closure, i.e. the transient drop the retry budget exists for. */
  drop(code) {
    this.readyState = 3;
    if (this.onerror) this.onerror({});
    if (this.onclose) this.onclose({ code: code === undefined ? 1006 : code, reason: "" });
  }
  static reset() {
    FakeWebSocket.instances = [];
  }
  static get latest() {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }
}
FakeWebSocket.instances = [];
// viewer.js gates every send on `ws.readyState !== WebSocket.OPEN`, so these class constants are
// part of the contract, not decoration.
FakeWebSocket.CONNECTING = 0;
FakeWebSocket.OPEN = 1;
FakeWebSocket.CLOSING = 2;
FakeWebSocket.CLOSED = 3;

// ---------------------------------------------------------------- load

/**
 * Install a browser and run the two viewer scripts in it.
 *
 * `vm.runInThisContext` (not require) matters twice: viewer-logic.js's UMD wrapper must take its
 * BROWSER branch (no CommonJS `module` in scope) so it publishes window.BossTermViewerLogic exactly
 * as the served page does, and viewer.js must see the same globals a page would.
 */
// ---------------------------------------------------------------- voice call fakes
// Boss Calling needs a secure context, a mic, WebRTC and fetch. Scenarios opt in
// (`voiceCapable: true`) so the default load still exercises the no-voice branch a plain-LAN
// http:// share really takes.

/** Real promises need a microtask turn to settle; the faked clock can't do that. */
const flushPromises = () => new Promise((resolve) => setImmediate(resolve));

class FakeMediaTrack {
  constructor() { this.enabled = true; this.stopped = false; }
  stop() { this.stopped = true; }
}

class FakeMediaStream {
  constructor() { this.tracks = [new FakeMediaTrack()]; }
  getAudioTracks() { return this.tracks; }
  getTracks() { return this.tracks; }
}

class FakeDataChannel {
  constructor(label) {
    this.label = label; this.readyState = "connecting"; this.sent = []; this.closed = false;
    this.onopen = null; this.onmessage = null;
  }
  send(data) { this.sent.push(data); }
  close() { this.closed = true; this.readyState = "closed"; }
  /** Test drivers: the channel coming up, and a server event arriving on it. */
  open() { this.readyState = "open"; if (this.onopen) this.onopen(); }
  deliver(event) { if (this.onmessage) this.onmessage({ data: JSON.stringify(event) }); }
}

class FakeRTCPeerConnection {
  constructor() {
    this.tracks = []; this.dc = null; this.closed = false; this.remote = null;
    this.connectionState = "new"; this.ontrack = null; this.onconnectionstatechange = null;
    FakeRTCPeerConnection.latest = this;
  }
  addTrack(track) { this.tracks.push(track); }
  createDataChannel(label) { this.dc = new FakeDataChannel(label); return this.dc; }
  createOffer() { return Promise.resolve({ type: "offer", sdp: "v=0 offer" }); }
  setLocalDescription() { return Promise.resolve(); }
  setRemoteDescription(desc) { this.remote = desc; return Promise.resolve(); }
  close() { this.closed = true; this.connectionState = "closed"; }
}
FakeRTCPeerConnection.latest = null;

const FakeFetch = {
  calls: [],
  install() {
    FakeFetch.calls = [];
    return (url, init) => {
      FakeFetch.calls.push({ url: url, init: init });
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("v=0 answer") });
    };
  },
};

function loadViewer(options) {
  const opts = options || {};
  installClock();
  frames = new Map();
  FakeWebSocket.reset();
  FakeImage.pending = [];
  FakeTerminal.created = [];
  const document = installDocument();

  const store = {};
  const win = global;
  // Several of these (navigator, crypto, WebSocket, …) are accessor-only on modern Node, so
  // define rather than assign — a plain `=` throws in strict mode.
  const define = (name, value) =>
    Object.defineProperty(win, name, { value: value, writable: true, configurable: true, enumerable: true });

  delete win.BossTermViewerLogic;
  define("window", win);
  define("document", document);
  define("location", {
    protocol: "http:",
    host: "192.168.1.20:8770",
    search: "?t=view-token",
    hash: "",
    reloaded: 0,
    reload() {
      win.location.reloaded += 1;
    },
  });
  define("navigator", { platform: "TestPhone", userAgent: "harness", clipboard: undefined });
  define("localStorage", {
    getItem(key) {
      return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null;
    },
    setItem(key, value) {
      store[key] = String(value);
    },
    removeItem(key) {
      delete store[key];
    },
  });
  // Plaintext LAN link: no secure context and no crypto.subtle, so the E2E branch stays out of the
  // way and frames are the plain JSON a http:// share actually exchanges.
  define("isSecureContext", false);
  define("crypto", undefined);
  define("devicePixelRatio", 2);
  define("innerWidth", 390);
  define("innerHeight", 780);
  define("visualViewport", undefined);
  define("matchMedia", () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
  }));
  define("confirm", () => false);
  define("prompt", () => null);
  define("open", () => null);
  define("close", () => {});
  define("WebglAddon", undefined);
  define("WebLinksAddon", undefined);
  define("Terminal", FakeTerminal);
  define("Image", FakeImage);
  define("WebSocket", FakeWebSocket);
  define("setTimeout", fakeSetTimeout);
  define("clearTimeout", fakeClearTimeout);
  define("setInterval", fakeSetTimeout);
  define("clearInterval", fakeClearTimeout);
  define("requestAnimationFrame", fakeRequestAnimationFrame);
  define("cancelAnimationFrame", fakeCancelAnimationFrame);
  define("addEventListener", () => {});
  define("removeEventListener", () => {});

  if (opts.voiceCapable) {
    FakeRTCPeerConnection.latest = null;
    define("isSecureContext", true);
    define("navigator", {
      platform: "TestPhone", userAgent: "harness", clipboard: undefined,
      mediaDevices: { getUserMedia: () => Promise.resolve(new FakeMediaStream()) },
    });
    define("RTCPeerConnection", FakeRTCPeerConnection);
    define("fetch", FakeFetch.install());
    // No AudioContext on purpose: the level meter must degrade to flat bars, never throw.
    define("AudioContext", undefined);
    define("webkitAudioContext", undefined);
  }

  if (!opts.withoutViewerLogic) {
    vm.runInThisContext(readAsset("viewer-logic.js"), { filename: "viewer-logic.js" });
    assert.ok(win.BossTermViewerLogic, "viewer-logic.js must publish window.BossTermViewerLogic");
  }
  vm.runInThisContext(readAsset("viewer.js"), { filename: "viewer.js" });
  return { document: document };
}

const el = (id) => fakeDocument.getElementById(id);
const overlayTitle = () => el("overlay-title").textContent;
const overlayActions = () => el("overlay-actions").children.map((b) => b.textContent);
const lastTerminal = () => FakeTerminal.created[FakeTerminal.created.length - 1];

/** Bring a fresh viewer to the state a real one reaches right after Layout + PaneSnapshot. */
function connectPanes(paneIds) {
  const socket = FakeWebSocket.latest;
  socket.open();
  socket.deliver({
    t: "layout",
    tabs: [
      {
        id: "tab-1",
        title: "zsh",
        tree: paneIds.length === 1
          ? { t: "pane", paneId: paneIds[0] }
          : { t: "split", v: true, a: { t: "pane", paneId: paneIds[0] }, b: { t: "pane", paneId: paneIds[1] } },
      },
    ],
    activeTabId: "tab-1",
  });
  paneIds.forEach((paneId) => {
    socket.deliver({ t: "paneSnapshot", paneId: paneId, data: "$ ", cols: 80, rows: 24, scrollbackLines: 12000 });
  });
  flushFrames();
  return socket;
}

const IMAGE_FRAME = (paneId, revision, full) => ({
  t: "paneGraphics",
  paneId: paneId,
  revision: revision,
  full: full,
  images: [{ id: "7", mimeType: "image/png", data: "AAAA", contentHash: "4-abc" }],
  removedImageIds: [],
  requiredImageIds: ["7"],
  cells: [
    { imageId: "7", row: 0, col: 0, cellX: 0, cellY: 0, length: 4, totalCellsX: 4, totalCellsY: 2 },
  ],
  historyLines: 0,
});

// ---------------------------------------------------------------- scenarios

const scenarios = {
  "viewer.js loads and connects with no undefined globals on the startup path"() {
    loadViewer();
    assert.strictEqual(FakeWebSocket.instances.length, 1, "startup must open exactly one socket");
    assert.strictEqual(
      FakeWebSocket.latest.url,
      "ws://192.168.1.20:8770/ws/view-token",
      "the socket must target this document's own origin (what the CSP has to allow)"
    );
    const socket = connectPanes(["pane-1"]);
    assert.deepStrictEqual(
      JSON.parse(socket.sent[0]).capabilities,
      ["paneGraphicsV1"],
      "the Hello must advertise host-decoded graphics"
    );
  },

  "a transient drop retries three times, then prompts once"() {
    loadViewer();
    connectPanes(["pane-1"]);
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      const before = FakeWebSocket.instances.length;
      FakeWebSocket.latest.drop(1006);
      assert.ok(
        /Reconnecting/.test(overlayTitle()),
        "attempt " + attempt + " must show the automatic-retry overlay, saw: " + overlayTitle()
      );
      advance(10000);
      assert.strictEqual(
        FakeWebSocket.instances.length,
        before + 1,
        "attempt " + attempt + " must open a new socket"
      );
      FakeWebSocket.latest.open();
    }
    const beforePrompt = FakeWebSocket.instances.length;
    FakeWebSocket.latest.drop(1006);
    advance(60000);
    assert.strictEqual(FakeWebSocket.instances.length, beforePrompt, "the fourth drop must not retry");
    assert.strictEqual(overlayTitle(), "Disconnected");
    assert.deepStrictEqual(overlayActions(), ["Reconnect", "Close"]);
    assert.strictEqual(el("status").className, "down");
  },

  "staying healthy for the stable window earns a fresh retry budget"() {
    loadViewer();
    connectPanes(["pane-1"]);
    FakeWebSocket.latest.drop(1006);
    advance(10000);
    FakeWebSocket.latest.open();
    connectPanes(["pane-1"]); // a live session: Layout arrives and the connection settles
    advance(60000); // longer than RECONNECT_STABLE_MS
    // Budget reset — three more automatic attempts must be available before the prompt.
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      const before = FakeWebSocket.instances.length;
      FakeWebSocket.latest.drop(1006);
      advance(10000);
      assert.strictEqual(
        FakeWebSocket.instances.length,
        before + 1,
        "post-heal attempt " + attempt + " must retry"
      );
      FakeWebSocket.latest.open();
    }
  },

  "a terminal close code ends the session without retrying"() {
    loadViewer();
    connectPanes(["pane-1"]);
    const before = FakeWebSocket.instances.length;
    FakeWebSocket.latest.drop(1000);
    advance(60000);
    assert.strictEqual(FakeWebSocket.instances.length, before, "1000 must not consume a retry");
    assert.strictEqual(overlayTitle(), "Connection ended");
  },

  "an error without a close does not consume a retry"() {
    loadViewer();
    connectPanes(["pane-1"]);
    const socket = FakeWebSocket.latest;
    if (socket.onerror) socket.onerror({});
    advance(60000);
    assert.strictEqual(FakeWebSocket.instances.length, 1, "error alone must not reconnect");
    assert.strictEqual(el("status").className, "down");
  },

  "a text-only pane schedules no graphics frame when xterm renders"() {
    loadViewer();
    connectPanes(["pane-1"]);
    flushFrames();
    const terminal = lastTerminal();
    assert.ok(terminal, "a pane terminal must exist");
    terminal.emitRender();
    terminal.handlers.scroll.forEach((fn) => fn(0));
    assert.strictEqual(
      frames.size,
      0,
      "a pane with no images, nothing decoding and no canvas must not queue a rAF per render"
    );
  },

  "graphics frames paint an overlay canvas below xterm's own"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    socket.deliver(IMAGE_FRAME("pane-1", 1, true));
    assert.strictEqual(FakeImage.pending.length, 1, "the raster must start decoding");
    FakeImage.pending[0].decodeAs(64, 32);
    flushFrames();
    const terminal = lastTerminal();
    const canvases = terminal.screen.querySelectorAll("canvas");
    assert.strictEqual(canvases.length, 2, "one overlay canvas plus xterm's glyph canvas");
    assert.strictEqual(canvases[0].className, "bossterm-graphics", "images must paint UNDER the glyphs");
    assert.ok(canvases[0].drawCalls && canvases[0].drawCalls.length > 0, "the decoded raster must be drawn");
    // A render after the images are placed SHOULD schedule a frame — the text-only gate must not
    // suppress real graphics work.
    terminal.emitRender();
    assert.strictEqual(frames.size, 1, "a pane with placements must still repaint on render");
  },

  "one pane recovering keeps the out-of-sync warning while another is still degraded"() {
    loadViewer();
    const socket = connectPanes(["pane-1", "pane-2"]);
    // Both panes require an image they never receive → each burns its resync budget and degrades.
    const missing = (paneId) => ({
      t: "paneGraphics",
      paneId: paneId,
      revision: 1,
      full: true,
      images: [],
      removedImageIds: [],
      requiredImageIds: ["9"],
      cells: [],
      historyLines: 0,
    });
    [1, 2].forEach((n) => {
      for (let round = 0; round < 6; round += 1) {
        socket.deliver(missing("pane-" + n));
        advance(30000);
      }
    });
    assert.ok(/out of sync/.test(el("status").title), "both panes degraded must warn: " + el("status").title);
    // pane-1 recovers; pane-2 has not.
    socket.deliver(IMAGE_FRAME("pane-1", 2, true));
    FakeImage.pending.forEach((img) => img.decodeAs(16, 16));
    assert.ok(
      /out of sync/.test(el("status").title),
      "the shared tooltip must survive one pane recovering, saw: '" + el("status").title + "'"
    );
    // pane-2 recovers too → the warning clears.
    socket.deliver(IMAGE_FRAME("pane-2", 2, true));
    FakeImage.pending.forEach((img) => img.decodeAs(16, 16));
    assert.strictEqual(el("status").title, "", "the warning clears once every pane is in sync");
  },

  "transparency is enabled only while a pane actually has drawable graphics"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    const terminal = lastTerminal();
    assert.strictEqual(terminal.options.allowTransparency, false, "a text-only pane stays opaque");
    socket.deliver(IMAGE_FRAME("pane-1", 1, true));
    FakeImage.pending[0].decodeAs(64, 32);
    flushFrames();
    assert.strictEqual(terminal.options.allowTransparency, true, "images need a transparent terminal");
    // Host clears the image: the overlay canvas and the transparency must both go away, or the
    // pane keeps paying for a compositing path it no longer needs.
    socket.deliver({
      t: "paneGraphics", paneId: "pane-1", revision: 2, full: true,
      images: [], removedImageIds: ["7"], requiredImageIds: [], cells: [], historyLines: 0,
    });
    flushFrames();
    assert.strictEqual(terminal.options.allowTransparency, false, "transparency must be released");
    assert.strictEqual(
      terminal.screen.querySelectorAll("canvas").length,
      1,
      "only xterm's own canvas should remain"
    );
  },

  "an undecodable raster is not retried with another full payload"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    socket.deliver(IMAGE_FRAME("pane-1", 1, true));
    const sentBefore = socket.sent.length;
    FakeImage.pending[0].failDecode();
    flushFrames();
    // The same bytes cannot become decodable, so a resync would loop forever over a multi-MB frame.
    socket.deliver(IMAGE_FRAME("pane-1", 2, false));
    advance(60000);
    const resyncs = socket.sent.slice(sentBefore).filter((raw) => JSON.parse(raw).t === "graphicsResync");
    assert.deepStrictEqual(resyncs, [], "a decode failure must not request a full resync");
  },

  "an unknown raster mime type falls back to png instead of being trusted"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    const frame = IMAGE_FRAME("pane-1", 1, true);
    frame.images[0].mimeType = "text/html";
    socket.deliver(frame);
    assert.strictEqual(
      FakeImage.pending[0].src,
      "data:image/png;base64,AAAA",
      "a host-supplied mime type must be allowlisted before it reaches a data: URL"
    );
  },

  "host theme numbers are clamped and non-string colors fall back"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    socket.deliver({ t: "theme", background: { evil: true }, fontSize: 9999, fontFamily: 42 });
    const terminal = lastTerminal();
    assert.strictEqual(terminal.options.fontSize, 72, "an absurd host font size must be clamped");
    assert.strictEqual(
      terminal.options.theme.background,
      "#1e1e1e",
      "a non-string background must fall back to the default"
    );
    socket.deliver({ t: "theme", fontSize: "not a number" });
    assert.strictEqual(terminal.options.fontSize, 13, "an unparseable font size falls back");
  },

  "pane output never moves the viewer's scroll position"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    const terminal = lastTerminal();
    terminal.buffer.active.baseY = 120;
    terminal.buffer.active.viewportY = 90;
    socket.deliver({ t: "paneOutput", paneId: "pane-1", data: "hello" });
    assert.strictEqual(terminal.scrolledToLine, null, "incremental output must not re-anchor the reader");
    assert.ok(terminal.written.indexOf("hello") >= 0);
  },

  "a repaint restores the reader's distance from the bottom"() {
    loadViewer();
    const socket = connectPanes(["pane-1"]);
    const terminal = lastTerminal();
    terminal.buffer.active.baseY = 120;
    terminal.buffer.active.viewportY = 87;
    socket.deliver({ t: "paneRepaint", paneId: "pane-1", data: "SCREEN" });
    assert.strictEqual(terminal.scrolledToLine, 87, "the viewer must not be yanked to the bottom");
    assert.ok(terminal.written.indexOf("SCREEN") >= 0, "the repaint payload must reach xterm");
  },

  "a pane snapshot clamps the host scrollback to the browser cap"() {
    loadViewer();
    const socket = FakeWebSocket.latest;
    socket.open();
    socket.deliver({ t: "layout", tabs: [{ id: "t", title: "z", tree: { t: "pane", paneId: "p" } }], activeTabId: "t" });
    socket.deliver({ t: "paneSnapshot", paneId: "p", data: "", cols: 80, rows: 24, scrollbackLines: 999999 });
    assert.strictEqual(lastTerminal().options.scrollback, 20000, "the viewer cap must win over a huge host cap");
  },

  async "Boss Calling lives in the bottom bar: Call → live strip with meter, Mute, End"() {
    loadViewer({ voiceCapable: true });
    const socket = connectPanes(["pane-1"]);
    socket.deliver({ t: "control", granted: true });
    const sentOfType = (t) => socket.sent.map(JSON.parse).filter((m) => m.t === t);

    // The bar exists only when the host can actually take a call.
    assert.ok(!el("voicebar").classList.contains("on"), "no bar before the host reports voice status");
    socket.deliver({ t: "voiceStatus", available: false, reason: "no_key" });
    assert.ok(!el("voicebar").classList.contains("on"), "a keyless host must not show the bar");
    socket.deliver({ t: "voiceStatus", available: true });
    assert.ok(el("voicebar").classList.contains("on"), "an available host must show the bottom bar");
    assert.strictEqual(el("voicecallbtn").style.display, "inline-flex", "idle shows the Call button");
    assert.strictEqual(el("voicelabel").textContent, "Call BossTerm", "the button is branded");
    assert.ok(!el("voicecall").classList.contains("on"), "idle hides the in-call strip");

    // Clicking Call takes the mic first, then asks the host to mint.
    el("voicecallbtn").onclick();
    await flushPromises();
    assert.strictEqual(sentOfType("voiceStart").length, 1, "Call must request a session");
    assert.strictEqual(el("voicecallbtn").style.display, "none", "the button yields to the strip");
    assert.ok(el("voicecall").classList.contains("on"), "connecting already shows the strip");
    assert.strictEqual(el("voicestate").textContent, "Connecting…");

    // Minted session → SDP exchange → data channel opens.
    socket.deliver({
      t: "voiceSession", clientSecret: "ek_test", model: "gpt-realtime-2.1", callToken: "call-tok-1",
    });
    await flushPromises();
    assert.strictEqual(
      FakeFetch.calls[0].url,
      "https://api.openai.com/v1/realtime/calls",
      "the GA SDP endpoint must be called with NO query string"
    );
    const pc = FakeRTCPeerConnection.latest;
    assert.strictEqual(pc.dc.label, "oai-events", "the data channel name is part of the protocol");
    pc.dc.open();
    assert.strictEqual(el("voicestate").textContent, "Listening…", "a live call is listening");

    // Mute, agent speech and tool runs are each visible in the strip.
    el("voicemute").onclick();
    assert.strictEqual(el("voicestate").textContent, "Muted");
    assert.ok(el("voicecall").classList.contains("muted"), "the meter must show muted");
    assert.strictEqual(pc.tracks[0].enabled, false, "muting must actually disable the mic track");
    el("voicemute").onclick();
    pc.dc.deliver({ type: "output_audio_buffer.started" });
    assert.strictEqual(el("voicestate").textContent, "Agent speaking");
    pc.dc.deliver({ type: "output_audio_buffer.stopped" });
    assert.strictEqual(el("voicestate").textContent, "Listening…");

    // A tool call rides the share socket, and its result goes back on the data channel.
    pc.dc.deliver({
      type: "response.function_call_arguments.done",
      call_id: "c1", name: "read_scrollback", arguments: "{\"lines\":20}",
    });
    assert.strictEqual(el("voicestate").textContent, "Working…");
    const toolCall = sentOfType("voiceToolCall")[0];
    assert.strictEqual(toolCall.callId, "c1", "the host executes it, not the browser");
    assert.strictEqual(toolCall.callToken, "call-tok-1", "the call handle must be echoed or the host refuses");
    socket.deliver({ t: "voiceToolResult", callId: "c1", resultJson: "{\"ok\":true}" });
    assert.ok(
      pc.dc.sent.some((s) => /function_call_output/.test(s)),
      "the result must be handed back to the model"
    );
    // The response is still generating: asking for another turn now earns
    // conversation_already_has_active_response, so the follow-up must wait for response.done.
    assert.ok(
      !pc.dc.sent.some((s) => /response\.create/.test(s)),
      "response.create must not fire while the response is still open"
    );
    socket.deliver({ t: "voiceToolResult", callId: "c2", resultJson: "{}" }); // stray/unknown id
    pc.dc.deliver({ type: "response.done", response: { output: [] } });
    assert.strictEqual(
      pc.dc.sent.filter((s) => /response\.create/.test(s)).length,
      1,
      "exactly one follow-up turn per tool round, however many results arrived"
    );
    assert.strictEqual(el("voicestate").textContent, "Listening…", "and the call resumes");

    // The host's master switch is a kill switch, not just a hidden button.
    socket.deliver({ t: "voiceStatus", available: false, reason: "disabled" });
    assert.ok(!el("voicecall").classList.contains("on"), "revoking voice must end a live call");
    assert.ok(!el("voicebar").classList.contains("on"), "and take the bar away");
    socket.deliver({ t: "voiceStatus", available: true });

    // End call tears down and restores the idle button.
    el("voicecallbtn").onclick();
    await flushPromises();
    socket.deliver({
      t: "voiceSession", clientSecret: "ek_test2", model: "gpt-realtime-2.1", callToken: "call-tok-2",
    });
    await flushPromises();
    FakeRTCPeerConnection.latest.dc.open();
    const pc2 = FakeRTCPeerConnection.latest;
    el("voicehang").onclick();
    assert.ok(!el("voicecall").classList.contains("on"), "ending hides the strip");
    assert.strictEqual(el("voicecallbtn").style.display, "inline-flex", "and restores the Call button");
    assert.ok(pc2.closed, "the peer connection must be closed");
    assert.ok(pc2.tracks[0].stopped, "the mic must be released");
    assert.ok(pc.closed, "the revoked call's peer connection was closed too");
    // Two calls ended in this scenario: the one the host revoked, and the one hung up here.
    assert.strictEqual(sentOfType("voiceEnd").length, 2, "every ended call must tell the host");
  },

  "a share-socket drop mid-call ends the call instead of leaving the agent blind"() {
    loadViewer({ voiceCapable: true });
    const socket = connectPanes(["pane-1"]);
    socket.deliver({ t: "control", granted: true });
    socket.deliver({ t: "voiceStatus", available: true });
    el("voicecallbtn").onclick();
    return flushPromises().then(() => {
      socket.drop(1006);
      assert.ok(!el("voicecall").classList.contains("on"), "the call strip must go away with the socket");
      assert.strictEqual(el("voicecallbtn").style.display, "inline-flex", "back to an idle Call button");
    });
  },

  "a viewer.js without viewer-logic.js fails visibly instead of throwing"() {
    loadViewer({ withoutViewerLogic: true });
    assert.strictEqual(FakeWebSocket.instances.length, 0, "it must not try to connect");
    assert.ok(
      /failed to load/.test(fakeDocument.body.textContent),
      "the page must say so: '" + fakeDocument.body.textContent + "'"
    );
  },
};

// Awaited, so a scenario that has to let promises settle (the WebRTC call flow) still reports its
// assertion failures instead of losing them to an unhandled rejection. Sync scenarios are
// unaffected — awaiting a non-promise just continues.
let failures = 0;
(async () => {
  for (const name of Object.keys(scenarios)) {
    try {
      await scenarios[name]();
      process.stdout.write("ok   " + name + "\n");
    } catch (e) {
      failures += 1;
      process.stdout.write("FAIL " + name + "\n     " + (e && e.message) + "\n");
      if (e && e.stack) process.stdout.write("     " + e.stack.split("\n").slice(1, 4).join("\n     ") + "\n");
    }
  }

  if (failures > 0) {
    process.stdout.write(failures + " viewer scenario(s) failed\n");
    process.exit(1);
  }
})();
