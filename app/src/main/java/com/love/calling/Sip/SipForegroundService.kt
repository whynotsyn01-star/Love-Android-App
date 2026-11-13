package com.love.calling.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
        startForeground(1001, buildNotification("Love SIP Connected"))

        // অ্যাপ ব্যাকগ্রাউন্ডে বা কিল হওয়ার পরও অটো-কানেক্ট রাখার জন্য
        SipManager.init(this)
        autoRegisterFromPrefs()

        iterateJob = serviceScope.launch {
            while (isActive) {
                SipManager.iterate()
                delay(20L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SipManager.init(this)
        if (SipManager.regState.value != "Online") {
            autoRegisterFromPrefs()
        }
        // অ্যাপ কিল করলে Android যেন নিজে থেকেই সার্ভিসটি আবার চালু করে দেয়
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // রিসেন্ট অ্যাপস থেকে সোয়াইপ করে কেটে দিলেও সার্ভিসটি পুনরায় চালু রাখার ব্যবস্থা
        val restartIntent = Intent(applicationContext, SipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun autoRegisterFromPrefs() {
        val prefs = getSharedPreferences("sip_prefs", Context.MODE_PRIVATE)
        val server = prefs.getString("server", "") ?: ""
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        val port = prefs.getInt("port", 5060)
        val tls = prefs.getBoolean("tls", false)
        if (server.isNotEmpty() && user.isNotEmpty()) {
            SipManager.register(server, user, pass, port, tls)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "love_sip_channel",
                "Love SIP Background Service",
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
            .setContentTitle("Love SIP")
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