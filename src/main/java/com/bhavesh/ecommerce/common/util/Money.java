package com.bhavesh.ecommerce.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary helpers. Every amount in this application is a {@link BigDecimal};
 * binary floating point is never used for money, because values such as 0.1 have
 * no exact {@code double} representation and the error compounds across every
 * summed order line.
 *
 * <p>Storage scale is 4, matching {@code NUMERIC(19,4)} in the schema, which
 * leaves room for sub-paisa intermediate values. Presentation rounds to 2 at the
 * edge.
 *
 * <p>{@link RoundingMode#HALF_UP} throughout — it matches ordinary financial
 * rounding expectations and, unlike {@code HALF_EVEN}, is what a customer
 * checking the arithmetic on their receipt will reproduce.
 */
public final class Money {

    /** Scale used for persistence and intermediate arithmetic. */
    public static final int STORAGE_SCALE = 4;

    /** Scale used when presenting an amount in an API response. */
    public static final int DISPLAY_SCALE = 2;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(DISPLAY_SCALE, ROUNDING);

    private Money() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /** Normalises to storage scale. {@code null} becomes zero. */
    public static BigDecimal normalize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(STORAGE_SCALE, ROUNDING)
                             : value.setScale(STORAGE_SCALE, ROUNDING);
    }

    /** Rounds to display scale. {@code null} becomes {@code 0.00}. */
    public static BigDecimal display(BigDecimal value) {
        return value == null ? ZERO : value.setScale(DISPLAY_SCALE, ROUNDING);
    }

    /**
     * {@code unitPrice * quantity} at storage scale.
     *
     * <p>The multiplication happens before any rounding, so a price with four
     * decimal places multiplied by a large quantity does not accumulate the error
     * that rounding each unit first would introduce.
     */
    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        if (unitPrice == null) {
            throw new IllegalArgumentException("unitPrice must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(STORAGE_SCALE, ROUNDING);
    }

    /** Null-safe addition at storage scale. */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return normalize(nz(a).add(nz(b)));
    }

    /** Null-safe subtraction at storage scale. */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return normalize(nz(a).subtract(nz(b)));
    }

    /** True when the value is non-null and strictly greater than zero. */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** True when the value is null or numerically zero, regardless of scale. */
    public static boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
