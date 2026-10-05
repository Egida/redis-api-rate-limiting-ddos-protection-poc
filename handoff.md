# Handoff — Redis API Rate Limiting and DDoS Protection POC

**Status:** complete and pushed. Public repo, `main` in sync with origin.
**Written:** 5 October 2026. Read this file first if you are picking the work up cold.

This is the single document for handing the project to another person or another agent. It is
written from a fresh read of the source, not from working notes.

---

## 1. What this project is

A proof of concept for API rate limiting that uses Redis as the centralized counter store, plus an
Angular console that runs the limit live in a browser so the behaviour is visible rather than
asserted.

Two halves:

- **`redis-rate-limit-poc/`** — Spring Boot 3.5.16 on Java 21. A fixed-window counter in Redis,
  three route policies with independent Redis failure modes, Actuator counters as evidence.
- **`frontend/`** — Angular 22 standalone components, no UI framework. Dev-server proxy keeps the
  browser same-origin, so there is no CORS configuration anywhere.

The claim the whole project exists to prove: **the rate limit is shared state, not per-process
bookkeeping.** Two separate JVMs, one Redis, one counter.

### Repository

| | |
|---|---|
| Local | `C:\Users\Vinaykumar.R\Desktop\Redis API Rate Limiting and DDoS Protection POC` |
| Remote | https://github.com/Vinaykumarreddy467/redis-api-rate-limiting-ddos-protection-poc |
| Visibility | **Public** |
| Branch | `main`, tracking `origin/main` |
| Commits | `43272c5` (initial) · `b890e73` (Markdown report) · one more, see §9 |
| Git identity | already set globally — do not set it again |
| **Latest changes** | Access logging filter + Tomcat DEBUG logging (uncommitted, see §16) |

---

## 2. Layout

```
.
├── redis-rate-limit-poc/              Spring Boot backend
│   ├── pom.xml                        parent 3.5.16, java.version 21, 8 dependencies
│   ├── docker-compose.yml             redis:7-alpine, container ratelimit-poc-redis
│   ├── src/main/java/com/example/ratelimit/
│   │   ├── RateLimitPocApplication.java            @SpringBootApplication @ConfigurationPropertiesScan
│   │   ├── config/
│   │   │   ├── RateLimitProperties.java            typed config + Policy record + enums
│   │   │   └── RateLimitConfiguration.java         bean wiring + filter ordering + access log filter
│   │   ├── ratelimit/
│   │   │   ├── RateLimitFilter.java                the HTTP filter: decide, then allow or 429
│   │   │   ├── RedisRateLimitStore.java            the Lua script, key layout, SHA-256 hashing
│   │   │   ├── RateLimitStore.java                 interface + unavailable exception
│   │   │   ├── RateLimitIdentityResolver.java      IP/USER identity, XFF walk, CIDR matching
│   │   │   ├── RateLimitPolicyResolver.java        most-specific-policy match + startup validation
│   │   │   ├── RateLimitDecision.java              record: allowed, limit, remaining, retryAfter
│   │   │   └── RateLimitMetrics.java               Micrometer counters, bounded labels
│   │   └── web/
│   │       ├── AccessLogFilter.java                logs every request: IP, method, path, outcome, latency, status
│   │       ├── DemoController.java                 the three stand-in business routes
│   │       ├── PocMetadataController.java          GET /api/poc/policies for the console
│   │       └── SecurityConfig.java                 in-memory users, HTTP Basic, /api/orders guarded
│   ├── src/main/resources/application.yml          all rate-limit configuration
│   ├── src/test/java/…                             10 test classes, 86 tests
│   ├── scripts/  verify-all.ps1 · load-demo.ps1 · two-instance-demo.ps1 · admin-cross-instance-demo.ps1 · lib/timing.ps1
│   ├── docs/api-rate-limiting-poc.md               553-line design document
│   └── README.md                                   backend-specific commands
│
├── frontend/                          Angular 22 console
│   ├── package.json                   @angular/* ^22.2.0 · typescript ~6.0.2 · vitest ^5.0.0
│   ├── angular.json                   build targets; test builder is @angular/build:unit-test
│   ├── proxy.conf.json                /api and /actuator → http://localhost:8080
│   ├── src/app/
│   │   ├── core/
│   │   │   ├── api-config.ts          API_CONFIG injection token, poll interval 10s
│   │   │   ├── api-client.service.ts  thin HTTP wrapper
│   │   │   ├── admin-api.service.ts   admin CRUD + capabilities, Basic auth, memory-only credentials
│   │   │   ├── admin-models.ts        admin DTOs, ISO duration helpers
│   │   │   ├── dashboard-api.service.ts  health, metrics, policies, 404-means-no-data handling
│   │   │   ├── demo-catalog.ts        the three routes as UI entries with per-route notes
│   │   │   ├── demo-request.service.ts  single request + Basic auth, credentials dropped after
│   │   │   └── models.ts              every response shape, documented as verified
│   │   ├── features/
│   │   │   ├── dashboard/dashboard-page.component.*   overview, policies, health
│   │   │   ├── request-demo/request-demo.component.* + demo-runner.service.ts
│   │   │   └── admin/admin-policies.component.*   login, list/editor/audit/probe (capabilities-bound)
│   │   ├── app.ts / app.html / app.scss / app.spec.ts
│   ├── src/ main.ts · styles.scss · index.html
│   └── .vscode/ extensions.json · launch.json · tasks.json   (Angular scaffold, committed)
│
├── docs/
│   ├── screenshots/                   01…08 PNG, real headless-Chromium captures
│   └── reports/                       .md · .html · .pdf
├── README.md                          root readme, all screenshots inline
├── .gitignore                         build output + local agent wiring
├── start.bat                          untracked, see §10
└── handoff.md                         this file
```

