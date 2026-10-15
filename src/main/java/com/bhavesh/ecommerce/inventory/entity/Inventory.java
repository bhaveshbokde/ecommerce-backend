package com.bhavesh.ecommerce.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * Stock for one product. One row per product, keyed by {@code product_id}.
 *
 * <p>Maps to {@code inventory} (V4). Does <strong>not</strong> extend
 * {@code AuditableEntity}: the table has {@code updated_at} and {@code version}
 * but no {@code created_at}, because a stock row is created with its product and
 * its birth date carries no information. Inheriting the superclass would declare a
 * column that does not exist and fail {@code ddl-auto=validate} at startup.
 *
 * <h2>Reads here, writes through the repository</h2>
 * This entity exposes no {@code reserve()} method, and that is deliberate.
 * Reserving through a loaded entity is a read-modify-write:
 *
 * <pre>{@code
 *   Inventory inv = repo.findById(productId).orElseThrow();   // T1 and T2 both read available = 1
 *   if (inv.getAvailable() >= qty) { inv.setAvailable(...); } // both pass the check
 * }</pre>
 *
 * Both transactions see one unit and both sell it. {@code @Version} would turn
 * that into a lost-update exception rather than an oversell, but it still means a
 * failed request under ordinary contention.
 *
 * <p>The real reservation is
 * {@link com.bhavesh.ecommerce.inventory.repository.InventoryRepository#reserve}:
 * a single conditional UPDATE whose WHERE clause re-checks the quantity. The
 * database evaluates the predicate and applies the change atomically under one row
 * lock, so the second transaction sees the first one's write and the UPDATE
 * affects zero rows. {@code CHECK (available >= 0)} is the backstop beneath that.
 *
 * <p>{@code @Version} is still mapped — it guards ordinary administrative edits
 * such as a restock, where last-write-wins would silently discard a concurrent
 * correction.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    /**
     * The primary key <em>is</em> {@code product_id} — a shared primary key, not a
     * surrogate. A product can only ever have one stock row, and this makes that a
     * property of the schema rather than a rule someone has to remember.
     */
    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Units sellable right now. {@code CHECK (available >= 0)}. */
    @Column(name = "available", nullable = false)
    private int available;

    /** Units held for orders awaiting payment; released on failure or cancellation. */
    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Inventory() {
    }

    public Inventory(Long productId, int available) {
        this.productId = productId;
        this.available = available;
        this.reserved = 0;
        this.updatedAt = Instant.now();
    }

    /** Total units physically accounted for, whether sellable or held. */
    public int getTotal() {
        return available + reserved;
    }

    public boolean isInStock() {
        return available > 0;
    }

    public Long getProductId() {
        return productId;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    /**
     * Absolute stock correction, for administrative use only.
     *
     * <p>Safe where reservation is not, because this is not a decrement derived
     * from a prior read: an administrator is asserting a counted quantity, and
     * {@code @Version} rejects the write if someone else changed the row since it
     * was loaded.
     */
    public void setAvailable(int available) {
        if (available < 0) {
            throw new IllegalArgumentException("available must not be negative, was " + available);
        }
        this.available = available;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Inventory inventory)) {
            return false;
        }
        return productId != null && productId.equals(inventory.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Inventory.class);
    }

    @Override
    public String toString() {
        return "Inventory{productId=%d, available=%d, reserved=%d}"
                .formatted(productId, available, reserved);
    }
}
