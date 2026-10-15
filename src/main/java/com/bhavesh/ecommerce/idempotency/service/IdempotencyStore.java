package com.bhavesh.ecommerce.idempotency.service;

import com.bhavesh.ecommerce.idempotency.entity.IdempotencyRecord;
import com.bhavesh.ecommerce.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transactional primitives behind {@link IdempotencyService}.
 *
 * <p>Separate class, and that is not an accident. Both methods here need
 * {@code REQUIRES_NEW}, and {@code @Transactional} is implemented with a proxy:
 * a call from one method of a bean to another method of the <em>same</em> bean
 * goes straight to {@code this}, bypassing the proxy entirely, so the annotation
 * would be silently ignored. Spring cannot warn about it; the code simply does not
 * do what it says. Putting these on a different bean means the call goes through
 * the proxy and the new transaction actually starts.
 *
 * <h2>Why a separate transaction is required</h2>
 * Claiming a key must become visible to <em>other</em> transactions immediately,
 * before the work it guards has finished. If the claim were part of the caller's
 * transaction, a concurrent duplicate request would not see it — uncommitted rows
 * are invisible under READ COMMITTED — and both requests would proceed. The claim
 * commits on its own so the second request collides with it at once.
 *
 * <p>The same applies in reverse to recording the response: it must survive even
 * if the business transaction is later rolled back, because "this key produced a
 * 409" is still the correct answer to replay.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(Long userId, String endpoint, String key) {
        return repository.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, key);
    }

    /**
     * Claims a key by inserting its row, committing immediately.
     *
     * @return the claimed record, or empty if another request got there first.
     *         The caller then re-reads and either replays or reports an in-flight
     *         duplicate. Returning empty rather than throwing keeps the race on the
     *         ordinary control-flow path, where it belongs — it is expected, not
     *         exceptional.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> claim(Long userId, String endpoint, String key, String requestHash) {
        try {
            return Optional.of(repository.saveAndFlush(
                    new IdempotencyRecord(userId, key, endpoint, requestHash)));
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // uq_idempotency_user_endpoint_key rejected it. Expected under a race.
            return Optional.empty();
        }
    }

    /** Stores the response to replay, in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResponse(Long recordId, int status, String body) {
        repository.findById(recordId).ifPresent(record -> {
            record.recordResponse(status, body);
            repository.save(record);
        });
    }

    /**
     * Drops a claim whose work failed, so the client may genuinely retry.
     *
     * <p>Without this, a request that died on a transient fault would leave its key
     * permanently claimed with no stored response, and every retry would be told
     * "that key is still in flight" forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(Long recordId) {
        repository.deleteById(recordId);
    }
}
