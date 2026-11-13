import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {fileURLToPath, pathToFileURL} from 'node:url';

const forbiddenRootDirectories = new Set(['src', 'frontend', 'schema', 'integrations']);
const forbiddenPathSegments = new Set([
  '.git', '.gradle', '.kotlin', '.wrangler', '.cache', 'cache', 'build',
  'node_modules', '.secrets'
]);
const privateBackendHarness = /^tests\/(?:android-local-worker(?:[./-]|$)|test-worker-journey(?:[./-]|$)|worker-journey(?:[./-]|$)|backend(?:\/|[.-]|$))/i;

export function checkTrackedPaths(paths) {
  const errors = [];
  for (const entry of paths) {
    const path = typeof entry === 'string' ? entry : entry?.path;
    if (typeof path !== 'string' || path.length === 0 || path.startsWith('/')) {
      errors.push('invalid tracked path');
      continue;
    }
    const segments = path.split('/');
    const filename = segments.at(-1).toLowerCase();
    const forbidden = forbiddenRootDirectories.has(segments[0])
      || segments.some(segment => forbiddenPathSegments.has(segment)
        || segment === '.env'
        || segment.startsWith('.env.'))
      || segments.some(segment => ['.dev.vars', 'local.properties', 'google-services.json'].includes(segment))
      || /^wrangler(?:\.[^/]+)?$/i.test(segments[0])
      || privateBackendHarness.test(path)
      || /\.(?:keystore|jks|p12|apk|aab)$/i.test(filename);
    if (forbidden) errors.push(`forbidden tracked path: ${path}`);

    if (typeof entry !== 'string'
      && (!['100644', '100755'].includes(entry?.mode) || entry?.stage !== 0)) {
      errors.push(`unsupported tracked file mode: ${path}`);
    }
  }
  return errors;
}

function readTrackedIndex(rootPath) {
  const paths = execFileSync('git', ['ls-files', '-z'], {cwd: rootPath, encoding: 'utf8'})
    .split('\0')
    .filter(Boolean);
  const stages = new Map();
  const stageRecords = execFileSync('git', ['ls-files', '--stage', '-z'], {cwd: rootPath, encoding: 'utf8'})
    .split('\0')
    .filter(Boolean);
  for (const record of stageRecords) {
    const separator = record.indexOf('\t');
    if (separator < 0) throw new Error('Malformed tracked index record');
    const [mode, , stageText] = record.slice(0, separator).split(' ');
    const path = record.slice(separator + 1);
    const entries = stages.get(path) || [];
    entries.push({mode, stage: Number(stageText)});
    stages.set(path, entries);
  }
  return paths.flatMap(path => {
    const entries = stages.get(path);
    return entries?.length
      ? entries.map(entry => ({path, ...entry}))
      : [{path, mode: null, stage: null}];
  });
}

