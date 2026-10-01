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
        val exitIconTemplate = ctx.templates.get("bygone_exit_icon")
            ?: return TaskResult(title, false, "副本退出图标模板未载入")
        val exitDialogTemplate = ctx.templates.get("bygone_exit_dialog")
            ?: return TaskResult(title, false, "退出确认弹窗模板未载入")
        val confirmTemplate = ctx.templates.get("bygone_exit_confirm")
            ?: return TaskResult(title, false, "退出确定按钮模板未载入")

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
                exitIconTemplate = exitIconTemplate,
                warpStartTemplate = warpStartTemplate,
                timeoutMs = 45_000,
            ) ?: return stopAfterDive(ctx, "潜入后未完成跃迁启动及跳过/退出唯一性验证")
            ctx.log("退出点击第 1/3 次：已识别退出图标，立即点击 (${firstExit.point.x}, ${firstExit.point.y})")
            ctx.device.tap(firstExit.point.x, firstExit.point.y)

            var confirm = waitForExitConfirmation(ctx, exitDialogTemplate, confirmTemplate, 4_000)
            for (exitAttempt in 2..3) {
                if (confirm != null) break
                ctx.log("未出现退出确认弹窗，重新截图识别退出图标（第 $exitAttempt/3 次）")
                // Inspect both states in the same fresh screenshot. Never reuse an
                // old exit coordinate, or click through a partially loaded dialog.
                var dialogVisible = false
                val target = ctx.waitUntil(3_000, 350) { screen ->
                    dialogVisible = BygoneScreenDetector.findExitDialog(screen, exitDialogTemplate) != null
                    if (dialogVisible) {
                        BygoneScreenDetector.findExitConfirm(screen, confirmTemplate)
                    } else {
                        val skip = BygoneScreenDetector.findSkip(screen, skipTemplate)
                        val exit = BygoneScreenDetector.findExitIcon(screen, exitIconTemplate)
                        if (skip == null) BygoneScreenDetector.exclusiveEntryAction(skip, exit) else null
                    }
                }
                if (target == null) {
                    ctx.log("退出定位第 $exitAttempt/3 次：未确认退出图标或弹窗按钮，本次不点击")
                    continue
                }
                if (dialogVisible) {
                    confirm = target
                    break
                }
                ctx.log("退出点击第 $exitAttempt/3 次：重新识别左上角退出图标，点击 (${target.point.x}, ${target.point.y})")
                ctx.device.tap(target.point.x, target.point.y)
                confirm = waitForExitConfirmation(ctx, exitDialogTemplate, confirmTemplate, 4_000)
                if (confirm != null) break
                ctx.log("退出点击第 $exitAttempt/3 次后未识别到确认弹窗" +
                    if (exitAttempt < 3) "，重新截图定位后重试" else "，已达到重试上限")
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
        return TaskResult(title, false, "$lastFailure；已重试3次")
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
        val exit: MatchResult? = null,
        val warpStarted: Boolean = false,
    )

    /** Read transition or both candidate actions from the same fresh screenshot. */
    private suspend fun readEntryFrame(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveTemplate: Bitmap,
        exitTemplate: Bitmap,
        warpTemplate: Bitmap,
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
            if (!detectActions) {
                val dive = BygoneScreenDetector.findDiveNext(screen, diveTemplate)
                if (dive != null) return EntryFrame(dive = toTouch(dive))
                val warpStarted = BygoneScreenDetector.findWarpStart(screen, warpTemplate) != null
                return EntryFrame(
                    warpStarted = warpStarted,
                    skip = if (warpStarted) null else toTouch(BygoneScreenDetector.findSkip(screen, skipTemplate)),
                    exit = if (warpStarted) null else toTouch(BygoneScreenDetector.findExitIcon(screen, exitTemplate)),
                )
            }
            return EntryFrame(
                skip = toTouch(BygoneScreenDetector.findSkip(screen, skipTemplate)),
                exit = toTouch(BygoneScreenDetector.findExitIcon(screen, exitTemplate)),
            )
        } finally {
            screen.recycle()
        }
    }

    private suspend fun waitThroughEntryAnimation(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveNextTemplate: Bitmap,
        exitIconTemplate: Bitmap,
        warpStartTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        var lastDiveTapAt = ctx.elapsedRealtime()
        var diveTapCount = 1
        var lastSkipTapAt = 0L
        var skipClickCount = 0
        var warpConfirmed = false
        var conflictLogged = false
        var waitingLogged = false
        while (ctx.elapsedRealtime() < deadline) {
            val roundStartedAt = ctx.elapsedRealtime()
            val frame = readEntryFrame(ctx, skipTemplate, diveNextTemplate, exitIconTemplate,
                warpStartTemplate, detectActions = warpConfirmed)
            val now = ctx.elapsedRealtime()
            if (!warpConfirmed) {
                if (frame?.warpStarted == true) {
                    warpConfirmed = true
                    ctx.log("潜入按钮已消失，识别到跃迁装置正在启动；进入旧日幻想环节，等待3秒")
                    delay(3_000)
                    ctx.log("等待结束，开始互斥识别跳过与退出图标")
                    continue
                }
                if (frame != null && frame.dive == null && frame.exit != null &&
                    BygoneScreenDetector.exclusiveEntryAction(frame.skip, frame.exit) != null
                ) {
                    ctx.log("潜入按钮已消失，未捕获跃迁提示但唯一识别到退出图标，直接执行退出")
                    return frame.exit
                }
                if (frame?.skip != null && frame.exit != null && !conflictLogged) {
                    ctx.log("跃迁提示未捕获，跳过与退出同时命中，拒绝点击并继续识别")
                    conflictLogged = true
                }
                if (frame?.dive != null && now - lastDiveTapAt >= 1_800) {
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
                val action = BygoneScreenDetector.exclusiveEntryAction(frame.skip, frame.exit)
                if (frame.skip != null && frame.exit != null) {
                    if (!conflictLogged) ctx.log("跳过和退出同时命中，拒绝点击，重新截图确认唯一按钮")
                    conflictLogged = true
                } else if (action != null) {
                    conflictLogged = false
                    if (frame.exit != null) {
                        ctx.log("仅识别到退出图标，已确认进入旧日幻想，开始退出")
                        return action
                    }
                    if (skipClickCount == 0 || now - lastSkipTapAt >= 700) {
                        if (skipClickCount >= 3) {
                            ctx.log("多次点击跳过后动画按钮仍在")
                            return null
                        }
                        skipClickCount++
                        ctx.log("仅识别到跳过按钮，已确认进入旧日幻想，点击跳过")
                        ctx.device.tap(action.point.x, action.point.y)
                        lastSkipTapAt = ctx.elapsedRealtime()
                    }
                } else if (!waitingLogged) {
                    ctx.log("暂未识别到唯一的跳过或退出图标，继续等待")
                    waitingLogged = true
                }
            }

            val remaining = 350 - (ctx.elapsedRealtime() - roundStartedAt)
            if (remaining > 0 && ctx.elapsedRealtime() < deadline) delay(remaining)
        }
        return null
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
