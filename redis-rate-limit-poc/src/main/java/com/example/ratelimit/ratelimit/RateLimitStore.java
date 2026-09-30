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

    /** Thrown to signal "store unavailable" as distinct from "rejected by policy". */
    class RateLimitStoreUnavailableException extends RuntimeException {
        public RateLimitStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
