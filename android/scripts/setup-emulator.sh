#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
tools="$ANDROID_HOME/cmdline-tools/19.0"
avd="${WHUMPO_AVD:-whumpo_api36}"

java -version
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
"$tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  'platform-tools' 'emulator' 'platforms;android-37.0' \
  'build-tools;36.0.0' 'build-tools;37.0.0' 'system-images;android-36;google_apis;x86_64'
if ! "$tools/bin/avdmanager" list avd -c | grep -Fxq "$avd"; then
  printf 'no\n' | "$tools/bin/avdmanager" create avd --name "$avd" \
    --package 'system-images;android-36;google_apis;x86_64' --device pixel_6
fi
printf '\nSDK: %s\nAVD: %s\n' "$ANDROID_HOME" "$avd"
printf 'Start with: emulator -avd %s -accel on -gpu swiftshader -no-audio -no-boot-anim -no-metrics\n' "$avd"
