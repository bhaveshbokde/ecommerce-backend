package com.bhavesh.ecommerce.payment.dto;

import com.bhavesh.ecommerce.common.util.Money;
import com.bhavesh.ecommerce.payment.entity.Payment;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The outcome of a payment attempt.
 *
 * <p>Deliberately omits {@code idempotencyKey}. It is client-chosen and may be
 * guessable; echoing it back adds nothing the caller does not have and would place
 * it in any log that records response bodies.
 *
 * <p>A replayed request returns this exact body again, byte for byte, from
 * {@code idempotency_keys.response_body} — a retry after a lost response must be
 * indistinguishable from the original call.
 */
@Schema(name = "PaymentResponse", description = "Result of a payment attempt")
public record PaymentResponse(

        @Schema(example = "17")
        Long id,

        @Schema(example = "PAY-20261014-3C81D0")
        String paymentReference,

        @Schema(example = "42")
        Long orderId,

        @Schema(example = "SUCCEEDED")
        PaymentStatus status,

        @Schema(example = "149998.00")
        BigDecimal amount,

        @Schema(example = "INR")
        String currency,

        @Schema(example = "SIM-9F2C4A81", description = "Identifier returned by the simulated provider")
        String providerReference,

        @Schema(example = "Card declined by issuer", description = "Populated only on failure")
        String failureReason,

        Instant completedAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getOrderId(),
                payment.getStatus(),
                Money.display(payment.getAmount()),
                payment.getCurrency(),
                payment.getProviderReference(),
                payment.getFailureReason(),
                payment.getCompletedAt());
    }
}
