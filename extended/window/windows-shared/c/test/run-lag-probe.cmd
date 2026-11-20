@echo off
rem Runs the windows-shared lag probe with the prebuilt probe beside this file. No compiler
rem is needed. It moves a window and the pointer for about three minutes: leave the mouse
rem and keyboard alone. The result is lag-probe-summary.txt in this folder.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-lag-probe.ps1" %*
set result=%errorlevel%
echo.
echo Done. Send lag-probe-summary.txt from this folder.
pause
exit /b %result%
