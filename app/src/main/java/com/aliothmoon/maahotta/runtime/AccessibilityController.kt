package com.aliothmoon.maahotta.runtime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import com.aliothmoon.maahotta.HottaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AccessibilityController : DeviceController {
    fun isReady(): Boolean = HottaAccessibilityService.isConnected()

    private fun service(): HottaAccessibilityService? = HottaAccessibilityService.instance.get()

    override fun backendName(): String = "无障碍"

    override fun activeWindowClassName(): String? = service()?.activeWindowClassName

    override fun screenSize(): Point = service()?.gameScreenSize() ?: Point(0, 0)

    override suspend fun screenshot(): Bitmap? = service()?.takeShot()

    override suspend fun tap(x: Int, y: Int, holdMs: Long) {
        service()?.dispatchTap(x, y, holdMs)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        service()?.dispatchSwipe(x1, y1, x2, y2, durationMs)
        delay(120)
    }

    override suspend fun inputText(text: String): Boolean {
        val ok = withContext(Dispatchers.Main) { service()?.trySetText(text) == true }
        delay(80)
        return ok
    }

    override suspend fun viewInfo(viewIdSuffix: String): ViewInfo? =
        withContext(Dispatchers.Main) { service()?.viewInfo(viewIdSuffix) }

    override suspend fun clickView(viewIdSuffix: String): Boolean =
        withContext(Dispatchers.Main) { service()?.clickView(viewIdSuffix) == true }

    override suspend fun clickText(text: String): Boolean =
        withContext(Dispatchers.Main) { service()?.clickText(text) == true }

    override suspend fun setViewText(viewIdSuffix: String, text: String): Boolean =
        withContext(Dispatchers.Main) { service()?.setViewText(viewIdSuffix, text) == true }

    override suspend fun maskedAccountPhone(): String? =
        withContext(Dispatchers.Main) { service()?.maskedAccountPhone() }

    override suspend fun launchApp(packageName: String, forceStop: Boolean): Boolean {
        val context = HottaApp.instance
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        delay(1500)
        return true
    }

    override suspend fun forceStop(packageName: String): Boolean = false
}