`frontend/` is a **sibling** of the Maven module, not nested inside it. That was a deliberate
migration; do not move it back. Scripts derive it via `Split-Path -Parent $PSScriptRoot`.

---

## 3. How the limiter works

### The atomic Lua script — `RedisRateLimitStore.java:30-41`

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

`INCR`, the conditional `PEXPIRE` and the `PTTL` read happen inside one Redis-side operation. Two
instances cannot both observe the pre-increment value, and a window can never be created without an
expiry. The `ttl < 0` branch repairs a key that somehow lost its TTL.

`ARGV[1]` = `(windowMillis - (nowMillis mod windowMillis)) + ttlGrace`, so the key expires just past
the window edge rather than at it.

### Decision — `RedisRateLimitStore.java:66-72`

`count <= limit` → allow, `remaining = limit - count`. Otherwise reject, and `retryAfter` is derived
from the **live** TTL read by the script, so it always covers the real remainder of the window.

### Redis key layout — `RedisRateLimitStore.java:95`

```
rate-limit:v1:{policyId}:{identityType}:{sha256-first-16-hex}:{windowIndex}
```

Defined in exactly one method. The raw request path is never used, so path parameters cannot leak
into keys. The window index is `floor(nowMillis / windowMillis)` and keeps consecutive windows from
bleeding into one another.

### Request flow — `RateLimitFilter.java`, `PolicyEnforcer.java`

1. `shouldNotFilter` — skip if the global kill switch is off, the method/path is excluded, or the path is `/api/admin/`.
2. `enforcer.applicablePolicies(method, uri)` — no match means **no limit at all**, the chain proceeds.
3. Resolve identity **per policy**: USER uses the authenticated principal with IP fallback; GLOBAL uses the constant `global`; other scopes use the gated client IP.
4. `enforcer.enforceResolved(...)` — one atomic `consumeAll` batch: a single Lua script inspects every applicable counter before incrementing any of them.
5. Allow → set `X-RateLimit-Limit` / `X-RateLimit-Remaining` from the governing policy, continue.
6. Reject → record the metric, write 429 with headers and a JSON body naming every consulted policy. A denial charges nothing anywhere; single-Redis only, so no Cluster hash-slot claim is made.

### Identity — `RateLimitIdentityResolver.java:50-60`

- An `identity: IP` policy stays per-IP **even when the caller sends credentials**.
- An `identity: USER` policy uses the authenticated principal, and **falls back to the client IP**
  when nobody is authenticated, so an anonymous flood cannot bypass the route.
- `X-Forwarded-For` is read **only** when the socket peer is inside a configured
  `trusted-proxies` CIDR. With none configured, a client cannot forge a fresh identity per request.
- The XFF walk goes **right to left**, skipping trusted hops, and stops at the first non-canonical
  hop rather than continuing into client-supplied text.
- `parseLiteral` rejects non-canonical IPv4, so `010.0.0.1`, `2130706433`, `1.2.3` and
  `::ffff:127.0.0.1` all collapse onto one identity instead of minting separate Redis keys.
- Identities are SHA-256 hashed (first 8 bytes, hex) so raw addresses stay out of Redis.

### Filter ordering — `RateLimitConfiguration.java:41-58`

| Filter | Order | Purpose |
|---|---|---|
| Spring Security chain | `-100` (default) | Authentication, populates `SecurityContext` |
| **AccessLogFilter** | `LOWEST_PRECEDENCE - 150` | Wraps entire chain, sees final status (200/429/401/503) |
| **RateLimitFilter** | `LOWEST_PRECEDENCE - 100` | Enforces limit, short-circuits on 429/503 |

