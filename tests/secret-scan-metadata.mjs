import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {findingMetadata} from '../tools/secret-scan-metadata.mjs';

const syntheticPath = `docs/${createHash('sha256').update('synthetic path fixture').digest('hex')}\nname.md`;
const finding = {
  RuleID: 'generic-api-key',
  Commit: 'a'.repeat(40),
  File: syntheticPath,
  StartLine: 7,
  Secret: 'synthetic-value-must-not-appear',
  Match: 'synthetic-match-must-not-appear',
  Line: 'synthetic-line-must-not-appear',
  Message: 'synthetic-message-must-not-appear',
  Author: 'synthetic-author-must-not-appear'
};
const result = findingMetadata([finding], 1);
assert.equal(result.exitCode, 1);
assert.equal(result.count, 1);
assert.deepEqual(Object.keys(result.findings[0]).sort(), ['commit', 'fileHash', 'fingerprint', 'line', 'rule']);
assert.equal(result.findings[0].fileHash, createHash('sha256').update(syntheticPath).digest('hex'));
for (const value of [
  syntheticPath,
  finding.Secret,
  finding.Match,
  finding.Line,
  finding.Message,
  finding.Author
]) {
  assert.equal(JSON.stringify(result).includes(String(value)), false, 'raw finding data must not be serialized');
}
assert.deepEqual(findingMetadata([], 0), {exitCode: 0, count: 0, findings: []});
assert.throws(() => findingMetadata([], 1));
assert.throws(() => findingMetadata([finding], 0));
assert.throws(() => findingMetadata({}, 1));

const temp = mkdtempSync(join(tmpdir(), 'mobile-secret-metadata-'));
try {
  const cli = fileURLToPath(new URL('../tools/secret-scan-metadata.mjs', import.meta.url));
  const badReport = join(temp, 'bad-report.json');
  writeFileSync(badReport, '{');
  for (const report of [join(temp, 'missing-report.json'), badReport]) {
    const child = spawnSync(process.execPath, [cli, report, '0'], {encoding: 'utf8'});
    assert.equal(child.status, 1);
    assert.equal(child.stdout, '');
    assert.equal(child.stderr.trim(), 'Scan metadata unavailable or invalid; raw finding values are not emitted.');
  }
} finally {
  rmSync(temp, {recursive: true, force: true});
}

console.log('Secret-scan metadata redaction contract passed.');
