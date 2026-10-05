package com.example.ratelimit.policy;

import java.util.List;

/** One or more validation problems in a submitted policy. Maps to HTTP 400. */
public class PolicyValidationException extends RuntimeException {

    private final List<String> problems;

    public PolicyValidationException(List<String> problems) {
        super("policy is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
