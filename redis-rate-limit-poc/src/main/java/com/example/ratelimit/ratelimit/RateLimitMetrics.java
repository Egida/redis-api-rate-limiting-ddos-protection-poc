package com.example.ratelimit.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality counters. Labels are only ever {@code outcome} (allowed/rejected/error),
 * {@code policy} (ids from configuration, so the set is finite) and {@code identity} (ip/user).
 * Never a client IP or user id.
 */
@Component
public class RateLimitMetrics {

    public enum Outcome {
        ALLOWED, REJECTED, ERROR
    }

    private final MeterRegistry registry;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Outcome outcome, String policyId, String identityType) {
        Counter.builder("ratelimit.requests")
                .description("Rate limit decisions")
                .tag("outcome", outcome.name().toLowerCase())
                .tag("policy", policyId)
                .tag("identity", identityType.toLowerCase())
                .register(registry)
                .increment();
    }
}
