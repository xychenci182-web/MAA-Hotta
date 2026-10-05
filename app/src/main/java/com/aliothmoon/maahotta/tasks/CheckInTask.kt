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
import java.time.LocalDate

class CheckInTask(
    private val keepWelfareOpenForSupply: Boolean = false,
    private val dayOfWeek: Int = LocalDate.now().dayOfWeek.value,
) : GameTask {
    override val id = "check_in"
    override val title = "每日签到"

    override suspend fun run(ctx: BotContext): TaskResult {
        if (dayOfWeek !in 1..7) return TaskResult(title, false, "本地星期无效，停止签到", retryable = false)
        ctx.log("按日常启动时的本地星期选择签到 DAY $dayOfWeek")
        val hudMenu = ctx.hudTemplates()
            ?: return TaskResult(title, false, "主界面菜单模板未载入")
        var alreadyOnPage = ctx.waitUntil(700, 250) { screen ->
            if (CheckInScreenDetector.isSignInPage(screen)) center(screen) else null
        } != null
        if (!alreadyOnPage && ctx.preserveTaskPage) {
            // Navigation already confirmed a welfare page. Select its tab without reopening the gift.
            alreadyOnPage = openSignInAfterGift(ctx, hudMenu)
            if (!alreadyOnPage) return failUnknown(ctx, "福利页内未能确认签到页")
        }
        if (!alreadyOnPage) {
            var entered = false
            var lastFailure = "未进入福利签到页"
            for (attempt in 1..2) {
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
                if (!ctx.tryTaskStep("$id:gift")) {
                    return TaskResult(title, false, "礼盒入口已重试一次，停止重复点击")
                }
                ctx.device.tap(gift.point.x, gift.point.y)
                if (openSignInAfterGift(ctx, hudMenu)) {
                    entered = true
                    break
                }
                lastFailure = "等待并重试后仍未进入福利签到页"
                if (attempt == 2) break
                ctx.log("$lastFailure，返回游戏主界面重试一次")
                if (!returnToGame(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回游戏主界面")
                }
            }
            if (!entered) return TaskResult(title, false, "$lastFailure；初次尝试及一次重试均未成功")
        }

        val rewardPopupTemplate = ctx.templates.get("mail_reward_popup")
        var dayHighlighted = false
        val claim = ctx.waitUntil(8_000, 500) { screen ->
            if (isRewardPopup(screen, rewardPopupTemplate)) return@waitUntil null
            val day = CheckInScreenDetector.findDay(screen, dayOfWeek) ?: return@waitUntil null
            // Locate and inspect this DAY on the same frame, before submitting any click.
            dayHighlighted = CheckInScreenDetector.hasDayHighlight(screen, dayOfWeek)
            day
        } ?: return failUnknown(ctx, "未能确认签到 DAY $dayOfWeek 的位置，不点击其他 DAY")
        if (!dayHighlighted) {
            ctx.log("签到 DAY $dayOfWeek 没有黄色高亮，不点击，直接结束签到任务")
            return finish(ctx, true, "签到 DAY $dayOfWeek 无黄色高亮，已结束")
        }
        ctx.log("签到 DAY $dayOfWeek 有黄色高亮，点击 (${claim.point.x},${claim.point.y})，等待奖励弹窗")
        var rewardAppeared = false
        ctx.onTaskFailureRecovery { screen, _ ->
            val popupVisible = isRewardPopup(screen, rewardPopupTemplate)
            if (popupVisible) rewardAppeared = true
            when {
                popupVisible -> closeRewardPopup(ctx, rewardPopupTemplate)
                rewardAppeared && CheckInScreenDetector.isSignInPage(screen) -> {
                    if (ctx.safety.pendingStepId == "$id:claim_day_$dayOfWeek") ctx.confirmActionResult()
                    finish(ctx, true, "签到 DAY $dayOfWeek 奖励弹窗已关闭（全局验证）")
                }
                else -> null
            }
        }
        ctx.markActionSubmitted("$id:claim_day_$dayOfWeek")
        ctx.device.tap(claim.point.x, claim.point.y)

        // After the single DAY click, only observe the reward overlay and its dismissal.
        rewardAppeared = ctx.waitUntil(12_000, 500) { screen ->
            if (isRewardPopup(screen, rewardPopupTemplate)) center(screen) else null
        } != null
        if (!rewardAppeared) {
            return failUnknown(ctx, "点击 DAY $dayOfWeek 后未识别到奖励弹窗，停止任务，不重复领取")
        }
        return closeRewardPopup(ctx, rewardPopupTemplate)
    }

    private suspend fun closeRewardPopup(ctx: BotContext, rewardPopupTemplate: Bitmap?): TaskResult {
        repeat(2) { attempt ->
            val size = ctx.device.screenSize()
            if (!ctx.tryTaskStep("$id:reward_close")) {
                return failUnknown(ctx, "签到奖励弹窗关闭已重试一次，停止重复点击")
            }
            ctx.log("已识别奖励弹窗，点击上方空白处关闭（第 ${attempt + 1}/2 次）")
            ctx.device.tap(size.x / 2, (size.y * 0.12f).toInt())
            val closed = ctx.waitUntil(3_000, 500) { screen ->
                if (!isRewardPopup(screen, rewardPopupTemplate)) center(screen) else null
            } != null
            if (closed) {
                if (ctx.safety.pendingStepId == "$id:claim_day_$dayOfWeek") ctx.confirmActionResult()
                ctx.log("签到奖励弹窗已关闭，完成 DAY $dayOfWeek，不再检查对勾")
                return finish(ctx, true, "签到 DAY $dayOfWeek 奖励弹窗已关闭")
            }
            if (attempt < 1) ctx.log("签到奖励弹窗仍在，重试一次点击上方空白处关闭")
        }
        return failUnknown(ctx, "签到奖励弹窗关闭重试一次后仍未确认消失，停止任务")
    }

    private suspend fun failUnknown(ctx: BotContext, reason: String): TaskResult {
        ctx.log(reason)
        ctx.saveTaskDiagnostic("check_in")
        return TaskResult.uncertain(title, reason)
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
        val navigationTapCounts = mutableMapOf<String, Int>()

        while (ctx.elapsedRealtime() < deadline) {
            val page = ctx.waitUntil(700, 250) { screen ->
                if (CheckInScreenDetector.isSignInPage(screen)) center(screen) else null
            }
            if (page != null) return true

            val now = ctx.elapsedRealtime()
            val canRetryWelfare = !welfareSelected || now - lastWelfareTapAt >= 8_000
            val targets = (if (canRetryWelfare) listOf("签到", "福利") else listOf("签到"))
                .filter { (navigationTapCounts[it] ?: 0) < 2 }
            val action = waitForCheckInNavigationText(ctx, targets, 3_000)
            if (action != null) {
                val step = if (action.target == "福利") "$id:welfare_tab" else "$id:sign_in"
                if (!ctx.tryTaskStep(step)) return false
                navigationTapCounts[action.target] = (navigationTapCounts[action.target] ?: 0) + 1
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

            if (ctx.elapsedRealtime() - lastGiftTapAt >= 6_000 && giftRetryCount < 1) {
                val gift = ctx.waitUntil(1_200, 300) { screen ->
                    GameScreenDetector.findGiftHudIcon(screen, hudMenu)
                }
                if (gift != null) {
                    if (!ctx.tryTaskStep("$id:gift")) return false
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

    private suspend fun finish(ctx: BotContext, checked: Boolean, detail: String): TaskResult {
        if (keepWelfareOpenForSupply) {
            ctx.log("签到完成，保留福利页，继续执行供给")
            return TaskResult(title, checked, detail)
        }
        ctx.log("签到完成，点击左上角退出福利页，不再检查游戏主界面")
        if (!tapFromLeft(ctx, 45f, 27f)) {
            return TaskResult(title, false, "$detail；退出福利页已重试一次，未再点击")
        }
        return TaskResult(title, checked, detail)
    }

    private suspend fun returnToGame(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUD)
        if (isGameHud(ctx)) return true
        ctx.log("点击左上角返回游戏主界面")
        if (!tapFromLeft(ctx, 45f, 27f)) return false
        repeat(8) {
            if (isGameHud(ctx)) return true
        }
        return false
    }

    private suspend fun isGameHud(ctx: BotContext): Boolean =
        GameHudNavigator.ensurePlainHud(ctx)
}
