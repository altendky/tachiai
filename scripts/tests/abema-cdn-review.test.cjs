const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const { parseReview, maximumBytes, maximumRecords, header } = require('../abema-cdn-review.cjs');

const row = '1791390000000\tPENDING\tSELECTED_MANIFEST\tUNKNOWN_ORIGIN\thttps://cdn.example.org/<redacted-path>\n';
const fixture = rows => Buffer.from(header + '\n' + rows);

test('bounded journal yields only normalized sanitized evidence', () => {
  assert.deepEqual(parseReview(fixture(row)), [{ firstSeenMs: 1791390000000, decision: 'PENDING',
    stage: 'SELECTED_MANIFEST', rule: 'UNKNOWN_ORIGIN', pattern: 'https://cdn.example.org/<redacted-path>' }]);
  assert.deepEqual(parseReview(fixture('')), []);
});

test('malformed, unredacted, oversized and duplicate evidence refuse', () => {
  for (const bytes of [Buffer.alloc(maximumBytes + 1), fixture(row + row), fixture(row.replace('PENDING', 'MAYBE')),
    fixture(row.replace('1791390000000', '-1')), fixture(row.replace('<redacted-path>', 'PRIVATE_SENTINEL?t=SECRET')),
    fixture(row.replace('cdn.example.org', 'user@cdn.example.org')), fixture(row.replace('cdn.example.org', 'cdn..org')),
    fixture(row.replace('cdn.example.org', 'cdn.example.org:443')), fixture(row.replace('https:', 'http:')),
    fixture(row).subarray(0, -1), Buffer.from('PRIVATE_SENTINEL')]) {
    assert.throws(() => parseReview(bytes), /REVIEW_INVALID/);
  }
  const rows = Array.from({ length: maximumRecords + 1 }, (_, i) => row.replace('cdn.example.org', `cdn${i}.example.org`)).join('');
  assert.throws(() => parseReview(fixture(rows)), /REVIEW_INVALID/);
});

test('CLI never prints malformed raw input or exception details', () => {
  const result = spawnSync(process.execPath, [require.resolve('../abema-cdn-review.cjs')], {
    input: fixture(row.replace('<redacted-path>', 'PRIVATE_SENTINEL?t=SECRET')), encoding: 'utf8',
  });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr.includes('PRIVATE_SENTINEL'), false);
  assert.equal(result.stderr.includes('SECRET'), false);
});
