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

            waitThroughEntryAnimation(
                ctx = ctx,
                skipTemplate = skipTemplate,
                diveNextTemplate = diveNextTemplate,
                exitIconTemplate = exitIconTemplate,
                timeoutMs = 45_000,
            ) ?: return stopAfterDive(ctx, "点击潜入后等待动画结束，仍未识别到左上角退出图标")
            ctx.log("已进入旧日幻想副本，开始重新定位退出图标")

            var confirm: MatchResult? = null
            for (exitAttempt in 1..3) {
                // Inspect both states in the same fresh screenshot. Never reuse an
                // old exit coordinate, or click through a partially loaded dialog.
                var dialogVisible = false
                val target = ctx.waitUntil(3_000, 350) { screen ->
                    dialogVisible = BygoneScreenDetector.findExitDialog(screen, exitDialogTemplate) != null
                    if (dialogVisible) {
                        BygoneScreenDetector.findExitConfirm(screen, confirmTemplate)
                    } else {
                        BygoneScreenDetector.findExitIcon(screen, exitIconTemplate)
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
    )

    /** Read the possible animation states from one screenshot, in action priority order. */
    private suspend fun readEntryFrame(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveTemplate: Bitmap,
        exitTemplate: Bitmap,
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
            val exit = BygoneScreenDetector.findExitIcon(screen, exitTemplate)
            if (exit != null) {
                // The previous floor page has an exit-like mark. An exit is only
                // actionable when the dive button is absent in this same frame.
                val dive = BygoneScreenDetector.findDiveNext(screen, diveTemplate)
                if (dive == null) return EntryFrame(exit = toTouch(exit))
                val skip = BygoneScreenDetector.findSkip(screen, skipTemplate)
                return if (skip != null) EntryFrame(skip = toTouch(skip))
                else EntryFrame(dive = toTouch(dive))
            }
            val skip = BygoneScreenDetector.findSkip(screen, skipTemplate)
            if (skip != null) return EntryFrame(skip = toTouch(skip))
            return EntryFrame(dive = toTouch(BygoneScreenDetector.findDiveNext(screen, diveTemplate)))
        } finally {
            screen.recycle()
        }
    }

    private suspend fun waitThroughEntryAnimation(
        ctx: BotContext,
        skipTemplate: Bitmap,
        diveNextTemplate: Bitmap,
        exitIconTemplate: Bitmap,
        timeoutMs: Long,
    ): MatchResult? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        var lastDiveTapAt = ctx.elapsedRealtime()
        var diveTapCount = 1
        var lastSkipTapAt = 0L
        var skipClickCount = 0
        var exitStreak = 0
        var naturalWaitLogged = false
        while (ctx.elapsedRealtime() < deadline) {
            val roundStartedAt = ctx.elapsedRealtime()
            val frame = readEntryFrame(ctx, skipTemplate, diveNextTemplate, exitIconTemplate)
            val now = ctx.elapsedRealtime()
            val sinceDiveTap = now - lastDiveTapAt
            when {
                frame?.skip != null -> {
                    exitStreak = 0
                    if (skipClickCount == 0 || now - lastSkipTapAt >= 700) {
                        if (skipClickCount >= 3) {
                            ctx.log("多次点击跳过后动画按钮仍在")
                            return null
                        }
                        skipClickCount++
                        ctx.log("识别到旧日幻想动画跳过按钮，点击跳过")
                        ctx.device.tap(frame.skip.point.x, frame.skip.point.y)
                        lastSkipTapAt = ctx.elapsedRealtime()
                    }
                }
                frame?.dive != null -> {
                    exitStreak = 0
                    if (sinceDiveTap >= 1_800) {
                        if (diveTapCount >= 3) {
                            ctx.log("多次点击潜入按钮后仍停留在原页面")
                            return null
                        }
                        diveTapCount++
                        ctx.log("潜入按钮仍在，重新识别并点击")
                        ctx.device.tap(frame.dive.point.x, frame.dive.point.y)
                        lastDiveTapAt = ctx.elapsedRealtime()
                    }
                }
                frame?.exit != null -> {
                    // The floor page can contain an exit-like mark. Require two
                    // different polling frames after the dive button vanishes.
                    exitStreak++
                    if (exitStreak >= 2) {
                        ctx.log("已确认潜入按钮消失，退出图标连续识别成功")
                        return frame.exit
                    }
                }
                else -> exitStreak = 0
            }

            if (skipClickCount == 0 && !naturalWaitLogged && sinceDiveTap >= 8_000) {
                ctx.log("暂未出现跳过或退出图标，继续观察动画")
                naturalWaitLogged = true
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
