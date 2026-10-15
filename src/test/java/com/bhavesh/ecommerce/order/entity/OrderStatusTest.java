package com.bhavesh.ecommerce.order.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order transition table.
 *
 * <p>Worth testing exhaustively rather than by example: the table is the one place
 * the fulfilment rules exist, and a wrong entry is a silent policy change — an
 * order that can be cancelled after shipping, or one that can never be delivered.
 */
class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "CREATED,    CONFIRMED",
            "CREATED,    CANCELLED",
            "CONFIRMED,  PROCESSING",
            "CONFIRMED,  CANCELLED",
            "PROCESSING, SHIPPED",
            "PROCESSING, CANCELLED",
            "SHIPPED,    DELIVERED",
    })
    void allowsLegalTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Skipping ahead would mean an order that was never paid for is marked
            // delivered.
            "CREATED,    DELIVERED",
            "CREATED,    SHIPPED",
            "CREATED,    PROCESSING",
            // The parcel has gone; the remedy is a return, not a cancellation.
            "SHIPPED,    CANCELLED",
            // Going backwards would let someone un-ship or un-cancel an order.
            "DELIVERED,  SHIPPED",
            "CONFIRMED,  CREATED",
            "CANCELLED,  CREATED",
            "CANCELLED,  CONFIRMED",
            "DELIVERED,  CANCELLED",
    })
    void refusesIllegalTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("no status may transition to itself")
    void noSelfTransition(OrderStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("every status has an entry, so no lookup can return null")
    void everyStatusHasAnEntry(OrderStatus status) {
        assertThat(status.allowedTransitions()).isNotNull();
    }

    @Test
    @DisplayName("a null target is refused rather than throwing")
    void nullTargetIsRefused() {
        assertThat(OrderStatus.CREATED.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("DELIVERED and CANCELLED are the only terminal states")
    void terminalStates() {
        assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.CREATED.isTerminal()).isFalse();
        assertThat(OrderStatus.CONFIRMED.isTerminal()).isFalse();
        assertThat(OrderStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(OrderStatus.SHIPPED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("cancellation is legal only while stock is still on the shelf")
    void cancellableStates() {
        assertThat(OrderStatus.CREATED.isCancellable()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCancellable()).isTrue();
        assertThat(OrderStatus.PROCESSING.isCancellable()).isTrue();
        assertThat(OrderStatus.SHIPPED.isCancellable()).isFalse();
        assertThat(OrderStatus.DELIVERED.isCancellable()).isFalse();
        assertThat(OrderStatus.CANCELLED.isCancellable()).isFalse();
    }

    /**
     * Regression test. The first version of the enum wrapped only the outer map,
     * which left the {@code EnumSet} values mutable — so a caller could do
     * {@code SHIPPED.allowedTransitions().add(CANCELLED)} and permanently grant
     * themselves a transition for the lifetime of the JVM. The map being read-only
     * said nothing about what was inside it.
     */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("the returned set cannot be used to edit the table")
    void transitionSetIsImmutable(OrderStatus status) {
        var allowed = status.allowedTransitions();
        assertThatThrownBy(() -> allowed.add(OrderStatus.CREATED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(allowed::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
