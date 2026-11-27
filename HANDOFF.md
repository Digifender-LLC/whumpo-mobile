# Whumpo Expo mock — cold takeover

This is a **React Native + Expo mock of Whumpo**, built to explore and review the mobile design before production integration. The original brief asked for an exact Android Compose copy of the ChatGPT mobile references. The user then asked for Whumpo’s own information architecture, and finally an Expo implementation closer to the original OLED materials. This folder is the Expo project; the Compose project is a separate, read-only reference.

Current app version: **v11 / 1.5.0**, Expo **58.0.26**, React Native **0.88.1**, React **19.3.0**, TypeScript, React Navigation native-stack. OLED `#000000`, neutral rounded surfaces, floating pills, bundled static Inter, standard Lucide icons with short Reanimated animations. No backend, real account integration, publication, payments or external business actions. All data/evidence is synthetic. Organization/pin choices persist locally; approvals/drafts and most preferences are session state.

**Read `RESULT.md` for final verification/cleanup and `DESIGN-LANGUAGE.md` for the design contract. Do not restart design exploration from this handoff.** The user requested wrap-up and a cold takeover, not another iteration. Screenshots are the review surface because the user is on mobile; no live simulator is left running.

## First 30 minutes

1. **0–5 minutes:** Read `AGENTS.md`, this file, `RESULT.md` and `DESIGN-LANGUAGE.md`. Run `git status --short`, `git log --oneline`, `git remote -v`, and `df -h /System/Volumes/Data`. Stop below **15 GB free**. Check the final screenshots and the v10/v11 comparison pairs before editing anything.
2. **5–10:** Inspect `App.tsx`, `src/model.ts`, `src/screens.tsx`, `src/keyboardMotion.tsx`, and `src/AdaptiveHeader.tsx`. Understand draft-only state, derived approval counts, keyboard layout and the immutable history fade. Check `artifacts/v11-native-provenance.json` and `artifacts/v11-cleanup.json`.
3. **10–20:** If execution is needed, `npm ci`, source the project environment, create a Python venv, run Jest/lint/typecheck/doctor. Do not install or upgrade toolchains blindly; `DEPENDENCIES.md` records what is already installed side by side.
4. **20–30:** Start Metro on **8619** and only the private Android emulator or a new disposable iOS simulator. Install the delivered native client or rebuild it after prebuild/Pods. Walk Today → draft → Partner, Today → Reviews → detail → confirm, Settings → Help, history → organization switch. Use an unused visual-pass number; never overwrite frozen snapshots. Finish by restoring/stopping your own devices and services.

## Non-negotiable user decisions

