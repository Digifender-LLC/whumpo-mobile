import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';

// Synthetic binary64 values; Node supplies the ECMAScript shortest decimal oracle.
// Keep generation deterministic and separate from the Kotlin implementation under test.
const buffer = new ArrayBuffer(8);
const view = new DataView(buffer);
const values = new Set();
const mask = (1n << 64n) - 1n;
function add(bits) {
  view.setBigUint64(0, bits & mask);
  if (Number.isFinite(view.getFloat64(0))) values.add((bits & mask).toString(16).padStart(16, '0'));
}
function around(value) {
  view.setFloat64(0, value);
  const bits = view.getBigUint64(0);
  for (const delta of [-1n, 0n, 1n]) if (bits + delta >= 0n) add(bits + delta);
}
for (let bits = 0n; bits <= 64n; bits++) {
  add(bits);
  add(bits | (1n << 63n));
}
for (const value of [0.1, 0.2, 0.3, 0.30000000000000004, 0.300000001, 1, 1e9, 1e23,
  9007199254740991, 9007199254740992, 1000000000000000100, Number.MAX_VALUE]) {
  around(value);
  around(-value);
}
for (let exponent = -1074; exponent <= 1023; exponent += 31) {
  around(2 ** exponent);
  around(-(2 ** exponent));
}
for (let exponent = -323; exponent <= 308; exponent += 11) {
  around(10 ** exponent);
  around(-(10 ** exponent));
}
for (const bits of [0x000fffffffffffffn, 0x0010000000000000n, 0x7fefffffffffffffn]) {
  for (const delta of [-1n, 0n, 1n]) add(bits + delta);
}
let seed = 0x123456789abcdef0n;
for (let index = 0; index < 512; index++) {
  seed = (seed * 6364136223846793005n + 1442695040888963407n) & mask;
  add(seed);
}
const rows = [...values].sort().map(bits => {
  view.setBigUint64(0, BigInt(`0x${bits}`));
  return {bits, decimal: JSON.stringify(view.getFloat64(0))};
});
const expected = JSON.stringify(rows, null, 2) + '\n';
const path = new URL('./fixtures/numbers/canonical-decimals.json', import.meta.url);
if (process.argv.includes('--write')) {
  assert.ok(!process.env.CI, 'CI only verifies the committed oracle');
  writeFileSync(path, expected);
}
else assert.equal(readFileSync(path, 'utf8'), expected, 'canonical numeric corpus must match the Node oracle');
console.log(`Verified ${rows.length} deterministic canonical JSON-number vectors.`);
