import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareQualityFixture } from './prepare-quality-fixture.mjs';

const app = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = resolve(app, '../..');
const output = resolve(app, 'build/public');
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
cpSync(resolve(root, 'build/core/web'), resolve(output, 'core'), { recursive: true });
cpSync(resolve(root, 'build/core/licenses'), resolve(output, 'licenses'), { recursive: true });

const provenance = [];
for (const name of ['a', 'b']) {
  const source = resolve(root, `apps/android/app/src/androidTest/assets/controlled-mirroring/${name}.mp4`);
  const destination = resolve(output, `media/${name}`);
  const hash = createHash('sha256').update(readFileSync(source)).digest('hex');
  const expected = JSON.parse(readFileSync(resolve(dirname(source), 'media.json'), 'utf8'));
  if (hash !== expected.files[`${name}.mp4`].sha256) throw new Error('Controlled fixture hash mismatch.');
  mkdirSync(destination, { recursive: true });
  const result = spawnSync(process.env.FFMPEG ?? 'ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y', '-i', source,
    '-map', '0:v:0', '-map', '0:a:0', '-c', 'copy',
    '-f', 'dash', '-seg_duration', '2', '-use_template', '1', '-use_timeline', '1',
    '-adaptation_sets', 'id=0,streams=v id=1,streams=a', '-hls_playlist', '1',
    resolve(destination, 'manifest.mpd'),
  ], { stdio: 'inherit' });
  if (result.error || result.status !== 0) {
    throw new Error('Fixture packaging failed; install FFmpeg or set FFMPEG to its executable.');
  }
  provenance.push({ name, sha256: hash });
}
writeFileSync(resolve(output, 'media/provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
prepareQualityFixture(root, output);

// Include the selected package's upstream notices with every build.
const notices = resolve(output, 'licenses/shaka-player');
mkdirSync(notices, { recursive: true });
for (const name of ['LICENSE', 'AUTHORS', 'CONTRIBUTORS']) {
  cpSync(resolve(app, 'node_modules/shaka-player', name), resolve(notices, name));
}
// Preserve vendored-component attribution and license texts as well.
cpSync(resolve(app, 'node_modules/shaka-player/third_party'), resolve(notices, 'third_party'), { recursive: true });
const compiled = readFileSync(resolve(app, 'node_modules/shaka-player/dist/shaka-player.compiled.js'), 'utf8');
const legalComments = [...compiled.matchAll(/\/\*[\s\S]*?\*\//g)]
  .map((match) => match[0]).filter((comment) => /@license|@preserve|Copyright/.test(comment));
writeFileSync(resolve(notices, 'UPSTREAM-NOTICES.txt'), [...new Set(legalComments)].join('\n\n') + '\n');