- Preserve the original ChatGPT **design language**: OLED black, generous type/space, flat neutral rounded surfaces with a faint rim, big borderless tiles, floating icon tabs/composer, real blurred history and soft fades. Whumpo supplies the destinations, wording and actions. The rejected first Android attempt copied layout but replaced the design with bordered charcoal/gold cards and a labeled docked tab bar.
- Warm charcoal identity was explored in Compose v3–v5, then **OLED true black** was explicitly restored for Expo. Do not reintroduce cyan. Gold is the single **action/selection** accent, never “success” or “done.” Green is only labeled verified sample evidence. Informational/approved/draft chips are neutral; red is destructive/error. Status must always be stated in text. Maintain AA text contrast.
- **No bespoke in-app icons.** Use Lucide (`lucide-react-native`, ISC), consistent stroke 2. Tinted muted backings are systematic, with no hand-drawn relief. Inter is OFL. Animate UI’s Lucide registry is web/React DOM; RN uses Lucide SVGs and short Reanimated transforms. No idle loops, respect system/local Reduce Motion.
- **More is concise:** tabs are Today / Partner / one pinned destination (Work by default) / More. More shows exactly four non-tabbed feature destinations, plus one neutral Account / Settings / Help list. Work swaps into the grid when another eligible destination is pinned. No Today/Partner duplicates, Projects, Billing, search pill or per-tile overflow. Long press/accessibility action can pin; Settings → Pinned tab is the visible alternative.
- **Today is approvals first:** compact greeting when decisions are pending, one combined main approval card, a narrower shaded rear “Next · Website draft” card, one floating composer. No “Your work” list or invented “all on track” claim. The rear Globe and text align to the front icon/title columns. Counts/copy derive from remaining pending records. No decisions gives a calm resolved state and Activity link.
- **History:** no visible Pinned, Recent, Partner history, Organization, Switch organization labels, and no X. One organization-name/chevron row expands an inline organization picker, followed by a spacer and the conversation list. Add organization opens a preview explanation. Pins distinguish pinned rows; spacing separates groups. Swipe left, tap outside, Android Back or accessibility escape/custom action closes. A root-only left-edge right swipe opens it; native detail back gestures keep priority. Organization data stays scoped.
- **KEEP THE HISTORY BOTTOM FADE EXACTLY:** the user preferred it to the original reference. `HistoryPane.tsx` retains `<Fade height={100} to="#1F1F1F" style={{position:'absolute',left:0,right:0,bottom:0}}/>`. Do not strengthen, recolor, move or redesign it. `artifacts/v11-sidebar-fade-preserved.json` checks the invocation.
- **Keyboard choreography:** rear tucks/fades first; Today title fades; the same organization chip rises into its title slot; greeting collapses; the card/footer stays above the composer with ~16 dp gap; tabs animate away. Native keyboard progress drives a reversible sequence. Large card bodies scroll while status/action stay outside. Reduced motion uses a 120 ms opacity handoff without decorative translations/scales. Hidden elements leave the accessibility tree. Partner compacts its conversation introduction; support has a keyboard-aware pushed screen.
- **Settings Help is top-right** (48 dp CircleHelp). More retains Help; Settings has no duplicate Help row, but keeps About. Do not repeat the earlier v10 removal of the header Help button.
- Composer sends **local drafts only**. Single-line Return creates a draft, multiline editing grows to five lines then scrolls, visible send face is a 40 dp gold circle in a stable 48 dp target. Tabs hide while the keyboard is up. Never claim published/paid/sent without evidence. Approval ends as **Approved, awaiting publish step**. Required decisions cannot be dismissed.
- Preserve safe areas, 48 dp targets, accessible icon labels, native disabled/selected semantics and 200% Android / approximately 214% iOS body text. Chrome caps are deliberate. No decorative loading shimmer; skeletons are static.

## Environment isolation

Work only in `<repo root>`. Do not write into FounderAI, Whumpo, Shortform, the old Android nav mock or `Whumpo-android-chatgpt-design-mock`. Reading reference files there is allowed. Another agent may use those repositories and devices.

| Service | This project only |
|---|---|
| adb server | **5619**, always explicit `adb -P 5619`; never shared 5037 |
| Android console / transport | **5620 / 5621**, serial **localhost:5621** |
| Other emulator ports | Do not touch 5586, 5587, 5600–5603 or other devices |
| Metro | **8619**, not 8081 |
| AVD | Existing `whumpo-client-api36-arm64-local-20261111`, headless/read-only/no snapshots |
| iOS | Create your own disposable named simulator, record its new UUID, delete it at the end |

The delivered `artifacts/ios-device.txt` UUID is historical provenance and is **deleted**, not a device to reuse. No toolchain selection or global npm package should be changed. `scripts/native-env.sh` selects existing isolated JDK/Ruby/Pods/tools for the current shell and confines Gradle/Pods caches to this project.

Known machine quirks:

- adb can die after install; reconnect before commands. **Never use `adb exec-out screencap`**: it can crash the server. Use the emulator console screenshot helper. It needs the existing local `~/.emulator_console_auth_token`; do not print that token.
- Physical emulator screenshots are 1080×2400 with a 30 px top/bottom letterbox at the overridden size. `device.py` removes only that letterbox to deliver **1080×2340**, including the actual status bar. No app pixels are painted over.
- Expo CLI sometimes loses the private adb alias **after a successful Gradle build**. Reconnect and explicitly install the generated APK; do not fall back to Expo Go. `--no-bundler` cannot be combined with `--port` in this SDK. The printed 8081 URL is not another server: launch helpers explicitly load 8619.
- Metro/Jest use the Node crawler (`useWatchman:false`). A previous headless Watchman crawl stalled. Generated captures/builds/snapshots are blocked from Metro so writing PNGs cannot cause Fast Refresh mid-capture. Keep this isolation.
- Keep the native navigator full size during keyboard motion. Resizing it with a root KeyboardAvoidingView caused an iOS idle stall. Scene/footer insets handle keyboard height instead.
- Native iOS sheet portals intermittently lost taps after keyboard use. Sheets use the existing root on iOS and fresh native Modals on Android. Support drafting is a pushed screen because the Android dialog field could sit behind the IME.
- Text-line measurements reject stale reports and guard before dispatching state. Do not restore the native content-size feedback loop. Android keyboard dismissal blurs the field without clearing its draft. Card body/footer layout callbacks also reject duplicate integer heights before shared-value writes; removing those guards can keep Android accessibility busy despite static pixels. Compact measurements are reconciled on the closed keyboard boundary.

