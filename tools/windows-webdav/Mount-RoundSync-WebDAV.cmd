@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "SCRIPT=%~dp0Mount-RoundSync-WebDAV.ps1"

if not exist "%SCRIPT%" (
    echo [ERROR] Nie znaleziono pliku:
    echo         "%SCRIPT%"
    pause
    exit /b 2
)

fltmc >nul 2>&1
if errorlevel 1 (
    echo Żądanie uprawnień administratora...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
        "Start-Process -FilePath '%~f0' -Verb RunAs -ArgumentList @('%*')"
    exit /b %errorlevel%
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "EXIT_CODE=%errorlevel%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo Montowanie zakończone błędem. Kod: %EXIT_CODE%
)
pause
exit /b %EXIT_CODE%
