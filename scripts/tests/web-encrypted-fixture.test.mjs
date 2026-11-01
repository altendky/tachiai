// Owned fixture structure and failure handling only; no provider media or keys.
import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { ENCRYPTED_FIXTURE_SOURCE, prepareEncryptedFixture, verifyEncryptedFragment } from '../../apps/web/scripts/prepare-encrypted-fixture.mjs';

const word = number => { const value = Buffer.alloc(4); value.writeUInt32BE(number); return value; };
const box = (type, ...parts) => { const body = Buffer.concat(parts); return Buffer.concat([word(body.length + 8), Buffer.from(type), body]); };
function fragment({ missing = false, duplicate = false, samples = 2, auxiliarySamples = samples, offsetDelta = 0, sizeDelta = 0 } = {}) {
  const trun = box('trun', word(0), word(samples));
  const senc = box('senc', word(0), word(auxiliarySamples), Buffer.alloc(auxiliarySamples * 8));
  const firstIvOffset = 8 + 8 + trun.length + 16;
  const saio = box('saio', word(0), word(1), word(firstIvOffset + offsetDelta));
  const saiz = box('saiz', word(0), Buffer.from([8 + sizeDelta]), word(auxiliarySamples));
  return Buffer.concat([box('moof', box('traf', trun, ...(missing ? [] : [senc, saio, saiz]), ...(duplicate ? [saiz] : []))),
    box('mdat', Buffer.from('senc saio saiz are not boxes inside media bytes'))]);
}

test('fragment verification requires actual nested CENC metadata with matching samples, sizes and offsets', () => {
  assert.doesNotThrow(() => verifyEncryptedFragment(fragment()));
  for (const value of [fragment({ missing: true }), fragment({ duplicate: true }), fragment({ auxiliarySamples: 4 }),
    fragment({ offsetDelta: 1 }), fragment({ sizeDelta: 1 }), fragment({ samples: 0 }), fragment().subarray(1), Buffer.alloc(2 * 1024 * 1024 + 1)]) {
    assert.throws(() => verifyEncryptedFragment(value), /fragmented CENC metadata missing or invalid/);
  }
  // FFmpeg8.0 emitted correct first-fragment counts but accumulated prior
  // auxiliary entries in later fragments. A first-fragment-only probe misses it.
  assert.doesNotThrow(() => verifyEncryptedFragment(fragment({ samples: 48 })));
  assert.throws(() => verifyEncryptedFragment(fragment({ samples: 48, auxiliarySamples: 96 })));
});

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const fixtureParent = process.env.TACHIAI_ROUTE_TEST_ROOT || tmpdir();
function ownedRoot() {
  mkdirSync(fixtureParent, { recursive: true });
  const root = mkdtempSync(join(fixtureParent, 'encrypted-fixture-'));
  const source = join(root, ENCRYPTED_FIXTURE_SOURCE.path);
  mkdirSync(dirname(source), { recursive: true });
  copyFileSync(join(repository, ENCRYPTED_FIXTURE_SOURCE.path), source);
  copyFileSync(join(dirname(join(repository, ENCRYPTED_FIXTURE_SOURCE.path)), 'media.json'), join(dirname(source), 'media.json'));
  return { root, source };
}

test('pinned source mismatch refuses before creating output or invoking any packager', () => {
  const { root, source } = ownedRoot();
  const previous = process.env.FFMPEG_ENCRYPTED;
  process.env.FFMPEG_ENCRYPTED = join(root, 'PRIVATE_MUST_NOT_EXECUTE');
  try {
    const input = readFileSync(source); input[input.length - 1] ^= 1; writeFileSync(source, input);
    const output = join(root, 'output');
    assert.throws(() => prepareEncryptedFixture(root, output), /input hash, size or stream binding mismatch/);
    assert.equal(existsSync(output), false);
    writeFileSync(source, input.subarray(1));
    assert.throws(() => prepareEncryptedFixture(root, output), /input hash, size or stream binding mismatch/);
    assert.equal(existsSync(output), false);
  } finally {
    if (previous === undefined) delete process.env.FFMPEG_ENCRYPTED; else process.env.FFMPEG_ENCRYPTED = previous;
    rmSync(root, { recursive: true, force: true });
  }
});

