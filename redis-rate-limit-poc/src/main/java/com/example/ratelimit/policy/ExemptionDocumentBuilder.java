package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;

public final class ExemptionDocumentBuilder {

    private final String id;
    private String name;
    private String method = "ANY";
    private String path;
    private boolean enabled = true;
    private long version = 1;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private String updatedBy;

    ExemptionDocumentBuilder(String id) {
        this.id = id;
        this.name = id;
    }

    public ExemptionDocumentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ExemptionDocumentBuilder route(String method, String path) {
        this.method = method;
        this.path = path;
        return this;
    }

    public ExemptionDocumentBuilder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public ExemptionDocumentBuilder version(long version) {
        this.version = version;
        return this;
    }

    public ExemptionDocumentBuilder timestamps(Instant createdAt, Instant updatedAt) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        return this;
    }

    public ExemptionDocumentBuilder updatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
        return this;
    }

    public ExemptionDocument build() {
        return new ExemptionDocument(id, name, method, path, enabled, version, createdAt, updatedAt, updatedBy);
    }
}
