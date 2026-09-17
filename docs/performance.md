# Performance

The capture service uses a two-buffer `ImageReader`, `acquireLatestImage`, one handler thread, one processing flag, ROI-only work after detection, and periodic re-detection. Frames received, processed, dropped, and deep-path latency are exposed in the dashboard/notification payload and stored only as diagnostics in memory.

The current profile is balanced and capped at a 1440-pixel width. A future device profile can lower the cap/sample cadence for thermal or battery pressure. No claim is made about device throughput because this checkout was built without an emulator or Android runtime. The implementation avoids unbounded queues, UI-thread CV, and persistent screenshots.

Before release, measure capture, ROI, candle, feature, inference, and deep-path latency on low/mid/high devices, plus memory/GC, thermal, battery, portrait/landscape, and rotation/revocation recovery. Release gates should be based on measured data rather than a guessed target.
