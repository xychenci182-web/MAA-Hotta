package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** Recognition for opening the guild, donating and claiming weekly rewards. */
object GuildScreenDetector {
    private val menuGuildRegion = SearchRegion(0.62f, 0.10f, 0.82f, 0.38f)
    private val dailyTabRegion = SearchRegion(0.46f, 0.82f, 0.72f, 1f)
    private val donateNowRegion = SearchRegion(0.70f, 0.62f, 1f, 0.88f)
    private val donateZeroRegion = SearchRegion(0.20f, 0.58f, 0.52f, 0.84f)
    private val confirmTextRegion = SearchRegion(0.30f, 0.32f, 0.70f, 0.55f)
    private val confirmButtonRegion = SearchRegion(0.52f, 0.43f, 0.80f, 0.66f)
    private val infoTabRegion = SearchRegion(0.02f, 0.82f, 0.25f, 1f)
    private val rewardsRowRegion = SearchRegion(0.52f, 0.05f, 1f, 0.31f)
    private val weeklyOpenRegion = SearchRegion(0.76f, 0.48f, 0.97f, 0.84f)
    private val weeklyRewardPopupRegion = SearchRegion(0.10f, 0.25f, 0.52f, 0.49f)

    private val rewardDotX = floatArrayOf(0.623f, 0.689f, 0.755f, 0.822f, 0.889f, 0.957f)

    fun findHudMenu(screen: Bitmap, template: HudTemplates?): MatchResult? =
        GameScreenDetector.findHudMenu(screen, template, exclusions = HudExclusions.GUILD)

    fun findMenuGuild(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, menuGuildRegion, 0.54f)

    fun findPageTitle(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, SearchRegion(0.08f, 0.01f, 0.23f, 0.10f), 0.80f)

