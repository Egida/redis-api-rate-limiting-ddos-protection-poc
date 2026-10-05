# Distributed concurrency proof: one app-wide cap enforced across two real JVMs.
#
# Creates a GLOBAL concurrency policy (4 permits, 30s leases) on instance A, then fires
# 10 parallel slow requests split across both instances. Exactly 4 must succeed and 6 must
# get 429: the permits live in shared Redis, so the cap holds globally, not per process.
# A final solo request proves every permit was released (no leak). The policy is deleted
# before exit.
#
# Usage:
#   powershell -File scripts\concurrency-demo.ps1 -AdminUser <name> -AdminPassword <secret>
param(
  [Parameter(Mandatory = $true)][string]$AdminUser,
  [Parameter(Mandatory = $true)][string]$AdminPassword,
  [int]$FirstPort = 0,
  [int]$Permits = 4,
  [int]$Parallel = 12,
  [int]$WorkMs = 2000
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$script:failures = @()
function Assert($condition, $label) {
  if ($condition) { Write-Output ("  PASS  {0}" -f $label) }
  else { $script:failures += $label; Write-Output ("  FAIL  {0}" -f $label) }
}

Write-Output "=== [1/5] build ==="
# Build in a temp copy of the module: a running backend may hold target/*.jar open, and Windows
# will not let Maven rename a file a JVM has open. The copy keeps the live tree untouched.
$buildRoot = Join-Path ([System.IO.Path]::GetTempPath()) "ratelimit-demo-build"
if (Test-Path $buildRoot) { Remove-Item -Recurse -Force $buildRoot }
New-Item -ItemType Directory -Path $buildRoot | Out-Null
Copy-Item "$root\src" "$buildRoot\src" -Recurse
Copy-Item "$root\pom.xml" "$buildRoot\pom.xml"
Push-Location $buildRoot
try {
  & mvn -B -o "-Dmaven.test.skip=true" package 2>&1 | Select-String -Pattern 'BUILD' | ForEach-Object { Write-Output ("  {0}" -f $_.Line.Trim()) }
} finally { Pop-Location }
$jar = Get-ChildItem "$buildRoot\target\redis-rate-limit-poc-*.jar" -Exclude "*sources*", "*.original" |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $jar) { throw "no jar produced" }

Write-Output "=== [2/5] redis + two JVMs ==="
$redisName = "ratelimit-redis"
if ((docker ps --filter "name=$redisName" --format "{{.Names}}" 2>$null) -ne $redisName) { throw "container $redisName is not running" }
docker exec $redisName redis-cli PING | ForEach-Object { Write-Output "  PING -> $_" }

$scanFrom = if ($FirstPort -eq 0) { 18081 } else { $FirstPort }
$ports = @()
foreach ($candidate in $scanFrom..($scanFrom + 40)) {
  $busy = Get-NetTCPConnection -State Listen -LocalPort $candidate -ErrorAction SilentlyContinue
  if (-not $busy) { $ports += $candidate; if ($ports.Count -eq 2) { break } }
}
if ($ports.Count -lt 2) { throw "no two free ports found scanning from $scanFrom" }
$portA, $portB = $ports[0], $ports[1]

$env:RATELIMIT_ADMIN_USER = $AdminUser
$env:RATELIMIT_ADMIN_PASSWORD = $AdminPassword
$pids = @()
foreach ($port in $ports) {
  $log = "$buildRoot\target\concurrency-$port.log"
  $p = Start-Process java -PassThru -WindowStyle Hidden -RedirectStandardOutput $log `
    -ArgumentList @("-jar", "`"$jar`"", "--server.port=$port")
  $pids += $p.Id
  Write-Output ("  started :{0} (pid {1})" -f $port, $p.Id)
}
Remove-Item Env:\RATELIMIT_ADMIN_PASSWORD -ErrorAction SilentlyContinue

