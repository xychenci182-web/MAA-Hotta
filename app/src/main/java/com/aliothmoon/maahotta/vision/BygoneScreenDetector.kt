package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Recognition for the Bygone Phantasm entry, floor page and exit flow. */
object BygoneScreenDetector {
    private val entryRegion = SearchRegion(0.12f, 0.11f, 0.45f, 0.43f)
    private val diveNextRegion = SearchRegion(0.76f, 0.72f, 1f, 0.98f)
    private val skipRegion = SearchRegion(0.84f, 0f, 1f, 0.13f)
    private val exitIconRegion = SearchRegion(0.10f, 0f, 0.21f, 0.16f)
    private val exitDialogRegion = SearchRegion(0.31f, 0.34f, 0.70f, 0.53f)
    private val confirmRegion = SearchRegion(0.52f, 0.44f, 0.80f, 0.66f)

    fun findEntry(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, entryRegion, 0.55f)

    fun findDiveNext(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, diveNextRegion, 0.57f)

    fun findSkip(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, skipRegion, 0.62f)

    fun findExitIcon(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, exitIconRegion, 0.55f)

    fun findExitDialog(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, exitDialogRegion, 0.57f)

    fun findExitConfirm(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, confirmRegion, 0.57f)

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
