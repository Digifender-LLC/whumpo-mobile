# Building the client

Use JDK 21 and the Android SDK with platform 37 installed and its terms accepted.
Set `ANDROID_HOME`, or set `sdk.dir` in an untracked `android/local.properties`.
The wrapper pins Gradle 9.8.2; `android/gradle/libs.versions.toml` pins AGP 9.5.0,
Kotlin 2.4.21 and Compose Multiplatform 1.12.2. Do not upgrade them independently.

Run the commands in the root README from `android/`. `localDebug` uses
`http://127.0.0.1:8787` and requires your own compatible backend; `deployedDebug`
targets `https://whumpo.com` and requires separately authorized service access.
Neither variant includes privileged provider credentials.

The shared module contains JVM and Android host tests. Roborazzi compares
synthetic UI snapshots; to intentionally regenerate baselines locally, run:

```sh
./gradlew :shared:testAndroidHostTest -Pscreenshots.record=true --rerun-tasks
```

Inspect changed images before committing. Do not use regeneration to hide failures.
App-module unit-test tasks may report `NO-SOURCE`; that is not additional coverage.

Instrumentation requires a disposable compatible emulator. The inherited scripts
are not a replacement for the private Worker integration harness. In particular,
`ci-instrumented.sh 36` requires backend files absent from this repository. See
[migration boundaries](migration.md) before running or wiring those scripts into CI.

Release configuration reads `WHUMPO_UPLOAD_STORE_FILE`, `WHUMPO_UPLOAD_STORE_PASSWORD`,
`WHUMPO_UPLOAD_KEY_ALIAS` and `WHUMPO_UPLOAD_KEY_PASSWORD` only from the environment.
Do not provide these to public contribution jobs. Without the complete signing
configuration, release builds use the debug identity and a `-debugsigned` suffix;
they are not official releases. This repository contains no release publishing job.