AccessLogFilter runs **before** RateLimitFilter so it wraps the whole chain and logs rejections even when RateLimitFilter short-circuits. The order values are negative, so more negative = runs earlier.

---

## 4. Configuration — `application.yml`

```yaml
rate-limit:
  enabled: true
  key-prefix: rate-limit:v1
  trusted-proxies: []          # empty = ignore XFF/X-Real-IP entirely, use socket peer
  on-redis-error: fail_open    # global default
  ttl-grace: 1s
  excluded-paths: [/actuator/**, /error]
  excluded-methods: [OPTIONS, HEAD]
  policies:
    - { id: products-read, method: GET,  path: /api/products, limit: 100, window: 60s, identity: IP   }
    - { id: login-attempt,  method: POST, path: /api/login,    limit: 10,  window: 60s, identity: IP,
        on-redis-error: fail_closed }
    - { id: order-create,   method: POST, path: /api/orders,   limit: 30,  window: 60s, identity: USER }

logging:
  level:
    com.example.ratelimit: INFO
    org.apache.catalina.connector.CoyoteAdapter: DEBUG   # request line + headers
    org.apache.coyote.http11.Http11InputBuffer: DEBUG    # response headers
```

| id | method | path | limit | window | identity | Redis failure |
|---|---|---|---|---|---|---|
| products-read | GET | /api/products | 100 | 1 min | Client IP | fail open |
| login-attempt | POST | /api/login | 10 | 1 min | Client IP | **fail closed** |
| order-create | POST | /api/orders | 30 | 1 min | Authenticated user | fail open |

**The fail-open / fail-closed split is the core design argument.** A read-heavy catalogue route
fails open — losing Redis costs a counter, not availability. A credential route fails closed — a
lost Redis must never become unlimited login attempts. A single global setting would be wrong in one
direction or the other.

Startup validation rejects duplicate policy ids, paths not starting with `/`, and windows under 1s
(`RateLimitPolicyResolver.java:19-33`).

**Real Redis settings:** host/port from `REDIS_HOST`/`REDIS_PORT` defaulting to localhost:6379,
2s timeout, 2s connect timeout, Lettuce pool enabled (max-active 16, max-idle 8) with
`commons-pool2` on the classpath — that dependency is required whenever Lettuce pooling is on.

---

## 5. API behaviour

### 429 — `RateLimitFilter.java:112-124`

Headers: `Retry-After`, `X-RateLimit-Policy`, `X-RateLimit-Limit`, `X-RateLimit-Remaining: 0`.

JSON body:

```json
{
  "timestamp": "...", "status": 429, "error": "Too Many Requests",
  "message": "Rate limit exceeded for this route. Retry after 59s.",
  "path": "/api/products", "policy": "products-read",
  "limit": 100, "windowSeconds": 60, "retryAfterSeconds": 59
}
```

Allowed requests get `X-RateLimit-Limit` and `X-RateLimit-Remaining` too.

### 503 when Redis is down and the policy fails closed — `RateLimitFilter.java:127-136`

Status **503**, not 429 — deliberate, because no quota was counted, so no limit is claimed. Carries
`X-RateLimit-Policy` and `Retry-After: 5`. No `X-RateLimit-Limit` / `-Remaining` headers.

### Metrics — `RateLimitMetrics.java:27-33`

Counter `ratelimit.requests`, tags `outcome=allowed|rejected|error`, `policy`, `identity`. Labels
are bounded by configuration — never a raw IP or user id. Read at
`/actuator/metrics/ratelimit.requests`, filterable `?tag=outcome:rejected`.

An unknown tag **value** returns HTTP 404 with an empty body. That means "no data", never an error.

Actuator exposes only health, info and metrics. `show-details: never`, so Redis connectivity shows
up only as overall UP/DOWN — **there is no latency figure to report, do not invent one.**

### The demo API — `DemoController.java`

`GET /api/products` (optional `?page`), `POST /api/login` (optional `?user`, returns a throwaway
token), `POST /api/orders` (requires auth, echoes the principal). In-memory users `alice`/`alice-pw`
and `bob`/`bob-pw`. `GET /api/poc/policies` is read-only metadata for the console; it exposes no
Redis key, identity or credential, and there is no mutation endpoint.

`/index.html` on the jar returns **404** — the Angular bundle is never packaged into the backend,
and a test asserts it.

---

## 5.5. Access Logging — `AccessLogFilter.java`

Every HTTP request is logged to `redis-rate-limit-poc/logs/backend.log` with:

```
access method=POST path=/api/login status=429 outcome=RATE_LIMITED durationMs=6 clientIp=0:0:0:0:0:0:0:1
access method=GET path=/api/products status=200 outcome=ALLOWED durationMs=5 clientIp=0:0:0:0:0:0:0:1
```

