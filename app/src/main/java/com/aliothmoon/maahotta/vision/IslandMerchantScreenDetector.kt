package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** Recognition for the Leisure page and the Artificial Island Building card. */
object IslandMerchantScreenDetector {
    private val leisureAnchorRegion = SearchRegion(0.12f, 0.10f, 0.44f, 0.40f)
    // Card order varies by account: search the whole visible card grid.
    private val islandCardRegion = SearchRegion(0.12f, 0.12f, 0.96f, 1f)
    private val islandTitleRegion = SearchRegion(0.05f, 0.12f, 0.37f, 0.27f)
    // The merchant portrait is on the right side of the island page.
    private val merchantPortraitRegion = SearchRegion(0.84f, 0.43f, 1f, 0.68f)

    fun findLeisureAnchor(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, leisureAnchorRegion, 0.55f)

    fun findIslandCard(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, islandCardRegion, 0.55f, step = 4)

    fun findIslandPage(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, islandTitleRegion, 0.68f, referenceHeight = 528)

    fun findMerchantPortrait(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, merchantPortraitRegion, 0.70f, referenceHeight = 528)

    fun findIslandRedDot(
        screen: Bitmap,
        cardCenter: Point,
    ): MatchResult? {
        val scale = screen.height / 596f
        val cardRight = cardCenter.x + 227f * scale / 2f
        val cardTop = cardCenter.y - 109f * scale / 2f
        val left = (cardRight - 24f * scale).roundToInt().coerceIn(0, screen.width - 1)
        val top = (cardTop + 8f * scale).roundToInt().coerceIn(0, screen.height - 1)
        val right = cardRight.roundToInt().coerceIn(left + 1, screen.width)
        val bottom = (top + 26f * scale).roundToInt().coerceIn(top + 1, screen.height)

        var redPixels = 0
        var minRedX = right
        var minRedY = bottom
        var maxRedX = left
        var maxRedY = top
        for (y in top until bottom) {
            for (x in left until right) {
                val color = screen.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (red > 170 && green < 130 && blue < 130 &&
                    red - green > 80 && red - blue > 80
                ) {
                    redPixels++
                    minRedX = minOf(minRedX, x)
                    minRedY = minOf(minRedY, y)
                    maxRedX = maxOf(maxRedX, x)
                    maxRedY = maxOf(maxRedY, y)
                }
            }
        }

        val minimumPixels = (18f * scale * scale).roundToInt().coerceAtLeast(8)
        val minimumWidth = (6f * scale).roundToInt().coerceAtLeast(4)
        val minimumHeight = (7f * scale).roundToInt().coerceAtLeast(5)
        val redWidth = if (redPixels == 0) 0 else maxRedX - minRedX + 1
        val redHeight = if (redPixels == 0) 0 else maxRedY - minRedY + 1
        if (redPixels < minimumPixels || redWidth < minimumWidth || redHeight < minimumHeight) {
            return null
        }

        val score = (redPixels.toFloat() / ((right - left) * (bottom - top)))
            .coerceIn(0f, 1f)
        return MatchResult(Point((left + right) / 2, (top + bottom) / 2), score)
    }

    private fun bestMatch(
        screen: Bitmap,
        template: Bitmap?,
        region: SearchRegion,
        threshold: Float,
        step: Int = 2,
        referenceHeight: Int = 596,
    ): MatchResult? {
        if (template == null) return null
        val scales = floatArrayOf(1f, 0.90f, 1.12f, 0.78f, 1.26f)
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
                    step = step,
                    region = region,
                    referenceHeight = referenceHeight,
                )
                if (match != null && match.score > (best?.score ?: -1f)) best = match
                if (scale == 1f && match != null && match.score >= 0.78f) return match
            } finally {
                if (candidate !== template) candidate.recycle()
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
