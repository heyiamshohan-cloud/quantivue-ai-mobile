# Android capture

Quantivue uses only the public `MediaProjection` flow. `MainActivity` launches `MediaProjectionManager.createScreenCaptureIntent`; the user approves the system prompt. The result is passed to `CaptureAnalysisService`, which runs as a foreground service with `foregroundServiceType="mediaProjection"` and shows an ongoing notification.

The service creates a bounded `ImageReader` (`RGBA_8888`, two buffers) and an auto-mirror `VirtualDisplay`. The image listener calls `acquireLatestImage`, so stale frames are dropped rather than queued. A single atomic processing flag supplies backpressure. A dedicated handler thread performs copy, ROI, candle, and analysis work; the UI thread never runs CV/inference.

The app calls `Image.close()` in `finally`, releases the virtual display/reader/projection on stop, and handles projection callbacks. It does not request accessibility services, read passwords, inspect hidden app state, use private APIs, or automate taps. Android's overlay permission is optional and used only for a small `WindowManager` analysis card.

## User controls

`START` requests capture. `PAUSE`, `RESUME`, `STOP`, and `RESET SESSION` are explicit. Revoking projection stops the service and publishes a visible error. Notifications are requested only on Android 13+.
