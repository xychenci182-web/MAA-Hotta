package com.aliothmoon.maahotta.tasks

import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.IslandMerchantScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import kotlin.math.roundToInt

class IslandMerchantTask(
    private val accountId: String,
    private val onDetected: suspend (Boolean) -> Unit,
) : GameTask {
    override val id = "island_merchant"
    override val title = "检查人工岛老头"

    override suspend fun run(ctx: BotContext): TaskResult {
        val result = checkMerchant(ctx)
        if (!result.ok) ctx.log("人工岛老头任务检查失败：${result.detail}；不能据此判定没有老头")
        return result
    }

    private suspend fun checkMerchant(ctx: BotContext): TaskResult {
        val islandCard = ctx.templates.get("entry_island_build")
            ?: return TaskResult(title, false, "人工岛建筑卡片模板未载入")
        val islandPage = ctx.templates.get("island_page_title")
            ?: return TaskResult(title, false, "人工岛页面标题模板未载入", retryable = false)
        val merchantPortrait = ctx.templates.get("island_merchant_portrait")
            ?: return TaskResult(title, false, "人工岛老头头像模板未载入", retryable = false)

        if (TaskNavigationMachine.observe(ctx).state == com.aliothmoon.maahotta.vision.PageState.ISLAND) {
            val portrait = ctx.waitUntil(2_000, 350) { screen ->
                IslandMerchantScreenDetector.findMerchantPortrait(screen, merchantPortrait)
            }
            if (portrait != null) {
                ctx.log("状态：人工岛页，直接确认老头头像；人工岛老头检查结果：有")
                onDetected(true)
                return TaskResult(title, true, "账号 $accountId：有老头，等待记录角色名称")
            }
            // Missing portrait on an island page is not proof of absence. Recheck the hub card.
            if (!RequiredHubNavigator.returnToHub(ctx)) return TaskResult(title, false,
                "人工岛页未识别到头像，也无法返回必做页复核", retryable = false)
        }
        var lastFailure = "未进入休闲页"
        for (attempt in 1..2) {
            ctx.log("人工岛老头第 $attempt 次检查")
            if (!RequiredHubNavigator.selectLeisureByText(ctx)) {
                lastFailure = "未能进入必做页或点击休闲"
                ctx.log(lastFailure)
                continue
            }

            var card = waitForIslandCard(ctx, islandCard, 1_200)
            if (card == null) {
                ctx.log("当前可见卡片中未找到人工岛建筑，上滑查看底部卡片")
                if (!ctx.tryTaskStep("$id:scroll_grid")) break
                swipeCardGridUp(ctx)
                card = waitForIslandCard(ctx, islandCard, 2_500)
            }
            if (card == null) {
                lastFailure = "休闲页卡片网格中未识别到人工岛建筑"
                ctx.log("$lastFailure，留在必做页重试休闲")
                continue
            }

            ctx.log("已在休闲页卡片网格中识别到人工岛建筑，score=${"%.2f".format(card.score)}")
            if (waitForIslandRedDot(ctx, card.point, 1_500) == null) {
                ctx.log("人工岛建筑卡片右上角没有红点，无需进入人工岛")
                onDetected(false)
                ctx.log("人工岛老头检查结果：没有（建筑卡片未检测到红点）")
                return TaskResult(title, true, "账号 $accountId：未发现老头")
            }
            ctx.log("人工岛建筑卡片有红点，进入页面二次验证")
            ctx.log("点击人工岛建筑卡片，进入后检查右侧老头头像")
            if (!ctx.tryTaskStep("$id:enter_island")) break
            ctx.device.tap(card.point.x, card.point.y)
            if (ctx.waitUntil(4_000, 350) { screen ->
                    IslandMerchantScreenDetector.findIslandPage(screen, islandPage)
                } == null) {
                lastFailure = "点击人工岛建筑后未确认进入我的人工岛"
                ctx.log(lastFailure)
                if (!RequiredHubNavigator.returnToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure，且未能返回必做页", retryable = false)
                }
                continue
            }

            val portrait = ctx.waitUntil(2_000, 350) { screen ->
                IslandMerchantScreenDetector.findMerchantPortrait(screen, merchantPortrait)
            }
            if (portrait == null) {
                lastFailure = "卡片有红点，但人工岛页面右侧未识别到老头头像"
                ctx.log(lastFailure)
                ctx.saveTaskDiagnostic(id)
                if (!RequiredHubNavigator.returnToHub(ctx)) {
                    return TaskResult(title, false, "$lastFailure，且未能返回必做页", retryable = false)
                }
                continue
            }
            ctx.log("人工岛页面右侧识别到老头头像，score=${"%.2f".format(portrait.score)}")
            ctx.log("人工岛老头检查结果：有（已识别到老头头像）")
            if (!ctx.preserveTaskPage && !RequiredHubNavigator.returnToHub(ctx)) {
                return TaskResult(title, false, "已检查人工岛，但未能返回必做页", retryable = false)
            }
            onDetected(true)
            return TaskResult(title, true, "账号 $accountId：有老头，等待记录角色名称")
        }
        ctx.saveTaskDiagnostic(id)
        return TaskResult(title, false, "$lastFailure；重试1次后仍失败")
    }

    private suspend fun waitForIslandCard(
        ctx: BotContext,
        template: Bitmap,
        timeoutMs: Long,
    ): MatchResult? = ctx.waitUntil(timeoutMs, 400) { screen ->
        IslandMerchantScreenDetector.findIslandCard(screen, template)
    }

    private suspend fun swipeCardGridUp(ctx: BotContext) {
        val size = ctx.device.screenSize()
        if (size.x <= 0 || size.y <= 0) return
        val x = (size.x * 0.66f).roundToInt()
        ctx.device.swipe(
            x, (size.y * 0.82f).roundToInt(),
            x, (size.y * 0.34f).roundToInt(),
            350,
        )
    }

    private suspend fun waitForIslandRedDot(
        ctx: BotContext,
        cardCenter: Point,
        timeoutMs: Long,
    ): MatchResult? {
        val touchSize = ctx.device.screenSize()
        if (touchSize.x <= 0 || touchSize.y <= 0) return null
        return ctx.waitUntil(timeoutMs, 350) { screen ->
            val point = Point(
                (cardCenter.x.toDouble() * screen.width / touchSize.x).roundToInt(),
                (cardCenter.y.toDouble() * screen.height / touchSize.y).roundToInt(),
            )
            IslandMerchantScreenDetector.findIslandRedDot(screen, point)
        }
    }

}
