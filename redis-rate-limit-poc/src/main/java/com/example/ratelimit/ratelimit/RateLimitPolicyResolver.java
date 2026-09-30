package com.example.ratelimit.ratelimit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/** Picks the single most specific policy for a request. No policy match means no limit. */
@Component
public class RateLimitPolicyResolver {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<Policy> policies;

    public RateLimitPolicyResolver(RateLimitProperties properties) {
        this.policies = List.copyOf(properties.getPolicies());
        Set<String> ids = new HashSet<>();
        for (Policy p : this.policies) {
            if (!ids.add(p.id())) {
                throw new IllegalStateException("rate-limit: duplicate policy id '" + p.id()
                        + "'. Each id must be unique; it is used as the Redis key and metric label.");
            }
            if (p.path() == null || !p.path().startsWith("/")) {
                throw new IllegalStateException(
                        "rate-limit: policy '" + p.id() + "' path must start with '/' but was " + p.path());
            }
            if (p.window() == null || p.window().toMillis() < 1000) {
                throw new IllegalStateException("rate-limit: policy '" + p.id() + "' window must be at least 1s"
                        + " but was " + p.window() + ". A zero-length window has no meaningful counter.");
            }
        }
    }

    public List<Policy> all() {
        return policies;
    }

    /** Returns the policy whose pattern matches with the most path characters, or null. */
    public Policy resolve(String method, String path) {
        Policy best = null;
        int bestScore = -1;
        for (Policy p : policies) {
            if (!p.method().equalsIgnoreCase(method) && !"ANY".equalsIgnoreCase(p.method())) {
                continue;
            }
            if (!matcher.match(p.path(), path)) {
                continue;
            }
            int score = p.path().length();
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }
        return best;
    }
}
