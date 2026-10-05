package com.aliothmoon.maahotta.engine

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import com.aliothmoon.maahotta.HottaApp
import com.aliothmoon.maahotta.runtime.DeviceController
import com.aliothmoon.maahotta.vision.TemplateMatcher
import com.aliothmoon.maahotta.vision.TemplateStore
import com.aliothmoon.maahotta.vision.SearchRegion
import com.aliothmoon.maahotta.vision.AnnouncementDetector
import com.aliothmoon.maahotta.vision.AccountScreenDetector
import com.aliothmoon.maahotta.vision.TitleScreenDetector
import com.aliothmoon.maahotta.vision.GameScreen
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.MatchResult
import com.aliothmoon.maahotta.vision.ScreenTextFinder
import com.aliothmoon.maahotta.vision.RewardRecoveryDetector
import com.aliothmoon.maahotta.vision.PageObservation
import com.aliothmoon.maahotta.vision.PageState
import com.aliothmoon.maahotta.vision.PageStateDetector
import com.aliothmoon.maahotta.tasks.TaskNavigationMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.abs

class BotContext(
    device: DeviceController,
    val templates: TemplateStore,
    val log: (String) -> Unit,
    private val journal: RunJournal? = null,
    private val redact: (String) -> String = { it },
) {
    val safety = TaskSafetyState()
    val accountSession = AccountSession()
    private var accountId: String? = null
    private data class LoginMenuResult(
        val accountId: String,
        val menu: MatchResult,
        val touchWidth: Int,
        val touchHeight: Int,
        val source: String,
    )
    private var loginMenuForCheckIn: LoginMenuResult? = null
    private var mailExitMenu: LoginMenuResult? = null

    fun rememberMailExitMenu(menu: MatchResult) {
        val id = accountId ?: return
        val touch = device.screenSize()
        mailExitMenu = LoginMenuResult(id, menu, touch.x, touch.y, "邮件退出")
    }

    fun consumeMailExitMenu(): MatchResult? {
        val result = mailExitMenu ?: return null
        mailExitMenu = null
        val touch = device.screenSize()
        if (result.accountId != accountId || touch.x != result.touchWidth || touch.y != result.touchHeight) return null
        log("复用邮件任务结束时已确认的游戏主界面菜单")
        return result.menu
    }
    private val taskStepAttempts = mutableMapOf<String, Int>()
    private var taskFailureRecovery: (suspend (Bitmap, PageObservation) -> TaskResult?)? = null

    fun beginAccount(id: String) {
        mailExitMenu = null
        // A transition logs in the next account before the outer loop begins that account.
        if (loginMenuForCheckIn?.accountId != id || !accountSession.isVerifiedFor(id)) {
            loginMenuForCheckIn = null
        }
        accountId = id
        journal?.record("account_started", accountId = id)
    }

    fun beginTask(id: String) {
        if (safety.taskId != "mail") mailExitMenu = null
        if (id != "check_in") loginMenuForCheckIn = null
        safety.beginTask(id)
        clearHudConfirmation()
        taskStepAttempts.clear()
        taskFailureRecovery = null
        record("task_started")
    }

    /** A step shares its initial attempt plus one retry across helpers and task recovery. */
    fun tryTaskStep(stepId: String): Boolean {
        if (firstLoginRecognition) return true
        val used = taskStepAttempts[stepId] ?: 0
        if (used >= 2) {
            log("步骤 $stepId 已执行初次及一次重试，不再重复点击")
            return false
        }
        taskStepAttempts[stepId] = used + 1
        return true
    }

    fun onTaskFailureRecovery(action: suspend (Bitmap, PageObservation) -> TaskResult?) {
        taskFailureRecovery = action
    }

    internal fun hasTaskFailureRecovery(): Boolean = taskFailureRecovery != null

    internal suspend fun recoverTaskFailure(screen: Bitmap, page: PageObservation): TaskResult? {
        val recovery = taskFailureRecovery ?: return null
        taskFailureRecovery = null
        return recovery(screen, page)
    }

    fun markActionSubmitted(stepId: String) {
        safety.submit(stepId)
        record("action_submitted", stepId = stepId)
    }

    fun confirmActionResult() {
        record("action_confirmed", stepId = safety.pendingStepId)
        safety.confirm()
    }

    fun confirmAccountIdentity(id: String) {
        journal?.record("account_verified", accountId = id)
        accountSession.verify(id)
    }

    /** Publish only a successful login's final menu, separately from the scoped HUD frame counter. */
    fun rememberLoginMenuForCheckIn(id: String, source: String): Boolean {
        loginMenuForCheckIn = null
        val frame = pendingHudFrame?.takeIf { it.accepted } ?: return false
        val menu = frame.menu ?: return false
        val requiredFrames = if (firstLoginRecognition) 1 else 3
        if (!accountSession.isVerifiedFor(id) || pendingHudCount < requiredFrames ||
            pendingHudWidth <= 0 || pendingHudHeight <= 0
        ) return false
        val touch = device.screenSize()
        if (touch.x <= 0 || touch.y <= 0 ||
            abs((pendingHudWidth.toDouble() / pendingHudHeight) / (touch.x.toDouble() / touch.y) - 1) > 0.03
        ) return false
        val point = Point(
            (menu.point.x.toDouble() * touch.x / pendingHudWidth).toInt(),
            (menu.point.y.toDouble() * touch.y / pendingHudHeight).toInt(),
        )
        if (point.x !in 0 until touch.x || point.y !in 0 until touch.y) return false
        loginMenuForCheckIn = LoginMenuResult(
            id, menu.copy(point = point, requiresStableFrames = false), touch.x, touch.y, source,
        )
        return true
    }

    /** One use, for the same account and touch dimensions, before any newer capture or input. */
    fun consumeLoginMenuForCheckIn(): MatchResult? {
        val result = loginMenuForCheckIn ?: return null
        loginMenuForCheckIn = null
        val touch = device.screenSize()
        if (result.accountId != accountId || !accountSession.isVerifiedFor(result.accountId) ||
            touch.x != result.touchWidth || touch.y != result.touchHeight
        ) {
            log("每日签到：登陆菜单结果已失效，按当前页面重新识别")
            return null
        }
        log("每日签到：复用${result.source}已确认的游戏主界面菜单，直接定位礼盒")
        return result.menu
    }

    fun invalidateAccountIdentity() {
        mailExitMenu = null
        loginMenuForCheckIn = null
        accountSession.invalidate()
        record("account_invalidated")
    }

    fun finishTask(result: TaskResult) {
        if (!result.ok) mailExitMenu = null
        record("task_finished", detail = result.detail, outcome = result.outcome.name)
    }

    private fun record(event: String, stepId: String? = null, detail: String = "", outcome: String? = null, screenshotPath: String? = null) {
        journal?.record(event, accountId = accountId, taskId = safety.taskId, stepId = stepId,
            detail = redact(detail), outcome = outcome, screenshotPath = screenshotPath)
    }

    /** Set by the engine: successful tasks leave their verified page for the next state transition. */
    var preserveTaskPage: Boolean = false
    private var firstLoginRecognition = false
    private var firstLoginMenuConfirmed = false

    /** First-login recognition changes never leak into later account transitions or daily tasks. */
    suspend fun <T> withFirstLoginRecognition(action: suspend () -> T): T {
        val previous = firstLoginRecognition
        val previousMenuConfirmation = firstLoginMenuConfirmed
        firstLoginRecognition = true
        if (!previous) {
            firstLoginMenuConfirmed = false
            resetHudDetection()
        }
        return try {
            action()
        } finally {
            firstLoginRecognition = previous
            firstLoginMenuConfirmed = previousMenuConfirmation
            if (!previous) resetHudDetection()
        }
    }

    fun inspectHud(screen: Bitmap): com.aliothmoon.maahotta.vision.HudDetection =
        GameScreenDetector.inspectHud(screen, hudTemplates(), excludeOtherScreens = !firstLoginRecognition,
            exclusions = com.aliothmoon.maahotta.vision.HudExclusions.forTask(safety.taskId))

    private val rawDevice = device
    private val captureReadiness = CaptureReadiness(
        ::elapsedRealtime,
        onWaitingWithoutDeadline = {
            log("登录加载中，截图暂未就绪，继续等待；可点击停止结束等待")
        },
    )

    suspend fun <T> withLoginCaptureWaiting(action: suspend () -> T): T =
        captureReadiness.awaiting(action)
    // Daily screenshots handle line selection first; first login deliberately skips that recognition.
    val device: DeviceController = object : DeviceController by rawDevice {
        // Synchronous backends may not suspend before sending an input; reject cancelled runs here.
        override suspend fun tap(x: Int, y: Int, holdMs: Long) {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            rawDevice.tap(x, y, holdMs)
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            rawDevice.swipe(x1, y1, x2, y2, durationMs)
        }

        override suspend fun inputText(text: String): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.inputText(text)
        }

        override suspend fun clickView(viewIdSuffix: String): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.clickView(viewIdSuffix)
        }

        override suspend fun clickText(text: String): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.clickText(text)
        }

        override suspend fun setViewText(viewIdSuffix: String, text: String): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.setViewText(viewIdSuffix, text)
        }

        override suspend fun launchApp(packageName: String, forceStop: Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.launchApp(packageName, forceStop)
        }

        override suspend fun forceStop(packageName: String): Boolean {
            currentCoroutineContext().ensureActive()
            invalidateScreenEvidence()
            return rawDevice.forceStop(packageName)
        }

        override suspend fun screenshot(): Bitmap? {
            currentCoroutineContext().ensureActive()
            mailExitMenu = null
            // A newer frame supersedes the one-time login result, even if capture fails.
            loginMenuForCheckIn = null
            var handled = false
            repeat(10) {
                currentCoroutineContext().ensureActive()
                val shot = captureReadiness.read { rawDevice.screenshot() } ?: return null
                if (firstLoginRecognition) return shot
                val visible = try {
                    dismissLineSwitch(shot)
                } catch (error: Throwable) {
                    shot.recycle()
                    throw error
                }
                if (!visible) {
                    if (handled) log("已确认线路选择弹窗关闭，恢复任务")
                    return shot
                }
                shot.recycle()
                handled = true
                delay(500)
            }
            throw IllegalStateException("线路选择弹窗未关闭，停止后续页面点击")
        }
    }
    private var lastHudDetail = "尚未执行菜单识别"
    private var hudTemplatesAvailable = false
    private var hudScreenshotAvailable = false
    private var loggedLandscapeCapture = false
    private var announcementStallStartedAt = 0L

    private fun announcementUnresolved(reason: String): Boolean {
        val now = elapsedRealtime()
        if (announcementStallStartedAt == 0L) announcementStallStartedAt = now
        if (now - announcementStallStartedAt >= 12_000L) {
            throw IllegalStateException("游戏公告持续无法处理：$reason")
        }
        log(reason)
        return true
    }

    suspend fun tap(point: RelPoint, delayMs: Long = 500) {
        val p = point.toPixel(device)
        log("点击 (${p.x},${p.y})")
        device.tap(p.x, p.y)
        delay(delayMs)
    }

    suspend fun tapMatch(template: String, timeoutMs: Long = 8_000, threshold: Float = 0.82f): Boolean {
        val found = waitMatch(template, timeoutMs, threshold) ?: return false
        log("命中 $template score=${"%.2f".format(found.score)}")
        device.tap(found.point.x, found.point.y)
        delay(600)
        return true
    }

    suspend fun waitMatch(template: String, timeoutMs: Long, threshold: Float = 0.82f) =
        waitUntil(timeoutMs) { shot ->
            val bmp = templates.get(template) ?: return@waitUntil null
            val loginImage = template.startsWith("pwd_") ||
                template.startsWith("announcement_")
            val region = when {
                template.startsWith("pwd_") -> SearchRegion(0.20f, 0.12f, 0.80f, 0.88f)
                template == "announcement_close" ->
                    SearchRegion(0.82f, 0.08f, 0.98f, 0.30f)
                template == "announcement_title" ->
                    SearchRegion(0.04f, 0.08f, 0.50f, 0.32f)
                else -> null
            }
            TemplateMatcher.match(
                shot,
                bmp,
                threshold,
                region = region,
                referenceHeight = if (loginImage) TemplateMatcher.LOGIN_REFERENCE_HEIGHT else null,
            )
        }

    suspend fun appears(template: String, timeoutMs: Long = 2_500): Boolean =
        waitMatch(template, timeoutMs) != null

    /** Adds a shared grace window for screens delayed by loading or network stalls. */
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    fun deadlineAfter(timeoutMs: Long): Long {
        val graceMs = when {
            timeoutMs >= 15_000 -> 10_000L
            timeoutMs >= 3_000 -> 4_000L
            else -> 0L
        }
        return elapsedRealtime() + timeoutMs + graceMs
    }

    suspend fun waitUntil(
        timeoutMs: Long,
        intervalMs: Long = 500,
        predicate: (Bitmap) -> com.aliothmoon.maahotta.vision.MatchResult?,
    ): com.aliothmoon.maahotta.vision.MatchResult? {
        val deadline = deadlineAfter(timeoutMs)
        var sawScreenshot = false
        var previousStableHit: MatchResult? = null
        var stableHitCount = 0
        while (elapsedRealtime() < deadline) {
            val roundStartedAt = elapsedRealtime()
            val shot = device.screenshot()
            if (shot != null) {
                sawScreenshot = true
                val shotWidth = shot.width
                val shotHeight = shot.height
                val size = device.screenSize()
                val hit = try {
                    if (size.x <= 0 || size.y <= 0 || shotWidth <= 0 || shotHeight <= 0) {
                        throw IllegalStateException("无法读取截图或触控屏幕尺寸")
                    }
                    val screenshotRatio = shotWidth.toDouble() / shotHeight
                    val touchRatio = size.x.toDouble() / size.y
                    if (abs(screenshotRatio / touchRatio - 1.0) > 0.03) {
                        throw IllegalStateException(
                            "截图 ${shotWidth}×${shotHeight} 与触控 ${size.x}×${size.y} 比例不一致，请确认游戏已横屏",
                        )
                    }
                    if (!loggedLandscapeCapture && shotWidth > shotHeight) {
                        val dpi = HottaApp.instance.resources.displayMetrics.densityDpi
                        log("游戏画面：截图 ${shotWidth}×${shotHeight}，触控 ${size.x}×${size.y}，DPI $dpi")
                        loggedLandscapeCapture = true
                    }
                    runCatching { predicate(shot) }.getOrNull()
                } finally {
                    shot.recycle()
                }
                if (hit != null) {
                    val scaled = hit.copy(
                        point = Point(
                            (hit.point.x.toDouble() * size.x / shotWidth).toInt(),
                            (hit.point.y.toDouble() * size.y / shotHeight).toInt(),
                        ),
                    )
                    if (!hit.requiresStableFrames || previousStableHit?.let {
                            abs(it.point.x - scaled.point.x) <= 8 && abs(it.point.y - scaled.point.y) <= 8
                        } == true) {
                        stableHitCount++
                        if (!hit.requiresStableFrames || stableHitCount >= 2) return scaled
                    } else stableHitCount = 1
                    previousStableHit = scaled
                } else {
                    previousStableHit = null
                    stableHitCount = 0
                }
            }
            if (shot == null) { previousStableHit = null; stableHitCount = 0 }
            val remaining = intervalMs - (elapsedRealtime() - roundStartedAt)
            if (remaining > 0 && elapsedRealtime() < deadline) delay(remaining)
        }
        if (!sawScreenshot) {
            throw ScreenshotUnavailableException()
        }
        return null
    }

    suspend fun recoverPopups() {
        if (dismissLineSwitch()) return
        if (dismissAnnouncement()) return
        val names = listOf("btn_close", "btn_confirm", "btn_skip", "btn_agree")
        for (name in names) {
            if (tapMatch(name, timeoutMs = 600, threshold = 0.85f)) {
                log("关闭弹窗 $name")
            }
        }
    }

    private var lineSwitchCancelCount = 0
    private var lastLineSwitchCancelAt = 0L
    private var lineSwitchHandledGeneration = 0L

    /** 线路切换：只点取消，避免误触后阻塞日常任务。 */
    suspend fun dismissLineSwitch(): Boolean {
        if (firstLoginRecognition) return false
        val previous = lineSwitchHandledGeneration
        val shot = device.screenshot()
        shot?.recycle()
        return previous != lineSwitchHandledGeneration
    }

    private suspend fun dismissLineSwitch(shot: Bitmap): Boolean {
        val title = templates.get("line_switch_title") ?: return false
        val cancel = templates.get("line_switch_cancel") ?: return false
        val heading = TemplateMatcher.match(shot, title, threshold = 0.80f,
            region = SearchRegion(0.12f, 0.16f, 0.50f, 0.38f), referenceHeight = 561)
        val titleConfirmed = heading != null || (GameScreenDetector.hasConfirmationPanel(shot) &&
            withTimeoutOrNull(2_000) {
                ScreenTextFinder.findAllInRegion(shot, listOf("线路选择"),
                    SearchRegion(0.12f, 0.16f, 0.55f, 0.39f)).isNotEmpty()
            } == true)
        if (!titleConfirmed) {
            lineSwitchCancelCount = 0
            return false
        }
        val button = TemplateMatcher.match(shot, cancel, threshold = 0.78f,
            region = SearchRegion(0.20f, 0.54f, 0.53f, 0.79f), referenceHeight = 561)
        // The title has been verified; Cancel stays left of Confirm in this dialog.
        val cancelPoint = button?.point ?: Point(
            (shot.width / 2f - shot.height * 0.18f).toInt(),
            (shot.height * 0.665f).toInt(),
        )
        if (elapsedRealtime() - lastLineSwitchCancelAt < 1_000 && lineSwitchCancelCount > 0) return true
        check(lineSwitchCancelCount < 2 && tryTaskStep("global:line_cancel")) { "线路切换弹窗重试1次仍未关闭" }
        val size = device.screenSize()
        if (size.x <= 0 || size.y <= 0) return true
        lineSwitchCancelCount++
        lineSwitchHandledGeneration++
        lastLineSwitchCancelAt = elapsedRealtime()
        log("识别到线路切换，点击取消（第 $lineSwitchCancelCount/2 次）")
        device.tap((cancelPoint.x.toDouble() * size.x / shot.width).toInt(),
            (cancelPoint.y.toDouble() * size.y / shot.height).toInt())
        return true
    }

    /** Account switching only. First login closes this popup inside its menu poll. */
    suspend fun dismissRewardRecoveryPopup(): Boolean {
        if (firstLoginRecognition) return false
        var detected = false
        repeat(2) { attempt ->
            val shot = device.screenshot()
            val visible = if (shot != null) {
                try {
                    rewardRecoveryVisible(shot)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    false
                } finally {
                    shot.recycle()
                }
            } else {
                false
            }
            if (!visible) {
                if (detected) log("已确认“奖励找回”关闭")
                return detected
            }
            detected = true
            check(tryTaskStep("global:reward_recovery_close")) { "奖励找回弹窗重试1次仍未关闭" }
            log(
                if (attempt == 0) "识别到“奖励找回”，点击上方空白处关闭"
                else "“奖励找回”仍在，点击下方空白处关闭",
            )
            tapRewardRecoveryBlank(attempt)
        }
        return detected
    }

    private var loggedMissingRewardTemplate = false

    private fun rewardRecoveryTemplate(): Bitmap? {
        val template = templates.get("reward_recovery_title")
        if (template == null && !loggedMissingRewardTemplate) {
            loggedMissingRewardTemplate = true
            log("奖励找回模板未载入，跳过奖励找回识别")
        }
        return template
    }

    private fun rewardRecoveryVisible(shot: Bitmap): Boolean =
        RewardRecoveryDetector.match(shot, rewardRecoveryTemplate()) != null

    private fun tapRewardRecoveryBlank(attempt: Int) {
        val size = device.screenSize()
        val blankY = if (attempt % 2 == 0) size.y * 0.10f else size.y * 0.95f
        device.tap(size.x / 2, blankY.toInt())
    }

    private data class AnnouncementHeading(val confirmed: Boolean, val readText: List<String>)

    /** Confirms the announcement and locates its X in one frame, without clicking. */
    suspend fun findGameAnnouncement(): Point? {
        val screen = device.screenshot() ?: return null
        return try {
            val size = device.screenSize()
            if (size.x <= 0 || size.y <= 0 || screen.width <= screen.height || size.x <= size.y ||
                abs(screen.width.toDouble() / screen.height / (size.x.toDouble() / size.y) - 1.0) > 0.03
            ) {
                null
            } else {
                // The layout is only a gate. Heading and X must be confirmed in this same frame.
                if (!AnnouncementDetector.hasLayout(screen) ||
                    !readAnnouncementHeading(screen, templateFirst = true).confirmed
                ) {
                    null
                } else {
                    findAnnouncementClose(screen)?.let { close ->
                        Point(
                            (close.point.x.toDouble() * size.x / screen.width).toInt(),
                            (close.point.y.toDouble() * size.y / screen.height).toInt(),
                        )
                    }
                }
            }
        } finally {
            screen.recycle()
        }
    }

    private suspend fun readAnnouncementHeading(screen: Bitmap, templateFirst: Boolean = false): AnnouncementHeading {
        // Express horizontal bounds in screen heights so wider phones do
        // not move a left-anchored heading outside the crop.
        val titleRegion = SearchRegion(
            (screen.height * 0.03f / screen.width).coerceIn(0f, 1f), 0.09f,
            (screen.height * 0.70f / screen.width).coerceIn(0f, 1f), 0.29f,
        )
        fun matchesTemplate(): Boolean = templates.get("announcement_title")?.let { title ->
            TemplateMatcher.match(
                screen, title, threshold = 0.64f,
                region = titleRegion,
                referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT,
            ) != null
        } == true
        // Only loading probes try the template first; closing announcements retains OCR-first behavior.
        if (templateFirst && matchesTemplate()) return AnnouncementHeading(true, emptyList())
        var readText = emptyList<String>()
        val ocrConfirmed = try {
            withTimeoutOrNull(2_000) {
                ScreenTextFinder.findAllInRegion(screen, listOf("游戏公告"), titleRegion) {
                    readText = it
                }.isNotEmpty()
            } == true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
        return AnnouncementHeading(ocrConfirmed || (!templateFirst && matchesTemplate()), readText)
    }

    private fun findAnnouncementClose(screen: Bitmap): MatchResult? {
        val closeRegion = SearchRegion(
            (1f - screen.height * 0.42f / screen.width).coerceIn(0f, 1f), 0.07f,
            (1f - screen.height * 0.01f / screen.width).coerceIn(0f, 1f), 0.32f,
        )
        return AnnouncementDetector.findClose(screen) ?: templates.get("announcement_close")?.let { icon ->
            TemplateMatcher.match(
                screen, icon, threshold = 0.60f,
                region = closeRegion,
                referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT,
            )
        }
    }

    /** Confirms the cropped heading, then locates the X in the same full screenshot. */
    suspend fun dismissAnnouncement(): Boolean {
        val screen = device.screenshot() ?: return false
        val closePoint = try {
            if (!AnnouncementDetector.hasLayout(screen)) {
                announcementStallStartedAt = 0L
                return false
            }
            val heading = readAnnouncementHeading(screen)
            if (!heading.confirmed) {
                return announcementUnresolved(
                    "公告布局可见，但左上角标题未确认；OCR=${heading.readText.distinct().take(6)}，重新截图",
                )
            }
            val close = findAnnouncementClose(screen)
            if (close == null) {
                return announcementUnresolved("已确认游戏公告，但未找到关闭 X；重新截图")
            }
            val size = device.screenSize()
            if (size.x <= 0 || size.y <= 0) {
                return announcementUnresolved("已确认游戏公告，但无法获取触控尺寸")
            }
            Point(
                (close.point.x.toDouble() * size.x / screen.width).toInt(),
                (close.point.y.toDouble() * size.y / screen.height).toInt(),
            )
        } finally {
            screen.recycle()
        }
        closeConfirmedAnnouncement(closePoint)
        return true
    }

    /** Uses the already confirmed frame's X; only the post-click check captures another frame. */
    suspend fun closeConfirmedAnnouncement(closePoint: Point, logPrefix: String = ""): Boolean {
        check(tryTaskStep("global:announcement_close")) { "游戏公告关闭重试1次仍未成功" }
        log("${logPrefix}已确认游戏公告，点击关闭 X (${closePoint.x},${closePoint.y})")
        device.tap(closePoint.x, closePoint.y)
        val gone = waitUntil(2_000, 500) { shot ->
            if (AnnouncementDetector.hasLayout(shot)) null else MatchResult(Point(0, 0), 1f)
        } != null
        if (gone) announcementStallStartedAt = 0L
        log(logPrefix + if (gone) "已确认游戏公告关闭" else "公告仍在，下一轮重新识别")
        if (!gone) announcementUnresolved("${logPrefix}公告关闭后仍在画面中")
        return gone
    }

    /** Saves the full frame and, when present, the cropped announcement title. */
    suspend fun saveLoginDiagnostic() {
        val screen = device.screenshot() ?: run {
            log("登录失败时无法获取诊断截图")
            return
        }
        try {
            val directory = HottaApp.instance.getExternalFilesDir("diagnostics")
                ?: File(HottaApp.instance.filesDir, "diagnostics")
            val name = "login_${System.currentTimeMillis()}"
            val hasAnnouncement = AnnouncementDetector.hasLayout(screen)
            val crop = if (hasAnnouncement) {
                val left = (screen.height * 0.03f).toInt().coerceIn(0, screen.width - 1)
                val top = (screen.height * 0.09f).toInt().coerceIn(0, screen.height - 1)
                val right = (screen.height * 0.70f).toInt().coerceIn(left + 1, screen.width)
                val bottom = (screen.height * 0.29f).toInt().coerceIn(top + 1, screen.height)
                Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
            } else null
            try {
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val full = File(directory, "${name}_full.png")
                    full.outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    log("登录诊断截图：${full.absolutePath}")
                    saveHudOverlay(screen, directory, name)
                    if (crop != null) {
                        val roi = File(directory, "${name}_announcement_roi.png")
                        roi.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        log("公告文字裁图：${roi.absolutePath}")
                    }
                }
            } finally {
                crop?.recycle()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log("保存登录诊断截图失败：${error.message ?: "未知错误"}")
        } finally {
            screen.recycle()
        }
    }

    /** Capture without automatic popup clicks; save and classify that exact failure frame. */
    internal suspend fun captureTaskFailure(taskId: String, reason: String): TaskFailureSnapshot {
        currentCoroutineContext().ensureActive()
        mailExitMenu = null
        loginMenuForCheckIn = null
        var screen: Bitmap? = null
        var captureError: String? = null
        var page = PageObservation(PageState.UNKNOWN)
        try {
            screen = rawDevice.screenshot()
            val frame = screen
            if (frame == null) {
                captureError = "无法获取当前失败画面"
            } else {
                val touch = device.screenSize()
                check(frame.width > frame.height && touch.x > touch.y && touch.y > 0) { "当前截图或触控尺寸不是有效横屏" }
                check(abs((frame.width.toDouble() / frame.height) / (touch.x.toDouble() / touch.y) - 1) <= 0.03) {
                    "截图与触控尺寸比例不一致"
                }
                val observed = PageStateDetector.inspectForRecovery(frame, templates::get, hudTemplates(),
                    com.aliothmoon.maahotta.vision.HudExclusions.forTask(safety.taskId))
                page = observed.copy(controls = observed.controls.mapValues { (_, hit) ->
                    hit.copy(point = Point((hit.point.x.toDouble() * touch.x / frame.width).toInt(),
                        (hit.point.y.toDouble() * touch.y / frame.height).toInt()))
                })
            }
        } catch (error: Exception) {
            if (error is CancellationException) { screen?.recycle(); throw error }
            captureError = error.message ?: "失败画面识别异常"
        }
        val directory = HottaApp.instance.getExternalFilesDir("diagnostics")
            ?: File(HottaApp.instance.filesDir, "diagnostics")
        val stem = "${taskId}_${System.currentTimeMillis()}_failure"
        var screenshotPath: String? = null
        var reasonPath: String? = null
        val description = redact(buildString {
            appendLine("任务：$taskId")
            appendLine("失败原因：$reason")
            appendLine("当前页面：${page.state.label}")
            appendLine("待确认动作：${safety.pendingStepId ?: "无"}")
            if (captureError != null) appendLine("截图/识别异常：$captureError")
        })
        try {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "无法创建诊断目录" }
                File(directory, "$stem.txt").also { it.writeText(description) }.let { reasonPath = it.absolutePath }
                screen?.let { frame ->
                    File(directory, "$stem.png").also { file ->
                        file.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图保存失败" } }
                    }.let { screenshotPath = it.absolutePath }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) { screen?.recycle(); throw error }
            log("全局验证诊断保存失败：${error.message ?: "未知错误"}")
        }
        // The journal's write/flush failure is fatal, even when diagnostic files are best effort.
        try {
            record("failure_verified", detail = description, screenshotPath = screenshotPath)
        } catch (error: Exception) {
            screen?.recycle()
            throw error
        }
        log("全局验证：当前位于${page.state.label}；原因：${redact(reason)}")
        if (captureError != null) log("全局验证：$captureError")
        screenshotPath?.let { log("失败截图：$it") }
        reasonPath?.let { log("失败原因文件：$it") }
        return TaskFailureSnapshot(screen, page, screenshotPath, reasonPath, captureError)
    }

    internal suspend fun reportVerifiedFailure(snapshot: TaskFailureSnapshot, result: TaskResult): TaskResult {
        snapshot.reasonPath?.let { path ->
            try {
                withContext(Dispatchers.IO) { File(path).appendText("\n最终失败原因：${redact(result.detail)}\n") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log("追加失败原因文件失败：${error.message ?: "未知错误"}")
            }
        }
        return result.copy(retryable = false, detail = buildString {
            append(result.detail)
            append("；当前页面：${snapshot.page.state.label}")
            append("；失败截图：${snapshot.screenshotPath ?: "未能保存（${snapshot.captureError ?: "保存失败"}）"}")
            snapshot.reasonPath?.let { append("；原因文件：$it") }
        })
    }

    /** Preserves an unrecognized task screen before navigation changes it. */
    suspend fun saveTaskDiagnostic(taskId: String) {
        val screen = device.screenshot() ?: run {
            log("任务异常时无法获取诊断截图")
            return
        }
        try {
            val directory = HottaApp.instance.getExternalFilesDir("diagnostics")
                ?: File(HottaApp.instance.filesDir, "diagnostics")
            val file = File(directory, "${taskId}_${System.currentTimeMillis()}.png")
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                file.outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                saveHudOverlay(screen, directory, file.nameWithoutExtension)
            }
            log("任务诊断截图：${file.absolutePath}")
            record("diagnostic_saved", screenshotPath = file.absolutePath)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log("保存任务诊断截图失败：${error.message ?: "未知错误"}")
        } finally {
            screen.recycle()
        }
    }

    private fun saveHudOverlay(screen: Bitmap, directory: File, name: String) {
        val detection = inspectHud(screen)
        val annotated = screen.copy(Bitmap.Config.ARGB_8888, true) ?: return
        try {
            val canvas = android.graphics.Canvas(annotated)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                strokeWidth = 3f
                textSize = (screen.height * 0.025f).coerceAtLeast(14f)
            }
            val scores = mapOf("menu" to detection.menuScore)
            for ((key, region) in GameScreenDetector.searchRegions) {
                val left = region.left * screen.width
                val top = region.top * screen.height
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawRect(left, top, region.right * screen.width, region.bottom * screen.height, paint)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawText("$key ${"%.2f".format(scores[key])}", left + 4f, top + paint.textSize, paint)
            }
            val file = File(directory, "${name}_hud_regions.png")
            file.outputStream().use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(directory, "${name}_hud_scores.txt").writeText("${screen.width}x${screen.height}\n${detection.summary()}")
            log("主界面诊断：${detection.summary()}；搜索区域截图：${file.absolutePath}")
        } finally { annotated.recycle() }
    }

    suspend fun enterFromTitle(): Boolean {
        val entry = waitUntil(700, 500, TitleScreenDetector::findEntry) ?: return false
        return tapTitleEntry(entry.point)
    }

    suspend fun tapTitleEntry(point: Point): Boolean {
        check(tryTaskStep("login:title_entry")) { "登陆首页点击进入重试1次仍未切换" }
        log("识别到登陆首页，点击进入")
        device.tap(point.x, point.y)
        val changed = waitUntil(3_000, 500) { shot ->
            if (TitleScreenDetector.findEntry(shot) == null) MatchResult(Point(0, 0), 1f) else null
        } != null
        if (!changed) {
            log("点击进入后仍是登陆首页，下一轮继续识别")
            return !firstLoginRecognition
        }
        if (firstLoginRecognition) {
            log("进入游戏加载：已确认离开登陆首页")
            awaitFirstLoginGameMenu()
        }
        return true
    }

    /** The last first-login phase: each poll frame checks the menu and the reward-recovery title together. */
    suspend fun awaitFirstLoginGameMenu() {
        check(firstLoginRecognition)
        if (firstLoginMenuConfirmed) return
        log("进入游戏加载：固定等待30秒")
        delay(30_000L)
        log("进入游戏加载：等待结束，每2秒同时识别游戏主界面菜单和奖励找回，最多60秒")
        resetHudDetection()
        // No shared grace window: the search starts after the fixed loading wait.
        val menuDeadline = elapsedRealtime() + 60_000L
        var rewardSeen = false
        var rewardTaps = 0
        val foundMenu = withTimeoutOrNull(60_000L) {
            var round = 0
            while (elapsedRealtime() < menuDeadline) {
                currentCoroutineContext().ensureActive()
                val roundStartedAt = elapsedRealtime()
                if (menuDeadline - roundStartedAt <= 0) break
                round++
                val shot = device.screenshot()
                var matchedMenu = false
                var suppressMissLog = false
                if (shot == null) {
                    clearHudConfirmation()
                    hudScreenshotAvailable = false
                } else {
                    try {
                        val visible = rewardRecoveryVisible(shot)
                        val detection = inspectMenuFrame(shot)
                        if (visible) {
                            clearHudConfirmation()
                            check(rewardTaps < 2) { "奖励找回弹窗重试1次仍未关闭" }
                            log(
                                if (rewardTaps == 0) "识别到“奖励找回”，点击上方空白处关闭"
                                else "“奖励找回”仍在，点击下方空白处关闭",
                            )
                            tapRewardRecoveryBlank(rewardTaps)
                            rewardTaps++
                            rewardSeen = true
                            if (detection.accepted) {
                                log("进入游戏加载：第 $round 轮已识别到主界面菜单，奖励找回仍在，先关闭后继续轮询")
                                suppressMissLog = true
                            }
                        } else {
                            if (rewardSeen) {
                                log("已确认“奖励找回”关闭")
                                rewardSeen = false
                                rewardTaps = 0
                            }
                            matchedMenu = acceptSingleMenuFrame(shot, detection)
                        }
                    } finally {
                        shot.recycle()
                    }
                }
                if (matchedMenu && elapsedRealtime() < menuDeadline) {
                    return@withTimeoutOrNull true
                }
                if (!suppressMissLog) {
                    log("进入游戏加载：第 $round 轮未识别到主界面菜单，${hudDetectionSummary()}")
                }
                val nextRoundAt = minOf(menuDeadline, roundStartedAt + 2_000L)
                val remaining = nextRoundAt - elapsedRealtime()
                if (remaining > 0) delay(remaining)
            }
            false
        } == true
        if (!foundMenu) {
            throw IllegalStateException("首次登陆流程失败：30秒等待后轮询60秒仍未识别到游戏主界面菜单")
        }
        firstLoginMenuConfirmed = true
        log("游戏主界面菜单已确认，首次登陆流程结束")
    }

    private fun inspectMenuFrame(shot: Bitmap): com.aliothmoon.maahotta.vision.HudDetection {
        hudScreenshotAvailable = true
        hudTemplatesAvailable = hudTemplates() != null
        val detection = inspectHud(shot)
        lastHudDetail = detection.summary()
        return detection
    }

    private fun acceptSingleMenuFrame(
        shot: Bitmap,
        detection: com.aliothmoon.maahotta.vision.HudDetection,
    ): Boolean {
        if (!detection.accepted) {
            clearHudConfirmation()
            return false
        }
        pendingHudFrame = detection
        pendingHudCount = 1
        pendingHudAt = elapsedRealtime()
        pendingHudWidth = shot.width
        pendingHudHeight = shot.height
        lastHudDetail += "；菜单匹配确认成功"
        log(lastHudDetail)
        return true
    }

    fun hudTemplates(): com.aliothmoon.maahotta.vision.HudTemplates? {
        val menu = templates.get("hud_menu_body") ?: return null
        if (firstLoginRecognition) return com.aliothmoon.maahotta.vision.HudTemplates(menu)
        return com.aliothmoon.maahotta.vision.HudTemplates(
            menu,
            null,
            templates.get("bygone_exit_dialog"),
            templates.get("bygone_exit_confirm"),
            null,
            templates.get("bygone_exit_icon"),
            templates.get("bygone_scene_timer"),
            templates.get("bygone_warp_start"),
        )
    }

    /** Require matching frames; never retain a hit across explicit resets or failed frames. */
    private var pendingHudFrame: com.aliothmoon.maahotta.vision.HudDetection? = null
    private var pendingHudCount = 0
    private var pendingHudAt = 0L
    private var pendingHudWidth = 0
    private var pendingHudHeight = 0
    private val hudConfirmTtlMs = 2_500L

    private fun invalidateScreenEvidence() {
        mailExitMenu = null
        loginMenuForCheckIn = null
        clearHudConfirmation()
    }

    private fun clearHudConfirmation() {
        pendingHudFrame = null
        pendingHudCount = 0
        pendingHudAt = 0L
        pendingHudWidth = 0
        pendingHudHeight = 0
    }

    private fun hasFreshHudConfirmation(requiredFrames: Int): Boolean =
        pendingHudCount >= requiredFrames &&
            pendingHudFrame?.accepted == true &&
            elapsedRealtime() - pendingHudAt <= hudConfirmTtlMs

    /** Consume completed evidence or finish only the frames missing before the login deadline. */
    suspend fun finishPendingHudConfirmation(deadline: Long, allowAdditionalFrames: Boolean = true): Boolean {
        currentCoroutineContext().ensureActive()
        val first = pendingHudFrame ?: return false
        val counted = pendingHudCount
        pendingHudFrame = null
        if (pendingHudAt > deadline) return false
        if (counted >= 2) {
            lastHudDetail = first.summary() + "；菜单连续确认成功"
            log("菜单确认已完成，保留本轮确认结果")
            return true
        }
        if (!allowAdditionalFrames) return false
        log("登录等待到时，菜单已命中${counted}/2帧；最多追加10秒完成确认")
        return withTimeoutOrNull(10_000L) {
            var previous = first
            repeat((2 - counted).coerceAtLeast(1)) {
                delay(300)
                val shot = device.screenshot() ?: return@withTimeoutOrNull false
                val next = try {
                    if (shot.width != pendingHudWidth || shot.height != pendingHudHeight) return@withTimeoutOrNull false
                    inspectHud(shot)
                } finally { shot.recycle() }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!GameScreenDetector.stablePair(previous, next, pendingHudHeight)) {
                    lastHudDetail = next.summary() + "；菜单连续确认未通过"
                    return@withTimeoutOrNull false
                }
                previous = next
            }
            lastHudDetail = previous.summary() + "；菜单连续确认成功"
            log(lastHudDetail)
            true
        } ?: false
    }

    /**
     * Confirm the character HUD. Default is 2 stable frames with a short reuse window so
     * navigation does not re-scan three full screenshots on every call. Login can request 3.
     */
    suspend fun hasEnteredGame(requiredFrames: Int = 2): Boolean {
        val frames = requiredFrames.coerceIn(1, 3)
        if (hasFreshHudConfirmation(frames)) return true
        clearHudConfirmation()
        if (dismissLineSwitch()) return false
        val bundle = hudTemplates()
        hudTemplatesAvailable = bundle != null
        if (bundle == null) return false
        var previous: com.aliothmoon.maahotta.vision.HudDetection? = null
        repeat(frames) { index ->
            if (index > 0) delay(300)
            val shot = device.screenshot() ?: run { clearHudConfirmation(); return false }
            hudScreenshotAvailable = true
            val frameHeight = shot.height
            val frameWidth = shot.width
            val detection = try { inspectHud(shot) }
                finally { shot.recycle() }
            lastHudDetail = detection.summary()
            if (!detection.accepted) { clearHudConfirmation(); return false }
            if (previous != null && !GameScreenDetector.stablePair(previous!!, detection, frameHeight)) {
                clearHudConfirmation()
                lastHudDetail += "；菜单位置不稳定"
                return false
            }
            pendingHudFrame = detection
            pendingHudCount = index + 1
            if (index == 0) pendingHudAt = elapsedRealtime()
            pendingHudWidth = frameWidth
            pendingHudHeight = frameHeight
            lastHudDetail += if (index + 1 < frames) {
                "；菜单连续命中${index + 1}/${frames}帧"
            } else if (frames == 1) {
                "；菜单匹配确认成功"
            } else {
                "；菜单连续${frames}帧确认成功"
            }
            log(lastHudDetail)
            previous = detection
        }
        return true
    }

    fun resetHudDetection() {
        clearHudConfirmation()
        lastHudDetail = "尚未执行菜单识别"
        hudTemplatesAvailable = false
        hudScreenshotAvailable = false
    }

    fun hudDetectionSummary(): String = when {
        !hudTemplatesAvailable -> "主界面验证模板未完整载入"
        !hudScreenshotAvailable -> "未能取得游戏画面截图"
        else -> lastHudDetail
    }

    suspend fun gameScreen(): GameScreen {
        if (firstLoginRecognition) {
            // No expanded-menu/Settings classification or negative HUD gates in the first-login flow.
            return if (hasEnteredGame(requiredFrames = 2)) GameScreen.HUD else GameScreen.OTHER
        }
        if (dismissLineSwitch()) return GameScreen.OTHER
        val shot = device.screenshot() ?: return GameScreen.OTHER
        val result = try { GameScreenDetector.classify(shot, hudTemplates(),
            com.aliothmoon.maahotta.vision.HudExclusions.forTask(safety.taskId)) }
            finally { shot.recycle() }
        if (result != GameScreen.HUD) return result
        // Reuse a fresh confirmation instead of running another multi-frame scan every call.
        return if (hasEnteredGame(requiredFrames = 2)) GameScreen.HUD else GameScreen.OTHER
    }
}

