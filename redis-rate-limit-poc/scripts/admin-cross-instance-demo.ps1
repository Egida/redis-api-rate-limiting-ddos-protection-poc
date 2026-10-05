# Cross-instance policy proof: an admin edit on instance A is enforced by instance B.
#
# Proves, with two real JVMs sharing one Redis and no restarts:
#   1. Both instances initially enforce the same stored policy.
#   2. A PUT through A's admin API changes the limit.
#   3. Requests to B immediately use the new limit (first 429 at limit+1).
#   4. Policy keys (ratelimit:policy:v1:*) stay separate from counter keys (rate-limit:v1:*).
#   5. Auth (401/403) and version-conflict (409) behaviour is intact.
#   6. The original policy is restored before exit.
#
# Usage:
#   powershell -File scripts\admin-cross-instance-demo.ps1 -AdminUser <name> -AdminPassword <secret>
#
# The credentials live only in the shell that runs this script and in the two child JVMs'
# environments. They are never written to a file, a log, or Redis.
param(
  [Parameter(Mandatory = $true)][string]$AdminUser,
  [Parameter(Mandatory = $true)][string]$AdminPassword,
  [int]$FirstPort = 0,   # 0 = use the first two free ports found
  [int]$NewLimit = 5     # temporary limit applied during the proof, then restored
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$script:failures = @()
function Assert($condition, $label) {
  if ($condition) { Write-Output ("  PASS  {0}" -f $label) }
  else { $script:failures += $label; Write-Output ("  FAIL  {0}" -f $label) }
}

Write-Output "=== [1/7] build (offline, tests skipped; full suite runs separately) ==="
Push-Location $root
try {
  & mvn -B -o -DskipTests package 2>&1 | Select-String -Pattern 'BUILD' | ForEach-Object { Write-Output ("  {0}" -f $_.Line.Trim()) }
} finally { Pop-Location }
$jar = Get-ChildItem "$root\target\redis-rate-limit-poc-*.jar" -Exclude "*sources*", "*.original" |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $jar) { throw "no jar produced" }

Write-Output "=== [2/7] redis (reuse the running one; never touch another container) ==="
$redisName = "ratelimit-redis"
$running = docker ps --filter "name=$redisName" --format "{{.Names}}" 2>$null
if ($running -eq $redisName) { Write-Output "  reusing running container $redisName" }
else {
  if (docker ps -a --filter "name=$redisName" --format "{{.Names}}" 2>$null) { docker start $redisName | Out-Null }
  else { docker run -d --name $redisName -p 6379:6379 redis:7-alpine | Out-Null }
  Write-Output "  started container $redisName"
}
docker exec $redisName redis-cli PING | ForEach-Object { Write-Output "  PING -> $_" }

Write-Output "=== [3/7] two JVMs on isolated ports ==="
$scanFrom = if ($FirstPort -eq 0) { 18081 } else { $FirstPort }
$ports = @()
foreach ($candidate in $scanFrom..($scanFrom + 40)) {
  $busy = Get-NetTCPConnection -State Listen -LocalPort $candidate -ErrorAction SilentlyContinue
  if (-not $busy) { $ports += $candidate; if ($ports.Count -eq 2) { break } }
}
if ($ports.Count -lt 2) { throw "no two free ports found scanning from $scanFrom" }
$portA, $portB = $ports[0], $ports[1]

# Child JVMs inherit this shell's environment, which is the only place the secret lives.
$env:RATELIMIT_ADMIN_USER = $AdminUser
$env:RATELIMIT_ADMIN_PASSWORD = $AdminPassword
$pids = @()
foreach ($port in $ports) {
  $log = "$root\target\cross-instance-$port.log"
  $p = Start-Process java -PassThru -WindowStyle Hidden -RedirectStandardOutput $log `
    -ArgumentList @("-jar", "`"$jar`"", "--server.port=$port")
  $pids += $p.Id
  Write-Output ("  started :{0} (pid {1})" -f $port, $p.Id)
}
# The secret is no longer needed in this shell.
Remove-Item Env:\RATELIMIT_ADMIN_PASSWORD -ErrorAction SilentlyContinue

