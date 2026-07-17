@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
  echo Nie znaleziono Python Launcher. Pobierz gotowy RoundSync-PC.exe z artefaktow GitHub Actions.
  pause
  exit /b 1
)
py -3 -m roundsync_pc
endlocal
