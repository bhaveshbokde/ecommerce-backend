package com.bhavesh.ecommerce.inventory.dto;

import com.bhavesh.ecommerce.inventory.entity.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stock for one product. Administrator-facing — the public catalogue exposes only
 * a boolean.
 */
@Schema(name = "InventoryResponse", description = "Stock levels for a product")
public record InventoryResponse(

        @Schema(example = "1")
        Long productId,

        @Schema(example = "ELEC-LAPTOP-001")
        String sku,

        @Schema(example = "5", description = "Units sellable right now")
        int available,

        @Schema(example = "2", description = "Units held for orders awaiting payment")
        int reserved,

        @Schema(example = "7", description = "available + reserved")
        int total
) {

    public static InventoryResponse from(Inventory inventory, String sku) {
        return new InventoryResponse(inventory.getProductId(), sku,
                inventory.getAvailable(), inventory.getReserved(), inventory.getTotal());
    }
}
