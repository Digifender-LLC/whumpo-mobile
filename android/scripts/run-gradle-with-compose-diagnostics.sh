#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$#" -eq 0 ]]; then
  echo 'Expected a Gradle command.' >&2
  exit 2
fi

log="$(mktemp "${TMPDIR:-/tmp}/gradle-output.XXXXXX")"
trap 'rm -- "$log" || true' EXIT
set +e
"$@" 2>&1 | tee "$log"
gradle_status=${PIPESTATUS[0]}
set -e

if (( gradle_status != 0 )) && grep -Fq "Plugin [id: 'org.jetbrains.kotlin.plugin.compose'" "$log"; then
  if bash scripts/diagnose-compose-plugin-resolution.sh; then
    :
  else
    diagnostic_status=$?
    printf 'Compose resolution diagnostic exited %s; preserving original Gradle exit %s.\n' \
      "$diagnostic_status" "$gradle_status" >&2
  fi
fi

exit "$gradle_status"