## Restore and run

The build caches and `node_modules` are intentionally removed after delivery. Installed toolchains remain; the detailed historical audit and installation commands are in `DEPENDENCIES.md`. Do not rerun its installation section unless a check proves something missing.

```sh
cd <repo root>
df -h /System/Volumes/Data
npm ci
source scripts/native-env.sh
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
npm test
npm run lint
npm run typecheck
npx expo-doctor
# In a separate terminal, source the same environment first:
npx expo start --dev-client --port 8619 --localhost
```

Delivered artifacts are **native development clients**, not Expo Go and not standalone release distributions. Metro supplies local JS/fonts on cold launch. A resident loaded session can continue doing local mock work with Metro stopped; that does not imply standalone cold-start support. The iOS `.app.zip` is simulator-only and cannot install on a physical iPhone.

### Android

```sh
source scripts/native-env.sh
# Keep this process's PID/log; do not kill other emulators.
"$ANDROID_HOME/emulator/emulator" -avd whumpo-client-api36-arm64-local-20261111 \
  -port 5620 -read-only -no-snapshot -no-window -no-audio -no-boot-anim
```

In another project terminal:

```sh
source scripts/native-env.sh
adb -P 5619 connect localhost:5621
adb -P 5619 -s localhost:5621 shell getprop sys.boot_completed
# Save existing font_scale, animator_duration_scale and transition_animation_scale before changing them.
adb -P 5619 -s localhost:5621 shell wm size 1080x2340
adb -P 5619 -s localhost:5621 shell wm density 420
adb -P 5619 -s localhost:5621 install -r -t artifacts/android/app-debug.apk
.venv/bin/python -c "import sys;sys.path.insert(0,'scripts');from device import launch;launch('today',restart=True)"
```

For a rebuild after source/native dependency changes:

```sh
npx expo prebuild --no-install
RCT_METRO_PORT=8619 npx expo run:android --device emulator-5620 --no-bundler
# Even if CLI install fails after BUILD SUCCESSFUL:
cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/android/app-debug.apk
adb -P 5619 connect localhost:5621
adb -P 5619 -s localhost:5621 install -r -t artifacts/android/app-debug.apk
```

Native prebuild reads `app.json`; do not hand-patch generated native directories. Compile SDK 37, target 36, min 31, NDK 27.1.12297006, CMake 3.30.5 are recorded in `DEPENDENCIES.md`.

### iOS

```sh
source scripts/native-env.sh
xcrun simctl list devices available
xcrun simctl list runtimes
# These identifiers existed during v11; verify availability before creating a NEW device.
WHUMPO_SIM_UDID=$(xcrun simctl create 'Whumpo Expo iOS' \
  com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro \
  com.apple.CoreSimulator.SimRuntime.iOS-27-2)
printf '%s\n' "$WHUMPO_SIM_UDID" > artifacts/ios-device.txt
xcrun simctl boot "$WHUMPO_SIM_UDID"
xcrun simctl bootstatus "$WHUMPO_SIM_UDID" -b
npx expo prebuild --no-install
pod install --project-directory=ios
RCT_METRO_PORT=8619 npx expo run:ios --scheme Whumpo --device "$WHUMPO_SIM_UDID" \
  --no-bundler --no-install --output .native-build/ios-debug
xcrun simctl install "$WHUMPO_SIM_UDID" .native-build/ios-debug/Whumpo.app
node scripts/ios-open.cjs __development
node scripts/ios-open.cjs today
# Save a simulator artifact if rebuilt:
mkdir -p artifacts/ios
ditto -c -k --sequesterRsrc --keepParent .native-build/ios-debug/Whumpo.app \
  artifacts/ios/Whumpo-simulator-debug.app.zip
```

