package com.example.openslmserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class ServerService : Service() {

    private var server: KtorServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "OpenSLMServerChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "OpenSLM-Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenSLM:ServerWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        
        val notification = createNotification("Server is starting...")
        startForeground(NOTIFICATION_ID, notification)

        wakeLock?.acquire()

        server = KtorServer { log ->
            updateNotification(log)
        }
        
        server?.start(8080)
        updateNotification("Server running on port 8080")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        wakeLock?.release()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val name = "OpenSLM Server Service"
        val descriptionText = "Keep OpenSLM server running in background"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenSLM Server")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
