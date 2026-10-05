package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Recognition for the HUD menu, Social page, Mail page and mail reward popup. */
object MailScreenDetector {
    private val socialMenuRegion = SearchRegion(0.58f, 0.24f, 0.77f, 0.52f)
    private val mailTabRegion = SearchRegion(0.03f, 0.28f, 0.18f, 0.67f)
    private val claimAllRegion = SearchRegion(0.66f, 0.78f, 0.89f, 0.98f)
    private val rewardPopupRegion = SearchRegion(0.08f, 0.25f, 0.48f, 0.48f)

    fun findHudMenu(screen: Bitmap, template: HudTemplates?): MatchResult? =
        GameScreenDetector.findHudMenu(screen, template, exclusions = HudExclusions.NONE)

    fun findSocialMenu(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, socialMenuRegion, 0.52f)

    fun findSocialPageTitle(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, SearchRegion(0.08f, 0.01f, 0.23f, 0.10f), 0.80f)

    fun findMailTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, mailTabRegion, 0.55f)

    fun findMailSelected(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, mailTabRegion, 0.55f)

    fun findClaimAll(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, claimAllRegion, 0.58f)

    fun findRewardPopup(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, rewardPopupRegion, 0.56f)

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
