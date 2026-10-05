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
        val entry = ctx.templates.get("entry_dimension_trial")
            ?: return TaskResult(title, false, "次元历练入口模板未载入", retryable = false)
        val logo = ctx.templates.get("trials_dialog_logo")
            ?: return TaskResult(title, false, "历练窗口模板未载入", retryable = false)
        val participate = ctx.templates.get("trials_participate")
            ?: return TaskResult(title, false, "参与按钮模板未载入", retryable = false)
        val proxy = ctx.templates.get("trials_proxy_battle")
            ?: return TaskResult(title, false, "代理战斗模板未载入", retryable = false)
        val result = ctx.templates.get("trials_result_success")
            ?: return TaskResult(title, false, "作战成功模板未载入", retryable = false)
        val vitality = ctx.templates.get("trials_vitality_insufficient")
            ?: return TaskResult(title, false, "活力不足提示模板未载入", retryable = false)

        // Keep the normal order. Only transitions and confirmations are observed.
        val initial = TaskNavigationMachine.observe(ctx)
        if (initial.state == com.aliothmoon.maahotta.vision.PageState.TRIALS_RESULT) {
            return stopAfterBattle(ctx, "发现遗留作战奖励，本轮尚未提交代理战斗，不能确认其类型及归属")
        }
        var resultReady = false
        var proxyReady = initial.state == com.aliothmoon.maahotta.vision.PageState.TRIALS_PROXY
        if (!resultReady && !proxyReady) {
            if (waitForLogo(ctx, logo, 700) == null) {
                var opened = false
                for (attempt in 1..3) {
                    val target = enterRequiredHub(ctx, entry) ?: continue
                    ctx.log("已确认推荐页历练入口，点击进入（$attempt/3）")
                    ctx.device.tap(target.point.x, target.point.y)
                    if (waitForLogo(ctx, logo, 4_000) != null) { opened = true; break }
                    // Reuse the hub only after recognizing it; unknown screens never get a blind Back.
                    if (!RequiredHubNavigator.returnToHub(ctx)) return stopAfterBattle(ctx, "历练入口点击后页面无法确认")
                }
                if (!opened) return stopAfterBattle(ctx, "未能进入次元历练窗口")
            } else ctx.log("已在次元历练窗口，直接复用")
            if (!selectType(ctx)) return stopAfterBattle(ctx, "未能确认$title 页签")
            if (waitForVitalityInsufficient(ctx, vitality, 700) != null) return noVitality()
            for (attempt in 1..3) {
                val target = waitForParticipate(ctx, participate, 1_500)
                if (target != null) {
                    ctx.log("已确认$title 页签，点击参与（$attempt/3）")
                    ctx.device.tap(target.point.x, target.point.y)
                }
                val changed = waitForTargetOrVitality(ctx, vitality, 4_000) { frame ->
                    TrialsScreenDetector.findProxyBattle(frame, proxy)
                }
                if (changed.vitalityInsufficient) return noVitality()
                if (changed.target != null) { proxyReady = true; break }
                // A retry requires a fresh participate control on the original dialog.
                if (waitForLogo(ctx, logo, 1_000) == null) break
            }
            if (!proxyReady) return stopAfterBattle(ctx, "参与后未确认代理战斗弹窗")
        }
        if (!resultReady) {
            if (initial.state == com.aliothmoon.maahotta.vision.PageState.TRIALS_PROXY && initial.controls[type.name] == null)
                return stopAfterBattle(ctx, "代理弹窗未能确认$title 类型，不执行错误类型的战斗")
            val target = waitForProxy(ctx, proxy, 2_000)
                ?: return stopAfterBattle(ctx, "代理战斗按钮未确认")
            ctx.log("已确认代理战斗，执行一次并等待作战结果")
            ctx.markActionSubmitted("$id:proxy_battle")
            ctx.device.tap(target.point.x, target.point.y)
            resultReady = waitForSuccess(ctx, result, 30_000) != null
            if (!resultReady) return stopAfterBattle(ctx, "代理战斗后未确认作战成功，不重复执行")
            ctx.confirmActionResult()
        }
        for (attempt in 1..3) {
            if (waitForSuccess(ctx, result, 700) == null) {
                if (waitForLogo(ctx, logo, 3_000) != null)
                    return TaskResult(title, true, "已完成1次代理战斗；保留历练窗口")
                return stopAfterBattle(ctx, "奖励消失后未确认返回历练窗口")
            }
            ctx.log("已确认作战成功奖励，点击白色空白处关闭")
            tapNow(ctx, RelPoint(0.50f, if (attempt % 2 == 1) 0.41f else 0.87f))
        }
        if (waitForSuccess(ctx, result, 700) == null && waitForLogo(ctx, logo, 3_000) != null)
            return TaskResult(title, true, "已完成1次代理战斗；保留历练窗口")
        return stopAfterBattle(ctx, "作战成功奖励未能关闭")
    }

    private fun noVitality(): TaskResult = TaskResult.skipped(title, "当前活力不足，已结束历练；保留窗口")

    private suspend fun stopAfterBattle(ctx: BotContext, detail: String): TaskResult {
        ctx.log("$detail，状态无法确认，停止重试并保存当前画面")
        ctx.saveTaskDiagnostic(id)
        return TaskResult.uncertain(title, detail)
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
            val logo = ctx.templates.get("trials_dialog_logo") ?: return false
            if (waitForLogo(ctx, logo, 700) == null) return false
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

    private suspend fun waitForSelectedType(ctx: BotContext, timeoutMs: Long): Boolean =
        ctx.waitUntil(timeoutMs, 350) { screen -> TrialsScreenDetector.findSelectedType(screen, type) } != null

}
