package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;

import java.time.Duration;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A rate-limit policy as stored in Redis and edited by an administrator.
 *
 * <p>Supersedes {@code RateLimitProperties.Policy} for runtime enforcement, but that record remains
 * the first-run seed source and the schema is deliberately compatible with it.
 *
 * <p>Parameters are a flat set of nullable fields rather than a polymorphic tree: only the fields
 * meaningful for {@link #algorithm()} may be set, and {@link #validate()} enforces that. A flat shape
 * keeps one JSON schema for every algorithm, which is what the admin form binds to.
 *
 * @param version incremented on every accepted save. A save carrying a stale version is rejected so
 *                two admins editing the same policy cannot silently overwrite each other.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyDocument(
        String id,
        String name,
        String method,
        String path,
        Algorithm algorithm,
        Scope scope,
        /** Period of the window. Required by FIXED_WINDOW, SLIDING_WINDOW, SLIDING_WINDOW_COUNTER. */
        Duration window,
        /** Request ceiling per window. Required by every window algorithm. */
        Integer limit,
        /** Token bucket capacity, i.e. the burst size. Required by TOKEN_BUCKET. */
        Integer capacity,
        /** Token bucket refill. Required by TOKEN_BUCKET. */
        Duration refillInterval,
        /** Tokens consumed per request. Defaults to 1. */
        Integer cost,
        /** Leaky bucket drain rate. */
        Integer drainRate,
        /** Leaky bucket queue depth. */
        Integer queueCapacity,
        /** Concurrency ceiling. Required by CONCURRENCY_LIMIT. */
        Integer maxConcurrent,
        /** How long a permit is held before Redis reclaims it. Required by CONCURRENCY_LIMIT. */
        Duration leaseDuration,
        /** When true the policy is stored but never enforced. */
        boolean enabled,
        FailureMode onRedisError,
        long version,
        Instant createdAt,
        Instant updatedAt,
        /** Who last changed it. Never a credential. */
        String updatedBy) {

    /** Compact, human-readable summary used by the admin list and by audit records. */
    public String describeParameters() {
        return switch (algorithm) {
            case FIXED_WINDOW, SLIDING_WINDOW, SLIDING_WINDOW_COUNTER ->
                    "%d per %s".formatted(limit, humanDuration(window));
            case TOKEN_BUCKET ->
                    "burst %d, refill %s per %s".formatted(capacity, cost == null ? 1 : cost,
                            humanDuration(refillInterval));
            case LEAKY_BUCKET ->
                    "drain %d/s, queue %s".formatted(drainRate, queueCapacity);
            case CONCURRENCY_LIMIT ->
                    "max %d in flight, lease %s".formatted(maxConcurrent, humanDuration(leaseDuration));
        };
    }

    /** "PT1M" reads badly in a table; the UI shows durations, never raw ISO strings. */
    private static String humanDuration(java.time.Duration duration) {
        if (duration == null) {
            return "—";
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + "s";
    }
    /**
     * Rejects a document that is internally inconsistent. Runs before any write, so a bad save can
     * never partially replace a live policy.
     *
     * @throws PolicyValidationException with every problem found, not just the first
     */
    public void validate() {
        var problems = new java.util.ArrayList<String>();

        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            problems.add("id must match [a-z0-9][a-z0-9-]{0,62}");
        }
        if (method == null || !method.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY")) {
            problems.add("method must be an HTTP verb or ANY");
        }
        // A GLOBAL policy deliberately has no route to match.
        if (scope != Scope.GLOBAL && scope != Scope.APPLICATION) {
            if (path == null || !path.startsWith("/")) {
                problems.add("path must start with '/' (only a GLOBAL policy may omit it)");
            }
        }
        if (algorithm == null) {
            problems.add("algorithm is required");
        }
        if (scope == null) {
            problems.add("scope is required");
        }
        if (algorithm == Algorithm.FIXED_WINDOW || algorithm == Algorithm.SLIDING_WINDOW
                || algorithm == Algorithm.SLIDING_WINDOW_COUNTER) {
            requirePositive(problems, "limit", limit);
            requireWindow(problems, window);
        }
        if (algorithm == Algorithm.TOKEN_BUCKET) {
            requirePositive(problems, "capacity", capacity);
            requireWindow(problems, refillInterval);
            if (cost != null && cost < 1) {
                problems.add("cost must be at least 1");
            }
        }
        if (algorithm == Algorithm.LEAKY_BUCKET) {
            requirePositive(problems, "drainRate", drainRate);
            requirePositive(problems, "queueCapacity", queueCapacity);
        }
        if (algorithm == Algorithm.CONCURRENCY_LIMIT) {
            requirePositive(problems, "maxConcurrent", maxConcurrent);
            requireWindow(problems, leaseDuration);
        }

        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
    }

    private static void requirePositive(List<String> problems, String field, Integer value) {
        if (value == null || value < 1) {
            problems.add(field + " must be at least 1");
        }
    }

    private static void requireWindow(List<String> problems, Duration value) {
        if (value == null || value.toMillis() < 1000) {
            problems.add("duration must be at least 1s");
        }
    }

    /** Convenience for building a document without hand-assembling 20 constructor arguments. */
    public static PolicyDocumentBuilder builder(String id) {
        return new PolicyDocumentBuilder(id);
    }

    /**
     * Rejects an update whose {@code version} is not exactly one greater than the stored version.
     * Kept for callers that want to check before reaching the store; the store enforces the same rule
     * atomically, which is the check that actually matters under concurrency.
     */
    public void requireVersionAdvance(long storedVersion) {
        if (version != storedVersion + 1) {
            throw new PolicyConflictException(storedVersion, version);
        }
    }
}
