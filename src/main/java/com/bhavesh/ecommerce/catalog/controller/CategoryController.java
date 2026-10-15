package com.bhavesh.ecommerce.catalog.controller;

import com.bhavesh.ecommerce.catalog.dto.CategoryRequest;
import com.bhavesh.ecommerce.catalog.dto.CategoryResponse;
import com.bhavesh.ecommerce.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Category browsing (public) and management (administrator).
 *
 * <p>Both live on the same path, separated by HTTP method in
 * {@link com.bhavesh.ecommerce.config.SecurityConfig}: {@code GET} is public,
 * everything else requires {@code ROLE_ADMIN}. Splitting them across
 * {@code /categories} and {@code /admin/categories} would duplicate the path for no
 * gain — a category has one canonical URL, and who may change it is an
 * authorization question, not a routing one.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Product categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "List active categories", description = "Public. Cached in Redis.")
    public ResponseEntity<List<CategoryResponse>> list() {
        return ResponseEntity.ok(categoryService.listActive());
    }

    @GetMapping("/{id}")
    @SecurityRequirements
    @Operation(summary = "Fetch one category", description = "Public. Cached in Redis.")
    public ResponseEntity<CategoryResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.get(id));
    }

    // The administrator listing — which includes deactivated categories — lives on
    // /api/v1/admin/categories, not here. GET under this path is permitAll, so an
    // admin-only read placed alongside these would be matched by that rule and
    // served to anyone. Keeping it on the admin prefix makes the authorization
    // rule structural rather than dependent on the order of matchers.

    @PostMapping
    @Operation(summary = "Create a category", description = "Administrator only.")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a category", description = "Administrator only.")
    public ResponseEntity<CategoryResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Activate or deactivate a category",
            description = "Administrator only. Deactivating hides the category and its products "
                    + "from the catalogue without touching order history.")
    public ResponseEntity<CategoryResponse> setActive(@PathVariable Long id,
                                                       @RequestParam boolean active) {
        return ResponseEntity.ok(categoryService.setActive(id, active));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an empty category",
            description = "Administrator only. Returns 409 if any product still references it; "
                    + "deactivate it instead.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
