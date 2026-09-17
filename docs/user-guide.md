# User guide

1. Open Quantivue AI. The dashboard starts in `NO TRADE`/ready state.
2. Open the Quotex app and put the intended chart on a visible 1-minute timeframe with enough history and minimal obstruction.
3. Return to Quantivue and tap **Confirm visible chart is 1M**. This is an explicit safety acknowledgement, not hidden timeframe inference.
4. Tap **START** and approve Android's capture prompt. A persistent notification confirms the authorized capture.
5. Switch to Quotex. Leave the chart visible; Quantivue will sample frames continuously without manual screenshots.
6. Review ROI confidence, confirmed candle count, uncertainty, regime, and gate reason. `NO TRADE` is valid and expected until a validated calibration artifact is available.
7. Optionally enable the Android overlay. It shows a compact summary and never taps or alters Quotex.
8. Use **PAUSE**, **RESUME**, **STOP**, or **RESET SESSION** at any time. Reset only clears the current in-memory chart context; historical audit rows remain local.

The app cannot see hidden prices, exact ticks, credentials, or the user's account. Do not treat estimates as guarantees or financial advice.
