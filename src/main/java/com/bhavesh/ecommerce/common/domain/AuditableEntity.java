package com.bhavesh.ecommerce.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Shared audit columns and the optimistic-locking version.
 *
 * <p>{@code @MappedSuperclass} contributes these columns to every subclass table
 * without creating a table of its own and without introducing an inheritance
 * hierarchy in the database.
 *
 * <p><strong>Every table whose entity extends this must have all three columns</strong>
 * — {@code created_at}, {@code updated_at} and {@code version}. Hibernate's
 * {@code ddl-auto=validate} fails startup otherwise, which is exactly the kind of
 * drift it exists to catch.
 *
 * <p>{@code @Version} gives optimistic locking: Hibernate appends
 * {@code AND version = ?} to each UPDATE and raises an exception when zero rows
 * match, which {@code GlobalExceptionHandler} converts to HTTP 409. This is the
 * right default for ordinary edits. Inventory reservation deliberately does
 * <em>not</em> rely on it — see
 * {@link com.bhavesh.ecommerce.inventory.repository.InventoryRepository} for why.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
