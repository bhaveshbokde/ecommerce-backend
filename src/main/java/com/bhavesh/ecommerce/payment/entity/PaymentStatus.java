package com.bhavesh.ecommerce.payment.entity;

/**
 * Lifecycle of one payment attempt.
 *
 * <p>Shared with {@code orders.payment_status}, so the two columns can never
 * disagree about what the words mean. Both database CHECK constraints list exactly
 * these four names.
 *
 * <p>Kept separate from the order's own {@code status}: a CONFIRMED order with a
 * REFUNDED payment is a real and meaningful state, and collapsing the two
 * lifecycles into one enum would require a constant for every combination.
 */
public enum PaymentStatus {

    /** Created, not yet resolved. The partial unique index treats this as live. */
    PENDING,

    /** The provider accepted the charge. Terminal. */
    SUCCEEDED,

    /**
     * Declined, or the provider could not be reached. Terminal <em>for this
     * attempt</em> — the partial unique index deliberately excludes FAILED rows so
     * the customer can retry with a fresh idempotency key.
     */
    FAILED,

    /** Previously succeeded, then reversed. Terminal. */
    REFUNDED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED;
    }
}
