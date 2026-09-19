"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const { webcrypto, createHash } = require("node:crypto");
const path = require("node:path");
const source = fs.readFileSync(path.join(process.argv[2], "viewer-files.js"), "utf8");
class Element {
  constructor() { this.children = []; this.textContent = ""; this.hidden = false; this.files = []; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  setAttribute() {}
  addEventListener() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  focus() {}
  click() { if (this.onclick) this.onclick(); }
}
const elements = new Map();
const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
let ui, control = true, wire = [], written = [], downloadOffset = 0, blobs = [], corruptHash = false, cancelWrite = false;
const payload = Buffer.from("Unicode file contents 😀\n".repeat(4000));
const hash = bytes => createHash("sha256").update(bytes).digest("hex");
const context = {
  console, setTimeout: (fn, delay) => { const t = setTimeout(fn, delay); t.unref(); return t; },
  clearTimeout, crypto: webcrypto, Uint8Array, Blob, ArrayBuffer,
  atob: s => Buffer.from(s, "base64").toString("binary"),
  btoa: s => Buffer.from(s, "binary").toString("base64"),
  URL: { createObjectURL: blob => { blobs.push(blob); return "blob:test"; }, revokeObjectURL() {} },
  document: { getElementById: get, createElement: () => new Element(), createElementNS: () => new Element() },
  confirm: () => true
};
context.window = context;
vm.runInNewContext(source, context);
const api = context.BossTermFiles;
let busyHost = false;
function send(message) {
  wire.push(message);
  if (message.t === "requestControl") return;
  assert.equal(busyHost, false, "only one request in flight");
  busyHost = true;
  setImmediate(() => {
    busyHost = false;
    let reply = { requestId: message.requestId };
    switch (message.operation) {
      case "access": Object.assign(reply, { root: "/approved", writable: control }); break;
      case "list": reply.entries = [{ name: "<script>.txt", directory: false, size: payload.length }]; break;
      case "download": Object.assign(reply, { transferId: "download", size: payload.length }); downloadOffset = 0; break;
      case "read": {
        assert.equal(message.offset, downloadOffset);
        const part = payload.subarray(downloadOffset, downloadOffset + 32768);
        downloadOffset += part.length;
        Object.assign(reply, { data: part.toString("base64"), done: downloadOffset === payload.length, sha256: corruptHash ? "invalid" : hash(payload) }); break;
      }
      case "upload": written = []; reply.transferId = "upload"; break;
      case "write": {
        assert.equal(message.offset, Buffer.concat(written).length);
        written.push(Buffer.from(message.data, "base64"));
        reply.size = Buffer.concat(written).length;
        if (cancelWrite) { cancelWrite = false; get("files-cancel").click(); }
        break;
      }
      case "finish": assert.equal(message.sha256, hash(Buffer.concat(written))); break;
      case "cancel": break;
      default: throw new Error(message.operation);
    }
    ui.receive(reply);
  });
}
const waitFor = async predicate => {
  for (let i = 0; i < 200; i++) {
    if (predicate()) return;
    await new Promise(r => setTimeout(r, 5));
  }
  throw new Error("Timed out");
};
(async () => {
  let request;
  const rpc = api.createRpc(m => request = m);
  const pending = rpc.request("access");
  await assert.rejects(rpc.request("list"), /active/);
  rpc.receive({ requestId: "unrelated" });
  rpc.receive({ requestId: request.requestId, error: "Permission denied" });
  await assert.rejects(pending, /Permission denied/);
  const interrupted = rpc.request("list"); rpc.disconnect();
  await assert.rejects(interrupted, /interrupted/);

  ui = api.mount(send, () => control);
  ui.layout({ filesAvailable: false }); assert.equal(get("files-actions").hidden, true);
  ui.layout({ filesAvailable: true }); assert.equal(get("files-actions").hidden, false);
  get("files-browse").click();
  await waitFor(() => get("files-list").children.length === 1 && get("files-cancel").hidden);
  assert.equal(get("files-list").children[0].children[0].textContent, "<script>.txt");
  get("files-list").children[0].children[0].click();
  await waitFor(() => blobs.length === 1 && get("files-cancel").hidden);
  assert.deepEqual(Buffer.from(await blobs[0].arrayBuffer()), payload);

  get("files-picker").files = [{ name: "test.txt", size: payload.length, arrayBuffer: async () => payload }];
  get("files-picker").onchange();
  await waitFor(() => wire.some(m => m.operation === "finish") && get("files-cancel").hidden);
  assert.deepEqual(Buffer.concat(written), payload);

  corruptHash = true;
  get("files-list").children[0].children[0].click();
  await waitFor(() => get("files-cancel").hidden);
  assert.equal(blobs.length, 1, "corrupt downloads are never saved");
  assert.match(get("files-status").textContent, /verification failed/);
  corruptHash = false;

  const finishes = wire.filter(m => m.operation === "finish").length;
  cancelWrite = true;
  get("files-picker").onchange();
  await waitFor(() => get("files-cancel").hidden);
  assert.equal(wire.filter(m => m.operation === "finish").length, finishes, "cancelled uploads are never published");
  assert.equal(wire.at(-1).operation, "cancel");

  get("files-picker").files = [{ name: "large", size: 65 * 1024 * 1024 }];
  get("files-picker").onchange();
  await waitFor(() => get("files-cancel").hidden);
  assert.match(get("files-status").textContent, /64 MB/);

  control = false;
  get("files-upload").click();
  await waitFor(() => wire.some(m => m.t === "requestControl"));
  ui.control(false);
  await waitFor(() => get("files-cancel").hidden);
  assert.match(get("files-status").textContent, /not approved/);
  get("files-upload").click();
  control = true; ui.control(true);
  await waitFor(() => get("files-status").textContent.includes("Choose files"));
  assert.equal(get("files-dialog").open, true);
  ui.disconnect();
  assert.equal(get("files-dialog").open, false);
  assert.equal(get("files-actions").hidden, true);
  console.log("PASS files RPC bounds, errors, disconnect, safe names, multi-chunk verified upload/download, control approval/denial");
})().catch(e => { console.error(e); process.exitCode = 1; });
