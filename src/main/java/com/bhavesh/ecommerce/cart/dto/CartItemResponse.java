package com.bhavesh.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One cart line, priced at read time.
 *
 * <p>{@code unitPrice} and {@code lineTotal} are computed from the catalogue when
 * the cart is read, not stored on the line — {@code cart_items} has no price
 * column. A cart is an intention, so the customer pays today's price at checkout,
 * not the price on the day they added the item.
 *
 * <p>{@code available} and {@code purchasable} let the UI flag a problem before
 * the customer reaches checkout. They are advisory: the authoritative check is the
 * conditional UPDATE at reservation time, and stock can vanish between this
 * response and that statement.
 */
@Schema(name = "CartItemResponse", description = "A cart line with current pricing")
public record CartItemResponse(

        @Schema(example = "10")
        Long itemId,

        @Schema(example = "1")
        Long productId,

        @Schema(example = "ELEC-LAPTOP-001")
        String sku,

        @Schema(example = "14-inch Developer Laptop")
        String name,

        @Schema(example = "74999.00", description = "Current catalogue price")
        BigDecimal unitPrice,

        @Schema(example = "2")
        int quantity,

        @Schema(example = "149998.00")
        BigDecimal lineTotal,

        @Schema(example = "5", description = "Units currently in stock; advisory only")
        int available,

        @Schema(example = "true", description = "False if the product or its category was deactivated")
        boolean purchasable
) {
}
