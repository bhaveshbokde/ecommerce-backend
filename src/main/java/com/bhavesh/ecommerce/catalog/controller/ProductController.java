package com.bhavesh.ecommerce.catalog.controller;

import com.bhavesh.ecommerce.catalog.dto.ProductRequest;
import com.bhavesh.ecommerce.catalog.dto.ProductResponse;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Catalogue browsing (public) and management (administrator).
 *
 * <h2>Why the sort field is whitelisted</h2>
 * Spring Data will happily sort by any property name a caller supplies, and that
 * name ends up in the generated SQL's ORDER BY. A request for
 * {@code ?sort=category.products.size} is a performance incident waiting to happen,
 * and sorting by an unindexed column on a large table is a cheap way to make the
 * database work hard. {@link #ALLOWED_SORTS} is the small set that is both
 * meaningful and indexed.
 *
 * <p>Page size is capped for the same reason: {@code ?size=1000000} should not be a
 * denial-of-service primitive.
 */
@RestController
@RequestMapping("/api/v1/products")
@Validated
@Tag(name = "Products", description = "Catalogue browsing and management")
public class ProductController {

    /** Sortable fields. Anything else falls back to {@code id}. */
    private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "price", "createdAt");

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "Search the catalogue",
            description = "Public. Returns active products in active categories. All filters "
                    + "are optional and are bound as parameters, never concatenated into SQL.")
    public ResponseEntity<PageResponse<ProductResponse>> search(
            @Parameter(description = "Restrict to one category")
            @RequestParam(required = false) Long categoryId,

            @Parameter(description = "Inclusive lower bound")
            @RequestParam(required = false) @PositiveOrZero BigDecimal minPrice,

            @Parameter(description = "Inclusive upper bound")
            @RequestParam(required = false) @PositiveOrZero BigDecimal maxPrice,

            @Parameter(description = "Case-insensitive substring match on the name")
            @RequestParam(required = false) String search,

            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "One of: id, name, price, createdAt")
            @RequestParam(defaultValue = "id") String sort,

            @RequestParam(defaultValue = "ASC") Sort.Direction direction) {

        return ResponseEntity.ok(productService.search(categoryId, minPrice, maxPrice, search,
                pageable(page, size, sort, direction)));
    }

    @GetMapping("/{id}")
    @SecurityRequirements
    @Operation(summary = "Fetch one product", description = "Public. Cached in Redis.")
    public ResponseEntity<ProductResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(productService.get(id));
    }

    // The administrator listing — which includes deactivated products — lives on
    // /api/v1/admin/products, not here. GET under this path is permitAll, so an
    // admin-only read placed alongside these would be matched by that rule and
    // served to anyone. Keeping it on the admin prefix makes the authorization
    // rule structural rather than dependent on the order of matchers.

    @PostMapping
    @Operation(summary = "Create a product",
            description = "Administrator only. Also creates its inventory row, at "
                    + "`initialStock` or zero.")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a product",
            description = "Administrator only. The SKU is immutable and stock is untouched — "
                    + "use the inventory endpoints for that. A price change affects future "
                    + "orders only; existing order lines keep their snapshot.")
    public ResponseEntity<ProductResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Activate or deactivate a product",
            description = "Administrator only. Preferred over deletion, which would orphan "
                    + "order history.")
    public ResponseEntity<ProductResponse> setActive(@PathVariable Long id,
                                                      @RequestParam boolean active) {
        return ResponseEntity.ok(productService.setActive(id, active));
    }

    /** Falls back to {@code id} for an unrecognised field rather than rejecting the request. */
    private Pageable pageable(int page, int size, String sort, Sort.Direction direction) {
        String field = ALLOWED_SORTS.contains(sort) ? sort : "id";
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by(direction, field));
    }
}
