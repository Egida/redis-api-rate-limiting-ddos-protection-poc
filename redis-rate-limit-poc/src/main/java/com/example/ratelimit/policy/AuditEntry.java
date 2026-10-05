package com.example.ratelimit.policy;

import java.time.Instant;

/**
 * One recorded administration action.
 *
 * <p>Records who, which policy, what operation, when, and the <em>names</em> of the fields that
 * changed. Field values are deliberately not recorded for identity or secret-bearing fields, and no
 * credential or raw API key ever reaches this record.
 *
 * @param changedFields field names only, e.g. {@code [limit, window]}
 */
public record AuditEntry(
        Instant at,
        String actor,
        String policyId,
        String operation,
        long resultingVersion,
        java.util.List<String> changedFields) {
}
