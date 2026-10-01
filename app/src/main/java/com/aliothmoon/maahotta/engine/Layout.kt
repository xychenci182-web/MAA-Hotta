package com.aliothmoon.maahotta.engine

import android.graphics.Point
import com.aliothmoon.maahotta.runtime.DeviceController

data class RelPoint(val x: Float, val y: Float) {
    fun toPixel(controller: DeviceController): Point {
        val size = controller.screenSize()
        return Point((size.x * x).toInt(), (size.y * y).toInt())
    }
}

/**
 * 相对坐标按当前横屏的物理像素宽高换算；Android DPI 不参与点击坐标。
 * 游戏改版后优先用模板名校准，而不是改任务逻辑。
 */
object Layout {
    val hudMenu = RelPoint(0.955f, 0.905f)
    /** Expanded hexagon menu close X, used only after MENU state is confirmed. */
    val hudMenuClose = RelPoint(0.899f, 0.082f)
    val hudWelfare = RelPoint(0.84f, 0.08f)
    val closeDialog = RelPoint(0.96f, 0.06f)
    val back = RelPoint(0.05f, 0.07f)

    val menuAdventure = RelPoint(0.18f, 0.42f)
    val menuLeisure = RelPoint(0.18f, 0.58f)
    val menuGuild = RelPoint(0.82f, 0.42f)
    val menuSettings = RelPoint(0.82f, 0.78f)
    val menuConfirmGo = RelPoint(0.72f, 0.72f)

    val welfareCheckInTab = RelPoint(0.12f, 0.28f)
    val welfareClaim = RelPoint(0.78f, 0.82f)

    val kitchenEat = RelPoint(0.78f, 0.82f)

    val trialsEntry = RelPoint(0.52f, 0.62f)
    val trialsSweep = RelPoint(0.70f, 0.86f)
    val trialsConfirm = RelPoint(0.62f, 0.72f)
    val trialsPlus = RelPoint(0.58f, 0.62f)

    val islandBuild = RelPoint(0.42f, 0.55f)
    val islandMap = RelPoint(0.05f, 0.18f)

    val guildDailyTab = RelPoint(0.18f, 0.18f)
    val guildWelfareTab = RelPoint(0.11f, 0.28f)
    val guildInfoTab = RelPoint(0.11f, 0.95f)

    val loginEnter = RelPoint(0.50f, 0.82f)
    /** 完美世界通行证：登录框在横屏正中，不要点屏幕左侧 */
    val pwdAgree = RelPoint(0.368f, 0.571f)
    val pwdPhoneField = RelPoint(0.590f, 0.448f)
    val pwdNext = RelPoint(0.532f, 0.524f)
    val pwdPasswordField = RelPoint(0.532f, 0.448f)
    val pwdLogin = RelPoint(0.532f, 0.560f)
    val loginAccountField = pwdPhoneField
    val loginPasswordField = pwdPasswordField
    val loginSubmit = pwdLogin
    val settingsLogout = RelPoint(0.50f, 0.78f)
    val confirmYes = RelPoint(0.62f, 0.68f)
    /** 登录后「游戏公告」面板右上角红色 X */
    val announcementClose = RelPoint(0.906f, 0.179f)
}
