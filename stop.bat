@echo off
setlocal
set "ROOT=%~dp0"

where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: Windows PowerShell is required.
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\stop-project.ps1"
exit /b %ERRORLEVEL%