To inspect the delivered iOS app without rebuilding, unzip it into a temporary project build directory and `simctl install` the resulting `Whumpo.app`. Still run Metro. Do not overlap prebuild/Pods with xcodebuild.

## Navigation and state

- Roots: Today; Partner; pinned Work/Reviews/Website/Activity; More. More shows non-tabbed features + Account/Settings/Help.
- Today → Reviews → Approval detail → Approve/Request changes confirmation → updated groups/counts; About → Reset sample data restores the two decisions.
- Website → Review changes → Approval detail; Website → Publish-ready checklist (no publish action).
- Activity → All/Approvals/Partner/Website filters. Organization → Members & permissions / switch organization.
- Work → Studio website or Launch plan → Project/tasks → Reviews.
- Settings → Account, Pinned tab/live tab preview, Reset navigation, Organization, Approval reminders → Quiet hours, Appearance → Reduce motion, Privacy & data → export confirmation, About → licenses/sample note/Preview states/reset. Header Help → Help.
- More Help → expandable FAQ → Contact support (pushed local-draft screen). Account → sign-in methods / destructive sign-out confirmation.
- Partner → root history/conversation list, new-conversation empty prompts, attachments sheet, draft composer and unsent bubble. History → scoped inline organization picker, search/no-results, New, Settings. Today’s org chip opens the organization sheet.
- Deep links `whumpoexpo://<scene>` are capture/development conveniences, handled by `App.tsx`; they select real components and synthetic fixture states. They do not bypass real interactions in `verify_flow.py`.

`src/model.ts` centralizes reducer/state, `pendingApprovals`, `approvedApprovals`, `todayApprovalCopy`, `moreDestinations`, `chipVariant` and scoped org/pin/draft records. Approval is idempotent; request changes remains pending. Only org/pins are hydrated from AsyncStorage. Draft action never sends/publishes anything.

## Component/token map

| File | Owns |
|---|---|
| `App.tsx` | Providers/fonts, state/storage, native routes/deep links, overlays/sheets, IME/footer insets, announcements and draft routing |
| `src/model.ts` | Product fixtures/reducer/derivations; `colors`, `hues`, `spacing`, `typeScale` |
| `src/screens.tsx` | Today, bounded scroll-body approval stack/rear Globe, More tiles |
| `src/keyboardMotion.tsx` | Native keyboard progress, reduced path, `compactState`, endpoint probe |
| `src/AdaptiveHeader.tsx` | Today chip/title choreography, compact Partner header, single header action |
| `src/Chrome.tsx` | Header controls/tabs and selected state |
| `src/NavigationChrome.tsx` | History gestures/edge opener, root vs pushed navigation behavior |
| `src/HistoryPane.tsx` | History rows, inline organization picker, search, **unchanged bottom fade**, New/capsule |
| `src/details.tsx` | Reviews/detail/Website/Activity/settings/account/help/organization subpages |
| `src/product-ui.tsx` | Reusable rows, reading layouts, state messages, skeletons, dialog content |
| `src/ui.tsx` | Text/font aliases, status semantics, badges, avatar initials, composer/send circle, rims/fades |
| `src/AnimatedIcon.tsx` | Standard Lucide lookup, functional transforms, system/local motion context |
| `src/interaction.ts` | Shared press/ripple treatment |
| `src/keyboard.ts` | Older keyboard-visibility helper; not the v11 native-progress source |
| `app.json` | Native IDs, portrait/dark configuration, icon/splash, predictive-back/build properties/dev-client plugins |
| `assets/fonts/`, `licenses/`, `assets/licenses/` | Static Inter and license notices; keyboard controller MIT |
| `metro.config.js`, `babel.config.js`, `tsconfig.json`, `eslint.config.js`, `jest.setup.js` | Tool configuration; exclude immutable snapshots/generated evidence from app/tool crawling |

