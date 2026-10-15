package com.bhavesh.ecommerce.idempotency.service;

import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.idempotency.entity.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a non-idempotent POST safe to retry.
 *
 * <h2>The problem</h2>
 * A client sends {@code POST /orders}, the server creates the order, and the
 * response is lost to a dropped connection. The client has no way to tell "it never
 * arrived" from "it worked and I missed the answer", so it retries — and gets a
 * second order. Retrying is the correct behaviour for the client; producing a
 * duplicate is a defect in the server.
 *
 * <h2>The mechanism</h2>
 * The client sends an {@code Idempotency-Key} header. The first request claims it;
 * the work runs; the response is stored. A retry with the same key finds the stored
 * response and replays it verbatim — same status, same body — so the retry is
 * indistinguishable from the original call. That is the whole point: returning a
 * bare 409 would be correct about "I have seen this" and useless to the client that
 * lost the first response.
 *
 * <h2>Three outcomes, not two</h2>
 * <ul>
 *   <li><strong>Replay</strong> — same key, same request body, stored response exists.</li>
 *   <li><strong>Key reused with a different body</strong> — 422. The key says "this is
 *       the same operation" and the body says otherwise; one of the two is a client
 *       bug, and silently replaying the first response would swallow a genuine second
 *       order. This is why a request hash is stored alongside the key.</li>
 *   <li><strong>Still in flight</strong> — the key is claimed but has no response yet,
 *       so the original request is still running. 409, with a retry-after
 *       suggestion. Waiting on it would tie up a thread; proceeding would duplicate
 *       the work the key exists to prevent.</li>
 * </ul>
 *
 * <h2>Why the database and not Redis</h2>
 * The claim has to be atomic with respect to other application instances, and it
 * has to survive a restart. A unique index gives both for free. Redis could do it
 * with {@code SET key value NX}, but then the idempotency guarantee depends on a
 * cache whose eviction policy is tuned for something else entirely.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code action} at most once per key.
     *
     * @param userId      scopes the key, so two customers may use the same string
     * @param endpoint    scopes it further: the same key on two endpoints is two operations
     * @param key         the client's {@code Idempotency-Key}; null disables the whole
     *                    mechanism and runs the action directly
     * @param requestBody canonical representation of the request, hashed to detect reuse
     * @param responseType the type to deserialise a replayed response into
     * @param action      the work to perform, exactly once
     * @return the result, and whether it came from a replay
     */
    public <T> Result<T> execute(Long userId, String endpoint, String key, String requestBody,
                                 Class<T> responseType, Supplier<T> action) {

        if (key == null || key.isBlank()) {
            // The header is optional. A client that does not send one accepts that
            // a retry may duplicate the work — their choice, made explicit.
            return new Result<>(action.get(), false);
        }

        String requestHash = IdempotencyRecord.hash(requestBody);

        Optional<IdempotencyRecord> existing = store.find(userId, endpoint, key);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), requestHash, responseType);
        }

        // Claim the key in its own transaction so a concurrent duplicate sees it
        // immediately. Losing the race means someone else claimed it between our
        // read and our insert — re-read and take the same path as above.
        Optional<IdempotencyRecord> claimed = store.claim(userId, endpoint, key, requestHash);
        if (claimed.isEmpty()) {
            IdempotencyRecord winner = store.find(userId, endpoint, key)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                            "Idempotency key is being processed; retry shortly"));
            return replayOrReject(winner, requestHash, responseType);
        }

        IdempotencyRecord record = claimed.get();
        T result;
        try {
            result = action.get();
        } catch (RuntimeException failure) {
            // Release the claim so a genuine retry is possible. Without this the key
            // would stay claimed with no response and every retry would be told the
            // original is still in flight — forever.
            store.releaseClaim(record.getId());
            throw failure;
        }

        store.recordResponse(record.getId(), 201, serialize(result));
        return new Result<>(result, false);
    }

    private <T> Result<T> replayOrReject(IdempotencyRecord record, String requestHash,
                                         Class<T> responseType) {
        if (!record.matches(requestHash)) {
            log.warn("Idempotency key reused with a different body: endpoint={} user={}",
                    record.getEndpoint(), record.getUserId());
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body");
        }

        if (!record.hasResponse()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The original request with this Idempotency-Key is still in progress; retry shortly");
        }

        log.info("Replaying stored response for endpoint={} user={}",
                record.getEndpoint(), record.getUserId());
        return new Result<>(deserialize(record.getResponseBody(), responseType), true);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Not fatal to the operation, which already succeeded — but it does mean
            // a retry will re-run the work, so it must be visible in the log.
            log.error("Could not serialise response for replay; retries will re-execute", e);
            return null;
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Stored idempotent response could not be read", e);
        }
    }

    /**
     * @param value   the result, whether freshly computed or replayed
     * @param replayed true when this came from a stored response — the controller uses
     *                 it to answer 200 instead of 201, since nothing was created
     */
    public record Result<T>(T value, boolean replayed) {
    }
}
