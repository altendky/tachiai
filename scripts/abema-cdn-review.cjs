'use strict';

// Read only the app's sanitized journal, never arbitrary provider URL/log data.
const maximumBytes = 64 * 1024;
const maximumRecords = 128;
const header = 'tachiai-abema-cdn-review-v1';

function parseReview(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length > maximumBytes ||
      bytes.some(byte => byte < 9 || byte > 126)) throw new Error('REVIEW_INVALID');
  const lines = bytes.toString('ascii').split('\n');
  if (lines[0] !== header || lines.at(-1) !== '' || lines.length > maximumRecords + 2)
    throw new Error('REVIEW_INVALID');
  const seen = new Set();
  return lines.slice(1, -1).map(line => {
    const fields = line.split('\t');
    if (fields.length !== 5 || !/^[0-9]{1,13}$/.test(fields[0]) ||
        !['APPROVED', 'PENDING', 'REJECTED'].includes(fields[1]) ||
        !['SELECTED_MANIFEST', 'DECLARED_MEDIA'].includes(fields[2]) ||
        !['ABEMATV_AKAMAI_FAMILY', 'APPROVED_EXACT', 'REJECTED_EXACT', 'UNKNOWN_ORIGIN'].includes(fields[3]))
      throw new Error('REVIEW_INVALID');
    const match = /^https:\/\/([a-z0-9.-]{1,253})\/<redacted-path>$/.exec(fields[4]);
    const labels = match?.[1].split('.');
    if (!labels || labels.length < 2 ||
        labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)))
      throw new Error('REVIEW_INVALID');
    const identity = fields.slice(1).join('\t');
    if (seen.has(identity)) throw new Error('REVIEW_INVALID');
    seen.add(identity);
    return { firstSeenMs: Number(fields[0]), decision: fields[1], stage: fields[2], rule: fields[3], pattern: fields[4] };
  });
}

module.exports = { parseReview, maximumBytes, maximumRecords, header };

if (require.main === module) {
  const chunks = [];
  let size = 0;
  function refuse() {
    process.stderr.write('CDN review unavailable or invalid; no input printed.\n');
    process.exitCode = 1;
  }
  process.stdin.on('data', chunk => {
    size += chunk.length;
    if (size > maximumBytes) { chunks.length = 0; process.stdin.destroy(); refuse(); }
    else chunks.push(chunk);
  });
  process.stdin.on('error', refuse);
  process.stdin.on('end', () => {
    if (size > maximumBytes) return;
    try {
      const records = parseReview(Buffer.concat(chunks));
      process.stdout.write(JSON.stringify({ records, pendingObservations: records.filter(row => row.decision === 'PENDING').length }, null, 2) + '\n');
    } catch { refuse(); }
  });
}
