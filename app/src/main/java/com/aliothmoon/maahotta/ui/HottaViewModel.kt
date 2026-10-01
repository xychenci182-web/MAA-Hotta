package com.aliothmoon.maahotta.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maahotta.data.AppConfig
import com.aliothmoon.maahotta.data.BarkPushClient
import com.aliothmoon.maahotta.data.AutoStartSchedule
import com.aliothmoon.maahotta.data.ConfigStore
import com.aliothmoon.maahotta.data.GameAccount
import com.aliothmoon.maahotta.data.IslandMerchantRecordStore
import com.aliothmoon.maahotta.data.MahReportAttachment
import com.aliothmoon.maahotta.data.MahReportKind
import com.aliothmoon.maahotta.data.MahRunReport
import com.aliothmoon.maahotta.data.NoOpMahReportApi
import com.aliothmoon.maahotta.data.TaskErrorReportStore
import com.aliothmoon.maahotta.data.TaskOptions
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.TaskEngine
import com.aliothmoon.maahotta.overlay.OverlayService
import com.aliothmoon.maahotta.scheduler.AutoStartScheduler
import com.aliothmoon.maahotta.runtime.AccessibilityController
import com.aliothmoon.maahotta.runtime.CompositeController
import com.aliothmoon.maahotta.runtime.HottaAccessibilityService
import com.aliothmoon.maahotta.runtime.KeepAliveService
import com.aliothmoon.maahotta.runtime.ReferenceFrameController
import com.aliothmoon.maahotta.runtime.ShizukuController
import com.aliothmoon.maahotta.tasks.LoginTask
import com.aliothmoon.maahotta.tasks.AccountTransitionTask
import com.aliothmoon.maahotta.tasks.buildDailyTasks
import com.aliothmoon.maahotta.vision.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class RunUiState(
    val running: Boolean = false,
    val backend: String = "检测中",
    val logs: List<String> = emptyList(),
    val lastSummary: String = "",
)

class HottaViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ConfigStore(app)
    val config = store.config.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    private val _run = MutableStateFlow(RunUiState())
    val run = _run.asStateFlow()

    private val shizuku = ShizukuController()
    private val accessibility = AccessibilityController()
    private val device = CompositeController(accessibility, shizuku)
    private val islandMerchantRecords = IslandMerchantRecordStore(app)
    private val taskErrorReports = TaskErrorReportStore(app)
    private val reportApi = NoOpMahReportApi
    private val runLogLock = Any()
    private val completeRunLogs = mutableListOf<String>()
    private var job: Job? = null

    init {
        refreshBackend()
        viewModelScope.launch { islandMerchantRecords.cleanupExpired() }
    }

    fun refreshBackend() {
        viewModelScope.launch {
            if (Shizuku.pingBinder()) shizuku.bind()
            _run.update { it.copy(backend = device.backendName()) }
        }
    }

    /** Read-only check for cloud phones and emulators with multiple displays. */
    fun checkGameCapture() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val shot = device.screenshot()
                if (shot == null) {
                    log("画面检查：未找到游戏窗口截图，请确认游戏已打开且无障碍已授权")
                    return@launch
                }
                try {
                    val size = device.screenSize()
                    val sameAspect = size.x > 0 && size.y > 0 &&
                        kotlin.math.abs(shot.width.toDouble() / shot.height /
                            (size.x.toDouble() / size.y) - 1.0) <= 0.03
                    log("画面检查：截图 ${shot.width}×${shot.height}，触控 ${size.x}×${size.y}，" +
                        if (sameAspect) "方向一致" else "方向不一致")
                } finally {
                    shot.recycle()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log("画面检查失败：${error.message ?: "截图异常"}")
            }
        }
    }

    fun upsertAccount(account: GameAccount) {
        viewModelScope.launch { store.upsertAccount(account) }
    }

    fun removeAccount(id: String) {
        viewModelScope.launch { store.removeAccount(id) }
    }

    fun setAllAccountsEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setAllAccountsEnabled(enabled) }
    }

    fun updateOptions(options: TaskOptions) {
        viewModelScope.launch { store.update { it.copy(options = options) } }
    }

    fun updateAutoStartSchedule(schedule: AutoStartSchedule) {
        viewModelScope.launch {
            store.update { it.copy(autoStartSchedule = schedule) }
            AutoStartScheduler.scheduleNext(getApplication(), schedule)
        }
    }

    fun updateBarkPush(enabled: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(barkPushEnabled = enabled) }
        }
    }

    fun updateKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(keepAliveEnabled = enabled) }
            val app = getApplication<Application>()
            if (enabled) {
                runCatching { KeepAliveService.start(app) }
            } else {
                KeepAliveService.stop(app)
            }
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            "package:${app.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun openOverlaySettings() {
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${app.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun startOverlay() {
        val app = getApplication<Application>()
        OverlayService.start(app)
    }

    fun startDaily() = startRun(loginOnlyId = null)

    fun startScheduledDaily() = startRun(loginOnlyId = null, enableAllAccounts = true)

    /** Runs only login for one saved account, without touching any daily rewards. */
    fun startLoginOnly(accountId: String) = startRun(loginOnlyId = accountId)

    private fun startRun(loginOnlyId: String?, enableAllAccounts: Boolean = false) {
        if (job?.isActive == true) return
        if (config.value.keepAliveEnabled) {
            runCatching { KeepAliveService.start(getApplication()) }
        }
        val runJob = viewModelScope.launch(Dispatchers.Default) {
            resetCompleteRunLogs()
            val reportAttachments = mutableListOf<MahReportAttachment>()
            fun queueReportAttachment(attachment: MahReportAttachment) {
                reportAttachments.removeAll {
                    it.kind == attachment.kind && it.file.absolutePath == attachment.file.absolutePath
                }
                reportAttachments += attachment
            }
            _run.update { it.copy(running = true, logs = emptyList(), lastSummary = "") }
            if (enableAllAccounts) {
                store.setAllAccountsEnabled(true)
                log("定时启动：已自动勾选全部保存账号")
            }
            log("后端：${device.backendName()}")
            if (!HottaAccessibilityService.isConnected() && !shizuku.isReady()) {
                shizuku.bind()
            }
            if (!HottaAccessibilityService.isConnected() && !shizuku.isReady()) {
                log("请先授权 Shizuku 或开启无障碍")
                _run.update { it.copy(running = false) }
                return@launch
            }
            val conf = if (enableAllAccounts) store.config.first() else config.value
            val accounts = if (loginOnlyId == null) {
                conf.accounts.filter { it.enabled }
            } else {
                conf.accounts.filter { it.id == loginOnlyId }
            }
            if (accounts.isEmpty()) {
                log("没有启用的账号")
                _run.update { it.copy(running = false) }
                return@launch
            }
            val templates = TemplateStore(getApplication())
            val ctx = BotContext(ReferenceFrameController(device), templates, ::log)
            val summaries = mutableListOf<String>()
            val mailNoRewardAccounts = linkedSetOf<String>()
            for ((index, account) in accounts.withIndex()) {
                val accountLogStart = completeRunLogSize()
                val engine = TaskEngine(ctx) {
                    LoginTask(account, launchGame = false).run(ctx)
                }
                log("======== ${account.label} ========")
                var islandMerchantCheckedThisRun = false
                var islandMerchantDetectedThisRun = false
                var islandMerchantRecordedThisRun = false
                val tasks = if (loginOnlyId == null) {
                    buildDailyTasks(
                        account = account,
                        options = conf.options,
                        includeLogin = index == 0,
                        onIslandMerchantDetected = { found ->
                            islandMerchantCheckedThisRun = true
                            if (found) {
                                islandMerchantDetectedThisRun = true
                                val accountNote = merchantRecordNote(account)
                                if (accountNote != null) {
                                    val file = islandMerchantRecords.append(accountNote)
                                    if (file != null) {
                                        islandMerchantRecordedThisRun = true
                                        queueReportAttachment(
                                            MahReportAttachment(MahReportKind.ISLAND_MERCHANT, file),
                                        )
                                        log("老头账号备注已追加：$accountNote")
                                        log("记录文件：${file.absolutePath}")
                                    }
                                }
                                store.updateAccount(account.id) {
                                    it.copy(islandMerchantPending = !islandMerchantRecordedThisRun)
                                }
                            } else {
                                store.updateAccount(account.id) {
                                    it.copy(islandMerchantPending = false)
                                }
                            }
                        },
                        onMailNoRewardPopup = {
                            val identifier = errorAccountIdentifier(account)
                            if (mailNoRewardAccounts.add(identifier)) {
                                log("账号 $identifier：点击一键领取后未见奖励弹窗，已列入待核实")
                                try {
                                    val file = islandMerchantRecords.append("邮件待核实：$identifier")
                                    if (file != null) {
                                        queueReportAttachment(
                                            MahReportAttachment(MahReportKind.ISLAND_MERCHANT, file),
                                        )
                                        log("已与老头账号一起保存：${file.absolutePath}")
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    log("邮件待核实记录保存失败：${error.message ?: "未知错误"}；账号仍保留在本次运行汇总中")
                                }
                            }
                        },
                    )
                } else {
                    listOf(LoginTask(account))
                }
                val results = engine.runAll(tasks).toMutableList()
                var stopAfterAccount = false

                if (loginOnlyId == null) {
                    val dailySucceeded = results.all { it.ok }
                    if (!dailySucceeded) {
                        log("账号 ${account.label} 有任务重试后仍失败，停止全部运行")
                        stopAfterAccount = true
                    } else {
                        store.updateAccount(account.id) { it.copy(enabled = false) }
                        log("账号 ${account.label} 的日常已完成，已自动取消勾选")
                        val nextAccount = accounts.getOrNull(index + 1)
                        val merchantNeedsRecord = !islandMerchantRecordedThisRun &&
                            if (islandMerchantCheckedThisRun) {
                                islandMerchantDetectedThisRun
                            } else {
                                account.islandMerchantPending
                            }
                        val accountNote = merchantRecordNote(account)
                        if (merchantNeedsRecord && accountNote != null) {
                            val file = islandMerchantRecords.append(accountNote)
                            if (file != null) {
                                islandMerchantRecordedThisRun = true
                                queueReportAttachment(
                                    MahReportAttachment(MahReportKind.ISLAND_MERCHANT, file),
                                )
                                log("老头账号备注已追加：$accountNote")
                                log("记录文件：${file.absolutePath}")
                                store.updateAccount(account.id) {
                                    it.copy(islandMerchantPending = false)
                                }
                            }
                        }
                        val captureCharacterName = merchantNeedsRecord &&
                            !islandMerchantRecordedThisRun
                        val transition = AccountTransitionTask(
                            currentAccount = account,
                            nextAccount = nextAccount,
                            captureCharacterName = captureCharacterName,
                            onCharacterName = { characterName ->
                                val shouldRecord = !islandMerchantRecordedThisRun &&
                                    (islandMerchantDetectedThisRun || account.islandMerchantPending)
                                if (shouldRecord) {
                                    val recordName = merchantRecordNote(account) ?: characterName
                                    val file = islandMerchantRecords.append(recordName)
                                    if (file != null) {
                                        queueReportAttachment(
                                            MahReportAttachment(MahReportKind.ISLAND_MERCHANT, file),
                                        )
                                        log("老头记录已追加：$recordName")
                                        log("记录文件：${file.absolutePath}")
                                    }
                                }
                                store.updateAccount(account.id) {
                                    it.copy(
                                        characterName = characterName,
                                        islandMerchantPending = false,
                                    )
                                }
                            },
                        )
                        val transitionResult = engine.runAll(listOf(transition)).single()
                        results += transitionResult
                        if (!transitionResult.ok && nextAccount != null) {
                            log("未能切换到下一账号，多号流程停止")
                            stopAfterAccount = true
                        }
                    }
                }

                val failedResults = results.filterNot { it.ok }
                if (failedResults.isNotEmpty()) {
                    val accountIdentifier = errorAccountIdentifier(account)
                    log("正在保存出错账号 $accountIdentifier 的运行日志")
                    val report = taskErrorReports.save(
                        accountIdentifier = accountIdentifier,
                        failures = failedResults.map { result ->
                            "${result.name}：${result.detail.ifBlank { "未提供失败原因" }}"
                        },
                        logs = completeRunLogsFrom(accountLogStart),
                    )
                    if (report != null) {
                        queueReportAttachment(
                            MahReportAttachment(
                                kind = MahReportKind.TASK_ERROR,
                                file = report,
                                accountIdentifier = accountIdentifier,
                            ),
                        )
                        log("错误报告已保存：${report.absolutePath}")
                    } else {
                        log("错误报告保存失败")
                    }
                }

                summaries += "${account.label}: " + results.joinToString { r ->
                    "${r.name}${if (r.ok) "✓" else "✗"}"
                }
                if (store.config.first().barkPushEnabled) {
                    val accountName = account.label.takeIf { it.isNotBlank() && it != account.username }
                        ?: "账号 ${index + 1}"
                    val body = buildString {
                        appendLine(accountName.take(80))
                        results.forEach { result ->
                            appendLine("${result.name}：${if (result.ok) "完成" else "失败"}")
                            if (!result.ok) appendLine(result.detail.take(150))
                        }
                    }.replace(account.password.takeIf { it.isNotEmpty() } ?: "\u0000", "***")
                        .replace(account.username.takeIf { it.isNotEmpty() } ?: "\u0000", "***")
                    try {
                        BarkPushClient.send(
                            title = if (failedResults.isEmpty()) "MAH 单号任务完成" else "MAH 单号任务失败",
                            body = body,
                        )
                        log("Bark 任务结果已推送")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        log("Bark 推送失败，请检查网络和推送地址；任务结果已保留")
                    }
                }
                if (failedResults.isEmpty()) {
                    _run.update { it.copy(logs = emptyList()) }
                }
                if (stopAfterAccount) break
            }
            val summaryLines = summaries.toMutableList()
            if (mailNoRewardAccounts.isNotEmpty()) {
                summaryLines += "邮件未见奖励弹窗，待核实账号：${mailNoRewardAccounts.joinToString("、")}"
            }
            val summary = summaryLines.joinToString("\n")
            if (reportAttachments.isNotEmpty()) {
                try {
                    reportApi.send(
                        MahRunReport(
                            createdAt = System.currentTimeMillis(),
                            attachments = reportAttachments.toList(),
                        ),
                    )
                } catch (error: Exception) {
                    log("运行报告发送失败：${error.message ?: "未知错误"}")
                }
            }
            log("全部结束")
            _run.update { it.copy(running = false, lastSummary = summary) }
        }
        job = runJob
        runJob.invokeOnCompletion { cause ->
            if (cause != null) {
                _run.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _run.update { it.copy(running = false) }
        log("已停止")
    }

    /** A user-entered note identifies the account; the auto-filled username does not. */
    private fun merchantRecordNote(account: GameAccount): String? =
        account.label.trim().takeIf { note ->
            note.isNotBlank() && note != account.username.trim()
        }

    /** Prefer the user's note; accounts without a note are identified by phone number. */
    private fun errorAccountIdentifier(account: GameAccount): String =
        merchantRecordNote(account) ?: account.username.trim().ifBlank { account.id }

    private fun resetCompleteRunLogs() {
        synchronized(runLogLock) { completeRunLogs.clear() }
    }

    private fun completeRunLogSize(): Int =
        synchronized(runLogLock) { completeRunLogs.size }

    private fun completeRunLogsFrom(startIndex: Int): List<String> =
        synchronized(runLogLock) {
            completeRunLogs.drop(startIndex.coerceIn(0, completeRunLogs.size))
        }

    private fun log(line: String) {
        synchronized(runLogLock) { completeRunLogs += line }
        _run.update { state ->
            state.copy(logs = (state.logs + line).takeLast(200))
        }
    }
}
