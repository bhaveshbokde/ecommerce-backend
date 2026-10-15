package com.bhavesh.ecommerce.inventory;

import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The overselling guarantee, proved with real threads against real PostgreSQL.
 *
 * <p><strong>This is the test that matters, and it is the one a mock cannot
 * stand in for.</strong> {@code InventoryServiceTest} asserts that the service
 * treats a zero-row UPDATE as insufficient stock — which is the right thing for a
 * unit test to check, and which says nothing at all about whether the UPDATE is
 * actually atomic. Atomicity is a property of PostgreSQL's row locking under
 * READ COMMITTED, so demonstrating it requires PostgreSQL and requires
 * contention.
 *
 * <h2>Why a barrier rather than just starting threads</h2>
 * Threads started in a loop do not run simultaneously; the first may well finish
 * before the last begins, and the test would pass without ever creating the race
 * it claims to test. A {@link CyclicBarrier} holds every thread until all of them
 * have arrived, then releases them together.
 *
 * <h2>Tagged, and why</h2>
 * {@code @Tag("concurrency")} keeps these out of the default {@code ./mvnw test}
 * run: they need a real database and they are slower than everything else. They
 * run under {@code ./mvnw verify -Pintegration}, which is what CI executes.
 */
@Tag("concurrency")
class InventoryConcurrencyIT extends AbstractIntegrationTest {

    /** Seeded with exactly 5 units by V9, so the ceiling is small and known. */
    private static final String SCARCE_SKU = "ELEC-LAPTOP-001";
    private static final int STOCK = 5;
    private static final int BUYERS = 20;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    private Long scarceProductId;

    @BeforeEach
    void setUp() {
        scarceProductId = productIdBySku(SCARCE_SKU);
        assertThat(availableStock(SCARCE_SKU))
                .as("the fixture depends on the seeded stock level")
                .isEqualTo(STOCK);
    }

    /**
     * Runs {@code task} on {@code count} threads released simultaneously.
     *
     * @return every result, in completion-agnostic order
     */
    private <T> List<T> inParallel(int count, Callable<T> task) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------- the repository statement --

    /**
     * The guarantee at its narrowest: {@code count} transactions each try to take
     * one unit of a product that has five. Exactly five UPDATEs may affect a row.
     */
    @Test
    @Timeout(120)
    @DisplayName("concurrent reservations never oversell — the conditional UPDATE decides")
    void conditionalUpdateNeverOversells() throws Exception {
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        inParallel(BUYERS, () -> {
            // Each reservation in its own transaction, as it would be in a request.
            Integer rows = txTemplate.execute(status ->
                    inventoryRepository.reserve(scarceProductId, 1));
            if (rows != null && rows == 1) {
                granted.incrementAndGet();
            } else {
                refused.incrementAndGet();
            }
            return rows;
        });

        assertThat(granted.get())
                .as("exactly the available stock may be sold, no matter how many ask")
                .isEqualTo(STOCK);
        assertThat(refused.get()).isEqualTo(BUYERS - STOCK);

        assertThat(availableStock(SCARCE_SKU)).isZero();
        assertThat(reservedStock(SCARCE_SKU)).isEqualTo(STOCK);
    }

    @Test
    @Timeout(120)
    @DisplayName("stock never goes negative, even with mixed quantities")
    void neverGoesNegative() throws Exception {
        // 20 threads each asking for 2 units out of 5. At most two can succeed.
        AtomicInteger granted = new AtomicInteger();

        inParallel(BUYERS, () -> {
            Integer rows = txTemplate.execute(status ->
                    inventoryRepository.reserve(scarceProductId, 2));
            if (rows != null && rows == 1) {
                granted.incrementAndGet();
            }
            return rows;
        });

        assertThat(granted.get()).isEqualTo(2);              // 2 x 2 = 4 of 5 units
        assertThat(availableStock(SCARCE_SKU)).isEqualTo(1);
        assertThat(reservedStock(SCARCE_SKU)).isEqualTo(4);
    }

