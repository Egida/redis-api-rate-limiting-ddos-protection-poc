# API Rate Limiting and DDoS Protection POC (Redis + Spring Boot)

Proof of concept: centralized, atomic, multi-instance API rate limiting backed by Redis, with
per-route policies, IP and authenticated-user identity, HTTP 429 responses, self-expiring
counters, bounded metrics and a deliberate Redis-failure strategy.

Every number in an "Actual results" section below was produced by the command shown, on this
machine: Windows 11, JDK 21.0.12 (Temurin), Maven 3.9.16, Docker 29.8.0, `redis:7-alpine`
(Testcontainers 1.21.4). Nothing here is projected.

---

## 1. Why a demo API exists in this repository

This repository contained no application before the POC: only editor/agent config (`.claude`,
`.codex`, `.cursor`, `.mcp`, `.opencode`, `.vscode`, `hooks`, `copilot-hooks.json`,
`opencode.json`, `.context-layer.json`). Those files are untouched. There were no business
endpoints to protect, so a three-route demo API was added under `com.example.ratelimit.web`:

| Route | Why it exists |
| --- | --- |
| `GET /api/products` | Public read route, exercises IP limiting with a high limit. |
| `POST /api/login` | Credential route, exercises a tight IP limit and fail-closed behaviour. |
| `POST /api/orders` | Authenticated write route, exercises per-user limiting. |

`DemoController` holds no business logic and is marked as a stand-in. The limiter has no dependency
on these routes: policies are configuration, and any new route is protected by adding a
`rate-limit.policies` entry.

## 2. Architecture and request flow

```text
HTTP request
  |
  v
[springSecurityFilterChain]        order -100  -> populates SecurityContext
  |
  v
[RateLimitFilter]                  order (LOWEST_PRECEDENCE - 100)
  |  shouldNotFilter?  -> enabled==false, method in excluded-methods, path in excluded-paths
  |  no policy match?  -> pass through untouched
  |  resolve identity  -> per the POLICY's strategy: USER (principal) or IP (peer / trusted XFF)
  |  store.consume()   -> one Redis Lua script: atomic INCR + conditional PEXPIRE + PTTL
  |    allowed  -> X-RateLimit-Limit / -Remaining, chain.doFilter()
  |    rejected -> 429 JSON body + Retry-After, chain not invoked
  |    store down -> FAIL_CLOSED: 503 JSON body;  FAIL_OPEN: chain.doFilter()
  v
DispatcherServlet -> DemoController
```

### Authentication ordering (verified, not assumed)

`RateLimitConfiguration.rateLimitFilterRegistration` registers the filter at
`Ordered.LOWEST_PRECEDENCE - 100`. Spring Security's `springSecurityFilterChain` registers at
`SecurityProperties.DEFAULT_FILTER_ORDER` = `-100`, so security runs first and the
`SecurityContext` is populated before `RateLimitIdentityResolver` runs. Two tests prove this
behaviourally rather than by inspection:

- `RateLimitHttpIntegrationTest.requestBeyondTheLimitReturns429WithRetryInfo` — anonymous
  `POST /api/login` is counted and then rejected, so the filter runs before the controller.
- `RateLimitHttpIntegrationTest.unauthenticatedRequestToProtectedRouteIsUnauthorisedNotRateLimited` —
  anonymous `POST /api/orders` returns 401 and consumes **no** quota, because security rejects it
  first. If the filter ran before security, that request would have burned order quota.

### Class map

| File | Responsibility |
| --- | --- |
| `config/RateLimitProperties.java` | Typed, validated `rate-limit.*` configuration. |
| `config/RateLimitConfiguration.java` | Beans: clock, identity resolver, filter, filter ordering. |
| `ratelimit/RateLimitPolicyResolver.java` | Longest-pattern method+path match; structural validation. |
| `ratelimit/RateLimitIdentityResolver.java` | Policy-driven identity, trusted-proxy CIDR matching. |
| `ratelimit/RateLimitStore.java` | Interface: the only seam between HTTP and Redis. |
| `ratelimit/RedisRateLimitStore.java` | Fixed-window counter via one Lua script; key layout. |
| `ratelimit/RateLimitFilter.java` | 429/503 responses, headers, metrics, clock. |
| `ratelimit/RateLimitMetrics.java` | Bounded-cardinality Micrometer counter. |
| `ratelimit/RateLimitDecision.java` | `allowed`, `limit`, `remaining`, `retryAfter`. |

