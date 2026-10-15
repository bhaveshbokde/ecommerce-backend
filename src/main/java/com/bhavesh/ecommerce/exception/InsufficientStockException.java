package com.bhavesh.ecommerce.exception;

/**
 * Raised when a reservation cannot be satisfied.
 *
 * <p>This is thrown when the conditional reservation UPDATE affects zero rows —
 * i.e. the database observed, atomically, that there was not enough stock. It is
 * <em>not</em> thrown from a read-then-check in Java, which would be subject to
 * the race it is supposed to prevent.
 *
 * <p>409 Conflict rather than 422: the request was entirely reasonable when the
 * customer made it, and may succeed later if stock is replenished or another
 * reservation is released. It is a conflict with the current state of the world,
 * not a malformed instruction.
 */
public class InsufficientStockException extends ApiException {

    private final transient Long productId;
    private final transient int requested;
    private final transient int available;

    public InsufficientStockException(Long productId, String productName, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                "Only %d unit(s) of '%s' are available; %d were requested"
                        .formatted(available, productName, requested));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public Long productId() {
        return productId;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
