#!/usr/bin/env bash
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/ci-instrumented.sh"
python3 "$(dirname "$script")/test-anr-summary.py"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/android/scripts" "$fixture/bin"
cp "$script" "$fixture/android/scripts/ci-instrumented.sh"
cp "$(dirname "$script")/anr-summary.py" "$fixture/android/scripts/anr-summary.py"
cp "$(dirname "$script")/verify-instrumented-results.py" "$fixture/android/scripts/verify-instrumented-results.py"
cat >"$fixture/android/gradlew" <<'SH'
#!/usr/bin/env bash
printf 'gradle %s\n' "$*" >>"$CALLS"
for flavor in local deployed; do
  report="androidApp/build/outputs/androidTest-results/connected/debug/flavors/$flavor"
  mkdir -p "$report"
  case "${RESULT_MODE:-pass}" in
    pass) echo '<testsuites tests="2" failures="0" errors="0"><testsuite><testcase name="synthetic"/><testcase name="unavailable"><skipped/></testcase></testsuite></testsuites>' >"$report/TEST-device.xml" ;;
    empty) echo '<testsuites tests="0"/>' >"$report/TEST-device.xml" ;;
    fail) echo '<testsuites tests="1" failures="1"><testsuite><testcase name="testRunFailed"><failure>Expected 51 tests, received 0</failure></testcase></testsuite></testsuites>' >"$report/TEST-device.xml" ;;
    skipped) echo '<testsuites tests="1"><testsuite><testcase name="unavailable"><skipped/></testcase></testsuite></testsuites>' >"$report/TEST-device.xml" ;;
    malformed) echo '<testsuites' >"$report/TEST-device.xml" ;;
    inconsistent) echo '<testsuites tests="2"><testsuite><testcase name="synthetic"/></testsuite></testsuites>' >"$report/TEST-device.xml" ;;
    local-only) [[ "$flavor" != local ]] || echo '<testsuites tests="1"><testsuite><testcase name="synthetic"/></testsuite></testsuites>' >"$report/TEST-device.xml" ;;
    missing) ;;
  esac
done
exit "$GRADLE_STATUS"
SH
cat >"$fixture/android/scripts/test-worker-journey.sh" <<'SH'
#!/usr/bin/env bash
echo journey >>"$CALLS"
exit 99
SH
cat >"$fixture/bin/adb" <<'SH'
#!/usr/bin/env bash
{
  printf 'adb'
  printf '|%s' "$@"
  printf '\n'
} >>"$CALLS"
case "$*" in
  "-s emulator-5554 logcat -b events -d -v raw am_anr:I *:S")
    echo '[0,1234,synthetic-private-process,0,synthetic-private-reason]'
    echo synthetic-private-stderr >&2
    exit "$ADB_STATUS"
    ;;
  "-s emulator-5554 get-state"|"-s emulator-5554 logcat -d -v threadtime WhumpoTreeDiagnostics:E *:S")
    echo synthetic-device-state
    exit "$ADB_STATUS"
    ;;
  *)
    echo unexpected-adb-command >&2
    exit 98
    ;;
esac
SH
cat >"$fixture/bin/timeout" <<'SH'
#!/usr/bin/env bash
[[ "$1 $2 $3" == '-k 2s 10s' ]] || exit 99
shift 3
[[ "${1:-}" == adb ]] || {
  printf 'unexpected-timeout-command|%s\n' "$*" >>"$CALLS"
  exit 98
}
exec "$@"
SH
chmod +x "$fixture/android/gradlew" "$fixture/bin/"*
export PATH="$fixture/bin:$PATH" ANDROID_SERIAL=emulator-5554
export CALLS="$fixture/calls" GRADLE_STATUS=0 ADB_STATUS=0
out="$fixture/android/androidApp/build/outputs/instrumented-diagnostics"
run() {
  local api=$1 expected=$2 status=0
  : >"$CALLS"
  bash "$fixture/android/scripts/ci-instrumented.sh" "$api" || status=$?
  [[ "$status" == "$expected" && "$(cat "$out/test-exit-code.txt")" == "$expected" ]]
  [[ "$(grep -c '^gradle ' "$CALLS")" == 1 ]]
  grep -Fxq 'gradle connectedLocalDebugAndroidTest connectedDeployedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.whumpo.android.WorkerJourneyTest' "$CALLS"
  local anrCall='adb|-s|emulator-5554|logcat|-b|events|-d|-v|raw|am_anr:I|*:S'
  local expectedAdb="$anrCall"$'\nadb|-s|emulator-5554|get-state\nadb|-s|emulator-5554|logcat|-d|-v|threadtime|WhumpoTreeDiagnostics:E|*:S\n'"$anrCall"
  [[ "$(grep -c '^adb|' "$CALLS")" == 4 ]]
  [[ "$(sed -n '/^adb|/p' "$CALLS")" == "$expectedAdb" ]]
  [[ "$(grep -c '^unexpected-' "$CALLS" || true)" == 0 ]]
  ! grep -Fq dumpsys "$CALLS"
  [[ "$(grep -c '^journey$' "$CALLS" || true)" == 0 ]]
  [[ "$(find "$out" -maxdepth 1 -type f -printf '%f\n' | sort)" == $'anr-after.json\nanr-before.json\ndevice-state.txt\ntest-exit-code.txt\ntree-failures.txt' ]]
  [[ -s "$out/device-state.txt" && -s "$out/tree-failures.txt" ]]
  ! grep -r 'synthetic-private' "$out"
  for stage in before after; do
    if [[ "$ADB_STATUS" == 0 ]]; then
      [[ "$(cat "$out/anr-$stage.json")" == '{"version":1,"status":"captured","events":[{"process":"other","pid":1234,"reasonKind":"other"}]}' ]]
    else
      [[ "$(cat "$out/anr-$stage.json")" == '{"version":1,"status":"unavailable","reason":"capture"}' ]]
    fi
  done
  echo "API $api, Gradle=$GRADLE_STATUS, adb=$ADB_STATUS: status $status preserved; Worker journey excluded"
}
run 36 0
run 28 0
GRADLE_STATUS=42 run 36 42
ADB_STATUS=9 run 36 0
GRADLE_STATUS=42 ADB_STATUS=9 run 28 42
RESULT_MODE=empty run 36 3
RESULT_MODE=fail run 36 3
RESULT_MODE=skipped run 28 3
RESULT_MODE=malformed run 36 3
RESULT_MODE=inconsistent run 36 3
RESULT_MODE=local-only run 36 3
# The preceding reports must be removed; a false adapter success cannot reuse them.
run 36 0
RESULT_MODE=missing run 36 3

reject() {
  local status=0
  : >"$CALLS"
  rm -rf "$out"
  "$@" || status=$?
  [[ "$status" == 2 && ! -s "$CALLS" && ! -e "$out/test-exit-code.txt" ]]
}
reject bash "$fixture/android/scripts/ci-instrumented.sh" 27
reject env ANDROID_SERIAL=R58-synthetic-physical bash "$fixture/android/scripts/ci-instrumented.sh" 36
echo "Invalid API and physical device serials rejected before tests or diagnostics"
