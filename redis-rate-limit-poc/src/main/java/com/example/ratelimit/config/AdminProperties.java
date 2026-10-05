package com.example.ratelimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Administrator credentials for the policy administration API.
 *
 * <p>Bound from {@code ratelimit.admin.*} and, in normal use, supplied entirely through the
 * environment:
 *
 * <pre>
 *   RATELIMIT_ADMIN_USER
 *   RATELIMIT_ADMIN_PASSWORD
 * </pre>
 *
 * <p><strong>There is deliberately no default password.</strong> When
 * {@link #getPassword()} is blank no administrator account is registered at all, so
 * {@code /api/admin/**} is unreachable for every caller, including anonymous ones. That is the safe
 * failure mode: forgetting to configure an admin locks the control plane rather than exposing it.
 *
 * <p>The demo {@code alice}/{@code bob} accounts are not administrators and cannot reach these
 * endpoints.
 */
@ConfigurationProperties(prefix = "ratelimit.admin")
public class AdminProperties {

    /** Administrator user name. Blank means no administrator exists. */
    private String username = "";

    /** Administrator password, expected to arrive already encoded (bcrypt) or as a raw secret. */
    private String password = "";

    /** When true a raw password is encoded at startup. Set false if {@link #password} is already encoded. */
    private boolean rawPassword = true;

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isRawPassword() {
        return rawPassword;
    }

    public void setRawPassword(boolean rawPassword) {
        this.rawPassword = rawPassword;
    }
}