**Fields:** HTTP method, request path **without the query string**, status, outcome, latency, and client IP. The query string, `Authorization`, `Cookie`, and header values are never logged.

**Outcomes:** `ALLOWED`, `BAD_REQUEST`, `UNAUTHENTICATED`, `FORBIDDEN`, `CONFLICT`, `RATE_LIMITED`, `STORE_UNAVAILABLE`, `ERROR`, `OTHER`.

**Filter ordering:** AccessLogFilter runs at `LOWEST_PRECEDENCE - 150` (before RateLimitFilter at `-100`) so it wraps the entire chain and sees the final status even when RateLimitFilter short-circuits on 429/503.

**Tomcat/coyote header DEBUG is deliberately off.** It would print `Authorization` and `Cookie` verbatim, writing credentials to `backend.log` in plaintext. `application.yml` leaves those loggers at `INFO`; the structured access line above is the request record.

---

## 5.6. Administration API, capabilities, and console

`GET /api/admin/rate-limit/capabilities` (ROLE_ADMIN) reports exactly what the build enforces:
algorithms with `implemented` flags (only `FIXED_WINDOW` true), scopes (`API_KEY` false), the AND
composition rule, and the `single-redis` topology note. The Angular **Admin → Rate Limit Policies**
section (`features/admin/`, `core/admin-api.service.ts`) binds its algorithm/scope dropdowns to this
response, so unimplemented options render disabled and can never be saved as working. The section
covers login (Basic, memory-only credentials, never stored), list, create/edit with server-version
conflict messaging, enable/disable, two-click delete, audit history, and a bounded probe (max 30
sequential requests, cancellable) that reuses the demo runner. `GET /api/poc/policies` stays read-only
for the dashboard.

---

## 6. Tests — 90 JVM tests across 10 classes

| File | @Test | What it establishes |
|---|---|---|
| `PolicyAdminControllerTest` | 12 | Admin boundary: anonymous 401, demo USER 403, admin CRUD lifecycle, stale version 409, invalid policy 400, unimplemented algorithm refused, audit without secrets, capabilities content + auth |
| `ManagedPolicyStoreTest` | 12 | Redis round trip, policy/counter namespacing, create refusal, stale-version conflict, version advance, concurrent-save winner, delete, audit, seed/reset, validation |
| `RateLimitIdentityResolverTest` | 25 | IPv4 spelling canonicalisation, XFF right-to-left walk, forged leftmost prefix, multi-hop, all-trusted, untrusted peer, IPv6 |
| `RateLimitHttpIntegrationTest` | 7 | Below-limit passes, beyond-limit 429 with retry info, per-user isolation, authenticated route limits by user not IP, 401 is not rate-limited, excluded routes pass, bounded metric labels |
| `RateLimitRedisFailureTest` | 9 | Fail-closed 503, fail-open passes, global default applies, kill switch skips the store, unlisted route uncounted, fail-closed makes no quota claim, most-specific policy wins, duplicate id fails startup |
| `RateLimitConfigurationValidationTest` | 6 | Config binding and validation |
| `RedisRateLimitStoreTest` | 8 | Allow-then-reject, identities independent, policies independent, TTL set and key gone after window, key shape, `twoStoreInstancesShareOneLimit`, atomic batch denial charges nothing, concurrent batches take the last unit exactly once |
| `PocMetadataControllerTest` | 6 | `/api/poc/policies` contract; asserts `/index.html` → 404 |
| `RateLimitWindowBoundaryTest` | 4 | Fresh quota every window, Retry-After reflects real time left, keys carry policy and hashed identity, preflight denial charges nothing |
| `RateLimitConcurrencyTest` | 1 | `exactAllowanceUnderParallelLoad` |

`RedisTestSupport` provides a Testcontainers `redis:7-alpine`. **The fixed-window logic is tested
against real Redis, not a mock** — failure modes and TTL behaviour are covered for real.

### The two tests that matter most

**`RateLimitConcurrencyTest.exactAllowanceUnderParallelLoad`** — 400 requests, 40 threads released
together by a `CyclicBarrier` so they genuinely collide, 4 distinct IPs, limit 100 each, asserts
exactly `100 × 4 = 400` allowed. The exact-count assertion is what proves the Lua script prevents
overshoot; a naive read-then-increment would leak past 400.

**`RedisRateLimitStoreTest.twoStoreInstancesShareOneLimit`** — two `RedisRateLimitStore` objects with
**separate connection factories** against one Redis, 8 requests alternating between them for a limit
of 4, asserts exactly 4 allowed. A real shared-state test, not a mock.

### Frontend — 33 tests across 6 files

