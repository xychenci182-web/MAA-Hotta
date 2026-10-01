package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.data.TrialType
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.RelPoint
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.RequiredHubScreenDetector
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.SearchRegion
import com.aliothmoon.maahotta.vision.TrialsScreenDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class TrialsTask(private val type: TrialType) : GameTask {
    override val id = when (type) {
        TrialType.WEAPON -> "trials_weapon"
        TrialType.MATRIX -> "trials_matrix"
        TrialType.GOLD -> "trials_gold"
    }
    override val title = when (type) {
        TrialType.WEAPON -> "武器历练"
        TrialType.MATRIX -> "意志历练"
        TrialType.GOLD -> "金币历练"
    }

    override suspend fun run(ctx: BotContext): TaskResult {
        val entryTemplate = ctx.templates.get("entry_dimension_trial")
            ?: return TaskResult(title, false, "次元历练入口模板未载入")
        val logoTemplate = ctx.templates.get("trials_dialog_logo")
            ?: return TaskResult(title, false, "次元历练窗口模板未载入")
        val participateTemplate = ctx.templates.get("trials_participate")
            ?: return TaskResult(title, false, "参与按钮模板未载入")
        val proxyTemplate = ctx.templates.get("trials_proxy_battle")
            ?: return TaskResult(title, false, "代理战斗模板未载入")
        val resultTemplate = ctx.templates.get("trials_result_success")
            ?: return TaskResult(title, false, "历练成功模板未载入")
        val closeTemplate = ctx.templates.get("trials_close")
            ?: return TaskResult(title, false, "次元历练关闭按钮模板未载入")
        val vitalityTemplate = ctx.templates.get("trials_vitality_insufficient")
            ?: return TaskResult(title, false, "次元历练活力不足提示模板未载入")

        var lastFailure = "未进入次元历练"
        for (attempt in 1..3) {
            ctx.log("$title 第 $attempt 次尝试")
            val entry = enterRequiredHub(ctx, entryTemplate)
            if (entry == null) {
                lastFailure = "推荐页右下区域未识别到“次元历练”名称"
                ctx.log("$lastFailure，留在必做页重试推荐")
                if (!RequiredHubNavigator.returnToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }

            var entryButton = entry!!
            var logo: MatchResult? = null
            for (entryAttempt in 1..3) {
                ctx.log(if (entryAttempt == 1) "已识别次元历练入口，点击进入" else "历练窗口未打开，重新识别后再次点击入口")
                ctx.device.tap(entryButton.point.x, entryButton.point.y)
                val opened = waitForTargetOrVitality(ctx, vitalityTemplate, 4_000) { screen ->
                    TrialsScreenDetector.findDialogLogo(screen, logoTemplate)
                }
                if (opened.vitalityInsufficient) return completeForNoVitality(ctx)
                logo = opened.target
                if (logo != null) {
                    if (waitForVitalityInsufficient(ctx, vitalityTemplate, 900) != null) {
                        return completeForNoVitality(ctx)
                    }
                    break
                }
                val refreshed = waitForEntry(ctx, entryTemplate, 1_500)
                if (refreshed != null) {
                    entryButton = refreshed
                } else {
                    ctx.log("入口已消失，历练窗口可能仍在加载，继续等待")
                    val delayed = waitForTargetOrVitality(ctx, vitalityTemplate, 5_000) { screen ->
                        TrialsScreenDetector.findDialogLogo(screen, logoTemplate)
                    }
                    if (delayed.vitalityInsufficient) return completeForNoVitality(ctx)
                    logo = delayed.target
                    if (logo != null && waitForVitalityInsufficient(ctx, vitalityTemplate, 900) != null) {
                        return completeForNoVitality(ctx)
                    }
                    break
                }
            }
            if (logo == null) {
                lastFailure = "点击入口后未识别到次元历练窗口"
                ctx.log("$lastFailure，返回必做页重试")
                if (!recoverToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }

            if (!selectType(ctx)) {
                lastFailure = "未确认$title 页签变红"
                ctx.log("$lastFailure，返回必做页重试")
                if (!recoverToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }

            if (waitForVitalityInsufficient(ctx, vitalityTemplate, 600) != null) {
                return completeForNoVitality(ctx)
            }

            val participate = waitForParticipate(ctx, participateTemplate, 2_500)
            if (participate == null) {
                lastFailure = "未识别到参与按钮"
                ctx.log("$lastFailure，返回必做页重试")
                if (!recoverToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }
            var participateButton = participate!!
            var proxy: MatchResult? = null
            for (participateAttempt in 1..3) {
                ctx.log(if (participateAttempt == 1) "$title 已选中，点击参与" else "代理战斗未出现，重新识别后再次点击参与")
                ctx.device.tap(participateButton.point.x, participateButton.point.y)
                val participated = waitForTargetOrVitality(ctx, vitalityTemplate, 4_000) { screen ->
                    TrialsScreenDetector.findProxyBattle(screen, proxyTemplate)
                }
                if (participated.vitalityInsufficient) return completeForNoVitality(ctx)
                proxy = participated.target
                if (proxy != null) break
                val refreshed = waitForParticipate(ctx, participateTemplate, 1_500)
                if (refreshed != null) {
                    participateButton = refreshed
                } else {
                    ctx.log("参与按钮已消失，代理战斗可能仍在加载，继续等待")
                    val delayed = waitForTargetOrVitality(ctx, vitalityTemplate, 5_000) { screen ->
                        TrialsScreenDetector.findProxyBattle(screen, proxyTemplate)
                    }
                    if (delayed.vitalityInsufficient) return completeForNoVitality(ctx)
                    proxy = delayed.target
                    break
                }
            }
            if (proxy == null) {
                lastFailure = "参与后未识别到代理战斗"
                ctx.log("$lastFailure，返回必做页重试")
                if (!recoverToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure；无法返回必做页")
                }
                continue
            }
            var proxyButton = proxy!!
            var success: MatchResult? = null
            for (proxyAttempt in 1..3) {
                ctx.log(if (proxyAttempt == 1) "识别到代理战斗，点击执行" else "作战结果未出现，重新识别后再次点击代理战斗")
                ctx.device.tap(proxyButton.point.x, proxyButton.point.y)
                success = waitForSuccess(ctx, resultTemplate, if (proxyAttempt == 1) 15_000 else 8_000)
                if (success != null) break
                val refreshed = waitForProxy(ctx, proxyTemplate, 1_500)
                if (refreshed != null) {
                    proxyButton = refreshed
                } else {
                    ctx.log("代理战斗按钮已消失，战斗结果可能仍在加载，继续等待")
                    success = waitForSuccess(ctx, resultTemplate, 12_000)
                    break
                }
            }
            if (success == null) {
                return stopAfterBattle(ctx, "代理战斗后未识别到作战成功")
            }

            ctx.log("作战成功，点击奖励图标上方白色空白处关闭")
            tapNow(ctx, RelPoint(0.50f, 0.41f))
            if (waitForSuccess(ctx, resultTemplate, 700) != null) {
                ctx.log("奖励界面仍在，点击奖励图标下方白色空白处")
                tapNow(ctx, RelPoint(0.50f, 0.87f))
            }
            if (waitForSuccess(ctx, resultTemplate, 700) != null) {
                return stopAfterBattle(ctx, "未能关闭作战成功奖励界面")
            }
            if (waitForLogo(ctx, logoTemplate, 3_000) == null) {
                return stopAfterBattle(ctx, "关闭奖励后未返回次元历练窗口")
            }

            val close = waitForClose(ctx, closeTemplate, 2_000)
            if (close == null) {
                return stopAfterBattle(ctx, "未识别到次元历练窗口右上角X")
            }
            var closeButton = close!!
            var returnedToRecommend = false
            for (closeAttempt in 1..3) {
                ctx.log(if (closeAttempt == 1) "点击次元历练窗口右上角X，返回推荐页" else "窗口仍在，重新识别后再次点击X")
                ctx.device.tap(closeButton.point.x, closeButton.point.y)
                returnedToRecommend = waitForEntry(ctx, entryTemplate, 3_000) != null
                if (returnedToRecommend) break
                val refreshed = waitForClose(ctx, closeTemplate, 1_200)
                if (refreshed != null) {
                    closeButton = refreshed
                } else {
                    returnedToRecommend = waitForEntry(ctx, entryTemplate, 5_000) != null
                    break
                }
            }
            if (!returnedToRecommend) {
                return stopAfterBattle(ctx, "关闭窗口后未返回推荐页")
            }
            return TaskResult(title, true, "已完成1次代理战斗并返回推荐页")
        }
        return TaskResult(title, false, "$lastFailure；已重试3次")
    }

    private suspend fun stopAfterBattle(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，代理战斗已经点击，停止重试并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, detail, retryable = false)
    }

    private suspend fun enterRequiredHub(
        ctx: BotContext,
        entryTemplate: Bitmap,
    ): MatchResult? {
        if (!RequiredHubNavigator.selectRecommendByText(ctx)) return null
        return waitForEntry(ctx, entryTemplate, 3_000)
    }

    private suspend fun selectType(ctx: BotContext): Boolean {
        if (waitForSelectedType(ctx, 700)) return true
        val point = when (type) {
            TrialType.WEAPON -> RelPoint(0.232f, 0.466f)
            TrialType.MATRIX -> RelPoint(0.344f, 0.466f)
            TrialType.GOLD -> RelPoint(0.452f, 0.466f)
        }
        repeat(3) { attempt ->
            ctx.log(if (attempt == 0) "点击$title 页签" else "$title 页签尚未选中，再次点击")
            tapNow(ctx, point)
            if (waitForSelectedType(ctx, 2_000)) return true
        }
        return false
    }

    private suspend fun waitForEntry(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? {
        val templateMatch = ctx.waitUntil(minOf(timeoutMs, 2_000), 350) { screen ->
            RequiredHubScreenDetector.findTrialsEntry(screen, template)
        }
        if (templateMatch != null) return templateMatch
        return waitForEntryText(ctx, 2_000)
    }

    /** OCR is a fallback for unusual scaling, limited to the live card area. */
    private suspend fun waitForEntryText(ctx: BotContext, timeoutMs: Long): MatchResult? {
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
                    ScreenTextFinder.findAllInRegion(
                        screen,
                        listOf("次元历练"),
                        SearchRegion(0.68f, 0.52f, 0.98f, 0.93f),
                    ).firstOrNull()?.point
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } finally {
                screen.recycle()
            }
            if (point != null) {
                val size = ctx.device.screenSize()
                return MatchResult(
                    Point(
                        (point.x.toDouble() * size.x / width).toInt(),
                        (point.y.toDouble() * size.y / height).toInt(),
                    ),
                    1f,
                )
            }
        }
        return null
    }

    private suspend fun tapNow(ctx: BotContext, point: RelPoint) {
        val pixel = point.toPixel(ctx.device)
        ctx.device.tap(pixel.x, pixel.y)
    }

    private suspend fun waitForLogo(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> TrialsScreenDetector.findDialogLogo(screen, template) }

    private suspend fun waitForParticipate(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> TrialsScreenDetector.findParticipate(screen, template) }

    private suspend fun waitForProxy(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> TrialsScreenDetector.findProxyBattle(screen, template) }

    private data class TargetOrVitality(
        val target: MatchResult?,
        val vitalityInsufficient: Boolean,
    )

    private suspend fun waitForTargetOrVitality(
        ctx: BotContext,
        vitalityTemplate: Bitmap,
        timeoutMs: Long,
        targetFinder: (Bitmap) -> MatchResult?,
    ): TargetOrVitality {
        var vitalityInsufficient = false
        val match = ctx.waitUntil(timeoutMs, 250) { screen ->
            if (TrialsScreenDetector.findVitalityInsufficient(screen, vitalityTemplate) != null) {
                vitalityInsufficient = true
                MatchResult(Point(screen.width / 2, screen.height / 2), 1f)
            } else {
                targetFinder(screen)
            }
        }
        return TargetOrVitality(
            target = match.takeUnless { vitalityInsufficient },
            vitalityInsufficient = vitalityInsufficient,
        )
    }

    private suspend fun waitForVitalityInsufficient(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 250) { screen ->
        TrialsScreenDetector.findVitalityInsufficient(screen, template)
    }

    private suspend fun waitForSuccess(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> TrialsScreenDetector.findResultSuccess(screen, template) }

    private suspend fun waitForClose(ctx: BotContext, template: Bitmap, timeoutMs: Long): MatchResult? =
        ctx.waitUntil(timeoutMs, 400) { screen -> TrialsScreenDetector.findClose(screen, template) }

    private suspend fun waitForSelectedType(ctx: BotContext, timeoutMs: Long): Boolean =
        ctx.waitUntil(timeoutMs, 350) { screen -> TrialsScreenDetector.findSelectedType(screen, type) } != null

    private suspend fun completeForNoVitality(ctx: BotContext): TaskResult {
        ctx.log("识别到“当前活力不足，将无法获得结算奖励”，次元历练按已完成处理")
        val returnedToHub = recoverToHub(ctx)
        return TaskResult(
            title,
            true,
            if (returnedToHub) {
                "当前活力不足，已结束历练并返回推荐页"
            } else {
                "当前活力不足，已按任务完成处理"
            },
        )
    }

    private suspend fun recoverToHub(ctx: BotContext): Boolean {
        val resultTemplate = ctx.templates.get("trials_result_success")
        if (resultTemplate != null && waitForSuccess(ctx, resultTemplate, 500) != null) {
            ctx.log("关闭次元历练奖励界面")
            tapNow(ctx, RelPoint(0.50f, 0.41f))
        }
        val closeTemplate = ctx.templates.get("trials_close")
        if (closeTemplate != null) {
            val close = waitForClose(ctx, closeTemplate, 600)
            if (close != null) {
                ctx.log("关闭次元历练窗口")
                ctx.device.tap(close.point.x, close.point.y)
            }
        }
        return RequiredHubNavigator.returnToHub(ctx)
    }
}
