$ErrorActionPreference = "Stop"

$portConn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
if ($portConn) {
    $pid = $portConn[0].OwningProcess
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pid" -ErrorAction SilentlyContinue
    $cmd = if ($proc.CommandLine) { $proc.CommandLine } else { '' }
    Write-Host "Port 8080 is already occupied (PID: $pid). Refusing to stop or replace it."
    exit 1
} else {
    Write-Host "No existing process on port 8080."
}

if ([string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_USER) -or
    [string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_PASSWORD)) {
    throw "Set RATELIMIT_ADMIN_USER and RATELIMIT_ADMIN_PASSWORD in the current shell before starting the backend."
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$jar = (Get-Item (Join-Path $projectRoot "target\redis-rate-limit-poc-*.jar")).FullName
Write-Host "Starting backend jar: $jar"

$backendDir = $projectRoot.Path
Start-Process -FilePath "java" -ArgumentList "-jar", "`"$jar`"" -WorkingDirectory $backendDir -RedirectStandardOutput "$backendDir\logs\backend.log" -RedirectStandardError "$backendDir\logs\backend-err.log"

Write-Host "Waiting for /actuator/health to report UP..."
$up = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $res = Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 2
        if ($res.status -eq "UP") {
            $up = $true
            break
        }
    } catch {}
}

if ($up) {
    Write-Host "SUCCESS: Backend is UP and healthy on port 8080 with admin credentials configured!"
} else {
    Write-Host "ERROR: Backend failed to become UP within 30s. Checking logs:"
    if (Test-Path "$backendDir\logs\backend.log") {
        Get-Content "$backendDir\logs\backend.log" -Tail 20
    }
}
