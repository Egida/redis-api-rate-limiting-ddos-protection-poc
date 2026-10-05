package com.example.ratelimit.policy;

/**
 * A save was attempted against a policy that had already moved on. Maps to HTTP 409 so the admin UI
 * can show the stored version and let the operator re-apply their edit deliberately.
 */
public class PolicyConflictException extends RuntimeException {

    private final long storedVersion;
    private final long attemptedVersion;

    public PolicyConflictException(long storedVersion, long attemptedVersion) {
        super("policy has version " + storedVersion + " but the edit was based on version "
                + attemptedVersion + "; reload and re-apply the change");
        this.storedVersion = storedVersion;
        this.attemptedVersion = attemptedVersion;
    }

    public long storedVersion() {
        return storedVersion;
    }

    public long attemptedVersion() {
        return attemptedVersion;
    }
}
