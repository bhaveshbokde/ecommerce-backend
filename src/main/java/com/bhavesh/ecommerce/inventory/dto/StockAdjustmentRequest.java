package com.bhavesh.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Administrative stock change.
 *
 * <p>A <em>delta</em>, not an absolute figure. Two deliveries recorded
 * concurrently must both count; with an absolute value the second write would
 * silently discard the first. The repository applies it as
 * {@code available = available + :quantity}, which the database serialises per
 * row.
 *
 * <p>Negative values are allowed — stock is also lost to damage and shrinkage —
 * and {@code CHECK (available >= 0)} stops a correction from driving the figure
 * below zero.
 */
@Schema(name = "StockAdjustmentRequest", description = "Relative change to available stock")
public record StockAdjustmentRequest(

        @Schema(example = "50", description = "Positive to add stock, negative to remove it")
        @NotNull(message = "quantity is required")
        @Min(value = -100000, message = "adjustment is implausibly large")
        @Max(value = 100000, message = "adjustment is implausibly large")
        Integer quantity,

        @Schema(example = "Supplier delivery #4471")
        String reason
) {
}
