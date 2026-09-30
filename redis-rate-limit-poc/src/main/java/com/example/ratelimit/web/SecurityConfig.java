package com.example.ratelimit.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security so the POC can show both identity strategies: anonymous IP limiting on
 * public routes and authenticated-user limiting on /api/orders.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/orders").authenticated()
                        .requestMatchers("/actuator/**").permitAll()
                        // Read-only policy metadata for the local RateGuard console. No mutation
                        // endpoint exists, and it exposes no keys, identities or credentials.
                        .requestMatchers("/api/poc/**").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var alice = User.withUsername("alice").password(encoder.encode("alice-pw")).roles("USER").build();
        var bob = User.withUsername("bob").password(encoder.encode("bob-pw")).roles("USER").build();
        return new InMemoryUserDetailsManager(alice, bob);
    }
}
