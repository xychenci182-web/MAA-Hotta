package com.aliothmoon.maahotta.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.aliothmoon.maahotta.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)

object AppUpdater {
    private fun connection(address: String): HttpURLConnection {
        val url = URL(address)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "更新地址必须是 HTTPS 地址" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = false
        }
    }

    private fun open(address: String): HttpURLConnection {
        var next = address
        repeat(6) {
            val c = connection(next)
            try {
                when (c.responseCode) {
                    200 -> return c
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: error("更新地址跳转无效")
                        next = URL(c.url, location).toString()
                    }
                    else -> error("更新服务器返回 HTTP ${c.responseCode}")
                }
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
            c.disconnect()
        }
        error("更新地址跳转次数过多")
    }

    suspend fun check(address: String): AppUpdate = withContext(Dispatchers.IO) {
        val c = open(address)
        try {
            val bytes = c.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (buffer.size() <= 65_536) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(chunk)
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            require(bytes.size <= 65_536) { "更新说明文件过大" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            AppUpdate(json.getLong("versionCode"), json.getString("versionName"),
                json.getString("apkUrl"), json.getString("sha256").lowercase(),
                json.optString("notes")).also {
                require(it.versionCode > 0 && it.versionName.isNotBlank()) { "版本信息无效" }
                require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "更新文件缺少有效 SHA-256" }
                connection(it.apkUrl).disconnect()
            }
        } finally { c.disconnect() }
    }

    suspend fun download(context: Context, update: AppUpdate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "update.apk")
        file.delete()
        val c = open(update.apkUrl)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val total = c.contentLengthLong
            var received = 0L
            var previousProgress = -2
            c.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= 512L * 1024 * 1024) { "安装包超过大小限制" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val percent = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else -1
                    if (percent != previousProgress) {
                        previousProgress = percent
                        progress(percent)
                    }
                }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "安装包校验失败，请重新下载" }
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: error("下载文件不是有效 APK")
            require(archive.packageName == context.packageName && archive.longVersionCode == update.versionCode &&
                archive.longVersionCode > BuildConfig.VERSION_CODE) { "安装包包名或版本不匹配" }
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val expected = installed.signingInfo?.apkContentsSigners?.toSet()
            require(!expected.isNullOrEmpty() && archive.signingInfo?.apkContentsSigners?.toSet() == expected) {
                "安装包签名不同，无法覆盖当前应用"
            }
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally { c.disconnect() }
    }

    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        require(file.isFile) { "安装包已失效，请重新下载" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
