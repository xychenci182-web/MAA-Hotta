package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.GuildScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult

class GuildDonateTask(private val keepGuildOpenForRewards: Boolean) : GameTask {
    override val id = "guild_donate"
    override val title = "公会捐赠"

    override suspend fun run(ctx: BotContext): TaskResult {
        val donateNow = ctx.templates.get("guild_donate_now")
            ?: return TaskResult(title, false, "立即捐献按钮模板未载入")
        val confirmText = ctx.templates.get("guild_donate_confirm_text")
            ?: return TaskResult(title, false, "确认捐献文字模板未载入")
        val confirmButton = ctx.templates.get("guild_donate_confirm")
            ?: return TaskResult(title, false, "捐献确定按钮模板未载入")
        val donateZero = ctx.templates.get("guild_donate_zero")
            ?: return TaskResult(title, false, "可捐献次数0/1模板未载入")
        val donateOne = ctx.templates.get("guild_donate_one")
            ?: return TaskResult(title, false, "可捐献次数1/1模板未载入")

        if (!GuildNavigation.openDaily(ctx)) {
            return failAndExit(ctx, "未能从游戏主界面进入公会日常")
        }

        var alreadyDonated = false
        val initialState = ctx.waitUntil(3_000, 350) { screen ->
            GuildScreenDetector.findDonateZero(screen, donateZero, donateOne)?.also {
                alreadyDonated = true
            } ?: GuildScreenDetector.findDonateNow(screen, donateNow)
        } ?: return failAndExit(ctx, "公会日常页未识别到捐献状态")
        if (alreadyDonated) {
            ctx.log("可捐献次数已经是0/1，今日捐赠已完成")
            return finish(ctx, "可捐献次数已是0/1")
        }

        var donateButton = initialState
        var confirmation: MatchResult? = null
        for (attempt in 1..3) {
            ctx.log(if (attempt == 1) "识别到立即捐献，点击" else "捐献弹窗未出现，重新识别后再次点击立即捐献")
            ctx.device.tap(donateButton.point.x, donateButton.point.y)
            confirmation = waitForConfirmation(ctx, confirmText, confirmButton, 2_500)
            if (confirmation != null) break

            val refreshed = waitForDonateNow(ctx, donateNow, 1_500)
            if (refreshed != null) {
                donateButton = refreshed
            } else {
                ctx.log("立即捐献按钮已消失，弹窗可能仍在加载，继续等待")
                confirmation = waitForConfirmation(ctx, confirmText, confirmButton, 4_000)
                break
            }
        }
        if (confirmation == null) {
            return failAndExit(ctx, "未同时识别到确认捐献文字和确定按钮")
        }
        var confirmButtonMatch = requireNotNull(confirmation)
        var donated = false
        for (attempt in 1..3) {
            ctx.log(if (attempt == 1) "已确认捐献弹窗内容，点击确定" else "捐献结果未出现，重新识别后再次点击确定")
            ctx.device.tap(confirmButtonMatch.point.x, confirmButtonMatch.point.y)
            donated = waitForDonateZero(ctx, donateZero, donateOne, 4_000) != null
            if (donated) break

            val refreshed = waitForConfirmation(ctx, confirmText, confirmButton, 1_500)
            if (refreshed != null) {
                confirmButtonMatch = refreshed
            } else {
                ctx.log("确定按钮已消失，捐献结果可能仍在加载，继续等待")
                donated = waitForDonateZero(ctx, donateZero, donateOne, 5_000) != null
                break
            }
        }
        if (!donated) {
            ctx.log("已点击捐献确定，但未确认次数变为0/1，停止后续任务并保存当前画面")
            ctx.saveTaskDiagnostic(id)
            return TaskResult(title, false, "捐献结果无法确认", retryable = false)
        }
        ctx.log("可捐献次数已从1/1变为0/1")
        return finish(ctx, "捐献成功，可捐献次数0/1")
    }

    private suspend fun finish(ctx: BotContext, detail: String): TaskResult {
        if (keepGuildOpenForRewards) {
            return TaskResult(title, true, "$detail；继续处理公会奖励子任务")
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

    private suspend fun waitForDonateNow(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findDonateNow(screen, template) }

    private suspend fun waitForConfirmation(
        ctx: BotContext,
        textTemplate: Bitmap,
        buttonTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        val text = GuildScreenDetector.findConfirmText(screen, textTemplate)
        val button = GuildScreenDetector.findConfirmButton(screen, buttonTemplate)
        if (text != null) button else null
    }

    private suspend fun waitForDonateZero(
        ctx: BotContext,
        zeroTemplate: Bitmap,
        oneTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        GuildScreenDetector.findDonateZero(screen, zeroTemplate, oneTemplate)
    }
}
