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

    /**
     * Atomic multi-policy batch. KEYS[i] is a counter key; ARGV[(i-1)*2+1] its limit and
     * ARGV[(i-1)*2+2] its TTL in millis.
     *
     * <p>Phase one reads every counter and returns early on the first exhausted policy, charging
     * nothing. Phase two increments every counter only when all policies allow. Returns
     * {@code {0, blockedIndex1Based, pttl}} on denial or {@code {1, c1, t1, c2, t2, ...}} on success.
     *
     * <p>Single Redis instance only. Under Redis Cluster the keys would have to share a hash slot
     * (hash tags), because a script cannot span slots; this POC runs one Redis, so no tagging is
     * applied and no cross-slot claim is made.
     */
    private static final RedisScript<List> BATCH = new DefaultRedisScript<>("""
            local n = #KEYS
            for i = 1, n do
              local raw = redis.call('GET', KEYS[i])
              local count = 0
              if raw then
                count = tonumber(raw)
              end
              if count >= tonumber(ARGV[(i - 1) * 2 + 1]) then
                return {0, i, redis.call('PTTL', KEYS[i])}
              end
            end
            local out = {1}
            for i = 1, n do
              local count = redis.call('INCR', KEYS[i])
              if count == 1 then
                redis.call('PEXPIRE', KEYS[i], ARGV[(i - 1) * 2 + 2])
              end
              local ttl = redis.call('PTTL', KEYS[i])
              if ttl < 0 then
                redis.call('PEXPIRE', KEYS[i], ARGV[(i - 1) * 2 + 2])
                ttl = tonumber(ARGV[(i - 1) * 2 + 2])
              end
              out[#out + 1] = count
              out[#out + 1] = ttl
            end
            return out
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

    @Override
    public BatchDecision consumeAll(java.util.List<Charge> charges, long nowMillis) {
        if (charges.isEmpty()) {
            throw new IllegalArgumentException("consumeAll requires at least one charge");
        }
        var keys = new java.util.ArrayList<String>(charges.size());
        var args = new java.util.ArrayList<String>(charges.size() * 2);
        var ttls = new java.util.ArrayList<Long>(charges.size());
        for (Charge charge : charges) {
            long windowMillis = charge.policy().window().toMillis();
            long elapsed = Math.floorMod(nowMillis, windowMillis);
            long ttlMillis = (windowMillis - elapsed) + ttlGrace.toMillis();
            keys.add(key(charge.policy(), charge.identityType(), charge.identity(), nowMillis));
            args.add(String.valueOf(charge.policy().limit()));
            args.add(String.valueOf(ttlMillis));
            ttls.add(ttlMillis);
        }

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(BATCH, keys, args.toArray(new String[0]));
            if (result == null || result.isEmpty() || result.get(0) == null) {
                throw new RateLimitStoreUnavailableException("redis returned no batch decision", null);
            }
            if (result.get(0) == 0) {
                int blockedIndex = result.get(1).intValue() - 1;
                Charge blocked = charges.get(blockedIndex);
                long pttl = result.get(2);
                long effectiveTtl = pttl >= 0 ? pttl : ttls.get(blockedIndex);
                long retryAfter = Math.max(1, Math.ceilDiv(effectiveTtl, 1000));
                return new BatchDecision(blockedIndex,
                        RateLimitDecision.reject(blocked.policy().limit(), Duration.ofSeconds(retryAfter)));
            }
            // Success layout: {1, c1, t1, c2, t2, ...}. The governing decision is the first policy's,
            // matching the most-specific-first order the enforcer supplies.
            Charge governing = charges.get(0);
            long count = result.get(1);
            return new BatchDecision(-1, RateLimitDecision.allow(governing.policy().limit(),
                    (int) Math.max(0, governing.policy().limit() - count)));
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
