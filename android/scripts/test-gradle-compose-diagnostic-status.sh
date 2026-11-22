#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -r -- "$fixture"' EXIT
mkdir -p "$fixture/android/scripts"
cp "$script_dir/run-gradle-with-compose-diagnostics.sh" "$fixture/android/scripts/"
cat >"$fixture/android/scripts/diagnose-compose-plugin-resolution.sh" <<'SH'
#!/usr/bin/env bash
printf 'diagnostic\n' >>"$DIAGNOSTIC_CALLS"
exit "$DIAGNOSTIC_STATUS"
SH
cat >"$fixture/android/fake-gradle" <<'SH'
#!/usr/bin/env bash
if [[ "${PLUGIN_MARKER_ERROR:-0}" == 1 ]]; then
  printf "Plugin [id: 'org.jetbrains.kotlin.plugin.compose', version: '2.4.21'] was not found.\n"
fi
exit "$GRADLE_STATUS"
SH
chmod +x "$fixture/android/fake-gradle"
export DIAGNOSTIC_CALLS="$fixture/diagnostic-calls"

run_case() {
  local expected=$1 status=0
  shift
  : >"$DIAGNOSTIC_CALLS"
  set +e
  (cd "$fixture/android" && env "$@" bash scripts/run-gradle-with-compose-diagnostics.sh ./fake-gradle) \
    >"$fixture/output.log" 2>&1
  status=$?
  set -e
  [[ "$status" == "$expected" ]]
}

run_case 42 GRADLE_STATUS=42 DIAGNOSTIC_STATUS=9 PLUGIN_MARKER_ERROR=1
[[ "$(cat "$DIAGNOSTIC_CALLS")" == diagnostic ]]
grep -Fq 'diagnostic exited 9; preserving original Gradle exit 42' "$fixture/output.log"

run_case 42 GRADLE_STATUS=42 DIAGNOSTIC_STATUS=0 PLUGIN_MARKER_ERROR=0
[[ ! -s "$DIAGNOSTIC_CALLS" ]]

run_case 0 GRADLE_STATUS=0 DIAGNOSTIC_STATUS=9 PLUGIN_MARKER_ERROR=1
[[ ! -s "$DIAGNOSTIC_CALLS" ]]

echo 'Gradle exit remains authoritative when the optional diagnostic fails.'
