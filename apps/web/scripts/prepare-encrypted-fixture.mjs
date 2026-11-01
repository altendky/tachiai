import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

// PUBLIC synthetic fixture values. These never describe provider media or keys.
export const ENCRYPTED_FIXTURE_KEY_HEX = '000102030405060708090a0b0c0d0e0f';
export const ENCRYPTED_FIXTURE_KID_HEX = '00112233445566778899aabbccddeeff';
export const ENCRYPTED_FIXTURE_KID_UUID = '00112233-4455-6677-8899-aabbccddeeff';
export const ENCRYPTED_FIXTURE_SOURCE = Object.freeze({
  path: 'apps/android/app/src/androidTest/assets/controlled-mirroring/a.mp4',
  sha256: '38f95dbc38432297f7106aa77e7eff30e7a94965545fc30b35403738455e660a',
  bytes: 2872166,
});

function verifyInitialization(data, sampleEntry) {
  // FFmpeg's owned init must contain a protected sample entry, its protection
  // container/scheme and the version-zero tenc with this exact public KID.
  const tenc = Buffer.from(`0000002074656e630000000000000108${ENCRYPTED_FIXTURE_KID_HEX}`, 'hex');
  if (data.length > 65536 || !data.includes(Buffer.from(sampleEntry)) ||
      !data.includes(Buffer.from('sinf')) || !data.includes(Buffer.from('schm')) ||
      !data.includes(Buffer.from('cenc')) || !data.includes(tenc)) {
    throw Error('Owned encrypted initialization verification failed.');
  }
}

function protectOwnedManifest(xml) {
  // This is deliberately a narrow transformation of our just-generated FFmpeg
  // fixture, never a parser/rewriter for supplied or provider manifests.
  if (xml.length > 65536 || /<!DOCTYPE|<!ENTITY|<ContentProtection\b|<BaseURL\b|<cenc:|xmlns:cenc\s*=/i.test(xml) ||
      !/<MPD\s[^>]*xmlns="urn:mpeg:dash:schema:mpd:2011"/.test(xml) ||
      !/\btype="static"/.test(xml) || !/\bmediaPresentationDuration="PT12(?:\.0+)?S"/.test(xml) ||
      (xml.match(/<Period\b/g) ?? []).length !== 1) {
    throw Error('Owned encrypted manifest shape refused.');
  }
  const adaptations = [...xml.matchAll(/<AdaptationSet\b([^>]*)>([\s\S]*?)<\/AdaptationSet>/g)];
  if (adaptations.length !== 2 || (xml.match(/<AdaptationSet\b/g) ?? []).length !== 2 ||
      (xml.match(/<Representation\b/g) ?? []).length !== 2) {
    throw Error('Owned encrypted fixture requires exactly two streams.');
  }
  const expected = [
    { id: '0', type: 'video', codec: 'avc1.', mime: 'video/mp4' },
    { id: '1', type: 'audio', codec: 'mp4a.', mime: 'audio/mp4' },
  ];
  for (const [index, adaptation] of adaptations.entries()) {
    const stream = expected[index];
    const representations = [...adaptation[2].matchAll(/<Representation\b([^>]*)>/g)];
    const representation = representations[0]?.[1] ?? '';
    if (!adaptation[1].includes(`contentType="${stream.type}"`) || representations.length !== 1 ||
        !representation.includes(`id="${stream.id}"`) || !representation.includes(`mimeType="${stream.mime}"`) ||
        !representation.includes(`codecs="${stream.codec}`) ||
        !adaptation[2].includes('initialization="init-stream$RepresentationID$.m4s"') ||
        !adaptation[2].includes('media="chunk-stream$RepresentationID$-$Number%05d$.m4s"')) {
      throw Error('Owned encrypted stream binding refused.');
    }
  }
  const protection = `<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="${ENCRYPTED_FIXTURE_KID_UUID}" />`;
  return xml.replace(/<MPD\s/, '<MPD xmlns:cenc="urn:mpeg:cenc:2013" ')
    .replace(/(<AdaptationSet\b[^>]*>)/g, `$1\n\t\t\t${protection}`);
}

// Like prepareQualityFixture, output is the prepared public-assets root. A new
// destination is required so stale clear or encrypted segments cannot survive.
export function prepareEncryptedFixture(root, output) {
  const source = resolve(root, ENCRYPTED_FIXTURE_SOURCE.path);
  const input = readFileSync(source);
  const sha256 = createHash('sha256').update(input).digest('hex');
  const declared = JSON.parse(readFileSync(resolve(dirname(source), 'media.json'), 'utf8')).files?.['a.mp4'];
  if (input.length !== ENCRYPTED_FIXTURE_SOURCE.bytes || sha256 !== ENCRYPTED_FIXTURE_SOURCE.sha256 ||
      declared?.sha256 !== sha256 || declared?.bytes !== input.length ||
      declared?.video !== 'h264' || declared?.audio !== 'aac') {
    throw Error('Owned encrypted input hash, size or stream binding mismatch.');
  }

  const destination = resolve(output, 'media/encrypted');
  mkdirSync(dirname(destination), { recursive: true });
  mkdirSync(destination);
  try {
    const ffmpeg = process.env.FFMPEG ?? 'ffmpeg';
    const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-i', source, '-t', '12',
      '-map', '0:v:0', '-map', '0:a:0', '-c', 'copy', '-map_metadata', '-1',
      '-f', 'dash', '-seg_duration', '2', '-use_template', '1', '-use_timeline', '1',
      '-adaptation_sets', 'id=0,streams=v id=1,streams=a',
      '-format_options', `encryption_scheme=cenc-aes-ctr:encryption_key=${ENCRYPTED_FIXTURE_KEY_HEX}:encryption_kid=${ENCRYPTED_FIXTURE_KID_HEX}`,
      resolve(destination, 'manifest.mpd')];
    const encoded = spawnSync(ffmpeg, args, { stdio: 'ignore', timeout: 60000, killSignal: 'SIGKILL' });
    if (encoded.error || encoded.signal || encoded.status !== 0) {
      throw Error('Owned encrypted fixture packaging failed; install FFmpeg or set FFMPEG.');
    }
    const initializations = readdirSync(destination).filter((name) => name.startsWith('init-'));
    if (initializations.length !== 2 || !initializations.includes('init-stream0.m4s') ||
        !initializations.includes('init-stream1.m4s')) {
      throw Error('Owned encrypted fixture initialization inventory refused.');
    }
    verifyInitialization(readFileSync(resolve(destination, 'init-stream0.m4s')), 'encv');
    verifyInitialization(readFileSync(resolve(destination, 'init-stream1.m4s')), 'enca');
    const manifest = resolve(destination, 'manifest.mpd');
    writeFileSync(manifest, protectOwnedManifest(readFileSync(manifest, 'utf8')));
    const metadata = {
      schema: 1, source: 'controlled-mirroring/a.mp4', sha256, sourceBytes: input.length,
      durationSeconds: 12, segmentSeconds: 2, video: 'h264', audio: 'aac',
      scheme: 'cenc-aes-ctr', publicSyntheticKey: ENCRYPTED_FIXTURE_KEY_HEX,
      publicSyntheticKid: ENCRYPTED_FIXTURE_KID_UUID, encryptedInitializations: 2, adaptationSets: 2,
      providerRequests: false, decodedPlaybackVerified: false,
    };
    writeFileSync(resolve(destination, 'provenance.json'), `${JSON.stringify(metadata, null, 2)}\n`);
    return metadata;
  } catch (error) {
    // Only the directory created by this invocation is removed on failure.
    rmSync(destination, { recursive: true, force: true });
    throw error;
  }
}
