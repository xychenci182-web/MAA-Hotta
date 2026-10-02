package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** The seven supply cards keep their layout while their rewards rotate. */
object SupplyScreenDetector {
    private const val REFERENCE_HEIGHT = 525f

    private fun centers(screen: Bitmap, rightAnchored: Boolean): List<Int> =
        (0 until 7).map { day ->
            if (rightAnchored) {
                (screen.width - (212f + (6 - day) * 80.5f) *
                    screen.height / REFERENCE_HEIGHT).roundToInt()
            } else {
                ((240f + day * 80.5f) * screen.width / 936f).roundToInt()
            }
        }

    private fun headerHits(screen: Bitmap, centers: List<Int>): Int {
        val y = (249 * screen.height / REFERENCE_HEIGHT).roundToInt()
        return centers.count { x ->
            if (x !in 0 until screen.width || y !in 0 until screen.height) return@count false
            val color = screen.getPixel(x, y)
            Color.red(color) < 115 && Color.green(color) < 115 && Color.blue(color) < 115
        }
    }

    private fun supplyCenters(screen: Bitmap): List<Int>? {
        if (screen.width < 320 || screen.height < 240) return null
        if (!WelfareNavigationDetector.hasBottomNavigation(screen)) return null
        val right = centers(screen, true)
        val proportional = centers(screen, false)
        val rightHits = headerHits(screen, right)
        val proportionalHits = headerHits(screen, proportional)
        if (maxOf(rightHits, proportionalHits) < 6) return null
        return if (rightHits >= proportionalHits) right else proportional
    }

    fun isSupplyPage(screen: Bitmap): Boolean = supplyCenters(screen) != null

    private fun ratio(screen: Bitmap, center: Int, test: (Int) -> Boolean): Float {
        val scale = screen.height / REFERENCE_HEIGHT
        val radius = (20 * scale).roundToInt().coerceAtLeast(2)
        val step = (screen.height / 350f).roundToInt().coerceAtLeast(1)
        var hits = 0
        var sampled = 0
        for (y in (268 * scale).roundToInt() until (288 * scale).roundToInt() step step) {
            if (y !in 0 until screen.height) continue
            for (x in (center - radius) until (center + radius) step step) {
                if (x !in 0 until screen.width) continue
                sampled++
                if (test(screen.getPixel(x, y))) hits++
            }
        }
        return if (sampled == 0) 0f else hits.toFloat() / sampled
    }

    private fun isYellow(color: Int): Boolean =
        Color.red(color) > 205 && Color.green(color) > 165 && Color.blue(color) < 205 &&
            Color.red(color) - Color.blue(color) > 55

    private fun isClaimedGray(color: Int): Boolean =
        Color.red(color) in 70..150 && Color.green(color) in 70..150 &&
            Color.blue(color) in 70..155

    fun findClaimable(screen: Bitmap): MatchResult? {
        val centers = supplyCenters(screen) ?: return null
        val center = centers.maxByOrNull { ratio(screen, it, ::isYellow) } ?: return null
        val score = ratio(screen, center, ::isYellow)
        if (score < 0.55f) return null
        return MatchResult(Point(center, (425 * screen.height / REFERENCE_HEIGHT).roundToInt()), score)
    }

    fun isClaimed(screen: Bitmap, xRatio: Float): Boolean {
        if (supplyCenters(screen) == null) return false
        val center = (screen.width * xRatio).roundToInt()
        return ratio(screen, center, ::isYellow) < 0.10f &&
            ratio(screen, center, ::isClaimedGray) > 0.70f
    }

    fun lastClaimedDay(screen: Bitmap): Int? {
        val days = supplyCenters(screen) ?: return null
        return days.mapIndexedNotNull { index, center ->
            if (ratio(screen, center, ::isClaimedGray) > 0.70f) index + 1 else null
        }.maxOrNull()
    }

    fun dayNumberAt(screenWidth: Int, screenHeight: Int, x: Int): Int? {
        if (screenWidth < 320 || screenHeight < 240) return null
        val scale = screenHeight / REFERENCE_HEIGHT
        val candidates = listOf(
            (0 until 7).map { day ->
                (screenWidth - (212f + (6 - day) * 80.5f) * scale).roundToInt()
            },
            (0 until 7).map { day ->
                ((240f + day * 80.5f) * screenWidth / 936f).roundToInt()
            },
        )
        val nearest = candidates.flatten().mapIndexed { index, center ->
            (index % 7 + 1) to kotlin.math.abs(center - x)
        }.minByOrNull { it.second } ?: return null
        return nearest.first.takeIf { nearest.second <= (45 * scale).roundToInt() }
    }

    fun hasClaimedDay(screen: Bitmap): Boolean =
        supplyCenters(screen)?.any { ratio(screen, it, ::isClaimedGray) > 0.70f } == true

    private fun cumulativeCenter(screen: Bitmap): Int =
        (screen.width - 100 * screen.height / REFERENCE_HEIGHT).roundToInt()

    fun findCumulativeClaimable(screen: Bitmap): MatchResult? {
        if (supplyCenters(screen) == null) return null
        val scale = screen.height / REFERENCE_HEIGHT
        val left = (screen.width - 160 * scale).roundToInt().coerceAtLeast(0)
        val right = (screen.width - 30 * scale).roundToInt().coerceAtMost(screen.width)
        // Like the day cards, the cumulative claim control sits along the bottom.
        val top = (400 * scale).roundToInt().coerceAtLeast(0)
        val bottom = (445 * scale).roundToInt().coerceAtMost(screen.height)
        val step = (screen.height / 350f).roundToInt().coerceAtLeast(1)
        var yellow = 0
        var sampled = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                sampled++
                if (isYellow(screen.getPixel(x, y))) yellow++
            }
        }
        val score = if (sampled == 0) 0f else yellow.toFloat() / sampled
        if (score < 0.07f) return null
        return MatchResult(
            Point(cumulativeCenter(screen), (425 * scale).roundToInt()),
            score,
        )
    }

    fun isCumulativeClaimed(screen: Bitmap): Boolean {
        if (supplyCenters(screen) == null) return false
        val x = cumulativeCenter(screen)
        // Claiming the cumulative chest applies the same dark completed overlay
        // used by the checked day cards. Sample the overlay instead of trusting
        // one pixel from the reward image.
        return ratio(screen, x, ::isClaimedGray) > 0.70f &&
            findCumulativeClaimable(screen) == null
    }

    fun isAllDayRewardsClaimed(screen: Bitmap): Boolean {
        val days = supplyCenters(screen) ?: return false
        return days.all {
            ratio(screen, it, ::isYellow) < 0.10f && ratio(screen, it, ::isClaimedGray) > 0.70f
        }
    }

    fun isAllClaimed(screen: Bitmap): Boolean =
        isAllDayRewardsClaimed(screen) && isCumulativeClaimed(screen)
}
