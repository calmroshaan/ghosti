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