`app.spec.ts`, `dashboard-api.service.spec.ts`, `demo-catalog.spec.ts`,
`demo-runner.service.spec.ts`, `admin-api.service.spec.ts` (login/auth mapping, 409/400 mapping,
logout drops credentials), `admin-policies.component.spec.ts` (login gating, capabilities-bound
dropdown, unimplemented algorithm refused locally). Runner is `@angular/build:unit-test` on Vitest 5
(jsdom). `npm run build`: Initial 267.66 kB, transfer 71.22 kB. Note
`app.spec.ts` asserts credentials, Redis keys and authorization headers are never rendered.

---

## 7. Verification results

Measured in this environment, not quoted from anywhere.

| Command | Result |
|---|---|
| `mvn -B -o clean test` | Tests run 90, Failures 0, Errors 0 — BUILD SUCCESS |
| `npm test` | 5 suites, 25/25 passed, 4.46 s |
| `npm run build` | Initial 244.44 kB, Transfer 66.15 kB, bundle 4.847 s |
| `verify-all.ps1 -SkipBuild -SkipUnitTests` | 133.50 s, every step OK |
| `load-demo.ps1` products, 120 req, limit 100 | 100×200, 20×429, first 429 at **#101** — PASS |
| `load-demo.ps1` orders, 45 req, limit 30 | 30×200, 15×429, first 429 at **#31** — PASS |
| `load-demo.ps1 -SelfTest` | 8 window-arithmetic checks passed, exit 0 |
| `two-instance-demo.ps1` | 89.3 s — 2 JVMs on 18081/18082 (pids 18120/7364), 30×200 + 30×429 each, **one** Redis key, count 60, pttl 17760 |
| `admin-cross-instance-demo.ps1` | 14/14 checks passed, 2 real JVMs (:18081/:18082), one Redis. Edit on A (products-read 100→5, v5→v6); B allowed exactly 5 then 5×429, first 429 at #6, counter == 5. alice 403, stale edit 409, namespaces disjoint, limit restored to 100 (v7). |
| browser demo, 150 requests | 100 allowed / 50 rejected, first 429 at **#101**, 1.9 s, `Retry-After: 59 s` |

Toolchain: Java 21.0.12.1, Maven 3.9.16, Node 24.19.0, npm 11.17.0, Docker 29.8.0.

**Since the atomic batch (commit `e0de509`), a denied request charges nothing.** The counter holds
exactly the allowed requests — 60 requests against a limit of 30 leaves `count = 30`, not 60.
Rejected attempts are tracked separately in `ratelimit.requests{outcome=rejected}` and the access log.
Older evidence showing `count = 60` predates the atomic batch and its totals are no longer reproducible
by design, not by regression.

### The demo scripts

| Script | Purpose |
|---|---|
| `verify-all.ps1` | Full gate. Build (offline first, downgrade to online), JVM tests, Angular tests, Angular build, Redis, app boot on **:8085**, API contract checks, both load demos. Per-step timing with warn/fatal budgets, fail-fast. Flags: `-SkipBuild`, `-SkipUnitTests`, `-KeepRunning`, `-Port`. |
| `load-demo.ps1` | One route, one policy. Waits for a fresh window, sends N requests, cross-checks the Redis counter when `-RedisContainer` is given, prints first-429 position, then asserts `allowed == min(sent, limit)` and `first429 == limit + 1`. `-Strict` exits non-zero on any mismatch. `-SelfTest` exercises the window arithmetic with no traffic. |
| `two-instance-demo.ps1` | Two real `java -jar` processes against one Redis. |
| `admin-cross-instance-demo.ps1` | Two real JVMs on scanned ports: admin edit on A, enforcement observed on B, namespaces/auth/409 checks, policy restored. Credentials passed as mandatory params, never stored. |
| `lib/timing.ps1` | `Write-Phase`, `New-Step`, `Complete-Step`, `Assert-StepBudget`, `Wait-ForHttp`, `Wait-ForRedisPing`, `Invoke-Native`. |

`verify-all.ps1` asserts `/index.html` must be 404 — the jar must never serve a static console.

**Why the demos wait for a fresh window.** The limiter uses an **epoch-aligned** fixed window
(`floor(nowMillis / windowMillis)`). A run that straddles a boundary sees two allowances and the
totals look wrong (e.g. 50/10) even though the limiter behaved perfectly. Both demos wait for the
window edge, record the window index, abort if it changes mid-run, and report **INCONCLUSIVE** rather
than a false PASS. `-WindowSeconds 1 -Strict` deliberately proves the boundary guard fires.

---

## 8. Documentation produced

