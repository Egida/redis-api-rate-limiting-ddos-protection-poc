package com.example.ratelimit.policy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Server-side API-key registry.
 *
 * <p>Keys arrive over {@code X-API-Key} and are resolved here to an owner and a tier. Only the
 * SHA-256 digest is ever stored, logged, metered or returned: the raw key exists exactly once, in the
 * creation response, and cannot be recovered afterwards. Losing it means creating a new key.
 *
 * <p>Storage is one Redis hash per key under {@code ratelimit:apikey:v1:*}, plus an index set. No TTL:
 * keys are durable until revoked, exactly like policies.
 *
 * <p>Tier is administrative metadata in this build (owner grouping, audit context). Per-tier rate
 * differences are expressed as separate policies, never by trusting a caller-supplied plan.
 *
 * @param keyId first 8 hex chars of the digest: safe to display, useless for forging
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);

    static final String NS = "ratelimit:apikey:v1";
    private static final String INDEX = NS + ":index";
    private static final String KEY_PREFIX = "rg_";

    public record ApiKey(String keyId, String owner, String tier, boolean enabled, Instant createdAt) {
    }

    /** Creation result. The raw key is present exactly once and must be shown once, then forgotten. */
    public record CreatedKey(ApiKey metadata, String rawKey) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyRegistry(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Issues a key. Returns the metadata plus the raw secret, which is never stored. */
    public CreatedKey create(String owner, String tier, String actor) {
        if (owner == null || owner.isBlank()) {
            throw new PolicyValidationException(List.of("owner is required"));
        }
        byte[] secret = new byte[24];
        random.nextBytes(secret);
        String rawKey = KEY_PREFIX + HexFormat.of().formatHex(secret);
        String digest = digest(rawKey);
        var key = new ApiKey(digest.substring(0, 8), owner.trim(),
                tier == null || tier.isBlank() ? "default" : tier.trim(), true, Instant.now());
        try {
            String json = mapper.writeValueAsString(key);
            redis.opsForHash().put(docKey(digest), "key", json);
            redis.opsForSet().add(INDEX, digest);
        } catch (Exception e) {
            throw new ManagedPolicyStore.PolicyStoreException("api key could not be stored", e);
        }
        log.info("api key {}... created for owner '{}' by {}", key.keyId(), key.owner(), actor);
        return new CreatedKey(key, rawKey);
    }

    /** Resolves a presented key to its record. Empty for unknown, revoked or disabled keys. */
    public Optional<ApiKey> resolve(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Object json = redis.opsForHash().get(docKey(digest(rawKey)), "key");
        if (json == null) {
            return Optional.empty();
        }
        try {
            ApiKey key = mapper.readValue(json.toString(), ApiKey.class);
            return key.enabled() ? Optional.of(key) : Optional.empty();
        } catch (Exception e) {
            log.warn("skipping unreadable api key record: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<ApiKey> list() {
        var digests = redis.opsForSet().members(INDEX);
        if (digests == null) {
            return List.of();
        }
        var out = new ArrayList<ApiKey>();
        for (String d : digests.stream().sorted().toList()) {
            Object json = redis.opsForHash().get(docKey(d), "key");
            if (json == null) {
                continue;
            }
            try {
                out.add(mapper.readValue(json.toString(), ApiKey.class));
            } catch (Exception e) {
                log.warn("skipping unreadable api key record: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Revokes by key id prefix. Returns false when nothing matched. */
    public boolean revoke(String keyId, String actor) {
        var digests = redis.opsForSet().members(INDEX);
        if (digests == null) {
            return false;
        }
        boolean removed = false;
        for (String d : digests) {
            if (d.startsWith(keyId.toLowerCase())) {
                redis.delete(docKey(d));
                redis.opsForSet().remove(INDEX, d);
                removed = true;
            }
        }
        if (removed) {
            log.info("api key {}... revoked by {}", keyId, actor);
        }
        return removed;
    }

    static String digest(String rawKey) {
        return RedisDigest.sha256Hex(rawKey);
    }

    private static String docKey(String digest) {
        return NS + ":" + digest;
    }
}
