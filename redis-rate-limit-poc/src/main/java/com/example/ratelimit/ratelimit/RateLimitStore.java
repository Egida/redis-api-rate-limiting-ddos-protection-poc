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

    /** One policy charge inside an atomic batch: same arguments as {@link #consume}. */
    record Charge(Policy policy, String identityType, String identity) {
    }

    /**
     * Outcome of one atomic batch.
     *
     * @param blockedIndex position of the denying charge in the input list, or -1 when all allowed
     * @param decision the denying charge's decision (with retry info), or the governing charge's
     *                 decision when everything was allowed
     */
    record BatchDecision(int blockedIndex, RateLimitDecision decision) {

        public boolean allowed() {
            return blockedIndex < 0;
        }
    }

    /**
     * Decides and charges every policy atomically: either all counters move or none do.
     *
     * <p>The default does sequential peek-then-commit, which still leaves the check/commit race open
     * for stores that cannot do better. {@link RedisRateLimitStore} overrides this with a single Lua
     * script so the check and the charge are one Redis-side operation.
     *
     * @throws RateLimitStoreUnavailableException when the backing store cannot be reached
     */
    default BatchDecision consumeAll(java.util.List<Charge> charges, long nowMillis) {
        if (charges.isEmpty()) {
            throw new IllegalArgumentException("consumeAll requires at least one charge");
        }
        for (int i = 0; i < charges.size(); i++) {
            var charge = charges.get(i);
            RateLimitDecision preflight = peek(charge.policy(), charge.identityType(), charge.identity(),
                    nowMillis);
            if (!preflight.allowed()) {
                return new BatchDecision(i, preflight);
            }
        }
        RateLimitDecision governing = null;
        for (int i = 0; i < charges.size(); i++) {
            var charge = charges.get(i);
            RateLimitDecision decision = consume(charge.policy(), charge.identityType(),
                    charge.identity(), nowMillis);
            if (governing == null) {
                governing = decision;
            }
            if (!decision.allowed()) {
                return new BatchDecision(i, decision);
            }
        }
        return new BatchDecision(-1, governing);
    }

    /** Thrown to signal "store unavailable" as distinct from "rejected by policy". */
    class RateLimitStoreUnavailableException extends RuntimeException {
        public RateLimitStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
