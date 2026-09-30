# Shared timing, polling and fail-fast helpers for the POC scripts.
#
# Replaces blind waits with dynamic polling, wraps every phase in a stopwatch, and enforces a hard
# threshold per step. Thresholds are set from observed local averages (see docs, "Script timing").

Set-StrictMode -Version Latest

function Invoke-Native {
  <#
    Runs a native command and captures its output without tripping $ErrorActionPreference.
    Windows PowerShell 5.1 raises a terminating error when a native process writes to stderr,
    which tools such as Maven and Mockito do routinely while still exiting 0. The real signal is
    $LASTEXITCODE, so stderr is merged into stdout and judged only by the exit code.
  #>
  param(
    [Parameter(Mandatory)][string]$FilePath,
    [string[]]$Arguments = @(),
    [string]$RedirectTo = $null
  )
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = & $FilePath @Arguments 2>&1
    $exit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($RedirectTo) {
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($RedirectTo, $text)
  } else {
    $output | ForEach-Object { Write-Host $_ }
  }
  return $exit
}

# --- Structured alert output -------------------------------------------------

function Write-AlertOk([string]$msg) { Write-Host "  [OK] $msg" -ForegroundColor DarkGray }
function Write-AlertWarn([string]$msg) { Write-Host "  $msg" -ForegroundColor Yellow }
function Write-AlertCritical([string]$msg) { Write-Host "  ⚠️ [CRITICAL ALERT] $msg" -ForegroundColor Yellow }
function Write-AlertFatal([string]$msg) { Write-Host "  ❌ [FATAL TIMEOUT] $msg" -ForegroundColor Red }
function Write-AlertFail([string]$msg) { Write-Host "  ❌ [FAIL] $msg" -ForegroundColor Red }
function Write-Phase([string]$name) { Write-Host "`n▶ $name" -ForegroundColor Cyan }
function Write-LogPath([string]$path) { Write-Host "  log: $path" -ForegroundColor DarkGray }

# --- Step timer --------------------------------------------------------------

function New-Step {
  param([string]$Name, [double]$WarnSeconds, [double]$FatalSeconds, [string]$LogPath = $null)
  return [pscustomobject]@{
    Name         = $Name
    WarnSeconds  = $WarnSeconds
    FatalSeconds = $FatalSeconds
    LogPath      = $LogPath
    Started      = [datetime]::UtcNow
    Elapsed      = $null
    Outcome      = $null
    Detail       = $null
  }
}

function Complete-Step {
  param([object]$Step, [string]$Outcome = 'ok', [string]$Detail = $null)
  $Step.Elapsed = [math]::Round(([datetime]::UtcNow - $Step.Started).TotalSeconds, 2)
  $Step.Outcome = $Outcome
  $Step.Detail = $Detail
  $line = ("{0,-34} {1,7:N2}s  (warn>{2}s fail>{3}s)" -f $Step.Name, $Step.Elapsed, $Step.WarnSeconds, $Step.FatalSeconds)
  switch ($Outcome) {
    'ok' {
      if ($Step.Elapsed -gt $Step.WarnSeconds) {
        Write-AlertCritical ("{0} succeeded but took {1:N2}s, over its {2}s target. {3}" -f $Step.Name, $Step.Elapsed, $Step.WarnSeconds, $line)
      } else {
        Write-Host ("  [OK] {0}" -f $line) -ForegroundColor DarkGray
      }
    }
    'fail' { Write-AlertFail ("{0} {1}" -f $line, $Detail) }
    default { Write-AlertWarn ("{0} {1}" -f $line, $Detail) }
  }
  return $Step
}

function Assert-StepBudget {
  param([object]$Step)
  if ($Step.Elapsed -gt $Step.FatalSeconds) {
    Write-AlertFatal ("{0} used {1:N2}s, past its {2}s hard limit. Aborting rather than waiting longer." -f $Step.Name, $Step.Elapsed, $Step.FatalSeconds)
    if ($Step.LogPath) { Write-LogPath $Step.LogPath }
    exit 2
  }
}

# --- Dynamic polling (replaces fixed sleeps) ---------------------------------

