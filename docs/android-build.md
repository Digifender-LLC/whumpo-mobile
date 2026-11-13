# Building the client

Use JDK 21 and the Android SDK with platform 37 installed and its terms accepted.
Set `ANDROID_HOME`, or set `sdk.dir` in an untracked `android/local.properties`.
The wrapper pins Gradle 9.9.0; `android/gradle/libs.versions.toml` pins AGP 9.5.1,
Kotlin 2.4.21 and Compose Multiplatform 1.12.2. Do not upgrade them independently.

The public build uses Temurin 21.0.13+9, Android platform `android-37.0`, and
build-tools 36.0.0 and 37.0.0. CI always downloads command-line tools 19.0,
verifies the checksum in `android/scripts/ci-install-sdk.sh`, and only then
replaces the selected tools tree. Build mode does not install the emulator
package or download system images. `bash android/scripts/test-ci-install-sdk.sh`
mock-tests the installer without network access. No Gradle dependency cache is
shared between jobs.

Run these commands from `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

The Android workflow runs both commands' tasks in one build gate, with wrapper
validation first. App unit-test tasks may report `NO-SOURCE`; report that honestly
rather than counting it as coverage. `localDebug` uses
`http://127.0.0.1:8787` and requires your own compatible backend; `deployedDebug`
targets `https://whumpo.com` and requires separately authorized service access.
Neither variant includes privileged provider credentials.

The shared module contains JVM and Android host tests. Roborazzi compares committed
synthetic UI snapshots; public CI only verifies them and never records or updates
baselines. To intentionally regenerate baselines locally, run:

```sh
./gradlew :shared:testAndroidHostTest -Pscreenshots.record=true --rerun-tasks
```

Inspect changed images before committing. Do not use regeneration to hide failures.
App-module unit-test tasks may report `NO-SOURCE`; that is not additional coverage.

Instrumentation requires a disposable compatible emulator. For either supported
API, `bash android/scripts/ci-instrumented.sh 28` or
`bash android/scripts/ci-instrumented.sh 36` runs only
`connectedLocalDebugAndroidTest` and `connectedDeployedDebugAndroidTest`, excluding
`WorkerJourneyTest`. Both API levels use this same client-only suite. The inherited
`android/scripts/test-worker-journey.sh` is not a public command: it requires
private website files and dependencies. Keep that real Worker journey in the
private website repository; public client tests do not replace it.

The Android workflow uses isolated `ubuntu-24.04` hosted runners, a disposable
x86_64 Google APIs emulator at API 28 or 36, no secrets and no provider calls.
It uploads only synthetic JUnit/build/Roborazzi reports and the allowlisted ANR,
device-state and tree-diagnostic outputs for seven days; it does not upload raw
logcat or workspace/cache directories. `Mobile policy` covers shell regressions
and source-contract tests. `Secret scan` covers full Git history, fetching all
commits and tags; its report contains only rule, commit, hashed path and line
metadata.

Required check names are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)` and
`Instrumented tests (API 36)`. The workflow definitions do not prove that checks
have run or are required by repository settings. See
[CI verification](android-verification.md) and the
[merge policy](agent-merge-policy.md).

Release configuration reads `WHUMPO_UPLOAD_STORE_FILE`, `WHUMPO_UPLOAD_STORE_PASSWORD`,
`WHUMPO_UPLOAD_KEY_ALIAS` and `WHUMPO_UPLOAD_KEY_PASSWORD` only from the environment.
Do not provide these to public contribution jobs. Without the complete signing
configuration, release builds use the debug identity and a `-debugsigned` suffix;
they are not official releases. This repository contains no release publishing job.