    fun findDailyTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, dailyTabRegion, 0.55f)

    fun findDonateNow(screen: Bitmap, template: Bitmap?): MatchResult? =
        if (isDailyTabSelected(screen)) bestMatch(screen, template, donateNowRegion, 0.57f) else null

    fun isDailyTabSelected(screen: Bitmap): Boolean = selectedTab(screen, 0.53f, 0.65f)

    fun isInfoTabSelected(screen: Bitmap): Boolean = selectedTab(screen, 0.063f, 0.178f)

    /** The selected footer tab has a dark panel; unselected tabs have a pale panel. */
    private fun selectedTab(screen: Bitmap, left: Float, right: Float): Boolean {
        var dark = 0
        var count = 0
        val step = (screen.height / 360).coerceAtLeast(1)
        for (y in (screen.height * 0.923f).toInt() until (screen.height * 0.965f).toInt() step step) {
            for (x in (screen.width * left).toInt() until (screen.width * right).toInt() step step) {
                val colour = screen.getPixel(x, y)
                if (maxOf(Color.red(colour), Color.green(colour), Color.blue(colour)) < 100) dark++
                count++
            }
        }
        return count > 0 && dark.toFloat() / count > 0.30f
    }

    /**
     * Distinguish the completed 0/1 state from the available 1/1 state.
     *
     * Both rows share almost all of their text and background, so comparing only
     * the 0/1 template can produce a strong false match on 1/1. Locate the row
     * with both narrow templates, then use the red/green count colour as the
     * primary decision. Template scores are only a conservative fallback when
     * colour is unclear because of animation or compression.
     */
    fun findDonateZero(
        screen: Bitmap,
        zeroTemplate: Bitmap?,
        oneTemplate: Bitmap?,
    ): MatchResult? {
        if (!isDailyTabSelected(screen)) return null
        val zero = bestMatch(screen, zeroTemplate, donateZeroRegion, 0.48f)
        val one = bestMatch(screen, oneTemplate, donateZeroRegion, 0.48f)
        val row = listOfNotNull(zero, one).maxByOrNull { it.score } ?: return null

        return when (donateCountColour(screen, row.point)) {
            DonateCountColour.RED -> row
            DonateCountColour.GREEN -> null
            DonateCountColour.UNKNOWN -> zero?.takeIf {
                it.score >= 0.62f && it.score >= (one?.score ?: 0f) + 0.035f
            }
        }
    }

    fun findConfirmText(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, confirmTextRegion, 0.57f)

    fun findConfirmButton(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, confirmButtonRegion, 0.57f)

    fun findInfoTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, infoTabRegion, 0.55f)

    fun findRewardsRow(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, rewardsRowRegion, 0.50f)

    fun findWeeklyOpen(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, weeklyOpenRegion, 0.55f)

    fun findWeeklyClaimed(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, weeklyOpenRegion, 0.78f)

    fun findDonationTab(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, SearchRegion(0.03f, 0.10f, 0.19f, 0.24f), 0.78f)

    fun findWeeklyRewardPopup(screen: Bitmap, template: Bitmap?): MatchResult? =
        bestMatch(screen, template, weeklyRewardPopupRegion, 0.54f)

    fun findRewardRedDot(screen: Bitmap, index: Int): MatchResult? {
        if (index !in rewardDotX.indices) return null
        val centerX = (screen.width * rewardDotX[index]).roundToInt()
        val centerY = (screen.height * 0.164f).roundToInt()
        val radiusX = (screen.width * 0.015f).roundToInt().coerceAtLeast(5)
        val radiusY = (screen.height * 0.026f).roundToInt().coerceAtLeast(5)
        val left = (centerX - radiusX).coerceAtLeast(0)
        val right = (centerX + radiusX).coerceAtMost(screen.width - 1)
        val top = (centerY - radiusY).coerceAtLeast(0)
        val bottom = (centerY + radiusY).coerceAtMost(screen.height - 1)

        var hits = 0
        var sumX = 0L
        var sumY = 0L
        for (y in top..bottom) {
            for (x in left..right) {
                val color = screen.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (r > 170 && g < 115 && b < 115 && r > g + 65 && r > b + 50) {
                    hits++
                    sumX += x
                    sumY += y
                }
            }
        }
        return if (hits >= 30) {
            MatchResult(Point((sumX / hits).toInt(), (sumY / hits).toInt()), hits / 100f)
        } else {
            null
        }
    }

    fun rewardRedDots(screen: Bitmap): BooleanArray =
        BooleanArray(rewardDotX.size) { index -> findRewardRedDot(screen, index) != null }

    private enum class DonateCountColour { RED, GREEN, UNKNOWN }

    private fun donateCountColour(screen: Bitmap, rowCenter: Point): DonateCountColour {
        // The count starts just to the right of the row centre. Keep this box
        // short enough to exclude the dark plus button at the far right.
        val left = (rowCenter.x + screen.height * 0.018f).roundToInt().coerceAtLeast(0)
        val right = (rowCenter.x + screen.height * 0.098f).roundToInt()
            .coerceAtMost(screen.width - 1)
        val top = (rowCenter.y - screen.height * 0.032f).roundToInt().coerceAtLeast(0)
        val bottom = (rowCenter.y + screen.height * 0.032f).roundToInt()
            .coerceAtMost(screen.height - 1)
        if (left > right || top > bottom) return DonateCountColour.UNKNOWN

        var red = 0
        var green = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val colour = screen.getPixel(x, y)
                val r = Color.red(colour)
                val g = Color.green(colour)
                val b = Color.blue(colour)
                if (r >= 135 && r >= g + 25 && r >= b + 20) red++
                if (g >= 100 && g >= r + 20 && g >= b + 5) green++
            }
        }

        val minimumPixels = ((right - left + 1) * (bottom - top + 1) / 180)
            .coerceAtLeast(8)
        return when {
            red >= minimumPixels && red >= green * 3 / 2 -> DonateCountColour.RED
            green >= minimumPixels && green >= red * 3 / 2 -> DonateCountColour.GREEN
            else -> DonateCountColour.UNKNOWN
        }
    }

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
