# Third-party material

The project's Apache-2.0 license does not relicense third-party components.

## Material included in the source tree

- **Inter fonts** — Copyright (c) 2016 The Inter Project Authors
  (https://github.com/rsms/inter). SIL Open Font License 1.1; the complete notice
  is retained in `android/shared/src/commonMain/composeResources/files/OFL-Inter.txt`.
- **Poppins fonts** — Copyright 2020 The Poppins Project Authors
  (https://github.com/itfoundry/Poppins). SIL Open Font License 1.1; the complete
  notice is retained in `android/shared/src/commonMain/composeResources/files/OFL-Poppins.txt`.
- **Gradle wrapper** — Apache License 2.0. Copyright/license headers remain in
  `android/gradlew` and `android/gradlew.bat`; the wrapper JAR retains its
  `META-INF/LICENSE`. Upstream: https://github.com/gradle/gradle.

## Build dependencies

Gradle resolves external libraries from the repositories configured in
`android/settings.gradle.kts`. Their code is not vendored by this source import;
their own licenses and notices continue to apply. The version catalog and module
build files identify direct dependencies. A distributable APK or app-store release
needs a separate inventory of resolved runtime dependencies and required notices;
this source import is not an APK redistribution/license-compliance certification.
