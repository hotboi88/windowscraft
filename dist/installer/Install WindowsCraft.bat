@echo off
rem Double-click me. Installs the WindowsCraft mod into your Cobbleverse instance(s).
title WindowsCraft installer
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windowscraft.ps1"
echo.
pause
