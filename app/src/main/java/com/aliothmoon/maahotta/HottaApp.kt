package com.aliothmoon.maahotta

import android.app.Application
import com.aliothmoon.maahotta.data.ConfigStore
import com.aliothmoon.maahotta.runtime.KeepAliveService
import com.aliothmoon.maahotta.scheduler.AutoStartScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

class HottaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("L")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val store = ConfigStore(this@HottaApp)
            store.importBundledAccounts()
            val config = store.config.first()
            if (config.keepAliveEnabled) {
                runCatching { KeepAliveService.start(this@HottaApp) }
            }
            AutoStartScheduler.scheduleNext(this@HottaApp, config.autoStartSchedule)
        }
        instance = this
    }

    companion object {
        lateinit var instance: HottaApp
            private set
    }
}
