package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.Layout
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.KitchenScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.SearchRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class KitchenTask : GameTask {
    override val id = "kitchen"
    override val title = "MIA 私厨"

    private val rewardPopupOutside = RelPoint(0.50f, 0.10f)

    // Popup checks begin after the first taste; taste → back → hub mark until completion.
    private val maxNavigationFailures = 2
    private var tasteRound = 0

    override suspend fun run(ctx: BotContext): TaskResult {
        val entryTemplate = ctx.templates.get("entry_mia_kitchen")
            ?: return TaskResult(title, false, "MIA 私厨入口模板未载入", retryable = false)
        val completedTemplate = ctx.templates.get("mia_completed")
            ?: return TaskResult(title, false, "MIA 私厨完成勾选模板未载入", retryable = false)
        val tasteTemplate = ctx.templates.get("btn_eat")
            ?: return TaskResult(title, false, "品尝按钮模板未载入", retryable = false)
        val rewardPopupTemplate = ctx.templates.get("mail_reward_popup")
            ?: return TaskResult(title, false, "奖励弹窗模板未载入", retryable = false)

        return runKitchen(ctx, entryTemplate, completedTemplate, tasteTemplate, rewardPopupTemplate, 0)
    }

    private suspend fun runKitchen(
        ctx: BotContext,
        entryTemplate: Bitmap,
        completedTemplate: Bitmap,
        tasteTemplate: Bitmap,
        rewardPopupTemplate: Bitmap,
        initialTaps: Int,
    ): TaskResult {
        var taps = initialTaps
        tasteRound = initialTaps
        var navigationFailures = 0
        var alreadyInKitchen = ctx.device.screenshot()?.let { screen ->
            try {
                KitchenScreenDetector.findTaste(screen, tasteTemplate)?.score?.let { it >= 0.70f } == true
            } finally {
                screen.recycle()
            }
        } ?: false

        while (true) {
            currentCoroutineContext().ensureActive()
            val clickRewardTemplate = rewardPopupTemplate.takeIf { taps > 0 }
            if (!dismissRewardPopup(ctx, clickRewardTemplate, 400)) {
                return stopWithScreenshot(ctx, "奖励弹窗无法关闭")
            }
            if (!alreadyInKitchen) {
                if (!selectRecommend(ctx, clickRewardTemplate)) {
                    navigationFailures++
                    if (navigationFailures >= maxNavigationFailures) {
                        return stopWithScreenshot(ctx, "多次未能确认必做页左上角标题和左侧推荐")
                    }
                    ctx.log("暂未确认必做和推荐，重新截图进入")
                    continue
                }

                if (waitForCompleted(ctx, completedTemplate, clickRewardTemplate, 1_500)) {
                    return completed(taps)
                }

                if (waitForEntry(ctx, entryTemplate, clickRewardTemplate, 3_000) == null) {
                    navigationFailures++
                    if (navigationFailures >= maxNavigationFailures) {
                        return stopWithScreenshot(ctx, "推荐页右上角多次未识别到 MIA 私厨入口")
                    }
                    ctx.log("右上角暂未识别到 MIA 私厨入口，重新确认推荐页")
                    continue
                }

                if (waitForCompleted(ctx, completedTemplate, clickRewardTemplate, 700)) {
                    return completed(taps)
                }

                val taste = openKitchen(ctx, entryTemplate, tasteTemplate, completedTemplate, clickRewardTemplate)
                if (taste == null) {
                    if (!exitKitchenToHub(ctx, clickRewardTemplate)) {
                        return stopWithScreenshot(ctx, "私厨入口点击后无法清理弹窗并回到必做页")
                    }
                    if (waitForCompleted(ctx, completedTemplate, clickRewardTemplate, 1_200)) return completed(taps)
                    navigationFailures++
                    if (navigationFailures >= maxNavigationFailures) {
                        return stopWithScreenshot(ctx, "多次点击私厨入口后，右下角仍未识别到品尝")
                    }
                    ctx.log("私厨按钮暂未出现，返回必做页重新识别入口")
                    continue
                }
            } else {
                if (waitForTaste(ctx, tasteTemplate, clickRewardTemplate, 2_000) == null) {
                    return stopWithScreenshot(ctx, "私厨页未确认品尝按钮")
                }
                alreadyInKitchen = false
            }

            // Popup dismissal can invalidate a previously located button; locate it again before submitting.
            if (!dismissRewardPopup(ctx, clickRewardTemplate, 400)) {
                return stopWithScreenshot(ctx, "品尝前奖励弹窗无法关闭")
            }
            val taste = waitForTaste(ctx, tasteTemplate, clickRewardTemplate, 2_000)
                ?: return stopWithScreenshot(ctx, "未确认品尝按钮")
            navigationFailures = 0
            taps++
            tasteRound = taps
            ctx.log(
                if (taps == 1) "右下角已识别品尝，首次点击前不检查弹窗"
                else "确认无弹窗且右下角已识别品尝，点击识别位置（第 $taps 次点击）",
            )
            val submittedTasteNumber = taps
            var submittedTasteConfirmed = false
            ctx.onTaskFailureRecovery { screen, page ->
                val rewardVisible = KitchenScreenDetector.findRewardPopup(screen, rewardPopupTemplate) != null
                if (page.state == com.aliothmoon.maahotta.vision.PageState.HUB &&
                    !rewardVisible && KitchenScreenDetector.findCompleted(screen, completedTemplate) != null) {
                    if (ctx.safety.pendingStepId != null) ctx.confirmActionResult()
                    completed(submittedTasteNumber)
                } else {
                    // A visible reward or a previously confirmed return proves this submission succeeded.
                    // Merely finding the taste button again is not permission to repeat the same taste.
                    if (rewardVisible) submittedTasteConfirmed = true
                    if (!submittedTasteConfirmed || !dismissRewardPopup(ctx, rewardPopupTemplate, 400) ||
                        !exitKitchenToHub(ctx, rewardPopupTemplate)) {
                        null
                    } else {
                        if (ctx.safety.pendingStepId != null) ctx.confirmActionResult()
                        if (waitForCompleted(ctx, completedTemplate, rewardPopupTemplate, 3_000)) {
                            completed(submittedTasteNumber)
                        } else {
                            // Continue later normal tastes with the original count; never report completion
                            // solely because one taste's reward was closed.
                            runKitchen(ctx, entryTemplate, completedTemplate, tasteTemplate,
                                rewardPopupTemplate, submittedTasteNumber)
                        }
                    }
                }
            }
            ctx.markActionSubmitted("$id:taste_$taps")
            ctx.device.tap(taste.x, taste.y)

            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 700)) {
                return stopWithScreenshot(ctx, "退出私厨前奖励弹窗无法关闭")
            }
            ctx.log("确认无弹窗，点击左上角退出 Mi-a 私厨并检查必做页")
            if (!ctx.tryTaskStep("$id:back_$tasteRound")) {
                return stopWithScreenshot(ctx, "本轮退出私厨重试1次后仍未完成")
            }
            ctx.tap(Layout.back, 0)
            if (!exitKitchenToHub(ctx, rewardPopupTemplate, initialBackSubmitted = true)) {
                return stopWithScreenshot(ctx, "品尝后未能退出私厨并回到必做页")
            }
            submittedTasteConfirmed = true
            ctx.confirmActionResult()

            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 800)) {
                return stopWithScreenshot(ctx, "必做页奖励弹窗无法关闭")
            }
            if (waitForCompleted(ctx, completedTemplate, rewardPopupTemplate, 3_000)) {
                return completed(taps)
            }
            ctx.log("必做页 MIA 私厨尚未打勾，再次进入品尝")
        }
    }

    private suspend fun openKitchen(
        ctx: BotContext,
        entryTemplate: Bitmap,
        tasteTemplate: Bitmap,
        completedTemplate: Bitmap,
        rewardPopupTemplate: Bitmap?,
    ): Point? {
        repeat(2) { attempt ->
            if (!dismissRewardPopup(ctx, rewardPopupTemplate, 400)) return null
            if (waitForCompleted(ctx, completedTemplate, rewardPopupTemplate, 400)) return null
            val entry = waitForEntry(ctx, entryTemplate, rewardPopupTemplate, 1_200) ?: return null
            val size = ctx.device.screenSize()
            val cardY = (entry.point.y + size.y * 0.16f).toInt().coerceIn(0, size.y - 1)
            ctx.log(
                if (attempt == 0) "识别到右上角 MIA 私厨标题，点击下方卡片主体 (${entry.point.x},$cardY)"
                else "仍在推荐页，重新识别后点击私厨卡片主体 (${entry.point.x},$cardY)",
            )
            if (!ctx.tryTaskStep("$id:entry_${tasteRound + 1}")) return null
            ctx.device.tap(entry.point.x, cardY)
            val taste = waitForTaste(ctx, tasteTemplate, rewardPopupTemplate, 2_500)
            if (taste != null) return taste

            if (waitForEntry(ctx, entryTemplate, rewardPopupTemplate, 1_200) == null) {
                val delayed = waitForTaste(ctx, tasteTemplate, rewardPopupTemplate, 2_500)
                if (delayed != null) return delayed
                return null
            }
        }
        return null
    }

    /**
     * Clear any reward popup with a blank tap, then use top-left back until the Must-do hub is stable.
     */
    private suspend fun exitKitchenToHub(
        ctx: BotContext,
        rewardTemplate: Bitmap?,
        initialBackSubmitted: Boolean = false,
    ): Boolean {
        // The taste path has already submitted its initial Back; permit only one further click.
        repeat(if (initialBackSubmitted) 1 else 2) { attempt ->
            if (!dismissRewardPopup(ctx, rewardTemplate, if (attempt == 0) 700 else 300)) {
                return false
            }
            if (selectRecommend(ctx, rewardTemplate)) return true
            if (!dismissRewardPopup(ctx, rewardTemplate, 300)) return false
            // A popup can have interrupted the tab lookup on an already open hub.
            if (selectRecommend(ctx, rewardTemplate)) return true
            if (!dismissRewardPopup(ctx, rewardTemplate, 300)) return false
            // An exhausted tab budget on the hub must not trigger an unrelated Back click.
            if (RequiredHubNavigator.isCurrentHubByText(ctx)) return false
            ctx.log(
                if (attempt == 0) "点击左上角返回退出私厨"
                else "仍未回到必做页，再次点击左上角返回",
            )
            if (!ctx.tryTaskStep("$id:back_$tasteRound")) return false
            ctx.tap(Layout.back, 500)
        }
        if (!dismissRewardPopup(ctx, rewardTemplate, 500)) return false
        return selectRecommend(ctx, rewardTemplate)
    }

    private suspend fun selectRecommend(ctx: BotContext, rewardTemplate: Bitmap?): Boolean =
        if (rewardTemplate == null) RequiredHubNavigator.selectRecommendOnCurrentPage(
            ctx, stepId = "$id:recommend_$tasteRound",
        )
        else RequiredHubNavigator.selectRecommendOnCurrentPage(ctx, stepId = "$id:recommend_$tasteRound") {
            dismissRewardPopup(ctx, rewardTemplate, 300)
        }

    private suspend fun dismissRewardPopup(
        ctx: BotContext,
        template: Bitmap?,
        firstWaitMs: Long,
    ): Boolean {
        if (template == null) return true
        if (ctx.waitUntil(firstWaitMs, 250) { screen ->
                KitchenScreenDetector.findRewardPopup(screen, template)
            } == null
        ) {
            return true
        }
        repeat(2) { attempt ->
            ctx.log(
                if (attempt == 0) "识别到奖励弹窗，点击上方空白处关闭"
                else "奖励弹窗仍在，再次点击上方空白处",
            )
            if (!ctx.tryTaskStep("$id:reward_$tasteRound")) return false
            ctx.tap(rewardPopupOutside, 450)
            val remaining = ctx.waitUntil(700, 250) { screen ->
                KitchenScreenDetector.findRewardPopup(screen, template)
            }
            if (remaining == null) return true
        }
        return false
    }

    /** OCR only the lower-right button; the count label above it is excluded. */
    private suspend fun findTasteOnScreen(screen: Bitmap, template: Bitmap, rewardTemplate: Bitmap?): Point? {
        if (rewardTemplate != null && KitchenScreenDetector.findRewardPopup(screen, rewardTemplate) != null) return null
        KitchenScreenDetector.findTaste(screen, template)?.let { return it.point }
        return ScreenTextFinder.findAllInRegion(
            screen,
            listOf("品尝"),
            SearchRegion(0.75f, 0.79f, 0.98f, 0.92f),
        ).firstOrNull()?.point
    }

    private suspend fun waitForTaste(ctx: BotContext, template: Bitmap, rewardTemplate: Bitmap?, timeoutMs: Long): Point? {
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
                    findTasteOnScreen(screen, template, rewardTemplate)
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

    private suspend fun waitForEntry(ctx: BotContext, template: Bitmap, rewardTemplate: Bitmap?, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen ->
            if (rewardTemplate != null && KitchenScreenDetector.findRewardPopup(screen, rewardTemplate) != null) null
            else KitchenScreenDetector.findEntry(screen, template)
        }

    private suspend fun waitForCompleted(ctx: BotContext, template: Bitmap, rewardTemplate: Bitmap?, timeoutMs: Long): Boolean =
        ctx.waitUntil(timeoutMs, 400) { screen ->
            if (rewardTemplate != null && KitchenScreenDetector.findRewardPopup(screen, rewardTemplate) != null) null
            else KitchenScreenDetector.findCompleted(screen, template)
        } != null

    private fun completed(taps: Int): TaskResult = TaskResult(
        title,
        true,
        if (taps == 0) "必做页已打勾，MIA 私厨已完成" else "点击品尝 $taps 次后必做页已打勾",
        outcome = if (taps == 0) com.aliothmoon.maahotta.engine.TaskOutcome.ALREADY_COMPLETED
            else com.aliothmoon.maahotta.engine.TaskOutcome.COMPLETED,
    )

    private suspend fun stopWithScreenshot(ctx: BotContext, detail: String): TaskResult {
        ctx.log(detail)
        ctx.saveTaskDiagnostic(id)
        return TaskResult.uncertain(title, detail)
    }
}
