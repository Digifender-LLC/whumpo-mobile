# Source from a shell running in this project; no machine-wide defaults are changed.
WHUMPO_EXPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")/.." && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_ADB_SERVER_PORT=5619
export GEM_HOME="$HOME/.local/share/whumpo-expo-tools/gems"
export GEM_PATH="$GEM_HOME"
export PATH="$HOME/.local/share/whumpo-expo-tools/bin:$HOME/.local/share/whumpo-expo-tools/npm/bin:$HOME/.local/share/whumpo-expo-tools/maestro/bin:/opt/homebrew/opt/ruby@3.4/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export GRADLE_USER_HOME="$WHUMPO_EXPO_ROOT/.native-cache/gradle"
export CP_HOME_DIR="$WHUMPO_EXPO_ROOT/.native-cache/cocoapods"
export CP_CACHE_DIR="$WHUMPO_EXPO_ROOT/.native-cache/cocoapods-cache"
export CMAKE_VERSION=3.30.5
export EXPO_NO_TELEMETRY=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
