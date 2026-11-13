import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {checkPolicy, checkTrackedPaths} from '../tools/check-policy.mjs';

const inputs = {
  android: readFileSync(new URL('../.github/workflows/android.yml', import.meta.url), 'utf8'),
  ci: readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8'),
  instrumentation: readFileSync(new URL('../android/scripts/ci-instrumented.sh', import.meta.url), 'utf8'),
  sdkInstaller: readFileSync(new URL('../android/scripts/ci-install-sdk.sh', import.meta.url), 'utf8'),
  dependabot: readFileSync(new URL('../.github/dependabot.yml', import.meta.url), 'utf8')
};

assert.deepEqual(checkPolicy(inputs), [], 'current workflow policy should pass');

function mutateActionReferences(base, action, replacement) {
  const escapedAction = action.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = () => new RegExp(`${escapedAction}@([a-f0-9]{40})`, 'g');
  const matches = [...`${base.android}\n${base.ci}`.matchAll(pattern())];
  assert.ok(matches.length > 0, `${action} must have a full SHA fixture to mutate`);
  let changes = 0;
  const update = text => text.replace(pattern(), (match, sha) => {
    const updated = replacement(sha);
    assert.notEqual(updated, match, `${action} fixture must change`);
    changes += 1;
    return updated;
  });
  const updated = {...base, android: update(base.android), ci: update(base.ci)};
  assert.ok(changes > 0, `${action} fixture must be mutated`);
  assert.notDeepEqual(updated, base, `${action} fixture must change before policy validation`);
  return updated;
}

const alternatePins = [
  ['actions/checkout', 'f'.repeat(40)],
  ['actions/setup-java', 'e'.repeat(40)],
  ['gradle/actions/wrapper-validation', 'd'.repeat(40)],
  ['actions/setup-node', 'c'.repeat(40)],
  ['actions/upload-artifact', 'b'.repeat(40)],
  ['reactivecircus/android-emulator-runner', 'a'.repeat(40)]
];
let updatedInputs = inputs;
for (const [action, sha] of alternatePins) {
  updatedInputs = mutateActionReferences(updatedInputs, action, () => `${action}@${sha}`);
}
assert.deepEqual(checkPolicy(updatedInputs), [], 'all approved actions accept changed full commit pins');

function rejectsInputs(base, change, expected, label) {
  const fixture = {...base, ...change};
  assert.ok(checkPolicy(fixture).some(error => error.includes(expected)), label);
}

function rejects(change, expected, label) {
  rejectsInputs(inputs, change, expected, label);
}

for (const base of [inputs, updatedInputs]) {
  rejectsInputs(mutateActionReferences(base, 'actions/checkout', () => 'actions/checkout@v7'), {},
    'immutable action pins', 'unpinned actions must fail for current and updated pins');
  rejectsInputs(mutateActionReferences(base, 'actions/setup-node',
    sha => `example/setup-node@${sha}`), {},
  'approved action identities and immutable pins',
  'unexpected action identities must fail for current and updated pins');
}
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
rejects({ci: inputs.ci.replace('          bash android/scripts/test-ci-install-sdk.sh\n', '')},
  'policy command bash android/scripts/test-ci-install-sdk.sh',
  'SDK installer regression must remain in the policy job');

// The exclusion must be attached to each upload, not merely mentioned somewhere in the workflow.
const logcatExclusion = '            !android/**/logcat*\n';
assert.equal(inputs.android.split(logcatExclusion).length - 1, 2);
for (const index of [inputs.android.indexOf(logcatExclusion), inputs.android.lastIndexOf(logcatExclusion)]) {
  const without = inputs.android.slice(0, index) + inputs.android.slice(index + logcatExclusion.length);
  rejects({android: without}, 'Android artifact upload paths', 'each upload must exclude per-test logcat');
}
const lastExclusion = inputs.android.lastIndexOf(logcatExclusion);
const misplacedExclusion = (inputs.android.slice(0, lastExclusion) + inputs.android.slice(lastExclusion + logcatExclusion.length))
  .replace(logcatExclusion, logcatExclusion.repeat(2));
assert.equal(misplacedExclusion.split(logcatExclusion).length - 1, 2);
rejects({android: misplacedExclusion}, 'Android artifact upload paths', 'two exclusions in one upload must not protect the other');
for (const [before, after, label] of [
  [logcatExclusion, '            !android/**/unrelated*\n', 'an unrelated exclusion must not protect logcat'],
  ['            android/androidApp/build/outputs/androidTest-results/**\n', '', 'JUnit outputs must remain uploaded'],
  ['            android/androidApp/build/outputs/instrumented-diagnostics/tree-failures.txt\n', '', 'allowlisted tree diagnostics must remain uploaded'],
  ['            android/androidApp/build/outputs/androidTest-results/**\n', '            android/androidApp/build/outputs/**\n', 'a broader output path must fail'],
  [logcatExclusion, `${logcatExclusion}            !android/**/*.xml\n`, 'a report-removing exclusion must fail'],
  [logcatExclusion, `${logcatExclusion}            android/**/logcat*\n`, 'a new positive logcat path must fail'],
]) {
  assert.ok(inputs.android.includes(before), label);
  rejects({android: inputs.android.replace(before, after)}, 'Android artifact upload paths', label);
}
rejects({android: inputs.android + `
      - uses: actions/upload-artifact@${'b'.repeat(40)}
        with:
          name: extra-unfiltered-results
          path: android/androidApp/build/outputs/androidTest-results/**
`}, 'Android artifact upload paths', 'an extra unguarded artifact upload must fail');

assert.deepEqual(checkTrackedPaths([
  'README.md',
  'android/build.gradle.kts',
  'android/gradle/wrapper/gradle-wrapper.jar',
  'android/scripts/test-worker-journey.sh',
  'docs/migration.md',
  'tests/fixtures/mobile-v1/operations.json',
  'tests/policy.mjs'
]), [], 'public scripts, fixtures, build scripts and documentation paths should pass');

for (const path of [
  'src/worker.ts',
  'frontend/app.tsx',
  'schema/control.sql',
  'integrations/cms/index.ts',
  'node_modules/package/index.js',
  'wrangler.jsonc',
  'tests/android-local-worker.mjs',
  '.git/config',
  'android/.gradle/caches/file',
  'android/.kotlin/sessions/file',
  'android/.wrangler/state.json',
  'android/cache/build-state.json',
  'android/app/build/outputs/apk/debug/app.apk',
  'android/app/release.aab',
  '.env',
  'android/.env.production',
  '.dev.vars',
  '.secrets/worker',
  'android/local.properties',
  'android/app/google-services.json',
  'android/signing/upload.keystore',
  'android/signing/upload.jks',
  'android/signing/upload.p12'
]) {
  assert.ok(checkTrackedPaths([path]).some(error => error.startsWith('forbidden tracked path:')), path);
}

assert.deepEqual(checkTrackedPaths([{path: 'README.md', mode: '100644', stage: 0}]), []);
for (const mode of ['120000', '160000']) {
  assert.ok(checkTrackedPaths([{path: 'docs/link', mode, stage: 0}])
    .some(error => error.startsWith('unsupported tracked file mode:')), mode);
}

console.log('Mobile policy negative fixtures passed.');
