package com.bhavesh.ecommerce.catalog.dto;

import com.bhavesh.ecommerce.catalog.entity.Category;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A category as the API presents it.
 *
 * <p>A record, which is why
 * {@link com.bhavesh.ecommerce.config.RedisCacheConfig} cannot use Jackson's
 * default typing: records are implicitly final, and {@code NON_FINAL} typing skips
 * exactly those classes. Each cache therefore binds a concrete serializer.
 */
@Schema(name = "CategoryResponse", description = "A product category")
public record CategoryResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "Electronics")
        String name,

        @Schema(example = "electronics", description = "URL-safe identifier derived from the name")
        String slug,

        String description,

        boolean active
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getDescription(), category.isActive());
    }
}
