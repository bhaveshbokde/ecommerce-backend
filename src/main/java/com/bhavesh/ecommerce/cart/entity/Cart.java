package com.bhavesh.ecommerce.cart.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One customer's shopping cart.
 *
 * <p>Maps to {@code carts} (V5). Exactly one per user, enforced by
 * {@code uq_carts_user} — so "get or create the cart" can never produce a second
 * one, even if two requests race.
 *
 * <h2>Why items are mapped here but products are not</h2>
 * A cart is a genuine aggregate: its lines have no meaning outside it, they are
 * always loaded together, and they are always deleted with it. That is what
 * {@code cascade = ALL} with {@code orphanRemoval} is for, and removing a line
 * from the list is enough to delete the row.
 *
 * <p>The association is bounded in practice — nobody has ten thousand cart lines —
 * which is what makes the collection safe. {@code User → orders} is the opposite
 * case and is deliberately not mapped.
 *
 * <h2>No stored prices</h2>
 * {@code cart_items} has no price column. A cart is an intention, not a contract:
 * the customer pays what the catalogue says at checkout, and a cart left open for
 * a week must not quietly hold a stale price. Totals are computed at read time
 * from the current catalogue. The price snapshot happens exactly once, when the
 * order is created.
 */
@Entity
@Table(name = "carts")
public class Cart extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /**
     * {@code LAZY} so loading a cart to check its owner does not drag in every
     * line. {@code @OrderBy} gives a stable presentation order — without it the
     * database may return rows in any order, and a cart that reshuffles itself
     * between refreshes looks broken.
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Long userId) {
        this.userId = userId;
    }

    /**
     * Adds {@code quantity} of a product, merging with an existing line.
     *
     * <p>Merging rather than appending is what keeps the data consistent with
     * {@code uq_cart_items_cart_product}: two lines for the same product would
     * violate it, so "add the same thing twice" has to mean "increase the
     * quantity".
     */
    public CartItem addItem(Long productId, int quantity) {
        Optional<CartItem> existing = findItem(productId);
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.increaseQuantity(quantity);
            return item;
        }
        CartItem item = new CartItem(this, productId, quantity);
        items.add(item);
        return item;
    }

    /** Sets an absolute quantity; a quantity of zero removes the line. */
    public void updateQuantity(Long productId, int quantity) {
        if (quantity <= 0) {
            removeItem(productId);
            return;
        }
        findItem(productId).ifPresent(item -> item.setQuantity(quantity));
    }

    /**
     * Removes a line.
     *
     * <p>{@code removeIf} on the mapped collection is what triggers
     * {@code orphanRemoval}; deleting through the repository instead would leave a
     * stale object in this list for the rest of the transaction.
     */
    public boolean removeItem(Long productId) {
        return items.removeIf(item -> Objects.equals(item.getProductId(), productId));
    }

    public void clear() {
        items.clear();
    }

    public Optional<CartItem> findItem(Long productId) {
        return items.stream()
                .filter(item -> Objects.equals(item.getProductId(), productId))
                .findFirst();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    /** Unmodifiable: mutation goes through the methods above, which keep invariants. */
    public List<CartItem> getItems() {
        return List.copyOf(items);
    }

    /** Package-private live view, for the entity's own bookkeeping. */
    List<CartItem> itemsInternal() {
        return items;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Cart cart)) {
            return false;
        }
        return id != null && id.equals(cart.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Cart.class);
    }

    /** Does not touch {@code items}, which would force a lazy load during logging. */
    @Override
    public String toString() {
        return "Cart{id=%d, userId=%d}".formatted(id, userId);
    }
}
