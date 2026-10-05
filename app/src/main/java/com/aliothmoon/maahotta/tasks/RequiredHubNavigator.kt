package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.KitchenScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.RequiredHubScreenDetector
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.SearchRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RequiredHubTab {
    WEEKLY,
    RECOMMEND,
    LEISURE,
    CHALLENGE,
}

/** Keeps related daily tasks inside the shared Must-do hub between task runs. */
internal object RequiredHubNavigator {
    private data class HubTemplates(
        val hudMenu: com.aliothmoon.maahotta.vision.HudTemplates,
        val weekly: Bitmap,
        val recommend: Bitmap,
        val leisure: Bitmap,
        val challenge: Bitmap,
    )

    suspend fun selectTab(ctx: BotContext, tab: RequiredHubTab): Boolean {
        val templates = loadTemplates(ctx) ?: return false
        if (!ensureOpen(ctx, templates)) return false
        val match = ctx.waitUntil(1_200, 350) { screen ->
            when (tab) {
                RequiredHubTab.WEEKLY -> RequiredHubScreenDetector.findWeeklyTab(screen, templates.weekly)
                RequiredHubTab.RECOMMEND -> RequiredHubScreenDetector.findRecommendTab(screen, templates.recommend)
                RequiredHubTab.LEISURE -> RequiredHubScreenDetector.findLeisureTab(screen, templates.leisure)
                RequiredHubTab.CHALLENGE -> RequiredHubScreenDetector.findChallengeTab(screen, templates.challenge)
            }
        } ?: return false
        val label = when (tab) {
            RequiredHubTab.WEEKLY -> "每周"
            RequiredHubTab.RECOMMEND -> "推荐"
            RequiredHubTab.LEISURE -> "休闲"
            RequiredHubTab.CHALLENGE -> "挑战"
        }
        ctx.log("点击左侧$label")
        if (!ctx.tryTaskStep("hub:select_$label")) return false
        ctx.device.tap(match.point.x, match.point.y)
        delay(900)
        return true
    }

    /** Verify 必做 and the target tab from the same screenshot before tapping. */
    suspend fun selectRecommendByText(ctx: BotContext): Boolean =
        selectTabByText(ctx, "推荐", 0.25f, 0.43f)

    /** Confirm the current hub heading without changing its selected tab. */
    suspend fun isCurrentHubByText(ctx: BotContext): Boolean {
        val screen = ctx.device.screenshot() ?: return false
        return try {
            val titleRegion = SearchRegion(
                (screen.height * 0.10f / screen.width).coerceIn(0f, 1f), 0f,
                (screen.height * 0.47f / screen.width).coerceIn(0f, 1f), 0.13f,
            )
            withTimeoutOrNull(1_500) {
                ScreenTextFinder.findAllInRegion(screen, listOf("必做"), titleRegion).isNotEmpty()
            } == true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        } finally {
            screen.recycle()
        }
    }

    /** Kitchen owns its Back clicks; only select a tab on a positively identified current hub. */
    suspend fun selectRecommendOnCurrentPage(
        ctx: BotContext,
        stepId: String = "hub:select_推荐",
        beforeTap: (suspend () -> Boolean)? = null,
    ): Boolean =
        selectTabByText(
            ctx, "推荐", 0.25f, 0.43f,
            openIfNeeded = false,
            stepId = stepId,
            beforeTap = beforeTap,
            rewardPopupTemplate = if (beforeTap == null) null else ctx.templates.get("mail_reward_popup"),
        )

    suspend fun selectLeisureByText(ctx: BotContext): Boolean =
        selectTabByText(ctx, "休闲", 0.38f, 0.58f)

    /** Fixed left-tab center, scaled by height to preserve placement on wider screens. */
    suspend fun selectChallengeAtFixedPosition(ctx: BotContext): Boolean {
        val templates = loadTemplates(ctx) ?: return false
        if (!ensureOpen(ctx, templates)) return false
        val size = ctx.device.screenSize()
        if (size.x <= 0 || size.y <= 0) return false
        val point = Point((size.y * 0.16f).toInt(), (size.y * 0.64f).toInt())
        ctx.log("已确认必做页，固定坐标点击挑战 (${point.x}, ${point.y})")
        if (!ctx.tryTaskStep("hub:select_挑战")) return false
        ctx.device.tap(point.x, point.y)
        return true
    }

    private suspend fun selectTabByText(
        ctx: BotContext,
        label: String,
        tabTop: Float,
        tabBottom: Float,
        openIfNeeded: Boolean = true,
        stepId: String = "hub:select_$label",
        beforeTap: (suspend () -> Boolean)? = null,
        rewardPopupTemplate: Bitmap? = null,
    ): Boolean {
        if (openIfNeeded) {
            val templates = loadTemplates(ctx) ?: return false
            if (!ensureOpen(ctx, templates)) return false
        }
        var tab = waitForTabText(ctx, label, tabTop, tabBottom, 2_500, rewardPopupTemplate) ?: return false
        if (beforeTap != null) {
            if (!beforeTap()) return false
            tab = waitForTabText(ctx, label, tabTop, tabBottom, 2_500, rewardPopupTemplate) ?: return false
        }
        ctx.log("左上角已识别必做，左侧已识别$label，点击$label")
        if (!ctx.tryTaskStep(stepId)) return false
        ctx.device.tap(tab.x, tab.y)
        return true
    }

