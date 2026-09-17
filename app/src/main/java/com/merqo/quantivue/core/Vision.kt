package com.merqo.quantivue.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lightweight, allocation-conscious visual primitives used by the foreground service. */
data class ValidatedFrame(val width: Int, val height: Int, val pixels: IntArray, val capturedAtMillis: Long)
data class DetectedCandle(
    val left: Int,
    val right: Int,
    val high: Int,
    val low: Int,
    val bodyTop: Int,
    val bodyBottom: Int,
    val bullish: Boolean,
    val confidence: Double
)

data class RoiDetection(val roi: ChartROI?, val quality: ChartQuality)

object FrameValidator {
    fun validate(width: Int, height: Int, pixels: IntArray, now: Long, previousAt: Long?): ValidatedFrame? {
        if (width < 160 || height < 160 || pixels.size < width * height) return null
        if (previousAt != null && now <= previousAt) return null
        var minL = 255
        var maxL = 0
        var total = 0L
        val step = max(1, min(width, height) / 96)
        var n = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 255
            val g = (p ushr 8) and 255
            val b = p and 255
            val l = (r * 30 + g * 59 + b * 11) / 100
            minL = min(minL, l); maxL = max(maxL, l); total += l; n++
            i += step
        }
        if (n == 0 || maxL - minL < 8) return null
        return ValidatedFrame(width, height, pixels, now)
    }
}

object ChartRoiDetector {
    /**
     * Scores candidate chart rectangles by visual information and coloured mark density.
     * It intentionally returns a low-confidence ROI rather than pretending every screen is a chart.
     */
    fun detect(frame: ValidatedFrame): RoiDetection {
        val w = frame.width
        val h = frame.height
        var best: ChartROI? = null
        var bestScore = 0.0
        val horizontalMargins = intArrayOf(0, w / 20, w / 12, w / 8)
        val topMargins = intArrayOf(h / 20, h / 12, h / 8, h / 6)
        val bottomMargins = intArrayOf(h / 8, h / 6, h / 5)
        for (mx in horizontalMargins) for (mt in topMargins) for (mb in bottomMargins) {
            val left = mx
            val top = mt
            val width = w - 2 * mx
            val height = h - mt - mb
            if (width < w * .55 || height < h * .45) continue
            val score = scoreRegion(frame.pixels, w, h, left, top, width, height)
            if (score > bestScore) {
                bestScore = score
                best = ChartROI(left, top, width, height, score.coerceIn(0.0, 1.0), frame.capturedAtMillis)
            }
        }
        val roi = best
        val identifiable = roi?.confidence ?: 0.0
        val quality = ChartQuality(
            visible = roi != null && identifiable >= .42,
            candleIdentifiability = identifiable,
            obstruction = (1.0 - identifiable).coerceIn(0.0, 1.0),
            blur = estimateBlur(frame, roi),
            timeframeConfidence = 0.0,
            enoughHistory = false,
            reason = if (roi == null) "Chart region not found" else "Timeframe label must be verified"
        )
        return RoiDetection(roi, quality)
    }

    fun refine(frame: ValidatedFrame, current: ChartROI): RoiDetection {
        val x0 = (current.left - current.width / 12).coerceAtLeast(0)
        val y0 = (current.top - current.height / 12).coerceAtLeast(0)
        val x1 = min(frame.width, current.right + current.width / 12)
        val y1 = min(frame.height, current.bottom + current.height / 12)
        val score = scoreRegion(frame.pixels, frame.width, frame.height, x0, y0, x1 - x0, y1 - y0)
        // The search window may expand, but the locked ROI must not drift/expand on every frame.
        val roi = current.copy(
            confidence = (current.confidence * .65 + score * .35).coerceIn(0.0, 1.0),
            detectedAtMillis = frame.capturedAtMillis
        )
        val q = ChartQuality(true, roi.confidence, 1.0 - roi.confidence, estimateBlur(frame, roi), 0.0, false,
            "Timeframe label must be verified")
        return RoiDetection(roi, q)
    }

