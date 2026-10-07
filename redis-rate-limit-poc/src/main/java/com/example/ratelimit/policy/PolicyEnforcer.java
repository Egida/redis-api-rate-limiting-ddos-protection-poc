package com.example.ratelimit.policy;

import java.util.ArrayList;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.ratelimit.RateLimitDecision;
import com.example.ratelimit.ratelimit.RateLimitStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Enforces managed policies by delegating to the original atomic fixed-window store.
 *
 * <p>Reusing {@link RateLimitStore} rather than adding a second counter implementation is deliberate:
 * the Lua script there is already proven by the concurrency and TTL tests, and duplicating it for the
 * managed path would mean two implementations to keep correct. The managed store only decides
 * <em>which</em> policies apply and with what identity; the store still decides whether to allow.
 *
 * <p><strong>Composition is one atomic batch.</strong> The enforcer hands every applicable policy to
 * the store in a single {@code consumeAll} call. The Redis implementation inspects all counters inside
 * one Lua script before incrementing any of them, so a denial charges nothing anywhere — rejected
 * attempts are recorded only as metrics, never as quota. There is no check/commit window left to race.
 */
@Component
public class PolicyEnforcer {

    /** One applicable policy together with the already-resolved identity it must be charged against. */
    public record ResolvedPolicy(PolicyDocument policy, String identityType, String identityValue) {
    }

    /** Outcome of evaluating every policy that applied to one request. */
    public record Composition(
            List<PolicyDocument> consulted,
            PolicyDocument blockedBy,
            RateLimitDecision decision,
            /** Policy id to lease id for concurrency permits this evaluation acquired. */
            java.util.Map<String, String> leases) {

        public boolean allowed() {
            return blockedBy == null;
        }
    }

    private final PolicyMatcher matcher;
    private final RateLimitStore store;
    private final RateLimitProperties.FailureMode globalFailureMode;
    /** Non-null only for unit tests that supply policies directly instead of via the managed store. */
    private final List<PolicyDocument> explicitPolicies;

    /**
     * Production constructor, reading the managed policy set.
     *
     * <p>Marks the injection point explicitly: this class also has a private constructor for the
     * explicit-policy test seam, and without this Spring sees more than one candidate and looks for a
     * no-arg constructor that does not exist.
     */
    @Autowired
    public PolicyEnforcer(PolicyMatcher matcher, RateLimitStore store, RateLimitProperties properties) {
        this.matcher = matcher;
        this.store = store;
        this.globalFailureMode = properties.getOnRedisError();
        this.explicitPolicies = null;
    }

    private PolicyEnforcer(List<PolicyDocument> policies, RateLimitStore store, FailureMode global) {
        this.matcher = null;
        this.store = store;
        this.globalFailureMode = global;
        this.explicitPolicies = List.copyOf(policies);
    }

    /**
     * Enforcer over a fixed policy list, bypassing the managed store.
     *
     * <p>Exists so limiter unit tests can drive the filter with in-memory policies and a stub store.
     * Production wiring always uses the managed-store constructor.
     */
    public static PolicyEnforcer forExplicitPolicies(List<PolicyDocument> policies, RateLimitStore store,
            RateLimitProperties.FailureMode global) {
        return new PolicyEnforcer(policies, store, global);
    }

    /** Policies that apply to this request, used by the filter to decide the governing scope. */
    public List<PolicyDocument> applicablePolicies(String method, String path) {
        return explicitPolicies != null ? select(explicitPolicies, method, path) : matcher.matching(method, path);
    }

    /**
     * Route/global selection for a caller-supplied list, mirroring {@link PolicyMatcher#matching} so a
     * test-driven enforcer behaves identically to the production one.
     */
    private static List<PolicyDocument> select(List<PolicyDocument> policies, String method, String path) {
        var matcher = new org.springframework.util.AntPathMatcher();
        var routeScoped = new ArrayList<PolicyDocument>();
        var global = new ArrayList<PolicyDocument>();
        for (PolicyDocument policy : policies) {
            if (!policy.enabled()) {
                continue;
            }
            if (policy.method() != null && !"ANY".equalsIgnoreCase(policy.method())
                    && !policy.method().equalsIgnoreCase(method)) {
                continue;
            }
            if (policy.scope() == Scope.GLOBAL || policy.scope() == Scope.APPLICATION
                    || policy.path() == null || policy.path().isBlank()) {
                global.add(policy);
            } else if (matcher.match(policy.path(), path)) {
                routeScoped.add(policy);
            }
        }
        routeScoped.sort((a, b) -> Integer.compare(
                b.path() == null ? 0 : b.path().length(),
                a.path() == null ? 0 : a.path().length()));
        var all = new ArrayList<PolicyDocument>(routeScoped);
        all.addAll(global);
        return all;
    }

    /**
     * Evaluates already-resolved policies in one atomic batch.
     *
     * <p>The store decides and charges every policy inside a single Redis-side operation, so a denial
     * never leaves earlier policies charged. The only store that cannot do better is one using the
     * interface default, which falls back to sequential peek-then-commit.
     */
    public Composition enforceResolved(List<ResolvedPolicy> resolved, long nowMillis) {
        if (resolved.isEmpty()) {
            return new Composition(List.of(), null, null, java.util.Map.of());
        }

        List<PolicyDocument> consulted = resolved.stream().map(ResolvedPolicy::policy).toList();
        var charges = new ArrayList<RateLimitStore.Charge>(resolved.size());
        for (ResolvedPolicy candidate : resolved) {
            charges.add(new RateLimitStore.Charge(candidate.policy(), candidate.identityType(),
                    candidate.identityValue()));
        }
        RateLimitStore.BatchDecision batch = store.consumeAll(charges, nowMillis);
        if (!batch.allowed()) {
            return new Composition(consulted, consulted.get(batch.blockedIndex()), batch.decision(),
                    java.util.Map.of());
        }
        // Allowed. The decision carries the governing policy's limit and remaining count, which the
        // filter publishes as X-RateLimit-Limit / X-RateLimit-Remaining.
        return new Composition(consulted, null, batch.decision(), batch.leases());
    }

    /**
     * Projects a managed policy onto the record the identity resolver understands.
     *
     * <p>Only the scope mapping matters here; the resolver never reads limits or windows, so this
     * works for every algorithm, unlike the fixed-window store projection.
     */
    private static RateLimitProperties.Policy asStorePolicy(PolicyDocument policy) {
        RateLimitProperties.Identity identity = policy.scope() == Scope.USER
                ? RateLimitProperties.Identity.USER
                : RateLimitProperties.Identity.IP;
        return new RateLimitProperties.Policy(policy.id(), policy.method(), policy.path(),
                policy.limit() == null ? 0 : policy.limit(),
                policy.window() == null ? java.time.Duration.ofSeconds(1) : policy.window(), identity,
                policy.onRedisError());
    }

    /** Projects a managed policy for the identity resolver. */
    public RateLimitProperties.Policy storePolicy(PolicyDocument policy) {
        return asStorePolicy(policy);
    }

    public RateLimitProperties.FailureMode failureModeFor(PolicyDocument policy) {
        return policy.onRedisError() != null ? policy.onRedisError() : globalFailureMode;
    }

    /**
     * Releases one concurrency lease. Called after the request completes, in a finally block for
     * normal requests and from an async listener when the servlet container takes over the lifecycle.
     */
    public void release(ResolvedPolicy resolved, String leaseId) {
        store.releaseConcurrency(resolved.policy(), resolved.identityType(), resolved.identityValue(),
                leaseId);
    }
}
