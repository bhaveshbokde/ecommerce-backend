package com.bhavesh.ecommerce.payment.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import com.bhavesh.ecommerce.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One payment attempt against the simulated provider.
 *
 * <p>Maps to {@code payments} (V7).
 *
 * <h2>Idempotency lives in the schema</h2>
 * {@code uq_payments_user_idempotency} on {@code (user_id, idempotency_key)} is
 * what makes a retried charge safe. An application-level "have I seen this key?"
 * check is a read followed by a write, and two concurrent requests both pass the
 * read. The unique index is evaluated by the database at insert time, so exactly
 * one of them wins and the other gets a constraint violation the service turns
 * into a replay of the original outcome.
 *
 * <p>Scoped <em>per user</em>, not globally: two customers must be able to pick the
 * same key without colliding, and a global key space would let one customer's
 * choice of {@code "1"} block everyone else's.
 *
 * <h2>One live payment per order</h2>
 * {@code uq_payments_order_active} is a partial index over {@code WHERE status IN
 * ('PENDING','SUCCEEDED')}. So an order can accumulate any number of FAILED
 * attempts — retrying a declined card is normal — but never two live ones, and
 * never a second charge after one has succeeded. A plain unique index on
 * {@code order_id} would forbid the retry; no index at all would permit the double
 * charge.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer-facing reference, e.g. {@code PAY-20261014-3C81D0}. Unique. */
    @Column(name = "payment_reference", nullable = false, length = 64, updatable = false)
    private String paymentReference;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    /** What the provider called this charge. Null until it answers. */
    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Payment() {
    }

    public Payment(String paymentReference, Long orderId, Long userId,
                   BigDecimal amount, String currency, String idempotencyKey) {
        this.paymentReference = paymentReference;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = Money.normalize(amount);
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
        this.attempts = 0;
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public void markSucceeded(String providerReference) {
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = providerReference;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    /**
     * @param reason truncated to the column width rather than allowed to overflow —
     *               a provider message must never be the thing that fails the write
     *               recording why the provider failed
     */
    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason, 255);
        this.completedAt = Instant.now();
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
        this.completedAt = Instant.now();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.SUCCEEDED;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Payment payment)) {
            return false;
        }
        return id != null && id.equals(payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Payment.class);
    }

    /** Omits {@code idempotencyKey}, which is client-chosen and may be guessable. */
    @Override
    public String toString() {
        return "Payment{id=%d, reference='%s', orderId=%d, status=%s, amount=%s}"
                .formatted(id, paymentReference, orderId, status, amount);
    }
}
