package com.bhavesh.ecommerce.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries a refresh token in the request <em>body</em>, not a query parameter.
 *
 * <p>Query strings are written to access logs, kept in browser history and sent in
 * the {@code Referer} header to any third party the page links to. A long-lived
 * credential in one of those places is a credential in a log aggregator.
 */
@Schema(name = "RefreshRequest", description = "Exchange a refresh token for a new access token")
public record RefreshRequest(

        @Schema(description = "The opaque refresh token returned by login")
        @NotBlank(message = "refreshToken is required")
        @Size(max = 200)
        String refreshToken
) {

    @Override
    public String toString() {
        return "RefreshRequest{refreshToken=***}";
    }
}
