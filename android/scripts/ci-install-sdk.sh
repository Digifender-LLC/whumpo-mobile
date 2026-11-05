#!/usr/bin/env bash
set -euo pipefail

mode="${1:-build}"
case "$mode" in
  build|emulator) ;;
  *) echo 'Expected build or emulator.' >&2; exit 2 ;;
esac

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
tools="$ANDROID_HOME/cmdline-tools/19.0"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [[ ! -x "$tools/bin/sdkmanager" ]]; then
  download="$(mktemp -d "$ANDROID_HOME/cmdline-tools/download.XXXXXX")"
  trap 'rm -rf "$download"' EXIT
  curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$download/tools.zip"
  echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  $download/tools.zip" | sha256sum -c -
  unzip -q "$download/tools.zip" -d "$download"
  if [[ -e "$tools" || -L "$tools" ]]; then
    mv -T "$tools" "$download/incomplete"
  fi
  mv -T "$download/cmdline-tools" "$tools"
  rm -rf "$download"
  trap - EXIT
fi

"$tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < <(yes) >/dev/null
packages=('platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0')
if [[ "$mode" == emulator ]]; then
  packages+=('emulator')
fi
"$tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "${packages[@]}"
