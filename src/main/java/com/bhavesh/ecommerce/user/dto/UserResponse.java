package com.bhavesh.ecommerce.user.dto;

import com.bhavesh.ecommerce.user.entity.Role;
import com.bhavesh.ecommerce.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A user as the API presents them.
 *
 * <p>The {@link User} entity is never serialised directly. It carries
 * {@code passwordHash}, and one forgotten {@code @JsonIgnore} would put a hash in
 * a response body. A separate response type makes exposure opt-in: a new entity
 * field is invisible until someone adds it here on purpose.
 */
@Schema(name = "UserResponse", description = "A registered account")
public record UserResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "priya@example.com")
        String email,

        @Schema(example = "Priya Sharma")
        String fullName,

        @Schema(example = "ROLE_CUSTOMER")
        Role role,

        boolean enabled,

        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}
