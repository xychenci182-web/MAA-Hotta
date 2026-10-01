package com.aliothmoon.maahotta

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import com.aliothmoon.maahotta.data.ConfigStore
import com.aliothmoon.maahotta.scheduler.AutoStartScheduler
import com.aliothmoon.maahotta.ui.HottaRoot
import com.aliothmoon.maahotta.ui.theme.MaaHottaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val autoStartRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        consumeAutoStartIntent(intent)
        enableEdgeToEdge()
        setContent {
            MaaHottaTheme {
                HottaRoot(autoStartRequest = autoStartRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoStartIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val schedule = ConfigStore(this@MainActivity).config.first().autoStartSchedule
            AutoStartScheduler.scheduleNext(this@MainActivity, schedule)
        }
    }

    private fun consumeAutoStartIntent(intent: android.content.Intent) {
        if (intent.action != AutoStartScheduler.ACTION_AUTO_START) return
        intent.action = null
        autoStartRequest.intValue++
        lifecycleScope.launch {
            val schedule = ConfigStore(this@MainActivity).config.first().autoStartSchedule
            AutoStartScheduler.scheduleNext(this@MainActivity, schedule)
        }
    }
}