    /**
     * Release has to be safe under the same contention: compensating actions run
     * from failure handling, often more than once.
     */
    @Test
    @Timeout(120)
    @DisplayName("concurrent releases return exactly what was reserved, never more")
    void concurrentReleasesDoNotInflateStock() throws Exception {
        txTemplate.executeWithoutResult(status ->
                inventoryRepository.reserve(scarceProductId, STOCK));
        assertThat(reservedStock(SCARCE_SKU)).isEqualTo(STOCK);

        // Twenty threads each try to release one unit; only five units are held.
        inParallel(BUYERS, () -> txTemplate.execute(status ->
                inventoryRepository.release(scarceProductId, 1)));

        assertThat(availableStock(SCARCE_SKU))
                .as("stock must return to its original level, not above it")
                .isEqualTo(STOCK);
        assertThat(reservedStock(SCARCE_SKU)).isZero();
    }

    // ------------------------------------------------------ the whole HTTP flow --

    /**
     * The same guarantee through the full stack: twenty authenticated customers,
     * each with one unit in their cart, all checking out at once.
     */
    @Test
    @Timeout(180)
    @DisplayName("concurrent checkouts sell exactly the available stock and no more")
    void concurrentCheckoutsNeverOversell() throws Exception {
        List<String> tokens = new ArrayList<>(BUYERS);
        for (int i = 0; i < BUYERS; i++) {
            String token = customerToken("buyer" + i + "@example.com");
            mockMvc.perform(post("/api/v1/cart/items")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId": %d, "quantity": 1}""".formatted(scarceProductId)))
                    .andReturn();
            tokens.add(token);
        }