    private suspend fun waitForTabText(
        ctx: BotContext,
        label: String,
        tabTop: Float,
        tabBottom: Float,
        timeoutMs: Long,
        rewardPopupTemplate: Bitmap? = null,
    ): Point? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen == null) {
                delay(100)
                continue
            }
            val width = screen.width
            val height = screen.height
            val point = try {
                withTimeoutOrNull(minOf(8_000L, deadline - ctx.elapsedRealtime()).coerceAtLeast(1L)) {
                    if (rewardPopupTemplate != null &&
                        KitchenScreenDetector.findRewardPopup(screen, rewardPopupTemplate) != null
                    ) return@withTimeoutOrNull null
                    val titleRegion = SearchRegion(
                        (height * 0.10f / width).coerceIn(0f, 1f), 0f,
                        (height * 0.47f / width).coerceIn(0f, 1f), 0.13f,
                    )
                    val tabRegion = SearchRegion(
                        (height * 0.03f / width).coerceIn(0f, 1f), tabTop,
                        (height * 0.29f / width).coerceIn(0f, 1f), tabBottom,
                    )
                    if (ScreenTextFinder.findAllInRegion(screen, listOf("必做"), titleRegion).isEmpty()) {
                        null
                    } else {
                        ScreenTextFinder.findAllInRegion(screen, listOf(label), tabRegion)
                            .firstOrNull()?.point
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } finally {
                screen.recycle()
            }
            if (point != null) {
                val size = ctx.device.screenSize()
                return Point(
                    (point.x.toDouble() * size.x / width).toInt(),
                    (point.y.toDouble() * size.y / height).toInt(),
                )
            }
        }
        return null
    }

    suspend fun returnToHub(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUB)
        val templates = loadTemplates(ctx) ?: return false
        if (waitForHub(ctx, templates, 600)) return true
        repeat(2) {
            ctx.log("点击左上角返回必做页")
            if (!ctx.tryTaskStep("hub:back")) return false
            ctx.tap(Layout.back, 700)
            if (waitForHub(ctx, templates, 1_600)) return true
        }
        return ensureOpen(ctx, templates)
    }

    private suspend fun ensureOpen(ctx: BotContext, templates: HubTemplates): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUB)
        if (waitForHub(ctx, templates, 700)) {
            ctx.log("当前已在必做页，继续使用左侧页签")
            return true
        }
        if (!returnToGame(ctx)) return false
        repeat(2) { attempt ->
            ctx.log(
                if (attempt == 0) "首次进入必做页：识别主界面交叉形入口"
                else "第 ${attempt + 1} 次重新识别主界面交叉形入口",
            )
            val crossed = ctx.waitUntil(4_000, 400) { screen ->
                GameScreenDetector.findCrossedHudIcon(
                    screen, templates.hudMenu,
                )
            }
            if (crossed == null) {
                ctx.log("本次未识别到交叉形入口，重新截图识别")
                return@repeat
            }

            ctx.log(
                "菜单锚点定位交叉形入口（向左2格）score=${"%.2f".format(crossed.score)}，点击 (${crossed.point.x},${crossed.point.y})",
            )
            if (!ctx.tryTaskStep("navigation:open_hub")) return false
            ctx.device.tap(crossed.point.x, crossed.point.y)
            if (waitForHub(ctx, templates, 5_000)) return true

            ctx.log("点击后未进入必做页，重新识别入口并再次点击")
            if (!ctx.hasEnteredGame() && !returnToGame(ctx)) return false
        }
        return false
    }

    private suspend fun waitForHub(
        ctx: BotContext,
        templates: HubTemplates,
        timeoutMs: Long,
    ): Boolean {
        if (ctx.waitUntil(timeoutMs, 350) { screen ->
                RequiredHubScreenDetector.findAnyTab(
                    screen,
                    templates.weekly,
                    templates.recommend,
                    templates.leisure,
                    templates.challenge,
                )
            } != null
        ) return true
        // A tab template can miss during the opening animation. Confirm the
        // fixed heading before treating the page as absent and pressing Back.
        val screen = ctx.device.screenshot() ?: return false
        return try {
            val titleRegion = SearchRegion(
                (screen.height * 0.10f / screen.width).coerceIn(0f, 1f), 0f,
                (screen.height * 0.47f / screen.width).coerceIn(0f, 1f), 0.13f,
            )
            withTimeoutOrNull(1_500) {
                ScreenTextFinder.findAllInRegion(screen, listOf("必做"), titleRegion).isNotEmpty()
            } == true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        } finally {
            screen.recycle()
        }
    }

    private suspend fun returnToGame(ctx: BotContext): Boolean {
        if (ctx.preserveTaskPage) return TaskNavigationMachine.reach(ctx, com.aliothmoon.maahotta.vision.NavigationGoal.HUD)
        if (GameHudNavigator.ensurePlainHud(ctx, 1_500)) return true
        repeat(2) {
            ctx.log("点击左上角返回游戏主界面")
            if (!ctx.tryTaskStep("hub:back")) return false
            ctx.tap(Layout.back, 700)
            repeat(3) {
                if (GameHudNavigator.ensurePlainHud(ctx, 1_000)) return true
                delay(350)
            }
        }
        return false
    }

    private fun loadTemplates(ctx: BotContext): HubTemplates? {
        val hudMenu = ctx.hudTemplates()
        val weekly = ctx.templates.get("hub_weekly_tab")
        val recommend = ctx.templates.get("hub_recommend_tab")
        val leisure = ctx.templates.get("hub_leisure_tab")
        val challenge = ctx.templates.get("hub_challenge_tab")
        if (hudMenu == null || weekly == null || recommend == null || leisure == null || challenge == null) {
            ctx.log("必做页导航模板未完整载入")
            return null
        }
        return HubTemplates(hudMenu, weekly, recommend, leisure, challenge)
    }
}
