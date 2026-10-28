package com.ghosti.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_STEALTH = "ACTION_UPDATE_STEALTH"

        private const val CHANNEL_VISIBLE = "ghosti_visible_channel"
        private const val CHANNEL_STEALTH = "ghosti_stealth_channel"
        private const val NOTIF_ID = 1001

        var stealthMode: Boolean = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GhostiTunnelEngine.stopEngine()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STEALTH -> {
                stealthMode = intent.getBooleanExtra("stealth", false)
                updateForegroundNotification()
            }
            else -> {
                val host = intent?.getStringExtra("vpsHost") ?: GhostiTunnelEngine.vpsHost
                val devId = intent?.getStringExtra("deviceId") ?: GhostiTunnelEngine.deviceId
                stealthMode = intent?.getBooleanExtra("stealth", stealthMode) ?: stealthMode

                startInForeground()
                GhostiTunnelEngine.startEngine(host, devId)
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Visible normal channel
            val visibleChannel = NotificationChannel(
                CHANNEL_VISIBLE,
                "Ghosti Active Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active Ghosti proxy status"
                setShowBadge(false)
            }
            nm.createNotificationChannel(visibleChannel)

            // Ultra-quiet minimized stealth channel
            val stealthChannel = NotificationChannel(
                CHANNEL_STEALTH,
                "Ghosti Background Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background connectivity"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(stealthChannel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (stealthMode) CHANNEL_STEALTH else CHANNEL_VISIBLE
        val priority = if (stealthMode) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(priority)
            .setVisibility(
                if (stealthMode) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setContentIntent(pendingIntent)

        if (stealthMode) {
            builder.setContentTitle("System Sync")
            builder.setContentText("Background service active")
        } else {
            val portInfo = if (GhostiTunnelEngine.assignedPort > 0) {
                "Port ${GhostiTunnelEngine.assignedPort}"
            } else {
                "Connecting..."
            }
            builder.setContentTitle("Ghosti Proxy Active ($portInfo)")
            builder.setContentText("Public IP: ${GhostiTunnelEngine.publicIp}")
        }

        return builder.build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Ghosti::ProxyWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    /**
     * Automatically detects when Wi-Fi or 4G Mobile Data changes/reconnects
     * and refreshes the phone's Public IP + tunnel state
     */
    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (GhostiTunnelEngine.isRunning.get()) {
                        Thread { GhostiTunnelEngine.fetchPublicIp() }.start()
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}