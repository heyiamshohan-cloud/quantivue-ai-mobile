# Validation and replay

Primary evaluation must be chronological. `WalkForwardSplitter` produces disjoint train, validation, and test ranges and advances by a configured step. Random splitting is not used for the primary evaluation path. A replay harness should reveal one target candle only after generating the prediction for it; `CausalGuard` rejects confirmed candles at/after the target, future timestamps, and future similarity examples.

Required research reporting includes accuracy, balanced accuracy, precision/recall/F1, Brier, log loss, ECE, confusion matrix, signal frequency, coverage, `NO_TRADE` rate, regime/volatility/confidence buckets, and sample-size warnings. No results are included in this repository because no genuine dataset was supplied. Status is `INSUFFICIENT REAL DATA`.

Unit tests intentionally introduce a future candle and verify rejection, check next-candle indexing, enforce running/confirmed separation, and check walk-forward ordering. Test fixtures are software fixtures only and must not be interpreted as market performance.
