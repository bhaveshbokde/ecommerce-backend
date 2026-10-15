package com.bhavesh.ecommerce.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-registration payload.
 *
 * <p><strong>There is no {@code role} field, and that is the point.</strong> The
 * handler calls {@link com.bhavesh.ecommerce.user.entity.User#registerCustomer},
 * which hard-codes {@code ROLE_CUSTOMER}. Privilege escalation through this
 * endpoint is not blocked by a validation rule that could be relaxed — there is
 * simply nowhere to put the request. A field with {@code @Null} on it would be a
 * weaker version of the same idea, because someone would eventually "fix" the
 * annotation.
 *
 * <p>A record, so every field is final and the object cannot be mutated between
 * validation and use.
 */
@Schema(name = "RegisterRequest", description = "New customer account")
public record RegisterRequest(

        @Schema(example = "priya@example.com")
        @NotBlank(message = "email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 254, message = "must not exceed 254 characters")
        String email,

        /**
         * Minimum 8 characters, upper bound 72.
         *
         * <p>The ceiling is not arbitrary: BCrypt ignores bytes past 72, so a
         * longer password would be silently truncated, and two different 100-character
         * passwords sharing a prefix would authenticate each other. Rejecting the
         * input is honest; silently truncating it is not.
         *
         * <p>No composition rule (one upper, one digit, one symbol). NIST SP
         * 800-63B advises against them: they push people toward {@code Password1!}
         * and add far less entropy than length does.
         */
        @Schema(example = "correct-horse-battery-staple", format = "password")
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @Schema(example = "Priya Sharma")
        @NotBlank(message = "fullName is required")
        @Size(max = 120, message = "must not exceed 120 characters")
        String fullName
) {

    /** Never prints {@code password}, so a logged DTO cannot leak a credential. */
    @Override
    public String toString() {
        return "RegisterRequest{email='%s', fullName='%s'}".formatted(email, fullName);
    }
}
