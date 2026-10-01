package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import com.aliothmoon.maahotta.data.TrialType
import kotlin.math.roundToInt

/** Recognition for the Dimension Trials dialog, proxy battle popup and result. */
object TrialsScreenDetector {
    private val logoRegion = SearchRegion(0.14f, 0.04f, 0.70f, 0.48f)
    private val participateRegion = SearchRegion(0.55f, 0.58f, 0.90f, 0.84f)
    private val proxyRegion = SearchRegion(0.16f, 0.44f, 0.48f, 0.72f)
    private val resultRegion = SearchRegion(0.55f, 0.20f, 0.98f, 0.50f)
    private val closeRegion = SearchRegion(0.82f, 0.08f, 0.98f, 0.34f)
    private val vitalityInsufficientRegion = SearchRegion(0.55f, 0.56f, 0.93f, 0.76f)

    fun findDialogLogo(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, logoRegion, 0.54f)

    fun findParticipate(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, participateRegion, 0.57f)

    fun findProxyBattle(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, proxyRegion, 0.57f)

    fun findResultSuccess(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, resultRegion, 0.57f)

    fun findClose(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, closeRegion, 0.55f)

    fun findVitalityInsufficient(screen: Bitmap, template: Bitmap?): MatchResult? {
        if (!hasRedVitalityText(screen)) return null
        return bestMatch(screen, template, vitalityInsufficientRegion, 0.55f)
    }

    /** The active training tab has a solid red background instead of blue-grey. */
    fun findSelectedType(screen: Bitmap, type: TrialType): MatchResult? {
        val centerX = when (type) {
            TrialType.WEAPON -> 0.232f
            TrialType.MATRIX -> 0.344f
            TrialType.GOLD -> 0.452f
        }
        val left = (screen.width * (centerX - 0.048f)).toInt().coerceAtLeast(0)
        val right = (screen.width * (centerX + 0.048f)).toInt().coerceAtMost(screen.width)
        val top = (screen.height * 0.425f).toInt().coerceAtLeast(0)
        val bottom = (screen.height * 0.505f).toInt().coerceAtMost(screen.height)
        if (left >= right || top >= bottom) return null

        var red = 0
        var count = 0
        val step = (screen.height / 300f).roundToInt().coerceAtLeast(1)
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = screen.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (r > 120 && r > g + 20 && r > b + 20) red++
                count++
            }
        }
        val ratio = if (count == 0) 0f else red.toFloat() / count
        return if (ratio >= 0.20f) {
            MatchResult(Point((left + right) / 2, (top + bottom) / 2), ratio)
        } else {
            null
        }
    }

    private fun hasRedVitalityText(screen: Bitmap): Boolean {
        val left = (screen.width * vitalityInsufficientRegion.left).toInt().coerceAtLeast(0)
        val right = (screen.width * vitalityInsufficientRegion.right).toInt().coerceAtMost(screen.width)
        val top = (screen.height * vitalityInsufficientRegion.top).toInt().coerceAtLeast(0)
        val bottom = (screen.height * vitalityInsufficientRegion.bottom).toInt().coerceAtMost(screen.height)
        if (left >= right || top >= bottom) return false

        val step = (screen.height / 360f).roundToInt().coerceAtLeast(1)
        var red = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val colour = screen.getPixel(x, y)
                val r = Color.red(colour)
                val g = Color.green(colour)
                val b = Color.blue(colour)
                if (r >= 175 && r >= g + 45 && r >= b + 35) {
                    red++
                    if (red >= 24) return true
                }
            }
        }
        return false
    }

    private fun bestMatch(
        screen: Bitmap,
        template: Bitmap?,
        region: SearchRegion,
        threshold: Float,
    ): MatchResult? {
        if (template == null) return null
        val scales = floatArrayOf(0.78f, 0.90f, 1f, 1.12f, 1.26f)
        var best: MatchResult? = null
        for (scale in scales) {
            val candidate = if (scale == 1f) {
                template
            } else {
                Bitmap.createScaledBitmap(
                    template,
                    (template.width * scale).roundToInt().coerceAtLeast(4),
                    (template.height * scale).roundToInt().coerceAtLeast(4),
                    true,
                )
            }
            try {
                val match = TemplateMatcher.match(
                    screen = screen,
                    template = candidate,
                    threshold = -1f,
                    step = 2,
                    region = region,
                    referenceHeight = 596,
                )
                if (match != null && match.score > (best?.score ?: -1f)) best = match
            } finally {
                if (candidate !== template) candidate.recycle()
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
