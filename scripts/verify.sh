#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
if [ "${1:-}" = "--emulator" ]; then
  ANDROID_SDK_LOCATION="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  ADB="$ANDROID_SDK_LOCATION/platform-tools/adb"
  EMULATOR_SERIAL="${WEAZLCLOUD_TEST_EMULATOR:-emulator-5554}"
  case "$EMULATOR_SERIAL" in
    emulator-*) ;;
    *) echo "Instrumentation requires an emulator serial, never a physical phone." >&2; exit 1 ;;
  esac
  "$ADB" -s "$EMULATOR_SERIAL" get-state >/dev/null
  export ANDROID_SERIAL="$EMULATOR_SERIAL"
  ./gradlew :app:connectedDebugAndroidTest
fi
