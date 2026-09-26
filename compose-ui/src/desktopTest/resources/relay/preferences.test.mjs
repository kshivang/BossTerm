import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const viewer = readFileSync(new URL('../../../desktopMain/resources/share-viewer/viewer.js', import.meta.url), 'utf8');
const preferencesScript = viewer.slice(viewer.indexOf('  var relayPreferences ='), viewer.indexOf('  var canE2E ='));
function harness() {
  let receive;
  const parent = {};
  const window = {parent, addEventListener(type, callback) { assert.equal(type, 'message'); receive = callback; }};
  const current = new Function('window', 'LIVE_SESSIONS_ORIGINS', preferencesScript + '\nreturn () => relayPreferences;')(window, ['https://cli.risaboss.com']);
  return {current, receive: (value, origin = 'https://cli.risaboss.com', source = parent) => receive({source, origin, data:{type:'bossterm-terminal-preferences', preferences:value}})};
}
test('preferences require the allowed parent window and origin', () => {
  const {current, receive} = harness();
  const value = {unfocused_mode:'preview', unfocused_fps:7, revision:2};
  receive(value, 'https://attacker.example');
  receive(value, 'https://cli.risaboss.com', {});
  assert.equal(current().unfocused_mode, 'batch');
  receive(value);
  assert.deepEqual(current(), value);
});
test('invalid rates modes and revisions never affect subscriptions', () => {
  const {current, receive} = harness();
  const defaults = current();
  for (const change of [{unfocused_mode:'other'}, {unfocused_fps:0}, {unfocused_fps:31}, {unfocused_fps:1.5}, {revision:-1}, {revision:Number.MAX_SAFE_INTEGER + 1}]) {
    receive({...defaults, ...change});
    assert.deepEqual(current(), defaults);
  }
});
