package com.example.ratelimit.ratelimit;

import com.example.ratelimit.config.RateLimitProperties.Policy;

/**
 * The single seam between HTTP handling and Redis. Keeping it an interface lets policy matching,
 * the filter, failure behaviour and concurrency be tested without a servlet container or Redis.
 */
public interface RateLimitStore {

    /**
     * Atomically count this request against {@code policy} for {@code identity} in the window that
     * contains {@code nowMillis}, and report the decision.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    RateLimitDecision consume(Policy policy, String identityType, String identity, long nowMillis);

    /**
     * Report the decision for {@code policy} and {@code identity} without spending quota.
     *
     * <p>Used for multi-policy preflight: every applicable policy is peeked before any of them is
     * consumed, so a denial does not leave earlier policies charged. Implementations must not create
     * quota state or change counters here.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    RateLimitDecision peek(Policy policy, String identityType, String identity, long nowMillis);

    /** Thrown to signal "store unavailable" as distinct from "rejected by policy". */
    class RateLimitStoreUnavailableException extends RuntimeException {
        public RateLimitStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
