package com.example.ratelimit.ratelimit;

import java.util.Map;

import com.example.ratelimit.RedisTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP behaviour against a real Redis: allow, 429 shape, per-policy independence,
 * identity strategy, excluded routes and metrics.
 *
 * <p>Redis keys live for a whole window and this class shares one application context, so the tests
 * are ordered and share quota deliberately: test 3 spends alice's order quota, test 4 proves the
 * quota is shared per user rather than per IP.
 *
 * <p>Windows are stretched to 10 minutes via src/test/resources/rate-limit-test-windows.properties.
 * With the shipped 60s window, whether these assertions hold would depend on where in the minute
 * the suite happened to run. Limits and identity strategies are unchanged; window-length behaviour
 * itself is covered deterministically by {@link RateLimitWindowBoundaryTest} (controllable clock)
 * and {@link RedisRateLimitStoreTest} (real Redis TTL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RateLimitHttpIntegrationTest.RedisConfig.class)
@TestPropertySource(locations = "classpath:rate-limit-test-windows.properties")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitHttpIntegrationTest {

    @TestConfiguration
    static class RedisConfig {
        @Bean
        @Primary
        LettuceConnectionFactory testConnectionFactory() {
            var container = RedisTestSupport.redis();
            var config = new RedisStandaloneConfiguration(container.getHost(),
                    container.getMappedPort(RedisTestSupport.REDIS_PORT));
            var factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return factory;
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;

    @Test
    @Order(1)
    void allowsRequestsBelowTheLimit() {
        // products-read policy: 100/min per IP.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = rest.getForEntity("/api/products", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("100");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
        }
    }

    @Test
    @Order(2)
    void requestBeyondTheLimitReturns429WithRetryInfo() {
        // login-attempt policy: 10/min per IP. All 10 pass, the 11th is rejected.
        for (int i = 0; i < 10; i++) {
            assertThat(postLogin().getStatusCode()).as("login request %d", i + 1).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> rejected = postLogin();
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("login-attempt");
        assertThat(rejected.getBody()).contains("Rate limit exceeded").contains("login-attempt");
    }

    @Test
    @Order(3)
    void oneUserSpendingTheOrderQuotaDoesNotAffectAnotherUser() {
        // order-create: 30/min per authenticated user.
        for (int i = 0; i < 20; i++) {
            assertThat(postOrders("alice", "alice-pw").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(postOrders("bob", "bob-pw").getStatusCode())
                .as("bob shares alice's IP but not her quota")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(4)
    void authenticatedRouteLimitsByUserNotByIp() {
        // Alice has 10 left from test 3; the 11th request is rejected. Bob's budget is untouched.
        for (int i = 0; i < 10; i++) {
            assertThat(postOrders("alice", "alice-pw").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> rejected = postOrders("alice", "alice-pw");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("order-create");

        for (int i = 0; i < 5; i++) {
            assertThat(postOrders("bob", "bob-pw").getStatusCode())
                    .as("bob still has %d of 30", 30 - 21)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @Order(5)
    void unauthenticatedRequestToProtectedRouteIsUnauthorisedNotRateLimited() {
        // Security runs first: no credentials means 401 before the limiter counts anything.
        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of(), new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(6)
    void excludedAndUnsupportedRequestsPassThrough() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // No policy exists for PUT, so it is not counted at all.
        var put = new HttpEntity<>(Map.of(), new HttpHeaders());
        assertThat(rest.exchange("/api/products", HttpMethod.PUT, put, String.class).getStatusCode())
                .isIn(HttpStatus.OK, HttpStatus.METHOD_NOT_ALLOWED);

        // OPTIONS is in excluded-methods, so a preflight never consumes quota.
        var preflight = rest.exchange("/api/products", HttpMethod.OPTIONS,
                new HttpEntity<>(Map.of(), new HttpHeaders()), String.class);
        assertThat(preflight.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @Order(7)
    void countersAreExposedWithBoundedLabels() throws Exception {
        ResponseEntity<String> metrics = rest.getForEntity("/actuator/metrics/ratelimit.requests", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = mapper.readValue(metrics.getBody(), Map.class);
        assertThat(body.get("name")).isEqualTo("ratelimit.requests");
        assertThat(String.valueOf(body.get("availableTags"))).contains("outcome", "policy", "identity");
        // No client-identifying label exists at all.
        assertThat(String.valueOf(body.get("availableTags"))).doesNotContain("client", "remoteAddr");
    }

    private ResponseEntity<String> postLogin() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("user", "alice"), headers), String.class);
    }

    private ResponseEntity<String> postOrders(String user, String password) {
        var headers = new HttpHeaders();
        headers.setBasicAuth(user, password);
        return rest.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(Map.of(), headers), String.class);
    }
}
