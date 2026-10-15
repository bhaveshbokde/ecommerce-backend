package com.bhavesh.ecommerce.order.dto;

import com.bhavesh.ecommerce.common.util.Money;
import com.bhavesh.ecommerce.order.entity.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One order line — the purchase-time snapshot, not the current catalogue.
 *
 * <p>Everything here is read from {@code order_items}. Nothing is looked up in
 * {@code products}, so a later rename or reprice cannot alter a receipt, and an
 * order for a since-deleted product still renders completely.
 */
@Schema(name = "OrderItemResponse", description = "A purchased line, as priced at the time")
public record OrderItemResponse(

        @Schema(example = "1", description = "Null if the catalogue entry has since been deleted")
        Long productId,

        @Schema(example = "ELEC-LAPTOP-001")
        String sku,

        @Schema(example = "14-inch Developer Laptop")
        String name,

        @Schema(example = "74999.00", description = "The price actually charged")
        BigDecimal unitPrice,

        @Schema(example = "2")
        int quantity,

        @Schema(example = "149998.00")
        BigDecimal lineTotal
) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductSku(),
                item.getProductName(),
                Money.display(item.getUnitPrice()),
                item.getQuantity(),
                Money.display(item.getLineTotal()));
    }
}
