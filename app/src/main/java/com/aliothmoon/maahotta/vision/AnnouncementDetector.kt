package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.roundToInt

/** Finds either announcement close-button style: red stroke or dark X. */
object AnnouncementDetector {
    /** Fast page gate before running OCR on the announcement heading. */
    fun hasLayout(screen: Bitmap): Boolean {
        val width = screen.width
        val height = screen.height
        if (width < 320 || height < 240) return false
        val pixels = IntArray(width * height)
        screen.getPixels(pixels, 0, width, 0, 0, width, height)
        return hasLayout(pixels, width, height)
    }

    private fun hasLayout(pixels: IntArray, width: Int, height: Int): Boolean {
        val step = (height / 260f).roundToInt().coerceAtLeast(1)
        fun ratio(left: Float, top: Float, right: Float, bottom: Float, dark: Boolean): Float {
            var hits = 0
            var total = 0
            for (y in (height * top).toInt() until (height * bottom).toInt() step step) {
                for (x in (width * left).toInt() until (width * right).toInt() step step) {
                    val color = pixels[y * width + x]
                    val found = if (dark) {
                        Color.red(color) < 130 && Color.green(color) < 130 && Color.blue(color) < 130
                    } else {
                        Color.red(color) > 190 && Color.green(color) > 190 &&
                            Color.blue(color) > 190 && abs(Color.red(color) - Color.green(color)) < 25
                    }
                    if (found) hits++
                    total++
                }
            }
            return if (total == 0) 0f else hits.toFloat() / total
        }
        val darkLeft = (height * 0.10f / width).coerceIn(0f, 1f)
        val darkRight = (height * 0.50f / width).coerceIn(darkLeft, 1f)
        val articleLeft = (height * 0.54f / width).coerceIn(0f, 1f)
        val articleRight = (1f - height * 0.18f / width).coerceIn(articleLeft, 1f)
        return ratio(darkLeft, 0.13f, darkRight, 0.82f, dark = true) > 0.45f &&
            ratio(articleLeft, 0.18f, articleRight, 0.72f, dark = false) > 0.45f
    }

    fun findClose(screen: Bitmap): MatchResult? {
        val width = screen.width
        val height = screen.height
        if (width < 320 || height < 240) return null

        val pixels = IntArray(width * height)
        screen.getPixels(pixels, 0, width, 0, 0, width, height)
        val scale = height / 1080f
        val whiteOffsets = arrayOf(-27 to 0, 12 to 0, 0 to -16, 0 to 17)
        val darkOffsets = arrayOf(24 to 0, 0 to 24)

        fun at(x: Int, y: Int): Int? =
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] else null

        fun light(x: Int, y: Int): Boolean {
            val color = at(x, y) ?: return false
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return red > 190 && green > 190 && blue > 190 && abs(red - green) < 25
        }

        fun dark(x: Int, y: Int): Boolean {
            val color = at(x, y) ?: return false
            return Color.red(color) < 130 && Color.green(color) < 130 && Color.blue(color) < 130
        }

        fun redStroke(x: Int, y: Int): Boolean {
            val color = at(x, y) ?: return false
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return red > 170 && red - green > 70 && red - blue > 70
        }

        fun closeStroke(x: Int, y: Int): Boolean = dark(x, y) || redStroke(x, y)

        // A close-like symbol also exists in Settings. Only search for an X
        // when the announcement's dark catalogue and light article are both visible.
        if (!hasLayout(pixels, width, height)) return null

        // Current announcements use a dark X centered on a white tile. Detect
        // its four diagonal arms, independent of the page behind the popup.
        val uiScale = height / 525f
        val arm = (7f * uiScale).roundToInt().coerceAtLeast(4)
        val clear = (13f * uiScale).roundToInt().coerceAtLeast(8)
        val darkXStep = (2f * uiScale).roundToInt().coerceAtLeast(1)
        fun findDarkX(left: Int, top: Int, right: Int, bottom: Int): MatchResult? {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height) step darkXStep) {
                for (x in left.coerceAtLeast(0) until right.coerceAtMost(width) step darkXStep) {
                    val diagonalArms = closeStroke(x - arm, y - arm) &&
                        closeStroke(x + arm, y + arm) && closeStroke(x + arm, y - arm) &&
                        closeStroke(x - arm, y + arm)
                    if (!diagonalArms) continue
                    val whiteCount = listOf(
                        light(x - clear, y), light(x + clear, y),
                        light(x, y - clear), light(x, y + clear),
                    ).count { it }
                    if (whiteCount >= 3) return MatchResult(Point(x, y), 1f)
                }
            }
            return null
        }
        // The usual X location is an inexpensive first try, but it is clicked
        // only after its shape is confirmed in the current frame.
        val expectedX = width - height * 0.17f
        val expectedY = height * 0.20f
        val margin = height * 0.06f
        findDarkX(
            (expectedX - margin).toInt(), (expectedY - margin).toInt(),
            (expectedX + margin).toInt(), (expectedY + margin).toInt(),
        )?.let { return it }
        findDarkX(
            (width - height * 0.40f).toInt(), (height * 0.10f).toInt(),
            (width - height * 0.02f).toInt(), (height * 0.29f).toInt(),
        )?.let { return it }

        val step = (height / 540f).roundToInt().coerceAtLeast(1)
        for (y in (height * 0.07f).toInt() until (height * 0.36f).toInt() step step) {
            for (x in (width - height * 0.70f).toInt().coerceAtLeast(0)
                until (width - height * 0.02f).toInt() step step) {
                val color = pixels[y * width + x]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (red < 175 || red - green < 80 || red - blue < 80 || green > 150 || blue > 150) {
                    continue
                }
                if (!whiteOffsets.all { (dx, dy) ->
                        light(x + (dx * scale).roundToInt(), y + (dy * scale).roundToInt())
                    }
                ) continue
                if (!darkOffsets.all { (dx, dy) ->
                        dark(x + (dx * scale).roundToInt(), y + (dy * scale).roundToInt())
                    }
                ) continue
                return MatchResult(Point(x, y), 1f)
            }
        }
        return null
    }
}
