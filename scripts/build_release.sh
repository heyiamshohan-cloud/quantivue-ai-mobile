#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v java >/dev/null || { echo "JDK 17+ is required" >&2; exit 2; }
if [ -x ./gradlew ]; then BUILD=(./gradlew); elif command -v gradle >/dev/null; then BUILD=(gradle); else echo "Gradle 8.9+ or gradlew is required" >&2; exit 2; fi
"${BUILD[@]}" testDebugUnitTest
"${BUILD[@]}" assembleRelease
"${BUILD[@]}" bundleRelease
scripts/package_release.sh
