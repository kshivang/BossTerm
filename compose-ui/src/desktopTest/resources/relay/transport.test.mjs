import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync, randomBytes, hkdfSync, createCipheriv, sign} from 'node:crypto';
import {RelayTransport, RelayGraphicsGate} from '../../../desktopMain/resources/share-viewer/relay-transport.mjs';
const room = '11111111-1111-4111-8111-111111111111';
class Socket {
  bufferedAmount = 0; sent = []; closed = null;
  send(text) { this.sent.push(JSON.parse(text)); }
  close(code, reason) { this.closed = code; this.onclose?.({code, reason}); }
}
function packet(value, binary = true) {
  return JSON.stringify({id:crypto.randomUUID(), part:0, count:1, binary, data:Buffer.from(JSON.stringify(value)).toString('base64url')});
}
function setup(apply, visibility = () => ({visible:new Set(['pane']), focused:'pane'})) {
  const relay = new RelayTransport({endpoint:'wss://relay.example', room, token:'token', decrypt:async bytes => new TextDecoder().decode(bytes), apply, visibility, WebSocketClass:Socket});
  relay.onmessage = async () => {};
  const emit = async message => { relay.socket.onmessage({data:JSON.stringify(message)}); await relay.chain; };
  return {relay, emit};
}
function publisher() {
  const root = randomBytes(32), epoch = crypto.randomUUID();
  const identity = generateKeyPairSync('ed25519');
  const key = {epoch, key:root.toString('base64url'), hostPublicKey:identity.publicKey.export({type:'spki', format:'der'}).toString('base64url')};
  return {key, output(seq, data) {
    const frame = {room, pane:'pane', epoch, kind:'live', seq};
    const aad = Buffer.from(`bossterm-relay-v1\n${room}\npane\n${epoch}\nlive\n${seq}\n`);
    const nonce = Buffer.alloc(12); nonce.writeBigUInt64BE(BigInt(seq), 4);
    const cipher = createCipheriv('aes-256-gcm', hkdfSync('sha256', root, epoch, 'bossterm-relay-v1/live', 32), nonce);
    cipher.setAAD(aad);
    const bytes = Buffer.concat([cipher.update(JSON.stringify({t:'paneOutput', paneId:'pane', data})), cipher.final(), cipher.getAuthTag()]);
    return {...frame, op:'output', payload:bytes.toString('base64url'), signature:sign(null, Buffer.concat([aad, bytes]), identity.privateKey).toString('base64url')};
  }};
}
async function admit(emit) {
  await emit({op:'welcome', v:1});
  await emit({op:'signal', payload:packet({t:'kex', v:1}, false)});
  await emit({op:'grant', panes:['pane']});
}

test('browser relay waits for rendered output before acknowledging and rejects private replay', async () => {
  let release, started;
  const rendering = new Promise(resolve => { release = resolve; });
  const applied = new Promise(resolve => { started = resolve; });
  const messages = [];
  const {relay, emit} = setup(async message => { messages.push(message); if (message.t === 'paneOutput') { started(); await rendering; } });
  try {
    await admit(emit); relay.subscribeNext();
    const host = publisher();
    const snapshot = {room, pane:'pane', epoch:host.key.epoch, sequence:0, key:host.key, screen:JSON.stringify({t:'paneSnapshot', paneId:'pane', data:'initial'})};
    const frame = {op:'snapshot', pane:'pane', epoch:host.key.epoch, seq:0, payload:packet({sequence:1, payload:JSON.stringify(snapshot)})};
    await emit(frame);
    const delivery = emit({op:'frames', delivery:1, messages:[host.output(1, ' next')]});
    await applied;
    assert.equal(relay.socket.sent.filter(m => m.op === 'ack').length, 0);
    release(); await delivery;
    assert.equal(relay.socket.sent.at(-1).through, 1);
    assert.deepEqual(messages.filter(m => m.t !== 'paneGraphics').map(m => m.data), ['initial', ' next']);
    await emit(frame);
    assert.equal(relay.socket.closed, 4000);
    assert.equal(messages.length, 3);
  } finally { release(); relay.close(); }
});

test('browser subscriptions hide background panes and restore them without replaying input', async () => {
  let visible = true;
  const {relay, emit} = setup(async () => {}, () => ({visible:new Set(visible ? ['pane'] : []), focused:null, mode:'preview', fps:7}));
  try {
    await admit(emit); relay.subscribeNext();
    assert.deepEqual(relay.socket.sent.at(-1), {op:'subscribe', pane:'pane', mode:'preview', fps:7});
    visible = false; relay.subscribeNext();
    assert.equal(relay.socket.sent.at(-1).mode, 'hidden');
    visible = true; relay.subscribeNext();
    assert.equal(relay.socket.sent.at(-1).mode, 'preview');
    assert.equal(JSON.parse(relay.relayWrap({t:'hello'})).capabilities.includes('relay-v1'), true);
    assert.equal(JSON.parse(relay.relayWrap({t:'input', data:'x'})).sequence, 1);
    assert.equal(JSON.parse(relay.relayWrap({t:'input', data:'y'})).sequence, 2);
  } finally { relay.close(); }
});

test('browser relay refuses out of order private packets and remote plaintext endpoints', async () => {
  assert.throws(() => setupOrigin('ws://relay.example'));
  const {relay, emit} = setup(async () => {});
  try {
    await emit({op:'welcome', v:1});
    await emit({op:'signal', payload:JSON.stringify({id:'id', part:1, count:2, binary:true, data:''})});
    assert.equal(relay.socket.closed, 4000);
  } finally { relay.close(); }
});
function setupOrigin(endpoint) { return new RelayTransport({endpoint, room, WebSocketClass:Socket}); }

