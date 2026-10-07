# Redis API Rate Limiting and DDoS Protection POC

Spring Boot 3.5 / Java 21 proof of concept: atomic, Redis-backed, multi-instance API rate limiting.

Full write-up: **[docs/api-rate-limiting-poc.md](docs/api-rate-limiting-poc.md)**

## Quick start

```bash
docker compose up -d          # Redis on 6379
mvn -DskipTests package
java -jar target/redis-rate-limit-poc-0.1.0-SNAPSHOT.jar
```

```bash
curl -i http://localhost:8080/api/products
curl -i -X POST http://localhost:8080/api/login                       # 10/min per IP
curl -i -u alice:alice-pw -X POST http://localhost:8080/api/orders    # 30/min per user
```

## Console (Angular)

The console is a standalone Angular app in `../frontend` (a sibling of this Maven project). It runs
on its own dev server and proxies `/api` and `/actuator` to the jar, so no CORS config is needed.

```bash
cd ../frontend
npm install        # first run only
npm start          # http://localhost:4200
```

| Command | What it does |
| --- | --- |
| `npm start` | dev server on 4200 with the proxy to 8080 |
| `npm test` | 25 unit tests (Vitest through the Angular CLI) |
| `npm run build` | production bundle into `frontend/dist/frontend` |

Run the jar first, then `npm start`, and open http://localhost:4200.

## Tests

```bash
mvn test                    # 105 tests, Redis supplied by Testcontainers
cd ../frontend && npm test  # 34 Angular unit tests
```

## Demonstrations

```powershell
# one instance, limit 100/min per IP. Waits for a fresh window, then reports PASS/INCONCLUSIVE.
powershell -File scripts\load-demo.ps1 -BaseUrl http://localhost:8090 -Requests 120 `
  -Limit 100 -Identity IP -RedisContainer ratelimit-poc-redis -Strict

# per-user limit on an authenticated route
powershell -File scripts\load-demo.ps1 -BaseUrl http://localhost:8090 -Method POST -Path /api/orders `
  -Requests 45 -Limit 30 -Identity USER -RedisContainer ratelimit-poc-redis -Strict

powershell -File scripts\two-instance-demo.ps1                     # two JVMs, one shared Redis limit
powershell -File scripts\load-demo.ps1 -SelfTest                   # window arithmetic, no traffic

# everything, with per-step timing and hard timeouts
powershell -File scripts\verify-all.ps1
```

## Layout

```text
src/main/java/com/example/ratelimit/
  config/     typed configuration + filter ordering
  ratelimit/  policy resolution, identity, Redis store, filter, metrics
  web/        demo API + minimal security (stand-ins, no business logic)
src/test/java/com/example/ratelimit/
  config/RateLimitConfigurationValidationTest
  ratelimit/ RedisRateLimitStoreTest, RateLimitConcurrencyTest, RateLimitHttpIntegrationTest,
             RateLimitIdentityResolverTest, RateLimitRedisFailureTest, RateLimitWindowBoundaryTest
  web/     PocMetadataControllerTest + Spring Boot tests
docs/api-rate-limiting-poc.md
scripts/load-demo.ps1, scripts/two-instance-demo.ps1, scripts/verify-all.ps1
../frontend/               standalone Angular console (dev server 4200, proxy -> 8080)
```
