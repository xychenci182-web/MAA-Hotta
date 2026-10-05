package com.aliothmoon.maahotta.tasks

import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.vision.*
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Observe -> act once -> observe again. Check-in can reuse the successful login's final HUD once. */
internal object TaskNavigationMachine {
    fun goalFor(taskId: String): NavigationGoal? = when {
        taskId == "login" -> null // Login has its own account-aware state machine.
        taskId == "mail" -> NavigationGoal.MAIL
        taskId in setOf("check_in", "supply") -> NavigationGoal.WELFARE
        taskId == "kitchen" -> NavigationGoal.KITCHEN
        taskId == "island_merchant" -> NavigationGoal.ISLAND
        taskId.startsWith("trials_") -> NavigationGoal.TRIALS
        taskId == "bygone_phantasm" -> NavigationGoal.BYGONE
        taskId == "guild_donate" -> NavigationGoal.GUILD_DAILY
        taskId.startsWith("guild_") -> NavigationGoal.GUILD
        taskId == "account_transition" -> NavigationGoal.SETTINGS
        else -> NavigationGoal.GAME
    }

    suspend fun observe(ctx: BotContext, focus: NavigationGoal? = null,
        hudExclusions: HudExclusions = HudExclusions.forTask(ctx.safety.taskId)): PageObservation {
        val frame = ctx.device.screenshot() ?: return PageObservation(PageState.UNKNOWN)
        val width = frame.width
        val height = frame.height
        val observed = try { PageStateDetector.inspect(frame, ctx.templates::get, ctx.hudTemplates(), focus, hudExclusions) }
            finally { frame.recycle() }
        val touch = ctx.device.screenSize()
        check(width > 0 && height > 0 && touch.x > 0 && touch.y > 0) { "无法读取截图或触控尺寸" }
        check(abs((width.toDouble() / height) / (touch.x.toDouble() / touch.y) - 1) <= 0.03) {
            "截图与触控比例不一致，无法执行状态导航"
        }
        return observed.copy(controls = observed.controls.mapValues { (_, hit) ->
            hit.copy(point = Point((hit.point.x.toDouble() * touch.x / width).toInt(),
                (hit.point.y.toDouble() * touch.y / height).toInt()))
        })
    }

