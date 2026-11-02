package com.remotesupport.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "RemoteSupportScreenSharing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_STREAM"
        const val ACTION_STOP = "ACTION_STOP_STREAM"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_SERVER_URL = "EXTRA_SERVER_URL"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"

        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://192.168.0.107:3000"
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "PHONE-DEFAULT"

            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true

            if (resultData != null) {
                WebRtcManager.instance.initialize(
                    context = applicationContext,
                    screenCaptureIntent = resultData,
                    serverUrl = serverUrl,
                    deviceId = deviceId
                )
            }
        }

        return START_STICKY
    }

    private fun stopStream() {
        isRunning = false
        WebRtcManager.instance.disconnect()
        stopForeground(true)
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ تطبيق Professor يعمل في الخلفية")
            .setContentText("جلسة مساعدة نشطة ومحمية عبر خادم الدعم الفني.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "إشعارات الدعم الفني عن بُعد",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "يُعرض بشكل دائم أثناء بث الشاشة لإعلام المستخدم"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
