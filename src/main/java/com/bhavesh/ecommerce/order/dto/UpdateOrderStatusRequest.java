package com.bhavesh.ecommerce.order.dto;

import com.bhavesh.ecommerce.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Administrative fulfilment move.
 *
 * <p>Validation only checks that a status was supplied. Whether the move is legal
 * is not a field-level question — it depends on where the order currently is — so
 * it is answered by {@link OrderStatus#canTransitionTo} inside the entity, which
 * returns 422 with the allowed set rather than a bare "invalid".
 */
@Schema(name = "UpdateOrderStatusRequest", description = "Advance an order to a new fulfilment status")
public record UpdateOrderStatusRequest(

        @Schema(example = "SHIPPED",
                allowableValues = {"CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
        @NotNull(message = "status is required")
        OrderStatus status
) {
}
