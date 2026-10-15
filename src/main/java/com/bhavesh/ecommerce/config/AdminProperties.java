package com.bhavesh.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code ecommerce.admin.*} — the development administrator bootstrap.
 *
 * <p>There is deliberately no seeded administrator in any Flyway migration. A
 * migration that inserted an account with a known password would put a working
 * credential into every environment that ever ran it, including any that later
 * became public, and it could not be removed without a second migration.
 *
 * <p>Instead {@link com.bhavesh.ecommerce.config.AdminBootstrap} creates the
 * account at startup, only when both values are present <em>and</em> the active
 * profile is not {@code prod}. The {@code prod} profile blanks both values in
 * {@code application.yml}, so a leaked environment variable cannot mint an
 * administrator in production.
 *
 * @param email    administrator email; empty disables the bootstrap entirely
 * @param password plain-text password, BCrypt-hashed before storage and never logged
 */
@ConfigurationProperties(prefix = "ecommerce.admin")
public record AdminProperties(
        @DefaultValue("") String email,
        @DefaultValue("") String password
) {

    /** True only when both halves are supplied. */
    public boolean isConfigured() {
        return email != null && !email.isBlank()
                && password != null && !password.isBlank();
    }
}
