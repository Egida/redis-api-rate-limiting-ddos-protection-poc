package com.example.ratelimit.policy;

/**
 * Rate-limiting algorithms selectable per policy.
 *
 * <p>Only {@link #FIXED_WINDOW} is wired to an enforcing strategy in this phase. The remaining
 * constants exist so the admin contract and the stored documents are forward-compatible, but
 * {@link #isImplemented()} is deliberately false for them and the admin API rejects a save that
 * selects one. That is the honest state: an enum value is not an implementation.
 */
public enum Algorithm {

    /** Counter per epoch-aligned window. Preserves the original POC semantics exactly. */
    FIXED_WINDOW(true),

    /** Rolling log of timestamps in a sorted set. Exact but memory grows with request rate. */
    SLIDING_WINDOW(false),

    /** Weighted current + previous window counters. Approximate; documented as such. */
    SLIDING_WINDOW_COUNTER(false),

    /** Capacity plus refill rate. Allows a burst of {@code capacity} then a sustained rate. */
    TOKEN_BUCKET(false),

    /** Queued shaping at a fixed drain rate. */
    LEAKY_BUCKET(false),

    /** Caps in-flight requests, independent of any time window. */
    CONCURRENCY_LIMIT(false);

    private final boolean implemented;

    Algorithm(boolean implemented) {
        this.implemented = implemented;
    }

    public boolean isImplemented() {
        return implemented;
    }
}
