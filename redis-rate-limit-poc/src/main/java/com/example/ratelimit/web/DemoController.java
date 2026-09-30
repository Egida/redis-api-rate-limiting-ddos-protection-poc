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
}
