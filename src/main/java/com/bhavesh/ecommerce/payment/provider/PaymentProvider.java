package com.bhavesh.ecommerce.payment.provider;

import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;

import java.math.BigDecimal;

/**
 * The boundary between this application and whatever actually moves money.
 *
 * <p>An interface rather than a direct call into the simulator, for one concrete
 * reason: swapping in a real gateway should be a new implementation of this type
 * and a bean definition, not an edit to {@code PaymentService}. Everything
 * interesting about the payment flow — the idempotency key, the stock release on
 * failure, the order state machine — lives on this side of the boundary and does
 * not change with the provider.
 *
 * <p>It also makes the service testable without the simulator: a stub returning
 * {@link ChargeResult#declined} exercises the compensation path directly.
 */
public interface PaymentProvider {

    /**
     * Attempts a charge.
     *
     * <p>Implementations must not throw for a declined charge — a decline is an
     * answer, not an error, and the caller needs to record it. Throwing is reserved
     * for "I could not get an answer at all", which this interface models as
     * {@link ChargeResult#error} rather than an exception so the caller is forced
     * to handle it.
     *
     * @param reference      this application's own reference for the charge
     * @param amount         exact decimal; never a {@code double}
     * @param requestedOutcome what the simulator should do, or null for the configured default
     */
    ChargeResult charge(String reference, BigDecimal amount, String currency,
                        SimulatedOutcome requestedOutcome);

    /**
     * The outcome of a charge.
     *
     * @param successful        whether money (notionally) moved
     * @param providerReference the provider's own id for the charge; null on failure
     * @param failureReason     why it failed; null on success
     * @param retryable         whether a later attempt might succeed. A decline is not
     *                          retryable with the same instrument; an unreachable
     *                          provider is. The caller records this so support can
     *                          tell "the card was refused" from "we could not ask".
     */
    record ChargeResult(boolean successful, String providerReference,
                        String failureReason, boolean retryable) {

        public static ChargeResult succeeded(String providerReference) {
            return new ChargeResult(true, providerReference, null, false);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(false, null, reason, false);
        }

        public static ChargeResult error(String reason) {
            return new ChargeResult(false, null, reason, true);
        }
    }
}