class ScreenshotUnavailableException : IllegalStateException(
    "无法获取屏幕截图，请检查云手机的无障碍截图权限和游戏画面",
)

interface GameTask {
    val id: String
    val title: String
    fun shouldNavigate(): Boolean = true
    fun allowEngineRetry(): Boolean = true
    fun allowEngineRelogin(): Boolean = true
    fun isFirstLoginFlow(): Boolean = false
    suspend fun run(ctx: BotContext): TaskResult
}

class TaskEngine(
    private val context: BotContext,
    private val expectedAccountId: String? = null,
    private val relogin: (suspend () -> TaskResult)? = null,
) {
    suspend fun runAll(tasks: List<GameTask>): List<TaskResult> {
        context.preserveTaskPage = true
        val out = mutableListOf<TaskResult>()
        for (task in tasks) {
            context.beginTask(task.id)
            val runner = TaskAttemptRunner(
                safety = context.safety,
                log = context.log,
                detectDisconnect = { false },
                relogin = relogin?.let { action ->
                    suspend {
                        val loginResult = action()
                        if (loginResult.ok && expectedAccountId != null &&
                            !context.accountSession.isVerifiedFor(expectedAccountId)) {
                            TaskResult.uncertain("重新登录", "重新登录后账号身份未确认")
                        } else loginResult
                    }
                },
                onException = { Timber.e(it, task.id) },
            )
            var result = runner.run(task.title, task.allowEngineRetry(), task.allowEngineRelogin()) {
                if (task.id != "login" && expectedAccountId != null &&
                    !context.accountSession.isVerifiedFor(expectedAccountId)) {
                    TaskResult.uncertain(task.title, "当前账号身份未确认，禁止执行任务")
                } else {
                    // Login launches the game itself; no game window may exist yet.
                    val goal = if (task.shouldNavigate()) TaskNavigationMachine.goalFor(task.id) else null
                    if (goal != null && !TaskNavigationMachine.reach(
                            context, goal, reuseLoginMenuForCheckIn = task.id == "check_in",
                        )) {
                        TaskResult(task.title, false, "当前页面无法确认或无法到达任务页面，状态导航已停止")
                    } else task.run(context)
                }
            }
            if (!result.ok && !task.isFirstLoginFlow()) result = verifyFailure(task, result)
            context.finishTask(result)
            context.log(if (result.ok) "完成 ${task.title}: ${result.detail}" else "失败 ${task.title}: ${result.detail}")
            out += result
            if (!result.ok) {
                suspend fun saveDiagnostics() {
                    if (task.id == "login") context.saveLoginDiagnostic()
                    context.saveTaskDiagnostic(task.id)
                }
                if (task.isFirstLoginFlow()) context.withFirstLoginRecognition { saveDiagnostics() }
                context.log(
                    if (result.retryable) "${task.title} 重试后仍失败，停止后续任务"
                    else "${task.title} 状态无法确认，已停止后续任务",
                )
                break
            }
        }
        return out
    }

    /** One global verification phase; submitted actions resume their saved result checks only. */
    private suspend fun verifyFailure(task: GameTask, original: TaskResult): TaskResult {
        context.log("${task.title} 执行失败，开始全局验证当前页面并选择下一步")
        var snapshot = context.captureTaskFailure(task.id, original.detail)
        var continuationStarted = false
        try {
            val recovered = try {
                withTimeoutOrNull(90_000) recovery@{
                    if (task.id !in setOf("login", "account_transition") && expectedAccountId != null &&
                        !context.accountSession.isVerifiedFor(expectedAccountId)) {
                        return@recovery TaskResult.uncertain(task.title, "当前账号身份未确认，禁止执行任务")
                    }
                    // Interruption handling keeps the task's submission history and shared retry budget.
                    while (snapshot.captureError == null && snapshot.page.state in setOf(
                            PageState.LINE_SELECTION, PageState.ANNOUNCEMENT, PageState.REWARD_RECOVERY)) {
                        continuationStarted = true
                        context.log("全局验证下一步：先处理${snapshot.page.state.label}")
                        if (!TaskNavigationMachine.clearFailureInterruption(context, snapshot.page)) {
                            return@recovery TaskResult.uncertain(task.title, "已识别${snapshot.page.state.label}，但未确认安全的关闭入口")
                        }
                        delay(700)
                        val next = context.captureTaskFailure(task.id, original.detail)
                        snapshot.recycle()
                        snapshot = next
                    }
                    val screen = snapshot.screen
                    if (screen == null || snapshot.captureError != null) {
                        return@recovery TaskResult.uncertain(task.title, snapshot.captureError ?: "无法获取当前画面")
                    }
                    if (snapshot.page.state == PageState.UNKNOWN) {
                        return@recovery TaskResult.uncertain(task.title, "无法判定当前所在位置，停止操作")
                    }
                    // The callback retains progress such as DAY7 claimed / cumulative reward pending.
                    if (context.hasTaskFailureRecovery()) {
                        continuationStarted = true
                        context.log("全局验证下一步：按${snapshot.page.state.label}续接${task.title}，保留已提交动作的进度")
                        context.recoverTaskFailure(screen, snapshot.page)?.let { return@recovery it.copy(name = task.title) }
                    }
                    if (context.safety.hasSubmittedAction) {
                        return@recovery TaskResult.uncertain(task.title,
                            "已识别${snapshot.page.state.label}，但原提交结果或后续完成条件仍无法确认，不重复提交")
                    }
                    if (task.id in setOf("login", "account_transition") ||
                        snapshot.page.state in setOf(PageState.LOGIN_TITLE, PageState.LOGIN_ACCOUNT)) {
                        return@recovery TaskResult.uncertain(task.title, "已识别账号相关页面，但无法安全续接本次登录/切号")
                    }
                    if (expectedAccountId != null && !context.accountSession.isVerifiedFor(expectedAccountId)) {
                        return@recovery TaskResult.uncertain(task.title, "当前账号身份未确认，禁止执行任务")
                    }
                    val goal = TaskNavigationMachine.goalFor(task.id)
                        ?: return@recovery TaskResult.uncertain(task.title, "当前页面没有可确认的任务恢复路径")
                    context.log("全局验证下一步：从${snapshot.page.state.label}导航到 $goal，继续尚未提交的任务")
                    // Do not enter the runner again or reset attempts. Previously exhausted steps stay exhausted.
                    continuationStarted = true
                    if (!TaskNavigationMachine.reach(context, goal, initialObservation = snapshot.page)) {
                        return@recovery TaskResult.uncertain(task.title, "按当前位置导航仍未到达任务页面")
                    }
                    task.run(context)
                } ?: TaskResult.uncertain(task.title, "全局验证及恢复超时，停止操作")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.e(error, "Global task verification: ${task.id}")
                TaskResult.uncertain(task.title, "全局验证后仍失败：${error.message ?: "未知异常"}")
            }
            if (recovered.ok && context.safety.pendingStepId == null) {
                context.log("全局验证完成：${recovered.detail}")
                return recovered.copy(name = task.title)
            }
            val reason = if (recovered.ok) "动作 ${context.safety.pendingStepId} 尚未确认，禁止将任务判为成功" else recovered.detail
            val failed = TaskResult.uncertain(task.title, "${original.detail}；全局验证：$reason")
            if (continuationStarted) {
                // A recovery may have changed the screen. Preserve the final failed location as well.
                val finalSnapshot = context.captureTaskFailure(task.id, failed.detail)
                snapshot.recycle()
                snapshot = finalSnapshot
            }
            return context.reportVerifiedFailure(snapshot, failed)
        } finally {
            snapshot.recycle()
        }
    }
}
