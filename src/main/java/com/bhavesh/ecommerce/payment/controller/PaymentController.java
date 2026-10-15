package com.bhavesh.ecommerce.payment.controller;

import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.payment.dto.PaymentRequest;
import com.bhavesh.ecommerce.payment.dto.PaymentResponse;
import com.bhavesh.ecommerce.payment.service.PaymentService;
import com.bhavesh.ecommerce.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payments against the simulated provider.
 *
 * <p><strong>No real money moves and no card details exist.</strong> The provider
 * is a deterministic simulator — see
 * {@link com.bhavesh.ecommerce.payment.provider.SimulatedPaymentProvider} for why
 * that is a scope decision rather than an omission.
 *
 * <h2>HTTP status versus payment status</h2>
 * These are two different things and the API keeps them apart. The HTTP status
 * describes the API call: 201 means an attempt was created, 200 means a stored
 * outcome was replayed. The {@code status} field in the body describes the money:
 * SUCCEEDED or FAILED.
 *
 * <p>A declined card therefore returns 201 with {@code "status": "FAILED"}, not a
 * 4xx. The request was valid, it was processed, and the answer was no — an error
 * status would say the API failed, which it did not. It would also be impossible to
 * implement honestly: throwing to produce a 402 would roll back the transaction
 * that records the failure and releases the stock.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
@Tag(name = "Payments", description = "Simulated payment processing")
public class PaymentController {

    private static final int MAX_PAGE_SIZE = 50;

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Pay for an order",
            description = """
                    Requires an `Idempotency-Key` header — unlike checkout, a duplicate charge \
                    is not something a caller gets to opt into. Retrying with the same key \
                    returns the original outcome rather than charging again.

                    The amount is read from the order; there is no amount field, so it cannot \
                    be tampered with.

                    On success the order moves to CONFIRMED. On failure the reserved stock is \
                    released in the same transaction and the order stays CREATED so another \
                    attempt is possible.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Attempt processed — check `status` in the body for the outcome"),
            @ApiResponse(responseCode = "200",
                    description = "Replayed: this Idempotency-Key was already processed, nothing was charged"),
            @ApiResponse(responseCode = "400", description = "Idempotency-Key header missing", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such order, or not the caller's", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "A payment already exists for this order", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "422", description = "Key reused for a different order", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<PaymentResponse> pay(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Required. A unique string per logical payment attempt.",
                    required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        var outcome = paymentService.pay(principal.getId(), request, idempotencyKey);

        // 200 for a replay: no attempt was created this time, so 201 would be a lie
        // about what the request did. The outcome itself is in the body either way.
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.payment());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one of the caller's payments")
    public ResponseEntity<PaymentResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long id) {
        return ResponseEntity.ok(paymentService.get(id, principal.getId()));
    }

    @GetMapping
    @Operation(summary = "The caller's payment history")
    public ResponseEntity<PageResponse<PaymentResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return ResponseEntity.ok(paymentService.listForUser(principal.getId(),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Every attempt against one order",
            description = "Includes failed attempts, newest first. Scoped through the order, "
                    + "so another customer's attempts are not reachable.")
    public ResponseEntity<List<PaymentResponse>> listForOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.listForOrder(orderId, principal.getId()));
    }
}
