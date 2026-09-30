package com.example.ratelimit.config;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Invalid policy configuration must fail startup with a message that names the offending property. */
class RateLimitConfigurationValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of());

    @Test
    void rejectsAZeroWindow() {
        // window=0s cannot be caught by the binder (@NotNull passes), so it is rejected by the
        // resolver, which is also where the other structural checks live.
        var properties = new RateLimitProperties();
        properties.setPolicies(List.of(new RateLimitProperties.Policy("products-read", "GET",
                "/api/products", 100, Duration.ZERO, RateLimitProperties.Identity.IP, null)));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new com.example.ratelimit.ratelimit.RateLimitPolicyResolver(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("window must be at least 1s")
                .hasMessageContaining("products-read");
    }

    @Test
    void rejectsALimitBelowOne() {
        runner.withPropertyValues(
                "rate-limit.policies[0].id=products-read",
                "rate-limit.policies[0].method=GET",
                "rate-limit.policies[0].path=/api/products",
                "rate-limit.policies[0].limit=0",
                "rate-limit.policies[0].window=60s",
                "rate-limit.policies[0].identity=IP")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("rate-limit.policies[0].limit"));
    }

    @Test
    void rejectsAPolicyIdThatWouldBreakKeyAndMetricLabels() {
        runner.withPropertyValues(
                "rate-limit.policies[0].id=Products Read!",
                "rate-limit.policies[0].method=GET",
                "rate-limit.policies[0].path=/api/products",
                "rate-limit.policies[0].limit=100",
                "rate-limit.policies[0].window=60s",
                "rate-limit.policies[0].identity=IP")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("rate-limit.policies[0].id"));
    }

    @Test
    void rejectsAnEmptyPolicyList() {
        runner.withPropertyValues("rate-limit.policies=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsTheShippedShape() {
        runner.withPropertyValues(
                "rate-limit.policies[0].id=products-read",
                "rate-limit.policies[0].method=GET",
                "rate-limit.policies[0].path=/api/products",
                "rate-limit.policies[0].limit=100",
                "rate-limit.policies[0].window=60s",
                "rate-limit.policies[0].identity=IP")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void duplicateIdsFailWithAnActionableMessage() {
        // Bean-level check, exercised directly because it lives in the resolver, not the binder.
        var properties = new RateLimitProperties();
        properties.setPolicies(List.of(
                new RateLimitProperties.Policy("dup", "GET", "/api/a", 1, Duration.ofMinutes(1),
                        RateLimitProperties.Identity.IP, null),
                new RateLimitProperties.Policy("dup", "GET", "/api/b", 1, Duration.ofMinutes(1),
                        RateLimitProperties.Identity.IP, null)));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new com.example.ratelimit.ratelimit.RateLimitPolicyResolver(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate policy id 'dup'");
    }

    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertiesConfig {
    }
}
