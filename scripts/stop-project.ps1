$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $root '.run-state'
$backendDir = Join-Path $root 'redis-rate-limit-poc'
$frontendDir = Join-Path $root 'frontend'
$errors = 0

function Stop-TrackedProcess([string] $name, [string] $expectedProcessName) {
    $recordPath = Join-Path $stateDir "$name.json"
    if (-not (Test-Path -LiteralPath $recordPath)) {
        $windowTitle = if ($name -eq 'backend') { 'RateGuard API :8080*' } else { 'RateGuard Console :4200*' }
        & taskkill.exe /FI "WINDOWTITLE eq $windowTitle" /T /F *> $null
        $legacyWindowStopped = $LASTEXITCODE -eq 0
        if ($legacyWindowStopped) {
            Write-Host "  Stopped legacy $name window '$windowTitle'." -ForegroundColor Green
        }

        $port = if ($name -eq 'backend') { 8080 } else { 4200 }
        $owners = @()
        for ($i = 0; $i -lt 10; $i++) {
            $owners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique)
            if ($owners.Count -eq 0) { break }
            Start-Sleep -Seconds 1
        }
        if (-not $owners) {
            if (-not $legacyWindowStopped) {
                Write-Host "  ${name}: no process started by the current or previous start.bat was found."
            }
            return
        }

        foreach ($ownerPid in $owners) {
            try {
                $owner = Get-Process -Id $ownerPid -ErrorAction Stop
            } catch {
                Write-Host "  Port $port is owned by PID $ownerPid, but its process details are unavailable; leaving it untouched." -ForegroundColor Yellow
                continue
            }
            if ($owner.ProcessName -ne $expectedProcessName) {
                Write-Host "  Port $port is owned by $($owner.ProcessName) PID $ownerPid, not the expected $expectedProcessName process; leaving it untouched." -ForegroundColor Yellow
                continue
            }

            Write-Host "  Untracked $expectedProcessName PID $ownerPid owns project port $port." -ForegroundColor Yellow
            Write-Host "  Executable: $($owner.Path)"
            $answer = Read-Host "  Stop this process tree? Enter Y only if this is the RateGuard service"
            if ($answer -match '^(y|yes)$') {
                & taskkill.exe /PID $ownerPid /T /F *> $null
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "  Stopped confirmed $name process PID $ownerPid." -ForegroundColor Green
                } else {
                    Write-Host "  Could not stop PID $ownerPid." -ForegroundColor Red
                    $script:errors++
                }
            } else {
                Write-Host "  Left PID $ownerPid running."
            }
        }
        return
    }

    try {
        $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
        $process = Get-Process -Id ([int]$record.pid) -ErrorAction Stop
        $actualStart = $process.StartTime.ToUniversalTime()
        $recordedStart = [DateTime]::Parse($record.startTimeUtc).ToUniversalTime()
        if ($process.ProcessName -ne $expectedProcessName -or
            [Math]::Abs(($actualStart - $recordedStart).TotalSeconds) -gt 2) {
            Write-Host "  ${name}: recorded PID no longer identifies the process this project started; leaving it alone." -ForegroundColor Yellow
            Remove-Item -LiteralPath $recordPath -Force
            return
        }

        & taskkill.exe /PID $process.Id /T /F *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  Stopped $name (PID $($process.Id))." -ForegroundColor Green
        } else {
            Write-Host "  Could not stop $name (PID $($process.Id))." -ForegroundColor Red
            $script:errors++
        }
    } catch {
        Write-Host "  $name is already stopped."
    } finally {
        Remove-Item -LiteralPath $recordPath -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'RateGuard - stopping project services' -ForegroundColor Cyan
Stop-TrackedProcess 'frontend' 'node'
Stop-TrackedProcess 'backend' 'java'

$redisState = Join-Path $stateDir 'redis.txt'
if (Test-Path -LiteralPath $redisState) {
    $redisName = (Get-Content -LiteralPath $redisState -Raw).Trim()
    if ($redisName -match '^ratelimit-(redis|poc-redis)$') {
        docker inspect $redisName *> $null
        if ($LASTEXITCODE -eq 0) {
            $running = docker inspect -f '{{.State.Running}}' $redisName
            if ($LASTEXITCODE -eq 0 -and $running -eq 'true') {
                docker stop $redisName *> $null
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "  Stopped Redis container '$redisName'; container and stored data were retained." -ForegroundColor Green
                } else {
                    Write-Host "  Could not stop Redis container '$redisName'." -ForegroundColor Red
                    $errors++
                }
            } else {
                Write-Host "  Redis container '$redisName' is already stopped."
            }
        } else {
            Write-Host "  Redis container '$redisName' no longer exists."
        }
    } else {
        Write-Host '  Redis state file had an unexpected container name; left Docker containers untouched.' -ForegroundColor Yellow
        $errors++
    }
    Remove-Item -LiteralPath $redisState -Force -ErrorAction SilentlyContinue
} else {
    # Backward compatibility for the previous start.bat, which did not write ownership records.
    # These two names are specific to this project; stopping retains the container and its data.
    foreach ($candidate in @('ratelimit-redis', 'ratelimit-poc-redis')) {
        docker inspect $candidate *> $null
        if ($LASTEXITCODE -eq 0) {
            $running = docker inspect -f '{{.State.Running}}' $candidate
            if ($LASTEXITCODE -eq 0 -and $running -eq 'true') {
                docker stop $candidate *> $null
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "  Stopped legacy project Redis '$candidate'; data retained." -ForegroundColor Green
                } else {
                    Write-Host "  Could not stop Redis container '$candidate'." -ForegroundColor Red
                    $errors++
                }
            }
        }
    }
}

if (Test-Path -LiteralPath $stateDir) {
    $remaining = @(Get-ChildItem -LiteralPath $stateDir -Force -ErrorAction SilentlyContinue)
    if ($remaining.Count -eq 0) { Remove-Item -LiteralPath $stateDir -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($errors -eq 0) {
    Write-Host 'RateGuard stopped. Redis data was not deleted.' -ForegroundColor Green
    exit 0
}
Write-Host 'RateGuard stop completed with errors; review the messages above.' -ForegroundColor Yellow
exit 1