test('encrypted packager is separate from ordinary FFMPEG and failure removes only its new destination', () => {
  const { root } = ownedRoot(), output = join(root, 'output');
  const previous = { ordinary: process.env.FFMPEG, encrypted: process.env.FFMPEG_ENCRYPTED };
  delete process.env.FFMPEG_ENCRYPTED;
  process.env.FFMPEG = join(root, 'PRIVATE_ORDINARY_PACKAGER');
  mkdirSync(join(output, 'media'), { recursive: true });
  writeFileSync(join(output, 'media/keep'), 'preserve');
  try {
    assert.throws(() => prepareEncryptedFixture(root, output), /packager unavailable; run scripts\/build-web-fixture-ffmpeg.sh or set FFMPEG_ENCRYPTED/);
    assert.equal(existsSync(join(output, 'media/encrypted')), false);
    assert.equal(readFileSync(join(output, 'media/keep'), 'utf8'), 'preserve');
    const fake = join(root, 'owned-failing-packager');
    writeFileSync(fake, '#!/bin/sh\nif [ "$1" = "-version" ]; then printf "ffmpeg version 8.1 owned\\n"; exit 0; fi\nexit 1\n', { mode: 0o700 });
    process.env.FFMPEG_ENCRYPTED = fake;
    assert.throws(() => prepareEncryptedFixture(root, output), /fixture packaging failed/);
    assert.equal(existsSync(join(output, 'media/encrypted')), false);
    assert.equal(readFileSync(join(output, 'media/keep'), 'utf8'), 'preserve');
  } finally {
    for (const [name, value] of [['FFMPEG', previous.ordinary], ['FFMPEG_ENCRYPTED', previous.encrypted]]) {
      if (value === undefined) delete process.env[name]; else process.env[name] = value;
    }
    rmSync(root, { recursive: true, force: true });
  }
});

test('packager build cache accepts only the exact source, recipe, platform and binary marker', () => {
  mkdirSync(fixtureParent, { recursive: true }); const root = mkdtempSync(join(fixtureParent, 'encrypted-packager-cache-'));
  const script = readFileSync(join(repository, 'scripts/build-web-fixture-ffmpeg.sh'));
  const helper = join(root, 'scripts/build-web-fixture-ffmpeg.sh'), tools = join(root, 'apps/web/build/tools');
  const prefix = join(tools, 'ffmpeg-8.1'), binary = join(prefix, 'bin/ffmpeg');
  mkdirSync(dirname(helper), { recursive: true }); mkdirSync(dirname(binary), { recursive: true });
  writeFileSync(helper, script); writeFileSync(binary, '#!/bin/sh\nexit 1\n', { mode: 0o700 });
  const sha = bytes => createHash('sha256').update(bytes).digest('hex');
  const platform = ['-s', '-m'].map(option => spawnSync('uname', [option], { encoding: 'utf8' }).stdout.trim()).join('-');
  const marker = 'tachiai-owned-ffmpeg-v1\nversion=8.1\nsource_sha256=b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a\n' +
    'recipe_sha256=' + sha(script) + '\nplatform=' + platform + '\nbinary_sha256=' + sha(readFileSync(binary)) + '\n';
  writeFileSync(join(prefix, '.fixture-build'), marker);
  const env = { ...process.env }; delete env.FFMPEG_ENCRYPTED;
  try {
    const cached = spawnSync('bash', [helper], { encoding: 'utf8', env, timeout: 5000 });
    assert.equal(cached.status, 0, cached.stderr);
    // A mismatched binary must not execute, silently rebuild or remove material.
    writeFileSync(binary, '#!/bin/sh\nprintf PRIVATE_MUST_NOT_EXECUTE\nexit 0\n', { mode: 0o700 });
    const refused = spawnSync('bash', [helper], { encoding: 'utf8', env, timeout: 5000 });
    assert.equal(refused.status, 1); assert.match(refused.stderr, /cache marker mismatch/);
    assert.equal(refused.stdout.includes('PRIVATE'), false); assert.equal(existsSync(binary), true);
    assert.equal(readFileSync(join(prefix, '.fixture-build'), 'utf8'), marker);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
