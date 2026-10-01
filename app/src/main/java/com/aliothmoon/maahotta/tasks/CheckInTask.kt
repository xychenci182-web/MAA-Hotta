package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.CheckInScreenDetector
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.MailScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.SearchRegion
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.ScreenTextMatch
import com.aliothmoon.maahotta.vision.WelfareNavigationDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class CheckInTask(
    private val keepWelfareOpenForSupply: Boolean = false,
) : GameTask {
    override val id = "check_in"
    override val title = "每日签到"

    private sealed interface ClaimState {
        data class Available(val point: Point) : ClaimState
        data object AlreadyClaimed : ClaimState
    }

    override suspend fun run(ctx: BotContext): TaskResult {
        val hudMenu = ctx.hudTemplates()
            ?: return TaskResult(title, false, "主界面菜单模板未载入")
        val alreadyOnPage = ctx.waitUntil(700, 250) { screen ->
            if (CheckInScreenDetector.isSignInPage(screen)) center(screen) else null
        } != null
        if (!alreadyOnPage) {
            var entered = false
            var lastFailure = "未进入福利签到页"
            for (attempt in 1..3) {
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "重试前无法返回游戏主界面")
                }
                ctx.log("每日签到第 $attempt 次尝试：打开右上角礼盒")
                val gift = ctx.waitUntil(10_000, 450) { screen ->
                    GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                }
                if (gift == null) {
                    lastFailure = "游戏主界面等待后仍未加载礼盒图标"
                    ctx.log("$lastFailure，不点击固定位置，继续重新截图")
                    continue
                }
                ctx.log("菜单锚点定位礼盒（向左4格）score=${"%.2f".format(gift.score)}，点击 (${gift.point.x},${gift.point.y})")
                ctx.device.tap(gift.point.x, gift.point.y)
                if (openSignInAfterGift(ctx, hudMenu)) {
                    entered = true
                    break
                }
                lastFailure = "等待并重试后仍未进入福利签到页"
                ctx.log("$lastFailure，返回游戏主界面重试")
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                }
            }
            if (!entered) return TaskResult(title, false, "$lastFailure；已重试3次")
        }

        val claim = when (val state = waitForClaimState(ctx)) {
            is ClaimState.Available -> state.point
            ClaimState.AlreadyClaimed -> {
                ctx.log("未看到可领取高亮，连续两帧识别到已领取对勾，今日已签到")
                return finish(ctx, true, "今日已签到")
            }
            null -> return failUnknown(ctx, "签到页奖励状态无法确认")
        }
        val rewardPopupTemplate = ctx.templates.get("mail_reward_popup")
        var claimPoint = claim
        var xRatio = claimPoint.x.toFloat() / ctx.device.screenSize().x
        repeat(3) { attempt ->
            ctx.log(if (attempt == 0) "找到黄色高亮的签到奖励，点击领取"
                else "奖励仍可领取，重新识别后再次点击")
            ctx.device.tap(claimPoint.x, claimPoint.y)
            var popupVisible = false
            val changed = ctx.waitUntil(4_000, 500) { screen ->
                if (isRewardPopup(screen, rewardPopupTemplate)) {
                    popupVisible = true
                    center(screen)
                } else if (CheckInScreenDetector.isSignInPage(screen) &&
                    CheckInScreenDetector.hasClaimCheck(screen, xRatio) &&
                    CheckInScreenDetector.findClaimable(screen) == null
                ) center(screen) else null
            }
            if (changed != null) {
                if (!popupVisible) return finish(ctx, true, "已领取签到奖励")
                if (closeRewardPopup(ctx, rewardPopupTemplate, xRatio)) {
                    return finish(ctx, true, "已领取并关闭奖励弹窗")
                }
                return failUnknown(ctx, "奖励弹窗未能关闭并确认对勾")
            }
            val refreshed = ctx.waitUntil(1_200, 500) { screen ->
                if (CheckInScreenDetector.isSignInPage(screen))
                    CheckInScreenDetector.findClaimable(screen) else null
            } ?: return failUnknown(ctx, "点击签到奖励后状态没有变化，且未找到可重试的卡片")
            claimPoint = refreshed.point
            xRatio = claimPoint.x.toFloat() / ctx.device.screenSize().x
        }
        return failUnknown(ctx, "多次点击后仍无法确认签到奖励")
    }

    private suspend fun waitForClaimState(ctx: BotContext): ClaimState? {
        var stableClaimedFrames = 0
        var alreadyClaimed = false
        val found = ctx.waitUntil(2_500, 500) { screen ->
            if (!CheckInScreenDetector.isSignInPage(screen)) {
                stableClaimedFrames = 0
                return@waitUntil null
            }
            val available = CheckInScreenDetector.findClaimable(screen)
            if (available != null) return@waitUntil available
            if (CheckInScreenDetector.hasPossibleClaimable(screen) ||
                !CheckInScreenDetector.hasAnyClaimCheck(screen)
            ) {
                stableClaimedFrames = 0
                return@waitUntil null
            }
            stableClaimedFrames++
            if (stableClaimedFrames >= 2) {
                alreadyClaimed = true
                center(screen)
            } else null
        } ?: return null
        return if (alreadyClaimed) ClaimState.AlreadyClaimed else ClaimState.Available(found.point)
    }

    private suspend fun closeRewardPopup(ctx: BotContext, template: Bitmap?, xRatio: Float): Boolean {
        repeat(3) {
            val size = ctx.device.screenSize()
            ctx.log("已识别奖励弹窗，点击上方空白处关闭")
            ctx.device.tap(size.x / 2, (size.y * 0.12f).toInt())
            if (ctx.waitUntil(3_000, 500) { screen ->
                    if (!isRewardPopup(screen, template) &&
                        CheckInScreenDetector.isSignInPage(screen) &&
                        CheckInScreenDetector.hasClaimCheck(screen, xRatio) &&
                        CheckInScreenDetector.findClaimable(screen) == null
                    ) center(screen) else null
                } != null
            ) return true
        }
        return false
    }

    private suspend fun failUnknown(ctx: BotContext, reason: String): TaskResult {
        ctx.log(reason)
        ctx.saveTaskDiagnostic("check_in")
        return TaskResult(title, false, reason, retryable = false)
    }

    private fun center(screen: Bitmap): MatchResult =
        MatchResult(Point(screen.width / 2, screen.height / 2), 1f)

    private fun isRewardPopup(screen: Bitmap, template: Bitmap?): Boolean =
        CheckInScreenDetector.isRewardPopup(screen) ||
            MailScreenDetector.findRewardPopup(screen, template) != null

    private suspend fun openSignInAfterGift(
        ctx: BotContext,
        hudMenu: com.aliothmoon.maahotta.vision.HudTemplates,
    ): Boolean {
        val deadline = ctx.deadlineAfter(30_000)
        var giftRetryCount = 0
        var lastGiftTapAt = ctx.elapsedRealtime()
        var welfareSelected = false
        var lastWelfareTapAt = 0L

        while (ctx.elapsedRealtime() < deadline) {
            val page = ctx.waitUntil(700, 250) { screen ->
                if (CheckInScreenDetector.isSignInPage(screen)) center(screen) else null
            }
            if (page != null) return true

            val now = ctx.elapsedRealtime()
            val canRetryWelfare = !welfareSelected || now - lastWelfareTapAt >= 8_000
            val targets = if (canRetryWelfare) listOf("签到", "福利") else listOf("签到")
            val action = waitForCheckInNavigationText(ctx, targets, 3_000)
            if (action != null) {
                ctx.log("识别到${action.target}，点击 (${action.point.x},${action.point.y})")
                ctx.device.tap(action.point.x, action.point.y)
                if (action.target == "福利") {
                    welfareSelected = true
                    lastWelfareTapAt = ctx.elapsedRealtime()
                    ctx.log("已点击福利，等待签到内容加载")
                } else {
                    ctx.log("已点击签到，等待签到页面加载")
                }
                continue
            }

            val bottomNavigationLoaded = ctx.waitUntil(700, 250) { screen ->
                if (WelfareNavigationDetector.hasBottomNavigation(screen)) center(screen) else null
            } != null
            if (bottomNavigationLoaded) {
                ctx.log(
                    if (welfareSelected) "签到内容尚未加载，停留当前页面继续识别"
                    else "福利导航尚未识别完成，停留当前页面继续识别",
                )
                continue
            }

            if (ctx.elapsedRealtime() - lastGiftTapAt >= 6_000 && giftRetryCount < 2) {
                val gift = ctx.waitUntil(1_200, 300) { screen ->
                    GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                }
                if (gift != null) {
                    giftRetryCount++
                    lastGiftTapAt = ctx.elapsedRealtime()
                    ctx.log("福利尚未加载且礼盒仍在，重新识别后再次点击")
                    ctx.device.tap(gift.point.x, gift.point.y)
                    continue
                }
            }

            ctx.log("礼盒已点击，页面仍在加载，继续重新截图识别")
        }
        return false
    }

    private suspend fun waitForCheckInNavigationText(
        ctx: BotContext,
        targets: List<String>,
        timeoutMs: Long,
    ): ScreenTextMatch? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot() ?: return null
            val width = screen.width
            val height = screen.height
            val match = try {
                withTimeoutOrNull(8_000) {
                    targets.firstNotNullOfOrNull { target ->
                        // These labels have fixed layout roles. Recognize only
                        // their live regions rather than OCRing the full game.
                        val region = when (target) {
                            "签到" -> SearchRegion(
                                (height * 0.03f / width).coerceIn(0f, 1f), 0.12f,
                                (height * 0.40f / width).coerceIn(0f, 1f), 0.29f,
                            )
                            "福利" -> SearchRegion(
                                (height * 0.76f / width).coerceIn(0f, 1f), 0.88f,
                                (height * 1.17f / width).coerceIn(0f, 1f), 1f,
                            )
                            else -> return@firstNotNullOfOrNull null
                        }
                        ScreenTextFinder.findAllInRegion(screen, listOf(target), region)
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
        return null
    }

    private suspend fun tapFromLeft(ctx: BotContext, x: Float, y: Float) {
        val size = ctx.device.screenSize()
        val scale = size.y / 525f
        ctx.device.tap(
            (x * scale).toInt().coerceIn(0, size.x - 1),
            (y * scale).toInt().coerceIn(0, size.y - 1),
        )
    }

    private suspend fun finish(ctx: BotContext, checked: Boolean, detail: String): TaskResult {
        if (keepWelfareOpenForSupply) {
            val specialActionVisible = ctx.waitUntil(1_500, 350) { screen ->
                if (WelfareNavigationDetector.hasSpecialActionTab(screen)) center(screen) else null
            } != null
            if (specialActionVisible) {
                return TaskResult(title, checked, detail)
            }
        }
        if (isGameHud(ctx)) {
            ctx.log("已在游戏主界面，不再点击左上角返回")
            return TaskResult(title, checked, detail)
        }
        ctx.log("退出福利页")
        tapFromLeft(ctx, 45f, 27f)
        repeat(5) {
            if (isGameHud(ctx)) {
                return TaskResult(title, checked, detail)
            }
        }
        return TaskResult(title, false, "$detail；未能返回游戏主界面")
    }

    private suspend fun returnToGame(ctx: BotContext): Boolean {
        if (isGameHud(ctx)) return true
        ctx.log("点击左上角返回游戏主界面")
        tapFromLeft(ctx, 45f, 27f)
        repeat(8) {
            if (isGameHud(ctx)) return true
        }
        return false
    }

    private suspend fun isGameHud(ctx: BotContext): Boolean =
        GameHudNavigator.ensurePlainHud(ctx)
}
