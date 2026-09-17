# Phase 2 hardening audit

## Fixed in this pass

* Locked ROI refinement no longer grows the ROI on every frame.
* Stale capture timestamps now suppress the current result and publish a specific stale-frame state.
* RGBA and row buffers are reused between sampled frames.
* ROI loss and large ROI/layout shifts reset candle context, lifecycle state, and prior result provenance; stale directional output is not retained.
* Start/stop resets session counters and state; each capture run has a unique session id so outcomes cannot mix sessions/assets.
* Prediction/outcome database schema migrated to version 2 with session-scoped updates.
* Historical similarity samples now require timestamps/target indices older than the prediction context.
* Causal guard now rejects a future-starting running candle and any future-time similarity example.
* Calibration loading now rejects missing payloads, checksum mismatch, incompatible model/feature versions, missing dataset provenance, invalid ECE metadata, and small samples.
* Price-action heuristic features are explicit and labelled as heuristic, rather than being presented as ML.
* Directional lifecycle hysteresis prevents one-frame CALL/PUT flicker.
* Capture initialization failures are caught and surfaced; projection revocation, ROI loss, stale frames, pause, stop, and reset are explicit states.
* Diagnostics and local history dialogs were added without requiring a capture session.
* Packaging scripts remove stale binary names, copy only existing outputs, and write actual build metadata/hashes.

## Verified by inspection/static checks

* The target contract is `running.index + 1`; `PredictionContext` rejects confirmed candles at or after the running index.
* Default calibration remains `uncalibrated-none` and the live gate returns `NO_TRADE`.
* No network permission, Quotex endpoint, accessibility service, credential path, click automation, or secret is present.
* Android XML parses; shell scripts pass `bash -n`; `git diff --check` passes.

## Not available in this environment

* Kotlin/Android compilation and Gradle unit-test execution: no JDK, Gradle, Android SDK, build tools, or adb are present.
* APK/AAB generation and install/launch testing.
* Emulator/physical-device capture testing.
* Genuine-data walk-forward validation, calibration, and performance measurement: no legally sourced real dataset is present.
* Visual Quotex testing: no Quotex app/device is available in the agent environment.

No unsupported result is promoted to a claim of production accuracy.
