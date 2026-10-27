import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

// Encode only repository-owned media. Existing mirroring A/B bytes stay intact.
export function prepareQualityFixture(root, output) {
  const source = resolve(root, 'apps/android/app/src/androidTest/assets/controlled-mirroring/a.mp4');
  const sha256 = createHash('sha256').update(readFileSync(source)).digest('hex');
  const expected = JSON.parse(readFileSync(resolve(source, '../media.json'), 'utf8'));
  if (sha256 !== expected.files['a.mp4'].sha256) throw Error('Owned quality input hash mismatch.');
  const destination = resolve(output, 'media/quality');
  mkdirSync(destination, { recursive: true });
  const args = ['-hide_banner', '-loglevel', 'error', '-y', '-i', source,
    '-filter_complex', '[0:v]split=2[v0][v1];[v0]scale=320:180[lo];[v1]scale=640:360[hi]',
    '-map', '[lo]', '-map', '[hi]', '-map', '0:a:0', '-map', '0:a:0', '-t', '24',
    '-map_metadata', '-1', '-c:v', 'libx264', '-threads:v', '1', '-preset', 'ultrafast',
    '-pix_fmt', 'yuv420p', '-b:v:0', '180k', '-b:v:1', '800k',
    '-g', '24', '-keyint_min', '24', '-sc_threshold', '0',
    '-c:a', 'aac', '-b:a:0', '32k', '-b:a:1', '128k', '-ar', '48000', '-ac', '1',
    '-f', 'dash', '-seg_duration', '1', '-use_template', '1', '-use_timeline', '1',
    '-adaptation_sets', 'id=0,streams=v id=1,streams=a', resolve(destination, 'manifest.mpd')];
  const ffmpeg = process.env.FFMPEG ?? 'ffmpeg';
  const encoded = spawnSync(ffmpeg, args, { stdio: 'inherit' });
  if (encoded.error || encoded.status !== 0) throw Error('Owned quality fixture encoding failed.');
  const version = spawnSync(ffmpeg, ['-version'], { encoding: 'utf8' });
  if (version.status !== 0) throw Error('Owned encoder version unavailable.');
  writeFileSync(resolve(destination, 'provenance.json'), JSON.stringify({
    schema: 1, source: 'controlled-mirroring/a.mp4', sha256,
    encoder: version.stdout.split('\n')[0], durationSeconds: 24,
    video: [{ width: 320, height: 180, declaredBitrateBps: 180000 }, { width: 640, height: 360, declaredBitrateBps: 800000 }],
    audio: [{ declaredBitrateBps: 32000 }, { declaredBitrateBps: 128000 }],
    sampleRateHz: 48000, channelCount: 1, segmentSeconds: 1,
  }, null, 2) + '\n');
}