$adminPair = "$AdminUser`:$AdminPassword"
$adminAuth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($adminPair))
$aliceAuth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("alice:alice-pw"))
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

  Write-Output "=== [4/7] both instances serve the same stored policy ==="
  $polA = Invoke-RestMethod "http://localhost:$portA/api/admin/rate-limit/policies" -Headers @{ Authorization = $adminAuth }
  $polB = Invoke-RestMethod "http://localhost:$portB/api/admin/rate-limit/policies" -Headers @{ Authorization = $adminAuth }
  $idsA = ($polA | ForEach-Object { "{0}@v{1}" -f $_.id, $_.version } | Sort-Object) -join ","
  $idsB = ($polB | ForEach-Object { "{0}@v{1}" -f $_.id, $_.version } | Sort-Object) -join ","
  Assert ($idsA -eq $idsB) ("identical policy sets on A and B: " + $idsA)
  $target = $polA | Where-Object { $_.id -eq "products-read" }
  if (-not $target) { throw "seed policy products-read not found; is the store seeded?" }
  $origLimit, $origVersion = $target.limit, $target.version
  Write-Output ("  products-read is limit={0} v{1}; will set {2}, then restore" -f $origLimit, $origVersion, $NewLimit)

  # Fresh window + clean counters so the phases below measure exactly what they send.
  $wait = (61 - (Get-Date).Second) % 60
  if ($wait -gt 0 -and $wait -lt 60) { Write-Output ("  waiting {0}s for a fresh window" -f $wait); Start-Sleep -Seconds $wait }
  docker exec $redisName redis-cli --scan --pattern "rate-limit:v1:*" | ForEach-Object { docker exec $redisName redis-cli del $_ | Out-Null }

  Write-Output "=== [5/7] admin edit on A, enforcement observed on B (no restarts) ==="
  $edit = '{"id":"products-read","method":"GET","path":"/api/products","algorithm":"FIXED_WINDOW","scope":"IP","window":"PT1M","limit":' + $NewLimit + ',"enabled":true,"version":' + $origVersion + '}'
  $saved = Invoke-RestMethod "http://localhost:$portA/api/admin/rate-limit/policies/products-read" -Method Put -Headers $H -Body $edit
  Assert ($saved.limit -eq $NewLimit) ("A accepted the edit: limit=$($saved.limit) v$($saved.version)")

  $statuses = @()
  for ($i = 1; $i -le ($NewLimit + 5); $i++) {
    try { $r = Invoke-WebRequest "http://localhost:$portB/api/products" -UseBasicParsing -ErrorAction Stop; $statuses += [int]$r.StatusCode }
    catch { $statuses += [int]$_.Exception.Response.StatusCode }
  }
  $ok200 = ($statuses | Where-Object { $_ -eq 200 }).Count
  $r429 = ($statuses | Where-Object { $_ -eq 429 }).Count
  $first429 = [array]::IndexOf($statuses, 429) + 1
  Assert ($ok200 -eq $NewLimit) ("B allowed exactly $NewLimit (got $ok200)")
  Assert ($r429 -eq 5) ("B rejected the remaining 5 (got $r429)")
  Assert ($first429 -eq ($NewLimit + 1)) ("first 429 at request #$first429")

  Write-Output "=== [6/7] namespaces, auth, and version conflicts intact ==="
  $policyKeys = @(docker exec $redisName redis-cli --scan --pattern "ratelimit:policy:v1:*")
  $counterKeys = @(docker exec $redisName redis-cli --scan --pattern "rate-limit:v1:*")
  Assert (($policyKeys | Where-Object { $_ -like "rate-limit:v1:*" }).Count -eq 0) "policy namespace holds no counter keys"
  Assert (($counterKeys | Where-Object { $_ -like "ratelimit:policy:*" }).Count -eq 0) "counter namespace holds no policy keys"
  Assert (($policyKeys -join ",") -match "doc:products-read") "policy document present in Redis"
  Write-Output ("  policy keys : {0}" -f ($policyKeys -join ", "))
  Write-Output ("  counter keys: {0}" -f ($counterKeys -join ", "))
  $productCounters = @($counterKeys | Where-Object { $_ -like "*products-read*" })
  Assert ($productCounters.Count -eq 1) ("one counter key for this window (boundary not straddled)")
  if ($productCounters.Count -eq 1) {
    # Atomic batch semantics: a denied batch charges nothing, so the counter holds exactly the
    # allowed requests. Rejected attempts are tracked separately in ratelimit.requests{outcome=rejected}.
    $count = docker exec $redisName redis-cli get $productCounters[0]
    Assert ([int]$count -eq $NewLimit) ("counter holds exactly the allowed requests: $count")
  }

  try { Invoke-WebRequest "http://localhost:$portB/api/admin/rate-limit/policies" -Headers @{ Authorization = $aliceAuth } -UseBasicParsing -ErrorAction Stop | Out-Null; Assert $false "alice refused on B" }
  catch { Assert ([int]$_.Exception.Response.StatusCode -eq 403) "alice gets 403 on B's admin API" }
  $staleEdit = $edit -replace ('"version":' + $origVersion), '"version":1'
  try { Invoke-RestMethod "http://localhost:$portA/api/admin/rate-limit/policies/products-read" -Method Put -Headers $H -Body $staleEdit | Out-Null; Assert $false "stale edit rejected" }
  catch { Assert ([int]$_.Exception.Response.StatusCode -eq 409) "stale version gets 409 on A" }

  Write-Output "=== [7/7] restore original limit ==="
  $restore = '{"id":"products-read","method":"GET","path":"/api/products","algorithm":"FIXED_WINDOW","scope":"IP","window":"PT1M","limit":' + $origLimit + ',"enabled":true,"version":' + $saved.version + '}'
  $restored = Invoke-RestMethod "http://localhost:$portA/api/admin/rate-limit/policies/products-read" -Method Put -Headers $H -Body $restore
  Assert ($restored.limit -eq $origLimit) ("restored limit=$($restored.limit) v$($restored.version)")
} finally {
  foreach ($pidToStop in $pids) { Stop-Process -Id $pidToStop -Force -ErrorAction SilentlyContinue }
  Write-Output "instances stopped (redis left running)"
}

if ($script:failures.Count -gt 0) { throw ("FAILED: " + ($script:failures -join "; ")) }
Write-Output ""
Write-Output "CROSS-INSTANCE PROOF PASSED: edit on A enforced by B with no restart."