    // A route can contain loading plus several independently confirmed page hops.
    // Keep a bounded budget that covers those hops on the 1280×720 emulator.
    @Suppress("UNUSED_PARAMETER") // Older callers disable nested recovery; all recovery is now owned by TaskEngine.
    suspend fun reach(
        ctx: BotContext,
        goal: NavigationGoal,
        timeoutMs: Long = 90_000,
        allowRecovery: Boolean = true,
        reuseLoginMenuForCheckIn: Boolean = false,
        initialObservation: PageObservation? = null,
    ): Boolean {
        val deadline = ctx.elapsedRealtime() + timeoutMs
        var loginPage = if (reuseLoginMenuForCheckIn && goal == NavigationGoal.WELFARE) {
            ctx.consumeLoginMenuForCheckIn()?.let { PageObservation(PageState.HUD, mapOf("menu" to it)) }
        } else null
        var mailExitPage = if (initialObservation == null && loginPage == null) {
            ctx.consumeMailExitMenu()?.let { PageObservation(PageState.HUD, mapOf("menu" to it)) }
        } else null
        var failurePage = initialObservation
        var previous: PageObservation? = null
        var stable = 0
        var lastActionAt = 0L
        var lastLogged: PageState? = null
        val attempts = mutableMapOf<Pair<PageState, NavigationAction>, Int>()
        var hudExclusions = HudExclusions.forTask(ctx.safety.taskId)
        while (ctx.elapsedRealtime() < deadline) {
            val observationStartedAt = ctx.elapsedRealtime()
            val reusedLoginResult = loginPage != null
            val reusedMailResult = mailExitPage != null
            val page = loginPage ?: mailExitPage ?: failurePage ?: observe(ctx, hudExclusions = hudExclusions)
            hudExclusions = when (page.state) {
                PageState.WELFARE, PageState.SIGN_IN, PageState.SUPPLY -> HudExclusions.WELFARE
                PageState.GUILD, PageState.GUILD_DAILY, PageState.GUILD_INFO, PageState.GUILD_WELFARE -> HudExclusions.GUILD
                PageState.BYGONE_SCENE, PageState.BYGONE_WARP, PageState.BYGONE_CONFIRM,
                PageState.BYGONE_FLOOR, PageState.TRIALS, PageState.TRIALS_PROXY, PageState.TRIALS_RESULT -> HudExclusions.DUNGEON
                PageState.LOGIN_ACCOUNT, PageState.LOGIN_TITLE, PageState.SETTINGS -> HudExclusions.LOGIN
                PageState.HUB, PageState.MAIL, PageState.SOCIAL, PageState.ISLAND, PageState.KITCHEN -> HudExclusions.NONE
                else -> hudExclusions
            }
            loginPage = null
            mailExitPage = null
            failurePage = null
            val menu = page.controls["menu"]
            val previousMenu = previous?.controls?.get("menu")
            val same = previous?.state == page.state && (page.state != PageState.HUD ||
                (menu != null && previousMenu != null &&
                    abs(menu.point.x - previousMenu.point.x) <= ctx.device.screenSize().y * 8f / 528 &&
                    abs(menu.point.y - previousMenu.point.y) <= ctx.device.screenSize().y * 8f / 528))
            stable = if (same) stable + 1 else 1
            previous = page
            if (page.state != lastLogged) {
                if (!reusedLoginResult) {
                    ctx.log("状态识别：${page.state.label}，导航目标：$goal，识别耗时=${ctx.elapsedRealtime() - observationStartedAt}ms")
                }
                lastLogged = page.state
            }
            val action = NavigationPolicy.next(page.state, goal)
            if (action == NavigationAction.WAIT) {
                // Only specifically recognized global interruptions may be handled.
                if (stable >= 2 && page.state == PageState.UNKNOWN) {
                    if (ctx.dismissAnnouncement()) {
                        previous = null
                        stable = 0
                    }
                }
                delay(400)
                continue
            }
            // Reuse login's confirmation only for this transition, without promoting its frame count.
            val requiredFrames = if (reusedLoginResult || reusedMailResult) 1 else NavigationPolicy.requiredFrames(page.state, action)
            if (stable < requiredFrames) { delay(350); continue }
            if (action == NavigationAction.READY) {
                ctx.log("状态确认：${page.state.label}，复用当前页面")
                return true
            }
            if (ctx.elapsedRealtime() - lastActionAt < 1_500) { delay(350); continue }
            val key = page.state to action
            if ((attempts[key] ?: 0) >= 2) {
                // Only the engine's final failure verification owns recovery; never reset this budget.
                ctx.log("状态导航失败：${page.state.label} 执行 $action 重试1次仍未跳转")
                return false
            }
            if (act(ctx, page, action)) {
                attempts[key] = (attempts[key] ?: 0) + 1
                lastActionAt = ctx.elapsedRealtime()
                previous = null
                stable = 0
            } else {
                // The page is recognized but the destination control is missing. Do not tap a fallback.
                ctx.log("${page.state.label} 未确认 $action 的入口，重新截图")
                delay(400)
            }
        }
        ctx.log("状态导航超时：未到达 $goal；最后状态：${previous?.state?.label ?: "未知"}")
        return false
    }

    /** Only a positively identified interruption can be clicked from the saved failure frame. */
    suspend fun clearFailureInterruption(ctx: BotContext, page: PageObservation): Boolean = when (page.state) {
        PageState.LINE_SELECTION -> act(ctx, page, NavigationAction.CANCEL_LINE)
        PageState.ANNOUNCEMENT -> act(ctx, page, NavigationAction.CLOSE_ANNOUNCEMENT)
        PageState.REWARD_RECOVERY -> act(ctx, page, NavigationAction.CLOSE_RECOVERY)
        else -> false
    }

