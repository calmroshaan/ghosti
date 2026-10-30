package com.ghosti.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvPublicIp: TextView
    private lateinit var tvIsp: TextView
    private lateinit var tvTraffic: TextView
    private lateinit var etVpsIp: EditText
    private lateinit var btnStartStop: Button
    private lateinit var tvCredsBox: TextView
    private lateinit var tvOneLineBox: TextView
    private lateinit var switchStealth: Switch

    companion object {
        private const val VPN_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#070B19")
        window.navigationBarColor = Color.parseColor("#070B19")

        val prefs = getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
        var savedDeviceId = prefs.getString("device_id", null)
        if (savedDeviceId.isNullOrBlank()) {
            savedDeviceId = "node_" + UUID.randomUUID().toString().substring(0, 6)
            prefs.edit().putString("device_id", savedDeviceId).apply()
        }
        GhostiTunnelEngine.deviceId = savedDeviceId

        val savedVpsIp = "167.104.104.21"
        GhostiTunnelEngine.vpsHost = savedVpsIp
        val savedStealth = prefs.getBoolean("stealth_mode", true)
        ProxyForegroundService.stealthMode = savedStealth

        setContentView(buildBlueTechUI(savedVpsIp, savedStealth))

        requestBatteryOptimizationExemption()

        GhostiTunnelEngine.onStateChanged = {
            runOnUiThread { refreshUI() }
        }

        Thread { GhostiTunnelEngine.fetchPublicIp() }.start()
        refreshUI()
    }

    override fun onDestroy() {
        GhostiTunnelEngine.onStateChanged = null
        super.onDestroy()
    }

    private fun buildBlueTechUI(initialVpsIp: String, initialStealth: Boolean): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#070B19"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#070B19"),
                    Color.parseColor("#0D1B3E"),
                    Color.parseColor("#070B19")
                )
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = "GHOSTI"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
        }

        val tvSubtitle = TextView(this).apply {
            text = "Mobile Residential Proxy Node"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvSubtitle)

        tvStatusBadge = TextView(this).apply {
            text = "DISCONNECTED"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBox("#334155", 20)
        }

        headerRow.addView(titleCol)
        headerRow.addView(tvStatusBadge)
        root.addView(headerRow)

        root.addView(space(20))

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#1E3A8A"),
                    Color.parseColor("#0284C7")
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }
        }

        val tvIpLabel = TextView(this).apply {
            text = "CURRENT PHONE PUBLIC IP (EXIT NODE)"
            setTextColor(Color.parseColor("#BAE6FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }

        tvPublicIp = TextView(this).apply {
            text = GhostiTunnelEngine.publicIp
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }

        tvIsp = TextView(this).apply {
            text = "ISP: Detecting Network..."
            setTextColor(Color.parseColor("#E0F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        tvTraffic = TextView(this).apply {
            text = "Traffic: UP 0.00 MB   |   DOWN 0.00 MB"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }

        heroCard.addView(tvIpLabel)
        heroCard.addView(tvPublicIp)
        heroCard.addView(tvIsp)
        heroCard.addView(tvTraffic)
        root.addView(heroCard)

        root.addView(space(18))

        val vpsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox("#111C38", 14, "#1E293B")
        }

        val tvVpsLabel = TextView(this).apply {
            text = "VPS RELAY SERVER IP"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }

        etVpsIp = EditText(this).apply {
            hint = "Enter your Sweden VPS IP"
            setHintTextColor(Color.parseColor("#64748B"))
            setText(initialVpsIp)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setPadding(0, dp(8), 0, dp(4))
        }

        vpsCard.addView(tvVpsLabel)
        vpsCard.addView(etVpsIp)
        vpsCard.visibility = View.GONE
        root.addView(vpsCard)

        btnStartStop = Button(this).apply {
            text = "START PROXY SERVER"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0284C7"), Color.parseColor("#2563EB"))
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { toggleProxyServer() }
        }
        root.addView(btnStartStop)

        root.addView(space(18))

        val credsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBox("#111C38", 16, "#1E3A8A")
        }

        val tvCredsTitle = TextView(this).apply {
            text = "PROXY CREDENTIALS (SOCKS5 & HTTP)"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }

        tvCredsBox = TextView(this).apply {
            text = "Tap 'START PROXY SERVER' to generate credentials..."
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(10))
        }

        val tvOneLineLabel = TextView(this).apply {
            text = "Dolphin{anty} One-Click Format (IP:Port:User:Pass):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        tvOneLineBox = TextView(this).apply {
            text = "---"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox("#070B19", 10, "#1E293B")
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }

        val btnCopy = Button(this).apply {
            text = "Copy"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBox("#0284C7", 10)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { copyCredentials() }
        }

        val btnShare = Button(this).apply {
            text = "Share"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBox("#0D9488", 10)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener { shareCredentials() }
        }

        btnRow.addView(btnCopy)
        btnRow.addView(btnShare)

        credsCard.addView(tvCredsTitle)
        credsCard.addView(tvCredsBox)
        credsCard.addView(tvOneLineLabel)
        credsCard.addView(space(6))
        credsCard.addView(tvOneLineBox)
        credsCard.addView(btnRow)
        root.addView(credsCard)

        root.addView(space(16))

        val stealthCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox("#111C38", 14, "#1E293B")
        }

        val stealthTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStealthTitle = TextView(this).apply {
            text = "Stealth Background Mode"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvStealthSub = TextView(this).apply {
            text = "Keep proxy alive 24/7 with minimized/silent status bar"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        stealthTextCol.addView(tvStealthTitle)
        stealthTextCol.addView(tvStealthSub)

        switchStealth = Switch(this).apply {
            isChecked = initialStealth
        }

        stealthCard.addView(stealthTextCol)
        stealthCard.addView(switchStealth)
        stealthCard.visibility = View.GONE
        root.addView(stealthCard)

        scrollView.addView(root)
        return scrollView
    }

    private fun toggleProxyServer() {
        if (GhostiTunnelEngine.isRunning.get()) {
            val stopIntent = Intent(this, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_STOP
            }
            startService(stopIntent)
            refreshUI()
        } else {
            // Prepare System-Bound VpnService (asks 1-time permission on first ever run)
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
            } else {
                startProxyServiceNow()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startProxyServiceNow()
            } else {
                Toast.makeText(
                    this,
                    "Please allow connection permission so Ghosti stays active in background!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startProxyServiceNow() {
        val host = "167.104.104.21"
        getSharedPreferences("ghosti_prefs", Context.MODE_PRIVATE)
            .edit().putString("vps_ip", host).apply()

        val startIntent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
            putExtra("vpsHost", host)
            putExtra("deviceId", GhostiTunnelEngine.deviceId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        refreshUI()
    }

    private fun refreshUI() {
        val running = GhostiTunnelEngine.isRunning.get()
        val connected = GhostiTunnelEngine.isConnected.get()

        tvPublicIp.text = GhostiTunnelEngine.publicIp
        tvIsp.text = if (GhostiTunnelEngine.ispName.isNotBlank()) {
            "ISP: ${GhostiTunnelEngine.ispName}  •  ${GhostiTunnelEngine.statusText}"
        } else {
            GhostiTunnelEngine.statusText
        }

        val upMb = String.format("%.2f", GhostiTunnelEngine.bytesUp / (1024.0 * 1024.0))
        val downMb = String.format("%.2f", GhostiTunnelEngine.bytesDown / (1024.0 * 1024.0))
        tvTraffic.text = "Traffic: UP $upMb MB   |   DOWN $downMb MB"

        if (connected) {
            tvStatusBadge.text = "ONLINE • PORT ${GhostiTunnelEngine.assignedPort}"
            tvStatusBadge.background = roundedBox("#059669", 20)
        } else if (running) {
            tvStatusBadge.text = "CONNECTING..."
            tvStatusBadge.background = roundedBox("#D97706", 20)
        } else {
            tvStatusBadge.text = "DISCONNECTED"
            tvStatusBadge.background = roundedBox("#334155", 20)
        }

        if (running) {
            btnStartStop.text = "STOP PROXY SERVER"
            btnStartStop.background = roundedBox("#DC2626", 14)
        } else {
            btnStartStop.text = "START PROXY SERVER"
            btnStartStop.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0284C7"), Color.parseColor("#2563EB"))
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
        }

        if (GhostiTunnelEngine.assignedPort > 0) {
            val host = GhostiTunnelEngine.vpsHost
            val port = GhostiTunnelEngine.assignedPort
            val user = GhostiTunnelEngine.proxyUser
            val pass = GhostiTunnelEngine.proxyPass

            tvCredsBox.text =
                "Type     : SOCKS5 or HTTP\n" +
                "Host/IP  : $host\n" +
                "Port     : $port\n" +
                "Username : $user\n" +
                "Password : $pass"

            tvOneLineBox.text = "$host:$port:$user:$pass"
        } else {
            tvCredsBox.text = "Tap 'START PROXY SERVER' to generate credentials..."
            tvOneLineBox.text = "---"
        }
    }

    private fun copyCredentials() {
        val line = tvOneLineBox.text.toString()
        if (line == "---" || line.isBlank()) {
            Toast.makeText(this, "No active credentials to copy yet!", Toast.LENGTH_SHORT).show()
            return
        }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Ghosti Proxy", line))
        Toast.makeText(this, "Copied: $line", Toast.LENGTH_SHORT).show()
    }

    private fun shareCredentials() {
        val line = tvOneLineBox.text.toString()
        if (line == "---" || line.isBlank()) {
            Toast.makeText(this, "Start Proxy first to share credentials!", Toast.LENGTH_SHORT).show()
            return
        }
        val shareText =
            "👻 *Ghosti Mobile Proxy Ready*\n\n" +
            "SOCKS5 / HTTP Format:\n`$line`\n\n" +
            "Exit IP: ${GhostiTunnelEngine.publicIp} (${GhostiTunnelEngine.ispName})"

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(sendIntent, "Share Ghosti Proxy via"))
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private fun roundedBox(hexColor: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}