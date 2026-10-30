/**
 * GHOSTI - Multi-Phone Reverse Proxy Relay Server
 * Features:
 * - Supports SOCKS5 & HTTP Connect (Dolphin{anty}, AdsPower, Extensions)
 * - Permanent Disk Storage (devices.json) -> Credentials NEVER change on VPS restart or migration
 * - STRICT PROTOCOL KILL-SWITCH (Zero IP Leak: Returns 502/SOCKS error when offline so Chrome never falls back to Wi-Fi)
 * - Supports 500 phones (Ports 10001 - 10500)
 */

const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const CONTROL_PORT = 9000;
const BASE_PROXY_PORT = 10001;
const MAX_PROXY_PORT = 10500;
const DB_FILE = path.join(__dirname, 'devices.json');

// Active Phone Sessions: deviceId -> session object
const devices = new Map();
// Used proxy ports: port -> deviceId
const usedPorts = new Map();

function generateRandomString(len = 6) {
  return crypto.randomBytes(Math.ceil(len / 2)).toString('hex').slice(0, len);
}

/**
 * Saves all registered device credentials permanently to devices.json
 */
function saveDatabase() {
  try {
    const data = {};
    for (const [id, dev] of devices.entries()) {
      data[id] = {
        deviceId: dev.deviceId,
        proxyPort: dev.proxyPort,
        username: dev.username,
        password: dev.password,
      };
    }
    fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2), 'utf8');
  } catch (err) {
    console.error('[Ghosti] Failed to save devices.json:', err.message);
  }
}

/**
 * Loads saved device credentials from devices.json on server start
 */
function loadDatabase() {
  try {
    if (!fs.existsSync(DB_FILE)) return;
    const raw = fs.readFileSync(DB_FILE, 'utf8');
    const data = JSON.parse(raw);
    for (const id of Object.keys(data)) {
      const item = data[id];
      if (item && item.deviceId && item.proxyPort && item.username && item.password) {
        const device = {
          deviceId: item.deviceId,
          proxyPort: item.proxyPort,
          username: item.username,
          password: item.password,
          controlSocket: null,
          online: false,
          bytesUp: 0,
          bytesDown: 0,
          pendingTunnels: new Map(),
          activeSockets: new Set(),
          proxyServer: null,
        };
        usedPorts.set(device.proxyPort, device.deviceId);
        device.proxyServer = startProxyServerForDevice(device);
        devices.set(device.deviceId, device);
      }
    }
    console.log(`[Ghosti] Restored ${devices.size} permanent device(s) from devices.json`);
  } catch (err) {
    console.error('[Ghosti] Failed to load devices.json:', err.message);
  }
}

function allocatePort(preferredPort) {
  if (preferredPort && !usedPorts.has(preferredPort)) {
    return preferredPort;
  }
  for (let p = BASE_PROXY_PORT; p <= MAX_PROXY_PORT; p++) {
    if (!usedPorts.has(p)) return p;
  }
  throw new Error('No free proxy ports available');
}

/**
 * Starts a dedicated Proxy Listener Port for a specific Android phone.
 */
function startProxyServerForDevice(device) {
  const proxyServer = net.createServer((browserSocket) => {
    browserSocket.setNoDelay(true);

    // Handle initial handshake from Dolphin{anty} / Browser (SOCKS5 or HTTP CONNECT)
    // Never raw-destroy before reading request so Chromium NEVER falls back to DIRECT Wi-Fi
    browserSocket.once('data', (firstChunk) => {
      if (!device.controlSocket || device.controlSocket.destroyed || !device.online) {
        if (firstChunk[0] !== 0x05) {
          browserSocket.end(
            'HTTP/1.1 502 Bad Gateway\r\n' +
              'Connection: close\r\n' +
              'Content-Type: text/plain\r\n\r\n' +
              'Ghosti Kill-Switch Active: Phone is offline. Connection blocked to prevent IP leak.'
          );
        } else {
          browserSocket.end(Buffer.from([0x05, 0x01]));
        }
        return;
      }

      if (firstChunk[0] === 0x05) {
        handleSocks5BrowserClient(browserSocket, firstChunk, device);
      } else {
        handleHttpBrowserClient(browserSocket, firstChunk, device);
      }
    });

    browserSocket.on('error', () => {
      browserSocket.destroy();
    });
  });

  proxyServer.on('error', (err) => {
    console.error(`[Ghosti] Proxy port ${device.proxyPort} error:`, err.message);
  });

  proxyServer.listen(device.proxyPort, '0.0.0.0', () => {
    console.log(`[Ghosti] Device ${device.deviceId} listening on Proxy Port ${device.proxyPort}`);
  });

  return proxyServer;
}

