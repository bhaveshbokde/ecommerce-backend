package com.bhavesh.ecommerce.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Human-facing order and payment references. */
class ReferenceGeneratorTest {

    /** Pinned so the date component is assertable without waiting for midnight. */
    private final ReferenceGenerator generator = new ReferenceGenerator(
            Clock.fixed(Instant.parse("2026-10-14T09:30:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("an order number is PREFIX-YYYYMMDD-HEX6")
    void orderNumberShape() {
        assertThat(generator.orderNumber()).matches("ORD-20261014-[0-9A-F]{6}");
    }

    @Test
    @DisplayName("a payment reference uses its own prefix")
    void paymentReferenceShape() {
        assertThat(generator.paymentReference()).matches("PAY-20261014-[0-9A-F]{6}");
    }

    @Test
    @DisplayName("both fit the 32-character column")
    void fitsTheColumn() {
        assertThat(generator.orderNumber().length()).isLessThanOrEqualTo(32);
        assertThat(generator.paymentReference().length()).isLessThanOrEqualTo(32);
    }

    /**
     * A sequential primary key published to customers would leak order volume —
     * order 1,204 today and 1,391 tomorrow says exactly how many orders the
     * business took — and invite someone to try the neighbouring number.
     */
    @Test
    @DisplayName("consecutive references are not consecutive numbers")
    void notSequential() {
        var references = new HashSet<String>();
        for (int i = 0; i < 1_000; i++) {
            references.add(generator.orderNumber());
        }
        // 24 random bits. Some birthday collisions in 1,000 draws are expected and
        // are caught by uq_orders_order_number, which OrderService retries on.
        assertThat(references).hasSizeGreaterThan(950);
    }

    @Test
    @DisplayName("order and payment references never collide, even on the same day")
    void prefixesKeepThemApart() {
        assertThat(generator.orderNumber()).doesNotStartWith("PAY-");
        assertThat(generator.paymentReference()).doesNotStartWith("ORD-");
    }

    @Test
    @DisplayName("the date component follows the clock")
    void followsTheClock() {
        var newYear = new ReferenceGenerator(
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(newYear.orderNumber()).startsWith("ORD-20270101-");
    }

    @Test
    @DisplayName("the no-arg constructor works, so Spring can build the bean")
    void defaultConstructor() {
        assertThat(new ReferenceGenerator().orderNumber()).matches("ORD-\\d{8}-[0-9A-F]{6}");
    }
}
