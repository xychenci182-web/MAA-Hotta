package com.aliothmoon.maahotta.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aliothmoon.maahotta.data.ConfigStore
import com.aliothmoon.maahotta.runtime.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutoStartBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val config = ConfigStore(context).config.first()
                AutoStartScheduler.scheduleNext(context, config.autoStartSchedule)
                if (config.keepAliveEnabled) {
                    runCatching { KeepAliveService.start(context) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
