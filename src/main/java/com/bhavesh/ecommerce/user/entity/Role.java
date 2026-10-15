package com.bhavesh.ecommerce.user.entity;

/**
 * Application roles.
 *
 * <p>The {@code ROLE_} prefix is kept in the enum name because Spring Security's
 * {@code hasRole("ADMIN")} expression prepends {@code ROLE_} before comparing
 * against the granted authority. Storing the prefixed form keeps the database
 * value, the {@code GrantedAuthority} and the SpEL expression identical, with no
 * translation step that could silently drift.
 */
public enum Role {

    /** An ordinary shopper: full control over their own cart and orders, nothing else. */
    ROLE_CUSTOMER,

    /**
     * Catalogue and inventory administrator.
     *
     * <p>An administrator may manage products, categories and stock, and may
     * advance order status through the fulfilment states. They may <em>not</em>
     * read another customer's cart — that would be a privacy regression with no
     * operational justification.
     */
    ROLE_ADMIN;

    /** The authority string Spring Security compares against. */
    public String authority() {
        return name();
    }

    /** The bare name used by {@code hasRole(...)} expressions. */
    public String shortName() {
        return name().substring("ROLE_".length());
    }
}
