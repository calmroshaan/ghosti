package com.ghosti.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object GhostiTunnelEngine {

    // Default VPS IP (User can also edit it inside the app UI anytime)
    var vpsHost: String = "YOUR_VPS_IP_HERE"
    var controlPort: Int = 9000

    var deviceId: String = "ghosti_node"
    var assignedPort: Int = 0
    var proxyUser: String = "---"
    var proxyPass: String = "---"
    var publicIp: String = "Detecting..."
    var ispName: String = ""
    var statusText: String = "Disconnected"
    var bytesUp: Long = 0L
    var bytesDown: Long = 0L

    val isRunning = AtomicBoolean(false)
    val isConnected = AtomicBoolean(false)

    private var controlSocket: Socket? = null
    private var controlWriter: PrintWriter? = null
    private val executor = Executors.newCachedThreadPool()

    var onStateChanged: (() -> Unit)? = null

    private fun notifyUI() {
        onStateChanged?.invoke()
    }

    fun startEngine(host: String, devId: String) {
        if (isRunning.get()) return
        vpsHost = host.trim()
        deviceId = devId.trim()
        isRunning.set(true)
        statusText = "Connecting to VPS..."
        notifyUI()

        executor.execute { fetchPublicIp() }
        executor.execute { connectionLoop() }
    }

    fun stopEngine() {
        isRunning.set(false)
        isConnected.set(false)
        statusText = "Disconnected"
        try {
            controlSocket?.close()
        } catch (_: Exception) {}
        controlSocket = null
        controlWriter = null
        notifyUI()
    }

    fun rotateCredentials() {
        executor.execute {
            try {
                val writer = controlWriter ?: return@execute
                val cmd = JSONObject().apply {
                    put("type", "ROTATE_CREDENTIALS")
                }
                synchronized(writer) {
                    writer.println(cmd.toString())
                    writer.flush()
                }
            } catch (_: Exception) {}
        }
    }

    fun fetchPublicIp() {
        try {
            val url = URL("http://ip-api.com/json/?fields=status,query,isp,org")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.optString("status") == "success") {
                publicIp = json.optString("query", "Unknown")
                ispName = json.optString("isp", "")
                notifyUI()
            }
        } catch (_: Exception) {
            publicIp = "Offline / Checking..."
            notifyUI()
        }
    }

    private fun connectionLoop() {
        while (isRunning.get()) {
            try {
                statusText = "Connecting to Relay..."
                notifyUI()

                fetchPublicIp()

                val sock = Socket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(InetSocketAddress(vpsHost, controlPort), 8000)

                controlSocket = sock
                val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                controlWriter = writer

                // Send REGISTER handshake
                val regJson = JSONObject().apply {
                    put("type", "REGISTER")
                    put("deviceId", deviceId)
                    if (assignedPort > 0) {
                        put("preferredPort", assignedPort)
                    }
                }
                synchronized(writer) {
                    writer.println(regJson.toString())
                    writer.flush()
                }

                // Start heartbeat ping thread
                executor.execute { heartbeatLoop(sock, writer) }

                while (isRunning.get() && !sock.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleServerMessage(line)
                }
            } catch (e: Exception) {
                isConnected.set(false)
                statusText = "Reconnecting (Waiting for Internet/VPS)..."
                notifyUI()
            } finally {
                isConnected.set(false)
                try {
                    controlSocket?.close()
                } catch (_: Exception) {}
            }

            if (isRunning.get()) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun heartbeatLoop(sock: Socket, writer: PrintWriter) {
        while (isRunning.get() && !sock.isClosed) {
            try {
                Thread.sleep(10000)
                val ping = JSONObject().apply { put("type", "PING") }
                synchronized(writer) {
                    writer.println(ping.toString())
                    writer.flush()
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleServerMessage(rawLine: String) {
        try {
            val msg = JSONObject(rawLine)
            when (msg.optString("type")) {
                "REGISTERED" -> {
                    assignedPort = msg.optInt("proxyPort", 0)
                    proxyUser = msg.optString("username", "---")
                    proxyPass = msg.optString("password", "---")
                    isConnected.set(true)
                    statusText = "Active & Tunneling (Port $assignedPort)"
                    notifyUI()
                }
                "OPEN_TUNNEL" -> {
                    val tunnelId = msg.optString("tunnelId")
                    val targetHost = msg.optString("host")
                    val targetPort = msg.optInt("port", 443)
                    executor.execute {
                        handleReverseTunnel(tunnelId, targetHost, targetPort)
                    }
                }
                "PONG" -> {
                    bytesUp = msg.optLong("bytesUp", bytesUp)
                    bytesDown = msg.optLong("bytesDown", bytesDown)
                    notifyUI()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Opens a connection to the target website using the Phone's Wi-Fi / 4G Internet
     * and bridges it back to the VPS Relay for Dolphin{anty}
     */
    private fun handleReverseTunnel(tunnelId: String, targetHost: String, targetPort: Int) {
        var targetSocket: Socket? = null
        var relaySocket: Socket? = null
        try {
            // 1. Connect to target website through Phone's network
            targetSocket = Socket()
            targetSocket.tcpNoDelay = true
            targetSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // 2. Connect back to VPS Control Port to bind the tunnel stream
            relaySocket = Socket()
            relaySocket.tcpNoDelay = true
            relaySocket.connect(InetSocketAddress(vpsHost, controlPort), 8000)

            val readyMsg = JSONObject().apply {
                put("type", "TUNNEL_READY")
                put("deviceId", deviceId)
                put("tunnelId", tunnelId)
            }.toString() + "\n"

            val relayOut = relaySocket.getOutputStream()
            relayOut.write(readyMsg.toByteArray(Charsets.UTF_8))
            relayOut.flush()

            val tSock = targetSocket
            val rSock = relaySocket

            // Pipe Relay -> Target Website
            executor.execute {
                pipeStreams(rSock, tSock)
            }
            // Pipe Target Website -> Relay
            pipeStreams(tSock, rSock)

        } catch (_: Exception) {
            try { targetSocket?.close() } catch (_: Exception) {}
            try { relaySocket?.close() } catch (_: Exception) {}
        }
    }

    private fun pipeStreams(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(16384)
            while (isRunning.get()) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }
}