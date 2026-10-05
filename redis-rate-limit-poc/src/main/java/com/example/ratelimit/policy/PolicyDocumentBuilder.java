package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;

import com.example.ratelimit.config.RateLimitProperties.FailureMode;

/**
 * Fluent builder for {@link PolicyDocument}.
 *
 * <p>The record has 21 components; without this every construction site is a wall of nulls. Only the
 * fields relevant to a given algorithm are set, and {@link PolicyDocument#validate()} still runs on
 * save, so the builder cannot produce a policy the store would accept but that makes no sense.
 */
public final class PolicyDocumentBuilder {

    private final String id;
    private String name;
    private String method = "ANY";
    private String path;
    private Algorithm algorithm = Algorithm.FIXED_WINDOW;
    private Scope scope = Scope.ENDPOINT;
    private Duration window;
    private Integer limit;
    private Integer capacity;
    private Duration refillInterval;
    private Integer cost;
    private Integer drainRate;
    private Integer queueCapacity;
    private Integer maxConcurrent;
    private Duration leaseDuration;
    private boolean enabled = true;
    private FailureMode onRedisError;
    private long version = 1;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private String updatedBy;

    PolicyDocumentBuilder(String id) {
        this.id = id;
        this.name = id;
    }

    public PolicyDocumentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public PolicyDocumentBuilder route(String method, String path) {
        this.method = method;
        this.path = path;
        return this;
    }

    public PolicyDocumentBuilder algorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public PolicyDocumentBuilder scope(Scope scope) {
        this.scope = scope;
        return this;
    }

    public PolicyDocumentBuilder window(Duration window, Integer limit) {
        this.window = window;
        this.limit = limit;
        return this;
    }

    public PolicyDocumentBuilder bucket(Integer capacity, Duration refillInterval, Integer cost) {
        this.capacity = capacity;
        this.refillInterval = refillInterval;
        this.cost = cost;
        return this;
    }

    public PolicyDocumentBuilder leaky(Integer drainRate, Integer queueCapacity) {
        this.drainRate = drainRate;
        this.queueCapacity = queueCapacity;
        return this;
    }

    public PolicyDocumentBuilder concurrency(Integer maxConcurrent, Duration leaseDuration) {
        this.maxConcurrent = maxConcurrent;
        this.leaseDuration = leaseDuration;
        return this;
    }

    public PolicyDocumentBuilder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public PolicyDocumentBuilder onRedisError(FailureMode onRedisError) {
        this.onRedisError = onRedisError;
        return this;
    }

    public PolicyDocumentBuilder version(long version) {
        this.version = version;
        return this;
    }

    public PolicyDocumentBuilder timestamps(Instant createdAt, Instant updatedAt) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        return this;
    }

    public PolicyDocumentBuilder updatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
        return this;
    }

    public PolicyDocument build() {
        return new PolicyDocument(id, name, method, path, algorithm, scope, window, limit, capacity,
                refillInterval, cost, drainRate, queueCapacity, maxConcurrent, leaseDuration, enabled,
                onRedisError, version, createdAt, updatedAt, updatedBy);
    }
}
