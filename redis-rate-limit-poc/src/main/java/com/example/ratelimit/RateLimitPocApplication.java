package com.example.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimitPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimitPocApplication.class, args);
    }
}
