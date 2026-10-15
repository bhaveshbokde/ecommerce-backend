package com.bhavesh.ecommerce.order.entity;

import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The order aggregate: totals, the status machine and the price snapshot. */
class OrderTest {

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order("ORD-20261014-ABC123", 42L, "INR");
    }

    @Nested
    @DisplayName("totals")
    class Totals {

        @Test
        @DisplayName("recomputed on every addItem, so the total is never momentarily stale")
        void recomputedOnEachAdd() {
            order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("6499.00"), 2);
            assertThat(order.getSubtotal()).isEqualByComparingTo("12998.00");

            order.addItem(2L, "SKU-2", "Laptop", new BigDecimal("89999.00"), 1);
            assertThat(order.getSubtotal()).isEqualByComparingTo("102997.00");
        }

        @Test
        @DisplayName("total equals subtotal — this project models no tax or shipping")
        void totalEqualsSubtotal() {
            order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("6499.00"), 2);
            assertThat(order.getTotal()).isEqualByComparingTo(order.getSubtotal());
        }

        @Test
        @DisplayName("an empty order totals zero rather than null")
        void emptyOrderTotalsZero() {
            assertThat(order.getSubtotal()).isEqualByComparingTo("0");
            assertThat(order.getTotal()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("fractional prices do not drift across many lines")
        void noDriftAcrossLines() {
            for (int i = 0; i < 50; i++) {
                order.addItem((long) i, "SKU-" + i, "Item " + i, new BigDecimal("19.99"), 1);
            }
            assertThat(order.getSubtotal()).isEqualByComparingTo("999.50");
        }
    }

    @Nested
    @DisplayName("price snapshot")
    class Snapshot {

        @Test
        @DisplayName("the line keeps the price it was created with")
        void lineKeepsItsPrice() {
            OrderItem item = order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("6499.00"), 2);

            assertThat(item.getUnitPrice()).isEqualByComparingTo("6499.00");
            assertThat(item.getProductSku()).isEqualTo("SKU-1");
            assertThat(item.getProductName()).isEqualTo("Keyboard");
            assertThat(item.getLineTotal()).isEqualByComparingTo("12998.00");
        }

        @Test
        @DisplayName("the returned item list is a copy, so a caller cannot add a line behind the total's back")
        void itemListIsACopy() {
            order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("10.00"), 1);
            var items = order.getItems();

            assertThatThrownBy(() -> items.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(order.getItems()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @BeforeEach
        void addALine() {
            order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("6499.00"), 2);
        }

        @Test
        @DisplayName("a new order is CREATED with PENDING payment and holds stock")
        void initialState() {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order.isPaid()).isFalse();
            assertThat(order.holdsReservation()).isTrue();
            assertThat(order.getConfirmedAt()).isNull();
        }

        @Test
        @DisplayName("confirm moves both lifecycles and stamps the time")
        void confirm() {
            order.confirm();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(order.isPaid()).isTrue();
            assertThat(order.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("a failed payment leaves the order CREATED so the customer can retry")
        void failedPaymentIsNotACancellation() {
            order.markPaymentFailed();

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getStatus())
                    .as("the fulfilment lifecycle is independent of the payment one")
                    .isEqualTo(OrderStatus.CREATED);
            assertThat(order.getStatus().isCancellable()).isTrue();
        }

        @Test
        @DisplayName("cancel stamps the time and stops holding stock")
        void cancel() {
            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancelledAt()).isNotNull();
            assertThat(order.holdsReservation()).isFalse();
        }

        @Test
        @DisplayName("cancelling a shipped order is refused with ORDER_NOT_CANCELLABLE")
        void cannotCancelAfterShipping() {
            order.confirm();
            order.advanceTo(OrderStatus.PROCESSING);
            order.advanceTo(OrderStatus.SHIPPED);

            assertThatThrownBy(order::cancel)
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_CANCELLABLE);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("an illegal jump is refused and names the allowed targets")
        void illegalJumpIsRefused() {
            assertThatThrownBy(() -> order.advanceTo(OrderStatus.DELIVERED))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATUS_TRANSITION)
                    .hasMessageContaining("CONFIRMED")
                    .hasMessageContaining("CANCELLED");

            assertThat(order.getStatus())
                    .as("a refused transition must leave the order untouched")
                    .isEqualTo(OrderStatus.CREATED);
        }

        @Test
        @DisplayName("a shipped order no longer holds a reservation — the units have left")
        void shippedReleasesTheHold() {
            order.confirm();
            order.advanceTo(OrderStatus.PROCESSING);
            assertThat(order.holdsReservation()).isTrue();

            order.advanceTo(OrderStatus.SHIPPED);
            assertThat(order.holdsReservation()).isFalse();
        }

        @Test
        @DisplayName("advanceTo(CANCELLED) routes through cancel, so the rule cannot be bypassed")
        void advanceToCancelledUsesTheCancelRule() {
            order.confirm();
            order.advanceTo(OrderStatus.PROCESSING);
            order.advanceTo(OrderStatus.SHIPPED);

            assertThatThrownBy(() -> order.advanceTo(OrderStatus.CANCELLED))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("a refund is recorded without rewinding the fulfilment status")
        void refundIsIndependent() {
            order.confirm();
            order.markRefunded();

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(order.getStatus())
                    .as("CONFIRMED + REFUNDED is a real state; one enum could not express it")
                    .isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("the full happy path")
        void happyPath() {
            order.confirm();
            order.advanceTo(OrderStatus.PROCESSING);
            order.advanceTo(OrderStatus.SHIPPED);
            order.advanceTo(OrderStatus.DELIVERED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.getStatus().isTerminal()).isTrue();
            assertThat(order.isPaid()).isTrue();
        }
    }

    @Test
    @DisplayName("toString does not touch the lazy item collection")
    void toStringIsSafeForLogging() {
        assertThat(order.toString())
                .contains("ORD-20261014-ABC123")
                .contains("CREATED")
                .doesNotContain("items");
    }
}
