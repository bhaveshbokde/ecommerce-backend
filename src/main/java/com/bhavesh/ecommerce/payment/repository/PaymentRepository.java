package com.bhavesh.ecommerce.payment.repository;

import com.bhavesh.ecommerce.payment.entity.Payment;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Data access for {@link Payment}. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * The idempotency lookup, hitting {@code uq_payments_user_idempotency}.
     *
     * <p>Scoped by user as well as key: the index is {@code (user_id,
     * idempotency_key)}, and querying by key alone would both miss the index and
     * let one customer's key collide with another's.
     */
    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByPaymentReference(String paymentReference);

    /**
     * The live payment for an order, if any.
     *
     * <p>Mirrors the partial index {@code uq_payments_order_active}, which
     * guarantees this returns at most one row. Written as a list anyway so a
     * future schema change cannot turn a correct query into a runtime
     * {@code IncorrectResultSizeDataAccessException} — the service takes the first
     * and the index keeps that honest.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.orderId = :orderId AND p.status IN ('PENDING', 'SUCCEEDED')
            """)
    List<Payment> findActiveByOrderId(@Param("orderId") Long orderId);

    List<Payment> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** Ownership-scoped: a customer may only read their own payments. */
    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    Page<Payment> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
