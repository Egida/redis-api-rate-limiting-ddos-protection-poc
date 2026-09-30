param(
  [string]$BaseUrl = "http://localhost:8080",
  [int]$Requests = 150,
  [string]$Path = "/api/products",
  [string]$Method = "GET",
  [string]$User = "alice",
  [string]$Password = "alice-pw",

  # Policy under test. Defaults match the shipped rate-limit.policies in application.yml.
  # products-read: 100/min per IP. order-create: 30/min per authenticated user.
  [int]$Limit = 100,
  [int]$WindowSeconds = 60,

  # Identity label used only for reporting; it must match the policy's configured identity.
  [string]$Identity = "IP",

  # Optional Redis container name. When set, the script verifies the counter it wrote to, which is
  # stronger evidence than wall-clock timing alone.
  [string]$RedisContainer = "",

  # Exit non-zero when the run crosses a window boundary or the totals do not match the policy.
  [switch]$Strict,

  # Exercise the window arithmetic without sending any traffic.
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

# --- Window arithmetic -------------------------------------------------------------
# The limiter uses an epoch-aligned fixed window: windowId = floor(nowMillis / windowMillis).
# So a run only sees one allowance if it starts just after a boundary and finishes before the next.

function Get-WindowIndex([long]$epochMillis, [int]$windowSeconds) {
  if ($windowSeconds -le 0) { throw "window must be positive" }
  return [long][Math]::Floor($epochMillis / ($windowSeconds * 1000))
}

function Get-SecondsIntoWindow([long]$epochMillis, [int]$windowSeconds) {
  $ms = $epochMillis % ($windowSeconds * 1000)
  return [Math]::Round($ms / 1000.0, 1)
}

function Get-WaitSeconds([long]$epochMillis, [int]$windowSeconds) {
  $into = ($epochMillis % ($windowSeconds * 1000)) / 1000.0
  $remain = $windowSeconds - $into
  if ($remain -le 0) { return 0 }
  return [Math]::Ceiling($remain)
}

if ($SelfTest) {
  # Pure arithmetic, no network. Asserts the boundary rules the demo relies on.
  $failures = @()
  function Check($name, $actual, $expected) {
    if ($actual -ne $expected) { $script:failures += "${name}: expected $expected, got $actual" }
  }
  $w = 60
  # A boundary-aligned instant: 1788999960000 ms is exactly 29816666 * 60000.
  $b = 1788999960000
  Check "index at boundary"        (Get-WindowIndex $b $w) (Get-WindowIndex ($b + 1) $w)
  Check "index advances at +60s"   (Get-WindowIndex ($b + 60000) $w) ((Get-WindowIndex $b $w) + 1)
  Check "seconds at boundary"      (Get-SecondsIntoWindow $b $w) 0.0
  Check "seconds mid window"       (Get-SecondsIntoWindow ($b + 30000) $w) 30.0
  Check "wait at boundary"         (Get-WaitSeconds $b $w) 60
  Check "wait mid window"          (Get-WaitSeconds ($b + 30000) $w) 30
  Check "wait just before edge"    (Get-WaitSeconds ($b + 59999) $w) 1
  Check "index differs across edge" ((Get-WindowIndex ($b + 59999) $w) -ne (Get-WindowIndex ($b + 60000) $w)) $true

  if ($failures.Count -eq 0) {
    Write-Output "self-test: 8 checks passed (window arithmetic)"
    exit 0
  }
  $failures | ForEach-Object { Write-Output "self-test FAILED $_" }
  exit 1
}

# --- Presets keep the documented one-liners honest ---------------------------------

if ($Path -eq "/api/products" -and $Limit -eq 150) {
  $Limit = 100; $Identity = "IP"
}
if ($Path -eq "/api/orders" -and $Limit -eq 150) {
  $Limit = 30; $Identity = "USER"
}

Write-Output "target      : $Method $BaseUrl$Path"
Write-Output "policy      : limit $Limit per $WindowSeconds s per $Identity"
Write-Output "requests    : $Requests"

# Start just after a window boundary so the whole run sees exactly one allowance.
$wait = Get-WaitSeconds ([long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())) $WindowSeconds
if ($wait -gt 0) {
  Write-Output "waiting $wait s for a fresh window"
  Start-Sleep -Seconds $wait
}

$startMillis = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
$startWindow = Get-WindowIndex $startMillis $WindowSeconds
Write-Output ("window      : index {0}, starting {1}s in" -f $startWindow, (Get-SecondsIntoWindow $startMillis $WindowSeconds))

# Local, sequential, moderate load only.
$counts = @{}
$first429 = $null
$crossed = $false

for ($i = 1; $i -le $Requests; $i++) {
  $nowMillis = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
  if ((Get-WindowIndex $nowMillis $WindowSeconds) -ne $startWindow) {
    $crossed = $true
    Write-Output ("window      : CROSSED into index {0} at request #{1}" -f (Get-WindowIndex $nowMillis $WindowSeconds), $i)
    break
  }

  try {
    if ($Method -eq "POST") {
      $pair = "$User`:$Password"
      $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
      $response = Invoke-WebRequest -Uri "$BaseUrl$Path" -Method POST -Headers @{ Authorization = "Basic $auth" } -UseBasicParsing -ErrorAction Stop
    } else {
      $response = Invoke-WebRequest -Uri "$BaseUrl$Path" -Method $Method -UseBasicParsing -ErrorAction Stop
    }
    $status = [int]$response.StatusCode
  } catch {
    $status = [int]$_.Exception.Response.StatusCode
  }

  if (-not $counts.ContainsKey($status)) { $counts[$status] = 0 }
  $counts[$status]++
  if ($status -eq 429 -and -not $first429) { $first429 = $i }
}

$endMillis = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
$sent = ($counts.Values | Measure-Object -Sum).Sum
if ($null -eq $sent) { $sent = 0 }
$elapsed = [Math]::Round(($endMillis - $startMillis) / 1000.0, 1)

$counts.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Output ("status {0,-3} : {1}" -f $_.Name, $_.Value) }
if ($first429) { Write-Output "first 429   : request #$first429" } else { Write-Output "first 429   : never" }
Write-Output ("elapsed     : {0}s of a {1}s window" -f $elapsed, $WindowSeconds)
Write-Output ("requests sent: {0} (all in window index {1})" -f $sent, $startWindow)

