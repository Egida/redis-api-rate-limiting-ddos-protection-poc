package com.example.ratelimit.policy;

import java.time.Instant;
import java.util.List;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreException;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreUnavailableException;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Copies the YAML policies into Redis once, on first run against an empty store.
 *
 * <p>The guard is a separate {@code seeded} marker rather than "is the store empty", because an admin
 * may legitimately delete every policy and must not have them silently reappear on the next restart.
 * Seeding runs only when the marker is absent, and {@link ManagedPolicyStore#reset(String)} clears the
 * marker to make a reseed an explicit act.
 *
 * <p>If Redis is unreachable at startup the application still boots; the limiter's own fail-open or
 * fail-closed behaviour then applies per policy. Failing startup here would turn a Redis outage into
 * a boot loop, which is a worse failure than not having migrated the seeds yet.
 */
@Component
@Order(100)
public class PolicySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicySeeder.class);

    private final ManagedPolicyStore store;
    private final RateLimitProperties properties;

    public PolicySeeder(ManagedPolicyStore store, RateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("system");
    }

    /**
     * Migrates the YAML policies if they have never been seeded.
     *
     * @return number of policies written; 0 when seeding was skipped or already done
     */
    public int seed(String actor) {
        try {
            if (store.isSeeded()) {
                log.info("managed policy store already seeded; leaving admin edits untouched");
                return 0;
            }
            List<RateLimitProperties.Policy> seeds = properties.getPolicies();
            if (seeds.isEmpty()) {
                log.warn("no rate-limit.policies configured and none stored; nothing to seed");
                store.markSeeded();
                return 0;
            }
            Instant now = Instant.now();
            int written = 0;
            for (RateLimitProperties.Policy seed : seeds) {
                Scope scope = seed.identity() == RateLimitProperties.Identity.USER ? Scope.USER : Scope.IP;
                PolicyDocument document = PolicyDocument.builder(seed.id())
                        .name(seed.id())
                        .route(seed.method(), seed.path())
                        .algorithm(Algorithm.FIXED_WINDOW)
                        .scope(scope)
                        .window(seed.window(), seed.limit())
                        .onRedisError(seed.onRedisError())
                        .enabled(true)
                        .version(1)
                        .timestamps(now, now)
                        .updatedBy(actor)
                        .build();
                store.save(document, null, actor);
                written++;
            }
            store.markSeeded();
            log.info("seeded {} policies into Redis from application.yml; further edits are admin-owned",
                    written);
            return written;
        } catch (PolicyStoreUnavailableException e) {
            log.warn("Redis unavailable during policy seed; limiter will apply its configured failure "
                    + "mode until policies are seeded. Cause: {}", e.getMessage());
            return 0;
        } catch (PolicyStoreException e) {
            log.error("policy seed failed: {}", e.getMessage());
            return 0;
        }
    }
}
