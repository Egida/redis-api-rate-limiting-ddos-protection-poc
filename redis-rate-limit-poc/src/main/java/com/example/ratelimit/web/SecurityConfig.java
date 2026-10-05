package com.example.ratelimit.web;

import com.example.ratelimit.config.AdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security so the POC can show every identity strategy: anonymous IP limiting on public
 * routes, authenticated-user limiting on /api/orders, and an administrator for policy changes.
 *
 * <h2>CSRF</h2>
 * Disabled here because the application has no cookie-backed session: authentication is HTTP Basic on
 * every authenticated path, and a browser does not attach Basic credentials to a cross-site request the
 * way it attaches cookies. That distinction matters — if a session or cookie authentication is ever
 * added, CSRF protection must be re-enabled for state-changing methods. The admin API is therefore
 * safe to call from the Angular dev server without a CSRF token, and no other weakening is applied.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(reg -> reg
                        // The control plane. ROLE_ADMIN only; alice/bob hold ROLE_USER and are refused.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
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
    UserDetailsService userDetailsService(PasswordEncoder encoder, AdminProperties admin) {
        var alice = User.withUsername("alice").password(encoder.encode("alice-pw")).roles("USER").build();
        var bob = User.withUsername("bob").password(encoder.encode("bob-pw")).roles("USER").build();

        if (!admin.isConfigured()) {
            log.warn("ratelimit.admin.username / .password are not set, so no administrator account "
                    + "exists and /api/admin/** will reject every caller. Set RATELIMIT_ADMIN_USER and "
                    + "RATELIMIT_ADMIN_PASSWORD to enable policy administration.");
            return new InMemoryUserDetailsManager(alice, bob);
        }

        // The admin secret never appears in a log line, an actuator body or a metrics tag.
        String encoded = admin.isRawPassword() ? encoder.encode(admin.getPassword()) : admin.getPassword();
        UserDetails administrator = User.withUsername(admin.getUsername())
                .password(encoded)
                .roles("ADMIN")
                .build();
        log.info("administrator account '{}' registered for /api/admin/**", admin.getUsername());
        return new InMemoryUserDetailsManager(alice, bob, administrator);
    }
}
