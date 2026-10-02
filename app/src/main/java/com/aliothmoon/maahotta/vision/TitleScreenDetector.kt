package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

/** Recognizes the bright "点击进入" caption and cyan rails on the game title screen. */
object TitleScreenDetector {
    /** Same-frame positive title evidence that is safe to use before slower login routing. */
    fun findUnobstructedEntry(screen: Bitmap): MatchResult? {
        val entry = findEntry(screen) ?: return null
        if (AnnouncementDetector.hasLayout(screen) ||
            RewardRecoveryDetector.isVisible(screen) ||
            GameScreenDetector.hasConfirmationPanel(screen) ||
            AccountScreenDetector.isUserCenter(screen) ||
            AccountScreenDetector.isAccountList(screen) ||
            AccountScreenDetector.isQuickLogin(screen) ||
            AccountScreenDetector.findPasswordSubmit(screen) != null
        ) return null
        return entry
    }

    fun findEntry(screen: Bitmap): MatchResult? {
        val width = screen.width
        val height = screen.height
        if (width < 320 || height < 240) return null
        val pixels = IntArray(width * height)
        screen.getPixels(pixels, 0, width, 0, 0, width, height)
        val step = (height / 540f).roundToInt().coerceAtLeast(1)

        fun sample(left: Float, top: Float, right: Float, bottom: Float, test: (Int) -> Boolean): Pair<Int, Int> {
            var hits = 0
            var count = 0
            for (y in (height * top).toInt() until (height * bottom).toInt() step step) {
                for (x in (width * left).toInt() until (width * right).toInt() step step) {
                    count++
                    if (test(pixels[y * width + x])) hits++
                }
            }
            return hits to count
        }

        fun isLight(color: Int): Boolean =
            Color.red(color) > 215 && Color.green(color) > 215 && Color.blue(color) > 215

        // Both the account card and the notice cover the center with a large light panel.
        val panel = sample(0.40f, 0.34f, 0.60f, 0.66f, ::isLight)
        if (panel.second == 0 || panel.first.toFloat() / panel.second > 0.15f) return null

        val caption = sample(0.45f, 0.87f, 0.55f, 0.91f) { color ->
            Color.red(color) > 225 && Color.green(color) > 225 && Color.blue(color) > 225
        }
        if (caption.second == 0 || caption.first.toFloat() / caption.second < 0.08f) return null

        fun cyan(color: Int): Boolean {
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return red < 170 && green > red + 17 && blue > green + 2
        }
        val leftRail = sample(0.38f, 0.86f, 0.45f, 0.92f, ::cyan)
        val rightRail = sample(0.55f, 0.86f, 0.62f, 0.92f, ::cyan)
        if (leftRail.second == 0 || rightRail.second == 0 ||
            leftRail.first.toFloat() / leftRail.second < 0.20f ||
            rightRail.first.toFloat() / rightRail.second < 0.20f
        ) return null

        return MatchResult(Point(width / 2, (height * 0.889f).roundToInt()), 1f)
    }
}
