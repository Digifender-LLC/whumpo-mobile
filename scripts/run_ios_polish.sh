#!/bin/zsh
set -eu
cd "$(dirname "$0")/.."
source scripts/native-env.sh
pass="$1"
xcrun simctl ui "$(cat artifacts/ios-device.txt)" content_size large
xcrun simctl terminate "$(cat artifacts/ios-device.txt)" com.whumpo.expomock || true
node scripts/ios-open.cjs __development
sleep 5
xcodebuild -project .native-build/ios-ui-check/WhumpoSmoke.xcodeproj -scheme WhumpoNativeSmoke -destination "platform=iOS Simulator,id=$(cat artifacts/ios-device.txt)" -derivedDataPath .native-build/ios-ui-derived -resultBundlePath ".native-build/v10-ios-polish-$pass.xcresult" -only-testing:WhumpoNativeSmoke/WhumpoPolish/testPolishFlow CODE_SIGNING_ALLOWED=NO test > "artifacts/v10-ios-polish-$pass.log" 2>&1
.venv/bin/python scripts/copy_polish_ios.py "$pass" ".native-build/v10-ios-polish-$pass.xcresult"