Key tokens: canvas `#000000`; surfaces `#303030`/`#212121`; selected `#2A2A2A` with `#737373` rim; text `#FFFFFF`/`#B0B0B0`/`#CECECE`; action gold `#E0BE78` with black text. `DESIGN-LANGUAGE.md` is authoritative for geometry, full contrast/hue/chip/type tables and keyboard timing. Do not copy values into new parallel token systems.

## Checks and evidence

Expected current JS checks: **42 tests / 7 suites**, lint and typecheck clean, Expo Doctor **20/20**. **The final full Android verify_flow.py is blocked at UIAutomator idle after the first approval/return to Today.** The separate continuation checks remaining paths; it is not a full-run pass. iOS native tests and the Android keyboard checks are listed in RESULT.md. Native checks and any exception are recorded in `RESULT.md`; do not treat an old log whose filename says “final” as proof. Use the explicitly listed final successful summaries/logs there.

```sh
npm test
npm run lint
npm run typecheck
npx expo-doctor
.venv/bin/python scripts/contrast.py
.venv/bin/python scripts/check_font_metadata.py
python3 scripts/verify_snapshots.py
# Needs installed native Android client + Metro/private emulator; clears ONLY this app's data:
.venv/bin/python scripts/verify_flow.py
.venv/bin/python scripts/verify_keyboard_android.py
```

Jest mocks native animation/keyboard modules for state/semantics/component assertions. It does **not** prove native motion or rendered pixels. Native Android uses UIAutomator/input and console screenshots; native iOS uses XCTest and simctl. The iOS UI-test harness is generated by `source scripts/native-env.sh; ruby scripts/create_ios_test_project.rb`; its Swift tests are `scripts/ios_polish.swift`. An example run uses `xcodebuild -project .native-build/ios-ui-check/WhumpoSmoke.xcodeproj -scheme WhumpoNativeSmoke -destination "platform=iOS Simulator,id=$WHUMPO_SIM_UDID" -derivedDataPath .native-build/ios-ui-derived -resultBundlePath <NEW-path>.xcresult -only-testing:WhumpoNativeSmoke/WhumpoPolish/testKeyboardFlow CODE_SIGNING_ALLOWED=NO test`. Result bundle paths must be unused.

Canonical evidence: `screenshots/android/`, `screenshots/ios/`, `compare/v11-final/`, three v10/v11 pairs per platform, four keyboard contact sheets, native videos and `artifacts/v11-native-provenance.json`. Main 01–07 are Today/More/History/Settings/Partner/default+large. L2 files cover deeper menus and local outcomes. K-01..08 cover keyboard endpoints, Partner/support, rear crop, Settings Help and enlarged Today. P/A files retain typing/outcome/large-menu evidence. Contact sheets are crops/resizes of real native video frames, not fabricated motion. The native provenance records exact binary/frame/source hashes. Historical failed runs remain diagnostic material and are not passing evidence.

## Script inventory

**Current helpers first.** Read arguments before rerunning; capture scripts overwrite current screenshots and some fixed comparison filenames. Use a fresh pass directory/unused result bundle. Version-specific legacy drivers are retained for history and must not be launched blindly on v11.

