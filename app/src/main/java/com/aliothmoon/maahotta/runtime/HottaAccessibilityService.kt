package com.aliothmoon.maahotta.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.aliothmoon.maahotta.constant.Packages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class HottaAccessibilityService : AccessibilityService() {
    private val screenshotMutex = Mutex()
    private var lastScreenshotStartedAt = 0L
    @Volatile private var lastGameDisplayId = Display.INVALID_DISPLAY
    @Volatile private var lastGameDisplaySeenAt = 0L

    @Volatile
    var activeWindowClassName: String? = null
        private set

    override fun onServiceConnected() {
        instance.set(this)
        Timber.i("accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activeWindowClassName = event.className?.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                event.packageName?.toString() == Packages.OFFICIAL &&
                event.displayId != Display.INVALID_DISPLAY
            ) {
                lastGameDisplayId = event.displayId
                lastGameDisplaySeenAt = SystemClock.elapsedRealtime()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun gameDisplayId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        // MuMu can put its launcher, this app and the game on three distinct
        // displays. The accessibility window identifies the game display.
        val allWindows = windowsOnAllDisplays
        for (index in 0 until allWindows.size()) {
            val displayWindows = allWindows.valueAt(index)
            for (window in displayWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val packageName = window.root?.packageName?.toString()
                if (packageName == Packages.OFFICIAL ||
                    (packageName == null && window.title?.toString() == "幻塔")
                ) {
                    val id = window.displayId
                    lastGameDisplayId = id
                    lastGameDisplaySeenAt = SystemClock.elapsedRealtime()
                    return id
                }
            }
        }
        // Window lists can briefly be empty while the game changes pages.
        return lastGameDisplayId.takeIf {
            it != Display.INVALID_DISPLAY &&
                SystemClock.elapsedRealtime() - lastGameDisplaySeenAt < 5_000L
        } ?: Display.INVALID_DISPLAY
    }

    fun gameScreenSize(): Point? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return realScreenSize()
        val id = gameDisplayId()
        if (id == Display.INVALID_DISPLAY) return null
        val display = getSystemService(DisplayManager::class.java).getDisplay(id) ?: return null
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point
    }

    suspend fun takeShot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val displayId = gameDisplayId()
        if (displayId == Display.INVALID_DISPLAY) return null
        return screenshotMutex.withLock {
            // Android rejects screenshots requested too close together. Callers
            // use different polling intervals, so enforce one interval here.
            val remaining = lastScreenshotStartedAt + 500L - SystemClock.elapsedRealtime()
            if (lastScreenshotStartedAt != 0L && remaining > 0L) delay(remaining)
            lastScreenshotStartedAt = SystemClock.elapsedRealtime()
            try {
                withTimeoutOrNull(3_000L) { captureShotOnce(displayId) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "takeScreenshot failed")
                null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureShotOnce(displayId: Int): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = try {
                            val wrapped = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            try {
                                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                wrapped?.recycle()
                            }
                        } catch (error: Exception) {
                            Timber.w(error, "screenshot buffer conversion failed")
                            null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        if (cont.isActive) cont.resume(bmp) else bmp?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        Timber.w("takeScreenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (error: Exception) {
            Timber.w(error, "takeScreenshot request failed")
            if (cont.isActive) cont.resume(null)
        }
    }

    suspend fun dispatchTap(x: Int, y: Int, holdMs: Long) {
        val displayId = gameDisplayId()
        check(displayId != Display.INVALID_DISPLAY) { "未找到游戏窗口，无法点击" }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs.coerceAtLeast(40))
        dispatch(gestureBuilder(displayId).addStroke(stroke).build())
    }

    suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val displayId = gameDisplayId()
        check(displayId != Display.INVALID_DISPLAY) { "未找到游戏窗口，无法滑动" }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80))
        dispatch(gestureBuilder(displayId).addStroke(stroke).build())
    }

    private fun gestureBuilder(displayId: Int): GestureDescription.Builder =
        GestureDescription.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(displayId)
        }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(false)
                }
            }, null)
        }

    fun trySetText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Never put credentials in an arbitrary first EditText if focus was lost.
        return setTextRecursive(root, text)
    }

    fun viewInfo(viewIdSuffix: String): ViewInfo? {
        val node = findView(viewIdSuffix) ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return ViewInfo(node.text?.toString(), node.isChecked, bounds)
    }

    fun clickView(viewIdSuffix: String): Boolean =
        findView(viewIdSuffix)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun clickText(text: String): Boolean {
        val node = findText(text) ?: return false
        var target: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = target ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            target = current.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        return dispatchGesture(
            gestureBuilder(gameDisplayId().takeIf { it != Display.INVALID_DISPLAY } ?: return false)
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(),
            null,
            null,
        )
    }

    fun maskedAccountPhone(): String? {
        val pattern = Regex("\\d{3}\\*+\\d{4}")
        rootInActiveWindow?.let { findMaskedPhone(it, pattern)?.let { phone -> return phone } }
        for (window in windows) {
            window.root?.let { findMaskedPhone(it, pattern)?.let { phone -> return phone } }
        }
        return null
    }

    fun setViewText(viewIdSuffix: String, text: String): Boolean {
        val node = findView(viewIdSuffix) ?: return false
        if (!node.isEditable) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findView(viewIdSuffix: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { findViewRecursive(it, viewIdSuffix)?.let { hit -> return hit } }
        for (window in windows) {
            window.root?.let { findViewRecursive(it, viewIdSuffix)?.let { hit -> return hit } }
        }
        return null
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { findTextRecursive(it, text)?.let { hit -> return hit } }
        for (window in windows) {
            window.root?.let { findTextRecursive(it, text)?.let { hit -> return hit } }
        }
        return null
    }

    private fun findTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser &&
            (node.text?.toString() == text || node.contentDescription?.toString() == text)
        ) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findTextRecursive(child, text)?.let { return it }
        }
        return null
    }

    private fun findViewRecursive(
        node: AccessibilityNodeInfo,
        viewIdSuffix: String,
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser &&
            node.viewIdResourceName?.substringAfterLast('/') == viewIdSuffix
        ) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findViewRecursive(child, viewIdSuffix)?.let { return it }
        }
        return null
    }

    private fun findMaskedPhone(node: AccessibilityNodeInfo, pattern: Regex): String? {
        if (!node.isVisibleToUser) return null
        val value = node.text?.toString() ?: node.contentDescription?.toString()
        pattern.find(value.orEmpty())?.let { return it.value }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findMaskedPhone(child, pattern)?.let { return it }
        }
        return null
    }

    private fun setTextRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        val editable = node.isEditable ||
            node.className?.toString()?.contains("EditText", ignoreCase = true) == true
        if (editable && node.isFocused) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (setTextRecursive(child, text)) return true
        }
        return false
    }

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            if (isConnected()) return true
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = android.content.ComponentName(context, HottaAccessibilityService::class.java)
            return enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it) == expected
            }
        }
        val instance = AtomicReference<HottaAccessibilityService?>()
        fun isConnected(): Boolean = instance.get() != null
    }
}
