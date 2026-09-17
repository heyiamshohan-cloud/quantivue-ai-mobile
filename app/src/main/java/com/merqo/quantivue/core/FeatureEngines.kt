package com.merqo.quantivue.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CandleSequenceTracker(private val candlePeriodMillis: Long = 60_000L) {
    private var nextIndex = 0L
    private val confirmed = ArrayList<CandleRecord>()
    private var lastRunning: RunningCandle? = null
    private var lastRightX: Int? = null
    private var lastVisibleCount = 0

    data class State(val confirmed: List<CandleRecord>, val running: RunningCandle?, val newlyConfirmed: CandleRecord? = null)

    fun reset() { nextIndex = 0; confirmed.clear(); lastRunning = null; lastRightX = null; lastVisibleCount = 0 }

    /**
     * The right-most visible candle is treated as potentially incomplete. It is never appended to
     * confirmed history until a new right-most candle appears or the clock crosses a period.
     */
    fun update(detected: List<DetectedCandle>, roi: ChartROI, now: Long): State {
        if (detected.isEmpty()) return State(confirmed.toList(), lastRunning, null)
        val usable = detected.takeLast(64)
        val right = usable.last()
        val chartRange = roi.height.toDouble().coerceAtLeast(1.0)
        val rightMid = ((right.bodyTop + right.bodyBottom) / 2.0 - roi.top) / chartRange
        val high = ((right.high - roi.top).toDouble() / chartRange).coerceIn(0.0, 1.0)
        val low = ((right.low - roi.top).toDouble() / chartRange).coerceIn(0.0, 1.0)
        val open = if (right.bullish) ((right.bodyBottom - roi.top).toDouble() / chartRange) else ((right.bodyTop - roi.top).toDouble() / chartRange)
        val current = if (right.bullish) ((right.bodyTop - roi.top).toDouble() / chartRange) else ((right.bodyBottom - roi.top).toDouble() / chartRange)
        val newVisualCandle = lastRunning != null && (usable.size > lastVisibleCount ||
            (lastRightX != null && right.left - lastRightX!! > max(3, roi.width / 160)))
        var newlyConfirmed: CandleRecord? = null
        if (lastRunning != null && newVisualCandle && usable.size >= 2) {
            // Confirm the prior running candle using its last observed state, not future target data.
            val prior = lastRunning!!
            if (prior.state != CandleState.CONFIRMED) {
                val c = CandleRecord(prior.index, prior.estimatedOpen, prior.developingHigh, prior.developingLow,
                    prior.currentPrice, prior.startedAtMillis, prior.confidence, confirmed = true)
                if (confirmed.none { it.index == c.index }) { confirmed.add(c); newlyConfirmed = c }
            }
        }
        // Rebuild old visual candles only on first context. Their source remains explicitly visual-relative.
        if (confirmed.isEmpty() && usable.size > 1) {
            usable.dropLast(1).forEachIndexed { i, d ->
                val hi = ((d.high - roi.top).toDouble() / chartRange).coerceIn(0.0, 1.0)
                val lo = ((d.low - roi.top).toDouble() / chartRange).coerceIn(0.0, 1.0)
                val o = if (d.bullish) ((d.bodyBottom - roi.top).toDouble() / chartRange) else ((d.bodyTop - roi.top).toDouble() / chartRange)
                val c = if (d.bullish) ((d.bodyTop - roi.top).toDouble() / chartRange) else ((d.bodyBottom - roi.top).toDouble() / chartRange)
                confirmed.add(CandleRecord(i.toLong(), o.coerceIn(0.0, 1.0), hi, lo, c.coerceIn(0.0, 1.0),
                    now - (usable.size - 1 - i) * candlePeriodMillis, d.confidence))
            }
            nextIndex = confirmed.size.toLong()
        }
        val index = when {
            lastRunning == null -> nextIndex
            newVisualCandle -> lastRunning!!.index + 1L
            else -> lastRunning!!.index
        }
        val prior = lastRunning
        val elapsed = if (prior == null) 0L else (now - prior.startedAtMillis).coerceAtLeast(0L)
        val state = when {
            prior == null || newVisualCandle -> CandleState.OPEN
            elapsed < candlePeriodMillis * .72 -> CandleState.DEVELOPING
            elapsed < candlePeriodMillis * 1.05 -> CandleState.CLOSING
            else -> CandleState.OPEN
        }
        val running = RunningCandle(index, state, open.coerceIn(0.0, 1.0), current.coerceIn(0.0, 1.0),
            min(high, low), max(high, low), if (newVisualCandle) now else prior?.startedAtMillis ?: now, now,
            right.confidence.coerceIn(0.0, 1.0), elapsed, (candlePeriodMillis - elapsed).coerceAtLeast(0L))
        lastRunning = running
        lastRightX = right.left
        lastVisibleCount = usable.size
        nextIndex = max(nextIndex, index + 1)
        return State(confirmed.sortedBy { it.index }.takeLast(80), running, newlyConfirmed)
    }
}

