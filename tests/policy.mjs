import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {checkPolicy, checkTrackedPaths} from '../tools/check-policy.mjs';

const inputs = {
  android: readFileSync(new URL('../.github/workflows/android.yml', import.meta.url), 'utf8'),
  ci: readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8'),
  instrumentation: readFileSync(new URL('../android/scripts/ci-instrumented.sh', import.meta.url), 'utf8'),
  diagnosticRunner: readFileSync(new URL('../android/scripts/run-gradle-with-compose-diagnostics.sh', import.meta.url), 'utf8'),
  sdkInstaller: readFileSync(new URL('../android/scripts/ci-install-sdk.sh', import.meta.url), 'utf8'),
  dependabot: readFileSync(new URL('../.github/dependabot.yml', import.meta.url), 'utf8')
};

assert.deepEqual(checkPolicy(inputs), [], 'current workflow policy should pass');
assert.ok(inputs.diagnosticRunner.includes('if (( gradle_status != 0 ))')
  && inputs.diagnosticRunner.includes('grep -Fq "Plugin [id: \'org.jetbrains.kotlin.plugin.compose\'"'),
  'Compose resolution probe runs only for the specific plugin-marker failure');
assert.ok(inputs.android.includes('run: bash android/scripts/run-gradle-with-compose-diagnostics.sh'),
  'Android build must route through the Compose diagnostic wrapper');
assert.ok(inputs.diagnosticRunner.includes('exit "$gradle_status"')
  && inputs.diagnosticRunner.includes('org.jetbrains.kotlin.plugin.compose')
  && inputs.diagnosticRunner.includes('bash scripts/diagnose-compose-plugin-resolution.sh'),
  'diagnostic wrapper must preserve the original Gradle result and check the exact plugin');
execFileSync('bash', ['android/scripts/test-gradle-compose-diagnostic-status.sh'], {stdio: 'inherit'});

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
  'API-specific phone and Wear instrumentation tasks and exclusion', 'missing WorkerJourneyTest exclusion must fail');
rejects({instrumentation: inputs.instrumentation.replace(
  ':wearApp:connectedLocalDebugAndroidTest', '')},
  'API-specific phone and Wear instrumentation tasks and exclusion', 'missing API 36 Wear task must fail');
rejects({instrumentation: inputs.instrumentation.replace(
  'if [[ "$api" == 36 ]]; then', 'if [[ "$api" == 28 ]]; then')},
  'API-specific phone and Wear instrumentation tasks and exclusion', 'Wear instrumentation must remain API 36-only');
rejects({ci: inputs.ci.replace('          bash android/scripts/test-ci-install-sdk.sh\n', '')},
  'policy command bash android/scripts/test-ci-install-sdk.sh',
  'SDK installer regression must remain in the policy job');
rejects({ci: inputs.ci.replace('          node tests/number-decimal-fixtures.mjs\n', '')},
  'policy command node tests/number-decimal-fixtures.mjs',
  'the independent numeric corpus check must remain in the policy job');
rejects({ci: inputs.ci.replace('          python3 android/scripts/test-export-debug-apks.py\n', '')},
  'policy command python3 android/scripts/test-export-debug-apks.py', 'APK export regressions must remain in policy');
rejects({ci: inputs.ci.replace(/      - uses: actions\/setup-java@[^\n]+\n        with:\n          distribution: temurin\n          java-version: "21\.0\.13\+9"\n/, '')},
  'policy SDK properties tests use pinned JDK 21', 'actual Java properties regressions require the configured JDK');
rejects({android: inputs.android.replace('        run: python3 android/scripts/export-debug-apks.py\n', '')},
  'debug APK export follows the build', 'upload must not omit APK validation');
rejects({android: inputs.android.replace('        run: python3 android/scripts/export-debug-apks.py\n',
  '        run: python3 android/scripts/export-debug-apks.py\n        continue-on-error: true\n')},
  'debug APK export follows the build', 'export validation failures must stop the upload');