$adminAuth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$AdminUser`:$AdminPassword"))
$H = @{ Authorization = $adminAuth; "Content-Type" = "application/json" }

try {
  foreach ($port in $ports) {
    $ok = $false
    foreach ($_ in 1..40) {
      Start-Sleep -Seconds 1
      try { Invoke-WebRequest "http://localhost:$port/actuator/health" -UseBasicParsing -ErrorAction Stop | Out-Null; $ok = $true; break } catch {}
    }
    if (-not $ok) { throw "instance :$port never became healthy" }
  }
  Write-Output "  both instances healthy"

  Write-Output "=== [3/5] create the global concurrency policy on A ==="
  $policy = '{"id":"work-conc","name":"work concurrency","method":"GET","path":"/api/work","algorithm":"CONCURRENCY_LIMIT","scope":"GLOBAL","maxConcurrent":' + $Permits + ',"leaseDuration":"PT30S","enabled":true}'
  $saved = Invoke-RestMethod "http://localhost:$portA/api/admin/rate-limit/policies" -Method Post -Headers $H -Body $policy
  Assert ($saved.maxConcurrent -eq $Permits) ("policy work-conc created with $Permits permits")

  Write-Output "=== [4/5] $Parallel near-simultaneous slow requests across both JVMs ==="
  # Runspaces, not Start-Job: jobs stagger launches over seconds (early permits release before late
  # jobs arrive, which proves nothing). Runspaces fire within milliseconds of each other.
  $pool = [runspacefactory]::CreateRunspacePool(1, $Parallel)
  $pool.Open()
  $invocations = @()
  $burstStart = Get-Date
  for ($i = 1; $i -le $Parallel; $i++) {
    $port = if ($i % 2 -eq 0) { $portA } else { $portB }
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript({
      param($port, $ms)
      try {
        $r = Invoke-WebRequest "http://localhost:$port/api/work?ms=$ms" -UseBasicParsing -TimeoutSec 30 -ErrorAction Stop
        @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
      } catch {
        @{ status = [int]$_.Exception.Response.StatusCode; body = $null }
      }
    }).AddArgument($port).AddArgument($WorkMs)
    $invocations += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
  }
  $results = foreach ($inv in $invocations) {
    try { $inv.PS.EndInvoke($inv.Handle) } finally { $inv.PS.Dispose() }
  }
  $pool.Close()
  $burstMs = ((Get-Date) - $burstStart).TotalMilliseconds
  $ok200 = ($results | Where-Object { $_.status -eq 200 }).Count
  $r429 = ($results | Where-Object { $_.status -eq 429 }).Count
  Assert ($ok200 -eq $Permits) ("exactly $Permits succeeded globally (got $ok200)")
  Assert ($r429 -eq ($Parallel - $Permits)) ("the rest got 429 (got $r429)")
  # Overlap guard: sequential execution would take Parallel*WorkMs; a true burst finishes in ~WorkMs.
  Assert ($burstMs -lt ($WorkMs * 3)) ("burst took {0:N0}ms, proving real overlap" -f $burstMs)
  $maxima = $results | Where-Object { $_.body } | ForEach-Object { $_.body.maxInFlight } | Sort-Object -Unique
  Write-Output ("  per-JVM max overlap observed: {0} (each must stay within the global cap)" -f ($maxima -join ", "))
  Assert ((($maxima | Measure-Object -Maximum).Maximum) -le $Permits) "no JVM ever exceeded the cap locally"

  Write-Output "=== [5/5] permits released, policy removed ==="
  Start-Sleep -Seconds 2
  $solo = Invoke-WebRequest "http://localhost:$portB/api/work?ms=50" -UseBasicParsing -ErrorAction Stop
  Assert ([int]$solo.StatusCode -eq 200) "a solo request succeeds after the burst (no leaked permits)"
  $del = Invoke-WebRequest "http://localhost:$portA/api/admin/rate-limit/policies/work-conc" -Method Delete -Headers @{ Authorization = $adminAuth } -UseBasicParsing -ErrorAction Stop
  Assert ([int]$del.StatusCode -eq 204) "demo policy deleted"
} finally {
  foreach ($pidToStop in $pids) { Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue }
  Write-Output "instances stopped (redis left running)"
}

if ($script:failures.Count -gt 0) { throw ("FAILED: " + ($script:failures -join "; ")) }
Write-Output ""
Write-Output "CONCURRENCY PROOF PASSED: one global cap held across two JVMs, permits released."
