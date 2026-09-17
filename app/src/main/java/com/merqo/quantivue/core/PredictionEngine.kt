package com.merqo.quantivue.core

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** A calibration artifact is deliberately explicit: identity/raw output is not validated calibration. */
data class CalibrationArtifact(
    val version: String,
    val slope: Double,
    val intercept: Double,
    val validated: Boolean,
    val sampleCount: Int,
    val expectedCalibrationError: Double?
) {
    fun apply(raw: Double): Double = (raw * slope + intercept).coerceIn(0.001, 0.999)
}

object ProbabilityCalibrator {
    fun unavailable(): CalibrationArtifact = CalibrationArtifact(
        version = "uncalibrated-none", slope = 1.0, intercept = 0.0, validated = false,
        sampleCount = 0, expectedCalibrationError = null
    )

    fun apply(raw: Double, artifact: CalibrationArtifact): Double = artifact.apply(raw)
}

object SimilarityEngine {
    data class HistoricalExample(val features: FloatArray, val bullishOutcome: Boolean, val regime: Regime)

    fun retrieve(current: FloatArray, currentRegime: Regime, examples: List<HistoricalExample>, k: Int = 20): SimilarityFeatures {
        if (examples.isEmpty() || current.isEmpty()) return SimilarityFeatures(false, 0, null, null, 0.0, false)
        val nearest = examples.asSequence().map { it to cosine(current, it.features) }
            .filter { it.second >= .55 && (it.first.regime == currentRegime || currentRegime == Regime.UNKNOWN) }
            .sortedByDescending { it.second }.take(k).toList()
        if (nearest.isEmpty()) return SimilarityFeatures(false, 0, null, null, 0.0, false)
        val bullish = nearest.count { it.first.bullishOutcome }.toDouble() / nearest.size
        return SimilarityFeatures(true, nearest.size, bullish, 1.0 - bullish,
            nearest.map { it.second }.average(), true)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) 0.0 else (dot / (sqrt(aa) * sqrt(bb))).coerceIn(-1.0, 1.0)
    }

    private fun sqrt(v: Double): Double = kotlin.math.sqrt(v)
}

data class EnsembleResult(val outputs: List<ModelOutput>, val rawBullish: Double, val uncertainty: Double)

class LocalSpecialistEnsemble {
    private val version = "local-statistical-v1"

    fun infer(context: PredictionContext): EnsembleResult {
        val started = System.nanoTime()
        val outputs = ArrayList<ModelOutput>()
        val trendBull = when (context.structure.trend) { Trend.BULLISH -> .72; Trend.BEARISH -> .28; else -> .5 }
        outputs += output("structure", trendBull, context.structure.confidence, 1.0 - context.structure.confidence, context.structure.confidence, started)
        val mom = (0.5 + context.momentum.score * .36).coerceIn(.05, .95)
        outputs += output("momentum", mom, context.momentum.confidence, 1.0 - context.momentum.confidence, context.momentum.confidence, started)
        val runningBias = when { context.runningCandle.bullish -> .58; context.runningCandle.bearish -> .42; else -> .5 }
        outputs += output("running-price-action", runningBias, context.runningCandle.confidence, .48, context.runningCandle.confidence, started)
        val volPenalty = (context.volatility.spike * .2 + context.volatility.expansion.coerceAtLeast(0.0) * .1).coerceIn(0.0, .25)
        outputs += output("volatility", (.5 + context.momentum.score * (.25 - volPenalty)).coerceIn(.05, .95),
            context.volatility.confidence, (.35 + volPenalty).coerceIn(0.0, 1.0), context.volatility.confidence, started)
        val zone = nearbyZoneBias(context)
        outputs += output("support-resistance", zone.first, zone.second, 1.0 - zone.second, zone.second, started)
        if (context.similarity.available && context.similarity.bullishRate != null) {
            outputs += output("historical-similarity", context.similarity.bullishRate, context.similarity.similarity,
                1.0 - context.similarity.similarity, context.similarity.similarity, started)
        }
        // Weighted consensus preserves provenance instead of inventing a black-box score.
        val weights = outputs.map { (it.confidence * it.featureQuality * (1.0 - it.uncertainty)).coerceAtLeast(.01) }
        val total = weights.sum().coerceAtLeast(.01)
        val bullish = outputs.mapIndexed { i, o -> o.bullishProbability * weights[i] }.sum() / total
        val disagreement = outputs.map { abs(it.bullishProbability - bullish) }.average().coerceIn(0.0, 1.0)
        val uncertainty = (disagreement * .65 + (1.0 - context.chartQuality.score) * .2 +
            context.regime.transitionRisk * .15).coerceIn(0.0, 1.0)
        return EnsembleResult(outputs, bullish.coerceIn(.001, .999), uncertainty)
    }

