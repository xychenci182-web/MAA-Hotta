package com.aliothmoon.maahotta.runtime

import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.IBinder
import com.aliothmoon.maahotta.BuildConfig
import com.aliothmoon.maahotta.HottaApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class ShizukuController : DeviceController {
    private val serviceRef = AtomicReference<IShellService?>()

    fun isReady(): Boolean = Shizuku.pingBinder() && serviceRef.get() != null

    suspend fun bind(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(7)
            delay(400)
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        if (serviceRef.get() != null) return true
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val args = Shizuku.UserServiceArgs(
                    ComponentName(HottaApp.instance, ShellUserService::class.java),
                )
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) {
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        serviceRef.set(IShellService.Stub.asInterface(binder))
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        serviceRef.set(null)
                    }
                }
                runCatching { Shizuku.bindUserService(args, connection) }
                    .onFailure {
                        Timber.e(it, "bindUserService")
                        if (cont.isActive) cont.resume(false)
                    }
            }
        } ?: false
    }

    private fun svc(): IShellService? = serviceRef.get()

    override fun backendName(): String = "Shizuku"

    override fun screenSize(): Point = realScreenSize()

    override suspend fun screenshot(): Bitmap? {
        val bytes = runCatching { svc()?.screenshotJpeg(1280, 90) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override suspend fun tap(x: Int, y: Int, holdMs: Long) {
        svc()?.exec("input tap $x $y")
        delay(120)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        svc()?.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
        delay(120)
    }

    override suspend fun inputText(text: String): Boolean {
        val shell = svc() ?: return false
        if (text.all { it.isDigit() } && text.isNotEmpty()) {
            for (ch in text) {
                val result = shell.exec("input keyevent ${7 + (ch - '0')}")
                if (result.startsWith("ERR:")) return false
                delay(45)
            }
            return true
        }
        // A separate, shell-quoted character avoids %s substitution and punctuation errors.
        for (ch in text) {
            if (ch.code !in 32..126) return false
            val argument = if (ch == ' ') "%s" else ch.toString()
            val quoted = "'" + argument.replace("'", "'\"'\"'") + "'"
            val result = shell.exec("input text $quoted")
            if (result.startsWith("ERR:")) return false
            delay(45)
        }
        return true
    }

    override suspend fun launchApp(packageName: String, forceStop: Boolean): Boolean {
        if (forceStop && !forceStop(packageName)) return false
        val result = svc()?.exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        delay(1500)
        return result != null && !result.startsWith("ERR:")
    }

    override suspend fun forceStop(packageName: String): Boolean {
        val result = svc()?.exec("am force-stop $packageName") ?: return false
        delay(400)
        return !result.startsWith("ERR:")
    }
}
