package com.aliothmoon.maahotta.tasks

import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import java.time.LocalDate

/** Runs a task only on its configured ISO weekdays (Monday=1, Sunday=7). */
class WeekdayTask(
    private val delegate: GameTask,
    private val weekdays: Set<Int>,
) : GameTask {
    override val id: String = delegate.id
    override val title: String = delegate.title
    override fun shouldNavigate(): Boolean = LocalDate.now().dayOfWeek.value in weekdays

    override suspend fun run(ctx: BotContext): TaskResult {
        val today = LocalDate.now().dayOfWeek.value
        if (today !in weekdays) {
            val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[today - 1]
            ctx.log("$title 未设置在${day}执行，跳过本次任务")
            return TaskResult(title, true, "${day}未设置执行，已跳过")
        }
        return delegate.run(ctx)
    }
}
