package com.example.ratelimit.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis, decided entirely inside one Lua script.
 *
 * <p>The script does INCR + conditional PEXPIRE + TTL read as a single Redis-side operation, so two
 * app instances cannot both observe the pre-increment value, and a window can never be created
 * without an expiry. Fixed window means a client may fire up to 2x the limit across a window
 * boundary; that is accepted and documented for this POC.
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {

    /** Returns {count, pttlMillis}. ARGV[1] = ttlMillis. */
    private static final RedisScript<List> CONSUME = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """, List.class);

    /** Read-only counterpart: returns {count, pttlMillis} without creating or changing quota state. */
    private static final RedisScript<List> PEEK = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            local count = 0
            if raw then
              count = tonumber(raw)
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttlGrace;

    public RedisRateLimitStore(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.keyPrefix = properties.getKeyPrefix();
        this.ttlGrace = properties.getTtlGrace();
    }

    @Override
    public RateLimitDecision consume(Policy policy, String identityType, String identity, long nowMillis) {
        long windowMillis = policy.window().toMillis();
        long elapsed = Math.floorMod(nowMillis, windowMillis);
        long ttlMillis = (windowMillis - elapsed) + ttlGrace.toMillis();
        String key = key(policy, identityType, identity, nowMillis);

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(CONSUME, List.of(key), String.valueOf(ttlMillis));
            if (result == null || result.size() < 2 || result.get(0) == null || result.get(1) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no decision for " + key, null);
            }
            long count = result.get(0);
            if (count <= policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), (int) Math.max(0, policy.limit() - count));
            }
            // Retry-After comes from the live TTL, so it always covers the rest of the real window.
            long retryAfter = Math.max(1, Math.ceilDiv(result.get(1), 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    @Override
    public RateLimitDecision peek(Policy policy, String identityType, String identity, long nowMillis) {
        long windowMillis = policy.window().toMillis();
        long elapsed = Math.floorMod(nowMillis, windowMillis);
        long expectedTtlMillis = (windowMillis - elapsed) + ttlGrace.toMillis();
        String key = key(policy, identityType, identity, nowMillis);

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(PEEK, List.of(key));
            if (result == null || result.size() < 2 || result.get(0) == null || result.get(1) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no peek for " + key, null);
            }
            long count = result.get(0);
            long ttl = result.get(1);
            if (ttl == -2) {
                return RateLimitDecision.allow(policy.limit(), policy.limit());
            }
            if (count < policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), (int) Math.max(0, policy.limit() - count));
            }
            long effectiveTtl = ttl >= 0 ? ttl : expectedTtlMillis;
            long retryAfter = Math.max(1, Math.ceilDiv(effectiveTtl, 1000));
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(retryAfter));
        } catch (DataAccessException e) {
            throw new RateLimitStoreUnavailableException("redis unavailable", e);
        }
    }

    /** sha-256 hex, first 16 chars: identifiers stay private and keys stay fixed width. */
    static String hash(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The one place the key layout is defined. The window id keeps windows from bleeding together. */
    public String keyFor(Policy policy, String identityType, String identity, long nowMillis) {
        return key(policy, identityType, identity, nowMillis);
    }

    private String key(Policy policy, String identityType, String identity, long nowMillis) {
        return "%s:%s:%s:%s:%d".formatted(keyPrefix, policy.id(), identityType.toLowerCase(),
                hash(identity), Math.floorDiv(nowMillis, policy.window().toMillis()));
    }
}
