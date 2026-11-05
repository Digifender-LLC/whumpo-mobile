#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'Use a disposable emulator, not a physical device.' >&2; exit 2; }
adb=(adb -s "$ANDROID_SERIAL")
[[ "$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || { echo 'Boot the emulator first.' >&2; exit 2; }
(cd android && ./gradlew :androidApp:assembleLocalDebug :androidApp:assembleLocalDebugAndroidTest --console=plain)
artifacts="${ARTIFACT_DIR:-$root/android/androidApp/build/outputs/worker-journey}"
mkdir -p "$artifacts"
run="$(mktemp -d "$artifacts/run.XXXXXX")"
if [[ -d android/androidApp/build/outputs/androidTest-results/connected ]]; then
  cp -R android/androidApp/build/outputs/androidTest-results/connected "$run/preceding-instrumented-results"
fi
worker=''
recorder=''
cleanup() {
  status=$?
  trap - EXIT
  if [[ -n "$recorder" ]]; then
    "${adb[@]}" shell pkill -INT screenrecord >/dev/null 2>&1 || true
    wait "$recorder" || true
    "${adb[@]}" pull /sdcard/whumpo-worker-journey.mp4 "$run/journey.mp4" >/dev/null 2>&1 || true
    "${adb[@]}" shell rm -f /sdcard/whumpo-worker-journey.mp4 >/dev/null 2>&1 || true
  fi
  "${adb[@]}" logcat -d -v threadtime >"$run/logcat.txt" 2>&1 || true
  if [[ ! -d "$run/screenshots" ]]; then
    "${adb[@]}" pull /sdcard/Android/data/com.whumpo.android.local/files/shots "$run/screenshots" >/dev/null 2>&1 || true
  fi
  if [[ -n "$worker" ]]; then
    kill -TERM "$worker" 2>/dev/null || true
    wait "$worker" || true
  fi
  rm -f "$run/seed.json"
  "${adb[@]}" reverse --remove tcp:8787 >/dev/null 2>&1 || true
  printf 'Journey artifacts: %s\n' "$run"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

PORT=8787 SEED_FILE="$run/seed.json" node tests/android-local-worker.mjs >"$run/worker.log" 2>&1 &
worker=$!
for ((attempt=0; attempt<120; attempt++)); do
  grep -q '^READY 8787$' "$run/worker.log" && break
  kill -0 "$worker" 2>/dev/null || { cat "$run/worker.log"; exit 1; }
  sleep 1
done
grep -q '^READY 8787$' "$run/worker.log" || { cat "$run/worker.log"; echo 'Worker startup timed out.' >&2; exit 1; }
"${adb[@]}" reverse tcp:8787 tcp:8787
"${adb[@]}" logcat -c
"${adb[@]}" shell 'rm -f /sdcard/Android/data/com.whumpo.android.local/files/shots/journey-*.png'
if "${adb[@]}" shell pidof screenrecord >/dev/null 2>&1; then
  echo 'Another screen recording is active; stop it before running the journey.' >&2
  exit 2
fi
"${adb[@]}" shell screenrecord --time-limit 180 /sdcard/whumpo-worker-journey.mp4 >"$run/recorder.log" 2>&1 &
recorder=$!
seed="$(base64 <"$run/seed.json" | tr -d '\n')"
(cd android && ./gradlew connectedLocalDebugAndroidTest --console=plain \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.injected.device.serial="$ANDROID_SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.whumpo.android.WorkerJourneyTest \
  -Pandroid.testInstrumentationRunnerArguments.seed="$seed")
python3 - "$root/android/androidApp/build/outputs/androidTest-results/connected" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
cases = [case for path in pathlib.Path(sys.argv[1]).rglob('*.xml')
         for case in ET.parse(path).iter('testcase')
         if case.get('classname') == 'com.whumpo.android.WorkerJourneyTest']
assert cases, 'WorkerJourneyTest produced no result'
assert all(not any(c.tag in ('skipped', 'failure', 'error') for c in case) for case in cases), 'Worker journey failed or skipped'
print(f'Worker journey: {len(cases)} passed, no skips')
PY
"${adb[@]}" pull /sdcard/Android/data/com.whumpo.android.local/files/shots "$run/screenshots"
for shot in journey-1-signed-in journey-2-chat-sent journey-3-review journey-4-approved; do
  compgen -G "$run/screenshots/$shot-*.png" >/dev/null || { echo "Missing screenshot: $shot" >&2; exit 1; }
done
