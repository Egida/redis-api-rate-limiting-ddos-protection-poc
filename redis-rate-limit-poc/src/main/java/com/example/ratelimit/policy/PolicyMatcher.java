package com.example.ratelimit.policy;

import java.util.ArrayList;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Produces the candidate policy list for a request, preferring administrator-managed policies in Redis
 * and falling back to the YAML policies when the managed store cannot be read.
 *
 * <p>The fallback is what keeps a Redis outage bounded. If policy lookup threw, the request would fail
 * before any per-policy fail-open/fail-closed rule could be consulted, and the documented behaviour for
 * a fail-closed route (503) would never be reached. Falling back to the immutable YAML copy means the
 * limiter still knows which policy applies, and the store call inside the enforcer is what then
 * triggers the configured failure mode.
 *
 * <p>Consequence to be explicit about: while Redis is unreachable, edits made in the admin UI are not
 * being enforced, because the last-known managed set is unavailable and the YAML baseline applies.
 */
@Component
public class PolicyMatcher {

    private static final Logger log = LoggerFactory.getLogger(PolicyMatcher.class);

    private final ManagedPolicyStore store;
    private final RateLimitProperties yamlProperties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PolicyMatcher(ManagedPolicyStore store, RateLimitProperties yamlProperties) {
        this.store = store;
        this.yamlProperties = yamlProperties;
    }

    /**
     * All enabled policies that apply to {@code method} and {@code path}, most specific route first and
     * global policies last.
     */
    public List<PolicyDocument> matching(String method, String path) {
        var routeScoped = new ArrayList<PolicyDocument>();
        var global = new ArrayList<PolicyDocument>();
        for (PolicyDocument policy : effectivePolicies()) {
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

    /** Managed policies when readable, otherwise the YAML baseline projected into the same shape. */
    private List<PolicyDocument> effectivePolicies() {
        try {
            List<PolicyDocument> managed = store.findAll();
            if (!managed.isEmpty()) {
                return managed;
            }
            // Empty managed store with no seed yet: use YAML so a cold Redis still limits traffic.
            return fromYaml();
        } catch (RuntimeException e) {
            log.warn("managed policy lookup failed ({}); enforcing the application.yml baseline instead",
                    e.getClass().getSimpleName());
            return fromYaml();
        }
    }

    /**
     * Projects the YAML policies into the managed shape.
     *
     * <p>Used as the enforcement fallback when the managed store is unreadable, and by unit tests that
     * exercise the limiter without a Redis-backed policy store.
     */
    public static List<PolicyDocument> fromYamlProperties(RateLimitProperties yamlProperties) {
        var out = new ArrayList<PolicyDocument>();
        for (RateLimitProperties.Policy p : yamlProperties.getPolicies()) {
            Scope scope = p.identity() == RateLimitProperties.Identity.USER ? Scope.USER : Scope.IP;
            out.add(PolicyDocument.builder(p.id())
                    .name(p.id())
                    .route(p.method(), p.path())
                    .algorithm(Algorithm.FIXED_WINDOW)
                    .scope(scope)
                    .window(p.window(), p.limit())
                    .onRedisError(p.onRedisError())
                    .enabled(true)
                    .version(1)
                    .updatedBy("application.yml")
                    .build());
        }
        return out;
    }

    private List<PolicyDocument> fromYaml() {
        return fromYamlProperties(yamlProperties);
    }
}
