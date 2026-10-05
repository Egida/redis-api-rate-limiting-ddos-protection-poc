package com.example.ratelimit.policy;

/**
 * Rate-limiting algorithms selectable per policy.
 *
 * <p>Every constant below is wired to the atomic batch in {@code RedisRateLimitStore}: saving a policy
 * that selects one means it is enforced. The capabilities endpoint reports the same set, so the admin
 * UI can never offer more — or less — than the backend does.
 */
public enum Algorithm {

    /** Counter per epoch-aligned window. Preserves the original POC semantics exactly. */
    FIXED_WINDOW(true),

    /** Rolling log of timestamps in a sorted set. Exact but memory grows with request rate. */
    SLIDING_WINDOW(true),

    /** Weighted current + previous window counters. Approximate; documented as such. */
    SLIDING_WINDOW_COUNTER(true),

    /** Capacity plus refill rate. Allows a burst of {@code capacity} then a sustained rate. */
    TOKEN_BUCKET(true),

    /** Queued shaping at a fixed drain rate. */
    LEAKY_BUCKET(true),

    /** Caps in-flight requests, independent of any time window. */
    CONCURRENCY_LIMIT(true);

    private final boolean implemented;

    Algorithm(boolean implemented) {
        this.implemented = implemented;
    }

    public boolean isImplemented() {
        return implemented;
    }
}
