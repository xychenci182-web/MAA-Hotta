package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import kotlin.math.roundToInt

enum class GameScreen { HUD, MENU, SETTINGS, OTHER }

data class HudTemplates(val menu: Bitmap, val minimap: Bitmap? = null, val exitDialog: Bitmap? = null, val exitConfirm: Bitmap? = null, val dodge: Bitmap? = null, val dungeonExit: Bitmap? = null)

data class HudDetection(
    val menu: MatchResult? = null,
    val menuScore: Float = 0f,
    val features: Map<String, MatchResult> = emptyMap(),
    val accepted: Boolean = false,
    val reason: String,
) {
    fun summary(): String = "菜单图标=${"%.2f".format(menuScore)}，阈值=0.78；$reason"
}

/** One menu anchor for HUD verification and fixed-spacing navigation. */
object GameScreenDetector {
    // 1280 x 720 reference: menu x=1210, crossed x=1096, gift x=982.
    // Scale uniformly by height, just like ReferenceFrameController.
    private const val ICON_SPACING_AT_720 = 57f

    fun findHudMenu(screen: Bitmap, templates: HudTemplates?): MatchResult? =
        inspectHud(screen, templates).menu

    fun inspectHud(screen: Bitmap, templates: HudTemplates?): HudDetection {
        if (templates == null) return HudDetection(reason = "主界面验证模板未完整载入")
        if (screen.width <= screen.height) return HudDetection(reason = "游戏画面尚未横屏")
        val best = matchFeature(screen, templates.menu, searchRegions.getValue("menu"))
        val menuScore = best?.score ?: 0f
        val features = if (best != null && menuScore >= 0.78f) mapOf("menu" to best) else emptyMap()
        val reason = when {
            WelfareNavigationDetector.hasBottomNavigation(screen) -> "检测到福利底栏，拒绝主界面判定"
            hasConfirmationPanel(screen) -> "检测到确认弹窗，拒绝主界面判定"
            BygoneScreenDetector.findExitDialog(screen, templates.exitDialog) != null &&
                BygoneScreenDetector.findExitConfirm(screen, templates.exitConfirm) != null ->
                "检测到旧日退出确认弹窗，拒绝主界面判定"
            AccountScreenDetector.findPasswordSubmit(screen) != null -> "检测到登录面板，拒绝主界面判定"
            templates.dungeonExit?.let { BygoneScreenDetector.findExitIcon(screen, it)?.score ?: 0f }?.let { it >= 0.62f } == true -> "检测到副本退出图标，拒绝主界面判定"
            menuScore < 0.78f -> "菜单未达到0.78"
            else -> null
        }
        return HudDetection(
            menu = if (reason == null) features["menu"]?.copy(requiresStableFrames = true) else null,
            menuScore = menuScore,
            features = features, accepted = reason == null,
            reason = reason ?: "本帧菜单匹配通过",
        )
    }

    // Shared with diagnostic overlays; bounds refer to the normalized game frame.
    val searchRegions = linkedMapOf(
        "menu" to SearchRegion(0.85f, 0f, 1f, 0.13f),
    )

    fun stablePair(previous: HudDetection, current: HudDetection, height: Int): Boolean =
        previous.accepted && current.accepted && current.features.count { (name, hit) ->
            previous.features[name]?.let {
                kotlin.math.abs(it.point.x - hit.point.x) <= 8f * height / 528f &&
                    kotlin.math.abs(it.point.y - hit.point.y) <= 8f * height / 528f
            } == true
        } >= 1

    private fun matchFeature(screen: Bitmap, template: Bitmap, region: SearchRegion, smooth: Boolean = false): MatchResult? {
        var best: MatchResult? = null
        for (scale in floatArrayOf(0.78f, 0.90f, 1f, 1.10f, 1.22f)) {
            val candidate = if (scale == 1f) template else Bitmap.createScaledBitmap(template,
                (template.width * scale).roundToInt().coerceAtLeast(4),
                (template.height * scale).roundToInt().coerceAtLeast(4), true)
            try {
                val hit = TemplateMatcher.match(screen, candidate, threshold = -1f, step = 1,
                    region = region, referenceHeight = 528, smooth = smooth)
                if (hit != null && hit.score > (best?.score ?: -1f)) best = hit
            } finally { if (candidate !== template) candidate.recycle() }
        }
        return best
    }

    fun findCrossedHudIcon(screen: Bitmap, menuTemplate: HudTemplates?): MatchResult? =
        findHudSlot(screen, menuTemplate, 2)

