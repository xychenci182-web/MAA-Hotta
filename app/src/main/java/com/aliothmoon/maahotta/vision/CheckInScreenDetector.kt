package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** Finds the highlighted day, the reward overlay, and the claimed check mark. */
object CheckInScreenDetector {
    private const val REFERENCE_HEIGHT = 525f

    private fun dayCenters(screen: Bitmap, rightAnchored: Boolean): List<Int> =
        (0 until 7).map { day ->
            if (rightAnchored) {
                (screen.width - (79 + (6 - day) * 103) * screen.height / REFERENCE_HEIGHT).roundToInt()
            } else {
                ((243 + day * 103) * screen.width / 936f).roundToInt()
            }
        }

    private fun labelScore(screen: Bitmap, centers: List<Int>): Int {
        val scale = screen.height / REFERENCE_HEIGHT
        val y = (330 * scale).roundToInt().coerceIn(0, screen.height - 1)
        return centers.count { center ->
            var dark = 0
            var sampled = 0
            for (dx in -33..-5 step 2) {
                val x = center + (dx * scale).roundToInt()
                if (x !in 0 until screen.width) continue
                val color = screen.getPixel(x, y)
                sampled++
                if (Color.red(color) < 125 && Color.green(color) < 125 &&
                    Color.blue(color) < 125
                ) dark++
            }
            sampled > 0 && dark.toFloat() / sampled > 0.30f
        }
    }

    private fun signInCenters(screen: Bitmap): List<Int>? {
        if (screen.width < 320 || screen.height < 240) return null
        if (!WelfareNavigationDetector.hasBottomNavigation(screen)) return null
        val right = dayCenters(screen, true)
        val proportional = dayCenters(screen, false)
        val rightScore = labelScore(screen, right)
        val proportionalScore = labelScore(screen, proportional)
        if (maxOf(rightScore, proportionalScore) < 5) return null
        return if (rightScore >= proportionalScore) right else proportional
    }

    fun isSignInPage(screen: Bitmap): Boolean = signInCenters(screen) != null

    private fun yellow(color: Int): Boolean =
        Color.red(color) > 205 && Color.green(color) > 165 && Color.blue(color) < 205 &&
            Color.red(color) - Color.blue(color) > 55

    private fun yellowRatio(screen: Bitmap, center: Int, top: Int, bottom: Int): Float {
        val scale = screen.height / REFERENCE_HEIGHT
        val radius = (30 * scale).roundToInt().coerceAtLeast(2)
        val step = (screen.height / 350f).roundToInt().coerceAtLeast(1)
        var yellowCount = 0
        var sampled = 0
        for (y in (top * scale).roundToInt().coerceAtLeast(0) until
            (bottom * scale).roundToInt().coerceAtMost(screen.height) step step
        ) {
            for (x in (center - radius).coerceAtLeast(0) until
                (center + radius).coerceAtMost(screen.width) step step
            ) {
                sampled++
                if (yellow(screen.getPixel(x, y))) yellowCount++
            }
        }
        return if (sampled == 0) 0f else yellowCount.toFloat() / sampled
    }

    fun findClaimable(screen: Bitmap): MatchResult? {
        val centers = signInCenters(screen) ?: return null
        var bestCenter: Int? = null
        var bestScore = 0f
        for (center in centers) {
            val top = yellowRatio(screen, center, 320, 354)
            val body = yellowRatio(screen, center, 365, 445)
            if (top < 0.18f || body < 0.20f) continue
            val score = minOf(top, body)
            if (score > bestScore) {
                bestScore = score
                bestCenter = center
            }
        }
        val center = bestCenter ?: return null
        return MatchResult(Point(center, (390 * screen.height / REFERENCE_HEIGHT).roundToInt()), bestScore)
    }

    /** A weaker yellow signal blocks an "already signed" conclusion while the page settles. */
    fun hasPossibleClaimable(screen: Bitmap): Boolean =
        signInCenters(screen)?.any { center ->
            yellowRatio(screen, center, 320, 354) >= 0.08f &&
                yellowRatio(screen, center, 365, 445) >= 0.10f
        } == true

    private fun lightRowRatio(screen: Bitmap, yRatio: Float): Float {
        val y = (screen.height * yRatio).roundToInt().coerceIn(0, screen.height - 1)
        val step = (screen.width / 320f).roundToInt().coerceAtLeast(1)
        var light = 0
        var sampled = 0
        for (x in 0 until screen.width step step) {
            val color = screen.getPixel(x, y)
            sampled++
            if (Color.red(color) > 205 && Color.green(color) > 205 && Color.blue(color) > 205) {
                light++
            }
        }
        return light.toFloat() / sampled
    }

    fun isRewardPopup(screen: Bitmap): Boolean =
        lightRowRatio(screen, 0.34f) > 0.70f &&
            lightRowRatio(screen, 0.46f) > 0.70f &&
            lightRowRatio(screen, 0.40f) < 0.45f

