#!/usr/bin/env bash
# Launch smoke test for the minified (R8) release APK on a running emulator. Not a substitute for the instrumented
# suite (which targets debug builds): it proves R8 did not strip what the app needs to start, show the sign-in
# screen and start the Credential Manager passkey request.
set -euo pipefail
apk="$1"
pkg=com.whumpo.android
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity"
sleep 5

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb exec-out cat /sdcard/ui.xml; }
fail() { echo "SMOKE FAILED: $1"; adb logcat -d | tail -n 200; exit 1; }

ui="$(dump)"
echo "$ui" | grep -q 'Welcome back to Whumpo.' || { echo "$ui" | head -c 3000; fail "welcome screen did not render"; }

# Tap the passkey sign-in: this runs Credential Manager (and its Play services provider) through the minified code.
center="$(echo "$ui" | python3 -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"Sign in with passkey\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x)
a=list(map(int,m.groups()))
print((a[0]+a[2])//2,(a[1]+a[3])//2)')"
adb shell input tap $center
sleep 10

adb shell pidof "$pkg" >/dev/null || fail "app process is gone after the passkey request"
if adb logcat -d | grep -E "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|SerializationException: Serializer for class|MissingFieldException"; then
  fail "crash or R8-stripped class in logcat"
fi
echo "SMOKE OK"