| Document | Size | Contents |
|---|---|---|
| `README.md` | 12.5 kB | Architecture diagram, request flow, policy table, run steps, all 8 screenshots inline, verification numbers, known limits, doc map |
| `docs/reports/rate-limiting-poc-report.md` | 13.4 kB / 326 lines | The report as Markdown |
| `docs/reports/rate-limiting-poc-report.html` | 19.2 kB | Print source for the PDF, `@page A4` |
| `docs/reports/redis-api-rate-limiting-ddos-protection-poc.pdf` | 352 kB / 11 pages | The report, printable |
| `redis-rate-limit-poc/docs/api-rate-limiting-poc.md` | 35.6 kB / 553 lines | Deep design doc, 15 sections |
| `redis-rate-limit-poc/README.md` | 3.0 kB | Backend commands |
| `frontend/README.md` | 1.5 kB | Angular CLI default, largely untouched |

Design doc sections: why a demo API exists · architecture and request flow · authentication ordering ·
class map · the Lua algorithm · window boundary behaviour · Redis keys and identity hashing · identity
strategies and proxy trust · configuration · 429 and retry semantics · metrics · Redis failure
behaviour · build/test/run · **actual test results with a scenario-to-test map** · demonstrations ·
**defects found and fixed during review** · limitations and production recommendations · layout.

### Screenshots

`docs/screenshots/` — 8 real captures via headless Chromium, 1440 px desktop and 390 px mobile.

| File | Shows |
|---|---|
| `01-dashboard-overview.png` | 1440×1292 full page — Allowed 200, Rejected 100, 3 policies |
| `02-service-and-redis-health.png` | 1132×183 — Actuator UP including Redis |
| `03-rate-limit-policies.png` | 1132×293 — the three policies from `/api/poc/policies` |
| `04-request-demo-before-run.png` | 1132×265 — console before a run |
| `05-request-demo-429-results.png` | 1132×274 — 150 sent, 100/50, first 429 at #101 |
| `06-response-inspector-headers.png` | 1132×223 — 429, Retry-After 59 s, Remaining 0 |
| `07-dashboard-mobile-layout.png` | 390×2637 — mobile stack |
| `08-two-instance-demo-results.png` | 1000×348 — two-instance terminal output |

Verified against the live DOM: title `RateGuard - API Protection Console`, Redis `Connected`,
3 policies, and the observed run figures above.

---

## 9. Changes made in this documentation engagement

| Commit | What |
|---|---|
| `43272c5` | Initial commit — 83 files |
| `b890e73` | Markdown report + README doc-map link |
| see `git log` | Angular 22 version correction across README, Markdown report, HTML report, regenerated PDF |
| **uncommitted** | Two-JVM admin proof (`admin-cross-instance-demo.ps1`, 14/14), capabilities endpoint, Angular admin section, counter-semantics correction (denials charge nothing) |

### The Angular version error, and the fix

The first version of the README and all three reports claimed **Angular 20**. A fresh inspection of
`frontend/package.json` and `frontend/node_modules` shows the project is on **Angular 22.2.0**
(`@angular/core`, `@angular/cli`, `@angular/build` all `^22.2.0`), TypeScript **6.0.3**, Vitest
**5.0.2**. Six references were corrected and the PDF regenerated. Any other copy of the report
circulating with "Angular 20" is wrong.

To regenerate the PDF after editing the HTML:

```powershell
node "$env:TEMP\opencode\make-pdf.cjs"        # HTML → PDF, logs every image's dimensions
node "$env:TEMP\opencode\check-layout.cjs"    # fails if any block exceeds the 263mm page height
```

### Access logging corrections

`AccessLogFilter.java` now logs path only, never the query string, and resolves client IP through the trusted-proxy-gated resolver. Tomcat/coyote header DEBUG was removed from `application.yml` because it could write `Authorization` and cookies in plaintext. Log file: `redis-rate-limit-poc/logs/backend.log`. See §5.5.

---

## 10. Known limitations

State these before a reviewer finds them.

1. **Fixed window, not sliding.** A client can burst roughly **2× the limit across a window
   boundary**. A sliding-window or token-bucket algorithm would close that. `INCR` + `PEXPIRE` was
   chosen because the point is to prove shared state, not to be the most sophisticated limiter.
2. **Single-node Redis.** No Cluster, no Sentinel. If Redis dies the configured failure mode
   applies; the system does not recover on its own.
3. **`trusted-proxies` ships empty.** Correct and safe by default (XFF ignored entirely), but a real
   deployment behind a load balancer must populate it with the proxy CIDRs or every client will look
   like one address.
4. **In-memory demo credentials.** `alice`/`bob` with hardcoded passwords, HTTP Basic. For the demo
   only.
