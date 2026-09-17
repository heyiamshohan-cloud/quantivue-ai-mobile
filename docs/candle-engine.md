# Candle engine

The screen detector works in normalized vertical coordinates: `0.0` is the top of the ROI and `1.0` is the bottom. A detected candle includes horizontal span, high/low, body bounds, polarity, and confidence. The detector uses chroma/color families and geometry, not one hard-coded pixel value.

`CandleSequenceTracker` treats the right-most candle as potentially partial and creates `RunningCandle` with `OPEN`, `DEVELOPING`, or `CLOSING` state based on elapsed wall-clock time. It updates open/current/high/low, range/body ratio, confidence, elapsed time, and remaining estimate. A prior running candle moves to immutable confirmed history only after a new visual candle appears. Thus a frame update cannot accidentally turn the current candle into historical data.

The engine is honest about visual limits: coordinates are relative, exact OHLC and ticks are not asserted, occluded/blurred charts lower quality, and the source field remains `visual-relative`. A future production detector can plug in OCR/theme calibration without changing the prediction contract.
