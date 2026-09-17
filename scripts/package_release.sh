#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p release
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
cp RELEASE_NOTES.md release/RELEASE_NOTES.md 2>/dev/null || true
echo "Generated files:"; find release -maxdepth 1 -type f -printf '%f\n' | sort
