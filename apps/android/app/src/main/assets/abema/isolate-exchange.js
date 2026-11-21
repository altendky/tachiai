// #598 one opaque exchange: runs the unchanged helper on the license
// response and applies the same owned Clear Key normalization as
// native-bootstrap.js begin(), without web APIs the isolate may lack.
// Kotlin substitutes the input, user and kids placeholders with JSON literals.
// Returns the JWK JSON, or a closed failure category.
(() => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const decode = text => {
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(text) || text.length % 4) throw new Error('INPUT');
    const clean = text.replace(/=+$/, '');
    const bytes = new Uint8Array(Math.floor(clean.length * 3 / 4));
    let bits = 0, value = 0, index = 0;
    for (const character of clean) {
      value = (value << 6) | alphabet.indexOf(character); bits += 6;
      if (bits >= 8) { bits -= 8; bytes[index++] = (value >> bits) & 255; }
    }
    return bytes;
  };
  const kind = error => error instanceof ReferenceError ? 'HELPER_REFERENCE_ERROR'
    : error instanceof TypeError ? 'HELPER_TYPE_ERROR' : error instanceof RangeError ? 'HELPER_RANGE_ERROR'
    : error instanceof Error && /^[A-Z_]{1,30}$/.test(error.message) ? error.message : 'HELPER_ERROR';
  try {
    const kids = __KIDS__;
    const input = decode(__INPUT__);
    if (input.byteLength < 1 || input.byteLength > 65536) throw new Error('INPUT');
    let processed;
    try { processed = globalThis.__tachiaiOperation(input.buffer, __USER__); }
    catch (error) { return kind(error); }
    finally { input.fill(0); }
    if (processed && typeof processed.then === 'function') return 'HELPER_ASYNC';
    const output = new Uint8Array(processed);
    let text = '';
    try {
      if (output.byteLength < 1 || output.byteLength > 65536) throw new Error('OUTPUT');
      for (const byte of output) { if (byte > 127) throw new Error('OUTPUT'); text += String.fromCharCode(byte); }
    } finally { output.fill(0); }
    const normalized = JSON.parse(text);
    if (!Array.isArray(normalized.keys) || normalized.keys.length < 1 || normalized.keys.length > 16) throw new Error('OUTPUT');
    const keys = normalized.keys.map(key => {
      if (!key || typeof key.kid !== 'string' || typeof key.k !== 'string') throw new Error('OUTPUT');
      const kid = key.kid.replace(/=+$/, '');
      const k = key.k.replace(/=+$/, '');
      if (!/^[A-Za-z0-9_-]{22}$/.test(kid) || !/^[A-Za-z0-9_-]{22}$/.test(k) || !kids.includes(kid)) throw new Error('OUTPUT');
      return { kty: 'oct', k, kid };
    });
    if (new Set(keys.map(key => key.kid)).size !== keys.length || keys.length !== kids.length) throw new Error('OUTPUT');
    return JSON.stringify({ keys, type: 'temporary' });
  } catch (error) { return error instanceof Error && /^[A-Z_]{1,30}$/.test(error.message) ? error.message : 'NORMALIZE_ERROR'; }
})()
