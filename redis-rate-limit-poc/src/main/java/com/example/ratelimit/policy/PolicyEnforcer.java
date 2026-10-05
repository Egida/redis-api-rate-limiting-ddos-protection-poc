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
 * <p><strong>Composition is preflight-then-commit.</strong> Every applicable policy is peeked without
 * spending quota; only if all peeks allow are the policies consumed. A preflight denial therefore leaves
 * earlier policies uncharged. The remaining race is between a successful preflight and the commit: a
 * concurrent request can take the last unit first, so the commit may reject after earlier commits have
 * already incremented. That bias is toward rejecting, never toward over-admitting.
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
            RateLimitDecision decision) {

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
            if (policy.scope() == Scope.GLOBAL || policy.path() == null || policy.path().isBlank()) {
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
     * Evaluates already-resolved policies.
     *
     * <p>Preflight first: every policy is peeked without charging. If any peek denies, nothing has been
     * consumed and that denial is returned. Only when all peeks allow are the policies committed in
     * order. A commit can still reject if a concurrent request spent the last unit after the preflight;
     * that race is safe but not free, and it is documented rather than hidden.
     */
    public Composition enforceResolved(List<ResolvedPolicy> resolved, long nowMillis) {
        if (resolved.isEmpty()) {
            return new Composition(List.of(), null, null);
        }

        List<PolicyDocument> consulted = resolved.stream().map(ResolvedPolicy::policy).toList();
        for (ResolvedPolicy candidate : resolved) {
            RateLimitDecision preflight = store.peek(asStorePolicy(candidate.policy()),
                    candidate.identityType(), candidate.identityValue(), nowMillis);
            if (!preflight.allowed()) {
                return new Composition(consulted, candidate.policy(), preflight);
            }
        }

        RateLimitDecision governing = null;
        for (ResolvedPolicy candidate : resolved) {
            RateLimitDecision decision = store.consume(asStorePolicy(candidate.policy()),
                    candidate.identityType(), candidate.identityValue(), nowMillis);
            if (governing == null) {
                // Most specific policy first, so this is the one whose limit headers the client sees.
                governing = decision;
            }
            if (!decision.allowed()) {
                return new Composition(consulted, candidate.policy(), decision);
            }
        }
        // Allowed. The decision carries the governing policy's limit and remaining count, which the
        // filter publishes as X-RateLimit-Limit / X-RateLimit-Remaining.
        return new Composition(consulted, null, governing);
    }

    /**
     * Projects a managed policy onto the record the existing store understands.
     *
     * <p>Only {@link Algorithm#FIXED_WINDOW} can appear here: {@link PolicyDocument#validate()} refuses
     * to store a policy selecting an unimplemented algorithm, so reaching this method with anything else
     * would mean the store was written to directly, bypassing the admin API.
     */
    private static RateLimitProperties.Policy asStorePolicy(PolicyDocument policy) {
        if (policy.algorithm() != Algorithm.FIXED_WINDOW) {
            throw new IllegalStateException("policy " + policy.id() + " selects " + policy.algorithm()
                    + ", which has no enforcing strategy in this phase");
        }
        RateLimitProperties.Identity identity = policy.scope() == Scope.USER
                ? RateLimitProperties.Identity.USER
                : RateLimitProperties.Identity.IP;
        return new RateLimitProperties.Policy(policy.id(), policy.method(), policy.path(),
                policy.limit(), policy.window(), identity, policy.onRedisError());
    }

    /** Projects a managed policy for the identity resolver. */
    public RateLimitProperties.Policy storePolicy(PolicyDocument policy) {
        return asStorePolicy(policy);
    }

    public RateLimitProperties.FailureMode failureModeFor(PolicyDocument policy) {
        return policy.onRedisError() != null ? policy.onRedisError() : globalFailureMode;
    }
}
