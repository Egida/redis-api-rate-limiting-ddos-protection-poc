package com.example.ratelimit.config;

import com.example.ratelimit.ratelimit.RateLimitFilter;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import com.example.ratelimit.ratelimit.RateLimitMetrics;
import com.example.ratelimit.ratelimit.RateLimitPolicyResolver;
import com.example.ratelimit.ratelimit.RateLimitStore;
import com.example.ratelimit.web.AccessLogFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

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

    /**
     * Access log filter runs BEFORE the rate limit filter so it wraps the entire chain
     * and sees the final response status (200, 429, 401, 503) even when rate limit
     * filter short-circuits. Lower order = runs earlier.
     */
    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
        var registration = new FilterRegistrationBean<>(new AccessLogFilter());
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 150); // before rate limit filter (-100)
        registration.addUrlPatterns("/*");
        return registration;
    }
}
