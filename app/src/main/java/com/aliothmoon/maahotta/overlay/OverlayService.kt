package com.aliothmoon.maahotta.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aliothmoon.maahotta.MainActivity
import com.aliothmoon.maahotta.R

class OverlayService : Service() {
    private var panel: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF7FFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            addView(TextView(this@OverlayService).apply {
                text = "MAH"
                setTextColor(0xFF007E9B.toInt())
                textSize = 14f
            })
            addView(Button(this@OverlayService).apply {
                text = "打开主界面"
                setOnClickListener {
                    startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
            addView(Button(this@OverlayService).apply {
                text = "关闭悬浮窗"
                setOnClickListener { stopSelf() }
            })
        }
        panel = layout
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 180
        }
        wm.addView(layout, params)
    }

    override fun onDestroy() {
        panel?.let {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channelId = "overlay"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.channel_overlay), NotificationManager.IMPORTANCE_LOW),
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("MAH")
            .setContentText("悬浮控制已开启")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }
    }
}
