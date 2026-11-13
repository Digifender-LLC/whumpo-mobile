#!/usr/bin/env bash
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/ci-instrumented.sh"
python3 "$(dirname "$script")/test-anr-summary.py"
bash "$(dirname "$script")/test-keyboard-geometry.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/android/scripts" "$fixture/bin"
cp "$script" "$fixture/android/scripts/ci-instrumented.sh"
cp "$(dirname "$script")/anr-summary.py" "$fixture/android/scripts/anr-summary.py"
cp "$(dirname "$script")/verify-instrumented-results.py" "$fixture/android/scripts/verify-instrumented-results.py"
cp "$(dirname "$script")/instrumented-test-inventory.json" "$fixture/android/scripts/instrumented-test-inventory.json"
PYTHONDONTWRITEBYTECODE=1 python3 "$(dirname "$script")/test-verify-instrumented-results.py"
# Record actual validator arguments in the private fixture, not the public source.
python3 - "$fixture/android/scripts/verify-instrumented-results.py" <<'PYMARK'
import sys
from pathlib import Path
p = Path(sys.argv[1])
p.write_text("import os, sys\nwith open(os.environ['CALLS'], 'a') as f: f.write('validator|' + '|'.join(sys.argv[1:]) + '\\n')\n" + p.read_text())
PYMARK
cat >"$fixture/android/scripts/mock-results.py" <<'PYMOCK'
import json, os
from pathlib import Path
import xml.etree.ElementTree as E
inventory = json.loads(Path('scripts/instrumented-test-inventory.json').read_text())
mode = os.environ.get('RESULT_MODE', 'pass')
for flavor in ('local', 'deployed'):
    folder = Path('androidApp/build/outputs/androidTest-results/connected/debug/flavors') / flavor
    folder.mkdir(parents=True, exist_ok=True)
    if mode == 'missing' or (mode == 'local-only' and flavor != 'local'):
        continue
    suite = E.Element('testsuites', tests='0', failures='0', errors='0', skipped='0')
    for cls, names in inventory['classes'].items():
        for name in names:
            E.SubElement(suite, 'testcase', classname=cls, name=name)
    if mode == 'empty':
        suite.clear()
    elif mode == 'fail':
        E.SubElement(suite[0], 'failure').text = 'synthetic failure'
        suite.set('failures', '1')
    elif mode == 'skipped':
        for case in suite:
            E.SubElement(case, 'skipped').text = 'synthetic skip'
        suite.set('skipped', str(len(suite)))
    cases = list(suite.iter('testcase'))
    props = E.SubElement(suite, 'properties')
    if mode != 'missing-device':
        E.SubElement(props, 'property', name='device', value='emulator-5556' if mode == 'wrong-device' else os.environ['ANDROID_SERIAL'])
    if mode == 'duplicate-device':
        E.SubElement(props, 'property', name='device', value=os.environ['ANDROID_SERIAL'])
    suite.set('tests', str(len(cases) + (mode == 'inconsistent')))
    report = folder / 'TEST-device.xml'
    report.write_text('<testsuites' if mode == 'malformed' else E.tostring(suite, encoding='unicode'))
PYMOCK
cat >"$fixture/android/gradlew" <<'SH'
#!/usr/bin/env bash
printf 'gradle %s\n' "$*" >>"$CALLS"
printf 'selector|%s\n' "$ANDROID_SERIAL" >>"$CALLS"
python3 scripts/mock-results.py
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
  "-s emulator-5554 shell getprop ro.build.version.sdk")
    printf '%s' "$DEVICE_API"
    exit "${API_STATUS:-0}"
    ;;
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
if [[ "$*" == 'adb -s emulator-5554 shell getprop ro.build.version.sdk' && "${API_TIMEOUT:-0}" == 1 ]]; then
  echo api-timeout >>"$CALLS"
  exit 124
fi
exec "$@"
SH
chmod +x "$fixture/android/gradlew" "$fixture/bin/"*
export PATH="$fixture/bin:$PATH" ANDROID_SERIAL=emulator-5554
export CALLS="$fixture/calls" GRADLE_STATUS=0 ADB_STATUS=0
out="$fixture/android/androidApp/build/outputs/instrumented-diagnostics"
run() {
  local api=$1 expected=$2 status=0
  : >"$CALLS"
  export DEVICE_API="$api"
  bash "$fixture/android/scripts/ci-instrumented.sh" "$api" || status=$?
  [[ "$status" == "$expected" && "$(cat "$out/test-exit-code.txt")" == "$expected" ]]
  [[ "$(grep -c '^gradle ' "$CALLS")" == 1 ]]
  grep -Fxq 'selector|emulator-5554' "$CALLS"
  if [[ "$GRADLE_STATUS" == 0 ]]; then
    grep -Fxq "validator|androidApp/build/outputs/androidTest-results/connected|$api|emulator-5554" "$CALLS"
  else
    [[ "$(grep -c '^validator|' "$CALLS" || true)" == 0 ]]
  fi
  grep -Fxq 'gradle connectedLocalDebugAndroidTest connectedDeployedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.whumpo.android.WorkerJourneyTest' "$CALLS"
  local anrCall='adb|-s|emulator-5554|logcat|-b|events|-d|-v|raw|am_anr:I|*:S'
  local expectedAdb=$'adb|-s|emulator-5554|shell|getprop|ro.build.version.sdk\n'"$anrCall"$'\nadb|-s|emulator-5554|get-state\nadb|-s|emulator-5554|logcat|-d|-v|threadtime|WhumpoTreeDiagnostics:E|*:S\n'"$anrCall"
  [[ "$(grep -c '^adb|' "$CALLS")" == 5 ]]
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
RESULT_MODE=wrong-device run 36 3
RESULT_MODE=missing-device run 28 3
RESULT_MODE=duplicate-device run 36 3
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
for serial in emulator-junk emulator-5554,emulator-5556 emulator-5554,physical; do
  reject env ANDROID_SERIAL="$serial" bash "$fixture/android/scripts/ci-instrumented.sh" 36
done
echo "Invalid API and non-single numeric emulator serials rejected before tests or diagnostics"

reject_api() {
  local requested=$1 actual=$2 status=0
  : >"$CALLS"
  rm -rf "$out"
  DEVICE_API="$actual" bash "$fixture/android/scripts/ci-instrumented.sh" "$requested" || status=$?
  [[ "$status" == 2 && ! -e "$out" ]]
  [[ "$(grep -c '^gradle\|^validator|' "$CALLS" || true)" == 0 ]]
  [[ "$(wc -l <"$CALLS")" == 1 ]]
  echo "Selected emulator API preflight rejected requested=$requested actual=$actual before Gradle/validator"
}
reject_api 36 28
reject_api 28 36
reject_api 36 ''
reject_api 36 '36 extra'
API_STATUS=9 reject_api 36 36
API_TIMEOUT=1 reject_api 28 28
