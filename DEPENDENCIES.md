# Native Expo stack — v7 audit

Audited and installed on 2026-11-21. Existing Xcode selections, Android components, mise installations and global npm packages were preserved. Nothing in the Whumpo, FounderAI, Shortform or Compose projects was changed. New command-line npm tools and Ruby gems use `~/.local/share/whumpo-expo-tools/`; Ruby and JDK 17 are retained as side-by-side Homebrew installations, selected explicitly by this project. `scripts/native-env.sh` selects them only for the shell that sources it. No shell profile was edited. Homebrew temporarily exposed the newly installed Ruby command; its new global links were removed after verification, restoring `/usr/bin/ruby` 2.6.10. The isolated project environment still selects Ruby3.4.12 and CocoaPods correctly. Homebrew added Python 3.15.2 as a support dependency; its new global command links were removed afterward, restoring the original `/usr/bin/python3` 3.9.6 while retaining the installed version for dependencies. Watchman version/socket probes still succeed.

## Versions and installation size

Sizes are allocated on-disk measurements, rounded to MiB. The complete path-level measurements, including Homebrew support libraries, are in `artifacts/v7-installed-sizes.json`. “Existing” means no replacement/install was needed.

| Item | Version / location | New size | Result / reason |
|---|---|---:|---|
| Node LTS | 24.21.0, existing mise shim | 0 | Matches installed Expo 58 and RN 0.88 engine range `^24.3.0` |
| npm | 11.19.0, existing | 0 | Lockfile install and package resolution work |
| Watchman | 2026.10.26.00, Homebrew | 15.5 MiB + support libraries | Version/socket probes succeed. This project's Jest/Metro use the Node crawler after a headless Watchman crawl stalled; other watchers were not changed. |
| Xcode | 27.2, build 27B52, existing | 0 | Existing selected developer directory `/Applications/Xcode.app/Contents/Developer` |
| Command line tools | 27.2.0.0.1792721180; Apple clang 21.0.0 | 0 | Existing; first-launch/license status already satisfied |
| iOS simulator runtime | iOS 27.2 (24B8117), existing | 0 | A disposable iPhone 16 Pro was created; no runtime download |
| Ruby | 3.4.12, `/opt/homebrew/opt/ruby@3.4` | 118.6 MiB + libraries | New global links removed; original system Ruby2.6.10 default restored |
| CocoaPods | 1.17.0, isolated gems prefix | Shared 25.9 MiB | Installed 117 pods for the native iOS build |
| Bundler | 4.1.0, same gems prefix | Included above | Available for Ruby dependency workflows; Expo's local build used CocoaPods directly |
| JDK 17 | OpenJDK 17.0.21.0.3, `/opt/homebrew/opt/openjdk@17` | 305.3 MiB + libraries | Keg-only, selected by project environment; recommended baseline for RN/Gradle |
| JDK 21 | Temurin 21.0.13+9 LTS, existing mise install | 0 | Found despite `/usr/libexec/java_home` not listing it; preserved |
| Android Studio | Existing `/Applications/Android Studio.app` | 0 | No upgrade or GUI setup performed |
| Android command line tools | 19.0, existing | 0 | sdkmanager works with explicit JAVA_HOME |
| Android platform | `platforms;android-37.0`, revision 2, existing | 0 | Resolves compile SDK 37 for RN 0.88 / Expo 58 |
| Android build-tools | 37.0.0, existing; 36.0.0 preserved | 0 | Matches generated native project |
| Android platform-tools / emulator | 37.0.1 / 37.2.13, existing | 0 | Private adb server 5619; only own console 5620 / adb 5621 used |
| Android NDK | **27.1.12297006** | **2465.8 MiB** | Exact RN 0.88.1 version catalog requirement, installed side by side |
| CMake | **3.30.5** | **109.6 MiB** | RN source default; selected consistently with `CMAKE_VERSION` |
| EAS CLI | 27.11.0, isolated npm prefix | Shared 159.1 MiB | Installed and version checked; no login, cloud build or publication |
| expo-doctor | 1.21.8, isolated npm prefix | Included above | `npx expo-doctor@1.21.8`: **20/20 checks pass** |
| Maestro CLI | **2.12.1**, isolated tools prefix | **378.3 MiB** | CLI only, official release archive; no Studio installation or full Maestro suite |
| ios-deploy / idb | Not installed | 0 | Not needed for simulator builds, installation, launch or screenshots; simctl handles those |

