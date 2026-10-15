package com.bhavesh.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * A priced cart.
 *
 * <p>{@code checkoutBlocked} saves the client from re-deriving "can I check out?"
 * by scanning the lines — and keeps that rule in one place, next to the service
 * that enforces it.
 */
@Schema(name = "CartResponse", description = "The customer's cart, priced from the current catalogue")
public record CartResponse(

        @Schema(example = "7")
        Long cartId,

        List<CartItemResponse> items,

        @Schema(example = "3", description = "Sum of all line quantities")
        int totalQuantity,

        @Schema(example = "149998.00")
        BigDecimal subtotal,

        @Schema(example = "INR")
        String currency,

        @Schema(example = "false",
                description = "True when a line is out of stock or no longer purchasable")
        boolean checkoutBlocked
) {
}
