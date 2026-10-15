package com.bhavesh.ecommerce.order.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import com.bhavesh.ecommerce.common.util.Money;
import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A placed order.
 *
 * <p>Maps to {@code orders} (V6). This is the application's one true aggregate
 * root: its lines are meaningless without it, are always loaded with it, and are
 * deleted with it.
 *
 * <h2>Two independent lifecycles</h2>
 * {@link #status} tracks fulfilment ({@code CREATED → … → DELIVERED}) and
 * {@link #paymentStatus} tracks money ({@code PENDING → SUCCEEDED | FAILED |
 * REFUNDED}). They move independently and the database has a separate CHECK for
 * each. Collapsing them into one column would need a value for every combination
 * and would make "refund a delivered order" inexpressible.
 *
 * <h2>Totals are stored, not derived</h2>
 * {@code subtotal} and {@code total} are columns rather than a {@code SUM} over
 * the lines. A financial record states what was charged; recomputing it means a
 * future change to rounding, discounting or tax silently restates past orders.
 * They are equal here because this project models no shipping or tax — kept as two
 * fields because that is where those would land, and a schema change later is more
 * expensive than an unused column now.
 *
 * <h2>Transitions are methods, not setters</h2>
 * There is no {@code setStatus}. Every change goes through a method that consults
 * {@link OrderStatus#canTransitionTo}, so an illegal jump such as
 * {@code CREATED → DELIVERED} is impossible to express rather than merely
 * discouraged.
 */
@Entity
@Table(name = "orders")
public class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Human-facing identifier, e.g. {@code ORD-20261014-7F3A91}.
     *
     * <p>Exists so support conversations and emails never quote the primary key.
     * A sequential surrogate id tells an outsider how many orders the business has
     * taken and lets them guess a neighbour's; this does neither.
     */
    @Column(name = "order_number", nullable = false, length = 32, updatable = false)
    private String orderNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 19, scale = 4)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(String orderNumber, Long userId, String currency) {
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.paymentStatus = PaymentStatus.PENDING;
    }

    /**
     * Appends a line, snapshotting the product's details as they are right now.
     *
     * <p>Recomputes the totals immediately so {@link #subtotal} is never
     * momentarily inconsistent with the lines — there is no "remember to call
     * recalculate()" step for a caller to forget.
     */
    public OrderItem addItem(Long productId, String sku, String name, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem(this, productId, sku, name, unitPrice, quantity);
        items.add(item);
        recalculateTotals();
        return item;
    }

    private void recalculateTotals() {
        BigDecimal sum = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.subtotal = Money.normalize(sum);
        // No shipping or tax in this project, so the two are equal by definition.
        // They remain separate fields because that is where those charges belong.
        this.total = this.subtotal;
    }

    // ------------------------------------------------------------ transitions --

    /** Payment succeeded: the order becomes a commitment. */
    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
        this.paymentStatus = PaymentStatus.SUCCEEDED;
        this.confirmedAt = Instant.now();
    }

    /**
     * Records a failed charge without cancelling.
     *
     * <p>The order stays CREATED so the customer can retry with another
     * instrument. Reservation release is the caller's responsibility — this entity
     * does not reach into another aggregate.
     */
    public void markPaymentFailed() {
        this.paymentStatus = PaymentStatus.FAILED;
    }

    public void cancel() {
        if (!status.isCancellable()) {
            throw new BusinessRuleException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "An order that is %s can no longer be cancelled".formatted(status));
        }
        transitionTo(OrderStatus.CANCELLED);
        this.cancelledAt = Instant.now();
    }

    public void markRefunded() {
        this.paymentStatus = PaymentStatus.REFUNDED;
    }

    /** Administrative fulfilment move, validated against the transition table. */
    public void advanceTo(OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            cancel();
            return;
        }
        transitionTo(target);
        if (target == OrderStatus.CONFIRMED && confirmedAt == null) {
            this.confirmedAt = Instant.now();
        }
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot move an order from %s to %s; allowed: %s"
                            .formatted(status, target, status.allowedTransitions()));
        }
        this.status = target;
    }

    public boolean isPaid() {
        return paymentStatus == PaymentStatus.SUCCEEDED;
    }

    /** True while stock is still held for this order and must be released on cancellation. */
    public boolean holdsReservation() {
        return status != OrderStatus.CANCELLED && status != OrderStatus.SHIPPED
                && status != OrderStatus.DELIVERED;
    }

    // ---------------------------------------------------------------- getters --

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Order order)) {
            return false;
        }
        return id != null && id.equals(order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Order.class);
    }

    @Override
    public String toString() {
        return "Order{id=%d, number='%s', status=%s, payment=%s, total=%s}"
                .formatted(id, orderNumber, status, paymentStatus, total);
    }
}
