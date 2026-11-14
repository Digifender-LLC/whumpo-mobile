---
name: android-testing
description: Use the public Whumpo Mobile build, host, unit, and emulator checks without credentials.
---

# Android testing

Read `AGENTS.md`, `docs/android-build.md` and
`docs/android-verification.md` before you change Android code or do Android tests.

Use the specified tool versions. They are JDK 21, Gradle 9.9.0, AGP 9.5.1, Kotlin
2.4.21, Compose Multiplatform 1.12.2 and Android platform 37.0. Keep provider
credentials, signing material, `.env` files, private website source and customer
data out of public tests and build environments.

From the repository root, enter each command separately.

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

From `android/`, enter each Gradle command separately.

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

For emulator tests, use an available disposable emulator. Do not install an
image or change host acceleration without a request. If the API and the
`emulator-*` serial agree with the selected device, enter
`bash scripts/ci-instrumented.sh 28` or use `36` for API 36. Both APIs exclude
`WorkerJourneyTest`. The private website requires that integration test separately.

Record the exact command results, the device, and the API. Include `NO-SOURCE`
results and tests that did not execute. Roborazzi tests compare committed
reference screenshots with synthetic data. Do not record or update the reference
screenshots in public CI. Provider, live-service, physical-device, signing, and
customer acceptance each require separate evidence.
