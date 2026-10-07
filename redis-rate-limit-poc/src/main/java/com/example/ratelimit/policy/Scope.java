package com.example.ratelimit.policy;

/**
 * What a policy counts against, and therefore which key namespace its counter lives in.
 *
 * <p>{@link #ENDPOINT} is the route matcher itself; the remaining values are identity scopes that may
 * be attached to a route or, for {@link #GLOBAL}, applied without one.
 */
public enum Scope {

    /** Match method + route template. Keyed per policy, so effectively per route. */
    ENDPOINT,

    /** Canonical client IP, resolved only through configured trusted-proxy CIDRs. */
    IP,

    /** Authenticated principal. A USER policy may optionally span several routes. */
    USER,

    /** One quota shared by every route, identity and application instance. */
    GLOBAL,

    /** One quota shared across all in-scope API routes, regardless of IP or user. */
    APPLICATION
}
