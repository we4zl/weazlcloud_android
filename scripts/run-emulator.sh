#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
ANDROID_SDK_LOCATION="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
./gradlew :app:assembleDebug
"$ANDROID_SDK_LOCATION/platform-tools/adb" wait-for-device
"$ANDROID_SDK_LOCATION/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_SDK_LOCATION/platform-tools/adb" shell am start -n io.prendie.weazlcloud/.MainActivity --ez demo true
