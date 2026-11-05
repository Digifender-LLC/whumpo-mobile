import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {checkPolicy} from '../tools/check-policy.mjs';

const inputs = {
  android: readFileSync(new URL('../.github/workflows/android.yml', import.meta.url), 'utf8'),
  ci: readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8'),
  instrumentation: readFileSync(new URL('../android/scripts/ci-instrumented.sh', import.meta.url), 'utf8'),
  sdkInstaller: readFileSync(new URL('../android/scripts/ci-install-sdk.sh', import.meta.url), 'utf8'),
  dependabot: readFileSync(new URL('../.github/dependabot.yml', import.meta.url), 'utf8')
};

assert.deepEqual(checkPolicy(inputs), [], 'current workflow policy should pass');

function rejects(change, expected, label) {
  const fixture = {...inputs, ...change};
  assert.ok(checkPolicy(fixture).some(error => error.includes(expected)), label);
}

rejects({android: inputs.android.replace('actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1', 'actions/checkout@v7')},
  'immutable action pins', 'unpinned actions must fail');
rejects({ci: inputs.ci.replace('  pull_request:\n', '  pull_request_target:\n')},
  'pull_request trigger', 'privileged pull_request_target must fail');
rejects({ci: inputs.ci.replace('      contents: read', '      contents: write')},
  'public-runner boundary', 'write permissions must fail');
rejects({ci: inputs.ci.replace(
  '        run: |\n          node tests/policy.mjs',
  '        env:\n          TOKEN: ${{ secrets.GITHUB_TOKEN }}\n        run: |\n          node tests/policy.mjs')},
  'public-runner boundary', 'repository tokens must not be available to PR jobs');
rejects({android: inputs.android.replace('runs-on: ubuntu-24.04', 'runs-on: [self-hosted, linux]')},
  'isolated hosted runner', 'persistent runners must fail');
rejects({android: inputs.android.replace('testLocalDebugUnitTest testDeployedDebugUnitTest', 'testLocalDebugUnitTest')},
  'required Gradle tasks', 'a missing required test task must fail');
rejects({android: inputs.android.replace('  build:\n', '  build:\n    if: false\n')},
  'required checks cannot be skipped', 'required check jobs must not be conditionally skipped');
rejects({dependabot: inputs.dependabot.replace('directory: /android', 'directory: /')},
  'Gradle dependency updates', 'Gradle updates must use the Android project directory');
rejects({instrumentation: inputs.instrumentation.replace(
  '-Pandroid.testInstrumentationRunnerArguments.notClass=com.whumpo.android.WorkerJourneyTest', '')},
  'client instrumentation command and exclusion', 'missing WorkerJourneyTest exclusion must fail');

console.log('Mobile policy negative fixtures passed.');
