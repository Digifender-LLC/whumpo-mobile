# Whumpo Mobile

Whumpo's Android-first native client, built with Kotlin Multiplatform and
Compose Multiplatform. The app provides project views, AI chat and change-review
interfaces for the Whumpo platform.

This is an early-development source import, not a production release or a
self-hosted backend. Building the app does not grant access to the hosted service.
The private backend, official signing keys and provider credentials are not included.

## Build and test

Use JDK 21 and an Android SDK with platform 37. Set `ANDROID_HOME` or configure
`android/local.properties` locally; never commit machine-specific paths or credentials.
The Gradle wrapper and version catalog pin the toolchain.

From `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug lintLocalDebug lintDeployedDebug
./gradlew :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

See [build notes](docs/android-build.md) for variants and test boundaries.
Host tests use synthetic fixtures and screenshot baselines. They do not establish
emulator, physical-device, live-service, signing or customer acceptance.

## Layout

- `android/shared/`: API client, shared models, UI and tests.
- `android/androidApp/`: Android shell, device authentication and storage.
- `tests/fixtures/mobile-v1/`: synthetic mobile API fixtures.
- [Mobile API contract](docs/mobile-api-v1.md): imported contract snapshot.
- [Migration status](docs/migration.md): provenance, pending changes and integration boundaries.

## Contributing

Use pull requests and synthetic test data. Do not include credentials, keystores,
customer data, real diagnostic captures or Firebase configuration. Preserve
authorization, privacy and signing boundaries. Public contribution code must not
run with release secrets or on a trusted persistent runner.

This import does not install CI workflows or replace the existing private
integration gates. CI and repository ownership cutover require a reviewed follow-up;
see the migration notes before making changes that affect client/server contracts.

## License

Original project code is licensed under [Apache License 2.0](LICENSE), except where
a file specifies otherwise. Third-party components retain their licenses and
notices; see [third-party notices](THIRD_PARTY_NOTICES.md).

The license grants no Whumpo trademark rights, official signing identity or hosted
service entitlement. Forks must not imply official endorsement.
