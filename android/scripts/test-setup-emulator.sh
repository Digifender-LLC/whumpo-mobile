#!/usr/bin/env bash
set -euo pipefail

setup="$(cd "$(dirname "$0")" && pwd)/setup-emulator.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
export TEST_BIN="$fixture/bin"
mkdir -p "$TEST_BIN"

# Stub downloads and SDK commands; exercise installation layout without network or an emulator.
cat >"$TEST_BIN/curl" <<'SH'
#!/usr/bin/env bash
echo download >>"$TEST_SETUP_LOG"
: >"${@: -1}"
SH
cat >"$TEST_BIN/unzip" <<'SH'
#!/usr/bin/env bash
mkdir -p "$4/cmdline-tools/bin"
cp "$TEST_BIN/sdkmanager" "$TEST_BIN/avdmanager" "$4/cmdline-tools/bin/"
SH
printf '#!/usr/bin/env bash\ncat >/dev/null\n' >"$TEST_BIN/sha256sum"
printf '#!/usr/bin/env bash\nexit 0\n' >"$TEST_BIN/java"
printf '#!/usr/bin/env bash\nexit 0\n' >"$TEST_BIN/sdkmanager"
printf '#!/usr/bin/env bash\necho whumpo_api36\n' >"$TEST_BIN/avdmanager"
chmod +x "$TEST_BIN/"*
export PATH="$TEST_BIN:$PATH"

for state in fresh partial broken-link complete; do
  export ANDROID_HOME="$fixture/$state"
  export TEST_SETUP_LOG="$fixture/$state.log"
  tools="$ANDROID_HOME/cmdline-tools/19.0"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  : >"$TEST_SETUP_LOG"
  case "$state" in
    partial) mkdir -p "$tools/bin"; touch "$tools/bin/sdkmanager" ;;
    broken-link) ln -s "$fixture/missing" "$tools" ;;
    complete)
      mkdir -p "$tools/bin"
      cp "$TEST_BIN/sdkmanager" "$TEST_BIN/avdmanager" "$tools/bin/"
      touch "$tools/preserve"
      ;;
  esac
  bash "$setup" >"$fixture/$state-output.log" 2>&1 || { cat "$fixture/$state-output.log"; exit 1; }
  [[ -x "$tools/bin/sdkmanager" && ! -e "$tools/cmdline-tools" ]]
  if [[ "$state" == complete ]]; then
    [[ -f "$tools/preserve" && ! -s "$TEST_SETUP_LOG" ]]
  else
    [[ "$(grep -c '^download$' "$TEST_SETUP_LOG")" == 1 ]]
  fi
  echo "SDK layout: $state passed"
done
