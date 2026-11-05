import {createHash} from 'node:crypto';
import {existsSync, readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

export function findingMetadata(findings, exitCode) {
  if (!Number.isSafeInteger(exitCode) || exitCode < 0 || !Array.isArray(findings)) {
    throw Error('Invalid scan metadata');
  }
  if (exitCode === 1 && !findings.length) throw Error('Failed scan did not provide finding metadata');
  if (exitCode === 0 && findings.length) throw Error('Successful scan unexpectedly provided findings');
  return {
    exitCode,
    count: findings.length,
    findings: findings.map(f => {
      if (
        typeof f.RuleID !== 'string' || !/^\w[\w-]*$/.test(f.RuleID)
        || !/^([a-f0-9]{40}|[a-f0-9]{64})$/.test(f.Commit)
        || typeof f.File !== 'string' || !Number.isSafeInteger(f.StartLine) || f.StartLine < 1
      ) throw Error('Invalid finding identity');
      return {
        rule: f.RuleID,
        commit: f.Commit,
        fileHash: createHash('sha256').update(f.File, 'utf8').digest('hex'),
        line: f.StartLine,
        fingerprint: createHash('sha256')
          .update(JSON.stringify([f.Commit, f.File, f.RuleID, f.StartLine]), 'utf8')
          .digest('hex')
      };
    })
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const exitCode = Number(process.argv[3]);
    if (!process.argv[2] || !existsSync(process.argv[2])) throw Error('Missing scan report');
    const findings = JSON.parse(readFileSync(process.argv[2], 'utf8'));
    console.log(JSON.stringify(findingMetadata(findings, exitCode), null, 2));
  } catch {
    console.error('Scan metadata unavailable or invalid; raw finding values are not emitted.');
    process.exitCode = 1;
  }
}
