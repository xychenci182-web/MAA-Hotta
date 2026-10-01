package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.SearchRegion
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.ScreenTextMatch
import com.aliothmoon.maahotta.vision.SupplyScreenDetector
import com.aliothmoon.maahotta.vision.WelfareNavigationDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class SupplyTask : GameTask {
    override val id = "supply"
    override val title = "执行供给"

    override suspend fun run(ctx: BotContext): TaskResult {
        val hudMenu = ctx.hudTemplates()
            ?: return TaskResult(title, false, "主界面菜单模板未载入")
        val alreadyOnPage = ctx.waitUntil(700, 250) { screen ->
            if (SupplyScreenDetector.isSupplyPage(screen)) center(screen) else null
        } != null
        if (!alreadyOnPage) {
            var entered = false
            var lastFailure = "未进入执行供给页面"

            val existingSpecialAction = waitForSupplyNavigationText(ctx, 1_500) != null
            if (existingSpecialAction) {
                ctx.log("当前界面已识别到福利导航，直接进入执行供给")
                entered = openSupplyFromSpecialAction(ctx)
                if (!entered) {
                    lastFailure = "从当前福利页未进入执行供给页面"
                    ctx.log("$lastFailure，返回游戏主界面重试")
                    if (!returnToGame(ctx)) {
                        return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                    }
                }
            } else {
                ctx.log("当前界面未识别到特别行动版，先返回游戏主界面")
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "重试前无法返回游戏主界面")
                }
            }

            for (attempt in 1..3) {
                if (entered) break
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "重试前无法返回游戏主界面")
                }
                ctx.log("执行供给第 $attempt 次尝试：打开右上角礼盒")
                var gift = ctx.waitUntil(10_000, 450) { screen ->
                    GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                }
                if (gift == null) {
                    lastFailure = "游戏主界面等待后仍未加载礼盒图标"
                    ctx.log("$lastFailure，不点击固定位置，继续重新截图")
                    continue
                }
                ctx.log("菜单锚点定位礼盒（向左4格）score=${"%.2f".format(gift.score)}，点击 (${gift.point.x},${gift.point.y})")
                ctx.device.tap(gift.point.x, gift.point.y)
                var navigation = waitForSupplyNavigationText(ctx, 3_000)
                if (navigation == null) {
                    ctx.log("暂未识别到特别行动版，继续截图识别2秒")
                    navigation = waitForSupplyNavigationText(ctx, 2_000)
                }
                if (navigation == null) {
                    gift = ctx.waitUntil(4_000, 400) { screen ->
                        GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                    }
                    if (gift != null) {
                        ctx.log("福利页未打开且礼盒仍在，重新识别后再次点击")
                        ctx.device.tap(gift.point.x, gift.point.y)
                    } else {
                        ctx.log("礼盒已经消失，继续等待福利页面加载")
                    }
                    navigation = waitForSupplyNavigationText(ctx, 8_000)
                }
                if (navigation == null) {
                    lastFailure = "点击礼盒后未识别到特别行动版"
                    ctx.log("$lastFailure，返回游戏主界面重试")
                    if (!returnToGame(ctx)) {
                        return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                    }
                    continue
                }
                ctx.log("已识别${navigation.target}")
                if (openSupplyFromSpecialAction(ctx)) {
                    entered = true
                    break
                }
                lastFailure = "未识别到执行供给页面"
                ctx.log("$lastFailure，返回游戏主界面重试")
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                }
            }
            if (!entered) return TaskResult(title, false, "$lastFailure；已重试3次")
        }

        val cumulativeAlreadyClaimed = ctx.waitUntil(1_500, 350) { screen ->
            if (SupplyScreenDetector.isCumulativeClaimed(screen)) center(screen) else null
        } != null
        if (cumulativeAlreadyClaimed) {
            ctx.log("识别到累计奖励已领取，直接判定执行供给完成")
            return finish(ctx, true, "累计奖励已领取，执行供给已完成")
        }

        val claim = ctx.waitUntil(2_000, 400, SupplyScreenDetector::findClaimable)
        val claimDay = claim?.let {
            val size = ctx.device.screenSize()
            SupplyScreenDetector.dayNumberAt(size.x, size.y, it.point.x)
        }
        if (claim != null) {
            val xRatio = claim.point.x.toFloat() / ctx.device.screenSize().x
            var claimButton = claim!!
            var claimed = false
            repeat(3) { attempt ->
                if (!claimed) {
                    ctx.log(
                        if (attempt == 0) "找到黄色高亮的供给奖励，点击领取"
                        else "供给奖励仍可领取，重新识别后再次点击",
                    )
                    ctx.device.tap(claimButton.point.x, claimButton.point.y)
                    claimed = ctx.waitUntil(5_000, 400) { screen ->
                        if (SupplyScreenDetector.isClaimed(screen, xRatio) &&
                            SupplyScreenDetector.findClaimable(screen) == null
                        ) center(screen) else null
                    } != null
                    if (!claimed) {
                        val refreshed = ctx.waitUntil(1_500, 350, SupplyScreenDetector::findClaimable)
                        if (refreshed != null) claimButton = refreshed
                    }
                }
            }
            if (!claimed) return stopUncertain(ctx, "点击后未确认供给已领取")
        }

        val verifiedDay = claimDay ?: detectLastClaimedDay(ctx)
        if (verifiedDay == 7) {
            ctx.log("已确认 DAY 7，等待右侧累计奖励解锁")
            val cumulative = ctx.waitUntil(12_000, 400) { screen ->
                SupplyScreenDetector.findCumulativeClaimable(screen)
            }
            if (cumulative == null) {
                val alreadyClaimed = ctx.waitUntil(1_500, 350) { screen ->
                    if (SupplyScreenDetector.isCumulativeClaimed(screen)) center(screen) else null
                } != null
                if (alreadyClaimed) {
                    ctx.log("DAY 7 累计奖励已经领取")
                    return finish(ctx, true, "累计奖励已领取")
                }
                return stopUncertain(ctx, "DAY 7 已领取，但等待后累计奖励仍未解锁")
            }

            var cumulativeButton = cumulative!!
            var cumulativeClaimed = false
            repeat(3) { attempt ->
                if (!cumulativeClaimed) {
                    ctx.log(
                        if (attempt == 0) "DAY 7 累计奖励已解锁，点击领取"
                        else "累计奖励仍可领取，重新识别后再次点击",
                    )
                    ctx.device.tap(cumulativeButton.point.x, cumulativeButton.point.y)
                    cumulativeClaimed = ctx.waitUntil(5_000, 400) { screen ->
                        if (SupplyScreenDetector.isCumulativeClaimed(screen)) center(screen) else null
                    } != null
                    if (!cumulativeClaimed) {
                        val refreshed = ctx.waitUntil(1_500, 350) { screen ->
                            SupplyScreenDetector.findCumulativeClaimable(screen)
                        }
                        if (refreshed != null) cumulativeButton = refreshed
                    }
                }
            }
            if (!cumulativeClaimed) return stopUncertain(ctx, "点击后未确认 DAY 7 累计奖励已领取")
            ctx.log("执行供给已经领取到 DAY 7，累计奖励已完成")
            return finish(ctx, true, "累计奖励已领取")
        }

        if (claim != null) {
            logClaimedDay(ctx, verifiedDay)
            return finish(ctx, true, "今日供给已领取")
        }
        ctx.log("未识别到黄色供给奖励，按今日已领取处理")
        logClaimedDay(ctx, verifiedDay)
        return finish(ctx, true, "今日供给已领取")
    }

    private fun center(screen: Bitmap): MatchResult =
        MatchResult(Point(screen.width / 2, screen.height / 2), 1f)

    private suspend fun tapFromLeft(ctx: BotContext, x: Float, y: Float) {
        val size = ctx.device.screenSize()
        val scale = size.y / 525f
        ctx.device.tap(
            (x * scale).toInt().coerceIn(0, size.x - 1),
            (y * scale).toInt().coerceIn(0, size.y - 1),
        )
    }

    private suspend fun finish(ctx: BotContext, claimed: Boolean, detail: String): TaskResult {
        if (isGameHud(ctx)) {
            ctx.log("已在游戏主界面，不再点击左上角返回")
            return TaskResult(title, claimed, detail)
        }
        ctx.log("退出福利页")
        tapFromLeft(ctx, 45f, 27f)
        repeat(5) {
            delay(500)
            if (isGameHud(ctx)) {
                return TaskResult(title, claimed, detail)
            }
        }
        return TaskResult(title, false, "$detail；未能返回游戏主界面", retryable = !claimed)
    }

    private suspend fun stopUncertain(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }

    private suspend fun returnToGame(ctx: BotContext): Boolean {
        if (isGameHud(ctx)) return true
        ctx.log("点击左上角返回游戏主界面")
        tapFromLeft(ctx, 45f, 27f)
        repeat(8) {
            delay(500)
            if (isGameHud(ctx)) return true
        }
        return false
    }

    private suspend fun isGameHud(ctx: BotContext): Boolean =
        GameHudNavigator.ensurePlainHud(ctx)

    private suspend fun detectLastClaimedDay(ctx: BotContext): Int? {
        var detected: Int? = null
        for (attempt in 0 until 3) {
            val screen = ctx.device.screenshot()
            if (screen != null) {
                detected = try {
                    SupplyScreenDetector.lastClaimedDay(screen)
                } finally {
                    screen.recycle()
                }
                if (detected != null) break
            }
            if (attempt < 2) delay(350)
        }
        return detected
    }

    private suspend fun logClaimedDay(ctx: BotContext, fallback: Int? = null) {
        val day = detectLastClaimedDay(ctx) ?: fallback
        if (day != null) {
            ctx.log("执行供给已经领取到 DAY $day")
        } else {
            ctx.log("执行供给今日已领取，未识别到具体 DAY")
        }
    }

    private suspend fun openSupplyFromSpecialAction(ctx: BotContext): Boolean {
        repeat(6) { attempt ->
            val alreadyEntered = ctx.waitUntil(700, 250) { screen ->
                if (SupplyScreenDetector.isSupplyPage(screen)) center(screen) else null
            } != null
            if (alreadyEntered) return true

            val action = waitForSupplyNavigationText(ctx, 4_000)
            if (action == null) {
                ctx.log("未识别到特别行动版或执行供给，重新截图识别")
                return@repeat
            }

            ctx.log("识别到${action.target}，点击 (${action.point.x},${action.point.y})")
            ctx.device.tap(action.point.x, action.point.y)

            if (action.target == "执行供给") {
                val entered = ctx.waitUntil(4_000, 450) { screen ->
                    if (SupplyScreenDetector.isSupplyPage(screen)) center(screen) else null
                } != null
                if (entered) return true
                ctx.log("点击执行供给后未进入页面，重新识别当前按钮")
            } else {
                ctx.log("点击特别行动版后，重新识别执行供给")
            }
        }
        return false
    }

    private suspend fun waitForSupplyNavigationText(
        ctx: BotContext,
        timeoutMs: Long,
    ): ScreenTextMatch? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen != null) {
                val width = screen.width
                val height = screen.height
                val match = try {
                    if (!WelfareNavigationDetector.hasBottomNavigation(screen)) null
                    else withTimeoutOrNull(
                        minOf(8_000L, deadline - ctx.elapsedRealtime()).coerceAtLeast(1L),
                    ) {
                        // Both labels have fixed layout roles. Use one live screenshot,
                        // crop each label's area, and prefer the next menu step.
                        val actionRegion = SearchRegion(
                            (height * 0.03f / width).coerceIn(0f, 1f), 0.225f,
                            (height * 0.35f / width).coerceIn(0f, 1f), 0.34f,
                        )
                        val tabRegion = SearchRegion(
                            (height * 0.28f / width).coerceIn(0f, 1f), 0.88f,
                            (height * 0.65f / width).coerceIn(0f, 1f), 1f,
                        )
                        val tabSelected = WelfareNavigationDetector.hasSelectedSpecialActionTab(screen)
                        if (tabSelected) {
                            ScreenTextFinder.findAllInRegion(screen, listOf("执行供给"), actionRegion)
                                .firstOrNull()
                                ?: ScreenTextFinder.findAllInRegion(screen, listOf("特别行动版"), tabRegion)
                                    .firstOrNull()
                        } else {
                            ScreenTextFinder.findAllInRegion(screen, listOf("特别行动版"), tabRegion)
                                .firstOrNull()
                                ?: ScreenTextFinder.findAllInRegion(screen, listOf("执行供给"), actionRegion)
                                    .firstOrNull()
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                } finally {
                    screen.recycle()
                }
                if (match != null) {
                    val size = ctx.device.screenSize()
                    return match.copy(
                        point = Point(
                            (match.point.x.toDouble() * size.x / width).toInt(),
                            (match.point.y.toDouble() * size.y / height).toInt(),
                        ),
                    )
                }
            }
        }
        return null
    }
}