    private fun scoreRegion(px: IntArray, fw: Int, fh: Int, left: Int, top: Int, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) return 0.0
        var chroma = 0; var edges = 0; var samples = 0
        val sx = max(2, width / 64); val sy = max(2, height / 64)
        var previous = -1
        var y = top + sy
        while (y < top + height - sy) {
            var x = left + sx
            while (x < left + width - sx) {
                val p = px[y * fw + x]
                val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
                val l = (r + g + b) / 3
                val c = max(r, max(g, b)) - min(r, min(g, b))
                if (c > 30) chroma++
                if (previous >= 0 && abs(l - previous) > 28) edges++
                previous = l; samples++
                x += sx
            }
            y += sy
        }
        if (samples == 0) return 0.0
        val colorScore = (chroma.toDouble() / samples * 2.6).coerceIn(0.0, 1.0)
        val edgeScore = (edges.toDouble() / samples * 2.1).coerceIn(0.0, 1.0)
        return (colorScore * .65 + edgeScore * .35).coerceIn(0.0, 1.0)
    }

    private fun estimateBlur(frame: ValidatedFrame, roi: ChartROI?): Double {
        if (roi == null) return 1.0
        var transitions = 0; var samples = 0
        val step = max(2, roi.width / 80)
        var y = roi.top + roi.height / 2
        var previous = -1
        var x = roi.left
        while (x < roi.right) {
            val p = frame.pixels[y * frame.width + x]
            val l = (((p ushr 16) and 255) * 30 + ((p ushr 8) and 255) * 59 + (p and 255) * 11) / 100
            if (previous >= 0 && abs(l - previous) > 24) transitions++
            previous = l; samples++; x += step
        }
        return (1.0 - (transitions.toDouble() / max(1, samples) * 3.0)).coerceIn(0.0, 1.0)
    }
}

object CandleDetector {
    /** Detects candle-like vertical marks without assuming one exact RGB value. */
    fun detect(frame: ValidatedFrame, roi: ChartROI): List<DetectedCandle> {
        if (roi.width < 120 || roi.height < 100) return emptyList()
        val width = roi.width
        val signal = IntArray(width)
        val upHue = IntArray(width)
        val downHue = IntArray(width)
        val yStep = max(1, roi.height / 180)
        for (xLocal in 0 until width) {
            var colored = 0; var up = 0; var down = 0
            var y = roi.top + 2
            while (y < roi.bottom - 2) {
                val p = frame.pixels[y * frame.width + roi.left + xLocal]
                val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val chroma = mx - mn
                if (chroma > 38 && mx > 95) {
                    colored++
                    // Color families, not exact pixels. Green/teal and red/magenta are common themes.
                    if (g >= r * 1.05 && g >= b * .95) up++
                    if (r >= g * 1.08 && r >= b * 1.02) down++
                }
                y += yStep
            }
            signal[xLocal] = colored
            upHue[xLocal] = up
            downHue[xLocal] = down
        }
        val threshold = max(2, roi.height / 95)
        val groups = ArrayList<IntArray>()
        var start = -1
        for (x in 0 until width) {
            if (signal[x] >= threshold) {
                if (start < 0) start = x
            } else if (start >= 0) {
                if (x - start >= 2) groups.add(intArrayOf(start, x - 1))
                start = -1
            }
        }
        if (start >= 0 && width - start >= 2) groups.add(intArrayOf(start, width - 1))
        val merged = mergeNarrowGaps(groups, width)
        val result = ArrayList<DetectedCandle>()
        for (group in merged) {
            val l = group[0]; val r = group[1]
            var high = roi.bottom; var low = roi.top; var bodyTop = roi.bottom; var bodyBottom = roi.top
            var upCount = 0; var downCount = 0; var count = 0
            for (x in l..r) {
                if (signal[x] < threshold / 2) continue
                upCount += upHue[x]; downCount += downHue[x]
                var y = roi.top + 2
                while (y < roi.bottom - 2) {
                    val p = frame.pixels[y * frame.width + roi.left + x]
                    val rr = (p ushr 16) and 255; val gg = (p ushr 8) and 255; val bb = p and 255
                    if (max(rr, max(gg, bb)) - min(rr, min(gg, bb)) > 38) {
                        high = min(high, y); low = max(low, y); count++
                    }
                    y += yStep
                }
            }
            if (count == 0 || low - high < 3) continue
            // A solid body has more coloured pixels near its centre; estimate body from projection.
            bodyTop = max(high, high + ((low - high) * .22).toInt())
            bodyBottom = min(low, high + ((low - high) * .78).toInt())
            val confidence = ((count.toDouble() / max(1, roi.height) * 2.4) +
                ((r - l + 1).toDouble() / max(1, width) * 2.0)).coerceIn(0.0, 1.0)
            result.add(DetectedCandle(roi.left + l, roi.left + r, high, low, bodyTop, bodyBottom,
                bullish = upCount >= downCount && upCount > 0, confidence = confidence))
        }
        return result.takeLast(80)
    }

    private fun mergeNarrowGaps(groups: List<IntArray>, width: Int): List<IntArray> {
        if (groups.isEmpty()) return groups
        val out = ArrayList<IntArray>(); var current = groups.first().copyOf()
        for (next in groups.drop(1)) {
            if (next[0] - current[1] <= max(3, width / 120)) current[1] = next[1]
            else { out.add(current); current = next.copyOf() }
        }
        out.add(current); return out
    }
}
