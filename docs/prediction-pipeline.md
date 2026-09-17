# Prediction pipeline

The service runs a fast path on sampled frames and a deep path every sixth frame or on closing/new-candle conditions. The fast path validates the frame, refines the ROI, updates the running candle, and reports diagnostics. The deep path reruns features and the ensemble.

1. Validate frame dimensions, timestamp monotonicity, and visual variance.
2. Detect a chart ROI by information/edge/chroma density; refine it while confidence is stable.
3. Detect candle-like colored marks without exact RGB matching.
4. Keep the right-most observed candle in `RunningCandle`; prior observations become `CandleRecord` only when a new visual candle appears.
5. Compute normalized structure, zones, momentum, volatility, regime, and optional similarity features.
6. Build immutable `PredictionContext`. It contains confirmed candles and the running candle, but has no target candle.
7. Run specialist outputs with provenance, feature quality, confidence, and uncertainty.
8. Aggregate with context-preserving weights. Apply a calibration artifact; raw logits are never displayed as calibrated.
9. Check contradictions and counterfactual invalidators.
10. Run `SignalGate`. The default artifact is `uncalibrated-none`, so the gate returns `NO_TRADE` until research installs a validated artifact.
11. Apply temporal hysteresis. A directional result must remain stable for two deep observations and resets when the running candle/target advances, ROI changes, or capture stops.
12. Persist the fingerprint, input metadata, result, gate reason, and later outcome locally.

`targetCandleIndex = runningCandle.index + 1`. The current running candle is never the prediction target. After it becomes confirmed and a new running candle is identified, the target index advances again.
