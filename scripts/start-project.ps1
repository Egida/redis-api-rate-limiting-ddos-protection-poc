$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'redis-rate-limit-poc'
$frontendDir = Join-Path $root 'frontend'
$stateDir = Join-Path $root '.run-state'
$redisPort = 6379
$backendPort = 8080
$frontendPort = 4200
$redisName = $null

function Test-HttpEndpoint([string] $uri, [int] $expectedStatus = 200) {
    try {
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -eq $expectedStatus
    } catch {
        return $false
    }
}

function Test-PortListening([int] $port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1)
}

function Save-ProcessRecord([string] $name, [System.Diagnostics.Process] $process) {
    $record = @{
        pid = $process.Id
        processName = $process.ProcessName
        startTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stateDir "$name.json") -Encoding UTF8
}

function Wait-ForHttp([string] $uri, [int] $seconds, [string] $serviceName) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-HttpEndpoint $uri) { return $true }
        Start-Sleep -Seconds 1
    }
    Write-Host "ERROR: $serviceName did not become ready at $uri within $seconds seconds." -ForegroundColor Red
    return $false
}

Write-Host ''
Write-Host 'RateGuard - starting Redis, backend, and Angular console' -ForegroundColor Cyan
Write-Host "Project: $root"

if (Test-HttpEndpoint "http://localhost:$backendPort/actuator/health") {
    Write-Host "Backend is already running on port $backendPort." -ForegroundColor Yellow
    Write-Host 'Run stop.bat first, then start.bat to rebuild and launch the current backend source.'
    exit 2
}
if (Test-PortListening $backendPort) {
    Write-Host "Port $backendPort is still closing; waiting up to 15 seconds for it to be released..." -ForegroundColor Yellow
    for ($i = 0; $i -lt 15 -and (Test-PortListening $backendPort); $i++) {
        Start-Sleep -Seconds 1
    }
    if (Test-PortListening $backendPort) {
        if (Test-HttpEndpoint "http://127.0.0.1:$backendPort/actuator/health") {
            Write-Host "Backend is already healthy on port $backendPort." -ForegroundColor Yellow
            Write-Host 'Run stop.bat first, then start.bat to rebuild and launch the current backend source.'
            exit 2
        }
        $owners = Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
        Write-Host "ERROR: port $backendPort is still occupied after waiting." -ForegroundColor Red
        foreach ($ownerPid in $owners) {
            try {
                $owner = Get-Process -Id $ownerPid -ErrorAction Stop
                Write-Host "  Listener PID $ownerPid ($($owner.ProcessName)); leaving it untouched."
            } catch {
                Write-Host "  Listener PID $ownerPid; process details are unavailable."
            }
        }
        Write-Host 'Close that application yourself; this script will not stop an unowned process.'
        exit 2
    }
}
$frontendAlreadyRunning = $false
if (Test-HttpEndpoint "http://localhost:$frontendPort/") {
    $frontendOwners = Get-NetTCPConnection -LocalPort $frontendPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    $matchedFrontend = $false
    foreach ($ownerPid in $frontendOwners) {
        $ownerDetails = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
        if ($ownerDetails.Name -eq 'node.exe' -and
            $ownerDetails.CommandLine.IndexOf($frontendDir, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $ownerDetails.CommandLine -match 'ng\.js.+serve') {
            $frontendProcess = Get-Process -Id $ownerPid -ErrorAction Stop
            Save-ProcessRecord 'frontend' $frontendProcess
            $matchedFrontend = $true
            $frontendAlreadyRunning = $true
            Write-Host "Angular console already running from this project (PID $ownerPid); reusing it." -ForegroundColor Green
        }
    }
    if (-not $matchedFrontend) {
        Write-Host "ERROR: port $frontendPort serves a site, but its process could not be verified as this project's Angular console." -ForegroundColor Red
        Write-Host 'Close it yourself; this script will not attach to or stop an unknown web server.'
        exit 2
    }
} elseif (Test-PortListening $frontendPort) {
    Write-Host "Port $frontendPort is still closing; waiting up to 10 seconds..." -ForegroundColor Yellow
    for ($i = 0; $i -lt 10 -and (Test-PortListening $frontendPort); $i++) { Start-Sleep -Seconds 1 }
    if (Test-PortListening $frontendPort) {
        $owners = Get-NetTCPConnection -LocalPort $frontendPort -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
        Write-Host "ERROR: port $frontendPort is still occupied." -ForegroundColor Red
        foreach ($ownerPid in $owners) {
            try {
                $owner = Get-Process -Id $ownerPid -ErrorAction Stop
                Write-Host "  Listener PID $ownerPid ($($owner.ProcessName)); leaving it untouched."
            } catch {
                Write-Host "  Listener PID $ownerPid; process details are unavailable."
            }
        }
        Write-Host 'Close that application yourself; this script will not stop an unowned process.'
        exit 2
    }
}

foreach ($tool in @('java', 'mvn', 'node', 'npm', 'docker')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' was not found on PATH. Install it and reopen this terminal."
    }
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running or is not accessible.' }

if ([string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_USER) -or
    [string]::IsNullOrWhiteSpace($env:RATELIMIT_ADMIN_PASSWORD)) {
    throw 'Set RATELIMIT_ADMIN_USER and RATELIMIT_ADMIN_PASSWORD in the environment before starting; the admin API has no default credentials.'
}

New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $backendDir 'logs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $frontendDir 'logs') -Force | Out-Null
Remove-Item -LiteralPath (Join-Path $stateDir 'backend.json'), (Join-Path $stateDir 'frontend.json'), (Join-Path $stateDir 'redis.txt') -Force -ErrorAction SilentlyContinue

Write-Host '[1/4] Redis'
$containerNames = @('ratelimit-redis', 'ratelimit-poc-redis')
foreach ($candidate in $containerNames) {
    docker inspect $candidate *> $null
    if ($LASTEXITCODE -eq 0) {
        $redisName = $candidate
        break
    }
}

if (-not $redisName) {
    Push-Location $backendDir
    try {
        docker compose up -d redis
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose could not start Redis.' }
    } finally {
        Pop-Location
    }
    $redisName = 'ratelimit-poc-redis'
} else {
    $running = docker inspect -f '{{.State.Running}}' $redisName
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect Redis container '$redisName'." }
    if ($running -ne 'true') {
        docker start $redisName *> $null
        if ($LASTEXITCODE -ne 0) { throw "Could not start Redis container '$redisName'." }
    }
}
Set-Content -LiteralPath (Join-Path $stateDir 'redis.txt') -Value $redisName -Encoding ASCII
$redisReady = $false
for ($i = 0; $i -lt 30; $i++) {
    $ping = docker exec $redisName redis-cli PING 2>$null
    if ($LASTEXITCODE -eq 0 -and $ping -match 'PONG') { $redisReady = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $redisReady) { throw "Redis container '$redisName' did not answer PING." }
Write-Host "  Redis ready in container '$redisName'; existing data/volume preserved." -ForegroundColor Green

Write-Host '[2/4] Build backend from current source'
Push-Location $backendDir
try {
    & mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed. Review the output above.' }
} finally {
    Pop-Location
}
$jar = Get-ChildItem -LiteralPath (Join-Path $backendDir 'target') -Filter 'redis-rate-limit-poc-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw 'Maven completed but no application JAR was found in redis-rate-limit-poc/target.' }

Write-Host '[3/4] Start Spring Boot backend'
$backendProcess = Start-Process -FilePath (Get-Command java.exe).Source `
    -ArgumentList @('-jar', ('"{0}"' -f $jar.FullName)) `
    -WorkingDirectory $backendDir `
    -RedirectStandardOutput (Join-Path $backendDir 'logs/backend.log') `
    -RedirectStandardError (Join-Path $backendDir 'logs/backend-err.log') `
    -WindowStyle Hidden -PassThru
Save-ProcessRecord 'backend' $backendProcess
if (-not (Wait-ForHttp "http://localhost:$backendPort/actuator/health" 60 'Spring Boot backend')) {
    Get-Content -LiteralPath (Join-Path $backendDir 'logs/backend.log') -Tail 30 -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "  Backend healthy at http://localhost:$backendPort" -ForegroundColor Green

Write-Host '[4/4] Start Angular console'
if (-not $frontendAlreadyRunning -and -not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules'))) {
    Push-Location $frontendDir
    try {
        if (Test-Path -LiteralPath (Join-Path $frontendDir 'package-lock.json')) {
            & npm ci
        } else {
            & npm install
        }
        if ($LASTEXITCODE -ne 0) { throw 'Installing frontend dependencies failed.' }
    } finally {
        Pop-Location
    }
}
if (-not $frontendAlreadyRunning) {
    $ngCli = Join-Path $frontendDir 'node_modules/@angular/cli/bin/ng.js'
    if (-not (Test-Path -LiteralPath $ngCli)) { throw "Angular CLI entry point is missing: $ngCli" }
    $nodeExe = (Get-Command node.exe).Source
    $frontendProcess = Start-Process -FilePath $nodeExe `
        -ArgumentList @(('"{0}"' -f $ngCli), 'serve', '--host', '127.0.0.1', '--proxy-config', 'proxy.conf.json') `
        -WorkingDirectory $frontendDir `
        -RedirectStandardOutput (Join-Path $frontendDir 'logs/console.log') `
        -RedirectStandardError (Join-Path $frontendDir 'logs/console-err.log') `
        -WindowStyle Hidden -PassThru
    Save-ProcessRecord 'frontend' $frontendProcess
    if (-not (Wait-ForHttp "http://localhost:$frontendPort/" 90 'Angular console')) {
        Get-Content -LiteralPath (Join-Path $frontendDir 'logs/console.log') -Tail 30 -ErrorAction SilentlyContinue
        exit 1
    }
}

Write-Host ''
Write-Host 'RateGuard is running.' -ForegroundColor Green
Write-Host "  Console: http://localhost:$frontendPort/"
Write-Host "  Backend: http://localhost:$backendPort/"
Write-Host "  Health:  http://localhost:$backendPort/actuator/health"
Write-Host "  Redis:   $redisName (port $redisPort)"
Write-Host 'Run stop.bat to stop only the services started/registered by this project.'
Start-Process "http://localhost:$frontendPort/" | Out-Null
