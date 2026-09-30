package com.example.ratelimit.web;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only endpoint backing the RateGuard policy table. Redis is not required: it reads bound
 * configuration only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PocMetadataControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void policyEndpointIsReadableWithoutCredentials() throws Exception {
        mvc.perform(get("/api/poc/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.source").value("rate-limit.policies (application.yml)"))
                .andExpect(jsonPath("$.limiterEnabled").value(true))
                .andExpect(jsonPath("$.defaultRedisFailureMode").value("FAIL_OPEN"))
                .andExpect(jsonPath("$.policyCount").value(3));
    }

    @Test
    void everyShippedPolicyIsExposedWithItsConfiguredValues() throws Exception {
        String body = mvc.perform(get("/api/poc/policies"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"id\":\"products-read\"")
                .contains("\"path\":\"/api/products\"")
                .contains("\"method\":\"GET\"")
                .contains("\"limit\":100")
                .contains("\"windowSeconds\":60")
                .contains("\"identity\":\"IP\"")
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
    void exposesNoCredentialsIdentitiesOrRedisKeys() throws Exception {
        String body = mvc.perform(get("/api/poc/policies"))
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
    void policiesMatchTheShippedYamlFile() throws Exception {
        // Guards against the console drifting from application.yml.
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(mvc.perform(get("/api/poc/policies")).andReturn().getResponse().getContentAsString(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
        var policies = (java.util.List<Map<String, Object>>) body.get("policies");
        assertThat(policies).extracting("id", "limit", "windowSeconds", "identity")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("products-read", 100, 60, "IP"),
                        org.assertj.core.groups.Tuple.tuple("login-attempt", 10, 60, "IP"),
                        org.assertj.core.groups.Tuple.tuple("order-create", 30, 60, "USER"));
    }

    @Test
    void doesNotRequireAuthenticationButDoesNotUnlockTheDemoRoutes() throws Exception {
        // The console must load without credentials ...
        mvc.perform(get("/api/poc/policies")).andExpect(status().isOk());
        // ... while /api/orders stays protected.
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
