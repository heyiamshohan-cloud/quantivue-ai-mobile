package com.merqo.quantivue.core

import kotlin.test.*

class PredictionPipelineTest {
    private fun candle(i: Long, close: Double, direction: Int = if (close < .5) 1 else -1) =
        CandleRecord(i, if (direction > 0) .58 else .42, .25, .75, close, i * 60_000L, .9)

    @Test fun runningCandleTargetsTheFollowingIndex() {
        val running = RunningCandle(17, CandleState.DEVELOPING, .5, .48, .42, .58, 17 * 60_000L, 17 * 60_000L + 20_000, .9, 20_000, 40_000)
        val context = PredictionContext(
            (0L..16L).map { candle(it, if (it % 2 == 0) .48 else .52) }, running,
            ChartQuality(true, .9, 0.0, .1, .9, true), .9,
            StructureFeatures(Trend.SIDEWAYS, emptyList(), null, .1, .2, .8),
            PriceActionFeatures(null, null, false, false, .2, .1, .0, .8), emptyList(),
            MomentumFeatures(.2, .1, .0, .1, .2, .1, .1, .8), VolatilityFeatures(.1, 0.0, .1, 0.0, .1, .8),
            RegimeFeatures(Regime.RANGE, .8, .1), SimilarityFeatures(false, 0, null, null, 0.0, false), 17 * 60_000L
        )
        assertEquals(18L, context.targetCandleIndex)
        assertTrue(context.confirmedCandles.none { it.index >= context.targetCandleIndex })
    }

    @Test fun contextRejectsRunningCandleInConfirmedHistory() {
        val running = RunningCandle(2, CandleState.DEVELOPING, .5, .48, .42, .58, 120_000, 130_000, .8, 10_000, 50_000)
        assertFailsWith<IllegalArgumentException> {
            PredictionContext(
                listOf(candle(0, .5), candle(1, .5), candle(2, .5)), running,
                ChartQuality(true, .9, 0.0, .1, .9, true), .9,
                StructureFeatures(Trend.UNKNOWN, emptyList(), null, 0.0, 0.0, .2),
                PriceActionFeatures(null, null, false, false, 1.0, 0.0, .0, .1), emptyList(),
                MomentumFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, .1), VolatilityFeatures(0.0, 0.0, 0.0, 0.0, 0.0, .1),
                RegimeFeatures(Regime.UNKNOWN, .1, .9), SimilarityFeatures(false, 0, null, null, 0.0, false), 130_000
            )
        }
    }

    @Test fun liveGateRefusesUncalibratedDirectionalOutput() {
        val candles = (0L until 14L).map { candle(it, if (it % 3 == 0) .45 else .55) }
        val running = RunningCandle(14, CandleState.DEVELOPING, .55, .45, .4, .6, 840_000, 850_000, .9, 10_000, 50_000)
        val quality = ChartQuality(true, .9, 0.0, .1, .9, true)
        val result = AnalysisEngine().analyze(candles, running, quality, .9, 850_000)
        assertEquals(Direction.NO_TRADE, result.direction)
        assertEquals("uncalibrated-none", result.calibrationVersion)
        assertEquals(15L, result.targetCandleIndex)
    }

    @Test fun futureCandleLeakageIsRejected() {
        val running = RunningCandle(5, CandleState.DEVELOPING, .5, .5, .4, .6, 300_000, 305_000, .9, 5_000, 55_000)
        val confirmed = (0L..6L).map { candle(it, .5) }
        assertFailsWith<IllegalArgumentException> { CausalGuard.validatePredictionInputs(305_000, 6, confirmed, running) }
    }

    @Test fun walkForwardWindowsNeverOverlapTemporalRoles() {
        val windows = WalkForwardSplitter(10, 3, 2).split(30)
        assertTrue(windows.isNotEmpty())
        windows.forEach {
            assertTrue(it.train.last < it.validation.first)
            assertTrue(it.validation.last < it.test.first)
        }
    }

    @Test fun trackerKeepsRunningSeparateUntilAVisualCandleAppears() {
        val roi = ChartROI(0, 0, 300, 200, .9, 0)
        fun detected(x: Int, bullish: Boolean) = DetectedCandle(x, x + 5, 30, 170, 70, 130, bullish, .9)
        val tracker = CandleSequenceTracker()
        val first = tracker.update((0 until 14).map { detected(it * 18, it % 2 == 0) }, roi, 0)
        assertEquals(13L, first.running?.index)
        assertEquals(CandleState.OPEN, first.running?.state)
        assertEquals(13, first.confirmed.size)
        val second = tracker.update((0 until 15).map { detected(it * 18, it % 2 == 0) }, roi, 60_000)
        assertEquals(14L, second.running?.index)
        assertEquals(14, second.confirmed.size)
        assertEquals(13L, second.newlyConfirmed?.index)
    }

    @Test fun similarityRejectsFutureSamples() {
        val current = FloatArray(3) { 1f }
        val examples = listOf(
            SimilarityEngine.HistoricalExample(current, true, Regime.RANGE, sampleTimestamp = 100, targetIndex = 3),
            SimilarityEngine.HistoricalExample(current, false, Regime.RANGE, sampleTimestamp = 500, targetIndex = 8)
        )
        val result = SimilarityEngine.retrieve(current, Regime.RANGE, examples, predictionTimestamp = 200, targetIndex = 5)
        assertEquals(1, result.sampleCount)
        assertEquals(1.0, result.bullishRate)
    }

    @Test fun lifecycleRequiresStableDirectionalEvidence() {
        val base = PredictionResult(
            Direction.CALL, .7, .3, .7, Uncertainty.LOW, .2, "MODERATE", Regime.TREND_UP,
            4, 1, SignalLifecycle.CONFIRMED, emptyList(), emptyList(), emptyList(), emptyList(),
            "test", "cal", 10, 1, "passed", "fingerprint"
        )
        val manager = SignalLifecycleManager(2)
        assertEquals(Direction.NO_TRADE, manager.apply(base).direction)
        assertEquals(Direction.CALL, manager.apply(base).direction)
        assertEquals(SignalLifecycle.ACTIVE, manager.apply(base).lifecycle)
        assertEquals(Direction.NO_TRADE, manager.apply(base.copy(direction = Direction.NO_TRADE)).direction)
    }

    @Test fun runningCandleEntersClosingWithoutBecomingConfirmed() {
        val roi = ChartROI(0, 0, 300, 200, .9, 0)
        val detected = DetectedCandle(240, 245, 30, 170, 70, 130, true, .9)
        val tracker = CandleSequenceTracker(1_000)
        tracker.update(listOf(detected), roi, 0)
        val state = tracker.update(listOf(detected), roi, 800)
        assertEquals(CandleState.CLOSING, state.running?.state)
        assertTrue(state.confirmed.isEmpty())
    }

    @Test fun calibrationLoaderRejectsIncompatibleArtifacts() {
        val manifest = CalibrationManifest("cal-v1", "dataset-v1", "different-model", "features-v1",
            1.0, 0.0, 100, .05, "sha256:abc", true)
        val artifact = CalibrationArtifactLoader.load(manifest, "local-statistical-v1", "features-v1")
        assertFalse(artifact.validated)
        assertEquals("uncalibrated-none", artifact.version)
    }

    @Test fun calibrationMetricsAreDataDriven() {
        val metrics = CalibrationMetrics.calculate(listOf(.8 to true, .2 to false, .5 to true))
        assertEquals(3, metrics.sampleCount)
        assertNotNull(metrics.brierScore)
        assertEquals("MEASURED_ON_PROVIDED_DATA", metrics.status)
    }
}