## 3. Algorithm: fixed window with an atomic Lua script

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  ttl = tonumber(ARGV[1])
end
return {count, ttl}
```

**Atomicity.** Redis runs a script to completion without interleaving other commands, so `INCR`
and the conditional `PEXPIRE` cannot be split by a concurrent request from another thread or another
instance. No check-then-increment sequence exists anywhere in the code.

**Self-repair.** The `ttl < 0` branch re-applies the expiry in the same round trip if a key ever
lost its TTL (an operator ran `PERSIST`, a snapshot was restored), instead of leaking a permanent
counter.

**Derived values.** `remaining = max(0, limit - count)`;
`Retry-After = max(1, ceil(livePttl / 1000))`. Because it comes from the live TTL it always covers
the rest of the real window and can never be 0 or negative.

### Window boundary behaviour

The window id is part of the key: `windowId = floor(nowMillis / windowMillis)`.

- Windows are epoch-aligned, not first-request-aligned, so every instance agrees with no
  coordination.
- At a boundary the key changes, so the new window starts at zero and the old key is never read
  again.
- TTL on the first increment of a window is `remaining window time + ttl-grace` (default grace 1s),
  so a counter lives slightly past its window and then self-deletes. No cleanup job exists.
- Boundary cost: a client may be allowed `limit` at 59.9s and another `limit` at 60.1s, i.e. up to
  2× the limit across one boundary. Accepted for this POC; see Limitations.

Covered by `RateLimitWindowBoundaryTest` (controllable clock, no sleeping) and
`RedisRateLimitStoreTest` (real Redis `PTTL`).

Why fixed window rather than sliding window or token bucket: one key and one script call per
request, no sorted-set trimming, bounded Redis memory. A sliding window needs `ZREMRANGEBYSCORE` +
`ZCARD` in the same script — worth it only if exactness at the boundary matters for that route.

## 4. Redis keys and identity hashing

```text
rate-limit:v1:<policy-id>:<identity-type>:<identity-hash>:<window-id>
```

Key shapes observed in Redis. `order-create` is the real key from the two-JVM run below;
`products-read` is the same shape for the IP-identity policy (`2bd806c9…` is the SHA-256 prefix of
`alice`, `9a1f0c2d…` of a client address):

```text
rate-limit:v1:order-create:user:2bd806c97f0e00af:29845871
rate-limit:v1:products-read:ip:<sha256-prefix>:<window-id>
```

- `<policy-id>` is the configured policy id, **never** the raw request path, so path parameters
  (`/api/orders/123`) cannot leak into keys.
- `<identity-hash>` is the first 8 bytes of SHA-256 of the raw identity, hex-encoded (16 chars).
  IPs and usernames are not readable in Redis.
- `<window-id>` stops a stale counter from carrying across windows.
- `key-prefix` is configurable; bump the version segment (`v1` → `v2`) to invalidate all counters
  during a policy migration.

## 5. Identity strategies and proxy trust

The **policy's** configured strategy decides — not who happens to be logged in:

- `identity: IP` — always keyed by client IP, even when the caller presents credentials.
- `identity: USER` — keyed by the `SecurityContext` principal name when authenticated (never a
  token, password or display name). If nobody is authenticated it falls back to the client IP, so
  anonymous flooding cannot bypass the route.

Forwarded headers are read **only** when the socket peer is inside a configured `trusted-proxies`
CIDR. With the default empty list, a client sending `X-Forwarded-For` has no effect and cannot
forge a fresh identity per request to escape its limit.

CIDR matching is backed by `java.net.InetAddress`, so compressed IPv6 literals parse correctly, and
non-literal input is rejected before `InetAddress` could attempt a DNS lookup:

```yaml
rate-limit:
  trusted-proxies:
    - 10.0.0.0/8        # your load balancer or ingress only
    - 2001:db8::/32
