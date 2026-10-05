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

            val giftAttempts = if (existingSpecialAction) 1 else 2
            for (attempt in 1..giftAttempts) {
                if (entered) break
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "重试前无法返回游戏主界面")
                }
                val attemptNumber = if (existingSpecialAction) attempt + 1 else attempt
                ctx.log("执行供给第 $attemptNumber 次尝试：打开右上角礼盒")
                var gift = ctx.waitUntil(10_000, 450) { screen ->
                    GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                }
                if (gift == null) {
                    lastFailure = "游戏主界面等待后仍未加载礼盒图标"
                    ctx.log("$lastFailure，不点击固定位置，继续重新截图")
                    continue
                }
                ctx.log("菜单锚点定位礼盒（向左4格）score=${"%.2f".format(gift.score)}，点击 (${gift.point.x},${gift.point.y})")
                if (!ctx.tryTaskStep("$id:gift")) {
                    return TaskResult(title, false, "礼盒入口已重试一次，停止重复点击")
                }
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
                        if (!ctx.tryTaskStep("$id:gift")) {
                            return TaskResult(title, false, "礼盒入口已重试一次，停止重复点击")
                        }
                        ctx.log("福利页未打开且礼盒仍在，重新识别后再次点击")
                        ctx.device.tap(gift.point.x, gift.point.y)
                    } else {
                        ctx.log("礼盒已经消失，继续等待福利页面加载")
                    }
                    navigation = waitForSupplyNavigationText(ctx, 8_000)
                }
                if (navigation == null) {
                    lastFailure = "点击礼盒后未识别到特别行动版"
                    if (attempt == giftAttempts) break
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
                if (attempt == giftAttempts) break
                ctx.log("$lastFailure，返回游戏主界面重试")
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                }
            }
            if (!entered) return TaskResult(title, false, "$lastFailure；初次尝试及一次重试均未成功")
        }

        var rewards: SupplyScreenDetector.ClaimableRewards? = null
        val selection = ctx.waitUntil(2_000, 400) { screen ->
            val current = SupplyScreenDetector.inspectClaimableRewards(screen)
                ?: return@waitUntil null
            rewards = current
            // Prefer a DAY when both a DAY and the cumulative chest are highlighted.
            current.dayReward ?: current.cumulativeReward ?: center(screen)
        } ?: return stopUncertain(ctx, "未能确认执行供给页面，停止领取")
        val currentRewards = rewards
            ?: return stopUncertain(ctx, "未取得执行供给黄色高亮判断结果")
        val day = currentRewards.dayNumber

        if (day == null && currentRewards.cumulativeReward == null) {
            ctx.log("同一截图中七个 DAY 和累计奖励均无黄色高亮，执行供给结束")
            return finish(ctx, true, "无黄色高亮的可领取供给奖励")
        }

        val dayXRatio = currentRewards.dayXRatio
        if (day != null && dayXRatio == null) {
            return stopUncertain(ctx, "未取得目标供给 DAY 的确认区域，停止领取")
        }
        var dayConfirmed = day == null
        var cumulativeSubmitted = false
        var cumulativeConfirmed = false

        // Keep the checkpoint across global recovery; never replay a submitted reward.
        suspend fun continueClaims(): TaskResult {
            if (!dayConfirmed) {
                ctx.log("只检查刚点击的供给 DAY $day，等待黄色消失并变为灰色已领取遮罩")
                val dayClaimed = waitForGrayOverlay(ctx, 8_000) { screen ->
                    SupplyScreenDetector.isClaimedInRegion(screen, requireNotNull(dayXRatio))
                }
                if (!dayClaimed) {
                    return stopUncertain(ctx, "点击后未确认供给 DAY $day 灰色已领取遮罩，不重复点击 DAY")
                }
                dayConfirmed = true
                ctx.confirmActionResult()
                ctx.log("连续两帧确认供给 DAY $day 灰色已领取遮罩，领取成功")
            }
            if (day != null && day != 7 && currentRewards.cumulativeReward == null) {
                return finish(ctx, true, "供给 DAY $day 已领取")
            }
            if (!cumulativeSubmitted) {
                val cumulative = if (day != null) {
                    ctx.log("供给 DAY $day 已领取，只识别右侧累计奖励黄色高亮")
                    ctx.waitUntil(12_000, 400, SupplyScreenDetector::findCumulativeClaimableInRegion)
                        ?: return stopUncertain(ctx, "供给 DAY $day 已领取，但未识别到累计奖励黄色高亮，不重复点击 DAY")
                } else {
                    ctx.log("仅累计奖励有黄色高亮，直接点击累计奖励一次")
                    selection
                }
                ctx.markActionSubmitted("${id}_cumulative_claim")
                cumulativeSubmitted = true
                ctx.device.tap(cumulative.point.x, cumulative.point.y)
            }
            if (!cumulativeConfirmed) {
                ctx.log("累计奖励已点击，只检查累计奖励区域的灰色已领取遮罩")
                val cumulativeClaimed = waitForGrayOverlay(
                    ctx, 5_000, SupplyScreenDetector::isCumulativeClaimedInRegion,
                )
                if (!cumulativeClaimed) {
                    return stopUncertain(ctx, "点击后未确认累计奖励灰色已领取遮罩，不重复点击累计奖励")
                }
                cumulativeConfirmed = true
                ctx.confirmActionResult()
            }
            return finish(ctx, true, "累计奖励已领取")
        }

        ctx.onTaskFailureRecovery { screen, _ ->
            if (SupplyScreenDetector.isSupplyPage(screen)) continueClaims() else null
        }
        if (day != null) {
            ctx.log("识别到供给 DAY $day 黄色高亮，点击一次")
            ctx.markActionSubmitted("${id}_day_claim")
            ctx.device.tap(selection.point.x, selection.point.y)
        }
        return continueClaims()
    }

    private fun center(screen: Bitmap): MatchResult =
        MatchResult(Point(screen.width / 2, screen.height / 2), 1f)

    private suspend fun tapFromLeft(ctx: BotContext, x: Float, y: Float): Boolean {
        if (!ctx.tryTaskStep("$id:welfare_back")) return false
        val size = ctx.device.screenSize()
        val scale = size.y / 525f
        ctx.device.tap(
            (x * scale).toInt().coerceIn(0, size.x - 1),
            (y * scale).toInt().coerceIn(0, size.y - 1),
        )
        return true
    }

    private suspend fun finish(ctx: BotContext, claimed: Boolean, detail: String): TaskResult {
        ctx.log("执行供给完成，点击左上角退出福利页，不再检查游戏主界面")
        if (!tapFromLeft(ctx, 45f, 27f)) {
            return TaskResult(title, false, "$detail；退出福利页已重试一次，未再点击")
        }
        return TaskResult(title, claimed, detail)
    }

    private suspend fun stopUncertain(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult.uncertain(title, detail)
    }

    private suspend fun returnToGame(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUD)
        if (isGameHud(ctx)) return true
        ctx.log("点击左上角返回游戏主界面")
        if (!tapFromLeft(ctx, 45f, 27f)) return false
        repeat(8) {
            delay(500)
            if (isGameHud(ctx)) return true
        }
        return false
    }

    private suspend fun isGameHud(ctx: BotContext): Boolean =
        GameHudNavigator.ensurePlainHud(ctx)

    private suspend fun waitForGrayOverlay(
        ctx: BotContext,
        timeoutMs: Long,
        condition: (Bitmap) -> Boolean,
    ): Boolean = ctx.waitUntil(timeoutMs, 350) { screen ->
        if (condition(screen)) center(screen).copy(requiresStableFrames = true) else null
    } != null

    private suspend fun openSupplyFromSpecialAction(ctx: BotContext): Boolean {
        val navigationTapCounts = mutableMapOf<String, Int>()
        // These rounds include both navigation stages and screenshot-only polling.
        repeat(6) {
            val alreadyEntered = ctx.waitUntil(700, 250) { screen ->
                if (SupplyScreenDetector.isSupplyPage(screen)) center(screen) else null
            } != null
            if (alreadyEntered) return true

            val action = waitForSupplyNavigationText(ctx, 4_000)
            if (action == null) {
                ctx.log("未识别到特别行动版或执行供给，重新截图识别")
                return@repeat
            }

            val tapCount = navigationTapCounts[action.target] ?: 0
            if (tapCount >= 2) {
                ctx.log("${action.target}初次点击及一次重试均未进入目标页，不再点击")
                return@repeat
            }
            if (!ctx.tryTaskStep("$id:${action.target}")) return false
            navigationTapCounts[action.target] = tapCount + 1

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
