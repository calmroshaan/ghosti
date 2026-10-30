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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.UUID

class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_WATCHDOG = "ACTION_WATCHDOG"
        const val ACTION_UPDATE_STEALTH = "ACTION_UPDATE_STEALTH"

        private const val CHANNEL_PERSISTENT = "ghosti_persistent_service_v3"
        private const val NOTIF_ID = 1001
        private const val WATCHDOG_REQ_CODE = 3003

        var stealthMode: Boolean = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
            val shouldBeActive = prefs.getBoolean("proxy_active", false)
            if (shouldBeActive) {
                acquireLocks()
                startInForeground()
                val savedId = prefs.getString("device_id", GhostiTunnelEngine.deviceId) ?: "ghosti_node"
                if (!GhostiTunnelEngine.isRunning.get()) {
                    GhostiTunnelEngine.startEngine("167.104.104.21", savedId)
                }
                scheduleWatchdogAlarm(45000L)
                mainHandler.postDelayed(this, 25000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)

        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("proxy_active", false).apply()
            mainHandler.removeCallbacks(keepAliveRunnable)
            cancelWatchdogAlarm()
            GhostiTunnelEngine.stopEngine()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Start or Watchdog / Sticky resume
        prefs.edit().putBoolean("proxy_active", true).apply()

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

        // Start 25-second live foreground refresh & 45-second AlarmManager watchdog
        mainHandler.removeCallbacks(keepAliveRunnable)
        mainHandler.postDelayed(keepAliveRunnable, 25000L)
        scheduleWatchdogAlarm(45000L)

        return START_STICKY
    }

    /**
     * Triggered immediately when user swipes the app away from RAM / Recent Apps
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("proxy_active", false)) {
            acquireLocks()
            startInForeground()
            scheduleWatchdogAlarm(3000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleWatchdogAlarm(delayMs: Long) {
        try {
            val restartIntent = Intent(applicationContext, ProxyForegroundService::class.java).apply {
                action = ACTION_WATCHDOG
                putExtra("vpsHost", "167.104.104.21")
                putExtra("deviceId", GhostiTunnelEngine.deviceId)
            }

            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    WATCHDOG_REQ_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    WATCHDOG_REQ_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }

    private fun cancelWatchdogAlarm() {
        try {
            val restartIntent = Intent(applicationContext, ProxyForegroundService::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    WATCHDOG_REQ_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    WATCHDOG_REQ_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        } catch (_: Exception) {}
    }

    private fun startInForeground() {
        try {
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
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PERSISTENT,
                "Ghosti Active Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ghosti proxy active 24/7"
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
            "Online"
        }

        val upMb = String.format("%.1f", GhostiTunnelEngine.bytesUp / (1024.0 * 1024.0))
        val downMb = String.format("%.1f", GhostiTunnelEngine.bytesDown / (1024.0 * 1024.0))

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Ghosti Active ($portText)")
            .setContentText("IP: ${GhostiTunnelEngine.publicIp} • Up: ${upMb}MB Down: ${downMb}MB")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
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
        mainHandler.removeCallbacks(keepAliveRunnable)
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        releaseLocks()
        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("proxy_active", false)) {
            scheduleWatchdogAlarm(2000L)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}