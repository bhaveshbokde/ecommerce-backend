package com.bhavesh.ecommerce.admin.controller;

import com.bhavesh.ecommerce.catalog.dto.CategoryResponse;
import com.bhavesh.ecommerce.catalog.dto.ProductResponse;
import com.bhavesh.ecommerce.catalog.service.CategoryService;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.inventory.dto.InventoryResponse;
import com.bhavesh.ecommerce.inventory.dto.StockAdjustmentRequest;
import com.bhavesh.ecommerce.inventory.service.InventoryService;
import com.bhavesh.ecommerce.order.dto.OrderResponse;
import com.bhavesh.ecommerce.order.dto.UpdateOrderStatusRequest;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator-only operations: inventory, order fulfilment and the unfiltered
 * catalogue views.
 *
 * <p>Everything lives under {@code /api/v1/admin}, which
 * {@link com.bhavesh.ecommerce.config.SecurityConfig} restricts to
 * {@code ROLE_ADMIN} as a single rule. The class-level {@code @PreAuthorize} is a
 * second, independent check — if someone later reorders the URL matchers and a
 * broader rule shadows the admin one, method security still refuses. Two
 * mechanisms, so one mistake is not enough.
 *
 * <p>These views are deliberately separate from the public controllers rather than
 * being extra paths under {@code /api/v1/products}: {@code GET} there is
 * {@code permitAll}, so an admin-only read placed alongside the public ones would
 * be matched by that rule and served to anyone.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration", description = "Inventory, fulfilment and unfiltered catalogue views")
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductService productService;
    private final CategoryService categoryService;
    private final InventoryService inventoryService;
    private final OrderService orderService;

    public AdminController(ProductService productService,
                           CategoryService categoryService,
                           InventoryService inventoryService,
                           OrderService orderService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.inventoryService = inventoryService;
        this.orderService = orderService;
    }

    // -------------------------------------------------------------- catalogue --

    @GetMapping("/products")
    @Operation(summary = "Every product, including deactivated ones and those in "
            + "deactivated categories")
    public ResponseEntity<PageResponse<ProductResponse>> listProducts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "id"));
        return ResponseEntity.ok(productService.listAll(pageable));
    }

    @GetMapping("/categories")
    @Operation(summary = "Every category, including deactivated ones")
    public ResponseEntity<List<CategoryResponse>> listCategories() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    // -------------------------------------------------------------- inventory --

    @GetMapping("/inventory/{productId}")
    @Operation(summary = "Stock for one product",
            description = "Exact figures. The public catalogue exposes only a boolean — a "
                    + "precise count there would be a free inventory feed for a competitor.")
    public ResponseEntity<InventoryResponse> getInventory(@PathVariable Long productId) {
        return ResponseEntity.ok(inventoryService.get(productId));
    }

    @PostMapping("/inventory/{productId}/adjust")
    @Operation(summary = "Adjust stock",
            description = """
                    The quantity is a **delta**, not an absolute figure, so two deliveries \
                    recorded concurrently both count. Applied as a single guarded SQL \
                    statement rather than a read-modify-write.

                    A negative adjustment that would drive stock below zero is refused with \
                    409 rather than clamped to zero — silently losing a correction is worse \
                    than rejecting it.""")
    public ResponseEntity<InventoryResponse> adjustStock(
            @PathVariable Long productId,
            @Valid @RequestBody StockAdjustmentRequest request) {

        return ResponseEntity.ok(
                inventoryService.adjust(productId, request.quantity(), request.reason()));
    }

    // ------------------------------------------------------------- fulfilment --

    @GetMapping("/orders")
    @Operation(summary = "Every order, optionally filtered by status")
    public ResponseEntity<PageResponse<OrderResponse>> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(orderService.listAll(status, pageable));
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Fetch any order, with its lines")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getAsAdmin(id));
    }

    @PatchMapping("/orders/{id}/status")
    @Operation(summary = "Advance an order's fulfilment status",
            description = """
                    Validated against the transition table in `OrderStatus`, so an illegal \
                    jump such as CREATED → DELIVERED is refused with 422 and the allowed set \
                    is listed in the response.

                    Moving to SHIPPED consumes the stock reservation; moving to CANCELLED \
                    releases it.""")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {

        return ResponseEntity.ok(orderService.updateStatus(id, request.status()));
    }

    @PostMapping("/orders/{id}/cancel")
    @Operation(summary = "Cancel any order, releasing its stock")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancelAsAdmin(id));
    }
}
