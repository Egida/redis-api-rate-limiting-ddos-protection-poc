package com.example.ratelimit.policy;

import java.time.Instant;
import java.util.ArrayList;
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
 * Rate-limit exemptions live in their own Redis namespace, separate from normal policies,
 * so the enforcement path can check them before any quota charging.
 */
@Component
public class ExemptionStore {

    private static final Logger log = LoggerFactory.getLogger(ExemptionStore.class);

    static final String NS = "ratelimit:exemption:v1";
    static final String INDEX = NS + ":index";
    private static final String DOC_PREFIX = NS + ":doc:";

    private static final int MODE_CREATE = 0;
    private static final int MODE_UPDATE = 1;
    private static final int MODE_DELETE = 2;

    private static final int OK = 1;
    private static final int MISSING = -1;
    private static final int CONFLICT = -2;

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

    private static final RedisScript<List> READ_ALL = new DefaultRedisScript<>("""
            local ids = redis.call('SMEMBERS', KEYS[1])
            local docs = {}
            for i, id in ipairs(ids) do
              local doc = redis.call('HGET', 'ratelimit:exemption:v1:doc:' .. id, 'doc')
              if doc then
                table.insert(docs, doc)
              end
            end
            return docs
            """, List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ExemptionStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Optional<ExemptionDocument> find(String id) {
        try {
            var ops = redis.opsForHash();
            Object json = ops.get(docKey(id), "doc");
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json.toString(), ExemptionDocument.class));
        } catch (DataAccessException e) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis unavailable reading exemption " + id, e);
        } catch (Exception e) {
            throw new ManagedPolicyStore.PolicyStoreException("stored exemption " + id + " is not readable JSON", e);
        }
    }

    public List<ExemptionDocument> findAll() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rawDocs = redis.execute(READ_ALL, List.of(INDEX));
            if (rawDocs == null || rawDocs.isEmpty()) {
                return List.of();
            }
            var out = new ArrayList<ExemptionDocument>(rawDocs.size());
            for (Object raw : rawDocs) {
                if (raw != null) {
                    try {
                        out.add(mapper.readValue(raw.toString(), ExemptionDocument.class));
                    } catch (Exception e) {
                        log.warn("skipping unreadable exemption doc in findAll: {}", e.getMessage());
                    }
                }
            }
            out.sort((a, b) -> a.id().compareTo(b.id()));
            return out;
        } catch (DataAccessException e) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis unavailable listing exemptions", e);
        }
    }

    public ExemptionDocument save(ExemptionDocument document, ExemptionDocument existing, String actor) {
        document.validate();
        int mode = existing == null ? MODE_CREATE : MODE_UPDATE;

        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (Exception e) {
            throw new ManagedPolicyStore.PolicyStoreException("exemption could not be serialised", e);
        }

        String now = document.updatedAt().toString();
        String created = document.createdAt().toString();
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(document.id()), INDEX),
                    String.valueOf(mode), document.id(), json, String.valueOf(document.version()), now, created);
        } catch (DataAccessException e) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis unavailable writing exemption " + document.id(), e);
        }
        if (result == null || result.size() < 2) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis returned no result for the exemption write", null);
        }
        int code = result.get(0).intValue();
        if (code == MISSING) {
            throw new ManagedPolicyStore.PolicyNotFoundException(document.id());
        }
        if (code == CONFLICT) {
            throw new PolicyConflictException(result.get(1), document.version());
        }

        log.info("exemption {} {} by {} at version {}", document.id(),
                existing == null ? "created" : "updated", actor, document.version());
        return document;
    }

    public void delete(String id, String actor) {
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(id), INDEX),
                    String.valueOf(MODE_DELETE), id, "", "0", "", "");
        } catch (DataAccessException e) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis unavailable deleting exemption " + id, e);
        }
        if (result == null || result.size() < 2) {
            throw new ManagedPolicyStore.PolicyStoreUnavailableException("redis returned no result for the exemption delete", null);
        }
        if (result.get(0).intValue() == MISSING) {
            throw new ManagedPolicyStore.PolicyNotFoundException(id);
        }
        log.info("exemption {} deleted by {}", id, actor);
    }

    private static String docKey(String id) {
        return DOC_PREFIX + id;
    }
}