| Script | Purpose / caution |
|---|---|
| `scripts/audit_handoff.py` | Checks handoff script coverage, delivered paths, successful reports and absence of placeholder claims. |
| `scripts/capture_android.py` | Earlier slower visible-route native Android capture driver; prefer capture_android_fast.py. |
| `scripts/capture_android_fast.py` | Current full Android native pass: main seven, real typing, L2 routes/outcomes; argument is pass number. |
| `scripts/capture_ios.py` | Native iOS main seven and L2 routes at default/accessibility-large; pass-number argument. |
| `scripts/capture_large.py` | Extra large-text menus for android or ios; requires native client and private device. |
| `scripts/capture_large_groups_android.py` | Large Android grouped-review/confirmation evidence helper from v10. |
| `scripts/capture_polish_android.py` | v10 typing/outcomes/Account/Settings evidence driver, useful reference but fixed v10 paths. |
| `scripts/check_font_metadata.py` | Checks static Inter PostScript names and hashes, preventing iOS face collisions. |
| `scripts/cleanup_native.py` | v11 owned-device/cache cleanup; requires native provenance and Metro already stopped. Reads v11 initial settings; do not blindly reuse those for a new session. |
| `scripts/compare_v10.py` | Historical v9/v10 pair and overview generator; fixed legacy directory conventions. |
| `scripts/compare_v11.py` | v10 snapshot left/current-pass right; argument pass number or final; writes overview sheets and root pairs. |
| `scripts/compare_v8.py` | Historical v7/v8 comparison and stack close-up; not current v11 packaging. |
| `scripts/compare_v9.py` | Historical v8/v9 pairs/sidebar close-up; not current v11 packaging. |
| `scripts/contrast.py` | Source-driven WCAG text/glyph/chip/selection tables and contrast JSON. |
| `scripts/copy_ios_test_frames.py` | Legacy iOS XCTest screenshot exporter; prefer export_v11_ios.py. |
| `scripts/copy_polish_ios.py` | Legacy P/A/L2 attachment copier for polish bundles; prefer export_v11_ios.py. |
| `scripts/create_ios_test_project.rb` | Generates an XCTest UI-test project around ios_polish.swift; uses isolated Ruby xcodeproj gem. |
| `scripts/device.py` | Android private-adb reconnect, fresh UIAutomator trees, input, native launch and console screenshots; shared imports. |
| `scripts/emu_shot.py` | Console-only PNG capture: console port and output directory arguments; no adb screencap. |
| `scripts/emulator_console.py` | Authenticated commands to only the owned emulator console (screenshots/video/stop). |
| `scripts/export_v11_ios.py` | Exports named XCTest screenshot attachments to current iOS evidence and a chosen pass. |
| `scripts/final_ios_v10.py` | Legacy coordinator for v10 polish/capture/large passes. |
| `scripts/finalize_evidence.py` | Legacy evidence finalizer; use finalize_v11.py for current native provenance. |
| `scripts/finalize_v11.py` | Verifies installed APK/iOS executables, passing evidence and snapshots; binds source/frame hashes and copies canonical final evidence. |
| `scripts/finish_android_v10.py` | Legacy v10 Android large/state finalizer; do not use for current provenance. |
| `scripts/finish_ios_large_v10.py` | Legacy v10 large review grouping/confirmation capture. |
| `scripts/finish_ios_resting_v11.py` | Wrap-up native iOS capture + isolated XCTest/large/motion driver; uses fixed wrap bundle names, so choose unused names on repeat. |
| `scripts/finish_ios_v10.sh` | Legacy v10 iOS capture finishing shell sequence. |
| `scripts/finish_ios_v11.py` | Earlier v11 large/motion finishing driver; fixed result paths, inspect before reuse. |
| `scripts/finish_native_checks.py` | Legacy native verification/capture finishing driver from earlier versions. |
| `scripts/ios-open.cjs` | Launch one native simulator client or scene on the UUID in artifacts/ios-device.txt, Metro 8619. |
| `scripts/ios_polish.swift` | Current native iOS typing, approval, keyboard, large-text, Return and resting-card regression tests. |
| `scripts/ios_smoke.swift` | Earlier native iOS smoke/gesture checks; current generated harness points to ios_polish.swift. |
| `scripts/keyboard_contacts.py` | Selects six real video frames each direction via chip movement; PLATFORM VIDEO arguments. |
| `scripts/measure.py` | Scan native PNGs for OLED/surface colors and tile width/height/inset measurements. |
| `scripts/native-env.sh` | Source shell-local private adb, JDK/Ruby/Pods/tool paths and project cache locations. |
| `scripts/nav_ui.py` | Visible Android route traversal, scroll/back/root helpers, scene list and validated warm deep links. |
| `scripts/recapture_ios_roots.py` | Legacy v10 settled Today-root recapture after v10 finishing log; would wait on old state. |
| `scripts/record_keyboard_android.py` | Console records native open/close and interrupted opening; asserts the restored closed endpoint. |
| `scripts/record_motion.py` | Console native icon-press recording, no idle loop; writes debug frame metrics. |
| `scripts/run_android_v10.sh` | Legacy Android v10 sequence; fixed outputs and settings; do not launch blindly. |
| `scripts/run_ios_polish.sh` | Legacy v10 XCTest polish runner, fixed v10 bundle/log paths. |
| `scripts/run_ios_v11_remaining.py` | Earlier v11 polish/large/motion sequencing driver; fixed checked result paths, retained as history. |
| `scripts/verify_blur.py` | Checks that the Android blur samples changed underlay content, preserving foreground/dim/fade. |
| `scripts/verify_dialog.py` | Android modal semantics/dismissal diagnostic retained from prior polish. |
| `scripts/verify_flow_continuation.py` | Explicit continuation from the one-approval Today UIA blocker via the existing header button; does not claim the full flow passed. |
| `scripts/verify_flow.py` | Destructive-to-this-preview-only Android E2E: clears app sample state; draft/org/pin/approval/help/support/large paths. |
| `scripts/verify_keyboard_android.py` | Native compact endpoints, header/action bounds, restored height, Help/support and large keyboard captures. |
| `scripts/verify_keyboard_raster.py` | PIL checks card/composer gaps and send circle on both native K sets; produces native rear-card crops. |
| `scripts/verify_layout.py` | Native Android header/CTA alignment and touch bounds diagnostic. |
| `scripts/verify_loaded_offline.py` | Checks already-loaded Android navigation/local drafting after Metro stops; explicitly not cold-start release proof. |
| `scripts/verify_motion.py` | Compares active and system-reduced icon video regions; not a release performance benchmark. |
| `scripts/verify_send_raster.py` | Measures the actual gold pixel silhouette, circle fit and composer insets. |
| `scripts/verify_sidebar_v9.py` | Original v9 Android history gesture/organization/semantics regression driver. |
| `scripts/verify_snapshots.py` | SHA-256 immutability checks for every retained Expo snapshot v6–v10. |

