package com.example.ratelimit.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import com.example.ratelimit.ratelimit.RateLimitIdentityResolver.Identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the resolved policy before the controller runs. Registered after Spring Security's filter
 * chain, so the {@code SecurityContext} is already populated when a {@code USER} identity is
 * resolved.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitPolicyResolver policies;
    private final RateLimitStore store;
    private final RateLimitIdentityResolver identities;
    private final RateLimitMetrics metrics;
    private final RateLimitProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitPolicyResolver policies, RateLimitStore store,
            RateLimitIdentityResolver identities, RateLimitMetrics metrics,
            RateLimitProperties properties, ObjectMapper mapper, Clock clock) {
        this.policies = policies;
        this.store = store;
        this.identities = identities;
        this.metrics = metrics;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String method = request.getMethod();
        for (String excluded : properties.getExcludedMethods()) {
            if (excluded.equalsIgnoreCase(method)) {
                return true;
            }
        }
        String path = request.getRequestURI();
        for (String excluded : properties.getExcludedPaths()) {
            if (matcher.match(excluded, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Policy policy = policies.resolve(request.getMethod(), request.getRequestURI());
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }

        Identity identity = identities.resolve(request, policy);
        RateLimitDecision decision;
        try {
            decision = store.consume(policy, identity.type(), identity.value(), clock.millis());
        } catch (RateLimitStore.RateLimitStoreUnavailableException e) {
            metrics.record(RateLimitMetrics.Outcome.ERROR, policy.id(), identity.type());
            log.warn("rate limit store unavailable for policy {}", policy.id(), e);
            if (policy.failureMode(properties.getOnRedisError()) == FailureMode.FAIL_CLOSED) {
                writeStoreUnavailable(response, request, policy);
                return;
            }
            // FAIL_OPEN: fall through to normal application handling.
            chain.doFilter(request, response);
            return;
        }

        if (decision.allowed()) {
            metrics.record(RateLimitMetrics.Outcome.ALLOWED, policy.id(), identity.type());
            response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }

        metrics.record(RateLimitMetrics.Outcome.REJECTED, policy.id(), identity.type());
        writeRateLimited(response, request, policy, decision);
    }

    private void writeRateLimited(HttpServletResponse response, HttpServletRequest request, Policy policy,
            RateLimitDecision decision) throws IOException {
        long retryAfterSeconds = decision.retryAfter().toSeconds();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Policy", policy.id());
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        writeBody(response, HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for this route. Retry after " + retryAfterSeconds + "s.",
                request, policy, retryAfterSeconds);
    }

    /** No quota was counted, so no X-RateLimit-Limit/Remaining headers are emitted here. */
    private void writeStoreUnavailable(HttpServletResponse response, HttpServletRequest request, Policy policy)
            throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Policy", policy.id());
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        writeBody(response, HttpStatus.SERVICE_UNAVAILABLE,
                "Rate limiting is temporarily unavailable. Please retry later.",
                request, policy, 5);
    }

    private void writeBody(HttpServletResponse response, HttpStatus status, String message,
            HttpServletRequest request, Policy policy, long retryAfterSeconds) throws IOException {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now(clock).toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", request.getRequestURI(),
                "policy", policy.id(),
                "limit", policy.limit(),
                "windowSeconds", policy.window().toSeconds(),
                "retryAfterSeconds", retryAfterSeconds);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
