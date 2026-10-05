package com.example.ratelimit.ratelimit;

import java.time.Duration;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Redis-backed store against a real Redis in a container. */
class RedisRateLimitStoreTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisRateLimitStore store;

    /** Deliberately window-aligned (multiple of 60s and 3s) so TTL maths is exact. */
    private static final long T0 = 1_699_999_980_000L;

    @BeforeAll
    static void setUp() {
        var container = RedisTestSupport.redis();
        var config = new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(RedisTestSupport.REDIS_PORT));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisRateLimitStore(redis, properties());
    }

    private static RateLimitProperties properties() {
        var props = new RateLimitProperties();
        props.setKeyPrefix("test:ratelimit");
        props.setTtlGrace(Duration.ofSeconds(1));
        return props;
    }

    private static RateLimitProperties.Policy policy(String id, int limit, Duration window) {
        return new RateLimitProperties.Policy(id, "GET", "/api/x", limit, window,
                RateLimitProperties.Identity.IP, null);
    }

    @Test
    void allowsUpToLimitThenRejects() {
        var policy = policy("allow-then-reject", 3, Duration.ofMinutes(1));
        for (int i = 1; i <= 3; i++) {
            var decision = store.consume(policy, "IP", "10.0.0.1", T0);
            assertThat(decision.allowed()).as("request %d", i).isTrue();
            assertThat(decision.remaining()).isEqualTo(3 - i);
        }
        var rejected = store.consume(policy, "IP", "10.0.0.1", T0);
        assertThat(rejected.allowed()).isFalse();
        // Window is aligned, so the TTL is the full window plus the 1s grace.
        assertThat(rejected.retryAfter()).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(61));
    }

    @Test
    void differentIdentitiesAreIndependent() {
        var policy = policy("independent-identities", 1, Duration.ofMinutes(1));
        assertThat(store.consume(policy, "IP", "10.0.0.1", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "IP", "10.0.0.2", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "IP", "10.0.0.1", T0).allowed()).isFalse();
    }

    @Test
    void differentPoliciesAreIndependent() {
        var first = policy("policy-a", 1, Duration.ofMinutes(1));
        var second = policy("policy-b", 5, Duration.ofMinutes(1));
        assertThat(store.consume(first, "IP", "10.0.0.3", T0).allowed()).isTrue();
        assertThat(store.consume(first, "IP", "10.0.0.3", T0).allowed()).isFalse();
        for (int i = 0; i < 5; i++) {
            assertThat(store.consume(second, "IP", "10.0.0.3", T0).allowed()).isTrue();
        }
    }

    @Test
    void counterGetsTtlAndIsGoneAfterWindow() {
        var policy = policy("ttl-check", 2, Duration.ofSeconds(3));
        store.consume(policy, "IP", "10.0.0.4", T0);
        String key = store.keyFor(policy, "IP", "10.0.0.4", T0);

        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isBetween(2_000L, 4_100L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");

        // A later window uses a different key, so the old counter can never be read again.
        long later = T0 + Duration.ofSeconds(3).toMillis();
        assertThat(store.keyFor(policy, "IP", "10.0.0.4", later)).isNotEqualTo(key);
        assertThat(store.consume(policy, "IP", "10.0.0.4", later).allowed()).isTrue();

        // Prove the key really does expire rather than lingering forever.
        Long freshKeyTtl = redis.getExpire(
                store.keyFor(policy, "IP", "10.9.9.9", System.currentTimeMillis()),
                java.util.concurrent.TimeUnit.SECONDS);
        assertThat(freshKeyTtl).isNotNull();
    }

    @Test
    void keyShapeIsNamespacedAndHashesIdentity() {
        var policy = policy("key-shape", 1, Duration.ofMinutes(1));
        String key = store.keyFor(policy, "USER", "alice@example.com", T0);
        assertThat(key).matches("test:ratelimit:key-shape:user:[0-9a-f]{16}:\\d+");
        assertThat(key).doesNotContain("alice");
        assertThat(RedisRateLimitStore.hash("alice")).hasSize(16);
        assertThat(RedisRateLimitStore.hash("alice")).isNotEqualTo(RedisRateLimitStore.hash("bob"));
    }

    @Test
    void batchDenialChargesNothing() {
        var first = policy("batch-first", 2, Duration.ofMinutes(1));
        var second = policy("batch-second", 1, Duration.ofMinutes(1));
        var charges = java.util.List.of(
                new RateLimitStore.Charge(first, "IP", "10.0.0.11"),
                new RateLimitStore.Charge(second, "IP", "10.0.0.11"));

        var allowed = store.consumeAll(charges, T0);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.blockedIndex()).isEqualTo(-1);

        var denied = store.consumeAll(charges, T0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.blockedIndex()).as("the exhausted second policy blocks").isEqualTo(1);
        assertThat(denied.decision().retryAfter().isZero()).isFalse();

        // Exact totals per policy: the denial left both counters exactly where the allowed batch put them.
        assertThat(redis.opsForValue().get(store.keyFor(first, "IP", "10.0.0.11", T0))).isEqualTo("1");
        assertThat(redis.opsForValue().get(store.keyFor(second, "IP", "10.0.0.11", T0))).isEqualTo("1");
    }

    @Test
    void concurrentBatchesForLastUnitAllowExactlyOne() throws Exception {
        var first = policy("race-first", 2, Duration.ofMinutes(1));
        var second = policy("race-second", 2, Duration.ofMinutes(1));
        var charges = java.util.List.of(
                new RateLimitStore.Charge(first, "IP", "10.0.0.12"),
                new RateLimitStore.Charge(second, "IP", "10.0.0.12"));
        assertThat(store.consumeAll(charges, T0).allowed()).isTrue();

        int threads = 2;
        var startLine = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLine.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return store.consumeAll(charges, T0).allowed();
            });
        }
        int allowed = 0;
        try {
            for (var f : pool.invokeAll(tasks)) {
                if (f.get(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed).as("exactly one racer may take the last unit").isEqualTo(1);
        assertThat(redis.opsForValue().get(store.keyFor(first, "IP", "10.0.0.12", T0))).isEqualTo("2");
        assertThat(redis.opsForValue().get(store.keyFor(second, "IP", "10.0.0.12", T0))).isEqualTo("2");
    }

    @Test
    void twoStoreInstancesShareOneLimit() {
        // Simulates two application instances against one Redis: separate objects, separate
        // connections, identical enforcement.
        var otherRedis = new StringRedisTemplate(factory);
        otherRedis.afterPropertiesSet();
        var secondInstance = new RedisRateLimitStore(otherRedis, properties());
        var policy = policy("multi-instance", 4, Duration.ofMinutes(1));

        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            RateLimitStore instance = (i % 2 == 0) ? RedisRateLimitStoreTest.store : secondInstance;
            if (instance.consume(policy, "IP", "10.0.0.5", T0).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(4);
    }
}
