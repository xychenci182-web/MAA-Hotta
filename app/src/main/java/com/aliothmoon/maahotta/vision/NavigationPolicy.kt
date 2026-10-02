package com.aliothmoon.maahotta.vision

enum class PageState(val label: String) {
    UNKNOWN("未确认/加载中"), HUD("游戏主界面"), MENU("展开菜单"), SETTINGS("设置页"),
    SOCIAL("社交页"), MAIL("邮件页"), WELFARE("福利页"), SIGN_IN("签到页"), SUPPLY("执行供给页"),
    HUB("必做页"), KITCHEN("私厨页"), TRIALS("次元历练窗口"), TRIALS_PROXY("代理战斗确认"),
    TRIALS_RESULT("历练作战成功"), ISLAND("人工岛页"),
    GUILD("公会页"), GUILD_DAILY("公会日常页"), GUILD_INFO("公会信息页"),
    REWARD("奖励弹层"), BYGONE_FLOOR("旧日潜入页"), BYGONE_WARP("旧日跃迁中"),
    BYGONE_SCENE("旧日副本场景"), BYGONE_CONFIRM("旧日退出确认"),
}

enum class NavigationGoal { HUD, GAME, MAIL, WELFARE, HUB, KITCHEN, TRIALS, ISLAND, GUILD, GUILD_DAILY, SETTINGS, BYGONE }
enum class NavigationAction {
    READY, WAIT, BACK, CLOSE_MENU, OPEN_MENU, OPEN_GIFT, OPEN_HUB, OPEN_SOCIAL,
    OPEN_MAIL, OPEN_GUILD, SELECT_GUILD_DAILY, CLOSE_REWARD, CLOSE_TRIALS_RESULT, EXIT_BYGONE, CONFIRM_BYGONE,
}

/** Pure transition policy: no actions on an unknown page, and no HUD shortcut from a dungeon. */
object NavigationPolicy {
    fun next(state: PageState, goal: NavigationGoal): NavigationAction {
        if (state == PageState.UNKNOWN) return NavigationAction.WAIT
        if (goal == NavigationGoal.BYGONE && state in setOf(PageState.BYGONE_FLOOR,
                PageState.BYGONE_WARP, PageState.BYGONE_SCENE, PageState.BYGONE_CONFIRM)) return NavigationAction.READY
        when (state) {
            PageState.BYGONE_WARP -> return NavigationAction.WAIT
            PageState.BYGONE_SCENE -> return NavigationAction.EXIT_BYGONE
            PageState.BYGONE_CONFIRM -> return NavigationAction.CONFIRM_BYGONE
            PageState.REWARD -> return NavigationAction.CLOSE_REWARD
            else -> Unit
        }
        val ready = when (goal) {
            NavigationGoal.HUD -> state == PageState.HUD
            NavigationGoal.GAME -> state in setOf(PageState.HUD, PageState.MENU)
            NavigationGoal.SETTINGS -> state in setOf(PageState.HUD, PageState.MENU, PageState.SETTINGS)
            NavigationGoal.MAIL -> state == PageState.MAIL
            NavigationGoal.WELFARE -> state in setOf(PageState.WELFARE, PageState.SIGN_IN, PageState.SUPPLY)
            NavigationGoal.HUB, NavigationGoal.BYGONE -> state == PageState.HUB
            NavigationGoal.KITCHEN -> state in setOf(PageState.HUB, PageState.KITCHEN)
            NavigationGoal.TRIALS -> state in setOf(PageState.HUB, PageState.TRIALS, PageState.TRIALS_PROXY, PageState.TRIALS_RESULT)
            NavigationGoal.ISLAND -> state in setOf(PageState.HUB, PageState.ISLAND)
            NavigationGoal.GUILD -> state in setOf(PageState.GUILD, PageState.GUILD_DAILY, PageState.GUILD_INFO)
            NavigationGoal.GUILD_DAILY -> state == PageState.GUILD_DAILY
        }
        if (ready) return NavigationAction.READY
        return when (state) {
            PageState.HUD -> when (goal) {
                NavigationGoal.WELFARE -> NavigationAction.OPEN_GIFT
                NavigationGoal.HUB, NavigationGoal.BYGONE, NavigationGoal.KITCHEN, NavigationGoal.TRIALS, NavigationGoal.ISLAND -> NavigationAction.OPEN_HUB
                else -> NavigationAction.OPEN_MENU
            }
            PageState.MENU -> when (goal) {
                NavigationGoal.MAIL -> NavigationAction.OPEN_SOCIAL
                NavigationGoal.GUILD, NavigationGoal.GUILD_DAILY -> NavigationAction.OPEN_GUILD
                else -> NavigationAction.CLOSE_MENU
            }
            PageState.SOCIAL -> if (goal == NavigationGoal.MAIL) NavigationAction.OPEN_MAIL else NavigationAction.BACK
            PageState.GUILD, PageState.GUILD_INFO -> if (goal == NavigationGoal.GUILD_DAILY)
                NavigationAction.SELECT_GUILD_DAILY else NavigationAction.BACK
            PageState.TRIALS -> NavigationAction.BACK
            PageState.TRIALS_PROXY -> NavigationAction.WAIT // Never dismiss or execute an unowned battle confirmation.
            PageState.TRIALS_RESULT -> NavigationAction.CLOSE_TRIALS_RESULT
            else -> NavigationAction.BACK
        }
    }
}
