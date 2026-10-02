package com.aliothmoon.maahotta.tasks

import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.vision.*
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Observe -> choose transition -> act once -> observe again. No remembered click coordinates. */
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

    suspend fun observe(ctx: BotContext, focus: NavigationGoal? = null): PageObservation {
        val frame = ctx.device.screenshot() ?: return PageObservation(PageState.UNKNOWN)
        val width = frame.width
        val height = frame.height
        val observed = try { PageStateDetector.inspect(frame, ctx.templates::get, ctx.hudTemplates(), focus) }
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
    suspend fun reach(ctx: BotContext, goal: NavigationGoal, timeoutMs: Long = 90_000, allowRecovery: Boolean = true): Boolean {
        val deadline = ctx.elapsedRealtime() + timeoutMs
        var previous: PageObservation? = null
        var stable = 0
        var lastActionAt = 0L
        var lastLogged: PageState? = null
        var recoveryUsed = false
        val attempts = mutableMapOf<Pair<PageState, NavigationAction>, Int>()
        while (ctx.elapsedRealtime() < deadline) {
            val observationStartedAt = ctx.elapsedRealtime()
            val page = observe(ctx)
            val menu = page.controls["menu"]
            val previousMenu = previous?.controls?.get("menu")
            val same = previous?.state == page.state && (page.state != PageState.HUD ||
                (menu != null && previousMenu != null &&
                    abs(menu.point.x - previousMenu.point.x) <= ctx.device.screenSize().y * 8f / 528 &&
                    abs(menu.point.y - previousMenu.point.y) <= ctx.device.screenSize().y * 8f / 528))
            stable = if (same) stable + 1 else 1
            previous = page
            if (page.state != lastLogged) {
                ctx.log("状态识别：${page.state.label}，导航目标：$goal，识别耗时=${ctx.elapsedRealtime() - observationStartedAt}ms")
                lastLogged = page.state
            }
            val action = NavigationPolicy.next(page.state, goal)
            if (action == NavigationAction.WAIT) {
                // Only specifically recognized global interruptions may be handled.
                if (stable >= 2 && page.state == PageState.UNKNOWN) {
                    if (ctx.dismissAnnouncement() || ctx.dismissRewardRecoveryPopup()) {
                        previous = null
                        stable = 0
                    }
                }
                delay(400)
                continue
            }
            val requiredFrames = NavigationPolicy.requiredFrames(page.state, action)
            if (stable < requiredFrames) { delay(350); continue }
            if (action == NavigationAction.READY) {
                ctx.log("状态确认：${page.state.label}，复用当前页面")
                return true
            }
            if (ctx.elapsedRealtime() - lastActionAt < 1_500) { delay(350); continue }
            val key = page.state to action
            if ((attempts[key] ?: 0) >= 3) {
                val canRecover = page.state in setOf(PageState.MENU, PageState.SOCIAL, PageState.MAIL,
                    PageState.WELFARE, PageState.SIGN_IN, PageState.SUPPLY, PageState.HUB,
                    PageState.GUILD, PageState.GUILD_DAILY, PageState.GUILD_INFO, PageState.GUILD_WELFARE, PageState.SETTINGS)
                if (allowRecovery && !recoveryUsed && canRecover && goal != NavigationGoal.HUD) {
                    recoveryUsed = true
                    ctx.log("页面跳转重试仍失败，按已确认状态恢复主界面，再尝试一次任务导航")
                    if (!reach(ctx, NavigationGoal.HUD, minOf(15_000, deadline - ctx.elapsedRealtime()).coerceAtLeast(1), false)) return false
                    attempts.clear()
                    previous = null
                    stable = 0
                    continue
                }
                ctx.log("状态导航失败：${page.state.label} 执行 $action 三次仍未跳转")
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

    private suspend fun act(ctx: BotContext, page: PageObservation, action: NavigationAction): Boolean {
        suspend fun control(name: String): Boolean {
            val hit = page.controls[name] ?: return false
            ctx.log("${page.state.label} → $action，点击 (${hit.point.x},${hit.point.y})")
            ctx.device.tap(hit.point.x, hit.point.y)
            return true
        }
        suspend fun fixed(point: RelPoint): Boolean {
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
                ctx.log("${page.state.label} → $action，菜单锚点 (${anchor.point.x},${anchor.point.y})，向左${slots}格，点击 ($x,${anchor.point.y})")
                ctx.device.tap(x, anchor.point.y)
                true
            }
            NavigationAction.EXIT_BYGONE -> {
                val size = ctx.device.screenSize()
                ctx.log("已连续确认旧日副本场景，按固定坐标退出")
                ctx.device.tap((198f * size.y / 720).toInt(), (56f * size.y / 720).toInt())
                true
            }
            else -> false
        }
    }
}
