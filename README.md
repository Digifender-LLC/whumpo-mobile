# Whumpo Mobile

Whumpo Mobile is the native client for Whumpo. It targets Android first.
The app uses Kotlin Multiplatform and Compose Multiplatform. It shows
projects, AI chat, and changes for review.

This repository contains a source import from early development. The import
excludes a production release and a self-hosted backend.
The hosted service requires separate access. The private backend, official
signing keys, and provider credentials are outside this repository.

## Build and test

Use JDK 21. Install the Android SDK with platform 37.
Set `ANDROID_HOME` or set `sdk.dir` in a local `android/local.properties` file.
Keep machine-specific paths and credentials outside Git.
The Gradle wrapper and version catalog specify the tool versions.

Enter these commands from `android/`.

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

See the [build notes](docs/android-build.md) for build variants and test limits.
Host tests use synthetic data and reference screenshots. Emulator, physical-device,
live-service, signing, and customer acceptance each require separate evidence.

## Layout

| Location | Contents |
| --- | --- |
| `android/shared/` | API client, shared models, UI, and tests |
| `android/androidApp/` | Android shell, device authentication, and storage |
| `tests/fixtures/mobile-v1/` | Synthetic mobile API fixtures |
| [Mobile API contract](docs/mobile-api-v1.md) | Imported contract snapshot |
| [Migration status](docs/migration.md) | Source history, pending changes, and integration limits |

## Contributing

Use pull requests. Use synthetic test data.
Do not include credentials, keystores, customer data, real diagnostic captures,
or Firebase configuration. Keep the authorization, privacy, and signing
boundaries. Keep release secrets and trusted persistent runners outside public
contribution jobs.

The repository defines credential-free CI for pull requests and pushes to
`main`. The five checks are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)`, and
`Instrumented tests (API 36)`. Examine GitHub separately for check results and
branch protection. Before a merge, the controller on trusted `main` examines the
current PR head and base, required CI results, review threads, and branch
protection. See [verification](docs/android-verification.md),
[merge policy](docs/agent-merge-policy.md), and [migration notes](docs/migration.md).

Use neutral, project-focused names for public branches, commits, and pull
requests. Use the same style in public discussion. Do not add optional AI/Devin
branding, generated-by or session footers, agent co-author trailers, or session
links. Keep the attribution that the law, tools, or audit records require.
Do not impersonate a person. Do not rewrite history.

## License

Original project code is licensed under [Apache License 2.0](LICENSE), except where
a file specifies otherwise. Third-party components retain their licenses and
notices; see [third-party notices](THIRD_PARTY_NOTICES.md).

The license grants no Whumpo trademark rights, official signing identity or hosted
service entitlement. Forks must not imply official endorsement.
