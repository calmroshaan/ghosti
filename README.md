# 👻 Ghosti - Mobile & Residential Proxy System

Ghosti turns any Android smartphone (connected to Wi-Fi or 4G/5G mobile data) into a private residential/mobile proxy exit node. It routes your laptop or antidetect browser (Dolphin{anty}, AdsPower, GoLogin) traffic directly through the phone's internet connection with zero local IP leaks.

---

## 🚀 Key Features

- **True Residential & Mobile IP:** Websites and browsers see the exact Public IP of the Android phone's network (PTCL, Jazz, Zong, StormFiber, etc.).
- **500 Concurrent Devices Capacity:** Supports up to 500 independent phones simultaneously (Port range: `10001` - `10500`).
- **Strict Kill-Switch (Zero IP Leak):** If the phone disconnects or network drops, all traffic closes instantly. Traffic NEVER exits through the VPS or your laptop directly.
- **Dynamic Mobile IP Rotation:** When the phone toggles Airplane Mode or switches networks, the proxy automatically routes through the newly assigned carrier IP using the same credentials.
- **Stealth 24/7 Background Operation:** Minimized notification and battery exemption keep the proxy engine alive even when the phone screen is locked.
- **Multi-Protocol Support:** Compatible with SOCKS5 and HTTP/HTTPS proxy protocols with dedicated Username/Password auth.
- **Lightweight Native Android App:** Pure Kotlin native implementation (~2.7 MB APK) with near-zero battery and RAM footprint.

---

## 🏗️ Architecture

```text
[ Laptop / Dolphin{anty} Profile ]
                │
                │ (Connects to Proxy: VPS_IP:PORT:USER:PASS)
                ▼
      [ Ghosti VPS Relay Server ] (Sweden / Cloud Node)
                │
                │ (Reverse TCP Data Tunnel)
                ▼
       [ Ghosti Android App ] (Connected via Wi-Fi / 4G SIM)
                │
                │ (Direct Internet Exit)
                ▼
        [ Target Website ] (Sees Phone's Real Residential IP)


📁 Project Structure

Ghosti/
├── .github/workflows/
│   └── build-apk.yml          # Automated cloud build pipeline for Ghosti APK
├── vps-relay-server/
│   ├── server.js              # Reverse proxy relay & authentication engine (Node.js)
│   └── start-server.bat       # Auto-starter & firewall port opener (10001-10500)
├── ghosti-android/            # Native Android project (Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/ghosti/app/
│   │   │   │   ├── MainActivity.kt           # Blue-tech UI & device controls
│   │   │   │   ├── ProxyForegroundService.kt # 24/7 background service
│   │   │   │   └── GhostiTunnelEngine.kt     # Reverse tunnel & network monitor
│   │   │   └── res/drawable/
│   │   │       └── ic_launcher.xml           # Custom Ghosti cyber icon
│   │   └── build.gradle
│   ├── build.gradle
│   ├── gradle.properties
│   └── settings.gradle
└── README.md

🛠️ Setup Instructions

1. VPS Relay Server Setup (Windows VPS)
Copy the vps-relay-server folder to your VPS desktop.

Ensure Node.js (v18+ or v20+ LTS) is installed on the VPS.

Right-click start-server.bat and select Run as administrator.

The server will automatically configure Windows Firewall rules for:

Control Port: 9000 (TCP)

Proxy Ports: 10001 - 10500 (TCP)

Keep the command prompt window open (it auto-restarts if interrupted).

2. Android App Installation
Download the generated Ghosti-Android-APK.zip from your GitHub repository under Actions > latest build run > Artifacts.

Extract the ZIP and transfer app-debug.apk to any Android phone.

Install the APK (allow Install from unknown sources if prompted).

Launch Ghosti, grant battery optimization permission, and tap START PROXY SERVER.

The screen will immediately display:

Current Phone Public IP & ISP

Dedicated Proxy Port (10001, 10002, etc.)

Generated Username and Password

One-click copy format for Dolphin{anty}

3. Dolphin{anty} / Antidetect Browser Configuration
Create a new profile in Dolphin{anty}.

In the Proxy section, select SOCKS5 or HTTP.

Paste the credentials string in the format:
VPS_IP:PORT:USERNAME:PASSWORD
(Example: 167.104.104.21:10001:gh_ab123:x9y8z7w6)

Click Check Proxy — it should show the Android phone's IP and country.

Launch the profile.

🔒 Security & IP Leak Protection
No Direct Fallback: If the phone loses connection, the browser fails closed (Proxy Connection Refused). It never falls back to your local internet.

WebRTC Protection: In your antidetect browser, ensure WebRTC is set to Altered or Disabled to match the proxy IP.