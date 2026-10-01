package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class MatchResult(val point: Point, val score: Float, val requiresStableFrames: Boolean = false)

/** Fractional bounds of the screenshot in which a template may appear. */
data class SearchRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object TemplateMatcher {
    /** Login templates were captured at 525 pixels high; scale by height for other aspect ratios. */
    const val LOGIN_REFERENCE_HEIGHT = 525

    fun match(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float = 0.82f,
        step: Int = 3,
        region: SearchRegion? = null,
        referenceHeight: Int? = null,
        edgesOnly: Boolean = false,
        smooth: Boolean = false,
    ): MatchResult? {
        val scaled = if (referenceHeight != null && screen.height != referenceHeight) {
            val width = (screen.width.toDouble() * referenceHeight / screen.height)
                .roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(screen, width, referenceHeight, true)
        } else {
            screen
        }
        try {
            val result = matchScaled(scaled, template, threshold, step.coerceAtLeast(1), region, edgesOnly, smooth)
                ?: return null
            return MatchResult(
                Point(
                    (result.point.x.toDouble() * screen.width / scaled.width).roundToInt(),
                    (result.point.y.toDouble() * screen.height / scaled.height).roundToInt(),
                ),
                result.score,
            )
        } finally {
            if (scaled !== screen) scaled.recycle()
        }
    }

    private data class Candidate(val x: Int, val y: Int, val score: Double)

    private fun matchScaled(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
        step: Int,
        region: SearchRegion?,
        edgesOnly: Boolean,
        smooth: Boolean,
    ): MatchResult? {
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0 || tw > screen.width || th > screen.height) return null

        val minX = ((region?.left ?: 0f) * screen.width).toInt().coerceIn(0, screen.width - tw)
        val minY = ((region?.top ?: 0f) * screen.height).toInt().coerceIn(0, screen.height - th)
        val maxX = (((region?.right ?: 1f) * screen.width).toInt() - tw)
            .coerceIn(minX, screen.width - tw)
        val maxY = (((region?.bottom ?: 1f) * screen.height).toInt() - th)
            .coerceIn(minY, screen.height - th)

        val sourceGray = grayscale(screen).let { if (smooth) soften(it, screen.width, screen.height) else it }
        val targetGray = grayscale(template).let { if (smooth) soften(it, tw, th) else it }
        val source = if (edgesOnly) edgeMagnitude(sourceGray, screen.width, screen.height) else sourceGray
        val target = if (edgesOnly) edgeMagnitude(targetGray, tw, th) else targetGray
        val targetAlpha = alphaMask(template)
        val offsets = ArrayList<Int>()
        val values = ArrayList<Float>()
        val dx = (tw / 48).coerceAtLeast(1)
        val dy = (th / 12).coerceAtLeast(1)
        for (ty in 0 until th step dy) {
            for (tx in 0 until tw step dx) {
                if (targetAlpha[ty * tw + tx] <= 32) continue
                offsets += ty * screen.width + tx
                values += target[ty * tw + tx]
            }
        }
        if (values.size < 2) return null
        val sampleCount = values.size.toDouble()
        var sumT = 0.0
        var sumTT = 0.0
        for (value in values) {
            sumT += value
            sumTT += value * value
        }
        val varianceT = sumTT - sumT * sumT / sampleCount
        if (varianceT < 1e-6) return null

        val candidates = ArrayList<Candidate>(6)
        for (y in minY..maxY step step) {
            for (x in minX..maxX step step) {
                val base = y * screen.width + x
                var sumS = 0.0
                var sumSS = 0.0
                var sumST = 0.0
                for (i in offsets.indices) {
                    val sv = source[base + offsets[i]].toDouble()
                    sumS += sv
                    sumSS += sv * sv
                    sumST += sv * values[i]
                }
                val varianceS = sumSS - sumS * sumS / sampleCount
                if (varianceS < 1e-6) continue
                val score = (sumST - sumS * sumT / sampleCount) / sqrt(varianceS * varianceT)
                if (candidates.size == 6 && score <= candidates.last().score) continue
                candidates += Candidate(x, y, score)
                candidates.sortByDescending { it.score }
                if (candidates.size > 6) candidates.removeAt(6)
            }
        }
        if (candidates.isEmpty()) return null

