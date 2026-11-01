#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
./gradlew :app:assembleDebug
mkdir -p output/releases
cp app/build/outputs/apk/debug/app-debug.apk output/releases/WeazlCloud-0.1.0-preview.apk
shasum -a 256 output/releases/WeazlCloud-0.1.0-preview.apk
