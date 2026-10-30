package com.ghosti.app

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.UUID

class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_STEALTH = "ACTION_UPDATE_STEALTH"

        // New valid channel ID so Android resets any old broken channel settings
        private const val CHANNEL_PERSISTENT = "ghosti_persistent_service_v2"
        private const val NOTIF_ID = 1001

        var stealthMode: Boolean = false
        private var isManuallyStopped: Boolean = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isManuallyStopped = true
                GhostiTunnelEngine.stopEngine()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                isManuallyStopped = false
                val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
                var savedId = prefs.getString("device_id", null)
                if (savedId.isNullOrBlank()) {
                    savedId = "node_" + UUID.randomUUID().toString().substring(0, 6)
                    prefs.edit().putString("device_id", savedId).apply()
                }
                val host = "167.104.104.21"
                val devId = intent?.getStringExtra("deviceId") ?: savedId
                GhostiTunnelEngine.deviceId = devId

                acquireLocks()
                startInForeground()
                GhostiTunnelEngine.startEngine(host, devId)
            }
        }
        return START_STICKY
    }

    /**
     * When user swipes the app away from RAM / Recent Apps, keep the ForegroundService
     * locked and schedule an immediate failsafe heartbeat so the OS never drops it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isManuallyStopped && GhostiTunnelEngine.isRunning.get()) {
            try {
                startInForeground()
                val restartIntent = Intent(applicationContext, ProxyForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra("vpsHost", "167.104.104.21")
                    putExtra("deviceId", GhostiTunnelEngine.deviceId)
                }
                val pi = PendingIntent.getService(
                    applicationContext,
                    2002,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500L,
                    pi
                )
            } catch (_: Exception) {}
        }
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

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW is required by Android OS so RAM swipe NEVER kills the service,
            // while remaining 100% silent (no sound, no vibration, no popup).
            val channel = NotificationChannel(
                CHANNEL_PERSISTENT,
                "Ghosti Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ghosti proxy running in background"
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

        val portText = if (GhostiTunnelEngine.assignedPort > 0) {
            "Port ${GhostiTunnelEngine.assignedPort}"
        } else {
            "Connected"
        }

        // Valid system monochrome icon + both Title & Text required by Android ForegroundService validator
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Ghosti Active ($portText)")
            .setContentText("Background service running • ${GhostiTunnelEngine.publicIp}")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Ghosti::ProxyWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}

        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Ghosti::ProxyWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
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
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}