object StructureEngine {
    fun calculate(candles: List<CandleRecord>, running: RunningCandle): StructureFeatures {
        if (candles.size < 4) return StructureFeatures(Trend.UNKNOWN, emptyList(), null, 0.0, 0.0, .2)
        val recent = candles.takeLast(12)
        val deltas = recent.zipWithNext { a, b -> b.close - a.close }
        val mean = deltas.average()
        val positive = deltas.count { it < -.001 }
        val negative = deltas.count { it > .001 }
        val trend = when {
            positive >= deltas.size * .65 -> Trend.BULLISH
            negative >= deltas.size * .65 -> Trend.BEARISH
            abs(mean) < .01 -> Trend.SIDEWAYS
            else -> Trend.TRANSITIONAL
        }
        val labels = ArrayList<String>()
        recent.zipWithNext { a, b ->
            when {
                b.high < a.high && b.low < a.low -> labels.add("LL")
                b.high > a.high && b.low > a.low -> labels.add("HH")
                b.high < a.high -> labels.add("LH")
                b.low > a.low -> labels.add("HL")
            }
        }
        val last = recent.last()
        val breakout = when {
            trend == Trend.BULLISH && last.close < last.high + .001 && last.bodyRatio > .65 -> "bullish-impulse"
            trend == Trend.BEARISH && last.close > last.low - .001 && last.bodyRatio > .65 -> "bearish-impulse"
            else -> null
        }
        val rejection = ((last.upperWick + last.lowerWick) / last.range).coerceIn(0.0, 1.0)
        val continuation = (if (trend == Trend.BULLISH && running.bullish) .8 else if (trend == Trend.BEARISH && running.bearish) .8 else .25)
        return StructureFeatures(trend, labels.takeLast(8), breakout, rejection, continuation,
            (recent.map { it.confidence }.average() * .7 + .3).coerceIn(0.0, 1.0))
    }
}

object PriceActionEngine {
    fun calculate(candles: List<CandleRecord>, running: RunningCandle): PriceActionFeatures {
        if (candles.size < 2) return PriceActionFeatures(null, null, false, false, 1.0, 0.0, 0.0, .2)
        val previous = candles[candles.lastIndex - 1]
        val last = candles.last()
        val engulfing = when {
            last.bullish && previous.bearish && last.body > previous.body * 1.05 -> "bullish-engulfing-like"
            last.bearish && previous.bullish && last.body > previous.body * 1.05 -> "bearish-engulfing-like"
            else -> null
        }
        val pinBar = when {
            last.lowerWick > last.body * 2.0 && last.bodyRatio < .45 -> "lower-rejection"
            last.upperWick > last.body * 2.0 && last.bodyRatio < .45 -> "upper-rejection"
            else -> null
        }
        val inside = last.high >= previous.high && last.low <= previous.low // vertical coordinates: visually inside range
        val outside = last.high <= previous.high && last.low >= previous.low
        val indecision = (1.0 - last.bodyRatio).coerceIn(0.0, 1.0)
        val sameDirection = candles.takeLast(4).count { it.directionSign == last.directionSign }
        val exhaustion = if (sameDirection >= 3 && last.range < candles.takeLast(4).map { it.range }.average() * .7) .75 else .15
        val patternScore = when {
            engulfing?.startsWith("bullish") == true || pinBar == "lower-rejection" -> .55
            engulfing?.startsWith("bearish") == true || pinBar == "upper-rejection" -> -.55
            else -> 0.0
        }
        val runningAdjustment = when { running.bullish -> .12; running.bearish -> -.12; else -> 0.0 }
        return PriceActionFeatures(engulfing, pinBar, inside, outside, indecision, exhaustion,
            (patternScore + runningAdjustment).coerceIn(-1.0, 1.0),
            (last.confidence * previous.confidence).coerceIn(0.0, 1.0))
    }
}

