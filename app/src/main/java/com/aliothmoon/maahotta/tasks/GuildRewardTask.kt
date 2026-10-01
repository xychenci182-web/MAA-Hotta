package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.GuildScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult

class GuildRewardTask : GameTask {
    override val id = "guild_rewards"
    override val title = "领取公会荣耀奖励"

    private val rewardPoints = arrayOf(
        RelPoint(0.600f, 0.190f),
        RelPoint(0.668f, 0.190f),
        RelPoint(0.736f, 0.190f),
        RelPoint(0.804f, 0.190f),
        RelPoint(0.872f, 0.190f),
        RelPoint(0.940f, 0.190f),
    )

    override suspend fun run(ctx: BotContext): TaskResult {
        val infoTab = ctx.templates.get("guild_info_tab")
            ?: return TaskResult(title, false, "公会信息页签模板未载入")
        val rewardsRow = ctx.templates.get("guild_rewards_row")
            ?: return TaskResult(title, false, "公会奖励栏模板未载入")

        var row = openInfoPage(ctx, infoTab, rewardsRow)
        if (row == null) {
            ctx.log("当前公会页面暂未识别到信息页签，停留原页面继续重新截图")
            row = openInfoPage(ctx, infoTab, rewardsRow)
        }
        if (row == null) {
            ctx.log("连续两轮未识别到公会信息页，才执行重新进入公会的恢复流程")
            if (!GuildNavigation.openDaily(ctx)) {
                return TaskResult(title, false, "未能重新进入公会")
            }
            row = openInfoPage(ctx, infoTab, rewardsRow)
        }
        if (row == null) {
            GuildNavigation.exitToGameHud(ctx)
            return TaskResult(title, false, "未能进入公会信息页或识别奖励栏")
        }

        var currentDots = currentRewardDots(ctx, rewardsRow)
            ?: return finishAfterFailure(ctx, "未能取得公会奖励红点状态")
        val targetCount = currentDots.count { it }
        ctx.log("检测到 $targetCount 个可领取公会奖励红点")

        var claimed = 0
        val claimedIndices = mutableSetOf<Int>()
        while (claimed < rewardPoints.size) {
            val index = currentDots.indexOfFirst { it }
            if (index < 0) break
            if (index in claimedIndices) {
                return stopUncertain(ctx, "第 ${index + 1} 个奖励红点在领取后重新出现")
            }
            var updatedDots: BooleanArray? = null
            for (attempt in 1..2) {
                ctx.log(
                    if (attempt == 1) "点击第 ${index + 1} 个带红点的公会奖励"
                    else "已确认第 ${index + 1} 个红点仍在，重新点击",
                )
                ctx.tap(rewardPoints[index], 0)
                updatedDots = waitForRewardDotGone(ctx, rewardsRow, index, 2_000)
                if (updatedDots != null) break

                // A missing reward row could mean a popup or a page transition.
                // Only tap again when the same dot is still on the rewards page.
                val refreshed = currentRewardDots(ctx, rewardsRow)
                    ?: return stopUncertain(ctx, "点击第 ${index + 1} 个奖励后无法确认仍在公会奖励页")
                if (!refreshed[index]) {
                    updatedDots = refreshed
                    break
                }
            }
            if (updatedDots == null) {
                return stopUncertain(ctx, "第 ${index + 1} 个奖励点击后红点仍在")
            }
            claimed++
            claimedIndices += index
            currentDots = requireNotNull(updatedDots)
        }

        if (currentDots.any { it }) {
            return stopUncertain(ctx, "领取后仍有 ${currentDots.count { it }} 个奖励红点")
        }

        val exited = GuildNavigation.exitToGameHud(ctx)
        return TaskResult(
            title,
            exited,
            if (exited) "已领取 $claimed 个奖励并退出到游戏主界面" else "已领取 $claimed 个奖励，但未能退出到游戏主界面",
        )
    }

    private suspend fun openInfoPage(
        ctx: BotContext,
        infoTemplate: Bitmap,
        rowTemplate: Bitmap,
    ): MatchResult? {
        val existingRow = waitForRewardsRow(ctx, rowTemplate, 800)
        if (existingRow != null) {
            ctx.log("当前已在公会信息页，直接读取奖励栏")
            return existingRow
        }

        val infoButton = waitForInfoTab(ctx, infoTemplate, 4_000)
        if (infoButton == null && GuildNavigation.isDailyPage(ctx, 1_200)) {
            ctx.log("已确认仍在公会日常页，信息模板未命中，点击下方信息区域")
            ctx.tap(Layout.guildInfoTab, 0)
            return waitForRewardsRow(ctx, rowTemplate, 5_000)
        }
        var infoButtonMatch = infoButton ?: return null
        repeat(3) { attempt ->
            ctx.log(if (attempt == 0) "点击公会下方信息" else "奖励栏未加载，重新识别后再次点击信息")
            ctx.device.tap(infoButtonMatch.point.x, infoButtonMatch.point.y)
            val row = waitForRewardsRow(ctx, rowTemplate, 4_000)
            if (row != null) return row

            val refreshed = waitForInfoTab(ctx, infoTemplate, 1_500)
            if (refreshed != null) {
                infoButtonMatch = refreshed
            } else {
                ctx.log("信息页签已消失，奖励栏可能仍在加载，继续等待")
                return waitForRewardsRow(ctx, rowTemplate, 5_000)
            }
        }
        return null
    }

    private suspend fun currentRewardDots(ctx: BotContext, rowTemplate: Bitmap): BooleanArray? {
        var dots: BooleanArray? = null
        val row = ctx.waitUntil(3_000, 300) { screen ->
            GuildScreenDetector.findRewardsRow(screen, rowTemplate)?.also {
                dots = GuildScreenDetector.rewardRedDots(screen)
            }
        }
        return if (row != null) dots else null
    }

    private suspend fun waitForRewardDotGone(
        ctx: BotContext,
        rowTemplate: Bitmap,
        index: Int,
        timeoutMs: Long,
    ): BooleanArray? {
        var dots: BooleanArray? = null
        val row = ctx.waitUntil(timeoutMs, 300) { screen ->
            if (GuildScreenDetector.findRewardRedDot(screen, index) != null) {
                return@waitUntil null
            }
            val detectedRow = GuildScreenDetector.findRewardsRow(screen, rowTemplate)
            if (detectedRow != null) {
                val latestDots = GuildScreenDetector.rewardRedDots(screen)
                if (!latestDots[index]) {
                    dots = latestDots
                    return@waitUntil detectedRow
                }
            }
            null
        }
        return if (row != null) dots else null
    }

    private suspend fun finishAfterFailure(ctx: BotContext, detail: String): TaskResult {
        GuildNavigation.exitToGameHud(ctx)
        return TaskResult(title, false, detail)
    }

    private suspend fun stopUncertain(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }

    private suspend fun waitForInfoTab(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findInfoTab(screen, template) }

    private suspend fun waitForRewardsRow(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findRewardsRow(screen, template) }
}
