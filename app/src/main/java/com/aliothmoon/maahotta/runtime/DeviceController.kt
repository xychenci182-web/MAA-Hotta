package com.aliothmoon.maahotta.runtime

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build

data class ViewInfo(val text: String?, val checked: Boolean, val bounds: Rect)

interface DeviceController {
    fun screenSize(): Point
    suspend fun screenshot(): Bitmap?
    suspend fun tap(x: Int, y: Int, holdMs: Long = 60)
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400)
    suspend fun inputText(text: String): Boolean
    suspend fun launchApp(packageName: String, forceStop: Boolean = false): Boolean
    suspend fun forceStop(packageName: String): Boolean
    fun backendName(): String

    suspend fun viewInfo(viewIdSuffix: String): ViewInfo? = null
    suspend fun clickView(viewIdSuffix: String): Boolean = false
    suspend fun clickText(text: String): Boolean = false
    suspend fun setViewText(viewIdSuffix: String, text: String): Boolean = false
    suspend fun maskedAccountPhone(): String? = null
    fun activeWindowClassName(): String? = null
}

class CompositeController(
    private val accessibility: AccessibilityController,
    private val shizuku: ShizukuController,
) : DeviceController {
    private fun useAccessibilityCapture(): Boolean =
        accessibility.isReady() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun backendName(): String {
        return buildString {
            if (accessibility.isReady()) append("无障碍")
            if (shizuku.isReady()) {
                if (isNotEmpty()) append('+')
                append("Shizuku")
            }
            if (isEmpty()) append("未就绪")
        }
    }

    override fun screenSize(): Point =
        if (useAccessibilityCapture()) accessibility.screenSize() else shizuku.screenSize()

    override suspend fun screenshot(): Bitmap? =
        if (useAccessibilityCapture()) accessibility.screenshot() else shizuku.screenshot()

    override suspend fun tap(x: Int, y: Int, holdMs: Long) {
        if (accessibility.isReady()) accessibility.tap(x, y, holdMs)
        else shizuku.tap(x, y, holdMs)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        if (accessibility.isReady()) accessibility.swipe(x1, y1, x2, y2, durationMs)
        else shizuku.swipe(x1, y1, x2, y2, durationMs)
    }

    override suspend fun inputText(text: String): Boolean =
        if (text.isNotEmpty() && text.all(Char::isDigit) && shizuku.isReady()) {
            shizuku.inputText(text) || accessibility.inputText(text)
        } else {
            accessibility.inputText(text) || shizuku.inputText(text)
        }

    override suspend fun viewInfo(viewIdSuffix: String): ViewInfo? =
        accessibility.viewInfo(viewIdSuffix)

    override suspend fun clickView(viewIdSuffix: String): Boolean =
        accessibility.clickView(viewIdSuffix)

    override suspend fun clickText(text: String): Boolean = accessibility.clickText(text)

    override suspend fun setViewText(viewIdSuffix: String, text: String): Boolean =
        accessibility.setViewText(viewIdSuffix, text)

    override suspend fun maskedAccountPhone(): String? = accessibility.maskedAccountPhone()

    override fun activeWindowClassName(): String? = accessibility.activeWindowClassName()

    override suspend fun launchApp(packageName: String, forceStop: Boolean): Boolean {
        if (forceStop) forceStop(packageName)
        return shizuku.launchApp(packageName, false) || accessibility.launchApp(packageName, false)
    }

    override suspend fun forceStop(packageName: String): Boolean =
        shizuku.forceStop(packageName)
}