## Version history

The numbers are design iterations, not Expo SDK versions. The Compose archive lives outside this project and must remain read-only.

| Pass | What changed and why | Preserved location |
|---|---|---|
| v2 | Exact ChatGPT geometry/materials with Whumpo copy; preserved when user approved the visual copy | Compose `v2-chatgpt-exact/` |
| v3 | Whumpo warm charcoal/gold identity, real product destinations/approval-first IA, bespoke glyph family | Compose `v3-whumpo/` |
| v4 | Less Today crowding, restrained per-destination hues, clearer header icons; user’s preferred fade preserved | Compose `v4-bespoke-icons/` |
| v5 | Removed bespoke icons/relief, adopted standard Lucide plus functional motion and systematic backings | Current read-only Compose source/artifacts; no separate v5 snapshot directory found |
| v6 | New Expo/RN implementation, returned to original OLED neutral materials, animated Lucide/Whumpo IA | `v6-snapshot/` |
| v7 | Native Android/iOS clients and toolchain proof; consistent headers/approval hierarchy, primary CTA, large-text fixes | `v7-snapshot/` |
| v8 | Concise four-tile More, grouped Account/Settings/Help, polished physical rear card, removed competing Today work list | `v8-snapshot/` |
| v9 | Label-free gesture history/org dropdown, deeper native menus/approval flow, app identity and preview states | `v9-snapshot/` |
| v10 | Circular send/keyboard tab hiding, remaining-approval copy/groups/resolved state, chip semantics, icon/settings consistency | `v10-snapshot/` |
| v11 | Native-progress keyboard choreography, rear Globe, Settings Help restored, support keyboard screen, measured/restored card sizing | Current source and canonical native evidence |

### Snapshot space and deletion policy

**No snapshots were deleted during wrap-up.** Never edit them. Expo snapshots retain source/evidence without node_modules or build caches. `artifacts/vN-snapshot-sha256.json` files are the immutability manifests.

| Copy | Approximate current size | Can a future owner remove it to save space? |
|---|---:|---|
| `v6-snapshot/` | 98 MiB | Runtime/build independent archive; safe after archival/user choice. Snapshot verifier must be adjusted if deliberately removed. |
| `v7-snapshot/` | 300 MiB | Same; preserves initial native/toolchain result. |
| `v8-snapshot/` | 232 MiB | Same; preserves concise-menu baseline. |
| `v9-snapshot/` | 402 MiB | Same; preserves before v10. |
| `v10-snapshot/` | 563 MiB | Runtime independent, but **keep for repeatable current v11 comparisons and snapshot checks**; only remove after archiving/changing those inputs. |
| Compose v2/v3/v4 archives | External protected project | Do not prune from this task/repository. Their full paths are below. |
| Compose v5 | External current source, not a frozen copy | Do not delete or modify it. |

