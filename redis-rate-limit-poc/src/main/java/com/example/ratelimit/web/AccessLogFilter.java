package com.example.ratelimit.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every HTTP request with client IP, method, path, status, and rate-limit outcome.
 * Runs after the rate-limit filter so the response status (200/429/401/503) reflects the decision.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String fullPath = query != null ? path + "?" + query : path;

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            int status = response.getStatus();

            String outcome = switch (status) {
                case 200, 201, 204 -> "APPROVED";
                case 429 -> "REJECTED (rate limit)";
                case 401 -> "REJECTED (unauthorized)";
                case 403 -> "REJECTED (forbidden)";
                case 503 -> "REJECTED (service unavailable)";
                default -> "STATUS_" + status;
            };

            log.info("ACCESS ip={} {} {} -> {} ({}ms) [{}]",
                    clientIp, method, fullPath, outcome, durationMs, status);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // first hop = original client
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }
}