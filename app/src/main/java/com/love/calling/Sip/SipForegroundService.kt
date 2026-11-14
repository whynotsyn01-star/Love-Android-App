package com.love.calling.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.love.calling.MainActivity
import kotlinx.coroutines.*

class SipForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var iterateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startSafeForeground()

        try {
            SipManager.init(this)
            autoRegisterFromPrefs()
        } catch (_: Throwable) {}

        iterateJob = serviceScope.launch {
            while (isActive) {
                try {
                    SipManager.iterate()
                } catch (_: Throwable) {}
                delay(20L)
            }
        }
    }

    private fun startSafeForeground() {
        val notification = buildNotification("Love Active in Background")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(1001, notification)
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSafeForeground()
        try {
            SipManager.init(this)
            if (SipManager.regState.value != "Online") {
                autoRegisterFromPrefs()
            }
        } catch (_: Throwable) {}
        return START_STICKY
    }

    private fun autoRegisterFromPrefs() {
        try {
            val prefs = getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
            val server = prefs.getString("server", "") ?: ""
            val user = prefs.getString("user", "") ?: ""
            val pass = prefs.getString("pass", "") ?: ""
            val transport = prefs.getString("transport", "UDP") ?: "UDP"
            if (server.isNotEmpty() && user.isNotEmpty()) {
                SipManager.register(server, user, pass, transport)
            }
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "love_sip_channel",
                "Love Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "love_sip_channel")
            .setContentTitle("Love")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        iterateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}