5. **Actuator counters are in-memory** and reset with the process. The console labels its totals
   "browser-observed since this page opened" for exactly this reason.
6. **Rejections are logged to console/file, not a durable audit store.** `AccessLogFilter` writes
   every rejection to `backend.log` with IP, path, latency, and status. A production system would
   ship these to a log aggregator (Loki/ELK/Datadog) with retention and alerting.
7. **Route matching is literal Ant patterns.** No regex, no versioning.
8. **No WebSocket or gRPC transport** coverage.
9. **`Retry-After` may exceed the window end** by the `ttl-grace` (1s).
10. **A misconfigured proxy that appends rather than overwrites** `X-Forwarded-For` breaks the
    right-to-left walk, making the limit over-restrictive rather than bypassable.

Production recommendations are in design doc §14.

---

## 11. Environment gotchas

These cost real time. Read before running shell commands here.

- **`Set-Content` corrupts non-ASCII files.** PowerShell 5.1 `Set-Content -Encoding UTF8` adds a BOM
  and double-encodes em-dashes and box-drawing characters. It silently damaged 67 lines of README
  once; caught by `git diff`. Use the edit tool, or
  `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`.
  The repo's own `.lean-ledger.md` carries this corruption already.
- **Backslash is not an escape in PowerShell.** `"$p\"` breaks the parser. Use
  `-replace [regex]::Escape($p + '\'), ''`.
- **Quote URLs containing `?` for `gh api`.** Unquoted, PowerShell swallows it and the call
  silently returns 1 file instead of the tree. For `Invoke-RestMethod`, backtick-escape:
  `` "…$sha`?recursive=1" ``.
- **`gh api --jq` expressions containing `{` or `"` get mangled.** Pipe to `ConvertFrom-Json` and
  filter in PowerShell instead.
- **PowerShell here uses Indian digit grouping** for `-f '{0:N0}'`, so `1,194,600` bytes reads like
  11.9 MB. It was 1.19 MB.
- PowerShell 5.1 only, no `pwsh`.
- `Start-Process npx` fails with "%1 is not a valid Win32 application". Use `npm start`.
- A running JVM holding the jar makes `mvn clean` fail with "process cannot access …jar". Stop the
  instance first.
- A demo JVM needs **11–18 s** to become healthy. Poll `/actuator/health`; never sleep a guess.
- **Port 8081 is held by an unrelated app** (`com.example.taskman`). Do not kill it. The
  two-instance demo scans from 18081 for free ports.
- `redis-cli` is not on PATH. Use `docker exec ratelimit-poc-redis redis-cli …`.
- Windows `core.autocrlf` emits ~60 "LF will be replaced by CRLF" warnings per `git add`. Harmless;
  content is stored as LF. A `.gitattributes` would silence it.
- This model cannot process images. **The PDF pages and screenshots have never been visually
  inspected** — see §12.

---

## 12. What still needs a human

1. **Eyeball the 11 PDF pages once.** Verification was structural: `%PDF-1.4`, valid `%%EOF`,
   11 `/MediaBox`, 8 `/Subtype /Image`, 12 `/ToUnicode` (it has a real text layer), all 8 images
   loaded at their source dimensions, and a layout check confirming no figure, table or code block
   exceeds the 263 mm printable height. That is not the same as having looked at it. Check
   screenshot 01 especially — a dense dashboard scaled to 175 mm in the PDF.
2. **Decide about `start.bat`** — see §13.
3. Optionally add `.gitattributes` to stop the CRLF churn.

---

## 13. Open items

### `start.bat` — untracked and undocumented

9,230 bytes, created 5 October 2026 10:48. **It predates this engagement and was not written by the
agent**; it appears to be the user's own work.

It is a good one-command launcher: checks the toolchain, creates-or-starts the Redis container via
`docker inspect`, builds the jar if absent, starts the API and console in titled windows, polls
`/actuator/health` and `:4200` for readiness, prints the expected demo result, and opens the browser.
It handles the spaces-in-this-path problem correctly with `pushd` / `start /D`, and correctly uses
`EnableDelayedExpansion` for its polling loops.

Neither README references it. **Choose: commit it, document it in both READMEs, or delete it. Do
not re-author it.**

### ngrok for external access (this session)

Installed `ngrok` via `winget install Ngrok.Ngrok`. To expose the demo externally:

```powershell
# Terminal A - frontend
ngrok http 4200

# Terminal B - backend API
ngrok http 8080
```

Both tunnels share the ngrok dashboard at `http://localhost:4040` showing every request with client IP, headers, body, latency.

**Important:** The Angular dev server blocks unknown hosts by default. Added `allowedHosts: ["automaker-rebuff-astrology.ngrok-free.dev"]` to `frontend/angular.json` serve options. For a new ngrok URL, either:
- Add the new host to `allowedHosts` and restart `npm start`, or
- Use the backend ngrok URL directly for API calls (`curl https://<backend-ngrok>/api/...`)

