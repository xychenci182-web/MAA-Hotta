package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap

/**
 * Matches only the red “奖励找回” title cropped from the 1280×720 reference frame.
 * The search window is that crop plus a small pad; the rest of the screen is ignored.
 */
object RewardRecoveryDetector {
    const val REFERENCE_HEIGHT = 720
    const val THRESHOLD = 0.78f

    /** Origin of reward_recovery_title.png on the 1280×720 reference frame. */
    const val CROP_LEFT = 70
    const val CROP_TOP = 412

    val searchRegion = SearchRegion(
        50f / 1280f,
        392f / 720f,
        420f / 1280f,
        520f / 720f,
    )

    fun match(screen: Bitmap, template: Bitmap?): MatchResult? {
        if (template == null || screen.width <= screen.height) return null
        return TemplateMatcher.match(
            screen,
            template,
            threshold = THRESHOLD,
            step = 1,
            region = searchRegion,
            referenceHeight = REFERENCE_HEIGHT,
        )
    }
}
