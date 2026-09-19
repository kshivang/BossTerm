/* Session-level filesV1 client. File names are always text, never HTML. */
(function (root) {
  "use strict";
  var LIMIT = 64 * 1024 * 1024, CHUNK = 32768;
  function createRpc(send) {
    var pending = null, seq = 0, timedOut = false;
    return {
      request: function (operation, args) {
        if (timedOut) return Promise.reject(new Error("Reconnect before retrying file operations"));
        if (pending) return Promise.reject(new Error("Another file request is active"));
        return new Promise(function (resolve, reject) {
          var id = "web-file-" + (++seq);
          var timer = setTimeout(function () {
            pending = null; timedOut = true; reject(new Error("File request timed out; reconnect before retrying"));
          }, operation === "access" ? 130000 : 30000);
          pending = { id: id, resolve: resolve, reject: reject, timer: timer };
          send(Object.assign({}, args, { t: "filesRequest", requestId: id, operation: operation }));
        });
      },
      receive: function (m) {
        if (!pending || pending.id !== m.requestId) return;
        var p = pending; pending = null; clearTimeout(p.timer);
        if (m.error) p.reject(new Error(m.error)); else p.resolve(m);
      },
      disconnect: function () {
        timedOut = false;
        if (!pending) return;
        var p = pending; pending = null; clearTimeout(p.timer);
        p.reject(new Error("Connection interrupted"));
      }
    };
  }
  function mount(send, hasControl) {
    var rpc = createRpc(send), available = false, busy = false, cancelled = false;
    var path = "", rootLabel = "", next = null, epoch = 0, controlWait = null;
    var dialog = document.getElementById("files-dialog");
    var list = document.getElementById("files-list");
    var status = document.getElementById("files-status");
    var location = document.getElementById("files-location");
    var picker = document.getElementById("files-picker");
    function el(id) { return document.getElementById(id); }
    function button(label, action) {
      var b = document.createElement("button"); b.type = "button"; b.textContent = label; b.onclick = action; return b;
    }
    function join(name) { return path ? path + "/" + name : name; }
    function check(run) {
      if (cancelled || run !== epoch) throw new Error("Transfer cancelled");
    }
    async function digest(bytes) {
      var hash = await crypto.subtle.digest("SHA-256", bytes);
      return Array.from(new Uint8Array(hash), function (v) { return v.toString(16).padStart(2, "0"); }).join("");
    }
    function progress(done, total) {
      status.textContent = Math.round(done / Math.max(total, 1) * 100) + "% — " + done.toLocaleString() + " / " + total.toLocaleString() + " bytes";
    }
    async function access() {
      var runEpoch = epoch;
      var result = await rpc.request("access"); check(runEpoch); rootLabel = result.root;
      el("files-role").textContent = result.writable ? "Read and write access" : "Read-only access · Upload requests control";
    }
    async function refresh(append) {
      var runEpoch = epoch;
      var result = await rpc.request("list", { path: path, offset: append ? next : 0 });
      check(runEpoch);
      if (!append) list.replaceChildren();
      next = result.next == null ? null : result.next;
      location.textContent = rootLabel + (path ? "/" + path : "");
      el("files-more").hidden = next === null;
      (result.entries || []).forEach(function (entry) {
        if (!el("files-hidden").checked && entry.name.startsWith(".")) return;
        var row = document.createElement("div"); row.className = "files-row";
        var name = button((entry.directory ? "▸ " : "") + entry.name, function () {
          if (busy) return;
          if (entry.directory) run(async function () { path = join(entry.name); await refresh(false); });
          else run(function () { return download(entry); });
        });
        name.className = "files-name";
        var detail = document.createElement("span");
        detail.textContent = entry.directory ? "Folder" : Math.ceil(entry.size / 1024).toLocaleString() + " KB";
        row.appendChild(name); row.appendChild(detail); list.appendChild(row);
      });
    }
    async function run(action) {
      if (busy) return;
      busy = true; cancelled = false; status.textContent = "";
      el("files-cancel").hidden = false; dialog.setAttribute("aria-busy", "true");
      try { await action(); }
      catch (e) { status.textContent = e.message || "File operation failed"; }
      finally { busy = false; el("files-cancel").hidden = true; dialog.setAttribute("aria-busy", "false"); }
    }
    async function cancelTransfer(id, runEpoch) {
      if (id && runEpoch === epoch) {
        try { await rpc.request("cancel", { transferId: id }); } catch (_) {}
      }
    }
    async function download(entry) {
      if (entry.size > LIMIT) throw new Error("Web downloads support files up to 64 MB. Use BossTerm desktop for larger files.");
      var id = null, runEpoch = epoch;
      try {
        var begin = await rpc.request("download", { path: join(entry.name) }); id = begin.transferId;
        if (begin.size == null) begin.size = 0;
        if (!Number.isSafeInteger(begin.size) || begin.size < 0 || begin.size > LIMIT) throw new Error("File exceeds the 64 MB web limit");
        var bytes = new Uint8Array(begin.size), offset = 0;
        while (true) {
          check(runEpoch);
          var chunk = await rpc.request("read", { transferId: id, offset: offset });
          if ((chunk.data || "").length > 44000) throw new Error("Invalid download chunk");
          var raw = atob(chunk.data || "");
          if (raw.length > CHUNK || offset + raw.length > bytes.length || (!raw.length && !chunk.done)) throw new Error("Invalid download size");
          for (var i = 0; i < raw.length; i++) bytes[offset + i] = raw.charCodeAt(i);
          offset += raw.length; progress(offset, bytes.length);
          if (chunk.done) {
            if (offset !== bytes.length || await digest(bytes) !== chunk.sha256) throw new Error("Download verification failed");
            break;
          }
        }
        check(runEpoch);
        var url = URL.createObjectURL(new Blob([bytes]));
        var a = document.createElement("a"); a.href = url; a.download = entry.name; a.click();
        setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
        id = null; status.textContent = "Download ready: " + entry.name;
      } finally { await cancelTransfer(id, runEpoch); }
    }
    async function upload(file) {
      if (file.size > LIMIT) throw new Error("Web uploads support files up to 64 MB. Use BossTerm desktop for larger files.");
      var id = null, runEpoch = epoch;
      try {
        var bytes = new Uint8Array(await file.arrayBuffer()), hash = await digest(bytes);
        check(runEpoch);
        var begin;
        try { begin = await rpc.request("upload", { path: join(file.name), size: bytes.length }); }
        catch (e) {
          if (e.message !== "File already exists" || !window.confirm('Replace "' + file.name + '" in the host folder?')) throw e;
          check(runEpoch);
          begin = await rpc.request("upload", { path: join(file.name), size: bytes.length, overwrite: true });
        }
        id = begin.transferId;
        for (var offset = 0; offset < bytes.length; offset += CHUNK) {
          check(runEpoch);
          var part = bytes.subarray(offset, Math.min(offset + CHUNK, bytes.length));
          var data = ""; for (var i = 0; i < part.length; i++) data += String.fromCharCode(part[i]);
          var reply = await rpc.request("write", { transferId: id, offset: offset, data: btoa(data) });
          if (reply.size !== offset + part.length) throw new Error("Invalid upload acknowledgement");
          progress(offset + part.length, bytes.length);
        }
        check(runEpoch);
        await rpc.request("finish", { transferId: id, sha256: hash });
        id = null; status.textContent = "Uploaded: " + file.name;
      } finally { await cancelTransfer(id, runEpoch); }
    }
    function requestControl() {
      return new Promise(function (resolve, reject) {
        var timer = setTimeout(function () { controlWait = null; reject(new Error("Control request timed out")); }, 130000);
        controlWait = function (granted) {
          clearTimeout(timer); controlWait = null;
          if (granted) resolve(); else reject(new Error("Control was not approved"));
        };
        status.textContent = "Waiting for the host to approve control…";
        send({ t: "requestControl" });
      });
    }
    function open(uploadRequested) {
      if (!available || busy) return;
      if (!dialog.open) dialog.showModal();
      run(async function () {
        if (uploadRequested && !hasControl()) await requestControl();
        await access(); await refresh(false);
        if (uploadRequested) {
          // Permission replies are asynchronous: browsers require a fresh user gesture
          // to open their picker. Keep the Choose files button visible and explicit.
          status.textContent = "Choose files from this device to upload into the folder shown above.";
          el("files-choose").focus();
        }
      });
    }
    el("files-upload").onclick = function () { open(true); };
    el("files-choose").onclick = function () {
      if (busy) return;
      if (!hasControl()) { open(true); return; }
      picker.value = ""; picker.click();
    };
    picker.onchange = function () {
      var files = Array.from(picker.files || []);
      run(async function () {
        await access();
        for (var i = 0; i < files.length; i++) { if (cancelled) break; await upload(files[i]); }
        await refresh(false);
      });
    };
    el("files-browse").onclick = function () { open(false); };
    el("files-up").onclick = function () { if (!busy) run(async function () { path = path.split("/").slice(0, -1).join("/"); await refresh(false); }); };
    el("files-refresh").onclick = function () { run(function () { return refresh(false); }); };
    el("files-more").onclick = function () { run(function () { return refresh(true); }); };
    el("files-hidden").onchange = function () { run(function () { return refresh(false); }); };
    el("files-cancel").onclick = function () { cancelled = true; status.textContent = "Cancelling…"; if (controlWait) controlWait(false); };
    el("files-close").onclick = function () { if (busy) { cancelled = true; if (controlWait) controlWait(false); } else dialog.close(); };
    dialog.addEventListener("cancel", function (event) { if (busy) { event.preventDefault(); cancelled = true; if (controlWait) controlWait(false); } });
    return {
      receive: rpc.receive,
      control: function (granted) { if (controlWait) controlWait(granted); },
      layout: function (m) {
        available = !!m.filesAvailable && !!(root.crypto && root.crypto.subtle);
        el("files-actions").hidden = !available;
      },
      button: function (uploadRequested) {
        if (!available) return null;
        var b = button(uploadRequested ? "↑" : "▱", function () { open(uploadRequested); });
        b.textContent = "";
        var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24"); svg.setAttribute("width", "16"); svg.setAttribute("height", "16");
        svg.setAttribute("fill", "none"); svg.setAttribute("stroke", "currentColor"); svg.setAttribute("stroke-width", "1.8");
        var icon = document.createElementNS("http://www.w3.org/2000/svg", "path");
        icon.setAttribute("d", uploadRequested ? "M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6" : "M3 6h7l2 3h9v12H3Z");
        svg.appendChild(icon); b.appendChild(svg);
        b.className = "splitbtn"; b.title = uploadRequested ? "Upload files" : "Browse files";
        b.setAttribute("aria-label", b.title); return b;
      },
      disconnect: function () {
        epoch++; cancelled = true; available = false; rpc.disconnect();
        if (controlWait) controlWait(false);
        dialog.close(); el("files-actions").hidden = true; path = "";
      }
    };
  }
  root.BossTermFiles = { mount: mount, createRpc: createRpc };
})(typeof window !== "undefined" ? window : globalThis);
