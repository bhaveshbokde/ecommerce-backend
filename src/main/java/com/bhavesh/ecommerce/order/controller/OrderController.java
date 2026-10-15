package com.bhavesh.ecommerce.order.controller;

import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.idempotency.service.IdempotencyService;
import com.bhavesh.ecommerce.order.dto.OrderResponse;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.service.OrderService;
import com.bhavesh.ecommerce.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout and order history for the authenticated customer.
 *
 * <p>Every read is scoped by {@code principal.getId()} inside the repository query,
 * so an order belonging to someone else is indistinguishable from one that does not
 * exist — both answer 404. That is deliberate: a 403 would confirm the id is real.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
@Tag(name = "Orders", description = "Checkout and order history")
public class OrderController {

    private static final String ENDPOINT = "POST /api/v1/orders";
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Turns the cart into an order.
     *
     * <p>Honours an optional {@code Idempotency-Key}. Without one, a retry after a
     * lost response creates a second order — which is the client's choice to make,
     * so the header is optional rather than mandatory. Payments take the opposite
     * line and require it, because a duplicate charge is not something a caller
     * gets to opt into.
     *
     * <p>The request has no body, so the hash that detects key reuse is computed
     * over the one thing that identifies this operation: the caller. A key reused
     * by the same user on this endpoint always means the same request.
     */
    @PostMapping
    @Operation(summary = "Check out the cart",
            description = "Reserves stock atomically, snapshots prices into order lines and "
                    + "empties the cart — all in one transaction. The order is created as "
                    + "CREATED/PENDING; pay for it with POST /api/v1/payments.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created"),
            @ApiResponse(responseCode = "200", description = "Replayed from a previous Idempotency-Key"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "422", description = "Cart empty, or a product is unavailable", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional. Retrying with the same key replays the original response.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        var result = idempotencyService.execute(
                principal.getId(), ENDPOINT, idempotencyKey,
                "checkout:" + principal.getId(),
                OrderResponse.class,
                () -> orderService.checkout(principal.getId()));

        // 200 for a replay: nothing was created this time, so 201 would be a lie.
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.value());
    }

    @GetMapping
    @Operation(summary = "The caller's order history",
            description = "Summary view — order lines are omitted so the listing stays one "
                    + "query. Fetch an order by id for its lines.")
    public ResponseEntity<PageResponse<OrderResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(orderService.listForUser(principal.getId(), status, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one of the caller's orders",
            description = "404 for an order that does not exist and for one belonging to "
                    + "another customer — the API does not confirm that someone else's id "
                    + "is real.")
    public ResponseEntity<OrderResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id) {
        return ResponseEntity.ok(orderService.get(id, principal.getId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Releases the reserved stock. Legal up to PROCESSING; a shipped "
                    + "order answers 409, because the remedy for that is a return, not a "
                    + "cancellation.")
    public ResponseEntity<OrderResponse> cancel(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancel(id, principal.getId()));
    }
}