test('large private rasters acknowledge bounded fragments and apply only after complete reassembly', async () => {
  const applied = [];
  const {relay, emit} = setup(async message => applied.push(message));
  try {
    await admit(emit);
    await relay.graphics.snapshot({t:'paneSnapshot', paneId:'pane', data:'screen', graphicsSequence:1});
    applied.length = 0;
    assert.ok(JSON.parse(relay.relayWrap({t:'hello'})).capabilities.includes('paneGraphicsV1'));
    const image = {t:'paneGraphics', paneId:'pane', revision:1, full:true, relaySequence:1,
      images:[{id:'image', mimeType:'image/png', data:'x'.repeat(2 * 1024 * 1024)}]};
    const bytes = Buffer.from(JSON.stringify({sequence:1, payload:JSON.stringify(image)}));
    const id = crypto.randomUUID(), count = Math.ceil(bytes.length / 24576);
    for (let part = 0; part < count; part++) {
      await emit({op:'signal', payload:JSON.stringify({id, part, count, binary:true, credit:true,
        data:bytes.subarray(part * 24576, (part + 1) * 24576).toString('base64url')})});
      const receipt = relay.socket.sent.filter(message => message.op === 'peerCredit').at(-1);
      assert.deepEqual(JSON.parse(receipt.payload), {credit:id, part});
      assert.equal(applied.length, part === count - 1 ? 1 : 0);
      assert.equal(relay.socket.closed, null);
    }
    assert.equal(applied[0].images[0].data.length, 2 * 1024 * 1024);
    assert.equal(relay.fragment, null);
    assert.equal(relay.pendingBytes, 0);
  } finally { relay.close(); }
});

test('graphics barriers order independent lanes and restore previews that overtake older raster delivery', async () => {
  const applied = [], resyncs = [];
  const gate = new RelayGraphicsGate({apply:async message => applied.push(message), resync:pane => resyncs.push(pane)});
  await gate.output({t:'paneRepaint', paneId:'pane', data:'screen', graphicsSequence:1});
  await gate.output({t:'paneOutput', paneId:'pane', data:'after'});
  await gate.output({t:'paneOutput', paneId:'other', data:'independent'});
  assert.deepEqual(applied.map(message => message.data), ['screen', 'independent']);
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:1, revision:1, images:[]});
  assert.deepEqual(applied.map(message => message.t), ['paneRepaint', 'paneOutput', 'paneGraphics', 'paneOutput']);
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:3, revision:3, images:[]});
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'latest', graphicsSequence:3}, true);
  assert.equal(applied.at(-1).relaySequence, 3, 'private graphics may arrive before the downsampled complete preview');
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:2, revision:2, images:[]});
  assert.equal(gate.panes.get('pane').graphics.size, 0, 'late graphics from discarded previews must not accumulate');
  assert.deepEqual(resyncs, []);
});

test('graphics barrier overflow and expiry resnapshot only the slow pane', async () => {
  let now = 0;
  const resyncs = [], applied = [];
  const gate = new RelayGraphicsGate({apply:async message => applied.push(message), resync:pane => resyncs.push(pane), now:() => now});
  await gate.output({t:'paneRepaint', paneId:'slow', data:'', graphicsSequence:1});
  await gate.output({t:'paneOutput', paneId:'slow', data:'x'.repeat(512 * 1024 + 1)});
  assert.deepEqual(resyncs, ['slow']);
  await gate.output({t:'paneOutput', paneId:'other', data:'healthy'});
  assert.equal(applied.at(-1).data, 'healthy');
  await gate.snapshot({t:'paneSnapshot', paneId:'slow', data:'restored', graphicsSequence:2});
  now = 120001; gate.expire();
  assert.deepEqual(resyncs, ['slow', 'slow']);
  assert.equal(gate.graphicsBytes, 0);
});

test('only delivered image previews request private graphics; text-only previews clear old overlays locally', async () => {
  const requests = [], applied = [];
  const gate = new RelayGraphicsGate({apply:async message => applied.push(message), resync:() => {},
    request:(pane, sequence) => requests.push({pane, sequence})});
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'text'}, true);
  assert.equal(applied.at(-1).t, 'paneGraphics');
  assert.deepEqual(applied.at(-1).cells, []);
  assert.deepEqual(requests, []);
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'image preview', graphicsSequence:5}, true);
  assert.deepEqual(requests, [{pane:'pane', sequence:5}]);
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:6, revision:6, full:true, images:[]});
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'newer preview', graphicsSequence:6}, true);
  assert.equal(requests.length, 1, 'pre-arrived matching graphics needs no request');
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:5, revision:5, full:true, images:[]});
  assert.equal(applied.at(-1).relaySequence, 6);
  assert.equal(requests.length, 1);
});

test('slow image previews finish one transfer and coalesce only the latest complete screen', async () => {
  const requests = [], applied = [];
  let now = 0;
  const gate = new RelayGraphicsGate({apply:async message => applied.push(message), resync:() => {},
    request:(pane, sequence) => requests.push(sequence), now:() => now});
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'first', graphicsSequence:1}, true);
  now = 20000;
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'middle', graphicsSequence:2}, true);
  await gate.snapshot({t:'paneSnapshot', paneId:'pane', data:'latest', graphicsSequence:3}, true);
  assert.deepEqual(requests, [1]);
  assert.equal(gate.panes.get('pane').since, 0, 'new previews must not extend a stalled barrier lifetime');
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:1, revision:1, full:true, images:[]});
  assert.deepEqual(requests, [1, 3]);
  assert.deepEqual(applied.filter(message => message.t === 'paneSnapshot').map(message => message.data), ['first', 'latest']);
  await gate.graphics({t:'paneGraphics', paneId:'pane', relaySequence:3, revision:3, full:true, images:[]});
  assert.equal(applied.at(-1).relaySequence, 3);
});
