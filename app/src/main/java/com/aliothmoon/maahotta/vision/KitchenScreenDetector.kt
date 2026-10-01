package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** Multi-scale recognition for the Must-do hub and the MIA kitchen page. */
object KitchenScreenDetector {
    private val hubRegion = SearchRegion(0.58f, 0.08f, 0.99f, 0.52f)
    private val tasteRegion = SearchRegion(0.75f, 0.76f, 0.99f, 0.93f)
    private val rewardPopupRegion = SearchRegion(0.08f, 0.25f, 0.48f, 0.48f)

    fun findEntry(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, hubRegion, 596, 0.55f)

    fun findCompleted(screen: Bitmap, template: Bitmap?): MatchResult? =
        findCurrentCompletedMark(screen)
            ?: bestMatch(screen, template, hubRegion, 793, 0.66f)

    /** The current Mi-a card overlays a large turquoise 已完成 ring when tastes are exhausted. */
    private fun findCurrentCompletedMark(screen: Bitmap): MatchResult? {
        val left = (screen.width * 0.77f).roundToInt()
        val right = (screen.width * 0.87f).roundToInt()
        val top = (screen.height * 0.25f).roundToInt()
        val bottom = (screen.height * 0.40f).roundToInt()
        if (right <= left || bottom <= top) return null
        val step = (screen.height / 360).coerceAtLeast(1)
        var turquoise = 0
        var sampled = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = screen.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                sampled++
                if (green > 140 && green > red + 45 && green > blue + 5) turquoise++
            }
        }
        if (sampled == 0 || turquoise.toFloat() / sampled < 0.10f) return null
        return MatchResult(Point((left + right) / 2, (top + bottom) / 2), turquoise.toFloat() / sampled)
    }

    fun findTaste(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, tasteRegion, 596, 0.56f)

    fun findRewardPopup(screen: Bitmap, template: Bitmap?): MatchResult? {
        if (isRewardPopupPanel(screen)) {
            return MatchResult(Point(screen.width / 2, screen.height / 2), 1f)
        }
        return bestMatch(screen, template, rewardPopupRegion, 596, 0.56f)
    }

    private fun isRewardPopupPanel(screen: Bitmap): Boolean =
        lightRowRatio(screen, 0.15f) < 0.30f &&
            lightRowRatio(screen, 0.32f) > 0.75f &&
            lightRowRatio(screen, 0.42f) > 0.65f

    private fun lightRowRatio(screen: Bitmap, yRatio: Float): Float {
        val y = (screen.height * yRatio).roundToInt().coerceIn(0, screen.height - 1)
        val step = (screen.width / 350f).roundToInt().coerceAtLeast(1)
        var light = 0
        var sampled = 0
        for (x in 0 until screen.width step step) {
            val color = screen.getPixel(x, y)
            sampled++
            if (Color.red(color) > 205 && Color.green(color) > 205 &&
                Color.blue(color) > 205
            ) {
                light++
            }
        }
        return if (sampled == 0) 0f else light.toFloat() / sampled
    }

    private fun bestMatch(
        screen: Bitmap,
        template: Bitmap?,
        region: SearchRegion,
        referenceHeight: Int,
        threshold: Float,
    ): MatchResult? {
        if (template == null) return null
        var best: MatchResult? = null
        for (scale in floatArrayOf(0.78f, 0.90f, 1f, 1.12f, 1.26f)) {
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
                    referenceHeight = referenceHeight,
                )
                if (match != null && match.score > (best?.score ?: -1f)) best = match
            } finally {
                if (candidate !== template) candidate.recycle()
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
