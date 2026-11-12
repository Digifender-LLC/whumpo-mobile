#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
api="${1:-}"
[[ "$api" == 28 || "$api" == 36 ]] || { echo 'Expected API 28 or 36.' >&2; exit 2; }
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$ANDROID_SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Use a disposable emulator.' >&2; exit 2; }
# AGP consumes ANDROID_SERIAL for selection; verify that exact target's actual API.
if ! device_api="$(timeout -k 2s 10s adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk)"; then
  echo 'Could not verify the selected emulator API.' >&2
  exit 2
fi
device_api="${device_api%$'\r'}"
[[ "$device_api" == "$api" ]] || { echo 'Selected emulator API does not match the requested API.' >&2; exit 2; }
out=androidApp/build/outputs/instrumented-diagnostics
mkdir -p "$out"
capture_anr() {
  local summary
  if summary="$(timeout -k 2s 10s adb -s "$ANDROID_SERIAL" logcat -b events -d -v raw am_anr:I '*:S' 2>/dev/null | python3 scripts/anr-summary.py 2>/dev/null)"; then
    printf '%s\n' "$summary" >"$1"
  else
    printf '%s\n' '{"version":1,"status":"unavailable","reason":"capture"}' >"$1"
  fi
}
capture() {
  status=$?
  trap - EXIT
  printf '%s\n' "$status" >"$out/test-exit-code.txt" || true
  # The action stops the emulator when this script returns. Capture first, without hiding a test failure.
  timeout -k 2s 10s adb -s "$ANDROID_SERIAL" get-state >"$out/device-state.txt" 2>&1 || true
  timeout -k 2s 10s adb -s "$ANDROID_SERIAL" logcat -d -v threadtime WhumpoTreeDiagnostics:E '*:S' >"$out/tree-failures.txt" 2>&1 || true
  capture_anr "$out/anr-after.json" || true
  exit "$status"
}
trap capture EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
capture_anr "$out/anr-before.json" || true
# A previous run's reports cannot establish that this invocation executed tests.
results=androidApp/build/outputs/androidTest-results/connected
rm -rf "$results"
./gradlew connectedLocalDebugAndroidTest connectedDeployedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.whumpo.android.WorkerJourneyTest
python3 scripts/verify-instrumented-results.py "$results" "$api" "$ANDROID_SERIAL"
