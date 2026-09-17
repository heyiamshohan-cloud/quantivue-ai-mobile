# Quantivue AI Mobile 1.0.0 — implementation release notes

## Included

* Native Android project with Kotlin and Android Gradle Plugin configuration.
* User-approved MediaProjection capture in a media-projection foreground service.
* Bounded latest-frame processing, ROI detection/refinement, visual candle reconstruction, running-candle separation, normalized feature engines, local statistical ensemble, contradiction/counterfactual checks, and NO TRADE signal gate.
* Explicit `running + 1` next-candle contract, SQLite audit store, overlay permission path, diagnostics, causal guards, walk-forward splitter, and unit tests.
* Offline/privacy documentation and reproducible build/package scripts.

## Validation status

`INSUFFICIENT REAL DATA`. No genuine chart/OHLC dataset was present, so no accuracy, calibration, or trading-performance result is claimed. The default live calibration artifact is unvalidated and intentionally blocks CALL/PUT.

## Artifact status in this agent environment

No APK, AAB, or SHA256 checksum is claimed because this environment had no `java`, `gradle`, Android SDK, `adb`, or Android build tools. The repository is ready to build in an Android toolchain using `docs/build.md` and `scripts/build_debug.sh`. Running the package script copies only artifacts that genuinely exist and never creates a fake APK.
