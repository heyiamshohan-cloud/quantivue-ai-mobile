package com.merqo.quantivue.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The only directional labels exposed by the live signal contract. */
enum class Direction { CALL, PUT, NO_TRADE }
enum class CandleState { OPEN, DEVELOPING, CLOSING, CONFIRMED, INVALIDATED }
enum class Uncertainty { LOW, MEDIUM, HIGH, UNKNOWN }
enum class Trend { BULLISH, BEARISH, SIDEWAYS, TRANSITIONAL, UNKNOWN }
enum class Regime { TREND_UP, TREND_DOWN, RANGE, CHOP, BREAKOUT, POST_BREAKOUT, HIGH_VOLATILITY, LOW_VOLATILITY, TRANSITION, UNKNOWN }
enum class SignalLifecycle { OBSERVING, SETUP_DETECTED, VALIDATING, CONFIRMED, ACTIVE, EXPIRED, INVALIDATED }
enum class AnalysisStage {
    IDLE, WAITING_FOR_CAPTURE, CAPTURING, DETECTING_CHART, ROI_LOST, VALIDATING_TIMEFRAME,
    BUILDING_CONTEXT, ANALYZING, STALE_FRAME, PAUSED, MODEL_UNAVAILABLE, CALIBRATION_UNAVAILABLE, ERROR
}
enum class DeviceProfile { LOW, BALANCED, PERFORMANCE, MAXIMUM }

