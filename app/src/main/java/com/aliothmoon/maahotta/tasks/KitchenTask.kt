package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.KitchenScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.ScreenTextMatch
import com.aliothmoon.maahotta.vision.SearchRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class KitchenTask : GameTask {
    override val id = "kitchen"
    override val title = "MIA 私厨"

    private val rewardPopupOutside = RelPoint(0.50f, 0.10f)

    // Three daily meals are expected; the extra two taps allow for missed input.
    // Completion is determined only by the mark on the Must-do card.
    private val maxTasteTaps = 5
    private val maxNavigationFailures = 3

    override suspend fun run(ctx: BotContext): TaskResult {
        val entryTemplate = ctx.templates.get("entry_mia_kitchen")
            ?: return TaskResult(title, false, "MIA 私厨入口模板未载入", retryable = false)
        val completedTemplate = ctx.templates.get("mia_completed")
            ?: return TaskResult(title, false, "MIA 私厨完成勾选模板未载入", retryable = false)
        val tasteTemplate = ctx.templates.get("btn_eat")
            ?: return TaskResult(title, false, "品尝按钮模板未载入", retryable = false)
        val rewardPopupTemplate = ctx.templates.get("mail_reward_popup")
            ?: return TaskResult(title, false, "奖励弹窗模板未载入", retryable = false)

        var taps = 0
        var navigationFailures = 0
        while (taps < maxTasteTaps) {
            // The tasting reward can appear after the first return-to-hub check.
            // Clear it before trying to recognize the hub tabs again.
            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 500)) {
                return stopWithScreenshot(ctx, "私厨奖励弹窗无法关闭")
            }
            if (!RequiredHubNavigator.selectRecommendByText(ctx)) {
                navigationFailures++
                if (navigationFailures >= maxNavigationFailures) {
                    return stopWithScreenshot(ctx, "多次未能确认必做页左上角标题和左侧推荐")
                }
                ctx.log("暂未确认必做和推荐，重新截图进入")
                continue
            }

            if (waitForCompleted(ctx, completedTemplate, 1_200)) {
                return completed(taps)
            }

            val entry = waitForEntry(ctx, entryTemplate, 3_000)
            if (entry == null) {
                navigationFailures++
                if (navigationFailures >= maxNavigationFailures) {
                    return stopWithScreenshot(ctx, "推荐页右上角多次未识别到 MIA 私厨入口")
                }
                ctx.log("右上角暂未识别到 MIA 私厨入口，重新确认推荐页")
                continue
            }

            // The same card remains visible after completion. Never open it
            // unless the completion mark is still absent.
            if (waitForCompleted(ctx, completedTemplate, 700)) {
                return completed(taps)
            }

            val taste = openKitchen(ctx, entry, entryTemplate, tasteTemplate)
            if (taste == null) {
                val returned = RequiredHubNavigator.returnToHub(ctx)
                if (!returned) return stopWithScreenshot(ctx, "进入私厨后未识别到品尝，也未能返回必做页")
                if (waitForCompleted(ctx, completedTemplate, 1_200)) return completed(taps)
                navigationFailures++
                if (navigationFailures >= maxNavigationFailures) {
                    return stopWithScreenshot(ctx, "多次点击私厨入口后，右下角仍未识别到品尝")
                }
                ctx.log("私厨按钮暂未出现，返回必做页重新识别入口")
                continue
            }

            navigationFailures = 0
            taps++
            ctx.log("右下角已识别品尝，点击识别位置（第 $taps 次点击）")
            ctx.device.tap(taste.x, taste.y)
            if (!waitForTasteToDisappear(ctx, tasteTemplate, 3_000)) {
                ctx.log("点击后未确认品尝按钮消失，返回必做页复查完成状态")
            }
            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 700)) {
                return stopWithScreenshot(ctx, "品尝后奖励弹窗无法关闭")
            }
            if (!RequiredHubNavigator.returnToHub(ctx)) {
                return stopWithScreenshot(ctx, "品尝后未能返回必做页")
            }
            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 1_500)) {
                return stopWithScreenshot(ctx, "必做页奖励弹窗无法关闭")
            }
            if (waitForCompleted(ctx, completedTemplate, 3_000)) {
                return completed(taps)
            }
            ctx.log("MIA 私厨尚未打勾，继续进入私厨品尝")
        }
        return stopWithScreenshot(ctx, "点击品尝 $taps 次后必做页仍未打勾，已停止以防卡死")
    }

    private suspend fun openKitchen(
        ctx: BotContext,
        firstEntry: MatchResult,
        entryTemplate: Bitmap,
        tasteTemplate: Bitmap,
    ): Point? {
        var entry = firstEntry
        repeat(3) { attempt ->
            val size = ctx.device.screenSize()
            val cardY = (entry.point.y + size.y * 0.16f).toInt().coerceIn(0, size.y - 1)
            ctx.log(
                if (attempt == 0) "识别到右上角 MIA 私厨标题，点击下方卡片主体 (${entry.point.x},$cardY)"
                else "仍在推荐页，重新识别后点击私厨卡片主体 (${entry.point.x},$cardY)",
            )
            ctx.device.tap(entry.point.x, cardY)
            val taste = waitForTaste(ctx, tasteTemplate, 2_500)
            if (taste != null) return taste

            val refreshed = waitForEntry(ctx, entryTemplate, 1_200)
            if (refreshed != null) {
                entry = refreshed
            } else {
                val delayed = waitForTaste(ctx, tasteTemplate, 2_500)
                if (delayed != null) return delayed
                return null
            }
        }
        return null
    }

    /** OCR only the lower-right button; the count label above it is excluded. */
    private suspend fun findTasteOnScreen(screen: Bitmap, template: Bitmap): Point? {
        KitchenScreenDetector.findTaste(screen, template)?.let { return it.point }
        return ScreenTextFinder.findAllInRegion(
            screen,
            listOf("品尝"),
            SearchRegion(0.75f, 0.79f, 0.98f, 0.92f),
        ).firstOrNull()?.point
    }

    private suspend fun waitForTaste(ctx: BotContext, template: Bitmap, timeoutMs: Long): Point? {
        val deadline = ctx.deadlineAfter(timeoutMs)
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen == null) {
                delay(100)
                continue
            }
            val width = screen.width
            val height = screen.height
            val match = try {
                withTimeoutOrNull(minOf(8_000L, deadline - ctx.elapsedRealtime()).coerceAtLeast(1L)) {
                    findTasteOnScreen(screen, template)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } finally {
                screen.recycle()
            }
            if (match != null) {
                val size = ctx.device.screenSize()
                return Point(
                    (match.x.toDouble() * size.x / width).toInt(),
                    (match.y.toDouble() * size.y / height).toInt(),
                )
            }
        }
        return null
    }

    private suspend fun waitForTasteToDisappear(ctx: BotContext, template: Bitmap, timeoutMs: Long): Boolean {
        val deadline = ctx.deadlineAfter(timeoutMs)
        var absentFrames = 0
        while (ctx.elapsedRealtime() < deadline) {
            val screen = ctx.device.screenshot()
            if (screen == null) {
                delay(100)
                continue
            }
            val visible = try {
                withTimeoutOrNull(minOf(8_000L, deadline - ctx.elapsedRealtime()).coerceAtLeast(1L)) {
                    findTasteOnScreen(screen, template) != null
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } finally {
                screen.recycle()
            }
            if (visible == false) {
                absentFrames++
                if (absentFrames >= 2) return true
            } else {
                absentFrames = 0
            }
        }
        return false
    }

    private suspend fun dismissRewardPopup(
        ctx: BotContext,
        template: Bitmap,
        firstWaitMs: Long,
    ): Boolean {
        if (ctx.waitUntil(firstWaitMs, 250) { screen ->
            KitchenScreenDetector.findRewardPopup(screen, template)
        } == null) return true
        repeat(3) { attempt ->
            ctx.log(if (attempt == 0) "识别到奖励弹窗，点击上方空白处关闭" else "奖励弹窗仍在，再次点击上方空白处")
            ctx.tap(rewardPopupOutside, 450)
            val remaining = ctx.waitUntil(700, 250) { screen ->
                KitchenScreenDetector.findRewardPopup(screen, template)
            }
            if (remaining == null) return true
        }
        return false
    }

    private suspend fun waitForEntry(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> KitchenScreenDetector.findEntry(screen, template) }

    private suspend fun waitForCompleted(ctx: BotContext, template: Bitmap, timeoutMs: Long): Boolean =
        ctx.waitUntil(timeoutMs, 400) { screen -> KitchenScreenDetector.findCompleted(screen, template) } != null

    private fun completed(taps: Int): TaskResult = TaskResult(
        title,
        true,
        if (taps == 0) "必做页已打勾，MIA 私厨已完成" else "点击品尝 $taps 次后必做页已打勾",
    )

    private suspend fun stopWithScreenshot(ctx: BotContext, detail: String): TaskResult {
        ctx.log(detail)
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }
}
