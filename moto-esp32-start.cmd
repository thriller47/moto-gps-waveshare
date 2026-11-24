@echo off
setlocal
set "IDF_TOOLS_PATH=C:\Espressif"
set "PATH=C:\Espressif\python_env\idf5.5_py3.11_env\Scripts;%PATH%"
if not exist "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat" (
    echo ESP-IDF export.bat not found. Check installation paths.
    exit /b 1
)
call "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat"
if errorlevel 1 exit /b 1
call "%~dp0moto-esp32.cmd" %*
exit /b %errorlevel%
