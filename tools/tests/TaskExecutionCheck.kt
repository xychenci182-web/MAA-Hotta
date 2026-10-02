package com.aliothmoon.maahotta.engine

import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Exercise the production runner without Android or real delays. */
object TaskExecutionCheck {
    private class Harness {
        val safety = TaskSafetyState().apply { beginTask("test-task") }
        var executions = 0
        var disconnectChecks = 0
        var relogins = 0
        val waits = mutableListOf<Long>()
        val errors = mutableListOf<Throwable>()
        var disconnect: suspend () -> Boolean = { false }
        var recover: suspend () -> TaskResult = { TaskResult("login", true) }
        var waitAction: suspend (Long) -> Unit = {}

        suspend fun run(
            allowRetry: Boolean = true,
            allowRecovery: Boolean = true,
            execute: suspend Harness.() -> TaskResult,
        ): TaskResult = TaskAttemptRunner(
            safety = safety,
            log = {},
            detectDisconnect = { disconnectChecks++; disconnect() },
            relogin = { relogins++; recover() },
            wait = { waits += it; waitAction(it) },
            onException = { errors += it },
        ).run("task", allowRetry, allowRecovery) {
            executions++
            execute()
        }

        fun assertNoReplay(result: TaskResult) {
            check(result.outcome == TaskOutcome.UNCERTAIN && !result.retryable && !result.ok)
            check(executions == 1 && disconnectChecks == 0 && relogins == 0)
            check(waits.isEmpty())
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val phoneA = "13812345678"
        val phoneB = "13899995678"
        check(AccountIdentity.matches("138****5678", phoneA, listOf(phoneA)))
        check(AccountIdentity.matches("138****5678", "+86$phoneA", listOf(phoneA, "+86$phoneA")))
        check(!AccountIdentity.matches("138****5678", phoneA, listOf(phoneA, phoneB)))
        check(!AccountIdentity.matches("138****5678", phoneB, listOf(phoneA, phoneB)))
        check(AccountIdentity.matches("+86$phoneA", phoneA, listOf(phoneA, phoneB)))
        check(!AccountIdentity.matches(phoneB, phoneA, listOf(phoneA, phoneB)))
        check(!AccountIdentity.matches("$phoneA $phoneB", phoneA, listOf(phoneA, phoneB)))
        check(!AccountIdentity.matches("", phoneA, listOf(phoneA)))
        check(!AccountIdentity.matches("138****5678 138****5678", phoneA, listOf(phoneA)))
        println("Account identity checks passed, including masked-number collisions")

        val submittedFailure = Harness()
        submittedFailure.assertNoReplay(submittedFailure.run {
            safety.submit("claim")
            throw IOException("result capture failed")
        })
        check(submittedFailure.safety.pendingStepId == "claim")

        val confirmedCleanupFailure = Harness()
        confirmedCleanupFailure.assertNoReplay(confirmedCleanupFailure.run {
            safety.submit("claim")
            safety.confirm()
            throw IOException("cleanup failed")
        })
        check(confirmedCleanupFailure.safety.pendingStepId == null)
        check(confirmedCleanupFailure.safety.hasSubmittedAction)

        val pendingSuccess = Harness()
        pendingSuccess.assertNoReplay(pendingSuccess.run {
            safety.submit("claim")
            TaskResult("task", true)
        })

        val uncertain = Harness()
        uncertain.assertNoReplay(uncertain.run { TaskResult.uncertain("task", "cannot verify") })

        val retryLimit = Harness()
        val failed = retryLimit.run { TaskResult("task", false, "entry missing") }
        check(!failed.ok && failed.outcome == TaskOutcome.FAILED)
        check(retryLimit.executions == 2 && retryLimit.disconnectChecks == 2 && retryLimit.relogins == 0)
        check(retryLimit.waits == listOf(1_500L))

        for (taskOk in listOf(true, false)) {
            val unknownConnection = Harness().apply { disconnect = { throw IOException("capture unavailable") } }
            val unknown = unknownConnection.run { TaskResult("task", taskOk) }
            check(unknown.outcome == TaskOutcome.UNCERTAIN && !unknown.retryable && !unknown.ok)
            check(unknownConnection.executions == 1 && unknownConnection.disconnectChecks == 1)
            check(unknownConnection.relogins == 0 && unknownConnection.waits.isEmpty())
        }

        val retryThenSuccess = Harness()
        check(retryThenSuccess.run { TaskResult("task", executions == 2) }.ok)
        check(retryThenSuccess.executions == 2 && retryThenSuccess.waits == listOf(1_500L))

        val recoveryLimit = Harness().apply { disconnect = { true } }
        val exhausted = recoveryLimit.run { TaskResult("task", false, "not submitted") }
        check(!exhausted.ok && !exhausted.retryable)
        check(recoveryLimit.executions == 3 && recoveryLimit.disconnectChecks == 3 && recoveryLimit.relogins == 2)
        check(recoveryLimit.waits == listOf(1_000L, 1_000L))

        val completedBeforeDisconnect = Harness().apply { disconnect = { true } }
        check(completedBeforeDisconnect.run { TaskResult("task", true) }.ok)
        check(completedBeforeDisconnect.executions == 1 && completedBeforeDisconnect.relogins == 1)

        val failedRecovery = Harness().apply {
            disconnect = { true }
            recover = { TaskResult("login", false, "identity unavailable") }
        }
        check(failedRecovery.run { TaskResult("task", false) }.outcome == TaskOutcome.UNCERTAIN)
        check(failedRecovery.executions == 1 && failedRecovery.relogins == 1)

        val session = AccountSession().apply { verify("account-A") }
        val transition = Harness().apply {
            disconnect = { error("account transition must own recovery") }
            recover = {
                session.verify("account-A")
                error("must not relogin the previous account")
            }
        }
        check(transition.run(allowRetry = false, allowRecovery = false) {
            session.invalidate()
            session.verify("account-B")
            TaskResult("switch account", true)
        }.ok)
        check(transition.executions == 1 && transition.disconnectChecks == 0 && transition.relogins == 0)
        check(session.isVerifiedFor("account-B") && !session.isVerifiedFor("account-A"))
        session.invalidate()
        check(!session.isVerifiedFor("account-B") && !session.isVerifiedFor("account-A"))

        val transitionCleanupFailure = Harness().apply {
            disconnect = { error("must not check the previous account") }
            recover = { session.verify("account-A"); TaskResult("login", true) }
        }
        val transitionFailure = transitionCleanupFailure.run(allowRetry = false, allowRecovery = false) {
            session.verify("account-B")
            throw IOException("transition cleanup failed")
        }
        check(!transitionFailure.ok && !transitionFailure.retryable)
        check(transitionCleanupFailure.executions == 1 && transitionCleanupFailure.relogins == 0)
        check(session.isVerifiedFor("account-B"))

        suspend fun expectCancellation(action: suspend () -> Unit) {
            val cancellation = runCatching { action() }.exceptionOrNull()
            check(cancellation is CancellationException) { "Cancellation was converted into a result: $cancellation" }
        }
        val cancelledExecute = Harness()
        expectCancellation {
            cancelledExecute.run {
                safety.submit("claim")
                throw CancellationException("user stopped")
            }
        }
        check(cancelledExecute.executions == 1 && cancelledExecute.relogins == 0)
        check(cancelledExecute.errors.isEmpty())
        val cancelledDetection = Harness().apply { disconnect = { throw CancellationException("stop capture") } }
        expectCancellation { cancelledDetection.run { TaskResult("task", true) } }
        val cancelledRecovery = Harness().apply {
            disconnect = { true }
            recover = { throw CancellationException("stop login") }
        }
        expectCancellation { cancelledRecovery.run { TaskResult("task", false) } }
        val cancelledWait = Harness().apply { waitAction = { throw CancellationException("stop waiting") } }
        expectCancellation { cancelledWait.run { TaskResult("task", false) } }
        check(cancelledWait.executions == 1)

        // Close the underlying writer to simulate a real IOException, without changing production APIs.
        val journal = RunJournal(File(args.single()))
        val writerField = RunJournal::class.java.getDeclaredField("writer").apply { isAccessible = true }
        (writerField.get(journal) as Closeable).close()
        var clicks = 0
        val journalFailure = Harness()
        try {
            journalFailure.assertNoReplay(journalFailure.run {
                safety.submit("claim")
                journal.record("action_submitted", accountId = "internal-account-id", stepId = "claim")
                clicks++
                TaskResult("task", true)
            })
            check(clicks == 0 && journalFailure.errors.single() is IOException)
        } finally {
            journal.close()
        }

        val state = TaskSafetyState().apply { beginTask("first") }
        check(runCatching { state.confirm() }.isFailure)
        state.submit("claim")
        check(runCatching { state.submit("another") }.isFailure)
        check(runCatching { state.beginTask("second") }.isFailure)
        state.confirm()
        check(state.hasSubmittedAction)
        state.beginTask("second")
        check(!state.hasSubmittedAction && state.pendingStepId == null && state.taskId == "second")
        check(runCatching { AccountSession().verify("") }.isFailure)

        println("TaskExecution checks passed: no reward replay, bounded retry/recovery, account identity, cancellation, journal I/O failure")
    }
}
