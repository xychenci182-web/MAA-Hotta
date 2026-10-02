package com.aliothmoon.maahotta.engine

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dependency-free JVM check; pass a scratch directory on G: when running locally. */
object RunJournalCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args.single())
        val runId = "check_${UUID.randomUUID()}"
        val journal = RunJournal(directory, runId)
        journal.record(
            event = "action_submitted",
            accountId = "internal-account-id",
            taskId = "mail",
            stepId = "claim",
            detail = "quote=\" slash=\\ newline=\n tab=\t nul=\u0000 中文 \uD83D\uDC31 \uD800",
            screenshotPath = "G:\\checks\\frame.jpg",
        )
        val first = journal.file.readLines(Charsets.UTF_8).single()
        check(first.startsWith("{\"runId\":\"$runId\",\"timestamp\":"))
        check(first.contains("\"accountId\":\"internal-account-id\""))
        check(first.contains("quote=\\\" slash=\\\\ newline=\\n tab=\\t nul=\\u0000 中文 \\ud83d\\udc31 \\ud800"))
        check(first.endsWith("\"screenshotPath\":\"G:\\\\checks\\\\frame.jpg\"}"))
        check(!first.contains("password") && !first.contains("username"))

        // record() has already flushed; no close is needed to see the bounded diagnostic text.
        journal.record("task_finished", outcome = "success", detail = "x".repeat(5_000))
        check(journal.file.readLines(Charsets.UTF_8).last().contains("\"detail\":\"${"x".repeat(2_048)}\""))

        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val futures = (0 until 4).map { worker ->
            workers.submit {
                start.await()
                repeat(30) { index ->
                    journal.record("progress", stepId = "$worker-$index")
                }
            }
        }
        start.countDown()
        workers.shutdown()
        check(workers.awaitTermination(10, TimeUnit.SECONDS))
        futures.forEach { it.get() }
        val lines = journal.file.readLines(Charsets.UTF_8)
        check(lines.size == 122)
        val steps = lines.drop(2).map { line ->
            check(line.startsWith('{') && line.endsWith('}'))
            line.substringAfter("\"stepId\":\"").substringBefore('"')
        }.toSet()
        check(steps.size == 120)
        check(lines.all { "\"runId\":\"$runId\"" in it })

        journal.close()
        journal.close()
        check(runCatching { journal.record("after_close") }.exceptionOrNull() is IllegalStateException)
        check(runCatching { RunJournal(directory, runId) }.exceptionOrNull() is IOException)
        check(runCatching { RunJournal(directory, "../unsafe") }.exceptionOrNull() is IllegalArgumentException)

        val notADirectory = File(directory, "not_a_directory_${UUID.randomUUID()}")
        check(notADirectory.createNewFile())
        check(runCatching { RunJournal(notADirectory) }.exceptionOrNull() is IOException)

        println("RunJournal checks passed: flush, JSON escaping, limits, 120 concurrent events, close, I/O failures")
        println(journal.file.absolutePath)
    }
}
