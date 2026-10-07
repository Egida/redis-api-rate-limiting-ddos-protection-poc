package com.example.ratelimit.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.ratelimit.RateLimitDecision;
import com.example.ratelimit.ratelimit.RateLimitFilter;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import com.example.ratelimit.ratelimit.RateLimitMetrics;
import com.example.ratelimit.ratelimit.RateLimitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting policy semantics through the real filter with an in-memory store double.
 *
 * <p>Proves the wiring the Redis tests cannot: that a GLOBAL policy charges one shared quota no
 * matter which client arrives, and that one USER policy on a wide route pattern spans endpoints.
 * The atomicity itself is proven against real Redis in {@code RedisRateLimitStoreTest}; this class
 * proves scope resolution feeds the batch the right identities.
 */
class PolicyCompositionTest {

    /** Fixed-window stand-in keyed exactly like the Redis layout: policy, scope type, identity. */
    private static final class TrackingStore implements RateLimitStore {
        final Map<String, Integer> counts = new HashMap<>();
        int consumes;

        private static String key(RateLimitProperties.Policy policy, String type, String identity) {
            return policy.id() + "|" + type + "|" + identity;
        }

        @Override
        public RateLimitDecision peek(RateLimitProperties.Policy policy, String type, String identity,
                long now) {
            int count = counts.getOrDefault(key(policy, type, identity), 0);
            if (count < policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(60));
        }

        @Override
        public RateLimitDecision consume(RateLimitProperties.Policy policy, String type, String identity,
                long now) {
            consumes++;
            int count = counts.merge(key(policy, type, identity), 1, Integer::sum);
            if (count <= policy.limit()) {
                return RateLimitDecision.allow(policy.limit(), policy.limit() - count);
            }
            return RateLimitDecision.reject(policy.limit(), Duration.ofSeconds(60));
        }
    }

    private final TrackingStore store = new TrackingStore();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitFilter filter(List<PolicyDocument> policies) {
        var properties = new RateLimitProperties();
        return new RateLimitFilter(
                PolicyEnforcer.forExplicitPolicies(policies, store, properties.getOnRedisError()),
                new RateLimitIdentityResolver(properties),
                new RateLimitMetrics(new SimpleMeterRegistry()),
                properties, new ObjectMapper(), Clock.systemUTC());
    }

    private static PolicyDocument doc(String id, String method, String path, Scope scope, int limit) {
        var now = Instant.now();
        return PolicyDocument.builder(id)
                .name(id).route(method, path)
                .algorithm(Algorithm.FIXED_WINDOW).scope(scope)
                .window(Duration.ofMinutes(1), limit)
                .version(1).timestamps(now, now).build();
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path,
            String remoteAddr) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response;
    }

    private static void authenticateAsAlice() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void applicationPolicySharesOneQuotaAcrossRoutesAndClients() throws Exception {
        var filter = filter(List.of(doc("site-wide", "GET", null, Scope.APPLICATION, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        var second = call(filter, "GET", "/api/orders", "198.51.100.7");
        assertThat(second.getStatus()).as("a different client on a different route shares the quota")
                .isEqualTo(429);
        assertThat(second.getHeader("X-RateLimit-Policy")).isEqualTo("site-wide");
        assertThat(store.counts).as("one key, charged once").hasSize(1);
        assertThat(store.consumes).isEqualTo(1);
    }

    @Test
    void distinctApplicationPoliciesUseDistinctCounters() throws Exception {
        var filter = filter(List.of(
                doc("app-a", null, null, Scope.APPLICATION, 1),
                doc("app-b", null, null, Scope.APPLICATION, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        // app-a is exhausted, but app-b has its own quota
        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(429);
        assertThat(store.counts).as("two separate counters").hasSize(2);
    }

    @Test
    void globalPolicySharesOneQuotaAcrossDifferentClients() throws Exception {
        var filter = filter(List.of(doc("site-wide", "GET", null, Scope.GLOBAL, 1)));

        assertThat(call(filter, "GET", "/api/products", "203.0.113.1").getStatus()).isEqualTo(200);
        var second = call(filter, "GET", "/api/orders", "198.51.100.7");
        assertThat(second.getStatus()).as("a different client on a different route shares the quota")
                .isEqualTo(429);
        assertThat(second.getHeader("X-RateLimit-Policy")).isEqualTo("site-wide");
        assertThat(store.counts).as("one key, charged once").hasSize(1);
        assertThat(store.consumes).isEqualTo(1);
    }

    @Test
    void userPolicyOnAWidePatternSpansEndpoints() throws Exception {
        authenticateAsAlice();
        var filter = filter(List.of(doc("user-wide", "POST", "/api/*", Scope.USER, 2)));

        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/b", "203.0.113.1").getStatus())
                .as("second endpoint draws from the same user quota").isEqualTo(200);
        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(429);
        assertThat(store.consumes).isEqualTo(2);
    }

    @Test
    void differentUsersDoNotShareAUserQuota() throws Exception {
        authenticateAsAlice();
        var filter = filter(List.of(doc("per-user", "POST", "/api/*", Scope.USER, 1)));
        assertThat(call(filter, "POST", "/api/a", "203.0.113.1").getStatus()).isEqualTo(200);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(call(filter, "POST", "/api/a", "203.0.113.9").getStatus())
                .as("bob has his own quota").isEqualTo(200);
    }

}
