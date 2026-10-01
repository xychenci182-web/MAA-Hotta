package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color

/** Recognizes the recovery overlay without relying on its stylized title font. */
object RewardRecoveryDetector {
    fun isVisible(screen: Bitmap): Boolean {
        if (screen.width < 640 || screen.height < 360 || screen.width <= screen.height) return false

        fun ratio(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            matches: (Int) -> Boolean,
        ): Float {
            val step = (screen.height / 144).coerceAtLeast(2)
            var hits = 0
            var count = 0
            for (y in (screen.height * top).toInt() until (screen.height * bottom).toInt() step step) {
                for (x in (screen.width * left).toInt() until (screen.width * right).toInt() step step) {
                    count++
                    if (matches(screen.getPixel(x, y))) hits++
                }
            }
            return if (count == 0) 0f else hits.toFloat() / count
        }

        fun red(color: Int): Boolean =
            Color.red(color) > 170 && Color.red(color) - Color.green(color) > 60 &&
                Color.red(color) - Color.blue(color) > 60
        fun blue(color: Int): Boolean =
            Color.blue(color) > 160 && Color.blue(color) - Color.red(color) > 70 &&
                Color.blue(color) - Color.green(color) > 40
        fun white(color: Int): Boolean =
            Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) > 220

        // Large red title, three light card headers and three aligned blue claim buttons.
        // Ratios keep the check tied to the game frame rather than Android DPI.
        if (ratio(0.059f, 0.583f, 0.305f, 0.681f, ::red) < 0.35f) return false
        for ((left, right) in listOf(0.391f to 0.570f, 0.590f to 0.773f, 0.793f to 0.973f)) {
            if (ratio(left, 0.308f, right, 0.385f, ::white) < 0.40f) return false
            if (ratio(left, 0.722f, right, 0.785f, ::blue) < 0.60f) return false
        }
        return true
    }
}
