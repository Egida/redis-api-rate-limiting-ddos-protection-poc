package com.example.ratelimit.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in business endpoints. The POC repo has no business API of its own, so these three
 * routes exist only to give the sample rate-limit policies something real to protect.
 * See docs/api-rate-limiting-poc.md ("Why a demo API exists in this repository").
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(defaultValue = "1") int page) {
        return Map.of("items", List.of("widget", "gadget"), "page", page);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam(defaultValue = "alice") String user) {
        // Not a real authentication endpoint; returns a throwaway token.
        return Map.of("user", user, "token", "poc-token-" + user);
    }

    @PostMapping("/orders")
    public Map<String, Object> orders(@AuthenticationPrincipal UserDetails principal) {
        return Map.of("orderId", "ord-" + Math.abs(principal.getUsername().hashCode()),
                "placedBy", principal.getUsername());
    }

    private final java.util.concurrent.atomic.AtomicInteger workInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger workMaxObserved = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Slow stand-in for concurrency demonstrations. Sleeps {@code ms} (capped) while holding whatever
     * concurrency permit the filter acquired, and reports the highest overlap this JVM has seen.
     *
     * <p>Per-JVM counters on purpose: the cross-instance script compares the sum of both JVMs'
     * observed maxima against the configured cap.
     *
     * @param fail when true, throws after tracking overlap, so tests can prove a permit is released
     *             on the error path as well as on success
     */
    @GetMapping("/work")
    public Map<String, Object> work(@RequestParam(defaultValue = "200") int ms,
            @RequestParam(defaultValue = "false") boolean fail)
            throws InterruptedException {
        int bounded = Math.max(0, Math.min(ms, 2000));
        int current = workInFlight.incrementAndGet();
        try {
            workMaxObserved.accumulateAndGet(current, Math::max);
            Thread.sleep(bounded);
            if (fail) {
                throw new IllegalStateException("demo failure as requested");
            }
        } finally {
            workInFlight.decrementAndGet();
        }
        return Map.of("sleptMs", bounded, "maxInFlight", workMaxObserved.get());
    }
}
