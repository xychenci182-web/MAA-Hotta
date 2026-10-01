package com.aliothmoon.maahotta.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Sends short task results only; local diagnostic files stay on the device. */
object BarkPushClient {
    suspend fun send(title: String, body: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("device_key", "[REDACTED]")
            .put("title", title.take(80))
            .put("body", body.take(800))
            .put("group", "MAH任务结果")
            .toString().toByteArray(Charsets.UTF_8)
        val connection = URL("https://api.day.app/push").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            check(connection.responseCode == 200) { "推送服务返回异常状态" }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(JSONObject(response).optInt("code") == 200) { "推送服务未接受消息" }
        } finally {
            connection.disconnect()
        }
    }
}
