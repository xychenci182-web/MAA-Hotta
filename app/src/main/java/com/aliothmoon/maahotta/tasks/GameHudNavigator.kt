package com.aliothmoon.maahotta.tasks

import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.vision.GameScreen
import kotlinx.coroutines.delay

/** Page-aware recovery shared by tasks that require the normal character HUD. */
internal object GameHudNavigator {
    suspend fun isHudOrMenu(ctx: BotContext): Boolean = ensurePlainHud(ctx)

    suspend fun ensurePlainHud(ctx: BotContext, timeoutMs: Long = 3_500): Boolean {
        when (ctx.gameScreen()) {
            GameScreen.HUD -> return true
            GameScreen.MENU -> Unit
            else -> return ctx.hasEnteredGame()
        }

        ctx.log("当前已在游戏界面但右上角菜单展开，下一步需要普通主界面，点击 X 收起")
        val deadline = ctx.deadlineAfter(timeoutMs)
        var closeAttempts = 0
        while (ctx.elapsedRealtime() < deadline) {
            when (ctx.gameScreen()) {
                GameScreen.HUD -> return true
                GameScreen.MENU -> if (closeAttempts < 2) {
                    ctx.tap(Layout.hudMenuClose, 500)
                    closeAttempts++
                    continue
                }
                else -> if (ctx.hasEnteredGame()) return true
            }
            delay(350)
        }
        return false
    }
}
