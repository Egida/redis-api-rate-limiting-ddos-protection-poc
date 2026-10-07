package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Policies live in the same shared Redis as the counters, under a different namespace:
 * {@code ratelimit:policy:v1:*} for documents versus {@code rate-limit:v1:*} for counters. Namespacing
 * them apart is what lets a reset of counter state leave policy edits untouched, and vice versa.
 *
 * <p>Every mutation runs through one Lua script so the version check and the write cannot interleave.
 * Two admins saving the same policy concurrently means exactly one succeeds; the other gets
 * {@link PolicyConflictException}. Single Redis only: the script touches one document key and the
 * shared index key, which would need a hash tag to stay in one slot under Redis Cluster.
 */
@Component
public class ManagedPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(ManagedPolicyStore.class);

    static final String NS = "ratelimit:policy:v1";
    static final String INDEX = NS + ":index";
    static final String AUDIT = NS + ":audit";
    static final String SEEDED = NS + ":seeded";
    static final String META = NS + ":meta";
    private static final String DOC_PREFIX = NS + ":doc:";

    /** Modes for {@link #WRITE}. */
    private static final int MODE_CREATE = 0;
    private static final int MODE_UPDATE = 1;
    private static final int MODE_DELETE = 2;

    /** Result codes returned by {@link #WRITE}. */
    private static final int OK = 1;
    private static final int MISSING = -1;
    private static final int CONFLICT = -2;

    private static final int AUDIT_MAX_ENTRIES = 200;

    /**
     * Compare-and-set on (version, document). KEYS[1]=document hash, KEYS[2]=index set.
     * ARGV[1]=mode ARGV[2]=id ARGV[3]=json ARGV[4]=attemptedVersion ARGV[5]=nowIso
     * ARGV[6]=createdAtIso
     *
     * <p>Returns {code, storedVersion}. Rejected writes touch nothing.
     */
    private static final RedisScript<List> WRITE = new DefaultRedisScript<>("""
            local mode = tonumber(ARGV[1])
            local id = ARGV[2]
            local exists = redis.call('EXISTS', KEYS[1])

            if mode == 2 then
              if exists == 0 then return {-1, 0} end
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], id)
              return {1, 0}
            end

            if mode == 0 then
              -- Create must not clobber an existing id; the caller retries as an update.
              if exists == 1 then return {-1, tonumber(redis.call('HGET', KEYS[1], 'version') or '0')} end
            else
              if exists == 0 then return {-1, 0} end
              local stored = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
              if stored ~= tonumber(ARGV[4]) - 1 then return {-2, stored} end
            end

            redis.call('HSET', KEYS[1], 'doc', ARGV[3], 'version', ARGV[4], 'updatedAt', ARGV[5])
            redis.call('HSETNX', KEYS[1], 'createdAt', ARGV[6])
            redis.call('SADD', KEYS[2], id)
            return {1, tonumber(ARGV[4])}
            """, List.class);

    /** Atomic batch read of all policy documents in one Redis round trip. KEYS[1] = INDEX. */
    private static final RedisScript<List> READ_ALL_DOCS = new DefaultRedisScript<>("""
            local ids = redis.call('SMEMBERS', KEYS[1])
            local docs = {}
            for i, id in ipairs(ids) do
              local doc = redis.call('HGET', 'ratelimit:policy:v1:doc:' .. id, 'doc')
              if doc then
                table.insert(docs, doc)
              end
            end
            return docs
            """, List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ManagedPolicyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** True when a first-run seed has already happened, so admin edits are never overwritten. */
    public boolean isSeeded() {
        try {
            return Boolean.TRUE.equals(redis.hasKey(SEEDED));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable checking seeded marker", e);
        }
    }

    public void markSeeded() {
        try {
            redis.opsForValue().set(SEEDED, "1");
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable marking seeded", e);
        }
    }

    public Optional<PolicyDocument> find(String id) {
        try {
            var ops = redis.opsForHash();
            Object json = ops.get(docKey(id), "doc");
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json.toString(), PolicyDocument.class));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading policy " + id, e);
        } catch (Exception e) {
            throw new PolicyStoreException("stored policy " + id + " is not readable JSON", e);
        }
    }

    /** Every policy, ordered by id so the admin list is stable across instances. Executes in 1 Redis round trip. */
    public List<PolicyDocument> findAll() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rawDocs = redis.execute(READ_ALL_DOCS, List.of(INDEX));
            if (rawDocs == null || rawDocs.isEmpty()) {
                return List.of();
            }
            var out = new ArrayList<PolicyDocument>(rawDocs.size());
            for (Object raw : rawDocs) {
                if (raw != null) {
                    try {
                        out.add(mapper.readValue(raw.toString(), PolicyDocument.class));
                    } catch (Exception e) {
                        log.warn("skipping unreadable policy doc in findAll: {}", e.getMessage());
                    }
                }
            }
            out.sort((a, b) -> a.id().compareTo(b.id()));
            return out;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable listing policies", e);
        }
    }

    /**
     * Atomically stores {@code document} and appends an audit entry.
     *
     * <p>Create requires the id to be unused. Update requires {@code document.version()} to be exactly
     * the stored version plus one, which is what prevents a lost update.
     *
     * @param existing the currently stored policy, or null when creating
     */
    public PolicyDocument save(PolicyDocument document, PolicyDocument existing, String actor) {
        document.validate();
        int mode = existing == null ? MODE_CREATE : MODE_UPDATE;

        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (Exception e) {
            throw new PolicyStoreException("policy could not be serialised", e);
        }

        String now = document.updatedAt().toString();
        String created = document.createdAt().toString();
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(document.id()), INDEX),
                    String.valueOf(mode), document.id(), json, String.valueOf(document.version()), now, created);
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable writing policy " + document.id(), e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the policy write", null);
        }
        int code = result.get(0).intValue();
        if (code == MISSING) {
            throw new PolicyNotFoundException(document.id());
        }
        if (code == CONFLICT) {
            throw new PolicyConflictException(result.get(1), document.version());
        }

        record(actor, document.id(), existing == null ? "CREATE" : "UPDATE", document.version(),
                changedFields(existing, document));
        log.info("policy {} {} by {} at version {}", document.id(),
                existing == null ? "created" : "updated", actor, document.version());
        return document;
    }

    public void delete(String id, String actor, long resultingVersion) {
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(id), INDEX),
                    String.valueOf(MODE_DELETE), id, "", "0", "", "");
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable deleting policy " + id, e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the policy delete", null);
        }
        if (result.get(0).intValue() == MISSING) {
            throw new PolicyNotFoundException(id);
        }
        record(actor, id, "DELETE", resultingVersion, List.of());
        log.info("policy {} deleted by {}", id, actor);
    }

    /** Newest first, capped at {@link #AUDIT_MAX_ENTRIES} entries in Redis. */
    public List<AuditEntry> audit(int limit) {
        int capped = Math.max(1, Math.min(limit, AUDIT_MAX_ENTRIES));
        List<String> raw;
        try {
            raw = redis.opsForList().range(AUDIT, 0, capped - 1);
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading audit log", e);
        }
        if (raw == null) {
            return List.of();
        }
        var out = new ArrayList<AuditEntry>(raw.size());
        for (String json : raw) {
            try {
                out.add(mapper.readValue(json, AuditEntry.class));
            } catch (Exception e) {
                log.warn("skipping unreadable audit entry: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Records the audit entry in the same round trip style as the write, then trims the list so it
     * cannot grow without bound. Losing the oldest entries under heavy admin use is the intended
     * trade-off for a POC; a real deployment ships these to a durable log instead.
     */
    private void record(String actor, String policyId, String operation, long version,
            List<String> changedFields) {
        try {
            String json = mapper.writeValueAsString(new AuditEntry(Instant.now(), actor, policyId,
                    operation, version, changedFields));
            redis.opsForList().leftPush(AUDIT, json);
            redis.opsForList().trim(AUDIT, 0, AUDIT_MAX_ENTRIES - 1);
        } catch (DataAccessException e) {
            // The policy change already committed. Losing its audit line must not fail the save.
            log.warn("audit append failed for policy {}: {}", policyId, e.getMessage());
        } catch (Exception e) {
            log.warn("audit serialisation failed for policy {}: {}", policyId, e.getMessage());
        }
    }

    /** Field names whose values differ. Names only, never values. */
    private static List<String> changedFields(PolicyDocument before, PolicyDocument after) {
        if (before == null) {
            return List.of("id", "name", "method", "path", "algorithm", "scope", "enabled");
        }
        Set<String> changed = new LinkedHashSet<>();
        if (!java.util.Objects.equals(before.name(), after.name())) changed.add("name");
        if (!java.util.Objects.equals(before.method(), after.method())) changed.add("method");
        if (!java.util.Objects.equals(before.path(), after.path())) changed.add("path");
        if (before.algorithm() != after.algorithm()) changed.add("algorithm");
        if (before.scope() != after.scope()) changed.add("scope");
        if (!java.util.Objects.equals(before.window(), after.window())) changed.add("window");
        if (!java.util.Objects.equals(before.limit(), after.limit())) changed.add("limit");
        if (!java.util.Objects.equals(before.capacity(), after.capacity())) changed.add("capacity");
        if (!java.util.Objects.equals(before.refillInterval(), after.refillInterval())) changed.add("refillInterval");
        if (!java.util.Objects.equals(before.cost(), after.cost())) changed.add("cost");
        if (!java.util.Objects.equals(before.drainRate(), after.drainRate())) changed.add("drainRate");
        if (!java.util.Objects.equals(before.queueCapacity(), after.queueCapacity())) changed.add("queueCapacity");
        if (!java.util.Objects.equals(before.maxConcurrent(), after.maxConcurrent())) changed.add("maxConcurrent");
        if (!java.util.Objects.equals(before.leaseDuration(), after.leaseDuration())) changed.add("leaseDuration");
        if (before.enabled() != after.enabled()) changed.add("enabled");
        if (before.onRedisError() != after.onRedisError()) changed.add("onRedisError");
        return List.copyOf(changed);
    }

    /** Deletes every policy, the audit list and the seeded marker. Intentional local reset only. */
    public int reset(String actor) {
        try {
            Collection<String> ids = redis.opsForSet().members(INDEX);
            int removed = 0;
            if (ids != null) {
                for (String id : ids) {
                    redis.delete(docKey(id));
                    removed++;
                }
            }
            redis.delete(List.of(INDEX, AUDIT, SEEDED, META));
            log.warn("policy store reset by {}: {} policies removed", actor, removed);
            return removed;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable during policy store reset", e);
        }
    }

    private static String docKey(String id) {
        return DOC_PREFIX + id;
    }

    /** Redis unreachable while reading or writing policy state. Maps to 503. */
    public static class PolicyStoreUnavailableException extends RuntimeException {
        public PolicyStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** No policy with that id. Maps to 404. */
    public static class PolicyNotFoundException extends RuntimeException {
        public PolicyNotFoundException(String id) {
            super("no policy with id '" + id + "'");
        }
    }

    /** Generic store failure, e.g. unreadable stored JSON. Maps to 500. */
    public static class PolicyStoreException extends RuntimeException {
        public PolicyStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
