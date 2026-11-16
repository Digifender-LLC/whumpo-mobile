# Building the client

Use JDK 21. Install Android SDK platform 37. Accept its terms.
Set `ANDROID_HOME` or set `sdk.dir` in an untracked `android/local.properties`.
The wrapper specifies Gradle 9.9.0. `android/gradle/libs.versions.toml`
specifies AGP 9.5.1,
Kotlin 2.4.21 and Compose Multiplatform 1.12.2. Do not upgrade them independently.

The public build uses Temurin 21.0.13+9, Android platform `android-37.0`, and
build-tools 36.0.0 and 37.0.0. CI always downloads command-line tools 19.0. It compares the checksum in
`android/scripts/ci-install-sdk.sh` before it replaces the selected tools tree.
Build mode omits the emulator package and system images. `bash android/scripts/test-ci-install-sdk.sh`
does a test of the installer with mock commands and without network access.
Jobs do not share Gradle dependency caches.

Run these commands from `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

The Android workflow does the tasks from both commands in one build gate.
The workflow validates the Gradle wrapper first. If an app unit task reports
`NO-SOURCE`, record that result separately from test coverage. `localDebug` uses
`http://127.0.0.1:8787` and requires your own compatible backend; `deployedDebug`
targets `https://whumpo.com` and requires separately authorized service access.
Neither variant includes privileged provider credentials.

The shared module contains JVM and Android host tests. Roborazzi compares committed reference screenshots with synthetic data. Public
CI compares the images and does not record or update them. If you intend to
record the reference screenshots again, enter this command from `android/`.

```sh
./gradlew :shared:testAndroidHostTest -Pscreenshots.record=true --rerun-tasks
```

Examine the changed images before you commit. Do not record new images to hide
errors.

Instrumentation requires a disposable compatible emulator. For either supported
API, `bash android/scripts/ci-instrumented.sh 28` or
`bash android/scripts/ci-instrumented.sh 36` runs only
`connectedLocalDebugAndroidTest` and `connectedDeployedDebugAndroidTest`, excluding
`WorkerJourneyTest`. Both API levels use this same client-only suite. The inherited `android/scripts/test-worker-journey.sh` requires private
website files and dependencies. Keep that Worker test in the private website
repository. The integration requires that test separately from public client tests.

The Android workflow uses isolated `ubuntu-24.04` hosted runners, a disposable
x86_64 Google APIs emulator at API 28 or 36, no secrets and no provider calls.
It uploads synthetic JUnit/build/Roborazzi reports and the allowlisted ANR,
device-state and tree-diagnostic outputs for seven days. A separate one-day artifact
contains only the two local debug APKs and their build manifest. The size limit
is 64 MiB. See [APK evidence](android-verification.md). It does not upload raw
logcat or workspace/cache directories. `Mobile policy` covers shell regressions
and source-contract tests. `Secret scan` examines all commits and tags in Git history. Its report contains only rule,
commit, hashed path, and line metadata.

Required check names are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)` and
`Instrumented tests (API 36)`. Examine GitHub separately for check results and branch protection. See
[CI verification](android-verification.md) and the
[merge policy](agent-merge-policy.md).

Release configuration reads `WHUMPO_UPLOAD_STORE_FILE`, `WHUMPO_UPLOAD_STORE_PASSWORD`,
`WHUMPO_UPLOAD_KEY_ALIAS` and `WHUMPO_UPLOAD_KEY_PASSWORD` only from the environment.
Do not provide these to public contribution jobs. If signing configuration is incomplete, a release build uses the debug identity
and a `-debugsigned` suffix. Official releases require complete signing configuration. This repository contains no release publishing job.