    private fun nearbyZoneBias(context: PredictionContext): Pair<Double, Double> {
        val zone = context.zones.minByOrNull { it.distanceToCurrent } ?: return .5 to .15
        if (zone.distanceToCurrent > zone.halfWidth * 2.5) return .5 to .2
        val current = (context.runningCandle.currentPrice + context.runningCandle.estimatedOpen) / 2
        val atResistance = zone.center < current // y-axis: lower coordinate means higher visual price
        return if (atResistance) .42 to zone.confidence else .58 to zone.confidence
    }

    private fun output(id: String, p: Double, confidence: Double, uncertainty: Double, quality: Double, started: Long): ModelOutput =
        ModelOutput(id, p.coerceIn(.001, .999), confidence.coerceIn(0.0, 1.0), uncertainty.coerceIn(0.0, 1.0),
            quality.coerceIn(0.0, 1.0), (System.nanoTime() - started) / 1_000_000L, version)
}

class ContradictionEngine {
    fun evaluate(direction: Direction, context: PredictionContext, bullish: Double): List<Contradiction> {
        if (direction == Direction.NO_TRADE) return emptyList()
        val result = ArrayList<Contradiction>()
        val edgeAgainst = if (direction == Direction.CALL) 1.0 - bullish else bullish
        if (edgeAgainst > .43) result += Contradiction("specialists are split", edgeAgainst)
        if (context.volatility.spike > .65) result += Contradiction("volatility spike", context.volatility.spike)
        if (context.regime.transitionRisk > .65) result += Contradiction("regime transition", context.regime.transitionRisk)
        if (context.runningCandle.state == CandleState.CLOSING) result += Contradiction("running candle is closing", .55)
        if (context.zones.any { it.distanceToCurrent <= it.halfWidth && it.confidence > .55 }) {
            result += Contradiction("nearby reaction zone", .58)
        }
        if (direction == Direction.CALL && context.structure.trend == Trend.BEARISH) result += Contradiction("bearish structure", .7)
        if (direction == Direction.PUT && context.structure.trend == Trend.BULLISH) result += Contradiction("bullish structure", .7)
        return result.sortedByDescending { it.severity }
    }

    fun counterfactuals(direction: Direction, context: PredictionContext): List<Counterfactual> = when (direction) {
        Direction.CALL -> listOf(
            Counterfactual("rejection at the nearest resistance zone", Direction.CALL, .7),
            Counterfactual("momentum score falls below neutral", Direction.CALL, .55)
        )
        Direction.PUT -> listOf(
            Counterfactual("support holds and rejects lower prices", Direction.PUT, .7),
            Counterfactual("momentum score turns positive", Direction.PUT, .55)
        )
        Direction.NO_TRADE -> emptyList()
    }
}

class SignalGate(private val calibration: CalibrationArtifact = ProbabilityCalibrator.unavailable()) {
    fun evaluate(context: PredictionContext, ensemble: EnsembleResult, now: Long): PredictionResult {
        val calibratedBullish = ProbabilityCalibrator.apply(ensemble.rawBullish, calibration)
        val calibratedBearish = 1.0 - calibratedBullish
        val preliminary = if (calibratedBullish >= .5) Direction.CALL else Direction.PUT
        val contradictions = ContradictionEngine().evaluate(preliminary, context, calibratedBullish)
        val uncertaintyScore = (ensemble.uncertainty + contradictions.take(2).sumOf { it.severity } * .15).coerceIn(0.0, 1.0)
        val edge = max(calibratedBullish, calibratedBearish) - .5
        val quality = when {
            context.chartQuality.score >= .78 && edge >= .17 && uncertaintyScore < .32 -> "STRONG"
            context.chartQuality.score >= .62 && edge >= .1 && uncertaintyScore < .52 -> "MODERATE"
            else -> "WEAK"
        }
        val gateReason = when {
            !context.chartQuality.usable -> context.chartQuality.reason ?: "Chart quality insufficient"
            context.confirmedCandles.size < 12 -> "Need at least 12 confirmed candles"
            context.runningCandle.confidence < .52 -> "Running candle uncertain"
            !calibration.validated -> "Validated calibration is unavailable"
            edge < adaptiveEdge(context.regime.regime) -> "Calibrated probability edge is insufficient"
            uncertaintyScore >= .52 -> "Uncertainty is high"
            (contradictions.firstOrNull()?.severity ?: 0.0) >= .8 -> "Contradictory evidence is too strong"
            else -> "Signal gate passed"
        }
        val allowed = gateReason == "Signal gate passed"
        val direction = if (allowed) preliminary else Direction.NO_TRADE
        val lifecycle = if (direction == Direction.NO_TRADE) SignalLifecycle.VALIDATING else SignalLifecycle.CONFIRMED
        val calls = ensemble.outputs.count { it.bullishProbability >= .5 }
        val puts = ensemble.outputs.size - calls
        val evidence = buildEvidence(context, ensemble, preliminary)
        return PredictionResult(direction, calibratedBullish, calibratedBearish,
            confidence = ((1.0 - uncertaintyScore) * context.chartQuality.score).coerceIn(0.0, 1.0),
            uncertainty = when { uncertaintyScore < .3 -> Uncertainty.LOW; uncertaintyScore < .55 -> Uncertainty.MEDIUM; else -> Uncertainty.HIGH },
            uncertaintyScore, quality, context.regime.regime, calls, puts, lifecycle, evidence, contradictions,
            ContradictionEngine().counterfactuals(preliminary, context), ensemble.outputs,
            modelVersion = ensemble.outputs.firstOrNull()?.modelVersion ?: "none",
            calibrationVersion = calibration.version, context.targetCandleIndex, now, gateReason,
            fingerprint(context))
    }

