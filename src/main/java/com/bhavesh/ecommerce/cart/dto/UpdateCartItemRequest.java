package com.bhavesh.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Set an absolute quantity for a line that is already in the cart.
 *
 * <p>Absolute rather than a delta: a retried {@code PATCH … {"quantity": 3}}
 * leaves the cart holding three either way, whereas a retried "+3" would leave
 * six. Idempotent by construction, which matters on a flaky mobile connection.
 */
@Schema(name = "UpdateCartItemRequest", description = "New absolute quantity for a cart line")
public record UpdateCartItemRequest(

        @Schema(example = "3")
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "must be at least 1; use DELETE to remove the line")
        @Max(value = 999, message = "must not exceed 999")
        Integer quantity
) {
}