        val fullIndices = targetAlpha.indices.filter { targetAlpha[it] > 32 }
        if (fullIndices.size < 2) return null
        val fullCount = fullIndices.size.toDouble()
        var fullSumT = 0.0
        var fullSumTT = 0.0
        for (index in fullIndices) {
            val value = target[index]
            fullSumT += value
            fullSumTT += value * value
        }
        val fullVarianceT = fullSumTT - fullSumT * fullSumT / fullCount
        if (fullVarianceT < 1e-6) return null
        var best: Candidate? = null
        for (candidate in candidates) {
            for (y in (candidate.y - step).coerceAtLeast(minY)..(candidate.y + step).coerceAtMost(maxY)) {
                for (x in (candidate.x - step).coerceAtLeast(minX)..(candidate.x + step).coerceAtMost(maxX)) {
                    var sumS = 0.0
                    var sumSS = 0.0
                    var sumST = 0.0
                    for (index in fullIndices) {
                        val ty = index / tw
                        val tx = index - ty * tw
                        val sv = source[(y + ty) * screen.width + x + tx].toDouble()
                        sumS += sv
                        sumSS += sv * sv
                        sumST += sv * target[index]
                    }
                    val varianceS = sumSS - sumS * sumS / fullCount
                    if (varianceS < 1e-6) continue
                    val score = (sumST - sumS * fullSumT / fullCount) /
                        sqrt(varianceS * fullVarianceT)
                    if (best == null || score > best.score) best = Candidate(x, y, score)
                }
            }
        }
        val result = best ?: return null
        if (result.score < threshold) return null
        return MatchResult(Point(result.x + tw / 2, result.y + th / 2), result.score.toFloat())
    }

    /** Suppress single-pixel rendering differences in small HUD controls. */
    private fun soften(values: FloatArray, width: Int, height: Int): FloatArray {
        val horizontal = FloatArray(values.size)
        val result = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            horizontal[y * width + x] = (values[y * width + (x - 1).coerceAtLeast(0)] +
                2f * values[y * width + x] + values[y * width + (x + 1).coerceAtMost(width - 1)]) / 4f
        }
        for (y in 0 until height) for (x in 0 until width) {
            result[y * width + x] = (horizontal[(y - 1).coerceAtLeast(0) * width + x] +
                2f * horizontal[y * width + x] + horizontal[(y + 1).coerceAtMost(height - 1) * width + x]) / 4f
        }
        return result
    }

    fun dominantHueNear(bitmap: Bitmap, cx: Int, cy: Int, radius: Int = 12): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val left = (cx - radius).coerceAtLeast(0)
        val top = (cy - radius).coerceAtLeast(0)
        val right = (cx + radius).coerceAtMost(bitmap.width - 1)
        val bottom = (cy + radius).coerceAtMost(bitmap.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
        }
        if (n == 0) return 0
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    fun colorClose(a: Int, b: Int, tol: Int = 40): Boolean {
        return abs(Color.red(a) - Color.red(b)) <= tol &&
            abs(Color.green(a) - Color.green(b)) <= tol &&
            abs(Color.blue(a) - Color.blue(b)) <= tol
    }

    /** Local contrast removes absolute scenery color/brightness from HUD matching.
     * Template alpha still selects only fixed UI contours; source stays untouched. */
    private fun edgeMagnitude(gray: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(gray.size)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val gx = -gray[i - width - 1] + gray[i - width + 1] - 2 * gray[i - 1] +
                2 * gray[i + 1] - gray[i + width - 1] + gray[i + width + 1]
            val gy = -gray[i - width - 1] - 2 * gray[i - width] - gray[i - width + 1] +
                gray[i + width - 1] + 2 * gray[i + width] + gray[i + width + 1]
            out[i] = sqrt(gx * gx + gy * gy)
        }
        return out
    }

    private fun grayscale(src: Bitmap): FloatArray {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i] = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        return out
    }

    private fun alphaMask(src: Bitmap): IntArray {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return IntArray(pixels.size) { Color.alpha(pixels[it]) }
    }
}
