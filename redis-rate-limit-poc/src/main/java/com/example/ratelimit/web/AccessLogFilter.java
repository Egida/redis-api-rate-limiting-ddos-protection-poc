package com.example.ratelimit.web;

import com.example.ratelimit.ratelimit.RateLimitIdentityResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One structured line per request: client IP, method, path, outcome, latency, status.
 *
 * <p>Registered <em>before</em> the rate-limit filter so it wraps the whole chain and observes the
 * final status even when the limiter short-circuits with 429 or 503.
 *
 * <p>Deliberately does not log: the query string, {@code Authorization}, {@code Cookie}, or any
 * header value. Query parameters carry tokens on the demo routes, and Tomcat's own header logging is
 * left at INFO in {@code application.yml} for the same reason.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private final RateLimitIdentityResolver identities;

    public AccessLogFilter(RateLimitIdentityResolver identities) {
        this.identities = identities;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String clientIp = identities.clientIp(request);
        String method = request.getMethod();
        // requestURI only: never the query string, which may hold a token.
        String path = request.getRequestURI();

        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            String outcome = switch (status) {
                case 200, 201, 202, 204 -> "ALLOWED";
                case 400, 404, 405 -> "BAD_REQUEST";
                case 401 -> "UNAUTHENTICATED";
                case 403 -> "FORBIDDEN";
                case 409 -> "CONFLICT";
                case 429 -> "RATE_LIMITED";
                case 503 -> "STORE_UNAVAILABLE";
                default -> status >= 500 ? "ERROR" : "OTHER";
            };
            // Bounded-cardinality by construction: no raw header, no query, no body.
            log.info("access method={} path={} status={} outcome={} durationMs={} clientIp={}",
                    method, path, status, outcome, durationMs, clientIp);
        }
    }
}
