package com.bhavesh.ecommerce.idempotency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A remembered response, so a replayed request returns the original answer rather
 * than performing the work twice.
 *
 * <p>Maps to {@code idempotency_keys} (V8). Does not extend
 * {@code AuditableEntity}: the row is written once and never modified, so the
 * table carries {@code created_at} alone.
 *
 * <h2>Why a request hash as well as a key</h2>
 * The key alone cannot tell a genuine retry from a client bug. If someone sends
 * {@code Idempotency-Key: abc} with a 500-rupee charge and then reuses {@code abc}
 * for a 5,000-rupee charge, replaying the first response would silently swallow
 * the second order. Comparing a SHA-256 of the canonical request body turns that
 * into an explicit 422 ({@link
 * com.bhavesh.ecommerce.exception.ErrorCode#IDEMPOTENCY_KEY_REUSED}).
 *
 * <p>SHA-256 rather than a slow hash: the input is a request body, not a password,
 * so there is nothing to brute-force and the only requirement is a low collision
 * probability.
 *
 * <h2>Why the response is stored</h2>
 * A retry must be indistinguishable from the original call, including the status
 * code and the body. Returning a bare 409 would be correct about "I have seen
 * this" and useless to a client that lost the first response to a timeout — which
 * is the exact situation idempotency exists to serve.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    /** Scopes the key: the same key on two different endpoints is two different operations. */
    @Column(name = "endpoint", nullable = false, length = 100, updatable = false)
    private String endpoint;

    /** Lowercase hex SHA-256, matching {@code ck_idempotency_request_hash}. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String idempotencyKey, String endpoint, String requestHash) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    /** Lowercase hex SHA-256 of a UTF-8 payload. */
    public static String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    public void recordResponse(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /** False while the original request is still in flight — there is nothing to replay yet. */
    public boolean hasResponse() {
        return responseStatus != null;
    }

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyRecord record)) {
            return false;
        }
        return id != null && id.equals(record.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(IdempotencyRecord.class);
    }

    @Override
    public String toString() {
        return "IdempotencyRecord{id=%d, userId=%d, endpoint='%s', status=%s}"
                .formatted(id, userId, endpoint, responseStatus);
    }
}