data class ChartROI(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val confidence: Double,
    val detectedAtMillis: Long
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

data class ChartQuality(
    val visible: Boolean,
    val candleIdentifiability: Double,
    val obstruction: Double,
    val blur: Double,
    val timeframeConfidence: Double,
    val enoughHistory: Boolean,
    val reason: String? = null
) {
    val score: Double
        get() = (candleIdentifiability * 0.35 + (1.0 - obstruction) * 0.2 +
            (1.0 - blur) * 0.15 + timeframeConfidence * 0.2 + if (enoughHistory) 0.1 else 0.0)
            .coerceIn(0.0, 1.0)
    val usable: Boolean
        get() = visible && enoughHistory && candleIdentifiability >= 0.48 && timeframeConfidence >= 0.65 && score >= 0.58
}

data class CandleRecord(
    val index: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val startedAtMillis: Long,
    val confidence: Double,
    val confirmed: Boolean = true,
    val source: String = "visual-relative"
) {
    init {
        require(open in 0.0..1.0 && high in 0.0..1.0 && low in 0.0..1.0 && close in 0.0..1.0)
        require(high <= low) { "Vertical chart coordinates use top=high and bottom=low" }
        require(confidence in 0.0..1.0)
    }
    val body: Double get() = abs(close - open)
    val range: Double get() = (low - high).coerceAtLeast(0.0001)
    val bodyRatio: Double get() = (body / range).coerceIn(0.0, 1.0)
    val bullish: Boolean get() = close < open
    val bearish: Boolean get() = close > open
    val directionSign: Int get() = when { bullish -> 1; bearish -> -1; else -> 0 }
    val upperWick: Double get() = min(open, close) - high
    val lowerWick: Double get() = low - max(open, close)
}

data class RunningCandle(
    val index: Long,
    val state: CandleState,
    val estimatedOpen: Double,
    val currentPrice: Double,
    val developingHigh: Double,
    val developingLow: Double,
    val startedAtMillis: Long,
    val observedAtMillis: Long,
    val confidence: Double,
    val elapsedMillis: Long,
    val estimatedRemainingMillis: Long?
) {
    init {
        require(estimatedOpen in 0.0..1.0 && currentPrice in 0.0..1.0)
        require(developingHigh in 0.0..1.0 && developingLow in 0.0..1.0)
    }
    val body: Double get() = abs(currentPrice - estimatedOpen)
    val range: Double get() = (developingLow - developingHigh).coerceAtLeast(0.0001)
    val bodyRatio: Double get() = (body / range).coerceIn(0.0, 1.0)
    val bullish: Boolean get() = currentPrice < estimatedOpen
    val bearish: Boolean get() = currentPrice > estimatedOpen
}

data class StructureFeatures(
    val trend: Trend,
    val swingLabels: List<String>,
    val breakout: String?,
    val rejection: Double,
    val continuation: Double,
    val confidence: Double
)

data class PriceActionFeatures(
    val engulfing: String?,
    val pinBar: String?,
    val insideBar: Boolean,
    val outsideBar: Boolean,
    val indecision: Double,
    val exhaustion: Double,
    val directionalScore: Double,
    val confidence: Double
)

data class SupportResistanceZone(
    val center: Double,
    val halfWidth: Double,
    val strength: Double,
    val touchCount: Int,
    val recency: Double,
    val reactionStrength: Double,
    val distanceToCurrent: Double,
    val breakoutStatus: String?,
    val rejectionStatus: String?,
    val confidence: Double
) {
    fun contains(value: Double): Boolean = value in (center - halfWidth)..(center + halfWidth)
}

data class MomentumFeatures(
    val directionalPersistence: Double,
    val bodyExpansion: Double,
    val acceleration: Double,
    val pullbackStrength: Double,
    val decay: Double,
    val reversalPressure: Double,
    val score: Double,
    val confidence: Double
)

data class VolatilityFeatures(
    val rollingRange: Double,
    val expansion: Double,
    val compression: Double,
    val spike: Double,
    val normalizedRange: Double,
    val confidence: Double
)

data class RegimeFeatures(val regime: Regime, val confidence: Double, val transitionRisk: Double)

data class SimilarityFeatures(
    val available: Boolean,
    val sampleCount: Int,
    val bullishRate: Double?,
    val bearishRate: Double?,
    val similarity: Double,
    val regimeCompatible: Boolean
)

data class ModelOutput(
    val modelId: String,
    val bullishProbability: Double,
    val confidence: Double,
    val uncertainty: Double,
    val featureQuality: Double,
    val latencyMillis: Long,
    val modelVersion: String
) {
    init {
        require(bullishProbability in 0.0..1.0)
        require(confidence in 0.0..1.0 && uncertainty in 0.0..1.0 && featureQuality in 0.0..1.0)
    }
    val bearishProbability: Double get() = 1.0 - bullishProbability
}

data class Evidence(val label: String, val weight: Double, val direction: Direction)
data class Contradiction(val label: String, val severity: Double)
data class Counterfactual(val trigger: String, val invalidates: Direction, val severity: Double)

data class PredictionContext(
    val confirmedCandles: List<CandleRecord>,
    val runningCandle: RunningCandle,
    val chartQuality: ChartQuality,
    val roiConfidence: Double,
    val structure: StructureFeatures,
    val priceAction: PriceActionFeatures,
    val zones: List<SupportResistanceZone>,
    val momentum: MomentumFeatures,
    val volatility: VolatilityFeatures,
    val regime: RegimeFeatures,
    val similarity: SimilarityFeatures,
    val capturedAtMillis: Long,
    val featureSchemaVersion: String = "features-v1"
) {
    /** Target is always the candle after the running candle; target data is not a field here. */
    val targetCandleIndex: Long get() = runningCandle.index + 1L
    init {
        require(confirmedCandles.none { it.index >= runningCandle.index }) {
            "Prediction context cannot contain running/target candles as confirmed history"
        }
        require(roiConfidence in 0.0..1.0)
    }
}

data class PredictionResult(
    val direction: Direction,
    val bullishProbability: Double,
    val bearishProbability: Double,
    val confidence: Double,
    val uncertainty: Uncertainty,
    val uncertaintyScore: Double,
    val setupQuality: String,
    val regime: Regime,
    val consensusCall: Int,
    val consensusPut: Int,
    val lifecycle: SignalLifecycle,
    val evidence: List<Evidence>,
    val contradictions: List<Contradiction>,
    val counterfactuals: List<Counterfactual>,
    val modelOutputs: List<ModelOutput>,
    val modelVersion: String,
    val calibrationVersion: String,
    val targetCandleIndex: Long,
    val timestampMillis: Long,
    val gateReason: String,
    val inputFingerprint: String
) {
    init {
        require(bullishProbability in 0.0..1.0 && bearishProbability in 0.0..1.0)
        require(abs((bullishProbability + bearishProbability) - 1.0) < 1e-9)
        require(confidence in 0.0..1.0 && uncertaintyScore in 0.0..1.0)
        require(consensusCall >= 0 && consensusPut >= 0)
    }
    val isNoTrade: Boolean get() = direction == Direction.NO_TRADE
    val displayedProbability: Int get() = (max(bullishProbability, bearishProbability) * 100.0).toInt()
}

data class AnalysisSnapshot(
    val stage: AnalysisStage = AnalysisStage.IDLE,
    val roi: ChartROI? = null,
    val quality: ChartQuality? = null,
    val prediction: PredictionResult? = null,
    val runningCandle: RunningCandle? = null,
    val confirmedCount: Int = 0,
    val framesReceived: Long = 0,
    val framesProcessed: Long = 0,
    val framesDropped: Long = 0,
    val lastLatencyMillis: Long = 0,
    val errorMessage: String? = null
)
