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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.UUID

class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val CHANNEL_ID = "ghosti_connected_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var isServiceRunning: Boolean = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        acquirePersistentLocks()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)

        if (intent?.action == ACTION_STOP) {
            isServiceRunning = false
            prefs.edit().putBoolean("proxy_active", false).apply()
            GhostiTunnelEngine.stopEngine()
            releasePersistentLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isServiceRunning = true
        prefs.edit().putBoolean("proxy_active", true).apply()

        var savedId = prefs.getString("device_id", null)
        if (savedId.isNullOrBlank()) {
            savedId = "node_" + UUID.randomUUID().toString().substring(0, 6)
            prefs.edit().putString("device_id", savedId).apply()
        }

        val host = "167.104.104.21"
        val devId = intent?.getStringExtra("deviceId") ?: savedId
        GhostiTunnelEngine.deviceId = devId

        acquirePersistentLocks()
        startInForeground()

        if (!GhostiTunnelEngine.isRunning.get()) {
            GhostiTunnelEngine.startEngine(host, devId)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keeps the service alive when swiped from RAM without restarting or dropping
        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("proxy_active", false)) {
            acquirePersistentLocks()
            startInForeground()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startInForeground() {
        try {
            val notification = buildSimpleNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {}
    }

    private fun buildSimpleNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghosti Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Ghosti connection status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ultra-clean, static, non-questionable notification (No upload arrow, no stats)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Ghosti")
            .setContentText("Connected")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquirePersistentLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Ghosti::PersistentWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Ghosti::PersistentWifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releasePersistentLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isServiceRunning && !GhostiTunnelEngine.isRunning.get()) {
                        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
                        val savedId = prefs.getString("device_id", GhostiTunnelEngine.deviceId) ?: "ghosti_node"
                        GhostiTunnelEngine.startEngine("167.104.104.21", savedId)
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
        releasePersistentLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}