for (const [before, after, label] of [
  ['            android/androidApp/build/outputs/debug-artifacts/manifest.json',
    '            android/androidApp/build/outputs/debug-artifacts/**', 'wildcard APK evidence upload'],
  ['            android/androidApp/build/outputs/debug-artifacts/android-local-debug.apk',
    '            android/androidApp/build/outputs/apk/deployed/release/androidApp-deployed-release.apk', 'release APK upload'],
  ['          retention-days: 1\n', '          retention-days: 7\n', 'longer APK retention'],
  ['          if-no-files-found: error\n', '          if-no-files-found: ignore\n', 'missing APK evidence ignored'],
  ['          retention-days: 1\n', '          retention-days: 1\n          retention-days: 90\n', 'duplicate artifact retention key'],
  ['          compression-level: 0\n', '          compression-level: 0\n          path: android/**\n', 'duplicate broad artifact path key'],
  ['      - name: Upload local debug APK evidence\n', '      - name: Upload local debug APK evidence\n        if: always()\n', 'APK upload after failed validation'],
]) rejects({android: inputs.android.replace(before, after)}, 'Android artifact upload paths', label);

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
  ['            android/wearApp/build/outputs/androidTest-results/**\n', '', 'Wear JUnit outputs must remain uploaded'],
  ['            android/wearApp/build/reports/androidTests/**\n', '', 'Wear test reports must remain uploaded'],
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
rejects({android: inputs.android + `
  extra-artifact:
    runs-on: ubuntu-24.04
    steps:
        - uses: actions/upload-artifact@${'b'.repeat(40)}
          with:
            name: extra-unfiltered-results
            path: android/androidApp/build/outputs/androidTest-results/**
`}, 'Android artifact upload paths', 'an extra upload with valid alternative indentation must fail');
rejects({android: inputs.android + `
  extra-artifact:
    runs-on: ubuntu-24.04
    steps:
      - {uses: actions/upload-artifact@${'b'.repeat(40)}, with: {name: raw-results, path: android/androidApp/build/outputs/androidTest-results/**}}
`}, 'Android artifact upload paths', 'an extra flow-style upload must fail');
rejects({android: inputs.android.replace(
  '      - name: Upload synthetic build and test reports',
  '      - &reportUpload\n        name: Upload synthetic build and test reports') + `
  extra-artifact:
    runs-on: ubuntu-24.04
    steps:
      - <<: *reportUpload
        with:
          name: raw-results
          path: android/androidApp/build/outputs/androidTest-results/**
`}, 'Android artifact upload paths', 'an inherited upload with an unfiltered path override must fail');

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

const connectedPreview = readFileSync(new URL(
  '../android/androidApp/src/localDebug/kotlin/com/whumpo/android/core/CorePreviewActivity.kt', import.meta.url
), 'utf8');
const shortcutHub = connectedPreview.match(/fun workspaceHubEpoch\(allowWorkList: Boolean = false\): Long\? \{([\s\S]*?)return session\.epoch/)?.[1];
assert.match(shortcutHub ?? '', /\bshowSourceHistory\b[\s\S]*return null/,
  'An open Source History screen must reject a new static shortcut.');
assert.match(connectedPreview, /publishShortcutReady \{ workspaceHubEpoch\(\) \}/,
  'Static shortcuts must use the hub without the Work-list share allowance.');
const localManifest = readFileSync(new URL('../android/androidApp/src/localDebug/AndroidManifest.xml', import.meta.url), 'utf8');
const mainManifest = readFileSync(new URL('../android/androidApp/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
const shareTarget = localManifest.match(/<activity\s+android:name="\.core\.CoreShareDraftActivity"[^>]*>([\s\S]*?)<\/activity>/)?.[1];
assert.match(shareTarget ?? '', /android.intent.action.SEND"[\s\S]*?mimeType="text\/plain"/,
  'The connected receiver must get the standard single text Sharesheet route.');
assert.doesNotMatch(shareTarget ?? '', /SEND_MULTIPLE|image\/|mimeType="\*\//,
  'The connected receiver must not register for files or multiple items.');
assert.doesNotMatch(mainManifest, /CoreShareDraftActivity/, 'The connected share target must only appear in localDebug.');
assert.match(connectedPreview, /val interruptWorkspaceEntry = \{\s*interruptShortcut\(\)\s*interruptShare\(\)/,
  'Explicit hub route changes must invalidate both entry types.');
assert.match(connectedPreview, /onReviewOpened = interruptShortcut, onInteraction = interruptShare/,
  'An ownership interaction must invalidate a pending share before the review is published.');
assert.match(connectedPreview, /onWorkListInteraction = interruptShare/,
  'An explicit Work list interaction must invalidate a pending share.');
assert.match(connectedPreview, /openSourceHistory = \{ interruptWorkspaceEntry\(\); showSourceHistory = true \}/,
  'Entering Source History must also invalidate a shortcut already awaiting workspace readiness.');
assert.match(connectedPreview, /controller\.commitIfCurrent\(session\.epoch\) \{\s*if \(currentEpoch\(\) == null/,
  'Shortcut navigation must recheck the current hub immediately before showing a destination.');
