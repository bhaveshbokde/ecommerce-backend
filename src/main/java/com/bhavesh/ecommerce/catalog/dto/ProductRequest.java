package com.bhavesh.ecommerce.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create or update a product. Administrator only.
 *
 * <p>{@code price} is a {@link BigDecimal}, never a {@code double}. Jackson binds
 * the JSON number {@code 19.99} to an exact decimal; a {@code double} would hold
 * {@code 19.989999999999998} and the error would compound across a basket.
 *
 * <p>{@code @Digits(integer = 15, fraction = 4)} mirrors {@code NUMERIC(19,4)}, so
 * an over-precise value is a 400 with a field message rather than a 500 from the
 * driver.
 */
@Schema(name = "ProductRequest", description = "Product details")
public record ProductRequest(

        @Schema(example = "ELEC-LAPTOP-001", description = "Uppercased before storage")
        @NotBlank(message = "sku is required")
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{2,63}$",
                message = "must be 3-64 characters of letters, digits and hyphens, starting with a letter or digit")
        String sku,

        @Schema(example = "14-inch Developer Laptop")
        @NotBlank(message = "name is required")
        @Size(max = 200)
        String name,

        @Size(max = 2000)
        String description,

        @Schema(example = "74999.00")
        @NotNull(message = "price is required")
        @Positive(message = "must be greater than 0")
        @DecimalMax(value = "999999999999999.9999", message = "is too large")
        @Digits(integer = 15, fraction = 4, message = "must have at most 4 decimal places")
        BigDecimal price,

        @Schema(example = "1")
        @NotNull(message = "categoryId is required")
        @Positive
        Long categoryId,

        /**
         * Opening stock, used only on create.
         *
         * <p>Ignored on update: changing stock is a separate operation with its own
         * concurrency guarantees, and folding it into a catalogue edit would mean a
         * product rename could clobber a concurrent sale.
         */
        @Schema(example = "25", description = "Initial stock; ignored when updating")
        @PositiveOrZero(message = "must not be negative")
        Integer initialStock
) {
}
