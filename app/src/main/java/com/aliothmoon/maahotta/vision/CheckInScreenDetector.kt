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
}
