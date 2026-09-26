import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {RelayOutputReceiver} from '../../../desktopMain/resources/share-viewer/relay-crypto.mjs';
const vector=JSON.parse(await readFile(new URL('./output-vector.json',import.meta.url)));
test('browser verifier decrypts interoperability vector and rejects replay and forgery',async()=>{
 const {grant,frame,plaintext}=vector;
 const receiver=await RelayOutputReceiver.create(frame.room,frame.pane,grant);
 await assert.rejects(receiver.decrypt({...frame,signature:frame.signature.replace(/^./,frame.signature[0]==='a'?'b':'a')}));
 assert.equal(await receiver.decrypt(frame),plaintext);
 await assert.rejects(receiver.decrypt(frame));
});
