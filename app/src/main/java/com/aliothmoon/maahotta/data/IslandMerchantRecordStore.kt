package com.aliothmoon.maahotta.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IslandMerchantRecordStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = context.getExternalFilesDir("records") ?: File(context.filesDir, "records")
    private val recordFile = File(directory, RECORD_FILE_NAME)
    private val createdAtFile = File(directory, CREATED_AT_FILE_NAME)

    suspend fun cleanupExpired(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { cleanupExpiredLocked(System.currentTimeMillis()) }
    }.also {
        if (recordFile.exists()) scheduleExpiry(readCreatedAt())
    }

    suspend fun append(characterName: String): File? {
        val name = characterName.replace('\r', ' ').replace('\n', ' ').trim()
        if (name.isBlank()) return null

        val result = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                cleanupExpiredLocked(now)
                directory.mkdirs()
                if (!recordFile.exists()) {
                    recordFile.createNewFile()
                    createdAtFile.writeText(now.toString())
                }
                val alreadyRecorded = recordFile.useLines { lines ->
                    lines.any { it.trim() == name }
                }
                if (!alreadyRecorded) recordFile.appendText("$name\n")
                Pair(recordFile, readCreatedAt())
            }
        }
        scheduleExpiry(result.second)
        return result.first
    }

    fun path(): String = recordFile.absolutePath

    private fun cleanupExpiredLocked(now: Long): Boolean {
        if (!recordFile.exists()) {
            createdAtFile.delete()
            return false
        }
        val createdAt = readCreatedAt().takeIf { it > 0L } ?: recordFile.lastModified()
        if (now - createdAt < EXPIRY_MS) return false
        val deleted = recordFile.delete()
        createdAtFile.delete()
        cancelExpiry()
        return deleted
    }

    private fun readCreatedAt(): Long =
        createdAtFile.takeIf(File::exists)?.readText()?.trim()?.toLongOrNull()
            ?: recordFile.takeIf(File::exists)?.lastModified()
            ?: 0L

    private fun scheduleExpiry(createdAt: Long) {
        if (createdAt <= 0L) return
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            createdAt + EXPIRY_MS,
            expiryIntent(appContext),
        )
    }

    private fun cancelExpiry() {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(expiryIntent(appContext))
    }

    companion object {
        const val ACTION_EXPIRE = "com.aliothmoon.maahotta.EXPIRE_ISLAND_MERCHANT_RECORD"
        private const val RECORD_FILE_NAME = "人工岛老头.txt"
        private const val CREATED_AT_FILE_NAME = ".island_merchant_created_at"
        private const val EXPIRY_MS = 24L * 60L * 60L * 1_000L
        private const val REQUEST_CODE = 24_001
        private val lock = Any()

        private fun expiryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, IslandMerchantRecordExpiryReceiver::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        internal fun deleteFiles(context: Context) {
            val directory = context.getExternalFilesDir("records") ?: File(context.filesDir, "records")
            File(directory, RECORD_FILE_NAME).delete()
            File(directory, CREATED_AT_FILE_NAME).delete()
        }
    }
}

class IslandMerchantRecordExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == IslandMerchantRecordStore.ACTION_EXPIRE) {
            IslandMerchantRecordStore.deleteFiles(context)
        }
    }
}
