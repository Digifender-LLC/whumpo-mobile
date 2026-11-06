---
name: android-testing
description: Run the public Whumpo Mobile credential-free build, host, unit, and client-only emulator checks.
---

# Android testing

Read `AGENTS.md`, `docs/android-build.md` and
`docs/android-verification.md` before changing or testing Android code.

Use the pinned wrapper/toolchain: JDK 21, Gradle 9.8.2, AGP 9.5.0, Kotlin
2.4.21, Compose Multiplatform 1.12.2 and Android platform 37.0. Keep provider
credentials, signing material, `.env` files, private website source and customer
data out of public tests and build environments.

Run shell and policy tests from the repository root, one at a time:

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

Run Gradle checks serially from `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

For emulator tests, reuse an available disposable emulator; never install an
image or alter host acceleration as an unrequested setup repair. Run
`bash scripts/ci-instrumented.sh 28` or `36` only with an API-matching emulator
and an `emulator-*` serial. The public script excludes `WorkerJourneyTest` on
both APIs; its private website journey remains a separate integration gate.

Report exact command outcomes, device/API and any `NO-SOURCE` or skipped tests.
Roborazzi tests verify committed synthetic baselines; never record or update
baselines in public CI. No build or test pass establishes provider, live service,
physical-device, signing or customer acceptance.
