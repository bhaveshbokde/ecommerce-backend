package com.bhavesh.ecommerce.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code ecommerce.jwt.*}.
 *
 * <p>A {@code record}, so the values are immutable after binding and no bean that
 * receives them can mutate them. {@code @Validated} turns a missing signing
 * secret into a <em>startup</em> failure rather than a 500 on the first login.
 *
 * @param secret          base64-encoded HMAC key material; must decode to >= 32 bytes
 * @param issuer          written into, and required by, the {@code iss} claim
 * @param accessTokenTtl  lifetime of an access token
 * @param refreshTokenTtl lifetime of a refresh token
 * @param clockSkew       leeway allowed when validating {@code exp} / {@code nbf}
 */
@ConfigurationProperties(prefix = "ecommerce.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "ecommerce.jwt.secret must be set (see .env.example)")
        String secret,

        @DefaultValue("ecommerce-backend")
        String issuer,

        @DefaultValue("PT30M")
        Duration accessTokenTtl,

        @DefaultValue("P7D")
        Duration refreshTokenTtl,

        @DefaultValue("PT30S")
        Duration clockSkew
) {
}
