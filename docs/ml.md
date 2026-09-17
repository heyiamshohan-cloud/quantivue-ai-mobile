# Models and calibration

The shipped local ensemble is a transparent statistical baseline, not a claimed trained neural network. Specialists currently cover structure, momentum, running price action, volatility, support/resistance, and optional historical similarity. Every `ModelOutput` contains model id/version, bullish probability, confidence, uncertainty, feature quality, and latency.

Weights are a deterministic function of confidence, feature quality, and inverse uncertainty. Disagreement contributes to uncertainty. The model version is `local-statistical-v1`.

`CalibrationArtifact` is explicit and versioned. `uncalibrated-none` has zero samples and `validated=false`. `CalibrationArtifactLoader` rejects missing payloads, checksum mismatches, incompatible model/feature versions, missing dataset provenance, invalid ECE metadata, and small samples. The live signal gate requires `validated=true`; it therefore cannot turn a raw baseline into a directional trading claim. Research code provides Brier score, log loss, ECE, and chronological windows for fitting/evaluating future calibration artifacts.

A future ONNX/TFLite model can implement the same specialist contract, include checksum/compatibility metadata, and remain behind a validated registry. Model-load failure must fall back to `NO_TRADE`, never to random or fabricated output.
