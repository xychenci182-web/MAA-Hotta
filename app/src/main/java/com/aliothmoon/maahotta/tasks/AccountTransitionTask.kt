package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import com.aliothmoon.maahotta.data.GameAccount
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.AccountScreenDetector
import com.aliothmoon.maahotta.vision.AccountTransitionScreenDetector
import com.aliothmoon.maahotta.vision.CharacterNameReader
import com.aliothmoon.maahotta.vision.GameScreen
import com.aliothmoon.maahotta.vision.MatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class AccountTransitionTask(
    private val currentAccount: GameAccount,
    private val nextAccount: GameAccount?,
    private val captureCharacterName: Boolean,
    private val onCharacterName: suspend (String) -> Unit,
) : GameTask {
    override val id = "account_transition"
    override val title = when {
        nextAccount != null -> "切换到下一账号"
        captureCharacterName -> "记录角色名称"
        else -> "完成当前账号"
    }

    override suspend fun run(ctx: BotContext): TaskResult {
        val hudMenu = ctx.hudTemplates()
            ?: return TaskResult(title, false, "主界面菜单模板未载入")
        val settingsMenu = ctx.templates.get("menu_settings_entry")
            ?: return TaskResult(title, false, "设置入口模板未载入")
        val userCenter = ctx.templates.get("settings_user_center")
            ?: return TaskResult(title, false, "用户中心模板未载入")

        if (nextAccount == null && !captureCharacterName) {
            if (!ensureGameHud(ctx)) {
                return TaskResult(title, false, "最后一个账号结束后未能确认游戏主界面")
            }
            ctx.log("最后一个账号未发现老头，无需打开菜单或记录名称")
            return TaskResult(title, true, "最后一个账号无需记录名称")
        }

        val userCenterButton = openSettingsFromCurrentState(
            ctx = ctx,
            hudMenu = hudMenu,
            settingsMenu = settingsMenu,
            userCenter = userCenter,
        ) ?: return TaskResult(title, false, "未能从当前界面进入设置页")
        val characterName = if (captureCharacterName) {
            readCharacterName(ctx) ?: currentAccount.characterName.takeIf(String::isNotBlank)
                ?: return TaskResult(title, false, "发现老头，但未能识别需要记录的角色名称")
        } else {
            null
        }
        if (characterName != null) {
            ctx.log("识别到需要记录的角色名称：$characterName")
            onCharacterName(characterName)
        } else {
            ctx.log("本账号无需记录老头名称，跳过角色名称识别")
        }

        val next = nextAccount
        if (next == null) {
            ctx.log("已是最后一个账号，点击左上角返回游戏主界面")
            ctx.tap(Layout.back, 700)
            val exited = waitForGameHud(ctx, 8_000)
            return TaskResult(
                title,
                exited,
                if (exited) {
                    characterName?.let { "已记录角色名称 $it" } ?: "本账号无需记录名称"
                } else {
                    characterName?.let { "已记录角色名称，但未返回游戏主界面" }
                        ?: "无需记录名称，但未返回游戏主界面"
                },
            )
        }

        var userCenterOpened = false
        repeat(2) {
            if (!userCenterOpened) {
                ctx.log("点击用户中心，准备直接输入下一账号")
                ctx.device.tap(userCenterButton.point.x, userCenterButton.point.y)
                userCenterOpened = waitForUserCenterOverlay(ctx, 3_000)
            }
        }
        if (!userCenterOpened) {
            return TaskResult(title, false, "点击用户中心后未识别到账号中心窗口")
        }

        val login = LoginTask(
            account = next,
            launchGame = false,
            forceSwitchWithoutVerification = true,
        ).run(ctx)
        return TaskResult(
            title,
            login.ok,
            if (login.ok) {
                characterName?.let { "已记录 $it，并直接登录下一账号 ${next.label}" }
                    ?: "已直接登录下一账号 ${next.label}"
            } else {
                characterName?.let { "已记录 $it，但切换 ${next.label} 失败：${login.detail}" }
                    ?: "切换 ${next.label} 失败：${login.detail}"
            },
        )
    }

    private suspend fun readCharacterName(ctx: BotContext): String? {
        repeat(3) {
            val screen = ctx.device.screenshot()
            if (screen != null) {
                val name = try {
                    withTimeoutOrNull(12_000) { CharacterNameReader.read(screen) }
                } finally {
                    screen.recycle()
                }
                if (!name.isNullOrBlank()) return name
            }
            delay(500)
        }
        return null
    }

    private suspend fun waitForUserCenterOverlay(ctx: BotContext, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            if (ctx.device.viewInfo("lib_change_account") != null) return true
            val screen = ctx.device.screenshot()
            if (screen != null) {
                val found = try {
                    AccountScreenDetector.isUserCenter(screen)
                } finally {
                    screen.recycle()
                }
                if (found) return true
            }
            delay(350)
        }
        return false
    }

    private suspend fun ensureGameHud(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUD)
        if (GameHudNavigator.ensurePlainHud(ctx)) return true
        repeat(5) {
            ctx.tap(Layout.back, 650)
            if (waitForGameHud(ctx, 3_000)) return true
        }
        return false
    }

    private suspend fun waitForGameHud(ctx: BotContext, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            if (GameHudNavigator.ensurePlainHud(ctx)) return true
            delay(450)
        }
        return false
    }

    private suspend fun openSettingsFromCurrentState(
        ctx: BotContext,
        hudMenu: com.aliothmoon.maahotta.vision.HudTemplates,
        settingsMenu: Bitmap,
        userCenter: Bitmap,
    ): MatchResult? {
        repeat(5) {
            val currentSettings = waitForUserCenter(ctx, userCenter, 1_200)
            if (currentSettings != null) {
                ctx.log("当前已在设置页，直接继续")
                return currentSettings
            }

            val settingsEntry = waitForSettingsMenu(ctx, settingsMenu, 1_200)
            if (settingsEntry != null) {
                ctx.log("当前菜单已经展开，直接点击设置")
                ctx.device.tap(settingsEntry.point.x, settingsEntry.point.y)
                delay(900)
                return@repeat
            }

            val menu = waitForHudMenu(ctx, hudMenu, 1_200)
            if (menu != null) {
                ctx.log("当前在游戏主界面，识别并点击右上角菜单")
                ctx.device.tap(menu.point.x, menu.point.y)
                delay(700)
                return@repeat
            }

            if (ctx.gameScreen() == GameScreen.HUD) {
                ctx.log("已确认游戏主界面，但菜单图标尚未加载，继续等待")
                delay(700)
            } else {
                ctx.log("当前不在主界面、展开菜单或设置页，返回后重新判断")
                ctx.tap(Layout.back, 700)
            }
        }
        return waitForUserCenter(ctx, userCenter, 2_000)
    }

    private suspend fun waitForHudMenu(ctx: BotContext, template: com.aliothmoon.maahotta.vision.HudTemplates, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 350) { screen ->
            AccountTransitionScreenDetector.findHudMenu(screen, template)
        }

    private suspend fun waitForSettingsMenu(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        AccountTransitionScreenDetector.findSettingsMenu(screen, template)
    }

    private suspend fun waitForUserCenter(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        AccountTransitionScreenDetector.findUserCenter(screen, template)
    }
}
