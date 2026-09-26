// v1 relay output verifier. The host key and pane key arrive only inside an
// authenticated pairwise channel; neither is trusted from relay routing metadata.
const encoder = new TextEncoder();
const decode = value => Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
const aad = frame => encoder.encode(`bossterm-relay-v1\n${frame.room}\n${frame.pane}\n${frame.epoch}\n${frame.kind}\n${frame.seq}\n`);
const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
export class RelayOutputReceiver {
  static async create(room, pane, grant) {
    if (![room, pane, grant.epoch].every(v => typeof v === 'string' && ID.test(v))) throw Error('Invalid stream identity');
    const secret = decode(grant.key);
    if (secret.length !== 32) throw Error('Invalid stream key');
    const receiver = new RelayOutputReceiver();
    receiver.room = room; receiver.pane = pane; receiver.epoch = grant.epoch;
    receiver.counters = {live: 0, preview: 0};
    receiver.host = await crypto.subtle.importKey('spki', decode(grant.hostPublicKey), 'Ed25519', false, ['verify']);
    const root = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
    receiver.keys = {};
    for (const kind of ['live', 'preview']) {
      receiver.keys[kind] = await crypto.subtle.deriveKey({name:'HKDF', hash:'SHA-256', salt:encoder.encode(grant.epoch), info:encoder.encode(`bossterm-relay-v1/${kind}`)}, root, {name:'AES-GCM', length:256}, false, ['decrypt']);
    }
    return receiver;
  }
  // The caller serializes receipt, snapshot application, and decryption in wire order.
  applySnapshotBoundary(sequence) {
    if (!Number.isSafeInteger(sequence) || sequence < this.counters.live) throw Error('Invalid snapshot boundary');
    this.counters.live = sequence;
  }
  async decrypt(frame) {
    if (frame.room !== this.room || frame.pane !== this.pane || frame.epoch !== this.epoch || !['live','preview'].includes(frame.kind)) throw Error('Invalid stream');
    const previous = this.counters[frame.kind];
    if (!Number.isSafeInteger(frame.seq) || frame.seq <= previous) throw Error('Replayed output');
    if (frame.kind === 'live' && frame.seq !== previous + 1) throw Error('Output gap; snapshot required');
    if (typeof frame.payload !== 'string' || frame.payload.length > 699076 || typeof frame.signature !== 'string' || frame.signature.length > 100) throw Error('Invalid frame');
    const encrypted = decode(frame.payload), metadata = aad(frame);
    if (encrypted.length < 16 || encrypted.length > 524304) throw Error('Invalid frame');
    const signed = new Uint8Array(metadata.length + encrypted.length);
    signed.set(metadata); signed.set(encrypted, metadata.length);
    if (!await crypto.subtle.verify('Ed25519', this.host, decode(frame.signature), signed)) throw Error('Invalid host signature');
    const nonce = new Uint8Array(12);
    new DataView(nonce.buffer).setBigUint64(4, BigInt(frame.seq));
    const plain = await crypto.subtle.decrypt({name:'AES-GCM', iv:nonce, additionalData:metadata}, this.keys[frame.kind], encrypted);
    this.counters[frame.kind] = frame.seq;
    return new TextDecoder().decode(plain);
  }
}
