package com.bhavesh.ecommerce.catalog.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * A product category.
 *
 * <p>Maps to {@code categories} (V3). Deliberately flat — no parent/child
 * self-reference. A tree would bring recursive queries, cycle prevention and
 * breadcrumb assembly, none of which this project is trying to demonstrate, and
 * all of which would obscure the parts that matter.
 *
 * <p>{@code active} is a soft-disable: an inactive category disappears from the
 * public catalogue while its products and their order history stay intact.
 * Hard-deleting it is blocked by {@code ON DELETE RESTRICT} on
 * {@code products.category_id} — deleting a category must never orphan a product.
 */
@Entity
@Table(name = "categories")
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** URL-safe identifier, matching {@code ck_categories_slug}. Unique. */
    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Category() {
    }

    public Category(String name, String description) {
        this.name = name == null ? null : name.trim();
        this.slug = slugify(this.name);
        this.description = description;
        this.active = true;
    }

    /**
     * Derives {@code "Home & Kitchen"} into {@code "home-kitchen"}.
     *
     * <p>Generated rather than client-supplied so a caller cannot invent a slug
     * that violates {@code ck_categories_slug} — and so two categories named the
     * same way collide on {@code uq_categories_slug} as well as on the name index.
     *
     * <p>{@code Normalizer.Form.NFD} plus the combining-marks strip turns accented
     * letters into their ASCII bases, so {@code "Café"} becomes {@code "cafe"}
     * instead of losing the character entirely.
     */
    public static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? null : slug;
    }

    public void rename(String newName) {
        this.name = newName == null ? null : newName.trim();
        this.slug = slugify(this.name);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
        if (!(other instanceof Category category)) {
            return false;
        }
        return id != null && id.equals(category.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Category.class);
    }

    @Override
    public String toString() {
        return "Category{id=%d, name='%s', active=%s}".formatted(id, name, active);
    }
}
