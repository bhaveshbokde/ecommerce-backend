package com.bhavesh.ecommerce.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A refresh token that has been issued and can be revoked.
 *
 * <p>Access tokens in this application are stateless JWTs — fast to verify, and
 * impossible to revoke before they expire. Refresh tokens are long-lived, so an
 * un-revokable one would be a serious liability. Persisting them is what makes
 * logout and revocation real rather than advisory.
 *
 * <p><strong>Only a SHA-256 hash of the token is stored.</strong> A stolen
 * database dump therefore yields nothing usable. A fast digest is the right
 * choice here, unlike for passwords: a refresh token is high-entropy random data,
 * so there is no dictionary to attack and BCrypt's deliberate slowness would only
 * add latency to every refresh.
 *
 * <p>This entity does not extend {@code AuditableEntity} — it has no
 * {@code updated_at} or {@code version} column, because a token is written once
 * and then only revoked. Adding them would mean three unused columns and a
 * {@code ddl-auto=validate} failure.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /** Marks the token unusable. Idempotent — revoking twice is not an error. */
    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** A token is usable only while it is neither revoked nor expired. */
    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Never prints the hash, which is still a credential-adjacent value. */
    @Override
    public String toString() {
        return "RefreshToken{id=%d, userId=%d, expiresAt=%s, revoked=%s}"
                .formatted(id, userId, expiresAt, isRevoked());
    }
}
