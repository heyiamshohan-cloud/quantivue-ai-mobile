# Release

Release checklist:

1. Run unit, integration, leakage, and replay tests on a real dataset.
2. Run chronological walk-forward validation and retain metrics/calibration provenance.
3. Confirm no future target data reaches `PredictionContext`; inspect audit fingerprints.
4. Build debug and release variants; install and launch debug on Android 10+.
5. Test MediaProjection grant/revocation, foreground notification, rotation, process restart, overlay denial, low battery, and stop controls.
6. Sign release with a user-controlled keystore outside the repository.
7. Run `scripts/package_release.sh`, review `SHA256SUMS.txt`, and publish only generated artifacts.

The current `release/RELEASE_NOTES.md` records that this agent container lacked Android build tooling; no missing APK is represented as generated.
