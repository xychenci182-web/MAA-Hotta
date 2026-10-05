package com.aliothmoon.maahotta.tasks

import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
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
        var rewardAppeared = false
        var rewardFrames = 0
        var closeAttempts = 0
        var mailFrames = 0
        var claimResult: TaskResult? = null
        suspend fun finishMail(): TaskResult {
            var hudFrames = 0
            var previousMenu: com.aliothmoon.maahotta.vision.MatchResult? = null
            while (true) {
                val page = TaskNavigationMachine.observe(ctx, NavigationGoal.HUD)
                val menu = page.controls["menu"]
                if (page.state == PageState.HUD && menu != null) {
                    val previous = previousMenu
                    val tolerance = ctx.device.screenSize().y * 8f / 528
                    hudFrames = if (previous != null &&
                        kotlin.math.abs(menu.point.x - previous.point.x) <= tolerance &&
                        kotlin.math.abs(menu.point.y - previous.point.y) <= tolerance
                    ) hudFrames + 1 else 1
                    previousMenu = menu
                    if (hudFrames >= 2) {
                        ctx.rememberMailExitMenu(menu)
                        ctx.log("邮件任务完成：右上角菜单已确认返回游戏主界面，下一任务复用此结果")
                        return checkNotNull(claimResult)
                    }
                } else {
                    hudFrames = 0
                    previousMenu = null
                    if (page.state == PageState.MAIL || page.state == PageState.SOCIAL) {
                        if (!ctx.tryTaskStep("$id:social_exit")) return stop(ctx, "点击社交退出重试一次后仍未返回游戏主界面")
                        ctx.log("邮件领取处理完成，点击左上角社交退出")
                        ctx.tap(Layout.back, 0)
                    }
                }
                delay(400)
            }
        }
        suspend fun continueMail(): TaskResult {
            if (claimResult != null) return finishMail()
            while (true) {
                val page = TaskNavigationMachine.observe(ctx, NavigationGoal.MAIL)
                when (page.state) {
                    PageState.REWARD -> {
                        if (claimAt != 0L) rewardAppeared = true
                        rewardFrames++
                        if (claimAt != 0L && rewardFrames >= 2) rewardSeen = true
                        mailFrames = 0
                        if (claimAt == 0L || rewardSeen) {
                            if (closeAttempts >= 2) return stop(ctx, "邮件奖励弹层关闭重试一次后仍未消失")
                            if (!ctx.tryTaskStep("$id:reward_close")) return stop(ctx, "邮件奖励弹层关闭已重试一次，停止重复点击")
                            ctx.log("状态：邮件奖励弹层，点击白框外关闭")
                            ctx.tap(RelPoint(0.50f, 0.10f), 0)
                            closeAttempts++
                        }
                    }
                    PageState.MAIL -> {
                        rewardFrames = 0
                        mailFrames++
                        if (claimAt == 0L) {
                            val claim = page.controls["claim"] ?: return stop(ctx, "邮件页未确认一键领取按钮")
                            ctx.log("状态：邮件页，点击一键领取")
                            ctx.markActionSubmitted("${id}_claim_all")
                            claimAt = ctx.elapsedRealtime()
                            ctx.device.tap(claim.point.x, claim.point.y)
                            rewardSeen = false
                            mailFrames = 0
                        } else if (mailFrames >= 2 && rewardSeen) {
                            ctx.confirmActionResult()
                            claimResult = TaskResult(title, true, "已领取邮件奖励并确认返回游戏主界面")
                            return finishMail()
                        } else if (!rewardAppeared && mailFrames >= 3 && ctx.elapsedRealtime() - claimAt >= 2_000) {
                            // The game leaves an empty mailbox unchanged after Claim All;
                            // it shows neither a toast nor a result popup. Submit once and
                            // allow delayed rewards before accepting the stable mail page.
                            ctx.confirmActionResult()
                            ctx.log("一键领取后邮件页保持稳定，等待2秒未出现奖励，当前无可领取邮件")
                            claimResult = TaskResult.alreadyCompleted(title, "无可领取邮件奖励，已确认返回游戏主界面")
                            return finishMail()
                        }
                    }
                    else -> {
                        mailFrames = 0
                        rewardFrames = 0
                    } // Loading or unexpected page: never repeat the old claim coordinate.
                }
                delay(400)
            }
        }
        ctx.onTaskFailureRecovery { _, page ->
            if (claimAt != 0L && page.state in setOf(PageState.MAIL, PageState.REWARD, PageState.SOCIAL, PageState.HUD)) {
                continueMail()
            } else null
        }
        return continueMail()
    }

    private suspend fun stop(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult.uncertain(title, detail)
    }
}
