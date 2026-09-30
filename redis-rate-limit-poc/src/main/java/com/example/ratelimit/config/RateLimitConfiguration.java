package com.example.ratelimit.config;

import java.time.Clock;

import com.example.ratelimit.ratelimit.RateLimitFilter;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import com.example.ratelimit.ratelimit.RateLimitMetrics;
import com.example.ratelimit.ratelimit.RateLimitPolicyResolver;
import com.example.ratelimit.ratelimit.RateLimitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimitIdentityResolver rateLimitIdentityResolver(RateLimitProperties properties) {
        return new RateLimitIdentityResolver(properties);
    }

    @Bean
    RateLimitFilter rateLimitFilter(RateLimitPolicyResolver policies, RateLimitStore store,
            RateLimitIdentityResolver identities, RateLimitMetrics metrics,
            RateLimitProperties properties, ObjectMapper mapper, Clock clock) {
        return new RateLimitFilter(policies, store, identities, metrics, properties, mapper, clock);
    }

    /**
     * Ordered after Spring Security's chain (which registers at -100 by default) so the
     * SecurityContext is populated before we resolve a USER identity. See
     * docs/api-rate-limiting-poc.md for the ordering proof.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
