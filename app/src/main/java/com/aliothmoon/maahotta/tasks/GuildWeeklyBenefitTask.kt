package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.GuildScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.ScreenTextMatch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Claims the once-per-week benefit reached from the left-side 福利 tab. */
class GuildWeeklyBenefitTask(
    private val keepGuildOpenForRewards: Boolean,
) : GameTask {
    override val id = "guild_weekly_benefit"
    override val title = "领取公会周奖励"

    private val popupOutside = RelPoint(0.50f, 0.13f)

    override suspend fun run(ctx: BotContext): TaskResult {
        val rewardPopup = ctx.templates.get("mail_reward_popup")
            ?: return TaskResult(title, false, "通用奖励弹窗模板未载入")
        val openTemplate = ctx.templates.get("guild_weekly_open")
            ?: return TaskResult(title, false, "公会周奖励 OPEN 模板未载入")

        if (!openWeeklyBenefitPage(ctx)) {
            return failAndExit(ctx, "未能进入公会福利页")
        }

        val claimTarget = findClaimTarget(ctx, openTemplate)
        if (claimTarget == null) {
            ctx.log("公会福利页未识别到 OPEN 或领取红点，本周奖励已经领取")
            return finish(ctx, "本周奖励已领取")
        }

        var target: Point = requireNotNull(claimTarget)
        var popup: MatchResult? = null
        for (attempt in 1..3) {
            ctx.log(
                if (attempt == 1) "识别到公会福利 OPEN，点击领取周奖励"
                else "周奖励弹窗尚未出现，重新识别后再次点击",
            )
            ctx.device.tap(target.x, target.y)
            popup = waitForRewardPopup(ctx, rewardPopup, 4_000)
            if (popup != null) break

            // The page may still be loading. Re-read the actual control rather
            // than repeating the previous coordinate.
            val refreshed = findClaimTarget(ctx, openTemplate)
            if (refreshed != null) {
                target = refreshed
            } else {
                ctx.log("OPEN 已消失，奖励弹窗可能仍在加载，继续等待")
                popup = waitForRewardPopup(ctx, rewardPopup, 5_000)
                break
            }
        }
        if (popup == null) {
            return stopUncertain(ctx, "点击 OPEN 后未识别到周奖励弹窗")
        }

        var closed = false
        repeat(3) { attempt ->
            if (!closed) {
                ctx.log(
                    if (attempt == 0) "识别到周奖励弹窗，点击白框外关闭"
                    else "周奖励弹窗仍在，重新点击白框外关闭",
                )
                ctx.tap(popupOutside, 0)
                closed = waitForPopupGone(ctx, rewardPopup, 3_000) &&
                    waitForWeeklyPage(ctx, 3_000) != null
            }
        }
        if (!closed) {
            return stopUncertain(ctx, "点击白框外后未确认周奖励弹窗关闭")
        }

        // A successful claim must remove both the OPEN label and its red dot.
        // Sample multiple frames because the disabled next-week emblem flashes.
        if (findClaimTarget(ctx, openTemplate) != null) {
            return stopUncertain(ctx, "关闭弹窗后仍识别到 OPEN")
        }
        return finish(ctx, "周奖励领取成功")
    }

    private suspend fun openWeeklyBenefitPage(ctx: BotContext): Boolean {
        if (waitForWeeklyPage(ctx, 900) != null) return true

        var welfare = waitForText(
            ctx = ctx,
            targets = listOf("福利"),
            timeoutMs = 1_500,
            accepted = { point, width, height ->
                point.x < width * 0.28f && point.y in (height * 0.12f).toInt()..(height * 0.48f).toInt()
            },
        )
        if (welfare == null) {
            if (GuildNavigation.isDailyPage(ctx, 1_200)) {
                ctx.log("已确认仍在公会日常页，福利文字未命中，点击左侧福利区域")
                ctx.tap(Layout.guildWelfareTab, 0)
                if (waitForWeeklyPage(ctx, 5_000) != null) return true
            }

            if (!GuildNavigation.openDaily(ctx)) return false
            welfare = waitForText(
                ctx = ctx,
                targets = listOf("福利"),
                timeoutMs = 3_000,
                accepted = { point, width, height ->
                    point.x < width * 0.28f && point.y in (height * 0.12f).toInt()..(height * 0.48f).toInt()
                },
            )
            if (welfare == null && GuildNavigation.isDailyPage(ctx, 1_200)) {
                ctx.log("重新确认公会日常页后，点击左侧福利区域")
                ctx.tap(Layout.guildWelfareTab, 0)
                return waitForWeeklyPage(ctx, 5_000) != null
            }
        }
        var welfareButton = welfare ?: return false

        repeat(3) { attempt ->
            ctx.log(if (attempt == 0) "识别到公会左侧福利，点击进入" else "公会福利页未加载，重新识别后再次点击福利")
            ctx.device.tap(welfareButton.point.x, welfareButton.point.y)
            if (waitForWeeklyPage(ctx, 4_000) != null) return true

            val refreshed = waitForText(
                ctx = ctx,
                targets = listOf("福利"),
                timeoutMs = 1_500,
                accepted = { point, width, height ->
                    point.x < width * 0.28f && point.y in (height * 0.12f).toInt()..(height * 0.48f).toInt()
                },
            )
            if (refreshed != null) {
                welfareButton = refreshed
            } else {
                ctx.log("福利入口已消失，页面可能仍在加载，继续等待")
                return waitForWeeklyPage(ctx, 5_000) != null
            }
        }
        return false
    }

    private suspend fun findClaimTarget(ctx: BotContext, template: Bitmap): Point? =
        ctx.waitUntil(2_800, 320) { screen ->
            GuildScreenDetector.findWeeklyOpen(screen, template)
        }?.point

    private suspend fun waitForWeeklyPage(ctx: BotContext, timeoutMs: Long): ScreenTextMatch? =
        waitForText(
            ctx = ctx,
            targets = listOf("公会福利", "每周一凌晨5点结算福利", "基础福利"),
            timeoutMs = timeoutMs,
            accepted = { point, width, height ->
                point.x > width * 0.18f && point.y < height * 0.82f
            },
        )

    private suspend fun waitForText(
        ctx: BotContext,
        targets: List<String>,
        timeoutMs: Long,
        accepted: (Point, Int, Int) -> Boolean,
    ): ScreenTextMatch? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen != null) {
                val width = screen.width
                val height = screen.height
                val match = try {
                    ScreenTextFinder.find(screen, targets)
                } catch (_: Throwable) {
                    null
                } finally {
                    screen.recycle()
                }
                if (match != null && accepted(match.point, width, height)) {
                    val size = ctx.device.screenSize()
                    return match.copy(
                        point = Point(
                            (match.point.x.toDouble() * size.x / width).roundToInt(),
                            (match.point.y.toDouble() * size.y / height).roundToInt(),
                        ),
                    )
                }
            }
            delay(350)
        }
        return null
    }

    private suspend fun waitForRewardPopup(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        GuildScreenDetector.findWeeklyRewardPopup(screen, template)
    }

    private suspend fun waitForPopupGone(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        var consecutiveGone = 0
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen != null) {
                val popup = try {
                    GuildScreenDetector.findWeeklyRewardPopup(screen, template)
                } finally {
                    screen.recycle()
                }
                if (popup == null) {
                    consecutiveGone++
                    if (consecutiveGone >= 2) return true
                } else {
                    consecutiveGone = 0
                }
            }
            delay(300)
        }
        return false
    }

    private suspend fun finish(ctx: BotContext, detail: String): TaskResult {
        if (ctx.preserveTaskPage) return TaskResult(title, true, "$detail；保留公会福利页，由下一任务选择路径")
        if (keepGuildOpenForRewards) {
            return TaskResult(title, true, "$detail；继续领取公会荣耀奖励")
        }
        val exited = GuildNavigation.exitToGameHud(ctx)
        return TaskResult(
            title,
            exited,
            if (exited) "$detail；已退出到游戏主界面" else "$detail；未能退出到游戏主界面",
        )
    }

    private suspend fun failAndExit(ctx: BotContext, detail: String): TaskResult {
        GuildNavigation.exitToGameHud(ctx)
        return TaskResult(title, false, detail)
    }

    private suspend fun stopUncertain(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }
}
