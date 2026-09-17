# Quantivue AI Mobile 1.0.0 — hardening release notes

## Included

* Native Android Kotlin application with user-approved MediaProjection capture, foreground notification, bounded latest-frame processing, adaptive sampling, reusable pixel buffers, stale-frame detection, ROI lock/refinement/invalidation, and graceful capture failure handling.
* Normalized visual candle reconstruction with a separate running candle, confirmed history, causal next-candle target indexing, session-scoped prediction/outcome persistence, and chart-context reset on ROI/layout change.
* Transparent local heuristic specialists for structure, price action, momentum, volatility, support/resistance, and optional causal historical similarity. Heuristics are labelled honestly; no trained model or performance claim is shipped.
* Calibration artifact loader with checksum, dataset provenance, feature-schema/model-version compatibility checks, and strict unvalidated default.
* Contradiction/counterfactual analysis, temporal signal hysteresis, strict NO TRADE gate, local diagnostics/history dialogs, and optional draggable overlay.
* Causal guards, walk-forward splitter, calibration metrics, leakage regression tests, database migration, privacy/build/release documentation, and packaging scripts.

## Validation status

`INSUFFICIENT REAL DATA`. No genuine OHLC or screenshot dataset was present, so no accuracy, calibration, or trading-performance result is claimed. The default live calibration artifact is unvalidated and intentionally blocks directional CALL/PUT.

## Artifact status in this agent environment

No APK, AAB, or SHA256 checksum is claimed because this environment has no `java`, `gradle`, Android SDK, `adb`, or Android build tools. `sudo apt-get update` and direct toolchain downloads were also attempted; Debian mirrors were unreachable and GitHub release asset redirects were unavailable. The repository is ready to build in an Android toolchain using `docs/build.md` and `scripts/build_debug.sh`. Running the package script copies only real artifacts and writes actual build information/hashes.
