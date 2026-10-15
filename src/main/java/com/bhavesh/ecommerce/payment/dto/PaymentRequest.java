package com.bhavesh.ecommerce.payment.dto;

import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Pay for an order.
 *
 * <p>No amount field. The customer does not get to say what they owe — the server
 * reads the total from the order. Accepting a client-supplied amount would be the
 * classic price-tampering vulnerability.
 *
 * <p>The idempotency key arrives in the {@code Idempotency-Key} header rather than
 * here. It describes the delivery of the request, not the payment, and keeping it
 * in a header means a generic filter or a future endpoint can honour it without
 * every DTO growing the same field.
 */
@Schema(name = "PaymentRequest", description = "Charge an order against the simulated provider")
public record PaymentRequest(

        @Schema(example = "42")
        @NotNull(message = "orderId is required")
        @Positive(message = "must be a positive id")
        Long orderId,

        /**
         * Forces a particular provider response.
         *
         * <p>Honoured only outside the {@code prod} profile — {@code PaymentService}
         * ignores it there and falls back to the configured default, so a request
         * cannot talk a production deployment into declining or succeeding on
         * demand.
         *
         * <p>It exists so the failure paths are demonstrable: releasing a
         * reservation after a decline is the interesting half of this flow, and it
         * would be untestable against a provider that always says yes.
         */
        @Schema(example = "SUCCESS",
                description = "Test hook; ignored in the prod profile. Defaults to the configured outcome.")
        SimulatedOutcome simulateOutcome
) {
}
