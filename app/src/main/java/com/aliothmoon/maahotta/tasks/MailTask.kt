package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.MailScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import kotlinx.coroutines.delay

class MailTask(
    private val onNoRewardPopup: suspend () -> Unit = {},
) : GameTask {
    override val id = "mail"
    override val title = "领取邮件"

    private val rewardPopupOutside = RelPoint(0.50f, 0.10f)

    override suspend fun run(ctx: BotContext): TaskResult {
        val hudMenu = ctx.hudTemplates()
            ?: return TaskResult(title, false, "主界面菜单模板未载入")
        val socialMenu = ctx.templates.get("menu_social_entry")
            ?: return TaskResult(title, false, "社交入口模板未载入")
        val mailTab = ctx.templates.get("social_mail_tab")
            ?: return TaskResult(title, false, "社交页邮件入口模板未载入")
        val mailSelected = ctx.templates.get("social_mail_selected")
            ?: return TaskResult(title, false, "邮件页选中状态模板未载入")
        val claimAll = ctx.templates.get("mail_claim_all")
            ?: return TaskResult(title, false, "一键领取按钮模板未载入")
        val rewardPopup = ctx.templates.get("mail_reward_popup")
            ?: return TaskResult(title, false, "邮件奖励弹层模板未载入")

        var social: MatchResult? = waitForSocialMenu(ctx, socialMenu, 900)
        if (social != null) {
            ctx.log("当前右上角菜单已经展开，直接使用已识别到的社交入口")
        } else {
            if (!ensureGameHud(ctx)) {
                return TaskResult(title, false, "开始邮件任务前未识别到游戏主界面")
            }

            val menu = waitForHudMenu(ctx, hudMenu, 3_000)
                ?: return TaskResult(title, false, "游戏主界面未识别到右上角菜单")
            var menuButton = menu
            for (attempt in 1..3) {
                ctx.log(if (attempt == 1) "识别到主界面右上角菜单，点击展开" else "菜单未展开，重新识别后再次点击")
                ctx.device.tap(menuButton.point.x, menuButton.point.y)
                social = waitForSocialMenu(ctx, socialMenu, 3_000)
                if (social != null) break
                val refreshed = waitForHudMenu(ctx, hudMenu, 1_500)
                if (refreshed != null) {
                    menuButton = refreshed
                } else {
                    ctx.log("主界面菜单图标已消失，菜单内容可能仍在加载，继续等待")
                    social = waitForSocialMenu(ctx, socialMenu, 5_000)
                    break
                }
            }
        }

        var socialButton = social
            ?: return failAndExit(ctx, "展开菜单后未识别到社交入口")
        var mail: MatchResult? = null
        for (attempt in 1..3) {
            ctx.log(if (attempt == 1) "识别到社交入口，点击进入" else "社交页未打开，重新识别后再次点击社交")
            ctx.device.tap(socialButton.point.x, socialButton.point.y)
            mail = waitForMailTab(ctx, mailTab, 4_000)
            if (mail != null) break
            val refreshed = waitForSocialMenu(ctx, socialMenu, 1_500)
            if (refreshed != null) {
                socialButton = refreshed
            } else {
                ctx.log("社交入口已消失，社交页可能仍在加载，继续等待邮件入口")
                mail = waitForMailTab(ctx, mailTab, 5_000)
                break
            }
        }

        var mailButton = mail
            ?: return failAndExit(ctx, "点击社交后未识别到左侧邮件入口")
        var claim: MatchResult? = null
        for (attempt in 1..3) {
            ctx.log(if (attempt == 1) "已进入社交页，点击左侧邮件" else "邮件内容未加载，重新识别后再次点击邮件")
            ctx.device.tap(mailButton.point.x, mailButton.point.y)
            claim = waitForMailPage(ctx, mailSelected, claimAll, 4_000)
            if (claim != null) break
            val refreshed = waitForMailTab(ctx, mailTab, 1_500)
            if (refreshed != null) {
                mailButton = refreshed
            } else {
                ctx.log("邮件入口已消失，邮件内容可能仍在加载，继续等待")
                claim = waitForMailPage(ctx, mailSelected, claimAll, 5_000)
                break
            }
        }

        val claimButton = claim
            ?: return failAndExit(ctx, "点击邮件后未同时识别到邮件页和一键领取按钮")
        ctx.log("已进入邮件页，点击一键领取")
        ctx.device.tap(claimButton.point.x, claimButton.point.y)
        delay(600)

        if (waitForRewardPopup(ctx, rewardPopup, 5_000) == null) {
            val stillOnMailPage = waitForMailPage(ctx, mailSelected, claimAll, 3_000) != null
            if (!stillOnMailPage) {
                return stopUncertain(ctx, "点击一键领取后既未识别到奖励弹层，也未确认仍在邮件页")
            }
            ctx.log("点击一键领取后未出现奖励弹窗，记录该账号供后续核实")
            ctx.log("点击左上角社交返回游戏主界面")
            ctx.tap(Layout.back, 700)
            if (!waitForGameHud(ctx, 8_000)) {
                return stopUncertain(ctx, "未出现邮件奖励弹窗，且未能退出到游戏主界面")
            }
            onNoRewardPopup()
            return TaskResult(title, true, "未出现邮件奖励弹窗，账号已记录待核实")
        }
        var returnedToMail = false
        repeat(3) { attempt ->
            if (!returnedToMail) {
                ctx.log(
                    if (attempt == 0) "识别到邮件奖励弹层，点击白框外关闭"
                    else "邮件奖励弹层仍在，重新点击白框外关闭",
                )
                ctx.tap(rewardPopupOutside, 500)
                returnedToMail = waitForMailPageAfterPopup(
                    ctx = ctx,
                    selectedTemplate = mailSelected,
                    claimTemplate = claimAll,
                    popupTemplate = rewardPopup,
                    timeoutMs = 4_000,
                )
            }
        }
        if (!returnedToMail) {
            return stopUncertain(ctx, "点击白框外后未确认返回邮件页")
        }

        ctx.log("奖励弹层已关闭，点击左上角社交返回游戏主界面")
        ctx.tap(Layout.back, 700)
        if (!waitForGameHud(ctx, 8_000)) {
            return stopUncertain(ctx, "点击左上角社交后未识别到游戏主界面")
        }
        return TaskResult(title, true, "已一键领取邮件并退出到游戏主界面")
    }

    private suspend fun ensureGameHud(ctx: BotContext): Boolean {
        if (GameHudNavigator.ensurePlainHud(ctx, 1_500)) return true
        repeat(4) {
            ctx.tap(Layout.back, 650)
            if (GameHudNavigator.ensurePlainHud(ctx, 3_000)) return true
        }
        return false
    }

    private suspend fun failAndExit(ctx: BotContext, detail: String): TaskResult {
        repeat(4) {
            if (GameHudNavigator.isHudOrMenu(ctx)) return TaskResult(title, false, detail)
            ctx.tap(Layout.back, 650)
            if (waitForGameHud(ctx, 2_500)) return TaskResult(title, false, detail)
        }
        return TaskResult(title, false, "$detail；未能返回游戏主界面")
    }

    private suspend fun stopUncertain(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，停止后续任务并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }

    private suspend fun waitForGameHud(ctx: BotContext, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            if (GameHudNavigator.isHudOrMenu(ctx)) return true
            delay(450)
        }
        return false
    }

    private suspend fun waitForHudMenu(ctx: BotContext, template: com.aliothmoon.maahotta.vision.HudTemplates, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> MailScreenDetector.findHudMenu(screen, template) }

    private suspend fun waitForSocialMenu(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> MailScreenDetector.findSocialMenu(screen, template) }

    private suspend fun waitForMailTab(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> MailScreenDetector.findMailTab(screen, template) }

    private suspend fun waitForMailPage(
        ctx: BotContext,
        selectedTemplate: Bitmap,
        claimTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        val selected = MailScreenDetector.findMailSelected(screen, selectedTemplate)
        val claim = MailScreenDetector.findClaimAll(screen, claimTemplate)
        if (selected != null && claim != null) claim else null
    }

    private suspend fun waitForRewardPopup(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        MailScreenDetector.findRewardPopup(screen, template)
    }

    private suspend fun waitForMailPageAfterPopup(
        ctx: BotContext,
        selectedTemplate: Bitmap,
        claimTemplate: Bitmap,
        popupTemplate: Bitmap,
        timeoutMs: Long,
    ): Boolean = ctx.waitUntil(timeoutMs, 350) { screen ->
        val popup = MailScreenDetector.findRewardPopup(screen, popupTemplate)
        val selected = MailScreenDetector.findMailSelected(screen, selectedTemplate)
        val claim = MailScreenDetector.findClaimAll(screen, claimTemplate)
        if (popup == null && selected != null && claim != null) selected else null
    } != null
}
