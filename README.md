# Whumpo mobile

The Whumpo mobile app: React Native + Expo SDK 58 (development builds, not Expo Go) with OLED-black surfaces, concise navigation, gesture-driven Partner history and local approval decisions.

**Status: demo.** The app runs on local fixtures only. There is no backend connection or external business action yet; wiring it to the Whumpo monorepo is future work. The earlier native Android/Wear app was retired when this Expo app replaced it; it remains in git history at commit `d482436`.

## Run

```sh
npm ci
source scripts/native-env.sh
npx expo start --dev-client --port 8619
```

## Check

```sh
npm run typecheck
npm run lint
npm test
npx expo-doctor
```

## Docs

- [HANDOFF.md](HANDOFF.md) — working notes from the demo iterations
- [RESULT.md](RESULT.md) — delivery record for the last demo build
- [DESIGN-LANGUAGE.md](DESIGN-LANGUAGE.md) — visual and motion rules
- [DEPENDENCIES.md](DEPENDENCIES.md) — dependency choices and native build notes
- [AGENTS.md](AGENTS.md) — instructions for coding agents

Generated native projects (`android/`, `ios/`) are not committed; recreate them with `npx expo prebuild --no-install`. The Python capture helpers in `scripts/` need a venv with `requirements-dev.txt` and refer to local emulator/simulator setups from the demo phase.
