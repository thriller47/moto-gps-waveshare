@echo off
setlocal
powershell.exe -NoProfile -File "%~dp0moto-esp32.ps1" %*
exit /b %errorlevel%
