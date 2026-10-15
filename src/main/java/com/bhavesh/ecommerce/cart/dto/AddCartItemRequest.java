package com.bhavesh.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Add a product to the cart, or increase an existing line.
 *
 * <p>The quantity bounds mirror {@code ck_cart_items_quantity}. Validating here
 * produces a readable 400; the CHECK constraint is what makes the rule true even
 * for a write that never passes through this class.
 */
@Schema(name = "AddCartItemRequest", description = "A product and a quantity")
public record AddCartItemRequest(

        @Schema(example = "1")
        @NotNull(message = "productId is required")
        @Positive(message = "must be a positive id")
        Long productId,

        @Schema(example = "2")
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 999, message = "must not exceed 999")
        Integer quantity
) {
}
