# Android CI and local verification

## Required check names

| GitHub check name | Workflow / job | Coverage |
| --- | --- | --- |
| `Mobile policy` | `.github/workflows/ci.yml` / `mobile-policy` | Instrumentation/setup/SDK-installer shell regressions and dependency-free policy/source-contract tests |
| `Secret scan` | `.github/workflows/ci.yml` / `secret-scan` | Gitleaks 8.30.1 over full Git history, including fetched tags |
| `Assemble, lint and unit tests` | `.github/workflows/android.yml` / `build` | Wrapper validation, both debug flavors and AndroidTest APKs, lint, shared JVM/host and app unit tasks |
| `Instrumented tests (API 28)` | `.github/workflows/android.yml` / `instrumented` matrix API 28 | Client instrumented suites on disposable x86_64 Google APIs emulator |
| `Instrumented tests (API 36)` | `.github/workflows/android.yml` / `instrumented` matrix API 36 | Same client-only suites on disposable x86_64 Google APIs emulator |

These are the exact source-defined names. A workflow file does not prove a run
occurred or that repository protections require its checks.

`Mobile policy` checks tracked paths and Git index modes against known private
source/configuration, generated-output and signing-file boundaries. It does not
detect backend code copied under an otherwise allowed path; Gitleaks and
independent source review remain required.

## Credential-free local commands

From the repository root:

```sh
node tests/policy.mjs
node tools/check-policy.mjs
node tests/merge-controller.mjs
node tests/secret-scan-metadata.mjs
bash android/scripts/test-ci-instrumented.sh
bash android/scripts/test-setup-emulator.sh
bash android/scripts/test-ci-install-sdk.sh
bash -n \
  android/scripts/ci-instrumented.sh \
  android/scripts/test-ci-instrumented.sh \
  android/scripts/ci-install-sdk.sh \
  android/scripts/test-ci-install-sdk.sh
```

With a credential-free environment, a configured JDK 21 and Android SDK, run
Gradle serially from `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

The first command covers shared JVM tests and desktop compilation. The second
assembles both debug variants and their instrumentation APKs, lints both
variants, runs shared Android host tests (including committed Roborazzi
baselines), and requests both app unit-test tasks. If an app unit task reports
`NO-SOURCE`, record that result without counting it as test coverage.

Instrumentation needs an already available disposable emulator; the script
accepts only API 28 or 36 and an `emulator-*` serial:

```sh
bash android/scripts/ci-instrumented.sh 28
bash android/scripts/ci-instrumented.sh 36
```

Each run invokes only `connectedLocalDebugAndroidTest` and
`connectedDeployedDebugAndroidTest`, with `WorkerJourneyTest` excluded. The
test APK installs a fixture application before session restoration. Shell tests
establish sealed synthetic sessions and pass the production onboarding gate with
synthetic bootstrap responses. The local flavor uses device-loopback HTTP; the
deployed flavor uses an in-process test engine and never contacts its configured
website origin. Neither transport replaces the onboarding gate in production.
The script captures bounded, allowlisted device/tree/ANR diagnostics without hiding
the Gradle exit status. The shell regression exercises both API values, invalid
API and physical serial rejection, missing/empty/failing JUnit results, and
diagnostic-command failures. Before Gradle runs, the script removes preceding
connected-test results; a successful Gradle exit also requires fresh JUnit matching
`android/scripts/instrumented-test-inventory.json` for **the selected emulator and each flavor**,
with exactly one report per flavor and no missing, duplicate, unknown or failing cases. This is a reviewed inventory
of full JUnit class/method identities, not a count inferred from that run or a
regex count of annotations. WorkerJourneyTest is explicitly excluded. Current
client classes use AndroidJUnit4, have no inherited test methods or parameterized
runners. When tests change, review and update the inventory: inherited tests use
the concrete reporting class, and every parameterized description (including its
parameter suffix) must be listed separately. Never strip parameter suffixes or
accept a partial parameter set. Synthetic regressions cover these identities.

API36 requires actual execution of `composerIsNotCoveredByTheKeyboard` in both
flavors; a missing keyboard fails. API28 alone permits that case to skip for the
exact keyboard-absence assumption. This is conditional suite completion **without
keyboard coverage**, not equivalent keyboard acceptance. The existing deployed
loopback-credential assumption and named missing-local-backup-transport assumption
are also explicitly allowlisted per API/flavor in the inventory. All other skips
are rejected; accepted skips print their identities and missing-coverage warning.

The keyboard assertion samples three stable **rendered frames**, identified by
vsync timestamps in OnDraw, with no active IME animation. Callback-generation
changes reset the evidence, including animations between polls. Callback
availability is logged, not required: distinct stable frames provide evidence on
APIs without callbacks. A stable covered composer still fails the original 1px
geometry assertion. Synthetic Java regressions exercise repeated-frame rejection,
movement, animation and absence resets, and stable-but-covered geometry.

Direct instrumentation is diagnostic evidence, not a replacement for this Gradle
gate.
Do not run
the private `android/scripts/test-worker-journey.sh` from this public repository.
It requires private website Worker source and dependencies; public CI does not
replace that separate integration.

Client CI runs on `ubuntu-24.04` for every pull request and each push to `main`, with
read-only contents permissions, no repository secrets, no private source, no
provider calls and no deployment/signing. The build job installs command-line
tools 19.0 with the pinned checksum and required Android platforms/build tools;
it does not download emulator images. Each instrumentation job provisions only
its matrix emulator. CI does not use shared Gradle caches or a screenshot
recording input.

The separate trusted-main merge controller is not client CI and never checks
out a PR revision. Its synthetic boundary tests run in `Mobile policy`; see the
[merge policy](agent-merge-policy.md) for event, permission and bootstrap limits.

Artifacts are retained for seven days and limited to synthetic JUnit/build/
Roborazzi reports and the allowlisted instrumentation files: ANR summaries,
device state, Gradle exit status and tree diagnostics. Raw logcat, workspaces,
`.gradle`, `.android`, signing files and private Worker outputs are not uploaded.
The secret-scan artifact contains sanitized rule/commit/hashed-filename/line
metadata only; raw Gitleaks reports and logs are temporary and deleted.

Local or CI test results do not establish live Worker/API availability, provider
behavior, official signing, Play publication, physical-device acceptance,
customer acceptance or a website ownership cutover.

## Dependency updates

`.github/dependabot.yml` declares weekly grouped version-update checks for Gradle
in `/android` and GitHub Actions in `/`. It does not establish that the dependency
graph, security alerts or security updates are enabled.
