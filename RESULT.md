# Whumpo Expo v11 — 1.5.0

v11 keeps the OLED Whumpo design and adds coordinated keyboard compaction, the restored rear-card globe, and the Settings Help button. The sidebar’s 100 dp bottom fade is unchanged. The immutable v10 reference is `v10-snapshot/`; its 1785 files are checked against `artifacts/v10-snapshot-sha256.json`.

**Every current delivered screenshot is from the rebuilt native Whumpo development client, never Expo Go.** Android uses the installed APK in `artifacts/android/app-debug.apk`; iOS uses the simulator app in `artifacts/ios/Whumpo-simulator-debug.app.zip`. These development clients load this project’s JavaScript from local Metro 8619. They are not standalone release distributions, and the simulator app is not an iPhone device build.

## Notes A–D

| Request | Result and evidence |
|---|---|
| A1 Rear layer tucks behind main card | Implemented: upward translation, fading and reveal-height collapse. `K-01`, `K-02` and both motion contact-sheet pairs. |
| A2 Today title / organization choreography | Implemented: the same tappable 48 dp chip moves into the title slot; menu/review controls stay fixed. Native alignment checks and `K-01`. |
| A3 Greeting fades and collapses | Implemented; its natural measurement is independent of the collapsing wrapper. Restored after dismissal, including enlarged text. |
| A4 Card remains above composer | Implemented: UI-thread viewport/IME measurements bound the card; the status and review action stay outside the scrollable body. The body has a soft 12 dp continuation fade. Default resting card minimum 240 dp is retained. Native bounds/pixel measurements below. |
| A5 Tabs animate out and return | Implemented: wrapper fades, slides and collapses; 48 dp child targets stay mounted. Hidden controls leave the accessibility tree. |
| A Reverse / interruption / reduction | Native keyboard progress drives one continuous mapping. Android interruption/restoration is exercised. Reduced motion uses 120 ms opacity crossfades with no additional translations/scales. Accessibility hiding remains until the closed endpoint is restored. |
| A Partner / support / history search | Partner collapses its large introduction and scrolls to the latest draft. Support uses a pushed native screen and the shared keyboard-aware composer. History search follows the IME viewport; its original bottom fade is untouched. Interactive iOS / on-drag Android dismissal is configured. `K-03`, `K-04`, native flow. |
| B Rear globe | Restored Lucide Globe in Website’s hue, 32 dp glyph / 44 dp column, aligned with the front title column. The reveal starts at 68 dp and grows with text. `K-05`, `K-08`. |
| C Settings Help | Restored 48 dp CircleHelp header control labeled Help. Removed the duplicate Settings Help row; About remains. More’s Help row is retained. `K-06` and native navigation checks. |
| D1 Send feedback / Return | 40 dp circular face, stable 48 dp target, .94 press scale 100 ms / release 80 ms, light haptic when reduction is off, native disabled semantics. Single-line Return saves a local draft; multiline Return inserts a line. **Partial:** hardware Shift+Enter on an otherwise single-line field is not implemented; see gaps. |
| D2 Draft / approval feedback | Local draft bubbles enter briefly, scroll into view, and explicitly say Draft · not sent. Draft and approval announcements are implemented; approval feedback stays neutral. iOS and the Android continuation verify draft/approval outcomes; the full Android UIAutomator run has the blocker below. |
| D3 Header restraint | Today has one organization chip. Reviews, Activity and Website retain simple back/title headers. |
| D4 Predictive back | Existing Android predictive-back configuration is enabled; pushed screens use native default stack animations. Sidebar platform-back dismissal is verified. Full physical-device predictive-gesture behavior is not claimed. |
| D5 Loading skeletons | Static, accessible skeletons for Reviews and Activity, without shimmer or loops. `L2-30`, `L2-36`; error/retry states retained. |
| D6 Placeholder / disabled contrast | Verified: placeholder 6.62:1 on `#2A2A2A`; 6.09:1 on the actual `#303030` composer. Disabled arrow 6.09:1. Existing secondary-text token already meets the requirement. |
| D7 Badge clipping /9+ | Visual numeral caps at 9+, exact count remains accessible. 42-count component test and native 200%/214% chrome checks; the synthetic native workspace contains two approvals. |
| D8 Floating-control insets | Today, Partner and More reserve floating-control heights and safe areas; keyboard scenes also reserve native IME height. Long content scrolls. |
| D9 Compact endpoint hook | A noninteractive `compact-mode-*` testID reports the settled shared-value endpoint. Native flow checks hidden/restored content, chip alignment, card/action bounds and restoration of the resting card height. |