/**
 * Authenticates SOCKS5 client from Dolphin{anty} and tunnels target request to the Phone
 */
function handleSocks5BrowserClient(browserSocket, greeting, device) {
  const nmethods = greeting[1] || 0;
  const methods = greeting.slice(2, 2 + nmethods);

  if (!methods.includes(0x02)) {
    browserSocket.end(Buffer.from([0x05, 0xff]));
    return;
  }

  browserSocket.write(Buffer.from([0x05, 0x02]));

  browserSocket.once('data', (authChunk) => {
    try {
      if (authChunk[0] !== 0x01) {
        browserSocket.end(Buffer.from([0x01, 0x01]));
        return;
      }
      const ulen = authChunk[1];
      const username = authChunk.slice(2, 2 + ulen).toString('utf8');
      const plen = authChunk[2 + ulen];
      const password = authChunk.slice(3 + ulen, 3 + ulen + plen).toString('utf8');

      if (username !== device.username || password !== device.password) {
        browserSocket.end(Buffer.from([0x01, 0x01]));
        return;
      }

      browserSocket.write(Buffer.from([0x01, 0x00]));

      browserSocket.once('data', (reqChunk) => {
        if (reqChunk[0] !== 0x05 || reqChunk[1] !== 0x01) {
          browserSocket.end(Buffer.from([0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
          return;
        }

        const atyp = reqChunk[3];
        let targetHost = '';
        let targetPort = 0;

        if (atyp === 0x01) {
          targetHost = `${reqChunk[4]}.${reqChunk[5]}.${reqChunk[6]}.${reqChunk[7]}`;
          targetPort = reqChunk.readUInt16BE(8);
        } else if (atyp === 0x03) {
          const dlen = reqChunk[4];
          targetHost = reqChunk.slice(5, 5 + dlen).toString('utf8');
          targetPort = reqChunk.readUInt16BE(5 + dlen);
        } else if (atyp === 0x04) {
          const parts = [];
          for (let i = 0; i < 8; i++) {
            parts.push(reqChunk.readUInt16BE(4 + i * 2).toString(16));
          }
          targetHost = parts.join(':');
          targetPort = reqChunk.readUInt16BE(20);
        } else {
          browserSocket.destroy();
          return;
        }

        openTunnelToPhone(device, targetHost, targetPort, (err, phoneStream) => {
          if (err || !phoneStream) {
            browserSocket.end(Buffer.from([0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
            return;
          }
          browserSocket.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
          pipeSocketsWithStats(browserSocket, phoneStream, device);
        });
      });
    } catch (e) {
      browserSocket.destroy();
    }
  });
}

/**
 * Authenticates HTTP/HTTPS Proxy client and tunnels target request to the Phone
 */
function handleHttpBrowserClient(browserSocket, firstChunk, device) {
  const headerStr = firstChunk.toString('utf8');
  const lines = headerStr.split('\r\n');
  const firstLine = lines[0] || '';

  const authLine = lines.find((l) => l.toLowerCase().startsWith('proxy-authorization:'));
  let authorized = false;
  if (authLine) {
    const parts = authLine.split(/\s+/);
    if (parts.length >= 3 && parts[1].toLowerCase() === 'basic') {
      const decoded = Buffer.from(parts[2], 'base64').toString('utf8');
      const [u, p] = decoded.split(':');
      if (u === device.username && p === device.password) {
        authorized = true;
      }
    }
  }

  if (!authorized) {
    browserSocket.end(
      'HTTP/1.1 407 Proxy Authentication Required\r\n' +
        'Proxy-Authenticate: Basic realm="Ghosti"\r\n' +
        'Connection: close\r\n\r\n'
    );
    return;
  }

  const [method, rawTarget] = firstLine.split(' ');
  if (!method || !rawTarget) {
    browserSocket.destroy();
    return;
  }

  if (method.toUpperCase() === 'CONNECT') {
    const [host, portStr] = rawTarget.split(':');
    const port = parseInt(portStr || '443', 10);

    openTunnelToPhone(device, host, port, (err, phoneStream) => {
      if (err || !phoneStream) {
        browserSocket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');
        return;
      }
      browserSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      pipeSocketsWithStats(browserSocket, phoneStream, device);
    });
  } else {
    try {
      const urlObj = new URL(rawTarget);
      const host = urlObj.hostname;
      const port = parseInt(urlObj.port || '80', 10);

      openTunnelToPhone(device, host, port, (err, phoneStream) => {
        if (err || !phoneStream) {
          browserSocket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');
          return;
        }
        const cleanedLines = lines.filter(
          (l) => !l.toLowerCase().startsWith('proxy-authorization:')
        );
        cleanedLines[0] = `${method} ${urlObj.pathname}${urlObj.search || ''} HTTP/1.1`;
        phoneStream.write(cleanedLines.join('\r\n'));
        pipeSocketsWithStats(browserSocket, phoneStream, device);
      });
    } catch (e) {
      browserSocket.destroy();
    }
  }
}

/**
 * Requests the connected Android phone to open a reverse data socket for (targetHost, targetPort)
 */
function openTunnelToPhone(device, targetHost, targetPort, callback) {
  if (!device.controlSocket || device.controlSocket.destroyed || !device.online) {
    return callback(new Error('Phone offline'));
  }

  const tunnelId = crypto.randomUUID();
  const timeout = setTimeout(() => {
    device.pendingTunnels.delete(tunnelId);
    callback(new Error('Phone tunnel timeout'));
  }, 10000);

  device.pendingTunnels.set(tunnelId, {
    callback: (err, sock) => {
      clearTimeout(timeout);
      callback(err, sock);
    },
  });

  const cmd = JSON.stringify({
    type: 'OPEN_TUNNEL',
    tunnelId,
    host: targetHost,
    port: targetPort,
  });

  try {
    device.controlSocket.write(cmd + '\n');
  } catch (e) {
    clearTimeout(timeout);
    device.pendingTunnels.delete(tunnelId);
    callback(e);
  }
}

/**
 * Pipes traffic between Dolphin{anty} and the Android Phone with strict kill-switch
 */
function pipeSocketsWithStats(browserSocket, phoneSocket, device) {
  device.activeSockets.add(browserSocket);
  device.activeSockets.add(phoneSocket);

  browserSocket.on('data', (chunk) => {
    device.bytesUp += chunk.length;
  });
  phoneSocket.on('data', (chunk) => {
    device.bytesDown += chunk.length;
  });

  browserSocket.pipe(phoneSocket);
  phoneSocket.pipe(browserSocket);

  const cleanup = () => {
    device.activeSockets.delete(browserSocket);
    device.activeSockets.delete(phoneSocket);
    if (!browserSocket.destroyed) browserSocket.destroy();
    if (!phoneSocket.destroyed) phoneSocket.destroy();
  };

  browserSocket.on('close', cleanup);
  browserSocket.on('error', cleanup);
  phoneSocket.on('close', cleanup);
  phoneSocket.on('error', cleanup);
}

/**
 * Forcefully kills all active browser connections if the phone disconnects (Zero Leak Kill-Switch)
 */
function killAllDeviceTraffic(device) {
  device.online = false;
  for (const [, pending] of device.pendingTunnels) {
    pending.callback(new Error('Phone disconnected'));
  }
  device.pendingTunnels.clear();

  for (const sock of device.activeSockets) {
    try {
      sock.destroy();
    } catch (_) {}
  }
  device.activeSockets.clear();
}

// Load saved permanent devices before starting control server
loadDatabase();

/**
 * Main Control Server (Port 9000): Handles Android Phone Registration & Reverse Data Channels
 */
const controlServer = net.createServer((socket) => {
  socket.setNoDelay(true);
  socket.setKeepAlive(true, 5000);

  let buffer = '';
  let isDataTunnel = false;
  let registeredDevice = null;

  const onData = (chunk) => {
    if (isDataTunnel) return;
    buffer += chunk.toString('utf8');

    let newlineIndex;
    while ((newlineIndex = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newlineIndex).trim();
      buffer = buffer.slice(newlineIndex + 1);
      if (!line) continue;

      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        socket.destroy();
        return;
      }

      // 1. Phone Control Registration (Permanent Credentials)
      if (msg.type === 'REGISTER') {
        const deviceId = msg.deviceId || generateRandomString(8);

        let device = devices.get(deviceId);
        if (!device) {
          const proxyPort = allocatePort(msg.preferredPort);
          device = {
            deviceId,
            proxyPort,
            username: 'gh_' + generateRandomString(5),
            password: generateRandomString(8),
            controlSocket: socket,
            online: true,
            bytesUp: 0,
            bytesDown: 0,
            pendingTunnels: new Map(),
            activeSockets: new Set(),
            proxyServer: null,
          };
          usedPorts.set(proxyPort, deviceId);
          device.proxyServer = startProxyServerForDevice(device);
          devices.set(deviceId, device);
          saveDatabase();
        } else {
          // Existing phone reconnected -> Keep exact same Port, Username & Password forever
          if (device.controlSocket && !device.controlSocket.destroyed && device.controlSocket !== socket) {
            try {
              device.controlSocket.destroy();
            } catch (_) {}
          }
          device.controlSocket = socket;
          device.online = true;
        }

        registeredDevice = device;
        console.log(
          `[Ghosti] Phone Online: ${device.deviceId} -> Port:${device.proxyPort} User:${device.username}`
        );

        socket.write(
          JSON.stringify({
            type: 'REGISTERED',
            deviceId: device.deviceId,
            proxyPort: device.proxyPort,
            username: device.username,
            password: device.password,
          }) + '\n'
        );
      }

      // 2. Phone Reverse Data Channel Ready for a Specific Browser Request
      else if (msg.type === 'TUNNEL_READY') {
        const { deviceId, tunnelId } = msg;
        const device = devices.get(deviceId);
        if (!device || !device.pendingTunnels.has(tunnelId)) {
          socket.destroy();
          return;
        }

        isDataTunnel = true;
        socket.removeListener('data', onData);

        const pending = device.pendingTunnels.get(tunnelId);
        device.pendingTunnels.delete(tunnelId);

        if (buffer.length > 0) {
          socket.unshift(Buffer.from(buffer, 'utf8'));
          buffer = '';
        }

        pending.callback(null, socket);
        return;
      }

      // 3. Phone Heartbeat / Ping
      else if (msg.type === 'PING') {
        if (registeredDevice) {
          socket.write(
            JSON.stringify({
              type: 'PONG',
              bytesUp: registeredDevice.bytesUp,
              bytesDown: registeredDevice.bytesDown,
            }) + '\n'
          );
        }
      }
    }
  };

  socket.on('data', onData);

  const handleDisconnect = () => {
    if (registeredDevice && registeredDevice.controlSocket === socket) {
      console.log(`[Ghosti] Phone Disconnected (Kill-Switch Activated): ${registeredDevice.deviceId}`);
      killAllDeviceTraffic(registeredDevice);
    }
  };

  socket.on('close', handleDisconnect);
  socket.on('error', handleDisconnect);
});

controlServer.listen(CONTROL_PORT, '0.0.0.0', () => {
  console.log('====================================================');
  console.log(' GHOSTI VPS RELAY SERVER RUNNING');
  console.log(` Control Port (For Android Apps) : ${CONTROL_PORT}`);
  console.log(` Proxy Ports (For Dolphin{anty}) : ${BASE_PROXY_PORT} - ${MAX_PROXY_PORT}`);
  console.log(' Permanent Storage (devices.json): ENABLED');
  console.log(' Strict Kill-Switch              : ENABLED (Zero IP Leak)');
  console.log('====================================================');
});