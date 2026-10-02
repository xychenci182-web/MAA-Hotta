package com.aliothmoon.maahotta.engine

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Local evidence for one run, not a command log or an automatic resume checkpoint.
 *
 * Callers must pass internal account IDs and diagnostic messages that contain no credentials or
 * text submitted to the game. Each complete event is flushed before [record] returns. I/O errors
 * propagate so the caller can stop rather than carry out an action without its evidence.
 */
class RunJournal(
    directory: File,
    val runId: String = UUID.randomUUID().toString(),
) : Closeable {
    val file: File
    private val writer: BufferedWriter
    private var closed = false

    init {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) {
            "Invalid run ID"
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create run journal directory")
        }
        file = File(directory, "run_$runId.jsonl")
        if (!file.createNewFile()) {
            throw IOException("Run journal already exists for this run ID")
        }
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    }

    @Synchronized
    fun record(
        event: String,
        accountId: String? = null,
        taskId: String? = null,
        stepId: String? = null,
        detail: String = "",
        outcome: String? = null,
        screenshotPath: String? = null,
    ) {
        check(!closed) { "Run journal is closed" }
        require(event.isNotBlank() && event.length <= MAX_EVENT_LENGTH) {
            "Invalid journal event"
        }
        val line = buildString {
            append('{')
            append("\"runId\":")
            appendJsonString(runId)
            append(",\"timestamp\":")
            append(System.currentTimeMillis())
            append(",\"event\":")
            appendJsonString(event)
            appendOptionalField("accountId", accountId)
            appendOptionalField("taskId", taskId)
            appendOptionalField("stepId", stepId)
            appendOptionalField("outcome", outcome)
            append(",\"detail\":")
            appendJsonString(detail.take(MAX_DETAIL_LENGTH))
            appendOptionalField("screenshotPath", screenshotPath)
            append('}')
        }
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        writer.close()
    }

    private fun StringBuilder.appendOptionalField(name: String, value: String?) {
        if (value == null) return
        append(',')
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    // Escape UTF-16 surrogates too: even malformed input stays valid UTF-8 JSON.
                    if (character.code < 0x20 || character.isSurrogate()) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    companion object {
        private const val MAX_EVENT_LENGTH = 80
        private const val MAX_DETAIL_LENGTH = 2_048
    }
}
