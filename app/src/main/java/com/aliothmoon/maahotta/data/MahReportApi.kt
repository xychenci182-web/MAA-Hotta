package com.aliothmoon.maahotta.data

import java.io.File

enum class MahReportKind {
    ISLAND_MERCHANT,
    TASK_ERROR,
}

data class MahReportAttachment(
    val kind: MahReportKind,
    val file: File,
    val accountIdentifier: String? = null,
)

data class MahRunReport(
    val createdAt: Long,
    val attachments: List<MahReportAttachment>,
)

/** Replace this implementation when the QQ sending API is available. */
fun interface MahReportApi {
    suspend fun send(report: MahRunReport)
}

object NoOpMahReportApi : MahReportApi {
    override suspend fun send(report: MahRunReport) = Unit
}
