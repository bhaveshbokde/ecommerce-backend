package com.bhavesh.ecommerce.cart.repository;

import com.bhavesh.ecommerce.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Data access for {@link Cart}. */
public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    /**
     * Loads the cart with its lines in one statement.
     *
     * <p>{@code LEFT JOIN FETCH} rather than {@code JOIN FETCH}: an inner join
     * would return nothing for an empty cart, so a customer who had just removed
     * their last item would be told they have no cart at all.
     *
     * <p>No {@code Pageable} here, and that is the rule this project follows for
     * collection fetch joins — Hibernate cannot push {@code LIMIT} into a
     * row-multiplying join and would paginate in memory instead. A cart is
     * bounded, so loading it whole is the right call.
     */
    @Query("SELECT c FROM Cart c LEFT JOIN FETCH c.items WHERE c.userId = :userId")
    Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);
}
