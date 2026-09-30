package com.example.ratelimit.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.ratelimit.config.RateLimitProperties;
import com.example.ratelimit.config.RateLimitProperties.FailureMode;
import com.example.ratelimit.config.RateLimitProperties.Policy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only metadata for the RateGuard console: which policies are configured and how this
 * instance would behave if Redis were unreachable.
 *
 * <p>Everything here comes from bound {@code rate-limit.*} configuration, which is the same source
 * {@code application.yml} documents. Nothing is mutable over HTTP, and no Redis key, client
 * identity or credential is exposed.
 */
@RestController
@RequestMapping("/api/poc")
public class PocMetadataController {

    private final RateLimitProperties properties;

    public PocMetadataController(RateLimitProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/policies")
    public Map<String, Object> policies() {
        List<Map<String, Object>> policies = new ArrayList<>();
        for (Policy policy : properties.getPolicies()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", policy.id());
            row.put("method", policy.method());
            row.put("path", policy.path());
            row.put("limit", policy.limit());
            row.put("windowSeconds", policy.window().toSeconds());
            row.put("identity", policy.identity().name());
            FailureMode failureMode = policy.failureMode(properties.getOnRedisError());
            row.put("redisFailureMode", failureMode.name());
            row.put("redisFailureModeLabel", switch (failureMode) {
                case FAIL_OPEN -> "Fail open";
                case FAIL_CLOSED -> "Fail closed";
            });
            policies.add(row);
        }
        return Map.of(
                "source", "rate-limit.policies (application.yml)",
                "editable", false,
                "limiterEnabled", properties.isEnabled(),
                "defaultRedisFailureMode", properties.getOnRedisError().name(),
                "policyCount", policies.size(),
                "policies", policies);
    }
}
