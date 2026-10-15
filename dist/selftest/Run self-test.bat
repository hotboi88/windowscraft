@echo off
title WindowsCraft self-test
tasklist /FI "IMAGENAME eq wc-vdisplay.exe" | find /I "wc-vdisplay.exe" >nul
if not errorlevel 1 (
  echo Minecraft is still running with in-game monitors. Close Minecraft first, then run this again.
  pause
  exit /b 1
)
rem (the desktop you see: often OneDrive's, not %USERPROFILE%\Desktop)
set DESK=
for /f "usebackq delims=" %%d in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('Desktop')"`) do set "DESK=%%d"
if not defined DESK set "DESK=%USERPROFILE%\Desktop"
set "OUT=%DESK%\WindowsCraft self-test"
if not exist "%OUT%" mkdir "%OUT%"
echo Checking in-game monitors on this PC. Your screens will blink and the mouse will move.
echo Please don't touch the mouse or keyboard for about two minutes.
echo.
"%~dp0wc-selftest.exe" --helpers "%~dp0." --rounds 1 --report "%OUT%\report.txt"
echo.
echo Done. The report is in "%OUT%" on your desktop.
pause
