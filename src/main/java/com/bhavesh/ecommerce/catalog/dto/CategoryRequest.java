package com.bhavesh.ecommerce.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update a category. Administrator only.
 *
 * <p>No {@code slug} field: it is derived from the name by
 * {@link com.bhavesh.ecommerce.catalog.entity.Category#slugify}. Accepting one
 * would let a caller submit a value that violates {@code ck_categories_slug}, and
 * would allow a slug that contradicts its own name.
 */
@Schema(name = "CategoryRequest", description = "Category details")
public record CategoryRequest(

        @Schema(example = "Electronics")
        @NotBlank(message = "name is required")
        @Size(max = 80, message = "must not exceed 80 characters")
        String name,

        @Schema(example = "Phones, laptops and accessories")
        @Size(max = 500, message = "must not exceed 500 characters")
        String description
) {
}
