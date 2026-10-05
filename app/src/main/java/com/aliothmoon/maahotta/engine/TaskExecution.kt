package com.aliothmoon.maahotta.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class TaskOutcome { COMPLETED, ALREADY_COMPLETED, SKIPPED, UNCERTAIN, FAILED }

data class TaskResult(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
    val retryable: Boolean = true,
    val outcome: TaskOutcome = if (ok) TaskOutcome.COMPLETED else TaskOutcome.FAILED,
) {
    companion object {
        fun alreadyCompleted(name: String, detail: String) =
            TaskResult(name, true, detail, outcome = TaskOutcome.ALREADY_COMPLETED)

        fun uncertain(name: String, detail: String) =
            TaskResult(name, false, detail, retryable = false, outcome = TaskOutcome.UNCERTAIN)

        fun skipped(name: String, detail: String) =
            TaskResult(name, true, detail, outcome = TaskOutcome.SKIPPED)
    }
}

/** Pending result and submission history are separate: a later cleanup failure must not replay a reward. */
class TaskSafetyState {
    var taskId: String? = null
        private set
    var pendingStepId: String? = null
        private set
    var hasSubmittedAction: Boolean = false
        private set

    fun beginTask(id: String) {
        check(pendingStepId == null) { "上一动作结果未确认，禁止开始新任务" }
        taskId = id
        hasSubmittedAction = false
    }

    fun submit(stepId: String) {
        check(pendingStepId == null) { "动作 $pendingStepId 结果未确认，禁止再次提交" }
        require(stepId.isNotBlank())
        pendingStepId = stepId
        hasSubmittedAction = true
    }

    fun confirm() {
        check(pendingStepId != null) { "没有待确认的提交动作" }
        pendingStepId = null
    }
}

/** In-memory identity proof is invalidated on login/reconnect; never persist it as proof for a new process. */
class AccountSession {
    var verifiedAccountId: String? = null
        private set

    fun isVerifiedFor(accountId: String): Boolean = verifiedAccountId == accountId
    fun invalidate() { verifiedAccountId = null }
    fun verify(accountId: String) {
        require(accountId.isNotBlank())
        verifiedAccountId = accountId
    }
}

/** Android-free execution policy, exercised with injected actions and disconnect sequences. */
internal class TaskAttemptRunner(
    private val safety: TaskSafetyState,
    private val log: (String) -> Unit,
    private val detectDisconnect: suspend () -> Boolean,
    private val relogin: (suspend () -> TaskResult)?,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onException: (Throwable) -> Unit = {},
) {
    suspend fun run(
        title: String,
        allowRetry: Boolean = true,
        allowRecovery: Boolean = true,
        execute: suspend () -> TaskResult,
    ): TaskResult {
        var attempt = 1
        var reconnects = 0
        while (true) {
            log(if (attempt == 1) "—— 开始 $title ——" else "—— 重试 $title ——")
            var result = try {
                execute()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onException(error)
                if (safety.hasSubmittedAction) TaskResult.uncertain(title, "动作已经提交，后续异常：${error.message ?: "未知异常"}")
                else TaskResult(title, false, error.message ?: "异常")
            }
            if (result.ok && safety.pendingStepId != null) {
                result = TaskResult.uncertain(title, "动作 ${safety.pendingStepId} 尚未确认，禁止将任务判为成功")
            }
            if (!result.ok && (safety.hasSubmittedAction || result.outcome == TaskOutcome.UNCERTAIN || !allowRetry)) {
                result = result.copy(retryable = false)
            }
            // An unresolved submitted action must stop before reconnecting or replaying a task.
            if (!result.ok && !result.retryable) return result
            // Account transition owns both account identities. Never invoke the old account's recovery here.
            if (allowRecovery) {
                val disconnected = try {
                    detectDisconnect()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onException(error)
                    return TaskResult.uncertain(title, "无法确认连接状态，停止运行：${error.message ?: "异常"}")
                }
                if (disconnected) {
                    val recover = relogin
                    if (recover == null || reconnects >= 2) {
                        return TaskResult(title, false, "掉线后无法继续重新登录", retryable = false)
                    }
                    reconnects++
                    log("掉线已确认，重新登录本任务所属账号（$reconnects/2）")
                    wait(1_000)
                    val loginResult = try {
                        recover()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        onException(error)
                        TaskResult.uncertain("重新登录", error.message ?: "重新登录异常")
                    }
                    if (!loginResult.ok) return TaskResult.uncertain(title, "掉线后重新登录失败：${loginResult.detail}")
                    if (result.ok) return result
                    log("身份重新确认，继续执行尚未提交的任务：$title")
                    continue
                }
            }
            if (result.ok || attempt >= 2) return result
            log("$title 首次执行失败：${result.detail}，等待后重试一次")
            wait(1_500)
            attempt++
        }
    }
}
