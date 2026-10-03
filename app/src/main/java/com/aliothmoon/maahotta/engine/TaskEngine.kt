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

    fun beginAccount(id: String) {
        accountId = id
        journal?.record("account_started", accountId = id)
    }

    fun beginTask(id: String) {
        safety.beginTask(id)
        record("task_started")
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

    fun invalidateAccountIdentity() {
        accountSession.invalidate()
        record("account_invalidated")
    }

    fun finishTask(result: TaskResult) {
        record("task_finished", detail = result.detail, outcome = result.outcome.name)
    }

    private fun record(event: String, stepId: String? = null, detail: String = "", outcome: String? = null, screenshotPath: String? = null) {
        journal?.record(event, accountId = accountId, taskId = safety.taskId, stepId = stepId,
            detail = redact(detail), outcome = outcome, screenshotPath = screenshotPath)
    }

    /** Set by the engine: successful tasks leave their verified page for the next state transition. */
    var preserveTaskPage: Boolean = false
    private val rawDevice = device
    private val captureReadiness = CaptureReadiness(::elapsedRealtime, onWaiting = { remaining ->
        log("登录加载中，截图暂未就绪，继续等待（剩余 ${remaining / 1_000} 秒）")
    })

    suspend fun <T> withLoginCaptureDeadline(deadline: Long, action: suspend () -> T): T =
        captureReadiness.within(deadline, action)
    // All task screenshots pass through the highest-priority popup handler.
    val device: DeviceController = object : DeviceController by rawDevice {
        // Synchronous backends may not suspend before sending an input; reject cancelled runs here.
        override suspend fun tap(x: Int, y: Int, holdMs: Long) {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            rawDevice.tap(x, y, holdMs)
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            rawDevice.swipe(x1, y1, x2, y2, durationMs)
        }

        override suspend fun inputText(text: String): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.inputText(text)
        }

        override suspend fun clickView(viewIdSuffix: String): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.clickView(viewIdSuffix)
        }

        override suspend fun clickText(text: String): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.clickText(text)
        }

        override suspend fun setViewText(viewIdSuffix: String, text: String): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.setViewText(viewIdSuffix, text)
        }

        override suspend fun launchApp(packageName: String, forceStop: Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.launchApp(packageName, forceStop)
        }

        override suspend fun forceStop(packageName: String): Boolean {
            currentCoroutineContext().ensureActive()
            clearHudConfirmation()
            return rawDevice.forceStop(packageName)
        }

        override suspend fun screenshot(): Bitmap? {
            currentCoroutineContext().ensureActive()
            var handled = false
            repeat(10) {
                currentCoroutineContext().ensureActive()
                val shot = captureReadiness.read { rawDevice.screenshot() } ?: return null
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
        check(lineSwitchCancelCount < 3) { "线路切换弹窗取消3次仍未关闭" }
        val size = device.screenSize()
        if (size.x <= 0 || size.y <= 0) return true
        lineSwitchCancelCount++
        lineSwitchHandledGeneration++
        lastLineSwitchCancelAt = elapsedRealtime()
        log("识别到线路切换，点击取消（第 $lineSwitchCancelCount/3 次）")
        device.tap((cancelPoint.x.toDouble() * size.x / shot.width).toInt(),
            (cancelPoint.y.toDouble() * size.y / shot.height).toInt())
        return true
    }

    suspend fun dismissRewardRecoveryPopup(allowOcr: Boolean = false): Boolean {
        var detected = false
        repeat(3) { attempt ->
            val shot = device.screenshot()
            val visible = if (shot != null) {
                try {
                    RewardRecoveryDetector.isVisible(shot) ||
                        (allowOcr && ScreenTextFinder.find(shot, listOf("奖励找回")) != null)
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
            log(
                if (attempt == 0) "识别到“奖励找回”，点击上方空白处关闭"
                else "“奖励找回”仍在，点击下方空白处关闭",
            )
            val size = device.screenSize()
            val blankY = if (attempt % 2 == 0) size.y * 0.10f else size.y * 0.95f
            device.tap(size.x / 2, blankY.toInt())
        }
        return detected
    }

    /** OCRs the cropped heading, then locates the X in the same full screenshot. */
    suspend fun dismissAnnouncement(): Boolean {
        val screen = device.screenshot() ?: return false
        val closePoint = try {
            if (!AnnouncementDetector.hasLayout(screen)) {
                announcementStallStartedAt = 0L
                return false
            }
            // Express horizontal bounds in screen heights so wider phones do
            // not move a left-anchored heading outside the crop.
            val titleRegion = SearchRegion(
                (screen.height * 0.03f / screen.width).coerceIn(0f, 1f), 0.09f,
                (screen.height * 0.70f / screen.width).coerceIn(0f, 1f), 0.29f,
            )
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
            val templateConfirmed = if (ocrConfirmed) true else {
                templates.get("announcement_title")?.let { title ->
                    TemplateMatcher.match(
                        screen, title, threshold = 0.64f,
                        region = titleRegion,
                        referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT,
                    ) != null
                } == true
            }
            if (!templateConfirmed) {
                return announcementUnresolved(
                    "公告布局可见，但左上角标题未确认；OCR=${readText.distinct().take(6)}，重新截图",
                )
            }
            val closeRegion = SearchRegion(
                (1f - screen.height * 0.42f / screen.width).coerceIn(0f, 1f), 0.07f,
                (1f - screen.height * 0.01f / screen.width).coerceIn(0f, 1f), 0.32f,
            )
            val close = AnnouncementDetector.findClose(screen) ?: templates.get("announcement_close")?.let { icon ->
                TemplateMatcher.match(
                    screen, icon, threshold = 0.60f,
                    region = closeRegion,
                    referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT,
                )
            }
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
        log("已确认游戏公告，点击关闭 X (${closePoint.x},${closePoint.y})")
        device.tap(closePoint.x, closePoint.y)
        val gone = waitUntil(2_000, 500) { shot ->
            if (AnnouncementDetector.hasLayout(shot)) null else MatchResult(Point(0, 0), 1f)
        } != null
        if (gone) announcementStallStartedAt = 0L
        log(if (gone) "已确认游戏公告关闭" else "公告仍在，下一轮重新识别")
        if (!gone) announcementUnresolved("公告关闭后仍在画面中")
        return true
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
        val detection = GameScreenDetector.inspectHud(screen, hudTemplates())
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
        tapTitleEntry(entry.point)
        return true
    }

    suspend fun tapTitleEntry(point: Point) {
        log("识别到登录首页，点击进入")
        device.tap(point.x, point.y)
        val changed = waitUntil(3_000, 500) { shot ->
            if (TitleScreenDetector.findEntry(shot) == null) MatchResult(Point(0, 0), 1f) else null
        } != null
        if (!changed) log("点击进入后仍是登录首页，下一轮继续识别")
    }

    fun hudTemplates(): com.aliothmoon.maahotta.vision.HudTemplates? {
        return com.aliothmoon.maahotta.vision.HudTemplates(
            templates.get("hud_menu_body") ?: return null,
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
                    GameScreenDetector.inspectHud(shot, hudTemplates())
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
            val detection = try { GameScreenDetector.inspectHud(shot, bundle) }
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
        if (dismissLineSwitch()) return GameScreen.OTHER
        val shot = device.screenshot() ?: return GameScreen.OTHER
        val result = try { GameScreenDetector.classify(shot, hudTemplates()) }
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
            val result = runner.run(task.title, task.allowEngineRetry(), task.allowEngineRelogin()) {
                if (task.id != "login" && expectedAccountId != null &&
                    !context.accountSession.isVerifiedFor(expectedAccountId)) {
                    TaskResult.uncertain(task.title, "当前账号身份未确认，禁止执行任务")
                } else {
                    // Login launches the game itself; no game window may exist yet.
                    val goal = if (task.shouldNavigate()) TaskNavigationMachine.goalFor(task.id) else null
                    if (goal != null && !TaskNavigationMachine.reach(context, goal)) {
                        context.saveTaskDiagnostic(task.id)
                        // No task action has started. Allow the existing disconnect/relogin recovery,
                        // or one bounded navigation retry, before stopping the chain.
                        TaskResult(task.title, false, "当前页面无法确认或无法到达任务页面，状态导航已停止")
                    } else task.run(context)
                }
            }
            context.finishTask(result)
            context.log(if (result.ok) "完成 ${task.title}: ${result.detail}" else "失败 ${task.title}: ${result.detail}")
            out += result
            if (!result.ok) {
                if (task.id == "login") context.saveLoginDiagnostic()
                context.saveTaskDiagnostic(task.id)
                context.log(
                    if (result.retryable) "${task.title} 重试后仍失败，停止后续任务"
                    else "${task.title} 状态无法确认，已停止后续任务",
                )
                break
            }
        }
        return out
    }
}
