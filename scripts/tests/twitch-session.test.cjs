const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');
const root = resolve(__dirname, '../../apps/android/app/src/main/assets/twitch');
const source = readFileSync(resolve(root, 'session.js'), 'utf8');

test('session experiment uses official video-with-chat layout with manual playback', () => {
  const calls = [];
  runInNewContext(source, { URL, location: { href: 'https://appassets.androidplatform.net/assets/twitch/session.html?channel=bobross' },
    Twitch: { Embed: function(id, options) { calls.push({ id, options }); } } });
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [{ id: 'player', options: {
    width: '100%', height: '100%', parent: ['appassets.androidplatform.net'],
    channel: 'bobross', layout: 'video-with-chat', autoplay: false, muted: false,
  } }]);
  assert.doesNotMatch(source, /document\.|cookie|localStorage|sessionStorage|postMessage|console\.|getPlayer/);
});

test('missing SDK and malformed fixtures fail without creating an embed', () => {
  runInNewContext(source, { URL, location: { href: 'https://appassets.androidplatform.net/?channel=bobross' } });
  for (const channel of ['', 'ab', 'bad/channel', '<script>', 'x'.repeat(26)]) {
    runInNewContext(source, { URL, location: { href: 'https://appassets.androidplatform.net/?channel=' + encodeURIComponent(channel) },
      Twitch: { Embed: function() { assert.fail('invalid fixture reached SDK'); } } });
  }
});

test('wrapper limits scripts and frames to local assets and exact official SDK hosts', () => {
  const html = readFileSync(resolve(root, 'session.html'), 'utf8');
  assert.match(html, /script-src 'self' https:\/\/embed\.twitch\.tv;/);
  assert.match(html, /frame-src https:\/\/embed\.twitch\.tv https:\/\/player\.twitch\.tv;/);
  assert.match(html, /form-action 'none'/);
  assert.match(html, /src="https:\/\/embed\.twitch\.tv\/embed\/v1\.js"/);
  assert.doesNotMatch(html, /timing-tools|replay-coordinator|\*/);
});
