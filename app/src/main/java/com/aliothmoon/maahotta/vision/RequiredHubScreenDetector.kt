package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Recognizes the shared Must-do hub used by MIA Kitchen and Dimension Trials. */
object RequiredHubScreenDetector {
    private val weeklyRegion = SearchRegion(0.02f, 0.10f, 0.13f, 0.27f)
    private val recommendRegion = SearchRegion(0.02f, 0.23f, 0.13f, 0.43f)
    private val leisureRegion = SearchRegion(0.02f, 0.38f, 0.13f, 0.59f)
    private val challengeRegion = SearchRegion(0.02f, 0.53f, 0.13f, 0.73f)
    private val trialsEntryRegion = SearchRegion(0.68f, 0.52f, 0.98f, 0.93f)

    fun findWeeklyTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, weeklyRegion, 596, 0.56f)

    fun findRecommendTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, recommendRegion, 596, 0.56f)

    fun findLeisureTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, leisureRegion, 596, 0.56f)

    fun findChallengeTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, challengeRegion, 596, 0.56f)

    fun findAnyTab(
        screen: Bitmap,
        weekly: Bitmap?,
        recommend: Bitmap?,
        leisure: Bitmap?,
        challenge: Bitmap?,
    ): MatchResult? =
        findWeeklyTab(screen, weekly)
            ?: findRecommendTab(screen, recommend)
            ?: findLeisureTab(screen, leisure)
            ?: findChallengeTab(screen, challenge)

    fun findTrialsEntry(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, trialsEntryRegion, 596, 0.54f)

    private fun bestMatch(
        screen: Bitmap,
        template: Bitmap?,
        region: SearchRegion,
        referenceHeight: Int,
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
