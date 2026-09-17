package com.merqo.quantivue.core

/** Dataset contracts used by replay and validation. All windows are chronological and causal. */
data class TemporalExample(
    val timestamp: Long,
    val targetIndex: Long,
    val features: FloatArray,
    val outcome: Direction? = null
)

object CausalGuard {
    /** Rejects any input containing the target or a sample after the prediction timestamp. */
    fun validatePredictionInputs(
        predictionTimestamp: Long,
        targetIndex: Long,
        confirmed: List<CandleRecord>,
        running: RunningCandle,
        historicalExamples: List<TemporalExample> = emptyList()
    ) {
        require(running.index < targetIndex) { "Running candle must precede target candle" }
        require(running.startedAtMillis <= predictionTimestamp) { "Running candle begins in the future" }
        require(confirmed.none { it.index >= targetIndex }) { "Target/future candle leaked into confirmed history" }
        require(confirmed.none { it.startedAtMillis > predictionTimestamp }) { "Future confirmed candle leaked into features" }
        require(historicalExamples.none { it.targetIndex >= targetIndex || it.timestamp >= predictionTimestamp }) {
            "Future similarity example leaked into prediction"
        }
    }
}

data class WalkForwardWindow(val train: LongRange, val validation: LongRange, val test: LongRange)

class WalkForwardSplitter(private val trainSize: Int, private val validationSize: Int, private val testSize: Int, private val step: Int = testSize) {
    init { require(trainSize > 0 && validationSize > 0 && testSize > 0 && step > 0) }
    fun split(total: Int): List<WalkForwardWindow> {
        val out = ArrayList<WalkForwardWindow>()
        var start = 0
        while (start + trainSize + validationSize + testSize <= total) {
            val trainEnd = start + trainSize - 1
            val validationEnd = trainEnd + validationSize
            out += WalkForwardWindow(start.toLong()..trainEnd.toLong(), (trainEnd + 1L)..validationEnd.toLong(),
                (validationEnd + 1L)..(validationEnd + testSize))
            start += step
        }
        return out
    }
}

data class ValidationMetrics(
    val sampleCount: Int,
    val accuracy: Double?,
    val balancedAccuracy: Double?,
    val brierScore: Double?,
    val logLoss: Double?,
    val noTradeRate: Double?,
    val coverage: Double?,
    val expectedCalibrationError: Double?,
    val status: String = "INSUFFICIENT REAL DATA"
)

object CalibrationMetrics {
    fun calculate(predictions: List<Pair<Double, Boolean>>): ValidationMetrics {
        if (predictions.isEmpty()) return ValidationMetrics(0, null, null, null, null, null, null, null)
        val brier = predictions.map { (p, y) -> val d = p - if (y) 1.0 else 0.0; d * d }.average()
        val logLoss = predictions.map { (p, y) ->
            val q = p.coerceIn(.0001, .9999); if (y) -kotlin.math.ln(q) else -kotlin.math.ln(1.0 - q)
        }.average()
        val bins = (0..9).map { i -> predictions.filter { (p, _) -> p >= i / 10.0 && (p < (i + 1) / 10.0 || i == 9) } }
        val ece = bins.filter { it.isNotEmpty() }.sumOf { bin ->
            val meanP = bin.map { it.first }.average(); val observed = bin.count { it.second }.toDouble() / bin.size
            bin.size.toDouble() / predictions.size * kotlin.math.abs(meanP - observed)
        }
        val correct = predictions.count { (p, y) -> (p >= .5) == y }
        return ValidationMetrics(predictions.size, correct.toDouble() / predictions.size, null, brier, logLoss,
            noTradeRate = 0.0, coverage = 1.0, expectedCalibrationError = ece, status = "MEASURED_ON_PROVIDED_DATA")
    }
}
