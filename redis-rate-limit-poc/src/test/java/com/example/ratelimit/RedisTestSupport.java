package com.example.ratelimit;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Redis container for every Redis-backed test in this module. */
public final class RedisTestSupport {

    public static final int REDIS_PORT = 6379;

    private static volatile GenericContainer<?> container;

    private RedisTestSupport() {
    }

    public static synchronized GenericContainer<?> redis() {
        if (container == null) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
        return container;
    }
}
