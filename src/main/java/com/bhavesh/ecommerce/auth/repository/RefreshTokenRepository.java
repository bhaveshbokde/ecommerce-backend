package com.bhavesh.ecommerce.auth.repository;

import com.bhavesh.ecommerce.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** Data access for issued refresh tokens. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** The lookup performed on every refresh request; hits {@code uq_refresh_tokens_hash}. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token for one user.
     *
     * <p>Used by "log out everywhere" and — more importantly — by any future
     * password change, where leaving old refresh tokens alive would defeat the
     * point of changing the password.
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically} keep the
     * persistence context honest: a bulk JPQL UPDATE bypasses the first-level
     * cache, so without these an entity loaded earlier in the same transaction
     * would still appear un-revoked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now
             WHERE t.userId = :userId AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Housekeeping: remove tokens that expired long ago. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);

    long countByUserIdAndRevokedAtIsNull(Long userId);
}
