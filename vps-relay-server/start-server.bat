@echo off
title Ghosti VPS Relay Server
echo Configuring Windows Firewall ports for Ghosti...
netsh advfirewall firewall delete rule name="Ghosti Control Port" >nul 2>&1
netsh advfirewall firewall add rule name="Ghosti Control Port" dir=in action=allow protocol=TCP localport=9000 >nul 2>&1

netsh advfirewall firewall delete rule name="Ghosti Proxy Ports" >nul 2>&1
netsh advfirewall firewall add rule name="Ghosti Proxy Ports" dir=in action=allow protocol=TCP localport=10001-10500 >nul 2>&1

echo Starting Ghosti Server...
:loop
node server.js
echo Server crashed or stopped. Restarting in 3 seconds...
timeout /t 3
goto loop