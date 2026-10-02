package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** Recognizes the pale bottom navigation shared by all Welfare pages. */
object WelfareNavigationDetector {
    /** The pale bottom row alone is not a page identity; other game pages also have one. */
    fun hasPageTitle(screen: Bitmap, template: Bitmap?): Boolean = template != null &&
        TemplateMatcher.match(screen, template, threshold = 0.82f, step = 2,
            region = SearchRegion(0.03f, 0f, 0.28f, 0.13f), referenceHeight = 528) != null

    fun hasBottomNavigation(screen: Bitmap): Boolean {
        if (screen.width < 320 || screen.height < 240) return false
        val pixels = IntArray(screen.width * screen.height)
        screen.getPixels(pixels, 0, screen.width, 0, 0, screen.width, screen.height)
        val left = (screen.width * 0.02f).roundToInt()
        val right = (screen.width * 0.98f).roundToInt()
        val top = (screen.height * 0.90f).roundToInt()
        val bottom = (screen.height * 0.995f).roundToInt().coerceAtMost(screen.height)
        val step = (screen.height / 300f).roundToInt().coerceAtLeast(1)
        var light = 0
        var neutral = 0
        var count = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = pixels[y * screen.width + x]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val average = (red + green + blue) / 3
                val spread = maxOf(red, green, blue) - minOf(red, green, blue)
                if (average > 150) light++
                if (average > 100 && spread < 35) neutral++
                count++
            }
        }
        if (count == 0) return false
        return light.toFloat() / count > 0.55f && neutral.toFloat() / count > 0.30f
    }

    fun hasWelfareTab(screen: Bitmap): Boolean =
        hasBottomNavigation(screen) && hasTabSurface(screen, 0.45f, 0.61f)

    fun hasSpecialActionTab(screen: Bitmap): Boolean =
        hasBottomNavigation(screen) && hasTabSurface(screen, 0.16f, 0.31f)

    /** The same tab turns dark after selecting Special Action. */
    fun hasSelectedSpecialActionTab(screen: Bitmap): Boolean {
        if (!hasBottomNavigation(screen)) return false
        val left = (screen.width * 0.186f).roundToInt()
        val right = (screen.width * 0.328f).roundToInt()
        val top = (screen.height * 0.914f).roundToInt()
        val bottom = (screen.height * 0.975f).roundToInt()
        val step = (screen.height / 180f).roundToInt().coerceAtLeast(1)
        var dark = 0
        var count = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = screen.getPixel(x, y)
                if ((Color.red(color) + Color.green(color) + Color.blue(color)) / 3 < 85) dark++
                count++
            }
        }
        return count > 0 && dark.toFloat() / count > 0.25f
    }

    private fun hasTabSurface(screen: Bitmap, leftRatio: Float, rightRatio: Float): Boolean {
        val left = (screen.width * leftRatio).roundToInt()
        val right = (screen.width * rightRatio).roundToInt()
        val top = (screen.height * 0.90f).roundToInt()
        val bottom = (screen.height * 0.995f).roundToInt().coerceAtMost(screen.height)
        val step = (screen.height / 300f).roundToInt().coerceAtLeast(1)
        var light = 0
        var neutral = 0
        var count = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = screen.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val average = (red + green + blue) / 3
                val spread = maxOf(red, green, blue) - minOf(red, green, blue)
                if (average > 130) light++
                if (average > 80 && spread < 45) neutral++
                count++
            }
        }
        if (count == 0) return false
        return light.toFloat() / count > 0.50f && neutral.toFloat() / count > 0.45f
    }
}
