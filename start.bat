@echo off
setlocal
set "ROOT=%~dp0"

rem Local POC-only administrator credentials. Do not reuse or deploy these in production.
set "RATELIMIT_ADMIN_USER=pocadmin"
set "RATELIMIT_ADMIN_PASSWORD=admin123"

where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: Windows PowerShell is required.
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\start-project.ps1"
exit /b %ERRORLEVEL%