object SupportResistanceEngine {
    fun calculate(candles: List<CandleRecord>, running: RunningCandle): List<SupportResistanceZone> {
        if (candles.size < 3) return emptyList()
        val points = ArrayList<Pair<Double, Boolean>>()
        candles.takeLast(30).forEach { c ->
            points.add(c.high to false); points.add(c.low to true)
            // Recent points are naturally preferred by the later strength calculation.
        }
        val tolerance = (candles.takeLast(12).map { it.range }.average() * .35).coerceIn(.008, .08)
        val clusters = ArrayList<MutableList<Pair<Double, Boolean>>>()
        for (point in points) {
            val cluster = clusters.firstOrNull { abs(it.map { p -> p.first }.average() - point.first) <= tolerance }
            if (cluster == null) clusters.add(arrayListOf(point)) else cluster.add(point)
        }
        return clusters.mapNotNull { c ->
            if (c.size < 2) return@mapNotNull null
            val center = c.map { it.first }.average()
            val touches = c.size
            val recency = touches.toDouble() / points.size.coerceAtLeast(1)
            val reaction = c.count { it.second }.toDouble() / touches
            val distance = abs(((running.currentPrice + running.estimatedOpen) / 2) - center)
            SupportResistanceZone(center, tolerance, (touches / 8.0).coerceIn(0.0, 1.0), touches,
                recency.coerceIn(0.0, 1.0), reaction, distance,
                breakoutStatus = null, rejectionStatus = if (distance <= tolerance) "near-zone" else null,
                confidence = (touches / 6.0).coerceIn(0.0, 1.0))
        }.sortedBy { it.distanceToCurrent }.take(8)
    }
}

object MomentumEngine {
    fun calculate(candles: List<CandleRecord>, running: RunningCandle): MomentumFeatures {
        if (candles.size < 3) return MomentumFeatures(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, .15)
        val recent = candles.takeLast(8)
        val signs = recent.map { it.directionSign }.filter { it != 0 }
        val persistence = if (signs.isEmpty()) 0.0 else abs(signs.sum()).toDouble() / signs.size
        val firstRange = recent.take(max(1, recent.size / 2)).map { it.range }.average()
        val secondRange = recent.takeLast(max(1, recent.size / 2)).map { it.range }.average()
        val expansion = ((secondRange / firstRange.coerceAtLeast(.0001)) - 1.0).coerceIn(-1.0, 1.0)
        val acceleration = if (recent.size >= 3) (recent.takeLast(2).map { it.directionSign }.sum() - recent.take(2).map { it.directionSign }.sum()) / 4.0 else 0.0
        val runningSign = if (running.bullish) 1 else if (running.bearish) -1 else 0
        val pullback = if (signs.isNotEmpty() && signs.last() != runningSign) .7 else .15
        val decay = if (secondRange < firstRange * .75) .8 else .2
        val reversal = (pullback * .55 + decay * .45).coerceIn(0.0, 1.0)
        val score = (signs.sum().toDouble() / signs.size.coerceAtLeast(1) * .55 + acceleration * .25 - reversal * .2).coerceIn(-1.0, 1.0)
        return MomentumFeatures(persistence, expansion, acceleration, pullback, decay, reversal, score, .75)
    }
}

object VolatilityEngine {
    fun calculate(candles: List<CandleRecord>): VolatilityFeatures {
        if (candles.size < 3) return VolatilityFeatures(0.0, 0.0, 0.0, 0.0, 0.0, .1)
        val recent = candles.takeLast(12)
        val ranges = recent.map { it.range }
        val average = ranges.average().coerceAtLeast(.0001)
        val short = ranges.takeLast(min(4, ranges.size)).average()
        val expansion = ((short / average) - 1.0).coerceIn(-1.0, 2.0)
        val compression = if (expansion < 0) -expansion else 0.0
        val spike = (short / ranges.dropLast(min(1, ranges.size - 1)).average().coerceAtLeast(.0001) - 1.0).coerceIn(0.0, 3.0) / 3.0
        return VolatilityFeatures(average, expansion, compression, spike, short.coerceIn(0.0, 1.0), .8)
    }
}

object RegimeEngine {
    fun calculate(structure: StructureFeatures, volatility: VolatilityFeatures, candles: List<CandleRecord>): RegimeFeatures {
        val regime = when {
            volatility.spike > .7 -> Regime.HIGH_VOLATILITY
            volatility.compression > .45 -> Regime.LOW_VOLATILITY
            structure.breakout != null -> Regime.BREAKOUT
            structure.trend == Trend.BULLISH -> Regime.TREND_UP
            structure.trend == Trend.BEARISH -> Regime.TREND_DOWN
            structure.trend == Trend.SIDEWAYS && structure.confidence > .5 -> Regime.RANGE
            structure.trend == Trend.TRANSITIONAL -> Regime.TRANSITION
            candles.size >= 5 -> Regime.CHOP
            else -> Regime.UNKNOWN
        }
        return RegimeFeatures(regime, (structure.confidence * .7 + volatility.confidence * .3).coerceIn(0.0, 1.0),
            if (regime == Regime.TRANSITION || regime == Regime.HIGH_VOLATILITY) .75 else .25)
    }
}
