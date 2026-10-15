package com.bhavesh.ecommerce.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money arithmetic.
 *
 * <p>Every assertion uses {@code isEqualByComparingTo} rather than
 * {@code isEqualTo}. {@link BigDecimal#equals} compares scale as well as value, so
 * {@code 10.00} and {@code 10.0000} are <em>not</em> equal — a distinction that
 * matters for storage and never for arithmetic. Using the wrong one here would
 * produce tests that fail for reasons no user could observe.
 */
class MoneyTest {

    @Nested
    @DisplayName("float is the wrong type for money")
    class FloatingPointContrast {

        /**
         * Demonstrates the bug this class exists to prevent, so the reason for
         * {@code BigDecimal} is recorded as an executable fact rather than a claim
         * in a comment.
         */
        @Test
        @DisplayName("double arithmetic loses the cent; BigDecimal does not")
        void doubleLosesPrecision() {
            double withDouble = 0.1 + 0.2;
            assertThat(withDouble).isNotEqualTo(0.3);
            assertThat(withDouble).isEqualTo(0.30000000000000004);

            BigDecimal withBigDecimal = new BigDecimal("0.1").add(new BigDecimal("0.2"));
            assertThat(withBigDecimal).isEqualByComparingTo("0.3");
        }

        @Test
        @DisplayName("the error compounds across a basket")
        void errorCompounds() {
            double runningTotal = 0.0;
            for (int i = 0; i < 100; i++) {
                runningTotal += 19.99;
            }
            // 100 items at 19.99 is 1999.00 exactly. It is not.
            assertThat(runningTotal).isNotEqualTo(1999.00);

            BigDecimal exact = BigDecimal.ZERO;
            for (int i = 0; i < 100; i++) {
                exact = Money.add(exact, new BigDecimal("19.99"));
            }
            assertThat(exact).isEqualByComparingTo("1999.00");
        }
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("pads to the storage scale so the value round-trips through NUMERIC(19,4)")
        void padsToStorageScale() {
            assertThat(Money.normalize(new BigDecimal("10.5")).scale()).isEqualTo(Money.STORAGE_SCALE);
        }

        @Test
        @DisplayName("null becomes zero rather than propagating a NullPointerException")
        void nullBecomesZero() {
            assertThat(Money.normalize(null)).isEqualByComparingTo("0");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "10.00004, 10.0000",     // rounds down
                "10.00005, 10.0001",     // HALF_UP rounds away from zero at the midpoint
                "10.00006, 10.0001",
        })
        @DisplayName("rounds HALF_UP at the fourth decimal place")
        void roundsHalfUp(String input, String expected) {
            assertThat(Money.normalize(new BigDecimal(input))).isEqualByComparingTo(expected);
        }
    }

    @Nested
    @DisplayName("lineTotal")
    class LineTotal {

        @Test
        @DisplayName("multiplies exactly")
        void multiplies() {
            assertThat(Money.lineTotal(new BigDecimal("19.99"), 3)).isEqualByComparingTo("59.97");
        }

        @Test
        @DisplayName("keeps a price with four decimal places exact")
        void keepsFourDecimals() {
            assertThat(Money.lineTotal(new BigDecimal("0.3333"), 3)).isEqualByComparingTo("0.9999");
        }

        /**
         * A zero or negative quantity is a programming error, not user input — the
         * DTO and the CHECK constraint both reject it long before here. Throwing
         * makes a bug in calling code loud instead of producing a zero or negative
         * line total that would quietly corrupt an order's total.
         */
        @ParameterizedTest
        @CsvSource({"0", "-1", "-100"})
        @DisplayName("rejects a non-positive quantity")
        void rejectsNonPositiveQuantity(int quantity) {
            assertThatThrownBy(() -> Money.lineTotal(BigDecimal.TEN, quantity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantity must be positive");
        }

        @Test
        @DisplayName("rejects a null unit price")
        void rejectsNullPrice() {
            assertThatThrownBy(() -> Money.lineTotal(null, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unitPrice");
        }
    }

    @Nested
    @DisplayName("display")
    class Display {

        @Test
        @DisplayName("trims the storage scale to two places for presentation")
        void trimsToTwoPlaces() {
            assertThat(Money.display(new BigDecimal("10.0000")).toPlainString()).isEqualTo("10.00");
        }

        @Test
        @DisplayName("rounds rather than truncating")
        void rounds() {
            assertThat(Money.display(new BigDecimal("10.005")).toPlainString()).isEqualTo("10.01");
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        void addAndSubtractTreatNullAsZero() {
            assertThat(Money.add(null, new BigDecimal("5"))).isEqualByComparingTo("5");
            assertThat(Money.subtract(new BigDecimal("5"), null)).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("isZero compares by value, so 0.0000 is zero")
        void isZeroIgnoresScale() {
            assertThat(Money.isZero(new BigDecimal("0.0000"))).isTrue();
            assertThat(Money.isZero(null)).isTrue();
            assertThat(Money.isZero(new BigDecimal("0.0001"))).isFalse();
        }

        @Test
        void isPositive() {
            assertThat(Money.isPositive(new BigDecimal("0.0001"))).isTrue();
            assertThat(Money.isPositive(BigDecimal.ZERO)).isFalse();
            assertThat(Money.isPositive(new BigDecimal("-1"))).isFalse();
            assertThat(Money.isPositive(null)).isFalse();
        }
    }
}