### Known public-repo facts

- Commit metadata exposes `vinaykumarreddy467@gmail.com` as author and committer. Normal for a
  public repo under the user's own account, but permanent.
- `.gitignore` excludes `node_modules`, `target`, `dist`, `.angular`, and the local agent wiring
  (`.claude`, `.codex`, `.cursor`, `.mcp`, `.opencode`, `/.vscode`, `/hooks`, `/opencode.json`,
  `/copilot-hooks.json`, `/.context-layer.json`) because every one of those hardcodes
  `C:\Users\Vinaykumar.R\Downloads\...`. `.lean-ledger.md` is also excluded as stale scratchpad.
  `frontend/.vscode/` is kept because those are Angular scaffold files, not agent config — the root
  ignore uses a leading slash so it does not swallow them.
- A pre-flight PII scan before going public found only `alice@example.com` (RFC 2606 reserved, in a
  test) and documentation/test IP ranges (`203.0.113.x`, `198.51.100.x`, `1.1.1.1`, plus
  `010.0.0.1` and `999.999.999.999` which are deliberate IPv4 key-minting fixtures).

### Possibly still running

Backend `:8080`, console `:4200`, Redis container `ratelimit-redis`. `start.bat` prints the
taskkill commands.

---

## 14. Acceptance-criteria mapping

The brief this POC answers has 11 scope items and 16 acceptance criteria. All are met.

| # | Criterion | Where |
|---|---|---|
| 1 | Redis integrated | `application.yml:4-14`, Lettuce pool + `commons-pool2` |
| 2 | Tracked by IP or user | `RateLimitIdentityResolver:50-60` |
| 3 | Limits enforced | `RateLimitFilter:100-109` |
| 4 | Within-limit processed | `RedisRateLimitStore:67-69` |
| 5 | 429 when exceeded | `RateLimitFilter:115` |
| 6 | Meaningful message + retry info | `RateLimitFilter:112-151` — 9-field JSON body + 4 headers |
| 7 | TTL expiry, no manual cleanup | Lua `PEXPIRE`, `RedisRateLimitStore:33` |
| 8 | Config-only changes | all three policies in YAML, zero code change |
| 9 | Different limits per API | 100 / 10 / 30 |
| 10 | High-volume demonstrated | 150-request run, first 429 at #101 |
| 11 | Multi-instance consistent | `twoStoreInstancesShareOneLimit` + `two-instance-demo.ps1` (2 JVMs) |
| 12 | Documented key convention | `RedisRateLimitStore:95`, design doc §4 |
| 13 | Allowed/rejected statistics | `ratelimit.requests` counter, tagged by outcome |
| 14 | Redis failure handled | per-policy fail-open/closed, 503 on fail-closed |
| 15 | Six required test classes | 90 tests across 10 classes — see §6 |
| 16 | Eight documentation topics | design doc 15 sections + README + 3 report formats |

**Sample limits match the brief exactly:** `GET /api/products` 100/min/IP,
`POST /api/login` 10/min/IP, `POST /api/orders` 30/min/user.

**One phrase to get right.** Criterion 15 asks for a multi-instance test. What exists is a
store-level unit test (`twoStoreInstancesShareOneLimit`, two store objects with separate connection
factories) plus a two-JVM demo script. It is **not** a JUnit test booting two Spring contexts. Say
"two separate store instances and, separately, two booted JVMs" — that is accurate and holds up under
scrutiny.

**Beyond the brief:** Lua atomicity, SHA-256 hashed identities, IP canonicalisation, trusted-proxy
CIDR gating, `OPTIONS`/`HEAD`/actuator exclusions, most-specific-policy-wins, duplicate policy id
failing at startup, bounded-cardinality metrics, and a configuration kill switch.

---

## 15. Where to start if you are picking this up

```powershell
cd "C:\Users\Vinaykumar.R\Desktop\Redis API Rate Limiting and DDoS Protection POC"

# Everything, end to end, from a cold start
.\start.bat

# Or the full verification gate (build + both demos + all tests)
powershell -File redis-rate-limit-poc\scripts\verify-all.ps1

# Fastest proof that the limiter works
powershell -File redis-rate-limit-poc\scripts\load-demo.ps1 -Requests 150 -Strict
```

Then open <http://localhost:4200>, set Requests to 150, press Start demo. Expect 100 allowed,
50 rejected, first 429 at request #101.

Read in this order: `README.md` → design doc §3 (algorithm) and §5 (identity) → `RateLimitFilter`
→ `RedisRateLimitStore`.