    private suspend fun act(ctx: BotContext, page: PageObservation, action: NavigationAction): Boolean {
        val step = when (action) {
            NavigationAction.OPEN_MENU -> if (ctx.safety.taskId?.startsWith("guild_") == true) "guild:menu" else "navigation:open_menu"
            NavigationAction.OPEN_GUILD -> "guild:entry"
            NavigationAction.SELECT_GUILD_DAILY -> "guild:daily_tab"
            NavigationAction.OPEN_GIFT -> "${ctx.safety.taskId}:gift"
            NavigationAction.OPEN_HUB -> "navigation:open_hub"
            NavigationAction.CLOSE_MENU -> "navigation:close_menu"
            NavigationAction.CANCEL_LINE -> "global:line_cancel"
            NavigationAction.CLOSE_ANNOUNCEMENT -> "global:announcement_close"
            NavigationAction.CLOSE_RECOVERY -> "global:reward_recovery_close"
            NavigationAction.CLOSE_REWARD -> "${ctx.safety.taskId}:reward_close"
            NavigationAction.EXIT_BYGONE -> "bygone_phantasm:exit_scene"
            NavigationAction.CONFIRM_BYGONE -> "bygone_phantasm:exit_confirm"
            NavigationAction.BACK -> when (page.state) {
                PageState.WELFARE, PageState.SIGN_IN, PageState.SUPPLY -> "${ctx.safety.taskId}:welfare_back"
                PageState.GUILD, PageState.GUILD_DAILY, PageState.GUILD_INFO, PageState.GUILD_WELFARE -> "guild:back"
                PageState.SETTINGS -> "account:back"
                else -> "navigation:back:${page.state}"
            }
            else -> "navigation:$action"
        }
        fun reserve() = check(ctx.tryTaskStep(step)) { "${page.state.label} 执行 $action 已重试1次，停止重复操作" }
        suspend fun control(name: String): Boolean {
            val hit = page.controls[name] ?: return false
            reserve()
            ctx.log("${page.state.label} → $action，点击 (${hit.point.x},${hit.point.y})")
            ctx.device.tap(hit.point.x, hit.point.y)
            return true
        }
        suspend fun fixed(point: RelPoint): Boolean {
            reserve()
            ctx.log("${page.state.label} → $action")
            ctx.tap(point, 0)
            return true
        }
        return when (action) {
            NavigationAction.OPEN_MENU -> control("menu")
            NavigationAction.OPEN_SOCIAL -> control("social")
            NavigationAction.OPEN_MAIL -> control("mail")
            NavigationAction.OPEN_GUILD -> control("guild")
            NavigationAction.SELECT_GUILD_DAILY -> control("daily")
            NavigationAction.CONFIRM_BYGONE -> control("confirm")
            NavigationAction.CANCEL_LINE -> control("cancel")
            NavigationAction.CLOSE_ANNOUNCEMENT -> control("close")
            NavigationAction.CLOSE_RECOVERY -> fixed(RelPoint(0.50f, 0.10f))
            NavigationAction.CLOSE_MENU -> fixed(Layout.hudMenuClose)
            NavigationAction.CLOSE_REWARD -> fixed(RelPoint(0.50f, 0.10f))
            NavigationAction.CLOSE_TRIALS_RESULT -> fixed(RelPoint(0.50f, 0.41f))
            NavigationAction.BACK -> if (page.state == PageState.TRIALS) control("close") else fixed(Layout.back)
            NavigationAction.OPEN_GIFT, NavigationAction.OPEN_HUB -> {
                val anchor = page.controls["menu"] ?: return false
                val size = ctx.device.screenSize()
                val slots = if (action == NavigationAction.OPEN_GIFT) 4 else 2
                val x = (anchor.point.x - slots * 57f * size.y / 720).toInt()
                if (x !in 0 until size.x) return false
                reserve()
                ctx.log("${page.state.label} → $action，菜单锚点 (${anchor.point.x},${anchor.point.y})，向左${slots}格，点击 ($x,${anchor.point.y})")
                ctx.device.tap(x, anchor.point.y)
                true
            }
            NavigationAction.EXIT_BYGONE -> {
                val size = ctx.device.screenSize()
                reserve()
                ctx.log("已连续确认旧日副本场景，按固定坐标退出")
                ctx.device.tap((198f * size.y / 720).toInt(), (56f * size.y / 720).toInt())
                true
            }
            else -> false
        }
    }
}
