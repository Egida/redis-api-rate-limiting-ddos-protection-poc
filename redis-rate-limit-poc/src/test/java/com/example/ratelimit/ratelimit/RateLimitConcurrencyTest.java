package com.example.ratelimit.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Parallel requests must not be able to overshoot the allowance. */
class RateLimitConcurrencyTest {

    private static LettuceConnectionFactory factory;
    private static RedisRateLimitStore store;

    @BeforeAll
    static void setUp() {
        var container = RedisTestSupport.redis();
        var config = new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(RedisTestSupport.REDIS_PORT));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisRateLimitStore(redis, properties());
    }

    private static RateLimitProperties properties() {
        var props = new RateLimitProperties();
        props.setKeyPrefix("test:concurrency");
        props.setTtlGrace(Duration.ofSeconds(1));
        return props;
    }

    @Test
    void exactAllowanceUnderParallelLoad() throws Exception {
        int limit = 100;
        int requests = 400;
        int threads = 40;
        var policy = new RateLimitProperties.Policy("concurrency", "GET", "/api/products", limit,
                Duration.ofMinutes(5), RateLimitProperties.Identity.IP, null);

        var allowed = new AtomicInteger();
        var rejected = new AtomicInteger();
        var startLine = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();

        // Four distinct client IPs, each with its own allowance, all hitting one store at once.
        for (int i = 0; i < requests; i++) {
            String ip = "10.1.0." + (i % 4);
            tasks.add(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                if (store.consume(policy, "IP", ip, 1_700_000_000_000L).allowed()) {
                    allowed.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }

        try {
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).as("exactly 4 IPs x %d", limit).isEqualTo(limit * 4);
        assertThat(rejected.get()).isEqualTo(requests - limit * 4);
    }
}
