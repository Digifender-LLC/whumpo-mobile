This is an Expo/React Native mobile application. Prioritize mobile-first patterns, performance, and cross-platform compatibility.

## Expo has changed — do not trust your training data

Expo ships breaking changes every SDK release. APIs you remember are likely renamed, moved, or removed. Before writing any code that touches an Expo, EAS, or React Native API:

1. Read the major version of the `expo` package in `package.json`.
2. Fetch the matching versioned docs: `https://docs.expo.dev/versions/v<major>.0.0/`
3. For anything else, fetch https://docs.expo.dev/llms.txt — an index of all Expo docs with corrections to common LLM misconceptions. Follow its links to the specific page you need; never answer from memory.

## Commands

Use `bunx` instead of `npx` if the project uses bun (`bun.lock` present).

```bash
npx expo install <package>  # ALWAYS use instead of npm/yarn/pnpm/bun add — resolves SDK-compatible versions
npx expo start              # start the dev server
npx expo lint               # lint
npx tsc --noEmit            # typecheck
npx expo-doctor             # diagnose dependency and config issues
npx expo install --fix      # fix incompatible package versions
```

Run lint and typecheck before declaring any task done.

## Navigation & Routing

- This mock intentionally uses React Navigation native-stack, as chosen in the v6 user brief. Preserve the existing Today / Partner / flexible slot / More flows; no router migration is part of a polish pass.
- Route setup is in `App.tsx`; shared UI, state and screens are in `src/`.
- Keep the `v6-snapshot/` `v7-snapshot/` and `v8-snapshot/` and `v9-snapshot/` and `v10-snapshot/` source/evidence immutable and excluded from builds/tests/watchers.

## Building with EAS

Use EAS to build, sign, and submit the app in the cloud (`eas build`, `eas submit`) and to ship over-the-air updates (`eas update`) — no local Xcode or Android Studio required. Run EAS CLI as `bunx eas-cli <command>` in Bun projects, or `npx eas-cli@latest <command>` otherwise; substitute that for bare `eas` in docs examples.
Docs: https://docs.expo.dev/eas/index.md

## Rules

- If `ios/` and `android/` directories do not exist, they are generated (Continuous Native Generation). Never create or edit them by hand — configure native behavior in `app.json` and config plugins.
- Expo Go only includes its bundled native modules. After adding a library with native code, the app needs a development build: `npx expo run:ios|android` locally, or `eas build --profile development`.
- Prefer recommended Expo modules over third-party libraries, and check your available skills before adding dependencies. Docs: https://docs.expo.dev/versions/latest/index.md

## v11 cold takeover rules

Read `HANDOFF.md`, `RESULT.md` and `DESIGN-LANGUAGE.md` before edits. This is a local offline preview, not a connected production app. The current full Android UIAutomator flow has a documented idle-state blocker after one approval; do not misreport old passing logs as current. Do not start a new design iteration without a new request.

- Work in this Expo folder only. FounderAI, Whumpo, Shortform, the old Android nav mock and the Compose mock are protected against writes; Compose references may be read.
- Check `df -h /System/Volumes/Data` first; stop below 15 GB free. No new toolchain/default changes without need and authorization.
- Private adb 5619, emulator console 5620 / transport 5621 (`localhost:5621`), Metro 8619 only. Never shared adb 5037 or ports 5586/5587/5600–5603. Existing AVD is headless/read-only/no snapshots.
- Native Whumpo clients only for screenshots, never Expo Go. Android console screenshots, never adb screencap. Reconnect before adb commands. Use/delete only an owned disposable iOS simulator; no live panel is required.
- Preserve the history bottom fade exactly. Standard Lucide only; gold action/selection only; OLED black; four non-tabbed More tiles; draft-only and never-published honesty rules. Detailed decisions are in HANDOFF.md.
- Keep immutable v6–v10 snapshots unchanged. The external Compose v2–v5 references are protected too. Read-only snapshots are excluded from app compilation/crawling.
- At completion restore owned wm/font/animation settings; stop only owned devices, private adb and Metro; remove project dependency/build caches and Pods/DerivedData while retaining source, lockfile, artifacts/screenshots/compare. Do not stop shared services or prune another project's caches.
- Builds for this handoff are local. No EAS publication, remote Git setup or push is authorized by a cold takeover.
- When interacting with Cloudflare, use the cf CLI unless this project has a Wrangler configuration file.