## Native measurements and contrast

Measurements below are from the final native PNGs (`artifacts/v11-keyboard-raster.json`). Pixel edges include antialiasing; native accessibility bounds independently measure the Android card/composer gap at 15.62 dp and the send target at 48 × 48 dp.

| Measured item | Android | iOS | Target |
|---|---:|---:|---:|
| Card → composer, keyboard open | 16.76 dp | 16.67 pt | 16 |
| Card → composer, enlarged text + keyboard | 16.76 dp | 16.67 pt | 16 |
| Gold send face, raster diameter | 39.62 × 39.62 dp | 39.67 × 40.00 pt | 40 × 40 |
| Gold send face, fitted radius | 19.81 dp | 19.92 pt | 20 |
| Send face right / bottom inset | 7.62 / 7.62 dp | 8.00 / 8.00 pt | 8 / 8 |
| More tile interior height | 150.86 dp | 151.33 pt | 152 outer bounds |
| More tile left edge | 24.38 dp | 24.33 pt | 24 |
| Canvas / tile surface | #000000 / #303030 | #000000 / #303030 | Exact tokens |

The Android closed card measures 240 dp high before and after keyboard dismissal. The longer one-approval iOS state grows to 247 pt; its body viewport measures 134.67 pt against 133.67 pt of intrinsic content (`v11-ios-resting-guarded.log`). The compact organization chip retains its 48 dp target and aligns with the fixed header buttons. At enlarged text, the body can scroll while the status/action footer stays visible; `K-07` shows this deliberate continuation fade, and `K-08` exposes the enlarged rear-card text and globe.

The complete source-driven color/hue table is `artifacts/contrast-table.md`. Gold remains action/selection only. Pending states are labeled, informational/approved/draft states are neutral, and green is restricted to labeled sample checks.

| Pair | Ratio |
|---|---:|
| White / OLED black |21.00:1|
| Secondary text / Surface `#303030` |6.09:1|
| Placeholder / `#2A2A2A` |6.62:1|
| Disabled arrow / composer Surface |6.09:1|
| Black / action gold |11.81:1|
| Selected rim / black |4.43:1|
| Selected rim / selected fill |3.03:1|

## Visual passes and corrections

1. Reviewed native main/depth sheets and default/large typography. Restored globe/Help and corrected greeting-height feedback, which could leave the greeting collapsed after keyboard dismissal. Long centered headers now fit their reserved slot.
2. Reviewed actual transition frames rather than endpoints alone. Replaced delayed layout estimates with native IME progress/height and UI-thread viewport bounds. The navigator itself stays full size; resizing it caused an iOS animation-idle stall. The support dialog field was behind Android’s IME, so support drafting moved into the existing native screen stack.
3. Reviewed both platforms’ menus, typing, approval outcomes and enlarged text. Excluded generated images/build/test output from Metro so capturing evidence cannot trigger Fast Refresh. Native multiline testing exposed repeated/stale measurement notifications; callbacks now guard before dispatching state and reject stale text layouts. Conversation scroll state is scoped by organization. Android keyboard dismissal clears focus while retaining the draft.
4. A final iOS resting-state assertion exposed a 6 pt shortfall after one approval: intrinsic text measurements made while compact could be ignored. Intrinsic body/footer measurements are now reconciled when compact mode ends. Duplicate integer-height callbacks are rejected before writing shared values; an intermediate continuous-update attempt produced Android accessibility-idle failures despite static pixels, and was rejected. The one-approval iOS native test asserts that the full body fits. The final full Android script still encounters the idle-state blocker below; it is not reported as passing.
5. Final review uses the current native main/L2/K frames and six actual video frames per direction. Card height restores, the rear globe shares the front icon/title columns, the action row clears the composer, and controls retain one Lucide family. The original sidebar fade remains unchanged. Historical rejected attempts remain in logs and iteration directories; they are not counted as passing tests.

Final canonical frames and overview sheets: `compare/v11-final/android/` and `compare/v11-final/ios/`. Before/after files use **v10 left / v11 right**. Base filenames are Android; `-ios` files are iOS. Motion contact-sheet timestamps include resting endpoints and interactive dragging; they are not animation-duration or release-performance benchmarks.

