#!/bin/zsh
set -eu
cd "$(dirname "$0")/.."
source scripts/native-env.sh
.venv/bin/python scripts/verify_flow.py > artifacts/v10-flow.log 2>&1
.venv/bin/python scripts/finish_android_v10.py > artifacts/v10-android-finish2.log 2>&1
.venv/bin/python scripts/capture_large_groups_android.py > artifacts/v10-android-large-groups.log 2>&1
cp screenshots/android/*.png compare/v10-pass2/android/
.venv/bin/python scripts/compare_v10.py 2
