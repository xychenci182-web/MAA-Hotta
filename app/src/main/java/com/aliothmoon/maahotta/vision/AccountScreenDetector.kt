package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point

/** Distinguishes the two account overlays shown over the title/settings screens. */
object AccountScreenDetector {
    /** Wide blue submit button on the password overlay. */
    fun findPasswordSubmit(screen: Bitmap): MatchResult? {
        if (screen.width < 320 || screen.height < 240) return null
        fun white(color: Int): Boolean =
            Color.red(color) > 225 && Color.green(color) > 225 && Color.blue(color) > 225
        fun blue(color: Int): Boolean =
            Color.red(color) < 150 && Color.green(color) in 100..200 &&
                Color.blue(color) > 165 && Color.blue(color) - Color.red(color) > 55

        // The account panel scales with height. Its title can move over old fixed
        // sample points, so verify the blank side margins and locate the button.
        val center = screen.width / 2
        val halfWidth = (screen.height * 0.30f).toInt()
        val margin = (screen.height * 0.40f).toInt()
        if (center - margin < 0 || center + margin >= screen.width) return null
        for (side in listOf(-1, 1)) {
            for (y in listOf(0.30f, 0.74f)) {
                if (!white(screen.getPixel(center + side * margin, (screen.height * y).toInt()))) return null
            }
        }
        var start = -1
        val minimumHeight = (screen.height * 0.06f).toInt()
        for (y in (screen.height * 0.40f).toInt()..(screen.height * 0.69f).toInt()) {
            var hits = 0
            var samples = 0
            for (x in center - halfWidth..center + halfWidth step 3) {
                if (blue(screen.getPixel(x, y))) hits++
                samples++
            }
            if (hits >= samples * 0.85f) {
                if (start < 0) start = y
            } else {
                if (start >= 0 && y - start >= minimumHeight) {
                    return MatchResult(Point(center, (start + y - 1) / 2), 1f)
                }
                start = -1
            }
        }
        return null
    }

    private fun light(screen: Bitmap, dxByHeight: Float, yRatio: Float): Boolean {
        val x = (screen.width / 2f + dxByHeight * screen.height).toInt()
        val y = (yRatio * screen.height).toInt()
        if (x !in 0 until screen.width || y !in 0 until screen.height) return false
        val color = screen.getPixel(x, y)
        return Color.red(color) > 238 && Color.green(color) > 238 && Color.blue(color) > 238
    }

    private fun blueButtonAt(screen: Bitmap, dxByHeight: Float, yRatio: Float): Boolean {
        val x = (screen.width / 2f + dxByHeight * screen.height).toInt()
        val y = (yRatio * screen.height).toInt()
        if (x !in 0 until screen.width || y !in 0 until screen.height) return false
        val color = screen.getPixel(x, y)
        return Color.red(color) < 140 && Color.green(color) in 110..190 &&
            Color.blue(color) > 180 && Color.blue(color) - Color.red(color) > 60
    }

    fun isUserCenter(screen: Bitmap): Boolean =
        light(screen, -0.30f, 0.06f) && light(screen, 0f, 0.06f) &&
            light(screen, 0.30f, 0.06f) && light(screen, -0.30f, 0.79f) &&
            light(screen, 0.30f, 0.79f)

    fun isQuickLogin(screen: Bitmap): Boolean =
        light(screen, -0.18f, 0.34f) && light(screen, 0.18f, 0.34f) &&
            light(screen, 0f, 0.68f) && !light(screen, -0.36f, 0.34f) &&
            !light(screen, 0.36f, 0.34f) && blueButtonAt(screen, -0.10f, 0.56f)

    /** Saved-account chooser shown after tapping Switch account in User Center. */
    fun isAccountList(screen: Bitmap): Boolean =
        !light(screen, 0f, 0.06f) && light(screen, 0f, 0.30f) &&
            light(screen, 0f, 0.45f) && light(screen, 0f, 0.67f) &&
            light(screen, -0.20f, 0.67f) && light(screen, 0.20f, 0.67f) &&
            !blueButtonAt(screen, -0.10f, 0.56f)
}
