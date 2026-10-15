package com.bhavesh.ecommerce.order.entity;

import com.bhavesh.ecommerce.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of an order — an immutable record of what was bought and for how much.
 *
 * <p>Maps to {@code order_items} (V6). Does not extend {@code AuditableEntity}:
 * the table has {@code created_at} only. An order line is written once and never
 * updated, so {@code updated_at} and {@code version} would be columns that can
 * never change — and declaring them would fail {@code ddl-auto=validate}, since
 * V6 does not create them.
 *
 * <h2>Why the product details are copied</h2>
 * {@code product_sku}, {@code product_name} and {@code unit_price} are duplicated
 * from the catalogue, which looks like a normalisation error and is not. An order
 * is a financial record of an event that already happened. If the line only held
 * {@code product_id}, then:
 *
 * <ul>
 *   <li>repricing an item would silently rewrite every past invoice;</li>
 *   <li>renaming it would make old receipts describe goods nobody ordered;</li>
 *   <li>deleting it would leave an order that cannot say what it was for.</li>
 * </ul>
 *
 * <p>{@code product_id} is kept as a nullable {@code ON DELETE SET NULL} reference
 * purely for analytics — "what else did buyers of this item purchase". The order
 * stays fully readable once it is gone.
 *
 * <p>{@code line_total} is stored rather than recomputed so the arithmetic that was
 * actually applied is preserved. If the rounding policy ever changes, historical
 * totals must not change with it.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    /** Nullable: the catalogue row may be deleted, the receipt must survive. */
    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(name = "product_sku", nullable = false, length = 64, updatable = false)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineTotal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderItem() {
    }

    OrderItem(Order order, Long productId, String productSku, String productName,
              BigDecimal unitPrice, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productSku = productSku;
        this.productName = productName;
        this.unitPrice = Money.normalize(unitPrice);
        this.quantity = quantity;
        this.lineTotal = Money.lineTotal(this.unitPrice, quantity);
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductSku() {
        return productSku;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OrderItem orderItem)) {
            return false;
        }
        return id != null && id.equals(orderItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(OrderItem.class);
    }

    @Override
    public String toString() {
        return "OrderItem{sku='%s', quantity=%d, unitPrice=%s, lineTotal=%s}"
                .formatted(productSku, quantity, unitPrice, lineTotal);
    }
}