```

A bare address (`10.0.0.5`) means that single address. A malformed CIDR or prefix fails startup.

### How the client IP is extracted from `X-Forwarded-For`

Only when the socket peer is a trusted proxy. The header is then scanned **right to left**:

1. Hops inside a trusted CIDR are skipped.
2. The first hop that is **not** trusted is validated as an IP literal and becomes the client
   identity.
3. Nothing to the left of that hop is ever chosen, so a value prepended by the client cannot mint a
   fresh rate-limit identity per request.
4. If no such hop exists (all hops trusted, header blank or absent) the socket peer is used.

Worked example, trusted range `10.0.0.0/8`, peer `10.0.0.9`:

| `X-Forwarded-For` | Resolved identity | Why |
| --- | --- | --- |
| `203.0.113.7, 10.0.0.7, 10.0.0.9` | `203.0.113.7` | two trusted hops skipped |
| `1.2.3.4, 5.6.7.8, 203.0.113.7, 10.0.0.5` | `203.0.113.7` | forged left entries discarded |
| `10.0.0.7, 10.0.0.9, 10.0.0.5` | `10.0.0.5` (peer) | all hops trusted, safe fallback |
| `1.2.3.4, not-an-ip, 10.0.0.5` | `10.0.0.5` (peer) | malformed hop stops the scan |

A hop that is not a canonical IP literal also ends the scan and falls back to the peer, so header text
can never become an identity. `X-Real-IP`, used only when `X-Forwarded-For` is absent, gets the same
treatment.

Canonical means strict, because `InetAddress.getByName` is more permissive than it looks. It accepts
A bare integer (`2130706433` → `127.0.0.1`), a short dotted form (`1.2.3` → `1.2.0.3`), and
IPv4-mapped IPv6 (`::ffff:127.0.0.1`). Since the identity is hashed into the Redis key, every
distinct spelling would be a distinct key and a client could mint unlimited identities for one host.
So a dotted quad must have exactly four canonical groups, and an IPv4-mapped literal is normalised
onto its dotted-quad form. Zone ids (`2001:db8::1%eth0`) are rejected. Measured behaviour of the
underlying JDK call:

| Input | `InetAddress.getByName` | Resolver outcome |
| --- | --- | --- |
| `203.0.113.7` | `203.0.113.7` | identity `203.0.113.7` |
| `2001:db8::1` | `2001:db8:0:0:0:0:0:1` | identity `2001:db8::1` |
| `::ffff:127.0.0.1` | `127.0.0.1` | identity `127.0.0.1` (collapsed) |
| `2130706433` | `127.0.0.1` | **rejected** → peer |
| `1.2.3` | `1.2.0.3` | **rejected** → peer |
| `010.0.0.1` | throws | **rejected** → peer |
| `0x7f000001` | regex rejects | **rejected** → peer |
| `2001:db8::1%eth0` | throws | **rejected** → peer |
| `attacker-1`, `<script>`, `999.999.999.999` | throws / regex rejects | **rejected** → peer |

> **Requirement on your proxies.** Each trusted proxy must **append** the peer it observed to
> `X-Forwarded-For`, or overwrite the header outright. A proxy that forwards a client-supplied header
> untouched destroys this right-to-left walk, because the app can no longer tell which entries its own
> proxies wrote. Strip inbound `X-Forwarded-For` and `X-Real-IP` at the edge, then set them yourself.
> Also keep `trusted-proxies` as narrow as possible: if clients can reach the app directly, leave the
> list empty rather than trusting a shared range.

## 6. Configuration

Defaults in `src/main/resources/application.yml`:

```yaml
rate-limit:
  enabled: true
  key-prefix: rate-limit:v1
  trusted-proxies: []
  on-redis-error: fail_open
  ttl-grace: 1s
  excluded-paths: [/actuator/**, /error]
  excluded-methods: [OPTIONS, HEAD]
  policies:
    - { id: products-read, method: GET,  path: /api/products, limit: 100, window: 60s, identity: IP }
    - { id: login-attempt,  method: POST, path: /api/login,     limit: 10,  window: 60s, identity: IP,  on-redis-error: fail_closed }
    - { id: order-create,   method: POST, path: /api/orders,    limit: 30,  window: 60s, identity: USER }
```

Redis connection uses Spring Boot's standard `spring.data.redis.*`; there is no custom connection
code. `connect-timeout` and `timeout` are both 2s so an unreachable Redis cannot hang a request
thread.

`method: ANY` matches every HTTP method for a path. When two patterns match, the longer pattern
wins (`RateLimitPolicyResolver.mostSpecificPolicyWins`-style behaviour is asserted by
`RateLimitRedisFailureTest.mostSpecificPolicyWins`).

**Startup validation**, all covered by `RateLimitConfigurationValidationTest`:

| Invalid input | Result |
| --- | --- |
| `limit: 0` | Bind failure naming `rate-limit.policies[N].limit` |
| `id` not matching `[a-z0-9-]+` | Bind failure naming `rate-limit.policies[N].id` (ids become key segments and metric labels) |
| `identity` neither `IP` nor `USER` | Bind failure naming `rate-limit.policies[N].identity` |
| empty `policies` list | Bind failure |
| `window` shorter than 1s | Startup failure naming the policy id and the required minimum |
| duplicate policy `id` | Startup failure: `duplicate policy id '<id>'` |
| policy `path` not starting with `/` | Startup failure naming the policy id |
| malformed `trusted-proxies` CIDR or prefix | Startup failure naming `rate-limit.trusted-proxies` |

Failing loudly beats silently not limiting, so an empty or invalid policy set refuses to start
rather than disabling the limiter.

## 7. 429 response and retry semantics

Observed live (`POST /api/orders`, policy 30/min per user, 31 requests already sent):

```http
HTTP/1.1 429
X-RateLimit-Policy: order-create
X-RateLimit-Limit: 30
X-RateLimit-Remaining: 0
Retry-After: 50
Content-Type: application/json

{"policy":"order-create","limit":30,"status":429,"retryAfterSeconds":50,
 "timestamp":"2026-09-30T06:33:11.880280500Z","windowSeconds":60,
 "error":"Too Many Requests",
 "message":"Rate limit exceeded for this route. Retry after 50s.",
 "path":"/api/orders"}
```

Allowed responses carry `X-RateLimit-Limit` and `X-RateLimit-Remaining`:

```http
HTTP/1.1 200
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
```

`Retry-After` is whole seconds rounded up from the live Redis TTL, so a client that backs off for
that long lands inside the next window.

The body is written directly by the filter (a servlet filter cannot use `@RestControllerAdvice`) but
uses the application's Jackson `ObjectMapper` and reuses Spring's default error field names
(`timestamp`, `status`, `error`, `message`), then adds rate-limit specifics. The 503 fail-closed body
uses the same shape and deliberately omits `X-RateLimit-Limit`/`-Remaining`, because no counter was
read.

## 8. Metrics

One counter, `ratelimit.requests`, incremented exactly once per limited request.

| Tag | Values | Cardinality |
| --- | --- | --- |
| `outcome` | `allowed`, `rejected`, `error` | 3 |
| `policy` | configured policy ids | = number of policies |
| `identity` | `ip`, `user` | 2 |

Observed tag set (from `/actuator/metrics/ratelimit.requests`):

```json
{"name":"ratelimit.requests","description":"Rate limit decisions",
 "availableTags":[{"tag":"identity","values":["user","ip"]},
                  {"tag":"outcome","values":["allowed","rejected","error"]},
                  {"tag":"policy","values":["products-read","order-create"]}]}
```

No client IP, username or path is ever a label, so cardinality is bounded by configuration rather
than by traffic. Inspect with:

```bash
curl http://localhost:8080/actuator/metrics/ratelimit.requests
curl "http://localhost:8080/actuator/metrics/ratelimit.requests?tag=outcome:rejected"
curl "http://localhost:8080/actuator/metrics/ratelimit.requests?tag=policy:order-create"
```

## 9. Redis failure behaviour

| Situation | Behaviour |
| --- | --- |
| Redis reachable | Count and enforce. |
| Unreachable, `FAIL_OPEN` (global default) | WARN log, `outcome=error` counter, request proceeds. |
| Unreachable, `FAIL_CLOSED` (per policy or global) | 503 JSON body, `Retry-After: 5`, chain not invoked. |

`login-attempt` overrides to `fail_closed`: unlimited login attempts during a Redis outage are worse
than a 503 on that one route. `fail-open` never bypasses silently — it is visible as the `error`
counter and a WARN per request.

Lettuce timeouts (2s connect / 2s command) bound the failure latency. `DataAccessException` is
translated to `RateLimitStoreUnavailableException` at the store boundary, so the filter never sees
or leaks a raw Redis exception to the client.

Actual behaviour with Redis unreachable (instance started with `--spring.data.redis.port=6399`,
nothing listening):

```text
POST /api/login    -> 503, Retry-After: 5      (fail_closed)
GET  /api/products -> 200                      (fail_open)
GET  /actuator/health -> 503                   (Spring Boot's own Redis health indicator)
ratelimit.requests{outcome=error} -> 2.0
```

The health indicator going red is Spring Boot's own Redis contributor, not the limiter. Actuator is
in `excluded-paths`, so health checks never consume quota.

## 10. Build, test and run

Prerequisites: JDK 21, Maven 3.9+, Node 22+, Docker (Testcontainers starts Redis for you).

```bash
mvn test                 # 63 JVM tests
mvn clean verify         # tests + jar
docker compose up -d     # local Redis on 6379
java -jar target/redis-rate-limit-poc-0.1.0-SNAPSHOT.jar

cd ../frontend && npm install && npm test   # 25 Angular unit tests
npm start                                   # console on http://localhost:4200
```

The console is a standalone Angular app in `../frontend`, a sibling of this Maven project. Its dev
server proxies `/api` and `/actuator` to port 8080 (`frontend/proxy.conf.json`), so the browser sees
a single origin and the jar needs no CORS configuration and ships no static UI of its own.

Demo users: `alice` / `alice-pw`, `bob` / `bob-pw` (HTTP Basic).

```bash
curl -i http://localhost:8080/api/products
curl -i -X POST http://localhost:8080/api/login
curl -i -u alice:alice-pw -X POST http://localhost:8080/api/orders
curl -i -u bob:bob-pw   -X POST http://localhost:8080/api/orders   # separate quota
```

## 11. Actual test results

Command: `mvn -B clean verify` from the project root (`redis-rate-limit-poc/`). Exit code **0**.
Re-verified after the Angular migration.

```text
Tests run:  6, Failures: 0, Errors: 0  RateLimitConfigurationValidationTest
Tests run:  1, Failures: 0, Errors: 0  RateLimitConcurrencyTest
Tests run:  7, Failures: 0, Errors: 0  RateLimitHttpIntegrationTest
Tests run: 25, Failures: 0, Errors: 0  RateLimitIdentityResolverTest
Tests run:  9, Failures: 0, Errors: 0  RateLimitRedisFailureTest
Tests run:  3, Failures: 0, Errors: 0  RateLimitWindowBoundaryTest
Tests run:  6, Failures: 0, Errors: 0  RedisRateLimitStoreTest
Tests run:  6, Failures: 0, Errors: 0  PocMetadataControllerTest
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: target/redis-rate-limit-poc-0.1.0-SNAPSHOT.jar
[INFO] BUILD SUCCESS
```

Angular suite, `npm test` in `../frontend` (Vitest via the Angular CLI): **4 files, 25/25 passed**.
`npm run build` produced a 242.60 kB `main` bundle (65.42 kB estimated transfer) in 12.7s.

Docker was available (`docker info` → server 29.8.0) and Redis genuinely started, from the same
run's log:

```text
INFO org.testcontainers.DockerClientFactory -- Connected to docker:
INFO tc.redis:7-alpine -- Container redis:7-alpine started in PT3.744433S
```

The full suite was also run twice back to back with identical results, so no result below depends on
a lucky run.

### Scenario-to-test map (all executed)

| # | Scenario | Test | Result |
| --- | --- | --- | --- |
| 1 | Requests below the limit are allowed | `RedisRateLimitStoreTest.allowsUpToLimitThenRejects`, `RateLimitHttpIntegrationTest.allowsRequestsBelowTheLimit` | pass |
| 2 | Request beyond the limit → 429 + retry info | `RateLimitHttpIntegrationTest.requestBeyondTheLimitReturns429WithRetryInfo`, `RateLimitWindowBoundaryTest.quotaIsFreshInEveryWindow` | pass |
| 3 | Keys get a TTL and reset after the window | `RedisRateLimitStoreTest.counterGetsTtlAndIsGoneAfterWindow` (asserts real `PTTL` and a distinct next-window key), `RateLimitWindowBoundaryTest` (3 tests, controllable clock, no sleeps) | pass |
| 4 | Two policies enforce different limits independently | `RedisRateLimitStoreTest.differentPoliciesAreIndependent`, `RateLimitRedisFailureTest.mostSpecificPolicyWins` | pass |
| 5 | IP identity anonymous / user identity authenticated | `RateLimitIdentityResolverTest` (21 tests, including policy-driven selection), `RateLimitHttpIntegrationTest.oneUserSpendingTheOrderQuotaDoesNotAffectAnotherUser` + `authenticatedRouteLimitsByUserNotByIp` | pass |
| 5a | Trusted-proxy chain cannot be forged | `RateLimitIdentityResolverTest`: `ignoresAClientForgedLeftmostHopInAProxyChain`, `aForgedHopCannotBeReusedToEscapeTheLimit`, `picksTheNearestUntrustedHopAcrossMultipleTrustedProxies`, `stopsAtTheNearestUntrustedHopEvenWhenAnOuterProxyIsTrusted`, `anAllTrustedChainFallsBackToThePeer`, `aMalformedHopNeverBecomesAnIdentity`, `malformedHeaderTextCannotMintUnlimitedDistinctKeys`, `aMalformedRealIpHeaderFallsBackToThePeer`, `anUntrustedPeerCannotInfluenceIdentityWithEitherHeader` | pass |
| 5b | Non-canonical IP spellings cannot mint extra keys | `RateLimitIdentityResolverTest`: `nonCanonicalIpSpellingsAreRejected`, `ipv4MappedIpv6CollapsesOntoItsDottedQuadForm`, `distinctSpellingsOfOneAddressProduceAtMostTwoIdentities`, `canonicalIpv4AndIpv6AreStillAccepted`, plus the existing IPv4/IPv6/CIDR/`X-Real-IP` tests | pass |
| 6 | Concurrency cannot exceed the allowance | `RateLimitConcurrencyTest.exactAllowanceUnderParallelLoad`: 400 requests, 40 threads, 4 IPs, limit 100 each; asserts exactly 400 allowed and 0 overshoot | pass |
| 7 | Two instances share one limit | `RedisRateLimitStoreTest.twoStoreInstancesShareOneLimit` (store level) and `scripts/two-instance-demo.ps1` (two real JVMs — output below) | pass |
| 8 | Redis-unavailable behaviour matches configuration | `RateLimitRedisFailureTest` (9 tests, stub store), plus a live instance started against a dead Redis port (output in §9) | pass |
| 9 | Excluded routes and unsupported methods | `RateLimitHttpIntegrationTest.excludedAndUnsupportedRequestsPassThrough`, `RateLimitRedisFailureTest.disabledLimiterNeverCallsTheStore`, `unlistedRouteIsNotCounted`, `aRedisOutageDoesNotAffectRoutesWithNoPolicy` | pass |

## 12. Demonstrations

### High volume against one instance

```powershell
# GET /api/products, limit 100/min per IP
powershell -File scripts\load-demo.ps1 -BaseUrl http://localhost:8090 -Requests 120 `
  -Limit 100 -Identity IP -RedisContainer ratelimit-poc-redis -Strict

# POST /api/orders, limit 30/min per authenticated user
powershell -File scripts\load-demo.ps1 -BaseUrl http://localhost:8090 -Method POST -Path /api/orders `
  -Requests 45 -Limit 30 -Identity USER -RedisContainer ratelimit-poc-redis -Strict
```

Both runs observed against one instance on port 8090:

```text
target      : GET http://localhost:8090/api/products
policy      : limit 100 per 60 s per IP
requests    : 120
waiting 31 s for a fresh window
window      : index 29845951, starting 0.4s in
status 200  : 100
status 429  : 20
first 429   : request #101
elapsed     : 3.3s of a 60s window
requests sent: 120 (all in window index 29845951)
redis key   : rate-limit:v1:products-read:ip:b6cf5bd6081fb775:29845951 -> count 120
verdict     : PASS (one window, 100-request allowance enforced exactly)

target      : POST http://localhost:8090/api/orders
policy      : limit 30 per 60 s per USER
requests    : 45
waiting 50 s for a fresh window
window      : index 29845952, starting 0.1s in
status 200  : 30
status 429  : 15
first 429   : request #31
elapsed     : 5.4s of a 60s window
requests sent: 45 (all in window index 29845952)
redis key   : rate-limit:v1:order-create:user:2bd806c97f0e00af:29845952 -> count 45
verdict     : PASS (one window, 30-request allowance enforced exactly)
```

**Why the script waits.** The limiter uses an epoch-aligned fixed window
(`windowId = floor(nowMillis / windowMillis)`), so a naive run that starts mid-window applies the
allowance twice and reports totals that look wrong while the limiter is behaving correctly. The
script therefore:

1. waits for the next window boundary before sending anything;
2. records the window index it started in, and aborts the run if that index changes mid-flight;
3. cross-checks the shared Redis counter for that exact window index when `-RedisContainer` is given;
4. compares the observed totals against the policy (`allowed == min(sent, limit)`, first 429 at
   `limit + 1`) and prints `PASS` or `INCONCLUSIVE` with the reason;
5. exits non-zero under `-Strict` when the run is not conclusive.

Evidence that the crossing guard actually fires — same script, a deliberately 1-second window:

```text
window      : index 1790757134, starting 0.8s in
window      : CROSSED into index 1790757135 at request #5
status 200  : 4
verdict     : INCONCLUSIVE
  - the run crossed a window boundary, so the totals describe two windows and are not conclusive
exit 1
```

Load is sequential and local by design: a correctness demonstration, not a throughput benchmark.

### Checking the window arithmetic without traffic

```powershell
powershell -File scripts\load-demo.ps1 -SelfTest
# self-test: 8 checks passed (window arithmetic)
```

Covers index stability within a window, index advance at the boundary, seconds-into-window at 0 s
and 30 s, wait calculation at the boundary / mid-window / just before the edge, and that the index
differs across the edge.

### Two JVMs, one Redis

```powershell
mvn -DskipTests package
powershell -File scripts\two-instance-demo.ps1
```

The script picks two free ports (18081/18082 by default, advancing past anything already in use),
starts one JVM per port against a single Redis container, waits for both to be healthy, then sends
60 authenticated `POST /api/orders` alternating between them. Observed:

```text
waiting 50 s for a fresh window
started instance on :18081 (pid 8516)
started instance on :18082 (pid 5212)
both instances healthy

status 200 : 30
status 429 : 30

evidence that both JVMs shared one Redis:
  redis container : ratelimit-poc-redis (id 60fba01cf239)
  instance pids   : 8516, 5212 on ports 18081 and 18082
  shared keys     : rate-limit:v1:order-create:user:2bd806c97f0e00af:29845871
  key rate-limit:v1:order-create:user:2bd806c97f0e00af:29845871 -> count 60, pttl 27697ms
```

Two distinct OS processes, two ports, one Redis container, one key. Exactly 30 allowed across both
instances combined: neither had an independent budget. The counter reads 60 because a fixed-window
counter records every attempt, rejected ones included.

The script waits for a fresh window and clears stale counters before sending, because a run that
straddles a minute boundary applies the 30-request allowance twice and reports misleading totals
(observed once as 50/200 and 10/429 before those guards were added — the limiter was correct, the
measurement was not).

Inspect manually:

```bash
docker exec ratelimit-poc-redis redis-cli --scan --pattern "rate-limit:v1:*"
# substitute the key the scan returned, e.g.:
docker exec ratelimit-poc-redis redis-cli get  "rate-limit:v1:order-create:user:2bd806c97f0e00af:29845871"
docker exec ratelimit-poc-redis redis-cli pttl "rate-limit:v1:order-create:user:2bd806c97f0e00af:29845871"
```

## 13. Defects found and fixed during review

A review pass over the finished code found six real problems. All are fixed and covered by tests.

1. **The policy's identity strategy was ignored.** `RateLimitIdentityResolver` decided from the
   `SecurityContext` alone, so `GET /api/products` (configured `identity: IP`) would have switched
   to per-user keys for any caller who sent credentials. Identity resolution is now driven by the
   matched policy, with an IP fallback when a `USER` policy meets an anonymous request. Covered by
   `RateLimitIdentityResolverTest.ipPolicyStaysPerIpEvenWhenTheCallerIsAuthenticated` and
   `userPolicyFallsBackToIpWhenNobodyIsAuthenticated`.
2. **The hand-rolled IPv6 parser mis-placed elided groups.** `2001:db8::1` parsed as
   `2001:db8::0:1`, so any prefix longer than /32 could not match — trusted proxies would silently
   stop being trusted. Replaced with `InetAddress`, which also removes ~40 lines. Covered by
   `RateLimitIdentityResolverTest.ipv6ProxyTrustRespectsCompressedLiteralsAndPrefixLength`.
3. **The HTTP integration test was time-dependent.** It shared one 60-second quota across ordered
   tests, so a minute boundary during the run flipped an expected 429 to a 200. Observed once, then
   eliminated by giving the test class a 10-minute window via
   `src/test/resources/rate-limit-test-windows.properties`. Limits and identity strategies are
   unchanged; window behaviour stays covered by the clock-driven and real-Redis tests.
4. **A `0s` window divided by zero.** `window` now has a startup check with an actionable message.
5. **A malformed `X-Forwarded-For` hop could become a rate-limit identity.** The right-to-left scan
   treated any hop it could not parse as "untrusted" and returned it verbatim, so arbitrary header
   text (`attacker-1`, `<script>`, `999.999.999.999`) reached the key builder. A hop is now validated
   as an IP literal before use; a malformed hop ends the scan and falls back to the socket peer, so
   bad text can neither become an identity nor mint unbounded keys. `X-Real-IP` gets the same check.
   Covered by `aMalformedHopNeverBecomesAnIdentity`,
   `malformedHeaderTextCannotMintUnlimitedDistinctKeys` and
   `aMalformedRealIpHeaderFallsBackToThePeer`.
6. **Non-canonical IP spellings became distinct identities.** Fix 5 still routed candidates through
   `InetAddress.getByName`, which accepts A bare integer (`2130706433`), a short dotted form
   (`1.2.3`) and IPv4-mapped IPv6 (`::ffff:127.0.0.1`). Each spelling hashes to a different Redis key,
   so a client behind a trusted proxy could rotate spellings of its own address for a fresh allowance
   on every request. Validation now requires a canonical dotted quad or a real IPv6 literal, and an
   IPv4-mapped literal is normalised onto its dotted-quad form so one host has exactly one identity.
   Found by probing the JDK call directly rather than by reading the code. Covered by
   `nonCanonicalIpSpellingsAreRejected`, `ipv4MappedIpv6CollapsesOntoItsDottedQuadForm`,
   `distinctSpellingsOfOneAddressProduceAtMostTwoIdentities` and
   `canonicalIpv4AndIpv6AreStillAccepted`.

Also removed while reviewing: a dead `retryAfterSeconds` default method on `RateLimitStore`, a
duplicated key-format string in `RedisRateLimitStore`, an unused logger, and the unused
`spring-security-test` dependency.

## 14. Limitations and production recommendations

**Known limitations of this POC**

1. Fixed window allows up to 2× the limit across a window boundary. Prefer the exact sliding
   window or token bucket policies for routes where that matters; all three are enforceable per
   policy from the admin API.
2. `Retry-After` comes from the live TTL and can exceed the true window end by up to `ttl-grace`;
   this errs toward over-caution, the safe direction.
3. Behind an unlisted load balancer every request appears to come from the LB and shares one quota.
   Safe but coarse; list your proxies.
3a. `X-Forwarded-For` support assumes proxies append correctly. If one forwards a client-supplied
   header untouched, the right-to-left scan cannot distinguish your proxies' entries from the
   client's and resolution may fall back to the proxy address (over-restrictive, not bypass-prone).
   The scanner never trusts a value left of the first untrusted hop, so the failure mode is shared
   quota rather than unlimited keys.
4. Since the atomic multi-policy batch, a denied request charges nothing: the counter holds
   exactly the allowed requests. Rejected attempts are visible in
   `ratelimit.requests{outcome=rejected}` and the access log, not in the quota counter.
5. Demo credentials (`alice`/`bob`) are in-memory with plain HTTP Basic. POC only.
6. No rate-limit rules for WebSocket, gRPC or non-HTTP ingress; this covers the servlet HTTP stack.
7. Redis is a single point here — no Sentinel or Cluster configuration is included.
8. `RateLimitPolicyResolver` matches on the literal request URI. Percent-encoded or path-traversal
   variants (`/api/products%2F..%2Flogin`) are matched as-is; a normalised forwarder in front of
   the app is assumed.

**Production recommendations**

- **Proxy setup**: populate `trusted-proxies` with only your LB/ingress CIDRs, and make sure each one
  appends (or overwrites) `X-Forwarded-For` rather than forwarding a client-supplied header. If
  clients can reach the app directly, keep the list empty or the limit is trivially bypassed by
  rotating the header. The scanner is defensive, not a substitute for correct proxy configuration.
- **Availability**: run Redis with Sentinel or Cluster plus replicas. Set `maxmemory` and
  `maxmemory-policy noeviction` — evicting a counter silently restores quota for that client. Alert
  on any rise in `outcome=error`.
- **Redis capacity**: one key per (policy, identity, window). An attacker rotating IPs creates one
  key each; bound it with short TTLs, `maxmemory`, and upstream network-level limits.
- **Abuse beyond per-client limits**: per-IP limits do not stop a botnet. Pair with edge/WAF
  limiting, account- and ASN-level limits, and anomaly alerting.
- **Endpoint cost weighting**: a cheap `GET` and an expensive report generation should not share a
  limit. Give expensive routes their own policy id and window.
- **Monitoring**: alert on rejected ratio per policy, on `outcome=error`, and on Redis latency. Keep
  metric labels bounded (as implemented) so the metrics backend is not the bottleneck.
- **Key privacy**: identities are SHA-256 hashed. Do not put raw tokens or email addresses in keys.
- **Real load testing**: the scripts here are sequential smoke tests. Run k6/Locust against a
  staging Redis before trusting any capacity number.

## 15. Repository layout

```text
redis-rate-limit-poc/
  pom.xml                       Spring Boot 3.5.16 parent, java.version 21
  docker-compose.yml            Redis for local runs
  README.md
  docs/api-rate-limiting-poc.md this document
  scripts/load-demo.ps1         sequential load against one instance
  scripts/two-instance-demo.ps1 two JVMs, one Redis
  scripts/admin-cross-instance-demo.ps1  admin edit on JVM A enforced by JVM B, no restarts
  scripts/concurrency-demo.ps1           global concurrency cap across two JVMs
  scripts/verify-all.ps1        build + tests + both demos, per-step timing and hard timeouts
  scripts/lib/timing.ps1        stopwatch, polling and alert helpers used by the scripts
  ../frontend/                  standalone Angular console (dev server 4200, proxy -> 8080)
    proxy.conf.json
    src/app/core/               API client, dashboard aggregation, demo request + runner services,
                                admin API client and models
    src/app/features/           dashboard page, request demo, admin policies
  src/main/java/com/example/ratelimit/
    RateLimitPocApplication.java
    config/    RateLimitProperties, RateLimitConfiguration, AdminProperties
    ratelimit/ policy resolver, identity resolver, store, Redis store (all algorithms), filter,
               metrics, decision
    policy/    documents, Redis-backed store, atomic batch enforcer, seeder, API-key registry
    admin/     policy and key administration REST API
    web/       DemoController, SecurityConfig, PocMetadataController (read-only policy metadata)
  src/main/resources/application.yml
  src/test/java/com/example/ratelimit/
    RedisTestSupport.java
    config/RateLimitConfigurationValidationTest.java
    ratelimit/ RedisRateLimitStoreTest, RateLimitConcurrencyTest, RateLimitHttpIntegrationTest,
                RateLimitIdentityResolverTest, RateLimitRedisFailureTest, RateLimitWindowBoundaryTest
    web/        PocMetadataControllerTest
  src/test/resources/rate-limit-test-windows.properties
```

## 16. Enforced algorithms, namespaces, and key management

Every policy selects one algorithm, enforced by a single Lua batch (`BATCH` in
`RedisRateLimitStore.java`) that inspects all applicable counters before charging any of them.
Time comes from Redis `TIME`, so JVM clocks never disagree. Parameters arrive as one JSON object
per policy, decoded with `cjson`.

| Algorithm | State | Decision | Retry |
|---|---|---|---|
| Fixed window | Counter, original `rate-limit:v1:*` layout | `count >= limit` denies | live TTL |
| Exact sliding window | Sorted set `rl:v2:sw:*`, expired members trimmed per decision, TTL refreshed | events in trailing window `>= limit` denies | oldest event expiry |
| Sliding-window counter | Current + previous counters `rl:v2:sc:*:w{index}` | `current + previous × (1 − elapsed/window) >= limit` denies; approximate | window end |
| Token bucket | Hash `{tok, ts}` at `rl:v2:tb:*`, refilled on read, expired when idle | balance `< cost` denies | time to afford cost |
| Leaky bucket (policing) | Depth counter `rl:v2:lb:*`, TTL = drain horizon | depth `>= queueCapacity` denies | drain horizon TTL |
| Concurrency limit | Set of lease ids `rl:v2:cc:*`, TTL = lease | held `>= maxConcurrent` denies | lease TTL |

Namespaces: `rate-limit:v1:*` (fixed counters), `rl:v2:{sw,sc,tb,lb,cc}:*` (algorithm state),
`ratelimit:policy:v1:*` (policy documents, audit, seed marker), `ratelimit:apikey:v1:*` (key
digests). No raw identity, key, or secret appears in any key, log, metric label, or screenshot.

API keys (`ApiKeyRegistry`): `rg_`-prefixed random secrets, SHA-256 stored, raw shown once at
creation. `API_KEY` policies resolve `X-API-Key` to owner/tier; unknown or revoked keys get 401,
never 429. Tier is administrative metadata; per-tier rates are separate policies.

Concurrency leases are acquired in the batch and released in `finally` after the request completes
(an async listener covers async dispatches). Saturation is 429 with the blocking policy id,
consistent with every other denial. `GET /api/work?ms=` sleeps while holding a permit and reports
per-JVM overlap for the two-JVM proof (`scripts/concurrency-demo.ps1`: 12 near-simultaneous slow
requests across two JVMs, exactly 4×200 + 8×429 for a 4-permit global cap, then a solo request
proving release).
