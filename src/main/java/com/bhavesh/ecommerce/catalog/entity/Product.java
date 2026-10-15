package com.bhavesh.ecommerce.catalog.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
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
import java.util.Locale;
import java.util.Objects;

/**
 * A catalogue item.
 *
 * <p>Maps to {@code products} (V3).
 *
 * <h2>Price</h2>
 * {@code price} is the <em>current list price</em> — what a new order would pay.
 * It is explicitly not what any existing order paid: {@code order_items} snapshots
 * {@code unit_price} at purchase time, so changing this field never rewrites
 * history. That separation is the single most important modelling decision in the
 * catalogue.
 *
 * <p>Stored as {@code BigDecimal} over {@code NUMERIC(19,4)}. {@code double} would
 * be wrong: {@code 0.1 + 0.2} is not {@code 0.3} in binary floating point, and
 * that error compounds across a basket.
 *
 * <h2>Fetching</h2>
 * The category association is {@code LAZY}. {@code @ManyToOne} defaults to
 * {@code EAGER}, which would fire one extra SELECT per product — the N+1 problem —
 * on any listing. Queries that need the category say so with a
 * {@code JOIN FETCH}.
 *
 * <h2>No inventory association</h2>
 * Stock lives in its own table and is reached through {@code InventoryRepository},
 * never through {@code product.getInventory()}. Reservation is a conditional
 * atomic UPDATE; routing it through a loaded entity would invite a
 * read-modify-write that oversells under concurrency.
 */
@Entity
@Table(name = "products")
public class Product extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable business identifier. Uppercase, matching {@code ck_products_sku}. */
    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Product() {
    }

    public Product(String sku, String name, String description, BigDecimal price, Category category) {
        this.sku = normalizeSku(sku);
        this.name = name == null ? null : name.trim();
        this.description = description;
        this.price = Money.normalize(price);
        this.category = category;
        this.active = true;
    }

    /** Uppercases and trims so {@code "elec-001"} and {@code "ELEC-001"} collide on the unique index. */
    public static String normalizeSku(String sku) {
        return sku == null ? null : sku.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Whether this product may be added to a cart or ordered.
     *
     * <p>An inactive category hides its products too — otherwise "retire this
     * category" would silently leave its items purchasable through a direct link.
     */
    public boolean isPurchasable() {
        return active && category != null && category.isActive();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    /** Affects future orders only; existing {@code order_items} keep their snapshot. */
    public void setPrice(BigDecimal price) {
        this.price = Money.normalize(price);
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Product product)) {
            return false;
        }
        return id != null && id.equals(product.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Product.class);
    }

    /** Avoids touching {@code category}, which would trigger a lazy load during logging. */
    @Override
    public String toString() {
        return "Product{id=%d, sku='%s', price=%s, active=%s}".formatted(id, sku, price, active);
    }
}
