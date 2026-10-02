package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.BygoneScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class BygonePhantasmTask : GameTask {
    override val id = "bygone_phantasm"
    override val title = "旧日幻想"

    override suspend fun run(ctx: BotContext): TaskResult {
        val entryTemplate = ctx.templates.get("entry_bygone_phantasm")
            ?: return TaskResult(title, false, "旧日幻想入口模板未载入")
        val diveNextTemplate = ctx.templates.get("bygone_dive_next")
            ?: return TaskResult(title, false, "潜入按钮模板未载入")
        val skipTemplate = ctx.templates.get("bygone_skip")
            ?: return TaskResult(title, false, "旧日幻想动画跳过模板未载入")
        val warpStartTemplate = ctx.templates.get("bygone_warp_start")
            ?: return TaskResult(title, false, "旧日幻想跃迁启动模板未载入")
        val sceneTimerTemplate = ctx.templates.get("bygone_scene_timer")
            ?: return TaskResult(title, false, "旧日幻想场景计时器模板未载入")
        val exitDialogTemplate = ctx.templates.get("bygone_exit_dialog")
            ?: return TaskResult(title, false, "退出确认弹窗模板未载入")
        val confirmTemplate = ctx.templates.get("bygone_exit_confirm")
            ?: return TaskResult(title, false, "退出确定按钮模板未载入")

        // Resume from the actual dungeon state, never open the Must-do hub over it.
        val current = TaskNavigationMachine.observe(ctx)
        if (current.state == com.aliothmoon.maahotta.vision.PageState.BYGONE_CONFIRM) {
            return exitScene(ctx, null, exitDialogTemplate, confirmTemplate,
                sceneTimerTemplate, diveNextTemplate, skipTemplate)
        }
        if (current.state in setOf(com.aliothmoon.maahotta.vision.PageState.BYGONE_SCENE,
                com.aliothmoon.maahotta.vision.PageState.BYGONE_WARP)) {
            val exit = waitThroughEntryAnimation(ctx, skipTemplate, diveNextTemplate,
                warpStartTemplate, sceneTimerTemplate, 45_000)
                ?: return stopAfterDive(ctx, "未能确认旧日副本场景")
            return exitScene(ctx, exit, exitDialogTemplate, confirmTemplate,
                sceneTimerTemplate, diveNextTemplate, skipTemplate)
        }
        if (current.state == com.aliothmoon.maahotta.vision.PageState.BYGONE_FLOOR) {
            val dive = waitForDiveNext(ctx, diveNextTemplate, 2_000)
                ?: return stopAfterDive(ctx, "旧日潜入页未确认潜入按钮")
            ctx.device.tap(dive.point.x, dive.point.y)
            val exit = waitThroughEntryAnimation(ctx, skipTemplate, diveNextTemplate,
                warpStartTemplate, sceneTimerTemplate, 45_000)
                ?: return stopAfterDive(ctx, "潜入后未能确认旧日副本场景")
            return exitScene(ctx, exit, exitDialogTemplate, confirmTemplate,
                sceneTimerTemplate, diveNextTemplate, skipTemplate)
        }
        var lastFailure = "未进入旧日幻想"
        for (attempt in 1..3) {
            ctx.log("旧日幻想第 $attempt 次尝试")
            if (!RequiredHubNavigator.selectChallengeByText(ctx)) {
                lastFailure = "未能进入必做页或点击挑战"
                ctx.log(lastFailure)
                continue
            }

            val entry = waitForEntry(ctx, entryTemplate, 3_000)
            if (entry == null) {
                lastFailure = "挑战页中央内容未识别到旧日幻想入口"
                ctx.log("$lastFailure，留在必做页重试挑战")
                continue
            }
            var entryButton = requireNotNull(entry)
            var diveNext: MatchResult? = null
            for (entryAttempt in 1..3) {
                ctx.log(if (entryAttempt == 1) "已识别中央旧日幻想卡片，点击进入" else "旧日页面未打开，重新识别后再次点击卡片")
                ctx.device.tap(entryButton.point.x, entryButton.point.y)
                diveNext = waitForDiveNext(ctx, diveNextTemplate, 5_000)
                if (diveNext != null) break
                val refreshed = waitForEntry(ctx, entryTemplate, 1_500)
                if (refreshed != null) {
                    entryButton = refreshed
                } else {
                    ctx.log("旧日卡片已消失，潜入按钮可能仍在加载，继续等待")
                    diveNext = waitForDiveNext(ctx, diveNextTemplate, 6_000)
                    break
                }
            }
            if (diveNext == null) {
                lastFailure = "进入旧日幻想后未识别到潜入按钮"
                ctx.log("$lastFailure，返回必做页重试")
                if (!RequiredHubNavigator.returnToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }
            ctx.log("识别到潜入按钮，点击进入副本")
            ctx.device.tap(diveNext.point.x, diveNext.point.y)

            val firstExit = waitThroughEntryAnimation(
                ctx = ctx,
                skipTemplate = skipTemplate,
                diveNextTemplate = diveNextTemplate,
                warpStartTemplate = warpStartTemplate,
                sceneTimerTemplate = sceneTimerTemplate,
                timeoutMs = 45_000,
            ) ?: return stopAfterDive(ctx, "潜入后按钮未消失或跳过动画未完成")
            return exitScene(ctx, firstExit, exitDialogTemplate, confirmTemplate,
                sceneTimerTemplate, diveNextTemplate, skipTemplate)
        }
        return TaskResult(title, false, "$lastFailure；已重试3次")
    }

    private suspend fun exitScene(
        ctx: BotContext, firstExit: MatchResult?, exitDialogTemplate: Bitmap, confirmTemplate: Bitmap,
        sceneTimerTemplate: Bitmap, diveNextTemplate: Bitmap, skipTemplate: Bitmap,
    ): TaskResult {
        if (firstExit != null) {
            ctx.log("退出点击第 1/3 次：已确认副本，按固定坐标退出 (${firstExit.point.x}, ${firstExit.point.y})")
            ctx.device.tap(firstExit.point.x, firstExit.point.y)
        }

        var confirm = waitForExitConfirmation(ctx, exitDialogTemplate, confirmTemplate, 4_000)
        for (exitAttempt in 2..3) {
            if (confirm != null) break
            var dialogVisible = false
            val retryTarget = ctx.waitUntil(3_000, 350) { screen ->
                dialogVisible = BygoneScreenDetector.findExitDialog(screen, exitDialogTemplate) != null
                if (dialogVisible) BygoneScreenDetector.findExitConfirm(screen, confirmTemplate)
                else if (BygoneScreenDetector.findSceneTimer(screen, sceneTimerTemplate) != null &&
                    BygoneScreenDetector.findDiveNext(screen, diveNextTemplate) == null &&
                    BygoneScreenDetector.findSkip(screen, skipTemplate) == null
                ) MatchResult(Point(0, 0), 1f) else null
            }
            if (retryTarget == null) {
                ctx.log("第 $exitAttempt/3 次：无法确认旧日场景或弹窗按钮，本次不点击")
                continue
            }
            if (dialogVisible) {
                confirm = retryTarget
                break
            }
            ctx.log("仍在旧日幻想且未出现退出确认弹窗，按固定坐标再次点击退出（第 $exitAttempt/3 次）")
            val point = fixedExitPoint(ctx)
            ctx.device.tap(point.x, point.y)
            confirm = waitForExitConfirmation(ctx, exitDialogTemplate, confirmTemplate, 4_000)
        }
        val confirmButton = confirm
            ?: return stopAfterDive(ctx, "退出流程已尝试3轮，仍未识别到退出确认弹窗和确定按钮")
        var returnedToGame = false
        repeat(3) { confirmAttempt ->
            if (!returnedToGame) {
                val currentConfirm = if (confirmAttempt == 0) confirmButton else
                    waitForExitConfirmation(ctx, exitDialogTemplate, confirmTemplate, 1_500)
                if (currentConfirm != null) {
                    ctx.log(if (confirmAttempt == 0) "识别到退出确认弹窗，点击确定"
                        else "尚未识别到主界面菜单，确认退出弹窗仍在，再次点击确定")
                    ctx.device.tap(currentConfirm.point.x, currentConfirm.point.y)
                } else {
                    ctx.log("未识别到退出确认弹窗，继续等待主界面菜单")
                }
                returnedToGame = waitForGameHud(ctx, 20_000)
            }
        }
        if (!returnedToGame) {
            return stopAfterDive(ctx, "点击确定后未识别到主界面菜单")
        }
        return TaskResult(title, true, "已潜入并退出到游戏主界面")
    }

    private suspend fun stopAfterDive(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，潜入已经点击，停止重试并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }

    private suspend fun waitForEntry(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> BygoneScreenDetector.findEntry(screen, template) }

    private suspend fun waitForDiveNext(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> BygoneScreenDetector.findDiveNext(screen, template) }

    private data class EntryFrame(
        val skip: MatchResult? = null,
        val dive: MatchResult? = null,
        val warpStarted: Boolean = false,
        val sceneConfirmed: Boolean = false,
    )

    /** Read transition or both candidate actions from the same fresh screenshot. */
    private suspend fun readEntryFrame(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveTemplate: Bitmap,
        warpTemplate: Bitmap,
        sceneTemplate: Bitmap,
        detectActions: Boolean,
    ): EntryFrame? {
        val screen = ctx.device.screenshot() ?: return null
        try {
            val touchSize = ctx.device.screenSize()
            if (touchSize.x <= 0 || touchSize.y <= 0) return null
            fun toTouch(match: MatchResult?): MatchResult? = match?.copy(
                point = Point(
                    (match.point.x.toDouble() * touchSize.x / screen.width).roundToInt(),
                    (match.point.y.toDouble() * touchSize.y / screen.height).roundToInt(),
                ),
            )
            val dive = BygoneScreenDetector.findDiveNext(screen, diveTemplate)
            return EntryFrame(
                dive = toTouch(dive),
                warpStarted = !detectActions && dive == null && BygoneScreenDetector.findWarpStart(screen, warpTemplate) != null,
                skip = if (detectActions && dive == null) toTouch(BygoneScreenDetector.findSkip(screen, skipTemplate)) else null,
                sceneConfirmed = detectActions && dive == null && BygoneScreenDetector.findSceneTimer(screen, sceneTemplate) != null,
            )
        } finally {
            screen.recycle()
        }
    }

    private suspend fun waitThroughEntryAnimation(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveNextTemplate: Bitmap,
        warpStartTemplate: Bitmap,
        sceneTimerTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        var lastDiveTapAt = ctx.elapsedRealtime()
        var diveTapCount = 1
        var lastSkipTapAt = 0L
        var skipClickCount = 0
        var absenceStartedAt: Long? = null
        var readyForActions = false
        while (ctx.elapsedRealtime() < deadline) {
            val roundStartedAt = ctx.elapsedRealtime()
            val frame = readEntryFrame(ctx, skipTemplate, diveNextTemplate,
                warpStartTemplate, sceneTimerTemplate, detectActions = readyForActions)
            val now = ctx.elapsedRealtime()
            if (frame?.dive != null) {
                absenceStartedAt = null
                readyForActions = false
                if (now - lastDiveTapAt >= 1_800) {
                    if (diveTapCount >= 3) {
                        ctx.log("多次点击潜入按钮后仍停留在原页面")
                        return null
                    }
                    diveTapCount++
                    ctx.log("潜入按钮仍在，重新识别并点击")
                    ctx.device.tap(frame.dive.point.x, frame.dive.point.y)
                    lastDiveTapAt = ctx.elapsedRealtime()
                }
            } else if (frame != null) {
                if (!readyForActions) {
                    if (absenceStartedAt == null) {
                        absenceStartedAt = now
                        ctx.log(if (frame.warpStarted) "潜入按钮消失，识别到跃迁启动，等待3秒"
                            else "潜入按钮消失，等待3秒后检查跳过并尝试固定坐标退出")
                    }
                    if (now - requireNotNull(absenceStartedAt) >= 3_000) {
                        readyForActions = true
                        continue // Obtain a new screenshot before handling skip.
                    }
                } else if (frame.skip != null) {
                    if (skipClickCount == 0 || now - lastSkipTapAt >= 700) {
                        if (skipClickCount >= 3) {
                            ctx.log("点击跳过3次后按钮仍在，停止并保存截图")
                            return null
                        }
                        skipClickCount++
                        ctx.log("识别到跳过按钮，点击后继续等待")
                        ctx.device.tap(frame.skip.point.x, frame.skip.point.y)
                        lastSkipTapAt = ctx.elapsedRealtime()
                        delay(1_000)
                    }
                } else if (frame.sceneConfirmed) {
                    ctx.log("已识别副本计时器，确认旧日幻想场景；潜入和跳过按钮均不在，使用固定坐标退出")
                    return MatchResult(fixedExitPoint(ctx), 1f)
                }
            } else {
                absenceStartedAt = null
                readyForActions = false
            }

            val remaining = 350 - (ctx.elapsedRealtime() - roundStartedAt)
            if (remaining > 0 && ctx.elapsedRealtime() < deadline) delay(remaining)
        }
        return null
    }

    /** Observed exit center (153, 43) in the supplied 945 x 556 game frame. */
    private fun fixedExitPoint(ctx: BotContext): Point {
        val size = ctx.device.screenSize()
        check(size.x > 0 && size.y > 0) { "无法读取退出点击所需的屏幕尺寸" }
        return Point((198f * size.y / 720f).roundToInt(), (56f * size.y / 720f).roundToInt())
    }

    private suspend fun waitForExitConfirmation(
        ctx: BotContext,
        dialogTemplate: Bitmap,
        confirmTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 350) { screen ->
        if (BygoneScreenDetector.findExitDialog(screen, dialogTemplate) == null) null
        else BygoneScreenDetector.findExitConfirm(screen, confirmTemplate)
    }

    private suspend fun waitForGameHud(ctx: BotContext, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            if (ctx.hasEnteredGame()) return true
            delay(500)
        }
        return false
    }
}