## Verification

- Jest: **42 tests / 7 suites pass**, including keyboard state mapping, reduced motion, Settings Help, rear Globe, line-measurement guards and badge capping. `artifacts/v11-jest.log`.
- ESLint and TypeScript pass. Expo Doctor: **20/20 checks pass**. Corresponding `artifacts/v11-lint.log`, `v11-typecheck.log`, `v11-expo-doctor.log`.
- Android native keyboard checks pass at default and 200%: hidden/restored title, greeting, rear layer and tabs; chip alignment; action/composer bounds; exact resting card-height restoration. `artifacts/v11-keyboard-android.json`.
- iOS native XCTest runs pass for the keyboard/support flow, multiline typing through two approvals, enlarged keyboard layout, and motion/Return behavior: five passing tests, zero failures across the five final result bundles (including the resting-card regression). The final summaries are `v11-ios-core-wrap-summary.json`, `v11-ios-polish-wrap-summary.json`, `v11-ios-large-wrap-summary.json`, `v11-ios-motion-wrap-summary.json` and `v11-ios-resting-guarded-summary.json` under `artifacts/`.
- The already loaded native Android session navigates and creates a local draft with Metro stopped (`artifacts/loaded-offline.json`, `artifacts/v11-loaded-offline.log`); this is not standalone cold-start proof.
- Native raster geometry and contrast checks pass. Source-dependent Android blur and reduced-motion icon checks pass; these are diagnostics, not frame-rate benchmarks.

- Final full Android UIAutomator flow: **blocked** at the one-approval Today state; see the next section. The explicit continuation passes **60 checks**, covering the remaining approval/resolved/reset/pin/account/settings/support/privacy/large-text paths (`artifacts/v11-flow-continuation.json`). This is not a full-run pass.
- Five final iOS result summaries report zero failures. Android and iOS retain actual native open/close videos and six-frame contact sheets; Android interruption returns to the closed endpoint.
- The final set contains **140 native frames** (68 Android, 72 iOS), including all requested main/L2/K views. Native binary/frame/source provenance and immutable-snapshot checks are recorded in `artifacts/v11-native-provenance.json`. Its verification metadata preserves the failed full Android status.

The reference Android viewport is 1080×2340 / density 420 (411.43 dp); iOS is iPhone 16 Pro, 402×874 pt at 3×. Android enlarged text uses `font_scale 2.0`; iOS’s retained `200pct` filenames use Dynamic Type accessibility-large, approximately 214%.

`artifacts/v11-native-provenance.json` binds delivered frames and final source hashes to the installed native APK/iOS executables. `artifacts/v11-sidebar-fade-preserved.json` verifies the unchanged fade invocation. The native blur diagnostic changes the underlying source screen while keeping history foreground/dim/fade constant; platform kernels still differ visually.

## Known Android automation blocker

The final full `scripts/verify_flow.py` run is **blocked**, not green. After the first approval, grouped Reviews is readable, but returning to Today causes UIAutomator to report **could not get idle state**. Native screenshots remain pixel-stable and show the correct remaining item/action. Duplicate layout-write guards improve settling after refresh but do not resolve this full-path reproduction. Manual TalkBack behavior and the cause still need investigation.

`artifacts/flow-verification.json` records `passed: false`; `artifacts/v11-flow-delivered.log` is the actual failed run. `scripts/verify_flow_continuation.py` explicitly uses the visible Reviews header target to leave that state and checks the remaining native paths. It has its own report and does not turn the full run into a pass. Earlier 127-check passing logs predate the final resting-card measurement work and are historical evidence only. No more design/code changes were started after recording this limitation for takeover.

## Dependencies, build and limitations

Added only **react-native-keyboard-controller 1.22.7**, matched with `npx expo install` for SDK 58. MIT notice: `assets/licenses/keyboard-controller-MIT.txt`. Its installed package footprint was 6780 KiB; no toolchain/SDK/JDK/global package was added or changed. Details are in `DEPENDENCIES.md`.

Android APK grows by 1,683,421 bytes relative to v10; the iOS app zip grows by 395,859 bytes. These are debug/development artifacts, not signed store distributions.

