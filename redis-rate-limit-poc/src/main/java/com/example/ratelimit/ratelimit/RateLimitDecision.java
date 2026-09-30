package com.example.ratelimit.ratelimit;

import java.time.Duration;

/**
 * Outcome of one quota check.
 *
 * @param allowed   whether the request may proceed
 * @param limit     the policy limit for the current window
 * @param remaining quota left in the current window
 * @param retryAfter seconds until the current window resets; 0 when allowed
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, Duration retryAfter) {

    public static RateLimitDecision allow(int limit, int remaining) {
        return new RateLimitDecision(true, limit, remaining, Duration.ZERO);
    }

    public static RateLimitDecision reject(int limit, Duration retryAfter) {
        return new RateLimitDecision(false, limit, 0, retryAfter);
    }
}