// A reviewed source contract for each upload step, not a general YAML or glob evaluator.
function androidArtifactPathsMatch(android) {
  const expected = {
    build: {
      name: 'android-build-reports',
      paths: [
        'android/androidApp/build/reports/**',
        'android/androidApp/build/test-results/**',
        'android/shared/build/reports/**',
        'android/shared/build/test-results/**',
        'android/shared/build/outputs/roborazzi/**',
        '!android/**/logcat*'
      ]
    },
    instrumented: {
      name: 'android-instrumented-api-${{ matrix.api-level }}',
      paths: [
        'android/androidApp/build/reports/androidTests/**',
        'android/androidApp/build/outputs/androidTest-results/**',
        'android/androidApp/build/outputs/instrumented-diagnostics/anr-before.json',
        'android/androidApp/build/outputs/instrumented-diagnostics/anr-after.json',
        'android/androidApp/build/outputs/instrumented-diagnostics/device-state.txt',
        'android/androidApp/build/outputs/instrumented-diagnostics/tree-failures.txt',
        'android/androidApp/build/outputs/instrumented-diagnostics/test-exit-code.txt',
        '!android/**/logcat*'
      ]
    }
  };
  const uploads = text => text.split(/^ {6}- /m).slice(1)
    .filter(step => /^\s*uses: actions\/upload-artifact@/m.test(step));
  // Count even noncanonical flow-style steps before mapping the two approved blocks.
  const allUploads = [...android.matchAll(/actions\/upload-artifact@/gi)];
  if (allUploads.length !== 2) return false;
  // Aliases can inherit an upload and override its paths without another action identity.
  // This canonical workflow does not admit YAML anchors, aliases or merge keys.
  if (/(?:^|[\s[{,:])(?:&|\*)[^\s&*,{}\[\]]+|^\s*<<\s*:/m.test(android)) return false;
  const sections = android.split(/^ {2}(?=[\w-]+:)/m);
  return Object.entries(expected).every(([job, artifact]) => {
    const section = sections.find(text => text.startsWith(`${job}:\n`));
    if (!section) return false;
    const steps = uploads(section);
    if (steps.length !== 1) return false;
    const name = /^ {10}name: (.+)$/m.exec(steps[0])?.[1];
    const block = /^ {10}path: \|\n((?: {12}[^\n]+\n)+)/m.exec(steps[0])?.[1];
    const paths = block?.trim().split('\n').map(line => line.trim());
    return name === artifact.name && JSON.stringify(paths) === JSON.stringify(artifact.paths);
  });
}

export function checkPolicy({android, ci, instrumentation, sdkInstaller, dependabot}) {
  const errors = [];
  const require = (condition, label) => {
    if (!condition) errors.push(label);
  };

  for (const [text, name] of [[android, 'Android'], [ci, 'CI']]) {
    require(text.includes(`name: ${name}\n`), `${name} workflow name`);
    require(/^  pull_request:\s*$/m.test(text), `${name} pull_request trigger`);
    require(/^  push:\n    branches: \[main\]$/m.test(text), `${name} main push trigger`);
    require(!/pull_request_target|workflow_dispatch|^\s+paths(?:-ignore)?:/m.test(text), `${name} trigger restrictions`);
    require(text.includes('group: ${{ github.workflow }}-${{ github.ref }}'), `${name} concurrency group`);
    require(text.includes("cancel-in-progress: ${{ github.event_name == 'pull_request' }}"), `${name} PR cancellation`);
    require(/permissions:\n  contents: read/m.test(text), `${name} read-only permissions`);
    require(!/\b(?:contents|actions|checks|pull-requests):\s*write\b|\$\{\{\s*secrets\.|\bgithub\.token\b|self-hosted|Ampere|ARM64/m.test(text), `${name} public-runner boundary`);
    const uses = [...text.matchAll(/^\s*(?:-\s*)?uses:\s*([^\s#]+)/gm)].map(match => match[1]);
    require(uses.length > 0 && uses.every(value => /@[a-f0-9]{40}$/.test(value)), `${name} immutable action pins`);
    require((text.match(/uses: actions\/checkout@[a-f0-9]{40}/g) || []).length
      === (text.match(/persist-credentials: false/g) || []).length, `${name} checkout credentials disabled`);
    require((text.match(/runs-on: ubuntu-24\.04/g) || []).length > 0
      && !/^ *runs-on: (?!ubuntu-24\.04)/m.test(text), `${name} isolated hosted runner`);
  }

  const expectedActions = [
    'actions/checkout',
    'actions/setup-java',
    'gradle/actions/wrapper-validation',
    'actions/setup-node',
    'actions/upload-artifact',
    'reactivecircus/android-emulator-runner'
  ];
  const combined = `${android}\n${ci}`;
  const actionUses = [...combined.matchAll(/^\s*(?:-\s*)?uses:\s*([^\s#]+)/gm)]
    .map(match => match[1]);
  const actionNames = actionUses.map(value => value.slice(0, value.lastIndexOf('@')));
  require(actionUses.length > 0 && actionUses.every(value => {
    const separator = value.lastIndexOf('@');
    return expectedActions.includes(value.slice(0, separator))
      && /^[a-f0-9]{40}$/.test(value.slice(separator + 1));
  }), 'approved action identities and immutable pins');
  for (const action of expectedActions) {
    require(actionNames.includes(action), `required action identity ${action}`);
  }

  for (const [label, text, timeout] of [
    ['Android build', android, 45],
    ['Android instrumentation', android, 60],
    ['Mobile policy', ci, 10],
    ['Secret scan', ci, 10]
  ]) require(text.includes(`timeout-minutes: ${timeout}`), `${label} bounded timeout`);

  require(android.includes('name: Assemble, lint and unit tests'), 'build check name');
  require(android.includes('name: Instrumented tests (API ${{ matrix.api-level }})'), 'instrumented check name');
  const androidConditions = [...android.matchAll(/^\s+if:\s+([^\n]+)/gm)].map(match => match[1].trim());
  const ciConditions = [...ci.matchAll(/^\s+if:\s+([^\n]+)/gm)].map(match => match[1].trim());
  require([...androidConditions, ...ciConditions].every(condition => condition === 'always()'), 'required checks cannot be skipped');
  require(android.includes('api-level: [28, 36]') && android.includes('target: google_apis') && android.includes('arch: x86_64'), 'instrumentation matrix');
  require(android.includes('setfacl -m "u:$(id -un):rw" /dev/kvm'), 'narrow ephemeral KVM ACL');
  require(android.includes('-no-snapshot -no-window -gpu swiftshader_indirect -noaudio')
    && android.includes("'-memory 2560'"), 'emulator launch restrictions');
  require(android.includes('java-version: "21.0.13+9"') && ci.includes('node-version: 24.21.0'), 'pinned toolchain');
  require(android.includes('assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:jvmTest :shared:compileKotlinJvm :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest'), 'required Gradle tasks');
  require(!/record_screenshots|screenshots\.record|release_bundle|bundleDeployedRelease|UPLOAD_KEYSTORE|test-worker-journey|worker-journey|raw logcat/i.test(android), 'no recording, release or private Worker test');
  require(android.includes('retention-days: 7') && !/android\/(?:\.gradle|\.android)\/|logcat -d/i.test(android), 'bounded allowlisted artifacts');
  require(androidArtifactPathsMatch(android), 'Android artifact upload paths retain reports and exclude per-test logcat');
  require((android.match(/actions\/setup-java@/g) || []).length === 2
    && !/cache: gradle|actions\/cache@/i.test(android), 'no shared Gradle cache');
  require(sdkInstaller.includes('commandlinetools-linux-13114758_latest.zip')
    && sdkInstaller.includes('7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee')
    && !sdkInstaller.includes('system-images;'), 'pinned SDK tools and no build-job system image');

  require(ci.includes('name: Mobile policy') && ci.includes('name: Secret scan'), 'CI check names');
  for (const command of [
    'node tests/policy.mjs', 'node tools/check-policy.mjs', 'node tests/secret-scan-metadata.mjs',
    'bash android/scripts/test-ci-instrumented.sh', 'bash android/scripts/test-setup-emulator.sh',
    'bash android/scripts/test-ci-install-sdk.sh'
  ]) require(ci.includes(command), `policy command ${command}`);
  require(ci.includes('fetch-depth: 0') && ci.includes('fetch-tags: true'), 'full-history secret scan checkout');
  require(ci.includes('GITLEAKS_VERSION: 8.30.1')
    && ci.includes('551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb')
    && ci.includes('--redact --config .gitleaks.toml'), 'pinned redacted Gitleaks scan');
  const scanArtifact = ci.slice(ci.indexOf('name: Upload sanitized finding metadata'));
  require(ci.includes('tools/secret-scan-metadata.mjs "$report" "$result"')
    && ci.includes('> "$GITHUB_WORKSPACE/scan-diagnostics/findings.json"')
    && scanArtifact.includes('path: scan-diagnostics/findings.json')
    && !/gitleaks-report|gitleaks-log|raw_log/i.test(scanArtifact), 'sanitized secret-scan output');
  require(!/\b(?:pull_request_target|workflow_run):|actions\/cache@|pull-requests:\s*write|contents:\s*write/.test(`${android}\n${ci}`), 'no privileged trigger or write permission');
  require(!/test-worker-journey\.sh/.test(instrumentation), 'private Worker journey excluded');
  require(instrumentation.includes('connectedLocalDebugAndroidTest connectedDeployedDebugAndroidTest')
    && instrumentation.includes('notClass=com.whumpo.android.WorkerJourneyTest'), 'client instrumentation command and exclusion');
  require(dependabot.includes('version: 2')
    && dependabot.includes('package-ecosystem: gradle')
    && dependabot.includes('directory: /android'), 'Gradle dependency updates');
  require(dependabot.includes('package-ecosystem: github-actions')
    && dependabot.includes('directory: /'), 'GitHub Actions dependency updates');
  require((dependabot.match(/interval: weekly/g) || []).length === 2
    && (dependabot.match(/day: wednesday/g) || []).length === 2
    && (dependabot.match(/open-pull-requests-limit: 2/g) || []).length === 2
    && (dependabot.match(/patterns:\n\s+- "\*"/g) || []).length === 2, 'bounded grouped dependency updates');
  return errors;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const root = fileURLToPath(new URL('../', import.meta.url));
    const read = path => readFileSync(new URL(path, pathToFileURL(`${root}/`)), 'utf8');
    const errors = checkPolicy({
      android: read('.github/workflows/android.yml'),
      ci: read('.github/workflows/ci.yml'),
      instrumentation: read('android/scripts/ci-instrumented.sh'),
      sdkInstaller: read('android/scripts/ci-install-sdk.sh'),
      dependabot: read('.github/dependabot.yml')
    });
    errors.push(...checkTrackedPaths(readTrackedIndex(root)));
    if (errors.length) {
      console.error(`Policy contract failures: ${errors.join('; ')}`);
      process.exitCode = 1;
    } else {
      console.log('Mobile CI and policy source contracts passed.');
    }
  } catch {
    console.error('Unable to read mobile CI policy inputs.');
    process.exitCode = 1;
  }
}