function Wait-For {
  param(
    [Parameter(Mandatory)][scriptblock]$Probe,
    [Parameter(Mandatory)][string]$What,
    [double]$TimeoutSeconds = 30,
    [double]$PollMs = 250,
    [string]$LogPath = $null
  )
  $started = [datetime]::UtcNow
  $deadline = $started.AddSeconds($TimeoutSeconds)
  $lastReason = ''
  while ([datetime]::UtcNow -lt $deadline) {
    $result = & $Probe
    # A probe must return $true or a status string. Anything else means the probe is buggy;
    # report the type rather than leaking the value into the console.
    if ($result -ne $true -and $result -isnot [string]) {
      $result = 'probe returned ' + $result.GetType().Name + ' instead of $true'
    }
    if ($result -eq $true) {
      $elapsed = [math]::Round(([datetime]::UtcNow - $started).TotalSeconds, 2)
      if ($elapsed -gt ($TimeoutSeconds / 2)) {
        Write-AlertCritical ("{0} ready but only after {1:N2}s of a {2}s budget." -f $What, $elapsed, $TimeoutSeconds)
      } else {
        Write-AlertOk ("{0} ready in {1:N2}s" -f $What, $elapsed)
      }
      return $true
    }
    if ($result -is [string] -and $result -ne $lastReason) {
      $lastReason = $result
      Write-AlertWarn ("{0}: {1}" -f $What, $result)
    }
    Start-Sleep -Milliseconds $PollMs
  }
  Write-AlertFatal ("{0} never became ready within {1}s. Last reason: {2}" -f $What, $TimeoutSeconds, $lastReason)
  if ($LogPath) { Write-LogPath $LogPath }
  exit 2
}

function Wait-ForHttp {
  param([string]$Url, [double]$TimeoutSeconds = 30, [int]$PollMs = 250, [string]$LogPath = $null)
  return Wait-For -What "HTTP $Url" -TimeoutSeconds $TimeoutSeconds -PollMs $PollMs -LogPath $LogPath -Probe {
    try {
      $r = Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
      if ($r.StatusCode -eq 200) { return $true }
      return "HTTP $($r.StatusCode)"
      } catch {
        # WebException carries the real status. StrictMode makes a bare .StatusCode access throw when
        # there is no response, so probe the property first.
        $response = $_.Exception.Response
        if ($null -ne $response -and $response.PSObject.Properties.Name -contains 'StatusCode') {
          return "HTTP $($response.StatusCode)"
        }
        return $_.Exception.GetType().Name
      }
  }
}

function Wait-ForRedisPing {
  param([string]$Container, [double]$TimeoutSeconds = 20, [string]$LogPath = $null)
  return Wait-For -What "redis container '$Container'" -TimeoutSeconds $TimeoutSeconds -LogPath $LogPath -Probe {
    $out = docker exec $Container redis-cli ping 2>&1
    if ($out -match 'PONG') { return $true }
    return "redis-cli ping -> $out"
  }
}

function Get-FreePorts {
  param([int]$From = 18081, [int]$Count = 2, [int]$Span = 60)
  $ports = @()
  foreach ($c in $From..($From + $Span)) {
    if (-not (Get-NetTCPConnection -State Listen -LocalPort $c -ErrorAction SilentlyContinue)) {
      $ports += $c
      if ($ports.Count -eq $Count) { break }
    }
  }
  if ($ports.Count -lt $Count) {
    Write-AlertFatal "only found $($ports.Count) free port(s) scanning $From..$($From+$Span); need $Count."
    exit 2
  }
  return $ports
}

# --- Timing table ------------------------------------------------------------

function Write-TimingTable {
  param([object[]]$Steps, [double]$TotalSeconds)
  Write-Host '' -ForegroundColor DarkGray
  Write-Host ('-- timing ' + ('-' * 50)) -ForegroundColor DarkGray
  foreach ($s in $Steps) {
    $colour = switch ($s.Outcome) {
      'fail' { 'Red' } 'warn' { 'Yellow' } default { 'DarkGray' }
    }
    Write-Host ("  {0,-34} {1,7:N2}s / {2,6:N2}s budget  {3}" -f $s.Name, $s.Elapsed, $s.FatalSeconds, $s.Outcome) -ForegroundColor $colour
  }
  Write-Host ("  {0,-34} {1,7:N2}s" -f 'TOTAL', $TotalSeconds) -ForegroundColor Cyan
  Write-Host ('-' * 60) -ForegroundColor DarkGray
}
