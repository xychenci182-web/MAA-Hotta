package com.aliothmoon.maahotta.tasks

import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.NavigationGoal
import com.aliothmoon.maahotta.vision.PageState
import kotlinx.coroutines.delay

class MailTask : GameTask {
    override val id = "mail"
    override val title = "领取邮件"

    override suspend fun run(ctx: BotContext): TaskResult {
        if (!ctx.preserveTaskPage && !TaskNavigationMachine.reach(ctx, NavigationGoal.MAIL)) return stop(ctx, "无法进入邮件页")
        var claimAt = 0L
        var rewardSeen = false
        var rewardFrames = 0
        var closeAttempts = 0
        var lastCloseAt = 0L
        var mailFrames = 0
        val deadline = ctx.elapsedRealtime() + 30_000
        while (ctx.elapsedRealtime() < deadline) {
            val page = TaskNavigationMachine.observe(ctx, NavigationGoal.MAIL)
            when (page.state) {
                PageState.REWARD -> {
                    rewardFrames++
                    if (claimAt != 0L && rewardFrames >= 2) rewardSeen = true
                    mailFrames = 0
                    if ((claimAt == 0L || rewardSeen) && ctx.elapsedRealtime() - lastCloseAt >= 1_500) {
                        if (closeAttempts >= 3) return stop(ctx, "邮件奖励弹层关闭三次仍未消失")
                        ctx.log("状态：邮件奖励弹层，点击白框外关闭")
                        ctx.tap(RelPoint(0.50f, 0.10f), 0)
                        closeAttempts++
                        lastCloseAt = ctx.elapsedRealtime()
                    }
                }
                PageState.MAIL -> {
                    rewardFrames = 0
                    mailFrames++
                    if (claimAt == 0L) {
                        val claim = page.controls["claim"] ?: return stop(ctx, "邮件页未确认一键领取按钮")
                        ctx.log("状态：邮件页，点击一键领取")
                        ctx.markActionSubmitted("${id}_claim_all")
                        ctx.device.tap(claim.point.x, claim.point.y)
                        claimAt = ctx.elapsedRealtime()
                        rewardSeen = false
                        mailFrames = 0
                    } else if (mailFrames >= 2 && rewardSeen) {
                        ctx.confirmActionResult()
                        ctx.log("邮件任务完成，保留邮件页；下一任务按当前状态选择路径")
                        return TaskResult(title, true, "已领取邮件奖励")
                    }
                }
                else -> {
                    mailFrames = 0
                    rewardFrames = 0
                } // Loading or unexpected page: never repeat the old claim coordinate.
            }
            delay(400)
        }
        return stop(ctx, if (claimAt != 0L && !rewardSeen) {
            "点击一键领取后未出现邮件奖励弹窗，也无明确无奖励或已领取证据，结果不明"
        } else {
            "领取邮件后页面或奖励结果无法确认"
        })
    }

    private suspend fun stop(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult.uncertain(title, detail)
    }
}
