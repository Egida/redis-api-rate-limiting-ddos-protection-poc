package com.example.ratelimit.web;

import java.util.Map;

import com.example.ratelimit.RedisTestSupport;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.PolicySeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only endpoint backing the RateGuard policy table. It serves the managed policies from
 * shared Redis, so the test reseeds the baseline before each method: the shared Testcontainers
 * Redis outlives any single test class, and another class's leftover documents must never leak in.
 */
@SpringBootTest(properties = {
        "ratelimit.admin.username=pocadmin",
        "ratelimit.admin.password=admin123",
        "ratelimit.admin.raw-password=true"
})
@AutoConfigureMockMvc
@Import(PocMetadataControllerTest.RedisConfig.class)
class PocMetadataControllerTest {

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
    MockMvc mvc;

    @Autowired
    ManagedPolicyStore store;

    @Autowired
    PolicySeeder seeder;

    private static String basic(String username, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
    }

    @BeforeEach
    void reseedBaseline() {
        store.reset("test-setup");
        seeder.seed("test-setup");
    }

    @Test
    void policyEndpointIsReadableWithAdminCredentials() throws Exception {
        mvc.perform(get("/api/poc/policies")
                        .header("Authorization", basic("pocadmin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.source").value("managed policy store (Redis)"))
                .andExpect(jsonPath("$.limiterEnabled").value(true))
                .andExpect(jsonPath("$.defaultRedisFailureMode").value("FAIL_OPEN"))
                .andExpect(jsonPath("$.policyCount").value(3));
    }

    @Test
    void policyEndpointRejectsUnauthenticatedCallers() throws Exception {
        mvc.perform(get("/api/poc/policies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everySeededPolicyIsExposedWithItsStoredValues() throws Exception {
        String body = mvc.perform(get("/api/poc/policies")
                        .header("Authorization", basic("pocadmin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"id\":\"products-read\"")
                .contains("\"path\":\"/api/products\"")
                .contains("\"method\":\"GET\"")
                .contains("\"limit\":100")
                .contains("\"windowSeconds\":60")
                .contains("\"identity\":\"IP\"")
                .contains("\"algorithm\":\"FIXED_WINDOW\"")
                .contains("\"redisFailureModeLabel\":\"Fail open\"");

        assertThat(body).contains("\"id\":\"login-attempt\"")
                .contains("\"limit\":10")
                .contains("\"redisFailureMode\":\"FAIL_CLOSED\"")
                .contains("\"redisFailureModeLabel\":\"Fail closed\"");

        assertThat(body).contains("\"id\":\"order-create\"")
                .contains("\"path\":\"/api/orders\"")
                .contains("\"limit\":30")
                .contains("\"identity\":\"USER\"");
    }

    @Test
    void consoleReflectsAnAdminEditWithoutRestart() throws Exception {
        var stored = store.find("products-read").orElseThrow();
        var updated = com.example.ratelimit.policy.PolicyDocument.builder(stored.id())
                .name(stored.name()).route(stored.method(), stored.path())
                .algorithm(stored.algorithm()).scope(stored.scope())
                .window(java.time.Duration.ofMinutes(1), 7)
                .version(2).timestamps(stored.createdAt(), java.time.Instant.now())
                .updatedBy("test-setup").build();
        store.save(updated, stored, "test-setup");

        String body = mvc.perform(get("/api/poc/policies")
                        .header("Authorization", basic("pocadmin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"id\":\"products-read\"").contains("\"limit\":7");
    }

    @Test
    void exposesNoCredentialsIdentitiesOrRedisKeys() throws Exception {
        String body = mvc.perform(get("/api/poc/policies")
                        .header("Authorization", basic("pocadmin", "admin123")))
                .andReturn().getResponse().getContentAsString();

        // Nothing here may reveal who called the API or how a counter is keyed.
        assertThat(body).doesNotContain("alice")
                .doesNotContain("bob")
                .doesNotContain("alice-pw")
                .doesNotContain("bob-pw")
                .doesNotContain("rate-limit:v1:")
                .doesNotContain("Authorization");
    }

    @Test
    void pocEndpointsRequireAdminWhileDemoRoutesStayProtected() throws Exception {
        // /api/poc/** requires admin now...
        mvc.perform(get("/api/poc/policies")).andExpect(status().isUnauthorized());
        // ... while /api/orders stays protected for normal users.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theAngularConsoleIsNotServedFromTheApplicationRoot() throws Exception {
        // The console is a separate Angular app behind its own dev server / proxy, so the API jar
        // must not ship a second copy of the UI.
        mvc.perform(get("/index.html")).andExpect(status().isNotFound());
    }
}