        AtomicInteger created = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Integer> statuses = new ArrayList<>();
        CyclicBarrier startLine = new CyclicBarrier(BUYERS);
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        try {
            List<Future<Integer>> futures = new ArrayList<>(BUYERS);
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/orders")
                                    .header(HttpHeaders.AUTHORIZATION, token))
                            .andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        for (int status : statuses) {
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                outOfStock.incrementAndGet();
            } else {
                unexpected.incrementAndGet();
            }
        }

        assertThat(unexpected.get())
                .as("a losing checkout must get a clean 409, never a 500 — "
                    + "a deadlock or a constraint violation would land here")
                .isZero();
        assertThat(created.get()).isEqualTo(STOCK);
        assertThat(outOfStock.get()).isEqualTo(BUYERS - STOCK);

        assertThat(countRows("orders")).isEqualTo(STOCK);
        assertThat(availableStock(SCARCE_SKU)).isZero();
        assertThat(reservedStock(SCARCE_SKU)).isEqualTo(STOCK);

        Long unitsSold = transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT COALESCE(SUM(quantity), 0) FROM order_items")
                .getSingleResult()).longValue());
        assertThat(unitsSold)
                .as("the ledger must agree with the shelf")
                .isEqualTo((long) STOCK);
    }

    /**
     * Two customers buying {A, B} and {B, A} would take the row locks in opposite
     * orders and deadlock. PostgreSQL detects the cycle and kills one transaction
     * with SQLSTATE 40P01, turning a perfectly valid pair of orders into a 500.
     *
     * <p>{@code OrderService} sorts cart lines by product id before reserving, so
     * the cycle cannot form. This test buys the same two products from opposite
     * directions as hard as it can; a single 500 fails it.
     */
    @Test
    @Timeout(180)
    @DisplayName("opposite-order multi-product checkouts do not deadlock")
    void deterministicLockOrderPreventsDeadlock() throws Exception {
        Long keyboardId = productIdBySku("ELEC-KBD-001");
        Long mouseId = productIdBySku("ELEC-WATCH-001");

        int pairs = 12;
        List<String> tokens = new ArrayList<>(pairs);
        for (int i = 0; i < pairs; i++) {
            String token = customerToken("pair" + i + "@example.com");
            // Half add A then B, half add B then A. The cart's insertion order
            // differs; the reservation order must not.
            Long first = (i % 2 == 0) ? keyboardId : mouseId;
            Long second = (i % 2 == 0) ? mouseId : keyboardId;

            for (Long productId : List.of(first, second)) {
                mockMvc.perform(post("/api/v1/cart/items")
                                .header(HttpHeaders.AUTHORIZATION, token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"productId": %d, "quantity": 1}""".formatted(productId)))
                        .andReturn();
            }
            tokens.add(token);
        }

        List<Integer> statuses = inParallelHttp(tokens);

        assertThat(statuses)
                .as("every checkout must succeed; a 500 here means a deadlock was detected")
                .allMatch(status -> status == 201);
        assertThat(countRows("orders")).isEqualTo(pairs);
    }

    private List<Integer> inParallelHttp(List<String> tokens) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(tokens.size());
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/orders")
                                    .header(HttpHeaders.AUTHORIZATION, token))
                            .andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>(tokens.size());
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code uq_payments_order_active} is a partial unique index over
     * {@code status IN ('PENDING','SUCCEEDED')}. Two concurrent charges against one
     * order must produce exactly one payment row, with the loser told so rather
     * than receiving a 500.
     */
    @Test
    @Timeout(180)
    @DisplayName("concurrent payments for one order charge exactly once")
    void concurrentPaymentsChargeOnce() throws Exception {
        String token = customerToken("priya@example.com");
        mockMvc.perform(post("/api/v1/cart/items")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId": %d, "quantity": 1}""".formatted(scarceProductId)));

        String order = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString();
        Long orderId = json(order).get("id").asLong();

        int attempts = 10;
        CyclicBarrier startLine = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> statuses = new ArrayList<>(attempts);
        try {
            List<Future<Integer>> futures = new ArrayList<>(attempts);
            for (int i = 0; i < attempts; i++) {
                // Distinct keys on purpose: this tests the per-order index, not the
                // per-key one. Identical keys would be caught earlier, by the replay.
                String key = "concurrent-pay-" + i;
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/payments")
                                    .header(HttpHeaders.AUTHORIZATION, token)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"orderId": %d, "simulateOutcome": "SUCCESS"}"""
                                            .formatted(orderId)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long succeeded = statuses.stream().filter(s -> s == 201).count();
        long conflicted = statuses.stream().filter(s -> s == 409).count();

        assertThat(succeeded).as("exactly one charge may be created").isEqualTo(1);
        assertThat(conflicted + succeeded)
                .as("every other attempt must get a clean 409, never a 500")
                .isEqualTo(attempts);

        assertThat(countRows("payments")).isEqualTo(1);
    }

    /**
     * The same idempotency key sent many times at once. The unique index
     * {@code uq_payments_user_idempotency} decides; nobody is charged twice and
     * nobody gets a 500.
     */
    @Test
    @Timeout(180)
    @DisplayName("the same idempotency key sent concurrently produces one payment")
    void concurrentRetriesOfOneKey() throws Exception {
        String token = customerToken("priya@example.com");
        mockMvc.perform(post("/api/v1/cart/items")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId": %d, "quantity": 1}""".formatted(scarceProductId)));

        Long orderId = json(mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        int attempts = 10;
        CyclicBarrier startLine = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> statuses = new ArrayList<>(attempts);
        try {
            List<Future<Integer>> futures = new ArrayList<>(attempts);
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/payments")
                                    .header(HttpHeaders.AUTHORIZATION, token)
                                    .header("Idempotency-Key", "the-one-and-only-key")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"orderId": %d, "simulateOutcome": "SUCCESS"}"""
                                            .formatted(orderId)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses)
                .as("every response must be a clean 201, 200 (replay) or 409 — never a 500")
                .allMatch(s -> s == 201 || s == 200 || s == 409);
        assertThat(statuses.stream().filter(s -> s == 201).count())
                .as("exactly one attempt may be created")
                .isEqualTo(1);

        assertThat(countRows("payments")).isEqualTo(1);
    }

    /**
     * The database constraint is the last line of defence, below every application
     * rule. This bypasses the service entirely and asserts that a direct UPDATE
     * cannot drive stock negative.
     */
    @Test
    @DisplayName("the CHECK constraint refuses a negative balance even for a direct UPDATE")
    void checkConstraintIsTheBackstop() {
        assertThat(availableStock(SCARCE_SKU)).isEqualTo(STOCK);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        txTemplate.executeWithoutResult(status -> entityManager
                                .createNativeQuery(
                                        "UPDATE inventory SET available = -1 WHERE product_id = :id")
                                .setParameter("id", scarceProductId)
                                .executeUpdate()))
                .hasMessageContaining("ck_inventory_available_non_negative");

        assertThat(availableStock(SCARCE_SKU)).isEqualTo(STOCK);
    }
}
