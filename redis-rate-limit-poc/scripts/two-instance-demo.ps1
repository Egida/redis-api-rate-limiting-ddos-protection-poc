# Two application instances, one Redis: proves the limit is shared, not per-process.
# Usage: pwsh scripts\two-instance-demo.ps1
param(
  [int]$Requests = 60,
  [int]$FirstPort = 0   # 0 = use the first two free ports found
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem "$root\target\redis-rate-limit-poc-*.jar" -Exclude "*sources*" |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $jar) { throw "Build first: mvn -DskipTests package" }

$redisName = "ratelimit-poc-redis"
if (-not (docker ps -a --filter "name=$redisName" -q)) { docker run -d --name $redisName -p 6379:6379 redis:7-alpine | Out-Null }
docker start $redisName | Out-Null
Start-Sleep -Seconds 2

# Pick two free ports so an unrelated app on 8081/8082 cannot break the demo.
$scanFrom = if ($FirstPort -eq 0) { 18081 } else { $FirstPort }
$ports = @()
foreach ($candidate in $scanFrom..($scanFrom + 40)) {
  $busy = Get-NetTCPConnection -State Listen -LocalPort $candidate -ErrorAction SilentlyContinue
  if (-not $busy) {
    $ports += $candidate
    if ($ports.Count -eq 2) { break }
  }
}
if ($ports.Count -lt 2) { throw "no two free ports found scanning from $scanFrom" }

# Start at the beginning of a rate-limit window so all $Requests land in the SAME window.
# Without this the run can straddle a minute boundary and the 30-request limit applies twice,
# which makes the 200/429 totals look wrong (e.g. 50/10) even though the limiter behaved correctly.
$wait = (61 - (Get-Date).Second) % 60
if ($wait -gt 0) { Write-Output "waiting $wait s for a fresh window"; Start-Sleep -Seconds $wait }

# Clear any counter left by a previous demo run so the totals describe this run only.
$stale = docker exec $redisName redis-cli --scan --pattern "rate-limit:v1:*"
foreach ($k in $stale) { docker exec $redisName redis-cli del $k | Out-Null }
if ($stale) { Write-Output "cleared $($stale.Count) stale counter(s) from a previous run" }

$pids = @()
foreach ($port in $ports) {
  $log = "$root\target\instance-$port.log"
  # Jar path is quoted: this project lives in a directory with spaces.
  $p = Start-Process java -PassThru -WindowStyle Hidden -RedirectStandardOutput $log `
    -ArgumentList @("-jar", "`"$jar`"", "--server.port=$port")
  $pids += $p.Id
  Write-Output "started instance on :$port (pid $($p.Id))"
}

try {
  foreach ($port in $ports) {
    $ok = $false
    foreach ($_ in 1..40) {
      Start-Sleep -Seconds 1
      try { Invoke-WebRequest "http://localhost:$port/actuator/health" -UseBasicParsing -ErrorAction Stop | Out-Null; $ok = $true; break } catch {}
    }
    if (-not $ok) { throw "instance :$port never became healthy" }
  }
  Write-Output "both instances healthy"
  Write-Output ""

  # POST /api/orders allows 30 per user per minute. Alice is over budget after 30 requests,
  # no matter which instance served them. 60 requests alternating across both ports.
  $pair = "alice:alice-pw"
  $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
  $counts = @{}
  foreach ($i in 1..$Requests) {
    $port = if ($i % 2 -eq 0) { $ports[0] } else { $ports[1] }
    try {
      $r = Invoke-WebRequest "http://localhost:$port/api/orders" -Method POST -Headers @{ Authorization = "Basic $auth" } -UseBasicParsing -ErrorAction Stop
      $status = [int]$r.StatusCode
    } catch { $status = [int]$_.Exception.Response.StatusCode }
    if (-not $counts.ContainsKey($status)) { $counts[$status] = 0 }
    $counts[$status]++
  }
  $counts.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Output ("status {0,-3} : {1}" -f $_.Name, $_.Value) }
  Write-Output ""
  Write-Output "evidence that both JVMs shared one Redis:"
  Write-Output ("  redis container : {0} (id {1})" -f $redisName, (docker inspect -f "{{.Id}}" $redisName).Substring(0,12))
  Write-Output ("  instance pids   : {0} on ports {1} and {2}" -f ($pids -join ", "), $ports[0], $ports[1])
  $keys = docker exec $redisName redis-cli --scan --pattern "rate-limit:v1:*"
  Write-Output ("  shared keys     : {0}" -f ($keys -join ", "))
  foreach ($k in $keys) {
    $value = docker exec $redisName redis-cli get $k
    $ttl = docker exec $redisName redis-cli pttl $k
    # The counter records every attempt, allowed and rejected alike: 60 requests, 30 allowed.
    Write-Output ("  key {0} -> count {1}, pttl {2}ms" -f $k, $value, $ttl)
  }
} finally {
  foreach ($pidToStop in $pids) { Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue }
  Write-Output "instances stopped (redis left running; docker stop $redisName to remove)"
}