    private fun checkStrokeRatio(
        screen: Bitmap,
        center: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Float {
        val scale = screen.height / REFERENCE_HEIGHT
        val x1 = (center + left * scale).roundToInt().coerceIn(0, screen.width)
        val x2 = (center + right * scale).roundToInt().coerceIn(0, screen.width)
        val y1 = (top * scale).roundToInt().coerceIn(0, screen.height)
        val y2 = (bottom * scale).roundToInt().coerceIn(0, screen.height)
        val step = (screen.height / 350f).roundToInt().coerceAtLeast(1)
        var brightCyan = 0
        var sampled = 0
        for (y in y1 until y2 step step) {
            for (x in x1 until x2 step step) {
                val color = screen.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (red > 105 && green > 210 && blue > 190 && green - red > 65) {
                    brightCyan++
                }
                sampled++
            }
        }
        return if (sampled == 0) 0f else brightCyan.toFloat() / sampled
    }

    fun hasClaimCheck(screen: Bitmap, xRatio: Float): Boolean {
        val center = (screen.width * xRatio).roundToInt()
        // Check-mark strokes cover two broad regions. Sampling an area avoids
        // missing the mark when the UI shifts a few pixels or the image glows.
        val corner = checkStrokeRatio(screen, center, -8, 10, 395, 414)
        val risingArm = checkStrokeRatio(screen, center, 10, 34, 378, 401)
        return corner >= 0.28f && risingArm >= 0.16f
    }

    fun hasAnyClaimCheck(screen: Bitmap): Boolean =
        signInCenters(screen)?.any { center ->
            hasClaimCheck(screen, center.toFloat() / screen.width)
        } == true

    fun hasAllClaimChecks(screen: Bitmap): Boolean =
        signInCenters(screen)?.all { center ->
            hasClaimCheck(screen, center.toFloat() / screen.width)
        } == true

    /** All seven labels and the future unclaimed card surfaces must be visible. */
    private fun completeDayState(screen: Bitmap): Pair<List<Int>, List<Boolean>>? {
        val centers = signInCenters(screen) ?: return null
        if (labelScore(screen, centers) != 7) return null
        val checks = centers.map { hasClaimCheck(screen, it.toFloat() / screen.width) }
        val lastClaimed = checks.indexOfLast { it } + 1
        if (centers.drop(lastClaimed).any { !hasUnclaimedCardSurface(screen, it) }) return null
        return centers to checks
    }

    private fun hasUnclaimedCardSurface(screen: Bitmap, center: Int): Boolean {
        val scale = screen.height / REFERENCE_HEIGHT
        val step = (screen.height / 350f).roundToInt().coerceAtLeast(1)
        for ((left, right) in listOf(-43 to -34, 34 to 43)) {
            val x1 = (center + left * scale).roundToInt()
            val x2 = (center + right * scale).roundToInt()
            val y1 = (355 * scale).roundToInt()
            val y2 = (445 * scale).roundToInt()
            if (x1 < 0 || x2 > screen.width || y1 < 0 || y2 > screen.height) return false
            var visible = 0
            var sampled = 0
            for (y in y1 until y2 step step) for (x in x1 until x2 step step) {
                val color = screen.getPixel(x, y)
                if ((Color.red(color) > 205 && Color.green(color) > 205 && Color.blue(color) > 205) ||
                    yellow(color)
                ) visible++
                sampled++
            }
            if (sampled == 0 || visible.toFloat() / sampled < 0.55f) return false
        }
        return true
    }

    /**
     * Number of visible checks. Prefer [lastClaimedDay] for routing: the next target is always
     * the card after the rightmost check (no checks → D1). When D7 is checked, today is done;
     * do not wrap to D1 until the board refreshes on a later day.
     */
    fun claimedDayCount(screen: Bitmap): Int? = completeDayState(screen)?.second?.count { it }

    /** Most recently claimed ordinal (0..7); null means the card layout is not fully confirmed. */
    fun lastClaimedDay(screen: Bitmap): Int? = completeDayState(screen)?.second?.indexOfLast { it }?.plus(1)

    /** Zero-based next card for today. Null when D7 is already checked. */
    fun nextDayIndex(screen: Bitmap): Int? = lastClaimedDay(screen)?.takeIf { it < 7 }

    /**
     * Only the card after the rightmost check may be submitted.
     * No checks → D1; D4 checked → D5; D7 checked → no target (caller treats as already done).
     */
    fun nextClaimable(screen: Bitmap): MatchResult? {
        val (centers, checks) = completeDayState(screen) ?: return null
        val index = checks.indexOfLast { it } + 1
        if (index >= 7) return null
        val center = centers[index]
        val top = yellowRatio(screen, center, 320, 354)
        val body = yellowRatio(screen, center, 365, 445)
        if (top < 0.18f || body < 0.20f) return null
        return MatchResult(Point(center, (390 * screen.height / REFERENCE_HEIGHT).roundToInt()), minOf(top, body))
    }

    /** A weak yellow signal on today's next card blocks "already claimed" while the UI settles. */
    fun hasPossibleNextClaimable(screen: Bitmap): Boolean {
        val (centers, checks) = completeDayState(screen) ?: return false
        val index = checks.indexOfLast { it } + 1
        if (index >= 7) return false
        val center = centers[index]
        return yellowRatio(screen, center, 320, 354) >= 0.08f &&
            yellowRatio(screen, center, 365, 445) >= 0.10f
    }
}