    fun findGiftHudIcon(screen: Bitmap, menuTemplate: HudTemplates?): MatchResult? =
        findHudSlot(screen, menuTemplate, 4)

    private fun findHudSlot(screen: Bitmap, menuTemplate: HudTemplates?, slotsLeft: Int): MatchResult? {
        val menu = findHudMenu(screen, menuTemplate) ?: return null
        val x = (menu.point.x - slotsLeft * ICON_SPACING_AT_720 * screen.height / 720f).roundToInt()
        if (x !in 0 until screen.width || menu.point.y !in 0 until screen.height) return null
        // Score belongs to the menu anchor, not to the derived icon.
        return menu.copy(point = Point(x, menu.point.y))
    }

    private fun hasConfirmationPanel(screen: Bitmap): Boolean {
        fun ratio(l: Float, t: Float, r: Float, b: Float, test: (Int, Int, Int) -> Boolean): Float {
            var count = 0; var hits = 0
            val step = (screen.height / 180).coerceAtLeast(1)
            for (y in (t * screen.height).toInt() until (b * screen.height).toInt() step step)
                for (x in (l * screen.width).toInt() until (r * screen.width).toInt() step step) {
                    val c = screen.getPixel(x, y)
                    if (test(Color.red(c), Color.green(c), Color.blue(c))) hits++
                    count++
                }
            return if (count == 0) 0f else hits.toFloat() / count
        }
        val neutral = ratio(0.30f, 0.30f, 0.70f, 0.62f) { r, g, b ->
            minOf(r, g, b) > 115 && maxOf(r, g, b) - minOf(r, g, b) < 35
        }
        val blueButton = ratio(0.53f, 0.44f, 0.80f, 0.70f) { r, g, b -> b > 170 && g > 100 && b - r > 70 }
        return neutral > 0.55f && blueButton > 0.10f
    }

    fun classify(screen: Bitmap, menuTemplate: HudTemplates?): GameScreen {
        if (inspectHud(screen, menuTemplate).accepted) return GameScreen.HUD
        val width = screen.width
        val height = screen.height
        if (width < 320 || height < 240) return GameScreen.OTHER
        val pixels = IntArray(width * height)
        screen.getPixels(pixels, 0, width, 0, 0, width, height)
        val step = (height / 270f).roundToInt().coerceAtLeast(1)

        fun ratio(left: Float, top: Float, right: Float, bottom: Float, test: (Int) -> Boolean): Float {
            var hits = 0
            var count = 0
            for (y in (height * top).toInt() until (height * bottom).toInt() step step) {
                for (x in (width * left).toInt() until (width * right).toInt() step step) {
                    count++
                    if (test(pixels[y * width + x])) hits++
                }
            }
            return if (count == 0) 0f else hits.toFloat() / count
        }

        fun bright(color: Int): Boolean =
            Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) > 180

        val lowerRightBright = ratio(0.70f, 0.55f, 0.98f, 0.94f, ::bright)
        if (lowerRightBright > 0.60f && ratio(0.40f, 0.34f, 0.60f, 0.66f, ::bright) > 0.40f) {
            return GameScreen.SETTINGS
        }
        // The in-game HUD draws a long cyan rule along the bottom edge.
        // Notification diamonds in the top-right may disappear, so they cannot gate login.
        val sampleStep = (width / 640f).roundToInt().coerceAtLeast(1)
        val minX = (width * 0.03f).toInt()
        val maxX = (width * 0.97f).toInt()
        var hasHudRule = false
        for (y in (height * 0.88f).toInt() until height) {
            var cyan = 0
            var count = 0
            for (x in minX until maxX step sampleStep) {
                val color = pixels[y * width + x]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (blue > 130 && green > 100 && blue - red > 55 && green - red > 25) cyan++
                count++
            }
            if (count > 0 && cyan.toFloat() / count > 0.65f) {
                hasHudRule = true
                break
            }
        }
        if (!hasHudRule) return GameScreen.OTHER

        // The opened hexagon menu replaces the top-right icons with a bright X.
        val scale = height / 1080f
        val menuClose = ratio(
            ((width - 240 * scale) / width).coerceAtLeast(0f),
            0.055f,
            ((width - 150 * scale) / width).coerceAtMost(1f),
            0.112f,
            ::bright,
        )
        if (menuClose > 0.035f) return GameScreen.MENU
        return GameScreen.OTHER
    }
}
