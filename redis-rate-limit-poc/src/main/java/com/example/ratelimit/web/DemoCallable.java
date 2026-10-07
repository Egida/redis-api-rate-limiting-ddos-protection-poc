package com.example.ratelimit.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as part of the request demo, with the metadata the demo needs to replay
 * it safely.
 *
 * <p>The demo catalog is built from Spring's registered handler mappings and keeps only annotated
 * methods. That is deliberate: a rate-limit policy proves only that somebody configured a limit, not
 * that an API exists. Annotating the handler makes a route's existence and its replay safety
 * explicit, and keeps admin, actuator and authentication-control routes out by omission.
 *
 * <p>A route that exists but must not be replayed is still annotated, with {@code repeatable=false}
 * and a {@code reason}, so the console can show why it is unavailable instead of hiding it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DemoCallable {

    /** False when the console must not send this request. {@link #reason()} then says why. */
    boolean repeatable() default true;

    /** Why the route cannot be replayed automatically. Shown to the operator verbatim. */
    String reason() default "";

    /**
     * Query string the demo appends, for example {@code "ms=200"}. Only ever query parameters:
     * the demo sends no request bodies, so a route needing one must set {@code repeatable=false}.
     */
    String sampleQuery() default "";

    /** Concrete path to request when the mapping template carries {@code {parameters}}. */
    String samplePath() default "";

    /** True when the handler needs HTTP Basic credentials to be reached meaningfully. */
    boolean requiresCredentials() default false;

    /** One line shown next to the route in the console. */
    String note() default "";
}