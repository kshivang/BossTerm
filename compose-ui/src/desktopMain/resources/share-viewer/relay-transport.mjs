import {RelayOutputReceiver} from './relay-crypto.mjs';

const encoder = new TextEncoder(), decoder = new TextDecoder();
const decode = value => Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
const encode = bytes => {
  let text = '';
  for (let i = 0; i < bytes.length; i += 8192) text += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const require = (condition, message) => { if (!condition) throw Error(message); };
const MAX_FRAME = 1024 * 1024;
const MAX_PRIVATE_FRAME = 32 * MAX_FRAME;

/** Match private rasters with their signed text barrier; a slow pane never stalls other panes. */
export class RelayGraphicsGate {
  constructor({apply, resync, request = () => {}, now = Date.now}) {
    this.apply = apply; this.resync = resync; this.now = now;
    this.request = request;
    this.panes = new Map(); this.graphicsBytes = 0;
  }
  state(pane) {
    if (!this.panes.has(pane)) this.panes.set(pane, {barrier:null, applied:-1, output:[], graphics:new Map(), outputBytes:0, graphicsBytes:0, since:this.now()});
    return this.panes.get(pane);
  }
  reset(pane) {
    const state = this.panes.get(pane);
    if (state) this.graphicsBytes -= state.graphicsBytes;
    this.panes.delete(pane);
  }
  async snapshot(message, preserve = false) {
    require(message.graphicsSequence == null || (Number.isSafeInteger(message.graphicsSequence) && message.graphicsSequence >= 0), 'Invalid graphics barrier');
    const current = this.panes.get(message.paneId);
    if (preserve && message.graphicsSequence != null && current?.barrier != null) {
      // Finish the current image before asking for the latest preview; otherwise a large raster
      // can perpetually lose its frame to a newer preview and never become visible.
      if (encoder.encode(message.data || '').length > 512 * 1024) {
        this.reset(message.paneId); this.resync(message.paneId); return;
      }
      current.latestPreview = message;
      return;
    }
    const retained = preserve && this.panes.get(message.paneId)?.graphics.get(message.graphicsSequence);
    this.reset(message.paneId);
    const state = this.state(message.paneId);
    await this.apply(message);
    state.barrier = message.graphicsSequence ?? null;
    if (state.barrier == null) await this.apply({t:'paneGraphics', paneId:message.paneId, revision:0, full:true,
      images:[], cells:[], requiredImageIds:[], removedImageIds:[]});
    if (retained) this.store(state, retained);
    await this.drain(state);
    if (preserve && state.barrier != null) await this.request(message.paneId, state.barrier);
  }
  async output(message) {
    const state = this.state(message.paneId), bytes = encoder.encode(message.data || '').length;
    if (state.outputBytes + bytes > 512 * 1024 || state.output.length >= 256) {
      this.reset(message.paneId); this.resync(message.paneId); return;
    }
    state.output.push(message); state.outputBytes += bytes;
    await this.drain(state);
  }
  size(message) { return (message.images || []).reduce((sum, image) => sum + image.data.length, 0) + (message.cells || []).length * 128 + 4096; }
  store(state, message) {
    const size = this.size(message);
    state.graphics.set(message.relaySequence, message); state.graphicsBytes += size; this.graphicsBytes += size;
  }
  async graphics(message) {
    const sequence = message.relaySequence;
    if (sequence == null) return;
    require(Number.isSafeInteger(sequence) && sequence >= 0, 'Invalid graphics barrier');
    const state = this.state(message.paneId);
    if (sequence <= state.applied || state.graphics.has(sequence) || (state.barrier != null && sequence < state.barrier)) return;
    if (this.graphicsBytes + this.size(message) > MAX_PRIVATE_FRAME || state.graphics.size >= 32) {
      this.reset(message.paneId); this.resync(message.paneId); return;
    }
    this.store(state, message);
    await this.drain(state);
  }
  async drain(state) {
    while (true) {
      if (state.barrier != null) {
        const images = state.graphics.get(state.barrier);
        if (!images) return;
        state.graphics.delete(state.barrier);
        const size = this.size(images); state.graphicsBytes -= size; this.graphicsBytes -= size;
        await this.apply(images);
        if (images.resyncRequired) return;
        state.applied = state.barrier; state.barrier = null;
        if (state.latestPreview) {
          const latest = state.latestPreview; state.latestPreview = null;
          await this.snapshot(latest, true);
          return;
        }
      }
      const message = state.output.shift();
      if (!message) { state.since = this.now(); return; }
      state.outputBytes -= encoder.encode(message.data || '').length;
      await this.apply(message);
      if (message.graphicsSequence != null) {
        require(Number.isSafeInteger(message.graphicsSequence) && message.graphicsSequence >= 0, 'Invalid graphics barrier');
        state.barrier = message.graphicsSequence; state.since = this.now();
      }
    }
  }
  expire() {
    for (const [pane, state] of this.panes) if ((state.barrier != null || state.graphics.size) && this.now() - state.since > 120000) {
      this.reset(pane); this.resync(pane);
    }
  }
}

/** WebSocket-shaped adapter. The existing viewer owns key exchange, approval and rendering. */
export class RelayTransport {
  constructor({endpoint, room, token, decrypt, apply, visibility, requestGraphics = () => {}, WebSocketClass = WebSocket}) {
    const url = new URL(endpoint);
    require(url.protocol === 'wss:' && !url.username && !url.password && !url.search && !url.hash && url.pathname === '/', 'Invalid relay origin');
    require(/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(room), 'Invalid relay room');
    this.room = room; this.token = token; this.decrypt = decrypt; this.apply = apply; this.visibility = visibility;
    this.readyState = 0; this.sent = 0; this.received = 0; this.delivery = 0; this.pendingBytes = 0;
    this.receivers = new Map(); this.granted = new Set(); this.waiting = new Set(); this.subscriptions = new Map();
    this.graphics = new RelayGraphicsGate({apply, resync:pane => this.resync(pane), request:requestGraphics});
    this.chain = Promise.resolve(); this.fragment = null; this.first = true; this.handshake = false;
    this.socket = new WebSocketClass(url.origin + '/v1/rooms/' + room);
    this.socket.onopen = () => this.wire({op:'hello', v:1, room});
    this.socket.onmessage = event => {
      const size = typeof event.data === 'string' ? encoder.encode(event.data).length : MAX_FRAME + 1;
      this.pendingBytes += size;
      if (size > MAX_FRAME || this.pendingBytes > 2 * MAX_FRAME) { this.close(1009, 'Relay backlog exceeded'); return; }
      this.chain = this.chain.then(async () => {
        if (this.readyState === 3) return;
        const message = JSON.parse(event.data);
        if (message.op === 'frames') {
          require(message.delivery === this.delivery + 1 && Array.isArray(message.messages) && message.messages.length <= 2048, 'Relay delivery gap');
          for (const item of message.messages) await this.message(item);
          this.delivery = message.delivery;
          this.wire({op:'ack', through:this.delivery});
        } else await this.message(message);
      }).catch(() => this.close(1008, 'Relay verification failed')).finally(() => { this.pendingBytes -= size; });
    };
    this.socket.onerror = event => { if (this.onerror) this.onerror(event); };
    this.socket.onclose = event => {
      this.readyState = 3; clearInterval(this.timer);
      if (this.onclose) this.onclose(event);
    };
    this.timer = setInterval(() => this.subscribeNext(), 25);
  }
  wire(message) {
    const text = JSON.stringify(message);
    require(encoder.encode(text).length <= MAX_FRAME && this.socket.bufferedAmount <= 2 * MAX_FRAME, 'Relay send queue full');
    this.socket.send(text);
  }
  relayWrap(message) {
    if (message.t === 'hello') return JSON.stringify({...message, capabilities:['filesV1', 'relay-v1', 'paneGraphicsV1']});
    return JSON.stringify({sequence:++this.sent, payload:JSON.stringify(message)});
  }
  send(data) {
    const binary = typeof data !== 'string';
    const bytes = binary ? new Uint8Array(data) : encoder.encode(data);
    require(bytes.length <= MAX_FRAME, 'Private message too large');
    const count = Math.max(1, Math.ceil(bytes.length / 24576)), id = crypto.randomUUID();
    for (let part = 0; part < count; part++) {
      const packet = {id, part, count, binary, data:encode(bytes.subarray(part * 24576, (part + 1) * 24576))};
      if (this.first && part === 0) packet.token = this.token;
      this.wire({op:'signal', payload:JSON.stringify(packet)});
    }
    this.first = false;
  }
  async message(message) {
    if (message.op === 'welcome') {
      require(this.readyState === 0 && message.v === 1, 'Unsupported relay version');
      this.readyState = 1;
      if (this.onopen) this.onopen();
    } else if (message.op === 'grant') {
      require(Array.isArray(message.panes) && message.panes.length <= 1000, 'Invalid grant');
      this.granted = new Set(message.panes);
      for (const pane of this.receivers.keys()) if (!this.granted.has(pane)) this.receivers.delete(pane);
      for (const pane of this.subscriptions.keys()) if (!this.granted.has(pane)) this.subscriptions.delete(pane);
      for (const pane of this.graphics.panes.keys()) if (!this.granted.has(pane)) this.graphics.reset(pane);
    } else if (message.op === 'signal' || message.op === 'snapshot') {
      const packet = JSON.parse(message.payload);
      require(typeof packet.id === 'string' && packet.id.length <= 64 && Number.isInteger(packet.count) && packet.count >= 1 && packet.count <= Math.ceil(MAX_PRIVATE_FRAME / 24576) && Number.isInteger(packet.part), 'Invalid private packet');
      require(typeof packet.data === 'string' && packet.data.length <= 1398104, 'Private message too large');
      if (!this.fragment) this.fragment = {id:packet.id, count:packet.count, binary:packet.binary, part:0, chunks:[], size:0};
      const f = this.fragment;
      require(f.id === packet.id && f.count === packet.count && f.binary === packet.binary && f.part === packet.part && packet.part < packet.count, 'Private packet order');
      const chunk = decode(packet.data); f.size += chunk.length;
      require(f.size <= MAX_PRIVATE_FRAME, 'Private message too large');
      f.chunks.push(chunk); f.part++;
      if (packet.credit) this.wire({op:'peerCredit', payload:JSON.stringify({credit:packet.id, part:packet.part})});
      if (f.part !== f.count) return;
      this.fragment = null;
      const bytes = new Uint8Array(f.size); let offset = 0;
      for (const chunk of f.chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      if (!this.handshake) {
        require(!f.binary && message.op === 'signal', 'Encrypted handshake failed');
        require(this.onmessage, 'Missing key exchange handler');
        await this.onmessage({data:decoder.decode(bytes)});
        this.handshake = true;
        return;
      }
      require(f.binary, 'Encryption required');
      const envelope = JSON.parse(await this.decrypt(bytes.buffer));
      require(envelope.sequence === this.received + 1, 'Private message replay');
      this.received = envelope.sequence;
      const payload = JSON.parse(envelope.payload);
      if (message.op === 'snapshot') {
        require(payload.room === this.room && this.granted.has(payload.pane) && payload.pane === message.pane && payload.epoch === message.epoch && payload.sequence === message.seq && payload.key.epoch === payload.epoch, 'Snapshot boundary mismatch');
        const receiver = await RelayOutputReceiver.create(this.room, payload.pane, payload.key);
        receiver.applySnapshotBoundary(payload.sequence);
        const screen = JSON.parse(payload.screen);
        require(screen.t === 'paneSnapshot' && screen.paneId === payload.pane, 'Invalid pane snapshot');
        await this.graphics.snapshot(screen);
        this.receivers.set(payload.pane, receiver); this.waiting.delete(payload.pane);
      } else if (payload.t === 'paneGraphics') {
        require(this.granted.has(payload.paneId), 'Invalid graphics pane');
        if (!this.waiting.has(payload.paneId)) await this.graphics.graphics(payload);
      } else await this.apply(payload);
    } else if (message.op === 'output') {
      require(message.room === this.room && this.granted.has(message.pane), 'Invalid pane output');
      if (this.waiting.has(message.pane)) return;
      const receiver = this.receivers.get(message.pane);
      if (!receiver) { this.resync(message.pane); return; }
      let text;
      try { text = await receiver.decrypt(message); } catch (_) { this.resync(message.pane); return; }
      const output = JSON.parse(text);
      require(output.paneId === message.pane && (message.kind === 'live' ? ['paneOutput', 'paneRepaint'].includes(output.t) : output.t === 'paneSnapshot'), 'Invalid pane output');
      if (message.kind === 'preview') await this.graphics.snapshot(output, true);
      else await this.graphics.output(output);
    } else if (message.op === 'resync') this.resync(message.pane);
    else if (message.op === 'leave') this.close(1012, 'Relay host disconnected');
  }
  subscribeNext() {
    if (this.readyState !== 1) return;
    try {
      this.graphics.expire();
      const {visible, focused, mode = 'batch', fps = 4} = this.visibility();
      require(['batch','preview'].includes(mode) && Number.isInteger(fps) && fps >= 1 && fps <= 30, 'Invalid viewing preference');
      for (const pane of this.granted) {
        const next = !visible.has(pane) ? 'hidden' : pane === focused ? 'live' : mode;
        const previous = this.subscriptions.get(pane);
        if (!previous && next === 'hidden' || previous === next + ':' + fps) continue;
        this.subscriptions.set(pane, next + ':' + fps); this.waiting.add(pane);
        this.graphics.reset(pane);
        this.wire({op:'subscribe', pane, mode:next, fps});
        break; // Pace bursts below the relay's control rate limit.
      }
    } catch (_) { this.close(1008, 'Relay subscription failed'); }
  }
  resync(pane) {
    if (!this.granted.has(pane) || this.waiting.has(pane)) return;
    this.graphics.reset(pane);
    this.waiting.add(pane); this.wire({op:'resync', pane, payload:''});
  }
  close(code = 1000, reason = '') {
    this.readyState = 3; clearInterval(this.timer);
    // Browsers reserve protocol close codes; use an application code for local failures.
    this.socket.close(code === 1000 ? code : 4000, reason);
  }
}