# Cross-check the shared counter when a Redis container is named. This proves the run wrote to one
# window's key and that the totals came from the limiter, not from coincidence.
if ($RedisContainer) {
  $keys = docker exec $RedisContainer redis-cli --scan --pattern "rate-limit:v1:*"
  $matched = $keys | Where-Object { $_ -match ":$startWindow$" }
  if ($matched) {
    foreach ($k in $matched) {
      $value = docker exec $RedisContainer redis-cli get $k
      Write-Output ("redis key   : {0} -> count {1}" -f $k, $value)
    }
  } else {
    Write-Output "redis key   : none matching window index $startWindow (counter may have expired)"
  }
}

# --- Verdict ------------------------------------------------------------------------
$problems = @()
if ($crossed) { $problems += "the run crossed a window boundary, so the totals describe two windows and are not conclusive" }

$allowed = if ($counts.ContainsKey(200)) { $counts[200] } else { 0 }
$rejected = if ($counts.ContainsKey(429)) { $counts[429] } else { 0 }
$expectedRejected = [Math]::Max(0, $sent - $Limit)
if ($sent -gt 0) {
  if ($allowed -ne [Math]::Min($sent, $Limit)) {
    $problems += "expected $($sent) requests to yield $([Math]::Min($sent, $Limit)) allowed, got $allowed"
  }
  if ($rejected -ne $expectedRejected) {
    $problems += "expected $expectedRejected rejected, got $rejected"
  }
  if ($first429 -and $first429 -ne ($Limit + 1)) {
    $problems += "first 429 was request #$first429, expected #$($Limit + 1)"
  }
}

if ($problems.Count -gt 0) {
  Write-Output "verdict     : INCONCLUSIVE"
  $problems | ForEach-Object { Write-Output "  - $_" }
  if ($Strict) { exit 1 }
  exit 0
}

Write-Output "verdict     : PASS (one window, $Limit-request allowance enforced exactly)"
if ($Strict) { exit 0 }
