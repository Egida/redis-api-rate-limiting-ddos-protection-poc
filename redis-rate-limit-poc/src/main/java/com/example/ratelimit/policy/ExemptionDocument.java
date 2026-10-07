package com.example.ratelimit.policy;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A rate-limit exemption: matching requests bypass all rate-limit policies.
 *
 * <p>Stored in a separate Redis namespace from normal policies so that exemption
 * evaluation can happen before any quota charging.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExemptionDocument(
        String id,
        String name,
        String method,
        String path,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {

    public void validate() {
        var problems = new java.util.ArrayList<String>();
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            problems.add("id must match [a-z0-9][a-z0-9-]{0,62}");
        }
        if (method == null || !method.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY")) {
            problems.add("method must be an HTTP verb or ANY");
        }
        if (path == null || !path.startsWith("/")) {
            problems.add("path must start with '/'");
        }
        if (path != null && path.equals("/**")) {
            problems.add("path /** is too broad; use a specific route");
        }
        if (path != null && (path.startsWith("/api/admin") || path.startsWith("/actuator"))) {
            problems.add("exemptions cannot target admin or actuator routes");
        }
        if (!problems.isEmpty()) {
            throw new PolicyValidationException(problems);
        }
    }

    public static ExemptionDocumentBuilder builder(String id) {
        return new ExemptionDocumentBuilder(id);
    }
}
