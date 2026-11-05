import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

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

  const pins = [
    ['actions/checkout', '3d3c42e5aac5ba805825da76410c181273ba90b1'],
    ['actions/setup-java', 'b843ebf9b913146b38da2eaa7aeddf76074ae1ad'],
    ['gradle/actions/wrapper-validation', '8da46b539cd8fe6ab27ca75128919a18d757604c'],
    ['actions/setup-node', '949feb2413d6458794dcd2491c4babbbce0c15c1'],
    ['actions/upload-artifact', 'cf430e030ddbb5b0abf93d22962f4752f3646cd9'],
    ['reactivecircus/android-emulator-runner', 'a421e43855164a8197daf9d8d40fe71c6996bb0d']
  ];
  for (const [action, sha] of pins) {
    const combined = `${android}\n${ci}`;
    require(combined.includes(`${action}@${sha}`), `pinned action ${action}`);
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
  require((android.match(/actions\/setup-java@/g) || []).length === 2
    && !/cache: gradle|actions\/cache@/i.test(android), 'no shared Gradle cache');
  require(sdkInstaller.includes('commandlinetools-linux-13114758_latest.zip')
    && sdkInstaller.includes('7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee')
    && !sdkInstaller.includes('system-images;'), 'pinned SDK tools and no build-job system image');

  require(ci.includes('name: Mobile policy') && ci.includes('name: Secret scan'), 'CI check names');
  for (const command of [
    'node tests/policy.mjs', 'node tools/check-policy.mjs', 'node tests/secret-scan-metadata.mjs',
    'bash android/scripts/test-ci-instrumented.sh', 'bash android/scripts/test-setup-emulator.sh'
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
    const root = new URL('../', import.meta.url);
    const read = path => readFileSync(new URL(path, root), 'utf8');
    const errors = checkPolicy({
      android: read('.github/workflows/android.yml'),
      ci: read('.github/workflows/ci.yml'),
      instrumentation: read('android/scripts/ci-instrumented.sh'),
      sdkInstaller: read('android/scripts/ci-install-sdk.sh'),
      dependabot: read('.github/dependabot.yml')
    });
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
