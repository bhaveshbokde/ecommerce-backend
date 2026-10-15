package com.bhavesh.ecommerce.idempotency.service;

import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.idempotency.entity.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Replay protection for non-idempotent POSTs. */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String ENDPOINT = "POST /api/v1/orders";
    private static final String BODY = "checkout:7";

    @Mock
    private IdempotencyStore store;

    private IdempotencyService idempotencyService;
    private AtomicInteger invocations;

    /** A stand-in for whatever the guarded endpoint returns. */
    record Receipt(Long id, String reference) {
    }

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(store, new ObjectMapper());
        invocations = new AtomicInteger();
    }

    private Receipt doWork() {
        invocations.incrementAndGet();
        return new Receipt(1L, "ORD-1");
    }

    private IdempotencyRecord recordWith(Long id, String requestHash, String responseBody) {
        IdempotencyRecord record = new IdempotencyRecord(7L, "key-1", ENDPOINT, requestHash);
        ReflectionTestUtils.setField(record, "id", id);
        if (responseBody != null) {
            record.recordResponse(201, responseBody);
        }
        return record;
    }

    @Nested
    @DisplayName("without a key")
    class NoKey {

        /**
         * The header is optional on checkout. A client that omits it accepts that a
         * retry may duplicate the work — their choice, made explicit rather than
         * forced.
         */
        @Test
        @DisplayName("the action simply runs; nothing is recorded")
        void runsDirectly() {
            var result = idempotencyService.execute(7L, ENDPOINT, null, BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork);

            assertThat(result.value().reference()).isEqualTo("ORD-1");
            assertThat(result.replayed()).isFalse();
            assertThat(invocations).hasValue(1);
            verify(store, never()).claim(anyLong(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a blank key is treated as no key")
        void blankKey() {
            idempotencyService.execute(7L, ENDPOINT, "   ", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork);

            assertThat(invocations).hasValue(1);
            verify(store, never()).claim(anyLong(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("first use of a key")
    class FirstUse {

        @Test
        @DisplayName("claims the key, runs the action and records the response")
        void claimsRunsRecords() {
            when(store.find(7L, ENDPOINT, "key-1")).thenReturn(Optional.empty());
            when(store.claim(eq(7L), eq(ENDPOINT), eq("key-1"), anyString()))
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), null)));

            var result = idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork);

            assertThat(result.replayed()).isFalse();
            assertThat(invocations).hasValue(1);
            verify(store).recordResponse(eq(100L), anyInt(), anyString());
        }

        /**
         * Without this, a request that died on a transient fault would leave its key
         * claimed with no stored response, and every retry would be told the
         * original is still in flight — forever.
         */
        @Test
        @DisplayName("a failing action releases the claim so a genuine retry is possible")
        void failureReleasesTheClaim() {
            when(store.find(7L, ENDPOINT, "key-1")).thenReturn(Optional.empty());
            when(store.claim(eq(7L), eq(ENDPOINT), eq("key-1"), anyString()))
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), null)));

            assertThatThrownBy(() -> idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, () -> {
                        throw new IllegalStateException("database went away");
                    }))
                    .isInstanceOf(IllegalStateException.class);

            verify(store).releaseClaim(100L);
            verify(store, never()).recordResponse(anyLong(), anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("a stored response is returned verbatim and the action does not run")
        void replaysStoredResponse() throws Exception {
            String stored = new ObjectMapper().writeValueAsString(new Receipt(1L, "ORD-1"));
            when(store.find(7L, ENDPOINT, "key-1"))
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), stored)));

            var result = idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork);

            assertThat(result.replayed()).isTrue();
            assertThat(result.value()).isEqualTo(new Receipt(1L, "ORD-1"));
            assertThat(invocations)
                    .as("the guarded work must not run a second time")
                    .hasValue(0);
        }

        /**
         * The key alone cannot tell a genuine retry from a client bug. Replaying the
         * first response for a different body would silently swallow a real second
         * request — which is why a hash of the body is stored alongside the key.
         */
        @Test
        @DisplayName("the same key with a different body is 422, not a replay")
        void keyReuseWithDifferentBody() {
            when(store.find(7L, ENDPOINT, "key-1"))
                    .thenReturn(Optional.of(recordWith(100L,
                            IdempotencyRecord.hash("a completely different request"), "{}")));

            assertThatThrownBy(() -> idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);

            assertThat(invocations).hasValue(0);
        }

        /**
         * Waiting on the original would tie up a request thread; proceeding would
         * duplicate the work the key exists to prevent. Telling the client to retry
         * is the only honest third option.
         */
        @Test
        @DisplayName("a claimed key with no response yet is 409, with a retry hint")
        void stillInFlight() {
            when(store.find(7L, ENDPOINT, "key-1"))
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), null)));

            assertThatThrownBy(() -> idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION)
                    .hasMessageContaining("still in progress");
        }
    }

    @Nested
    @DisplayName("concurrent first use")
    class ClaimRace {

        /**
         * Two requests both read "no record", both try to insert, and the unique
         * index rejects one. The loser must behave exactly as if it had seen the
         * winner's row on its first read.
         */
        @Test
        @DisplayName("losing the claim race falls back to the replay path")
        void losingTheClaimRace() throws Exception {
            String stored = new ObjectMapper().writeValueAsString(new Receipt(1L, "ORD-1"));
            when(store.find(7L, ENDPOINT, "key-1"))
                    .thenReturn(Optional.empty())   // our first read
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), stored)));
            when(store.claim(eq(7L), eq(ENDPOINT), eq("key-1"), anyString()))
                    .thenReturn(Optional.empty());  // the unique index rejected us

            var result = idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork);

            assertThat(result.replayed()).isTrue();
            assertThat(invocations)
                    .as("the loser must not also perform the work")
                    .hasValue(0);
        }

        @Test
        @DisplayName("losing the race to a request still in flight reports a conflict")
        void losingToAnInFlightRequest() {
            when(store.find(7L, ENDPOINT, "key-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(recordWith(100L, IdempotencyRecord.hash(BODY), null)));
            when(store.claim(eq(7L), eq(ENDPOINT), eq("key-1"), anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> idempotencyService.execute(7L, ENDPOINT, "key-1", BODY,
                    Receipt.class, IdempotencyServiceTest.this::doWork))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        }
    }

    @Nested
    @DisplayName("the request hash")
    class Hashing {

        @Test
        @DisplayName("is a lowercase hex SHA-256, matching ck_idempotency_request_hash")
        void shape() {
            assertThat(IdempotencyRecord.hash("anything")).matches("[0-9a-f]{64}");
        }

        @Test
        void isDeterministic() {
            assertThat(IdempotencyRecord.hash("checkout:7"))
                    .isEqualTo(IdempotencyRecord.hash("checkout:7"));
        }

        @Test
        void differsForDifferentInput() {
            assertThat(IdempotencyRecord.hash("checkout:7"))
                    .isNotEqualTo(IdempotencyRecord.hash("checkout:8"));
        }

        @Test
        @DisplayName("null and empty hash alike, so an absent body is not a special case")
        void nullIsEmpty() {
            assertThat(IdempotencyRecord.hash(null)).isEqualTo(IdempotencyRecord.hash(""));
        }
    }
}
