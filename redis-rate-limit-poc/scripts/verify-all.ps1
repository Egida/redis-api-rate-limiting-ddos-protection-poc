# Runs the POC end to end with step-level timing, hard timeouts and fail-fast.
#
#   powershell -File scripts\verify-all.ps1                 # build + both demos
#   powershell -File scripts\verify-all.ps1 -SkipBuild      # demos only
#   powershell -File scripts\verify-all.ps1 -KeepRunning    # leave the instance up afterwards
#
# Thresholds come from observed local averages; a step that exceeds its warn budget is reported as
# a CRITICAL ALERT, and a step that blows its hard limit aborts the run instead of idling.

param(
  [int]$Port = 8085,
  [switch]$SkipBuild,
  [switch]$SkipUnitTests,
  [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot is <project>/scripts, so one parent up is the project root.
$root = Split-Path -Parent $PSScriptRoot
$pocRoot = Split-Path -Parent $root
. (Join-Path $PSScriptRoot 'lib\timing.ps1')

$steps = @()
$runStarted = [datetime]::UtcNow
$redisName = 'ratelimit-poc-redis'
$appPid = $null

try {
  # 1 -- build ------------------------------------------------------------------
  # Maven caches every dependency after the first build, so a repeat run is fully offline and skips
  # remote metadata checks. Offline is attempted first and downgraded if anything is missing.
  $offline = $true
  if (-not $SkipBuild) {
    Write-Phase 'Build'
    $offline = $true
    $s = New-Step -Name 'mvn package (offline)' -WarnSeconds 15 -FatalSeconds 90
    $buildExit = Invoke-Native 'mvn' @('-B', '-q', '-o', '-DskipTests', 'package', '-f', (Join-Path $root 'pom.xml')) `
      -RedirectTo (Join-Path $root 'target\build.log')
    if ($buildExit -ne 0) {
      Complete-Step $s 'warn' 'offline build failed (dependency not cached); retrying online'
      $offline = $false
      $s2 = New-Step -Name 'mvn package (online)' -WarnSeconds 45 -FatalSeconds 240
      $buildExit = Invoke-Native 'mvn' @('-B', '-q', '-DskipTests', 'package', '-f', (Join-Path $root 'pom.xml')) `
        -RedirectTo (Join-Path $root 'target\build.log')
      $steps += Complete-Step $s2 $(if ($buildExit -eq 0) { 'ok' } else { 'fail' })
      if ($buildExit -ne 0) {
        Write-AlertFail 'maven package failed.'
        Write-LogPath (Join-Path $root 'target\build.log')
        exit 1
      }
    } else {
      $steps += Complete-Step $s 'ok'
    }
    Assert-StepBudget $s
  }

  if (-not $SkipUnitTests) {
    Write-Phase 'Tests'
    $s = New-Step -Name 'mvn test (JVM)' -WarnSeconds 45 -FatalSeconds 180 -LogPath (Join-Path $root 'target\surefire-reports')
    $offlineArgs = @('-B', 'test')
    if ($offline) { $offlineArgs = @('-B', '-o', 'test') }
    $mvnOk = (Invoke-Native 'mvn' ($offlineArgs + @('-f', (Join-Path $root 'pom.xml'))) `
        -RedirectTo (Join-Path $root 'target\test.log')) -eq 0
    $steps += Complete-Step $s $(if ($mvnOk) { 'ok' } else { 'fail' })
    if (-not $mvnOk) {
      Write-AlertFail 'mvn test failed.'
      Write-LogPath (Join-Path $root 'target\test.log')
      exit 1
    }
    Assert-StepBudget $s

    $s = New-Step -Name 'ng test (Angular)' -WarnSeconds 25 -FatalSeconds 120
    # npm resolves the local Angular CLI, so this runs the same suite a developer runs by hand.
    Push-Location (Join-Path $pocRoot 'frontend')
    try {
      $ngExit = Invoke-Native 'npm' @('test', '--silent', '--', '--no-watch') `
        -RedirectTo (Join-Path $root 'target\ng-test.log')
    } finally { Pop-Location }
    $steps += Complete-Step $s $(if ($ngExit -eq 0) { 'ok' } else { 'fail' })
    if ($ngExit -ne 0) {
      Write-AlertFail 'Angular tests failed.'
      Write-LogPath (Join-Path $root 'target\ng-test.log')
      exit 1
    }
    Assert-StepBudget $s

    $s = New-Step -Name 'ng build (production)' -WarnSeconds 25 -FatalSeconds 180
    Push-Location (Join-Path $pocRoot 'frontend')
    try {
      $ngBuildExit = Invoke-Native 'npm' @('run', 'build', '--silent') `
        -RedirectTo (Join-Path $root 'target\ng-build.log')
    } finally { Pop-Location }
    $steps += Complete-Step $s $(if ($ngBuildExit -eq 0) { 'ok' } else { 'fail' })
    if ($ngBuildExit -ne 0) {
      Write-AlertFail 'Angular build failed.'
      Write-LogPath (Join-Path $root 'target\ng-build.log')
      exit 1
    }
    Assert-StepBudget $s
  }

  # 2 -- redis -----------------------------------------------------------------
  Write-Phase 'Redis'
  $s = New-Step -Name 'redis container' -WarnSeconds 4 -FatalSeconds 30
  $exists = docker ps -a --filter "name=$redisName" -q
  $redisExit = if ($exists) {
    Invoke-Native 'docker' @('start', $redisName)
  } else {
    Invoke-Native 'docker' @('run', '-d', '--name', $redisName, '-p', '6379:6379', 'redis:7-alpine')
  }
  # The image is pre-pulled, so a slow step here means a Docker problem rather than a download.
  $steps += Complete-Step $s $(if ($redisExit -eq 0) { 'ok' } else { 'fail' })
  Assert-StepBudget $s
  Wait-ForRedisPing -Container $redisName -TimeoutSeconds 20

  # 3 -- application -----------------------------------------------------------
  Write-Phase 'Application startup'
  $s = New-Step -Name "app boot :$Port" -WarnSeconds 12 -FatalSeconds 60 -LogPath (Join-Path $root "target\instance-$Port.log")
  $jar = (Get-ChildItem (Join-Path $root 'target\redis-rate-limit-poc-*.jar') -Exclude '*sources*' |
    Select-Object -First 1 -ExpandProperty FullName)
  if (-not $jar) { Write-AlertFail 'jar not found; build first.'; exit 1 }
  $appPid = (Start-Process java -PassThru -WindowStyle Hidden `
      -RedirectStandardOutput (Join-Path $root "target\instance-$Port.log") `
      -ArgumentList @('-jar', "`"$jar`"", "--server.port=$Port")).Id
  $steps += Complete-Step $s 'ok'
  Assert-StepBudget $s

  # Poll the health endpoint instead of sleeping a guessed 18 seconds.
  Wait-ForHttp -Url "http://localhost:$Port/actuator/health" -TimeoutSeconds 60 -PollMs 250 `
    -LogPath (Join-Path $root "target\instance-$Port.log")

  # 4 -- API contracts ---------------------------------------------------------
  Write-Phase 'API contracts'
  $s = New-Step -Name 'api contract' -WarnSeconds 1 -FatalSeconds 15
  # ratelimit.requests has no series until the first decision, so seed one before asserting it.
  curl.exe -s -o NUL "http://localhost:$Port/api/products"
  # The console is a separate Angular app, so the jar serves API + actuator only.
  foreach ($asset in @('/actuator/health', '/api/poc/policies', '/actuator/metrics/ratelimit.requests')) {
    $code = curl.exe -s -o NUL -w '%{http_code}' "http://localhost:$Port$asset"
    if ($code -ne '200') {
      $steps += Complete-Step $s 'fail' "HTTP $code for $asset"
      Write-AlertFail "$asset returned HTTP $code"
      exit 1
    }
  }
  # No second copy of the UI may ship inside the jar.
  $rootCode = curl.exe -s -o NUL -w '%{http_code}' "http://localhost:$Port/index.html"
  if ($rootCode -ne '404') {
    $steps += Complete-Step $s 'fail' "HTTP $rootCode for /index.html"
    Write-AlertFail "the jar must not serve a static console (got HTTP $rootCode)"
    exit 1
  }
  $steps += Complete-Step $s 'ok'

  # 5 -- load demos ------------------------------------------------------------
  Write-Phase 'Load demos'
  foreach ($demo in @(
      @{ name = 'products (IP, 100/min)'; args = @('-Requests', '120', '-Limit', '100', '-Identity', 'IP') },
      @{ name = 'orders (USER, 30/min)'; args = @('-Method', 'POST', '-Path', '/api/orders', '-Requests', '45', '-Limit', '30', '-Identity', 'USER') }
    )) {
    # These legitimately wait up to one window for a fresh quota; that wait is the guarantee,
    # so its budget is a window length, not a startup average.
    $s = New-Step -Name ("load-demo " + $demo.name) -WarnSeconds 70 -FatalSeconds 150
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'load-demo.ps1') `
      -BaseUrl "http://localhost:$Port" -RedisContainer $redisName -Strict @($demo.args)
    $ok = $LASTEXITCODE -eq 0
    $steps += Complete-Step $s $(if ($ok) { 'ok' } else { 'fail' })
    if (-not $ok) {
      Write-AlertFail "load demo $($demo.name) was inconclusive."
      Write-AlertCritical 'A demo that reports INCONCLUSIVE crossed a rate-limit window. Re-run it; do not trust the totals.'
      exit 1
    }
    Assert-StepBudget $s
  }
}
finally {
  if ($appPid -and -not $KeepRunning) {
    Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
    Write-Host "`n  app on :$Port stopped (pid $appPid)" -ForegroundColor DarkGray
  }
  elseif ($appPid) {
    Write-Host "`n  app left running on :$Port (pid $appPid)" -ForegroundColor DarkGray
  }
  $total = [math]::Round(([datetime]::UtcNow - $runStarted).TotalSeconds, 2)
  if ($steps) { Write-TimingTable -Steps $steps -TotalSeconds $total }
}
