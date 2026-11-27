#!/bin/zsh
set -eu
cd "$(dirname "$0")/.."
source scripts/native-env.sh
pass="${1:-4}"
.venv/bin/python scripts/capture_ios.py "$pass" > artifacts/v10-ios-capture-pass${pass}.log 2>&1
# Replace route-only composer framing with a physically typed, native-keyboard frame.
cp screenshots/ios/P-02-partner-typing.png screenshots/ios/L2-12-partner-composer.png
.venv/bin/python scripts/capture_large.py ios > artifacts/v10-ios-large.log 2>&1
xcrun simctl ui "$(cat artifacts/ios-device.txt)" content_size accessibility-large
xcrun simctl terminate "$(cat artifacts/ios-device.txt)" com.whumpo.expomock || true
node scripts/ios-open.cjs __development
sleep 4
xcodebuild -project .native-build/ios-ui-check/WhumpoSmoke.xcodeproj -scheme WhumpoNativeSmoke -destination "platform=iOS Simulator,id=$(cat artifacts/ios-device.txt)" -derivedDataPath .native-build/ios-ui-derived -resultBundlePath .native-build/v10-ios-large${pass}.xcresult -only-testing:WhumpoNativeSmoke/WhumpoPolish/testLargeReviews CODE_SIGNING_ALLOWED=NO test > artifacts/v10-ios-large-ui${pass}.log 2>&1
.venv/bin/python scripts/copy_polish_ios.py "$pass" .native-build/v10-ios-large${pass}.xcresult
xcrun simctl ui "$(cat artifacts/ios-device.txt)" content_size large
cp screenshots/ios/*.png compare/v10-pass${pass}/ios/
.venv/bin/python scripts/verify_send_raster.py ios
.venv/bin/python scripts/compare_v10.py "$pass"
