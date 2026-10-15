package com.bhavesh.ecommerce.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>{@code email} carries {@code @NotBlank} but deliberately not {@code @Email}.
 * A malformed address at login is not a validation problem — it simply will not
 * match any account — and answering 400 "that is not an email" distinguishes it
 * from 401 "wrong credentials", which is a small enumeration oracle. Both cases
 * should look identical from outside.
 */
@Schema(name = "LoginRequest", description = "Credentials")
public record LoginRequest(

        @Schema(example = "priya@example.com")
        @NotBlank(message = "email is required")
        @Size(max = 254)
        String email,

        @Schema(example = "correct-horse-battery-staple", format = "password")
        @NotBlank(message = "password is required")
        @Size(max = 72)
        String password
) {

    @Override
    public String toString() {
        return "LoginRequest{email='%s'}".formatted(email);
    }
}