External root: `<archived android design mock>`; confirmed archive names are `v2-chatgpt-exact`, `v3-whumpo`, `v4-bespoke-icons`. v5 is that project’s current source/artifacts. Original measured references are its `reference/DESIGN-SPEC.md` and `reference/assets/ref-*.jpg`. They are not copied into this Expo folder again.

Old `compare/v*-pass*/` iterations and superseded logs/videos are not runtime dependencies. They can be archived later if space matters, while retaining canonical final frames, native binaries, provenance, final successful logs and the four motion contact sheets. Root comparison sets with v6–v10 names are historical; only the explicitly named v11 set is current. The baseline commit retains tracked archives: removing a working copy frees that copy’s space but does not purge Git objects. No history rewrite/garbage collection is authorized merely by this note.

## Known gaps and deliberately unfinished ideas

- **Android full E2E is blocked:** UIAutomator cannot idle after returning from grouped Reviews to Today with one decision left. Pixels are static and copy/counts look correct. Ref guards did not fix the full reproduction. `flow-verification.json` is explicitly failed; `v11-flow-continuation.json` records the remaining paths after entering Reviews via its visible header target. Reproduce with verify_flow.py before further work, investigate native accessibility/layout events, and manually check TalkBack. Keep device.py’s fresh-tree guard: a failed dump must not reuse stale XML or be relabeled passing. Do not call the full Android suite passing based on old 127-check logs.

- **Hardware Shift+Enter on an otherwise single-line input:** RN’s portable key event does not expose modifier flags. Single-line Return, multiline editing/scrolling and the accessible Insert a new line action work. A platform modifier-key adapter was not added.
- Development clients require local Metro on cold launch. A standalone release build, App Store/device signing, backend/authentication, real publication/payments/notifications, physical file/photo attachment and external support delivery are not implemented.
- Physical-device haptics, exhaustive TalkBack/VoiceOver use, additional phone sizes/tablets/landscape and full physical Android predictive-back behavior are not claimed. Portrait is locked; the two specified native phone viewports and enlarged text were exercised.
- Native blur kernels and keyboard chrome differ by OS; pixel equivalence between Android/iOS is not claimed. The bottom fade itself is fixed, not a design gap to “improve.”
- Tests deliberately use sample evidence/status, not real verification. Offline/list errors and skeletons are fixture-driven. The old New design toggle was removed because only one renderer exists; do not expose a switch with no real alternative.
- Do not implement future ideas without a new request. A new agent should first read final verification/known gaps rather than adding features or redesigning the menu.

## Cleanup / Git baseline

`artifacts/v11-cleanup.json` records restored wm/font/animation settings, closed private ports, deleted disposable simulator and removed project build/dependency caches. Installed side-by-side toolchains remain. Shared adb, other emulators/Metro instances, protected repositories and unrelated caches/DerivedData were not cleaned.

For a future session: save its initial settings, reset wm size/density before stopping only console 5620, restore its saved font/animation values, stop private adb 5619 and Metro 8619, shut down/delete only its recorded simulator UUID, stop only project-scoped build daemons, and delete project node_modules/.expo/.native-cache/.native-build/Pods/build/DerivedData. `cleanup_native.py` is a v11 record-specific helper; adapt initial-setting/provenance paths before reusing it. Never use broad `pkill`, shared `adb kill-server`, `simctl shutdown all`, or delete another repo’s cache.

This folder originally had Expo’s template-only Initial commit. Wrap-up establishes **one local current-state baseline on `main`**, replacing that template commit, with **no remote and no push**. The author identity is the local automation identity (`Codex`, `codex@localhost`), not a fabricated user email. Run `git log -1 --oneline` for the final ID; it is intentionally not embedded in tracked files because that would change the commit. A clean `git status --short` is the takeover baseline. Native generated projects/caches are ignored; source, lockfile, artifacts, screenshots, comparisons and immutable snapshots are tracked.
