package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.vision.GuildScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import kotlinx.coroutines.delay

internal object GuildNavigation {
    suspend fun openDaily(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.GUILD_DAILY)
        if (ctx.dismissLineSwitch()) delay(500)
        val hudMenu = ctx.hudTemplates() ?: return false
        val guildEntry = ctx.templates.get("guild_menu_entry") ?: return false
        val dailyTab = ctx.templates.get("guild_daily_tab") ?: return false
        val donateNow = ctx.templates.get("guild_donate_now") ?: return false
        val donateZero = ctx.templates.get("guild_donate_zero") ?: return false
        val donateOne = ctx.templates.get("guild_donate_one") ?: return false

        if (isDailyPage(ctx, 900)) {
            ctx.log("当前已在公会日常页，直接复用当前页面")
            return true
        }

        var guild: MatchResult? = waitForMenuGuild(ctx, guildEntry, 800)
        if (guild != null) {
            ctx.log("右上角菜单已经展开，直接使用已识别到的公会入口")
        } else {
            if (!ensureGameHud(ctx)) return false

            guild = waitForMenuGuild(ctx, guildEntry, 800)
            if (guild != null) {
                ctx.log("返回后菜单已经展开，直接使用公会入口")
            } else {
                var menu = waitForHudMenu(ctx, hudMenu, 3_000) ?: return false
                repeat(2) { attempt ->
                    if (guild == null) {
                        if (!ctx.tryTaskStep("guild:menu")) return false
                        ctx.log(if (attempt == 0) "点击主界面右上角菜单" else "菜单未展开，重新识别后再次点击")
                        ctx.device.tap(menu.point.x, menu.point.y)
                        guild = waitForMenuGuild(ctx, guildEntry, 3_000)
                        if (guild == null) {
                            val refreshed = waitForHudMenu(ctx, hudMenu, 1_200)
                            if (refreshed != null) menu = refreshed
                        }
                    }
                }
            }
        }
        var guildButton = guild ?: return false

        var daily: MatchResult? = null
        repeat(2) { attempt ->
            if (daily == null) {
                if (!ctx.tryTaskStep("guild:entry")) return false
                ctx.log(if (attempt == 0) "识别到公会按键，点击进入" else "公会页未打开，重新识别后再次点击公会")
                ctx.device.tap(guildButton.point.x, guildButton.point.y)
                daily = waitForDailyTab(ctx, dailyTab, 4_000)
                if (daily == null) {
                    val refreshed = waitForMenuGuild(ctx, guildEntry, 1_200)
                    if (refreshed != null) guildButton = refreshed
                }
            }
        }
        var dailyButton = daily ?: return false

        repeat(2) { attempt ->
            if (!ctx.tryTaskStep("guild:daily_tab")) return false
            ctx.log(if (attempt == 0) "识别到公会页下方日常，点击进入" else "公会日常内容未加载，重新识别后再次点击日常")
            ctx.device.tap(dailyButton.point.x, dailyButton.point.y)
            val pageReady = ctx.waitUntil(4_000, 350) { screen ->
                GuildScreenDetector.findDonateNow(screen, donateNow)
                    ?: GuildScreenDetector.findDonateZero(screen, donateZero, donateOne)
            }
            if (pageReady != null) return true
            val refreshed = waitForDailyTab(ctx, dailyTab, 1_200)
            if (refreshed != null) dailyButton = refreshed
        }
        return false
    }

    suspend fun isDailyPage(ctx: BotContext, timeoutMs: Long = 900): Boolean {
        val donateNow = ctx.templates.get("guild_donate_now") ?: return false
        val donateZero = ctx.templates.get("guild_donate_zero") ?: return false
        val donateOne = ctx.templates.get("guild_donate_one") ?: return false
        return ctx.waitUntil(timeoutMs, 300) { screen ->
            GuildScreenDetector.findDonateNow(screen, donateNow)
                ?: GuildScreenDetector.findDonateZero(screen, donateZero, donateOne)
        } != null
    }

    suspend fun exitToGameHud(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.GAME)
        if (ctx.hasEnteredGame()) return true
        repeat(2) {
            if (ctx.dismissLineSwitch()) {
                delay(500)
                if (ctx.hasEnteredGame()) return true
                return@repeat
            }
            if (ctx.hasEnteredGame()) return true
            if (!ctx.tryTaskStep("guild:back")) return false
            ctx.log("点击左上角公会返回")
            ctx.tap(Layout.back, 0)
            if (waitForGameHud(ctx, 5_000)) return true
        }
        return false
    }

    suspend fun waitForGameHud(ctx: BotContext, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            if (GameHudNavigator.ensurePlainHud(ctx, 650)) return true
            delay(450)
        }
        return false
    }

    private suspend fun ensureGameHud(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.GAME)
        if (ctx.hasEnteredGame()) return true
        repeat(2) {
            if (ctx.dismissLineSwitch()) {
                delay(500)
                if (ctx.hasEnteredGame()) return true
                return@repeat
            }
            if (ctx.hasEnteredGame()) return true
            if (!ctx.tryTaskStep("guild:back")) return false
            ctx.tap(Layout.back, 0)
            if (waitForGameHud(ctx, 3_000)) return true
        }
        return false
    }

    private suspend fun waitForHudMenu(ctx: BotContext, template: com.aliothmoon.maahotta.vision.HudTemplates, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findHudMenu(screen, template) }

    private suspend fun waitForMenuGuild(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findMenuGuild(screen, template) }

    private suspend fun waitForDailyTab(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen -> GuildScreenDetector.findDailyTab(screen, template) }
}
