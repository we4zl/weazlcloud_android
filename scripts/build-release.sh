#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [ ! -f keystore.properties ]; then
  echo "Configure the upload key in local keystore.properties; see keystore.properties.example." >&2
  exit 1
fi
./gradlew :app:bundleRelease :app:lintRelease
BUNDLE="app/build/outputs/bundle/release/app-release.aab"
JARSIGNER="${JAVA_HOME:+$JAVA_HOME/bin/}jarsigner"
if ! VERIFICATION_REPORT="$("$JARSIGNER" -J-Duser.language=en -verify "$BUNDLE" 2>&1)"; then
  printf '%s\n' "$VERIFICATION_REPORT" >&2
  exit 1
fi
case "$VERIFICATION_REPORT" in
  *"jar verified."*) echo "Signed bundle verified." ;;
  *) printf '%s\n' "$VERIFICATION_REPORT" >&2; exit 1 ;;
esac
mkdir -p output/play-store
cp "$BUNDLE" output/play-store/weazlcloud-0.1.0-1.aab
shasum -a 256 output/play-store/weazlcloud-0.1.0-1.aab