Measured durable additions total **4.18 GiB (4.49 GB)** including all new Homebrew support formulae, NDK/CMake and isolated npm/gem/Maestro tools. Temporary project build/dependency caches are measured separately and cleaned after validation. Xcode and simulator runtimes were already present, so no multi-gigabyte platform download was needed.

Maestro was chosen over Detox because it offers one native cross-platform CLI without adding app instrumentation/build dependencies to this mock. Only the CLI and its version were verified; the existing Python end-to-end flow remains the executed app test. No claim is made that a Maestro test suite was run. The [official CLI install instructions](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli) and [release archive](https://github.com/mobile-dev-inc/maestro/releases) were used. The installer script was not run because it would append shell-profile entries; the archive was extracted into the isolated prefix instead.

## Native application dependencies

Expo 58.0.26, RN 0.88.1 and React 19.3.0 remain pinned by the lockfile. Added SDK-compatible packages through `npx expo install`: `expo-dev-client` 58.0.21, `expo-haptics` 58.0.8, `expo-system-ui` 58.0.7 and `expo-build-properties` 58.0.12. The native build uses compile SDK 37, target SDK 36, min SDK 31, build-tools 37.0.0, NDK 27.1.12297006, Kotlin 2.2.0, AGP 9.2.1 and generated Gradle wrapper 9.4.1. These values were read from the installed RN catalog/generated project rather than guessed from older Expo releases.

The build-properties plugin sets the Android levels and iOS deployment target 16.4. Native directories were generated with Expo prebuild; native source was not hand-patched. The obsolete top-level `splash` key from v6 was removed after doctor flagged the SDK58 schema; system UI/background configuration is native OLED black. Dev-client configuration suppresses onboarding and floating tools for clean mock captures, with project URLs explicitly using port 8619.

## Reproduce the installation

```sh
# Additions only; no upgrade, relinking or default-selection changes.
HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_UPGRADE=1 HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK=1 brew install watchman ruby@3.4 openjdk@17
# Only for the newly added support Python on this machine; preserve the original default command:
brew unlink python@3.15
brew unlink ruby@3.4
/opt/homebrew/opt/ruby@3.4/bin/gem install --install-dir "$HOME/.local/share/whumpo-expo-tools/gems" --bindir "$HOME/.local/share/whumpo-expo-tools/bin" cocoapods:1.17.0 bundler:4.1.0 --no-document
npm install --global --prefix "$HOME/.local/share/whumpo-expo-tools/npm" eas-cli@27.11.0 expo-doctor@1.21.8
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home "$HOME/Library/Android/sdk/cmdline-tools/19.0/bin/sdkmanager" --install 'ndk;27.1.12297006' 'cmake;3.30.5'
# Maestro: extract the official CLI 2.12.1 maestro.zip into the tools prefix.
curl -fL https://github.com/mobile-dev-inc/maestro/releases/download/cli-2.12.1/maestro.zip -o /tmp/whumpo-maestro.zip
unzip -q /tmp/whumpo-maestro.zip -d "$HOME/.local/share/whumpo-expo-tools"
rm /tmp/whumpo-maestro.zip
```

The actual initial Maestro download used the official `releases/latest/download/maestro.zip` URL and returned 2.12.1. Reproduction above pins that resolved version. Avoid extracting over an existing installation; this task's prefix was new.

## Build and validation commands

```sh
npm ci
source scripts/native-env.sh
npx expo prebuild --no-install
pod install --project-directory=ios
npx --yes expo-doctor@1.21.8
npm run lint
npm run typecheck
npm test

# Boot only the brief's private read-only AVD first, then:
adb connect localhost:5621
RCT_METRO_PORT=8619 npx expo run:android --device localhost:5621 --no-bundler

# Replace with the UUID of a newly created disposable simulator:
RCT_METRO_PORT=8619 npx expo run:ios --device "$WHUMPO_SIM_UDID" --no-bundler --no-install --output .native-build/ios-debug
npx expo start --dev-client --port 8619 --localhost
```

SDK58 CLI rejects combining `--port` with `--no-bundler`. The native build commands therefore skip the bundler; the separately started Metro listens only on 8619. Their “Waiting on localhost:8081” message and initial generated launch link are CLI defaults, not a second server. Native launch helpers explicitly connect the development clients to 8619.

Android `assembleDebug` succeeded. Expo CLI then lost the private adb alias during its install step, matching the machine's known adb behavior. The produced APK was copied to `artifacts/android/app-debug.apk`, the private server was reconnected, and that exact APK was installed successfully with `adb -s localhost:5621 install -r -t`. The capture helper reconnects before commands and confirms the native Whumpo UI/package rather than photographing the development launcher. iOS `expo run:ios` completed successfully and produced `artifacts/ios/Whumpo-simulator-debug.app.zip`.

These are development artifacts, not signed App Store/device distributions. They use local Metro for JavaScript; no Expo Go host or backend is involved. The simulator app cannot be installed directly onto a physical iPhone.

## Privileged or missing items

**No privileged command, Xcode license action, password or GUI login was required.** The existing Xcode first-launch check succeeded; Android package licenses were already accepted. There is no pending sudo command for this simulator/emulator workflow. Physical-device signing/account setup was outside the requested scope; ios-deploy/idb were deliberately not installed.

Installed toolchains remain. Project caches, generated build products, Pods and the project's own DerivedData are removed after preserving artifacts. Other Whumpo DerivedData folders and shared Gradle daemons/caches are not cleaned. The disposable simulator is shut down/deleted per the user's mobile-viewing update, and all final screenshots come from the native builds.


## v9 runtime addition

No toolchain was added, upgraded or relinked for v9. Expo SDK58/RN0.88, Node24, isolated JDK17/Ruby/CocoaPods, NDK27.1.12297006 and CMake3.30.5 remain as above.

Added **expo-splash-screen58.0.14** using `npx expo install expo-splash-screen` (MIT; installed package about712 KiB before cleanup). The SDK-matched native module configures the OLED splash and original launcher mark. Android/iOS native builds must be regenerated and rebuilt after adding it. `npm ci` restores the locked JS dependency; `npx expo prebuild --no-install`, then `pod install --project-directory=ios`, must precede native iOS compilation. The explicit iOS scheme is **Whumpo**. Do not overlap native regeneration with xcodebuild.

SDK58 rejects the old `android.edgeToEdgeEnabled` app-config key. It was removed; the current Android target/native edge-to-edge behavior is retained. Expo Doctor verifies the final configuration. The existing `predictiveBackGestureEnabled` configuration is enabled in v9.

v9 font correction: fetched `@expo-google-fonts/inter@0.4.2` with `npm pack` (4.8 MB compressed /8.0 MB unpacked, temporary only). Copied its unmodified Regular/Medium/Semibold TTFs and OFL notice into existing bundled asset paths. Earlier variable-font instances all had the PostScript name Inter-Regular; unique official static names fix iOS face collisions. No font package runtime dependency or font-editing tool was added. The temporary tarball is removed with `.native-build/`. Reproduce with `npm pack @expo-google-fonts/inter@0.4.2` and the three `400Regular/500Medium/600SemiBold` font files. `scripts/check_font_metadata.py` verifies unique names and records hashes.

## v11 keyboard runtime addition

Added **react-native-keyboard-controller1.22.7** using `npx expo install react-native-keyboard-controller`, matching Expo SDK58’s bundled compatibility recommendation. MIT, copyright2021 Kiryl Ziusko; license copied into `assets/licenses/keyboard-controller-MIT.txt`. Installed package footprint6,780 KiB before cleanup. It exposes the native UI-thread keyboard curve and keyboard-aware avoiding view; Reanimated remains4.7.1. Android and iOS clients were regenerated and rebuilt. No toolchain, SDK, JDK or global package was added or changed. Restore with `npm ci`, `npx expo prebuild --no-install`, and the existing user-level CocoaPods/native build commands above.
