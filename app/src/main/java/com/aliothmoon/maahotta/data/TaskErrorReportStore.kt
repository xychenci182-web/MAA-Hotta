package com.aliothmoon.maahotta.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskErrorReportStore(context: Context) {
    private val directory = context.getExternalFilesDir("error_reports")
        ?: File(context.filesDir, "error_reports")

    suspend fun save(
        accountIdentifier: String,
        failures: List<String>,
        logs: List<String>,
    ): File? {
        if (failures.isEmpty()) return null
        val now = System.currentTimeMillis()
        val account = accountIdentifier
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .ifBlank { "未知账号" }
        val safeAccount = account.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "_")
            .take(40)
            .ifBlank { "unknown" }
        val timestamp = fileTimestamp.format(Date(now))

        val file = withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                File(directory, "${timestamp}_$safeAccount.txt").also { reportFile ->
                    reportFile.writeText(
                        buildString {
                            appendLine("MAH 任务错误报告")
                            appendLine("时间：${displayTimestamp.format(Date(now))}")
                            appendLine("账号：$account")
                            appendLine()
                            appendLine("失败任务：")
                            failures.forEach { appendLine("- $it") }
                            appendLine()
                            appendLine("运行日志：")
                            logs.forEach(::appendLine)
                        },
                    )
                }
            }.getOrNull()
        } ?: return null

        return file
    }

    companion object {
        private val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val displayTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
