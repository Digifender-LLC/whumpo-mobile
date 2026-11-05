#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
installer="$script_dir/ci-install-sdk.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
test_bin="$fixture/bin"
mkdir -p "$test_bin"
export EXPECTED_SHA=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
export PATH="$test_bin:$PATH"

cat >"$test_bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl\n' >>"$TEST_TRACE"
archive="${@: -1}"
[[ "$archive" == *.zip ]]
: >"$archive"
SH

cat >"$test_bin/sha256sum" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == -c && "${2:-}" == - ]]
read -r checksum archive
printf 'checksum\n' >>"$TEST_TRACE"
[[ "$checksum" == "$EXPECTED_SHA" && -f "$archive" ]]
[[ "${FAIL_CHECKSUM:-0}" != 1 ]]
SH

cat >"$test_bin/unzip" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -q && "$3" == -d && -f "$2" ]]
printf 'unzip\n' >>"$TEST_TRACE"
destination="$4"
mkdir -p "$destination/cmdline-tools/bin"
cat >"$destination/cmdline-tools/bin/sdkmanager" <<'MANAGER'
#!/usr/bin/env bash
set -euo pipefail
printf 'verified-sdkmanager:%s\n' "$*" >>"$SDK_CALLS"
MANAGER
chmod +x "$destination/cmdline-tools/bin/sdkmanager"
SH

cat >"$test_bin/yes" <<'SH'
#!/usr/bin/env bash
printf 'y\n'
SH

chmod +x "$test_bin/"*

write_existing_sdkmanager() {
  local path="$1"
  mkdir -p "$(dirname "$path")"
  cat >"$path" <<'MANAGER'
#!/usr/bin/env bash
printf 'preinstalled-sdkmanager:%s\n' "$*" >>"$SDK_CALLS"
MANAGER
  chmod +x "$path"
}

build_home="$fixture/build-sdk"
legacy_tools="$fixture/legacy-tools"
mkdir -p "$build_home/cmdline-tools" "$legacy_tools/bin"
write_existing_sdkmanager "$legacy_tools/bin/sdkmanager"
export ANDROID_HOME="$build_home"
export SDK_CALLS="$fixture/build-calls"
export TEST_TRACE="$fixture/build-trace"
: >"$SDK_CALLS"
: >"$TEST_TRACE"
ln -s "$legacy_tools" "$ANDROID_HOME/cmdline-tools/19.0"
timeout 15s bash "$installer" build >/dev/null
[[ ! -L "$ANDROID_HOME/cmdline-tools/19.0" && -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]
! grep -q '^preinstalled-sdkmanager:' "$SDK_CALLS"
grep -q '^verified-sdkmanager:--sdk_root=.* --licenses$' "$SDK_CALLS"
build_packages="$(grep '^verified-sdkmanager:--sdk_root=' "$SDK_CALLS")"
[[ "$build_packages" == *'platform-tools'* && "$build_packages" == *'platforms;android-37.0'* ]]
if grep -Eq '(^|[[:space:]])emulator([[:space:]]|$)' <<<"$build_packages"; then
  echo 'Build mode unexpectedly requested the emulator package.' >&2
  exit 1
fi
[[ "$(<"$TEST_TRACE")" == $'curl\nchecksum\nunzip' ]]
echo 'CI SDK installer replaces existing tools only after verification; build packages passed'

failure_home="$fixture/failure-sdk"
failure_tools="$failure_home/cmdline-tools/19.0"
write_existing_sdkmanager "$failure_tools/bin/sdkmanager"
printf 'preserve\n' >"$failure_tools/preserve"
export ANDROID_HOME="$failure_home"
export SDK_CALLS="$fixture/failure-calls"
export TEST_TRACE="$fixture/failure-trace"
export FAIL_CHECKSUM=1
: >"$SDK_CALLS"
: >"$TEST_TRACE"
if timeout 15s bash "$installer" build >/dev/null 2>&1; then
  echo 'Checksum failure unexpectedly succeeded.' >&2
  exit 1
fi
unset FAIL_CHECKSUM
[[ -x "$failure_tools/bin/sdkmanager" && -f "$failure_tools/preserve" ]]
[[ ! -s "$SDK_CALLS" && "$(<"$TEST_TRACE")" == $'curl\nchecksum' ]]
echo 'CI SDK installer checksum failure preserves existing tools and aborts'

emulator_home="$fixture/emulator-sdk"
export ANDROID_HOME="$emulator_home"
export SDK_CALLS="$fixture/emulator-calls"
export TEST_TRACE="$fixture/emulator-trace"
: >"$SDK_CALLS"
: >"$TEST_TRACE"
timeout 15s bash "$installer" emulator >/dev/null
emulator_packages="$(grep '^verified-sdkmanager:--sdk_root=' "$SDK_CALLS")"
grep -Eq '(^|[[:space:]])emulator([[:space:]]|$)' <<<"$emulator_packages"
echo 'CI SDK installer emulator mode requests the emulator package'
