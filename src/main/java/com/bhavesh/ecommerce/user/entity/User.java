package com.bhavesh.ecommerce.user.entity;

import com.bhavesh.ecommerce.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Locale;
import java.util.Objects;

/**
 * A registered account — customer or administrator.
 *
 * <p>Maps to {@code users}. The schema is owned by Flyway (V1); this class only
 * describes it, and {@code ddl-auto=validate} fails startup if the two drift.
 *
 * <h2>Deliberate omissions</h2>
 * <ul>
 *   <li>No {@code @OneToMany} to orders. A customer may accumulate thousands;
 *       a mapped collection invites someone to call {@code user.getOrders()} and
 *       load all of them. Navigation goes the other way, through repositories
 *       that always take a {@code userId} and a {@code Pageable}.</li>
 *   <li>No setter accepting a raw password. The only way in is
 *       {@link #setPasswordHash(String)}, called with an already-encoded value.
 *       This class never sees plain text and never encodes.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. An ordinal mapping stores
     * the enum's position, so inserting a constant in the middle of the enum
     * silently re-labels every existing row — here, that would turn customers
     * into administrators.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.ROLE_CUSTOMER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** JPA requires a no-arg constructor; it is not meant for application code. */
    protected User() {
    }

    private User(String email, String passwordHash, String fullName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = true;
    }

    /**
     * Factory for a self-registered customer.
     *
     * <p>Hard-codes {@link Role#ROLE_CUSTOMER}. There is no parameter through
     * which a registration request could request a different role, which is what
     * makes privilege escalation via the public endpoint structurally impossible
     * rather than merely validated against.
     *
     * @param passwordHash an <em>already encoded</em> password
     */
    public static User registerCustomer(String email, String passwordHash, String fullName) {
        return new User(email, passwordHash, fullName, Role.ROLE_CUSTOMER);
    }

    /**
     * Factory for an administrator.
     *
     * <p>Reachable only from {@link com.bhavesh.ecommerce.config.AdminBootstrap},
     * never from a controller.
     */
    public static User createAdmin(String email, String passwordHash, String fullName) {
        return new User(email, passwordHash, fullName, Role.ROLE_ADMIN);
    }

    /** Canonical form used for both storage and lookup, matching {@code uq_users_email_lower}. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Identity is the persistent id, and only once assigned.
     *
     * <p>Two transient instances are never equal, which keeps a {@code HashSet}
     * of unsaved entities from collapsing them into one.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.id);
    }

    /**
     * A constant hash code is the standard recipe for a JPA entity with a
     * generated id: it stays stable across the transition from transient to
     * persistent, which {@code Objects.hash(id)} would not — an entity already in
     * a {@code HashSet} would become unfindable the moment it was saved.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(User.class);
    }

    /** Never includes {@code passwordHash}. */
    @Override
    public String toString() {
        return "User{id=%d, email='%s', role=%s, enabled=%s}".formatted(id, email, role, enabled);
    }
}
