# Architecture

Quantivue is a single Android application module with a dependency-light native shell and a pure Kotlin analysis core. The app is deliberately offline-first: there is no Internet permission, no network client, and no Quotex integration.

## Runtime graph

`MainActivity` requests the user-controlled `MediaProjection` grant. `CaptureAnalysisService` starts as a media-projection foreground service, creates an `ImageReader`/`VirtualDisplay`, and processes only the latest frame on a dedicated `HandlerThread`. The pipeline is:

`ImageReader -> FrameValidator -> ChartRoiDetector -> CandleDetector -> CandleSequenceTracker -> feature engines -> local specialist ensemble -> gate -> UI/SQLite/overlay`.

The service broadcasts a small summary to the activity; raw frames never cross the activity boundary. SQLite stores prediction audit metadata, not images. Every capture run gets a fresh session id so outcomes cannot be attached to a different asset/layout session. The optional overlay uses `TYPE_APPLICATION_OVERLAY` only after the Android user grants the separate permission.

## Core boundaries

* `Models.kt`: immutable contracts and explicit `PredictionContext` / `PredictionResult`.
* `Vision.kt`: allocation-conscious screen sampling and relative candle extraction.
* `FeatureEngines.kt`: stateful running-candle tracking plus structure, zones, momentum, volatility, and regime features.
* `PredictionEngine.kt`: transparent specialist ensemble, calibration artifact, contradictions, counterfactuals, and signal gate.
* `Research.kt`: chronological split/metric/causal guards.
* `PredictionStore`: local audit persistence.

No model receives the future target candle. A calibration artifact is explicit and must be marked validated before directional live output is allowed.

## Failure policy

Permission loss, malformed frames, absent ROI, unknown timeframe, unsupported chart layout, insufficient context, model/calibration absence, and contradictory evidence degrade to `NO TRADE` or a visible error state. They do not produce a guessed signal.