    private fun adaptiveEdge(regime: Regime): Double = when (regime) {
        Regime.HIGH_VOLATILITY, Regime.TRANSITION, Regime.CHOP -> .18
        Regime.TREND_UP, Regime.TREND_DOWN -> .12
        else -> .15
    }

    private fun buildEvidence(context: PredictionContext, ensemble: EnsembleResult, direction: Direction): List<Evidence> {
        val out = ArrayList<Evidence>()
        if (context.structure.trend == Trend.BULLISH) out += Evidence("bullish structure detected", .7, Direction.CALL)
        if (context.structure.trend == Trend.BEARISH) out += Evidence("bearish structure detected", .7, Direction.PUT)
        if (context.momentum.score > .18) out += Evidence("positive momentum", context.momentum.score, Direction.CALL)
        if (context.momentum.score < -.18) out += Evidence("negative momentum", -context.momentum.score, Direction.PUT)
        if (context.volatility.expansion > .35) out += Evidence("volatility expanding", context.volatility.expansion, direction)
        if (context.similarity.available) out += Evidence("historical matches available", context.similarity.similarity, direction)
        out += Evidence("${ensemble.outputs.count { it.bullishProbability >= .5 }}/${ensemble.outputs.size} specialists call bullish", .5, if (ensemble.rawBullish >= .5) Direction.CALL else Direction.PUT)
        return out.sortedByDescending { it.weight }.take(5)
    }

    private fun fingerprint(context: PredictionContext): String {
        val text = buildString {
            append(context.featureSchemaVersion).append('|').append(context.runningCandle.index).append('|')
            context.confirmedCandles.takeLast(20).forEach { append(it.open).append(',').append(it.high).append(',').append(it.low).append(',').append(it.close).append(';') }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}

class AnalysisEngine(private val calibration: CalibrationArtifact = ProbabilityCalibrator.unavailable()) {
    private val ensemble = LocalSpecialistEnsemble()
    private val gate = SignalGate(calibration)

    fun analyze(
        confirmed: List<CandleRecord>, running: RunningCandle, quality: ChartQuality, roiConfidence: Double,
        now: Long, historicalExamples: List<SimilarityEngine.HistoricalExample> = emptyList()
    ): PredictionResult {
        CausalGuard.validatePredictionInputs(now, running.index + 1L, confirmed, running)
        val structure = StructureEngine.calculate(confirmed, running)
        val zones = SupportResistanceEngine.calculate(confirmed, running)
        val momentum = MomentumEngine.calculate(confirmed, running)
        val volatility = VolatilityEngine.calculate(confirmed)
        val regime = RegimeEngine.calculate(structure, volatility, confirmed)
        val similarity = SimilarityEngine.retrieve(featureVector(confirmed, running), regime.regime, historicalExamples)
        val context = PredictionContext(confirmed, running, quality, roiConfidence, structure, zones, momentum, volatility, regime, similarity, now)
        return gate.evaluate(context, ensemble.infer(context), now)
    }

    private fun featureVector(candles: List<CandleRecord>, running: RunningCandle): FloatArray {
        val values = candles.takeLast(12).flatMap { listOf(it.bodyRatio, it.range, it.directionSign.toDouble()) } +
            listOf(running.bodyRatio, running.range)
        return values.map { it.toFloat() }.toFloatArray()
    }
}
