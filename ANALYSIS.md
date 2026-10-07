# Project Analysis

**Review date:** 2026-10-06
**Scope:** Source code, application/build configuration, scripts, and tests. Documentation, PDFs, screenshots, logs, editor/agent wiring, and dependency lockfiles were excluded from the code review.

## What the project does

A proof-of-concept rate limiting system with a Spring Boot 3.5 / Java 21 backend, Redis shared state, and an Angular 22 operator console. Requests pass through Spring Security, the access logger, then the limiter. Admin-managed policies are stored in Redis; the console offers policy, audit, health, metrics, and request-demo views.

## Backend design

- Policies are selected by HTTP method and Ant-style route pattern. Route-specific policies are evaluated before global policies. All applicable policies must allow a request.
- The Redis store uses Lua for atomic batch decisions. It checks every applicable quota before charging any, so rejected requests do not consume quota in the Redis implementation.
- Supported algorithms: fixed window, exact sliding window, approximate sliding-window counter, token bucket, leaky-bucket policing, and concurrency limits using expiring leases. Supported scopes include endpoint, IP, authenticated user, and global.
- Client IP forwarding headers are ignored unless the socket peer belongs to a configured trusted-proxy CIDR. Policy writes use Redis-side optimistic version checks and keep a bounded audit history.
- If managed policies cannot be read, the matcher falls back to YAML policies. Per-policy Redis failure behavior then determines whether requests are allowed or return 503. The sample is globally fail-open, with the login-attempt policy fail-closed.

## Frontend and operations

- Angular uses standalone routed components and services. Admin credentials remain in service memory for the page session. The route guard is only a UI convenience; backend authorization protects admin endpoints.
- `start.bat` starts local Redis/backend/frontend; PowerShell scripts exercise load, concurrency, and two-instance behavior. Compose runs a single Redis 7 instance with AOF persistence and a named volume.
- Automated tests cover store/window boundaries, Redis failures, identity resolution, HTTP integration, concurrency, policy composition and admin behavior, plus Angular services/components. Tests were inspected but not run for this read-only analysis.

## Findings and operational limits

1. **Production boundary:** this is explicitly a POC configuration. The sample includes demo `alice`/`bob` credentials, HTTP Basic, a local Redis without authentication/TLS, a fail-open default, and a dev-server host allowlist. Before any non-local deployment, replace demo identities, require TLS for credentials, secure Redis/network access, review failure modes route by route, and remove/parameterize dev-only host settings.
2. **Single Redis topology:** Lua batches touch multiple keys and policy scripts use a shared index. The code documents that Redis Cluster is unsupported without a redesign using hash tags/partitioned scripts. Current compose also has one Redis instance, so this is not a highly available deployment.
3. **Policy fallback semantics:** while Redis is unavailable, runtime admin edits cannot be read and YAML baseline policies apply. This is documented in code; operators should account for the possible mismatch between the last saved UI policy and active enforcement during an outage.
4. **Leaky bucket semantics:** implementation is policing, not request queuing/shaping. Overflow requests are rejected; the UI capability description correctly calls out this behavior.
5. **Repository state:** the checkout already has many staged, unstaged, and untracked changes, including logs and browser-capture artifacts. Those were left untouched; review this state before staging or committing anything.

## Overall assessment

The code has a coherent POC architecture with strong attention to atomic Redis decisions, proxy-header trust, secret handling, and test coverage. Its main constraints are deployment hardening and its deliberately single-Redis topology. No implementation changes or runtime verification were performed as part of this analysis.
