package com.bhavesh.ecommerce.cart.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * One line in a cart: a product and a quantity.
 *
 * <p>Maps to {@code cart_items} (V5).
 *
 * <p>Holds {@code productId} as a plain column rather than a {@code @ManyToOne} to
 * {@code Product}. A cart line needs the identity of the product, not the product
 * itself; mapping the association would make every cart read either eager-load the
 * catalogue rows or risk a lazy-initialisation failure once the line is handed to
 * a mapper outside the transaction. The service loads the products it needs in one
 * batched query and joins them to the lines in memory.
 *
 * <p>{@code MAX_QUANTITY} mirrors {@code ck_cart_items_quantity}; both exist so the
 * rule holds whether the write comes through this class or through psql.
 */
@Entity
@Table(name = "cart_items")
public class CartItem extends AuditableEntity {

    /** Matches {@code CHECK (quantity > 0 AND quantity <= 999)}. */
    public static final int MAX_QUANTITY = 999;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected CartItem() {
    }

    CartItem(Cart cart, Long productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        setQuantity(quantity);
    }

    /** Caps at {@link #MAX_QUANTITY} rather than throwing — "add 5 more" to a nearly-full line is not an error. */
    void increaseQuantity(int delta) {
        setQuantity(Math.min(this.quantity + delta, MAX_QUANTITY));
    }

    void setQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity must not exceed " + MAX_QUANTITY);
        }
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CartItem cartItem)) {
            return false;
        }
        return id != null && id.equals(cartItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(CartItem.class);
    }

    @Override
    public String toString() {
        return "CartItem{id=%d, productId=%d, quantity=%d}".formatted(id, productId, quantity);
    }
}
