package com.bhavesh.ecommerce.catalog.dto;

import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.common.util.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * A product as the API presents it.
 *
 * <p>{@code price} is rendered at {@link Money#DISPLAY_SCALE} (two places) even
 * though it is stored at four. The extra scale exists so intermediate arithmetic
 * does not lose precision; showing {@code 74999.0000} in a response would be
 * noise.
 *
 * <p>Carries {@code inStock} rather than an exact count. A precise figure on a
 * public endpoint is a free inventory feed for a competitor, and it changes
 * between the response and the click anyway. Administrators get the number from
 * the inventory endpoint.
 */
@Schema(name = "ProductResponse", description = "A catalogue item")
public record ProductResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "ELEC-LAPTOP-001")
        String sku,

        @Schema(example = "14-inch Developer Laptop")
        String name,

        String description,

        @Schema(example = "74999.00")
        BigDecimal price,

        @Schema(example = "1")
        Long categoryId,

        @Schema(example = "Electronics")
        String categoryName,

        boolean active,

        @Schema(example = "true", description = "Whether any units are currently sellable")
        boolean inStock
) {

    /**
     * @param product must have been loaded with its category — see
     *                {@code ProductRepository.findByIdWithCategory}. Mapping a
     *                lazily-loaded association outside a transaction is where
     *                {@code LazyInitializationException} comes from.
     */
    public static ProductResponse from(Product product, boolean inStock) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                Money.display(product.getPrice()),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.isActive(),
                inStock);
    }
}
