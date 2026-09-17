# Build

## Requirements

* JDK 17
* Android SDK platform 35 and build tools installed
* Gradle 8.9 (or the checked-in wrapper when one is added by a release environment)
* Android Gradle Plugin 8.7.3 / Kotlin 2.0.21

The repository's container audit found none of JDK, Gradle, Android SDK, or `adb`. `sudo apt-get update` was attempted but Debian mirrors were unreachable, and direct JDK/Gradle downloads were blocked at TLS/release-asset egress. Therefore the agent could not produce an APK here. This is an environment blocker, not a claimed build success.

## Commands

```bash
gradle testDebugUnitTest
gradle assembleDebug
gradle assembleRelease
gradle bundleRelease
scripts/package_release.sh
```

Use `./gradlew` for each Gradle command if a release environment has generated the standard Gradle wrapper.

On a normal checkout, debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. Release output is `app/build/outputs/apk/release/app-release-unsigned.apk` unless a signing configuration is provided. The package helper copies/renames artifacts into `release/` and writes `SHA256SUMS.txt` only for files that actually exist.

For a signed release, create a keystore outside Git and supply `QUANTIVUE_KEYSTORE`, `QUANTIVUE_KEY_ALIAS`, `QUANTIVUE_KEYSTORE_PASSWORD`, and `QUANTIVUE_KEY_PASSWORD` to a local signing configuration. Never commit that file or its secrets. A debug APK is the appropriate artifact when no production keystore is supplied.
