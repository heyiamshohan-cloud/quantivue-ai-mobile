# Quantivue AI Mobile

Quantivue AI Mobile is MERQO's offline-first Android decision-support client for a **user-visible Quotex 1-minute chart**. It uses Android `MediaProjection` only after the user approves screen capture. It never uses a Quotex API, reads hidden data, clicks the trading UI, places orders, or uploads frames.

> **Important honesty note**: this repository contains an operational capture and visual-analysis pipeline, but it does not ship a real-data-trained/calibrated predictive model. Until a user installs a validated calibration artifact from chronological research data, the live signal gate intentionally returns `NO TRADE`. The probabilities shown before the gate are raw local statistical estimates, not a claim of live-market accuracy.

## Product behavior

* Captures the screen continuously with a foreground service and a persistent notification.
* Locates a likely chart ROI, refines it incrementally, and samples frames with backpressure.
* Reconstructs approximate, normalized candles from pixels; it does not fabricate exact OHLC values.
* Keeps the current/running candle separate from confirmed candles.
* Builds structure, support/resistance, momentum, volatility, regime, and optional historical-similarity features.
* Combines transparent local specialist outputs and records provenance, uncertainty, contradictions, and a causal input fingerprint.
* Targets `runningCandle.index + 1`: the **next** candle. The target candle is never present in `PredictionContext`.
* Uses a first-class `NO TRADE` gate for bad quality, uncertain timeframe, insufficient history, disagreement, high uncertainty, or missing validated calibration.
* Stores prediction audit records locally in SQLite. Frames are not stored by default.

## Safety and privacy

The source of truth is **user-visible chart pixels captured through authorized Android screen capture**. The app has no network permission and no cloud dependency. It does not request credentials, accessibility privileges, or trading permissions. It does not automate Quotex. Screen content remains in memory unless a future, explicitly disclosed research-capture feature is added.

Probabilities are estimates, not guarantees. This is not financial advice. A correct `NO TRADE` is preferred over unsupported direction.

## Build

See [docs/build.md](docs/build.md). The intended toolchain is JDK 17, Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, compile/target SDK 35, and min SDK 29. The checkout does not include a production keystore.

```bash
gradle testDebugUnitTest
gradle assembleDebug
gradle bundleRelease
```

If your checkout has a generated Gradle wrapper, `./gradlew` may be used instead.

Artifacts are copied by `scripts/package_release.sh` when a local Android SDK is available. This Arena environment was audited during implementation and did not contain Java, Gradle, an Android SDK, or an APK builder; therefore no APK is claimed in this checkout. See `release/RELEASE_NOTES.md` for the exact blocker.

## First-run workflow

1. Install the debug APK on Android 10 or newer.
2. Open Quantivue and read the privacy/source-of-truth notice.
3. Keep the visible Quotex chart on the intended 1-minute timeframe.
4. Tap **Confirm visible chart is 1M** only when the user can verify the chart label. Quantivue intentionally does not silently infer an unknown timeframe.
5. Tap **START** and approve the Android screen-capture prompt.
6. Switch to Quotex. Quantivue detects the visible chart and keeps analysis in its foreground service.
7. Optionally grant `Display over other apps` and start again for the compact overlay.
8. Pause, resume, stop, reset the session, or revoke capture at any time.

`NO TRADE` is expected when the chart is obstructed, the ROI is uncertain, the context is too short, the timeframe is not confirmed, or calibration is unavailable.

## Architecture

The native shell is intentionally dependency-light and uses Android Views so the audit/build surface is small:

```text
MainActivity
  ├─ DashboardView (accessible custom dashboard)
  └─ MediaProjection permission
       └─ CaptureAnalysisService (foreground service)
            ├─ ImageReader + bounded latest-frame processing
            ├─ ChartRoiDetector / CandleDetector
            ├─ CandleSequenceTracker
            ├─ FeatureEngines
            ├─ LocalSpecialistEnsemble + SignalGate
            ├─ PredictionStore (SQLite audit log)
            └─ optional WindowManager overlay
```

Pure Kotlin domain code lives under `app/src/main/java/com/merqo/quantivue/core`. It has no Android dependency and is covered by local unit tests. `Research.kt` provides chronological window splitting, causal guards, and metric calculations for a future genuine dataset/research UI. No random signal generation is present.

## Validation status

Automated unit tests cover target indexing, running/confirmed separation, calibration gating, future-candle leakage rejection, walk-forward window ordering, and metric calculation. No genuine OHLC or screenshot dataset was supplied with this repository, so there are no accuracy, calibration, or performance claims. Validation status is **INSUFFICIENT REAL DATA**.

## Known limitations

* There is no packaged trained neural model or validated calibration artifact. A production promotion workflow must be fed legally obtained, chronological data and must keep train/validation/test windows separate.
* Pixel candle direction is inferred from color families and normalized coordinates. Themes, overlays, compression, zoom, and unusual chart layouts may reduce confidence.
* The timeframe gate currently requires explicit user confirmation. OCR/semantic label verification is intentionally not guessed.
* Exact price, ticks, asset identity, and hidden indicators are unavailable from pixels and are never invented.
* The overlay is compact and permission-controlled; a future release can add more user-configurable drag persistence.
* APK install/build verification is blocked in the current agent container because Android build tooling is absent.

## Repository guide

* `app/src/main/java/com/merqo/quantivue/core` — immutable contracts, vision, candle tracking, feature engines, ensemble, calibration, causal research.
* `app/src/main/java/com/merqo/quantivue/capture` — MediaProjection foreground service and overlay.
* `app/src/main/java/com/merqo/quantivue/data` — local SQLite prediction audit store.
* `app/src/test` — deterministic leakage and pipeline tests.
* `docs/` — implementation and operational documentation.
* `scripts/` — reproducible build/package helpers.
* `release/` — release notes and generated artifacts when built in an Android toolchain.
