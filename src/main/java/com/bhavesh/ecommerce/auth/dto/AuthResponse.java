package com.bhavesh.ecommerce.auth.dto;

import com.bhavesh.ecommerce.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What login and refresh return.
 *
 * <p>{@code expiresInSeconds} is included so a client can schedule a refresh
 * rather than wait for a 401 and retry. The value is derived from configuration,
 * not hard-coded in the client.
 *
 * <p>Two tokens with different lifetimes is the whole design: the access token is
 * short-lived and stateless, so verification needs no database round trip and a
 * leaked one expires quickly; the refresh token is long-lived but stored (as a
 * hash) and therefore revocable. One token could not be both.
 */
@Schema(name = "AuthResponse", description = "Issued tokens and the authenticated user")
public record AuthResponse(

        @Schema(description = "Short-lived JWT for the Authorization header")
        String accessToken,

        @Schema(description = "Long-lived opaque token, accepted only by /auth/refresh")
        String refreshToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(example = "1800", description = "Access token lifetime in seconds")
        long expiresInSeconds,

        UserResponse user
) {

    public static AuthResponse of(String accessToken, String refreshToken,
                                  long expiresInSeconds, UserResponse user) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user);
    }

    /** Never prints either token. */
    @Override
    public String toString() {
        return "AuthResponse{tokenType='%s', expiresInSeconds=%d, user=%s}"
                .formatted(tokenType, expiresInSeconds, user);
    }
}