Hardware Shift+Enter is the remaining optional polish gap: RN 0.88’s portable TextInput key event does not expose modifier flags. A dedicated platform adapter was not added. Single-line Return, multiline editing/scrolling, and the accessible Insert a new line action are provided. Nothing sends or publishes externally.

All workspace content and evidence are synthetic. Approvals, drafts and most preferences are session-local; organization/pinned-navigation choices persist locally. Support drafting does not contact support. Attachment choices create sample tokens. Physical-device haptics, manual TalkBack/VoiceOver exploration, other phone geometries and iOS mid-opening gesture interruption were not exhaustively tested. Android’s system keyboard color remains OS-controlled. Native curve tracking takes precedence over nominal 240/180 ms timing tokens, including user-controlled interactive dismissal.

## Commands and environment

Representative commands actually used (full outcomes in `artifacts/v11-*.log`):

```sh
df -h /System/Volumes/Data
npm ci
npx expo install react-native-keyboard-controller
source scripts/native-env.sh
npx expo prebuild --no-install
pod install --project-directory=ios
emulator -avd whumpo-client-api36-arm64-local-20261111 -port 5620 -read-only -no-snapshot -no-window -no-audio -no-boot-anim
adb -P 5619 connect localhost:5621
adb -P 5619 -s localhost:5621 shell wm size 1080x2340
adb -P 5619 -s localhost:5621 shell wm density 420
npx expo run:android --device emulator-5620 --no-bundler
adb -P 5619 -s localhost:5621 install -r -t artifacts/android/app-debug.apk
RCT_METRO_PORT=8619 npx expo run:ios --scheme Whumpo --device "$WHUMPO_SIM_UDID" --no-bundler --no-install --output .native-build/ios-debug
npx expo start --dev-client --port 8619 --localhost
npm run lint
npm run typecheck
npm test
npx expo-doctor
.venv/bin/python scripts/capture_android_fast.py PASS
.venv/bin/python scripts/capture_ios.py PASS
.venv/bin/python scripts/verify_flow.py
.venv/bin/python scripts/verify_keyboard_android.py
.venv/bin/python scripts/verify_keyboard_raster.py
.venv/bin/python scripts/keyboard_contacts.py PLATFORM VIDEO
python3 scripts/verify_snapshots.py
```

The Android Gradle assembly succeeded; Expo CLI’s post-build install lost the private emulator alias. Explicit reconnect/install of the produced APK succeeded. iOS compiled successfully with 0 errors and 2 build-phase warnings. SDK 58’s no-bundler CLI prints its default 8081 URL; no server was opened there. Native launch helpers connect only to Metro 8619. Android frames use the required emulator-console screenshot helper; only its 30 px physical letterbox is removed. Comparisons/crops do not repaint app content.

## Cleanup and restoration

Cleanup **passed** (`artifacts/v11-cleanup.json`, `artifacts/v11-cleanup.log`). Metro 8619, private adb 5619 and the owned emulator’s 5620/5621 ports are closed. The disposable iOS simulator is shut down/deleted. wm size/density overrides are reset; the read-only AVD reports physical 1080×2400 / density 420. Its saved font scale 2.0, animator setting null and transition scale 1.0 were restored and verified before shutdown.

Removed this project’s node_modules, .expo, .venv, .native-cache, .native-build, Android build/Gradle/C++ outputs, iOS Pods/build products and exact project-owned DerivedData. Native XCTest bundles were removed after exporting screenshots, logs and result summaries. Installed toolchains and protected/shared repositories/caches remain. Cleanup observed **153 GiB free** on the Data volume; The post-staging free-space reading is retained in the same cleanup report; local Git objects are preserved as requested.

Cold takeover starts at `HANDOFF.md`. All retained v6–v10 snapshots remain byte-identical; v2–v5 Compose references remain in the protected external project. The handoff lists storage/deletion tradeoffs, every script, decisions/rejected options, source/token/navigation maps, known gaps and a first-30-minutes checklist. The local `main` baseline contains the current source and evidence with no remote or push. Run `git log -1 --oneline` and `git status --short` to inspect it.

Restore dependencies and the local development server with:

```sh
npm ci
npx expo start --dev-client --port 8619 --localhost
```

For native rebuilding, source `scripts/native-env.sh`, regenerate with `npx expo prebuild --no-install`, install Pods, and use the commands above with a newly created disposable simulator. The saved simulator UUID is provenance only after cleanup.
