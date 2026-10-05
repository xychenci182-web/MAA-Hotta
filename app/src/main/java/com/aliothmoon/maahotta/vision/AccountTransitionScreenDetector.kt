package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Recognition used after one account finishes and before the next account starts. */
object AccountTransitionScreenDetector {
    private val settingsMenuRegion = SearchRegion(0.75f, 0.45f, 0.94f, 0.75f)
    private val userCenterRegion = SearchRegion(0.55f, 0.08f, 0.75f, 0.34f)

    fun findHudMenu(screen: Bitmap, template: HudTemplates?): MatchResult? =
        GameScreenDetector.findHudMenu(screen, template, exclusions = HudExclusions.LOGIN)

    fun findSettingsMenu(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, settingsMenuRegion, 0.54f)

    fun findUserCenter(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, userCenterRegion, 0.56f)

    private fun bestMatch(
        screen: Bitmap,
        template: Bitmap?,
        region: SearchRegion,
        threshold: Float,
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
                    step = 2,
                    region = region,
                    referenceHeight = 596,
                )
                if (match != null && match.score > (best?.score ?: -1f)) best = match
                if (match != null && match.score >= maxOf(threshold, 0.92f)) return match
            } finally {
                if (candidate !== template) candidate.recycle()
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
