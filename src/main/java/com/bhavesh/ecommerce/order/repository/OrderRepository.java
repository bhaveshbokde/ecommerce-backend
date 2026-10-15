package com.bhavesh.ecommerce.order.repository;

import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data access for {@link Order}.
 *
 * <p>Every customer-facing finder takes a {@code userId} and puts it in the WHERE
 * clause. That is what makes data isolation structural: a service cannot
 * accidentally hand back someone else's order, because there is no method that
 * would return one. Loading by id and then comparing owners in Java is the same
 * check written somewhere it can be forgotten.
 *
 * <p>A miss therefore means "not found or not yours", and the service answers 404
 * either way — see {@link com.bhavesh.ecommerce.exception.ResourceNotFoundException}.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Ownership-scoped lookup. The customer-facing detail endpoint uses this one. */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id AND o.userId = :userId")
    Optional<Order> findByIdAndUserIdWithItems(@Param("id") Long id, @Param("userId") Long userId);

    /** Unscoped lookup, for administrators and for the payment flow's own bookkeeping. */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * The customer's order history.
     *
     * <p>No fetch join on {@code items}: this is paginated, and fetching a
     * collection alongside {@code LIMIT} makes Hibernate load every matching row
     * and paginate in memory. The list view shows totals and status, so the lines
     * are not needed; the detail endpoint fetches them.
     */
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, OrderStatus status,
                                                          Pageable pageable);

    /** Administrative listing, optionally filtered by status. */
    @Query(value = "SELECT o FROM Order o WHERE (:status IS NULL OR o.status = :status)",
            countQuery = "SELECT COUNT(o) FROM Order o WHERE (:status IS NULL OR o.status = :status)")
    Page<Order> findAllByOptionalStatus(@Param("status") OrderStatus status, Pageable pageable);

    long countByUserId(Long userId);
}
