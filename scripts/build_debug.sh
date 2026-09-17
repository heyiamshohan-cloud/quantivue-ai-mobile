#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v java >/dev/null || { echo "JDK 17+ is required" >&2; exit 2; }
if [ -x ./gradlew ]; then BUILD=(./gradlew); elif command -v gradle >/dev/null; then BUILD=(gradle); else echo "Gradle 8.9+ or gradlew is required" >&2; exit 2; fi
"${BUILD[@]}" testDebugUnitTest
"${BUILD[@]}" assembleDebug
mkdir -p release
cp app/build/outputs/apk/debug/app-debug.apk release/QuantivueAI-Mobile-debug.apk
cp release/QuantivueAI-Mobile-debug.apk release/QuantivueAI-Mobile.apk
scripts/package_release.sh
echo "Debug APK: release/QuantivueAI-Mobile.apk"
