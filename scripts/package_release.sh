#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p release
# Remove only generated artifact names so a failed rebuild cannot leave stale binaries.
rm -f release/QuantivueAI-Mobile.apk release/QuantivueAI-Mobile-debug.apk release/QuantivueAI-Mobile.aab
# Copy only artifacts that really exist; never create a placeholder APK.
[ -f app/build/outputs/apk/debug/app-debug.apk ] && cp app/build/outputs/apk/debug/app-debug.apk release/QuantivueAI-Mobile-debug.apk
if [ -f app/build/outputs/apk/release/app-release.apk ]; then cp app/build/outputs/apk/release/app-release.apk release/QuantivueAI-Mobile.apk
elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then cp app/build/outputs/apk/release/app-release-unsigned.apk release/QuantivueAI-Mobile.apk
elif [ -f release/QuantivueAI-Mobile-debug.apk ]; then cp release/QuantivueAI-Mobile-debug.apk release/QuantivueAI-Mobile.apk
fi
[ -f app/build/outputs/bundle/release/app-release.aab ] && cp app/build/outputs/bundle/release/app-release.aab release/QuantivueAI-Mobile.aab
: > release/SHA256SUMS.txt
for file in release/QuantivueAI-Mobile.apk release/QuantivueAI-Mobile-debug.apk release/QuantivueAI-Mobile.aab; do
  [ -f "$file" ] && sha256sum "$file" >> release/SHA256SUMS.txt
done
if [ ! -s release/SHA256SUMS.txt ]; then
  echo "# No checksums generated: no APK/AAB artifact exists." > release/SHA256SUMS.txt
fi
cp RELEASE_NOTES.md release/RELEASE_NOTES.md 2>/dev/null || true
jdk_version="unavailable"
if command -v java >/dev/null 2>&1; then jdk_version="$(java -version 2>&1 | head -1)"; fi
gradle_version="unavailable"
if command -v gradle >/dev/null 2>&1; then gradle_version="$(gradle --version 2>/dev/null | awk '/Gradle / {print $2; exit}')"; fi
{
  echo "Application: Quantivue AI Mobile"
  echo "Version: 1.0.0"
  echo "Git commit: $(git rev-parse HEAD 2>/dev/null || echo unavailable)"
  echo "Build timestamp UTC: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ -f app/build/outputs/apk/release/app-release.apk ] || [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    echo "Build variant: release APK (signing state must be inspected)"
  elif [ -f release/QuantivueAI-Mobile-debug.apk ]; then
    echo "Build variant: debug APK"
  else
    echo "Build variant: no artifact"
  fi
  echo "JDK: $jdk_version"
  echo "Gradle: $gradle_version"
  echo "Android Gradle Plugin: 8.7.3"
  echo "Compile SDK: 35"
  echo "Target SDK: 35"
  if [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    echo "Signing: unsigned release APK"
  elif [ -f app/build/outputs/apk/release/app-release.apk ]; then
    echo "Signing: release APK (signature supplied by external build configuration)"
  elif [ -f release/QuantivueAI-Mobile-debug.apk ]; then
    echo "Signing: Android debug keystore"
  else
    echo "Signing: not generated"
  fi
  echo "Artifacts and SHA256:"
  if [ -s release/SHA256SUMS.txt ]; then cat release/SHA256SUMS.txt; else echo "none; build was not available in this environment"; fi
} > release/BUILD_INFO.txt
echo "Generated files:"; find release -maxdepth 1 -type f -printf '%f\n' | sort
