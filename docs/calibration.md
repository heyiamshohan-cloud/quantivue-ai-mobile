# Calibration

The displayed directional values are only called calibrated when a `CalibrationArtifact` with `validated=true` is installed. The default is an explicit unvalidated identity artifact and the gate emits `NO TRADE`.

A production research run should fit a calibration method on a training/validation window only, select it using validation Brier/log-loss/ECE, and report it on a later untouched test window. Candidate methods may include Platt/temperature/isotonic/beta calibration, but the method and schema must be persisted with the artifact. Tiny samples must be marked insufficient. Reliability bins should display predicted range, observed outcome rate, and count; no strong conclusion should be drawn from a small bin.

The stored prediction includes calibration version, input fingerprint, model version, target index, and gate reason, so later outcome recording cannot rewrite the original input.
