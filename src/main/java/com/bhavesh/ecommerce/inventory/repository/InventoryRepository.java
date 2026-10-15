package com.bhavesh.ecommerce.inventory.repository;

import com.bhavesh.ecommerce.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Data access for stock levels.
 *
 * <p>The three mutating methods are native, conditional UPDATEs rather than
 * entity manipulation. That is the whole concurrency strategy, and it is worth
 * stating plainly why.
 *
 * <h2>Why a conditional UPDATE</h2>
 * <pre>{@code
 *   UPDATE inventory
 *      SET available = available - :qty
 *    WHERE product_id = :id
 *      AND available >= :qty
 * }</pre>
 *
 * PostgreSQL takes a row-level lock before applying this statement. A second
 * transaction attempting the same row <em>blocks</em> until the first commits, then
 * — under READ COMMITTED — re-evaluates the WHERE clause against the newly
 * committed row rather than its original snapshot. So the second transaction sees
 * the decremented value, the predicate fails, and the statement reports zero rows
 * affected. The caller turns that zero into
 * {@link com.bhavesh.ecommerce.exception.InsufficientStockException}.
 *
 * <p>The check and the decrement are one statement. There is no window between
 * them for another transaction to act, which is exactly what a
 * read-then-write in Java cannot promise.
 *
 * <h2>Why not the alternatives</h2>
 * <ul>
 *   <li><strong>Optimistic locking ({@code @Version})</strong> — correct, but the
 *       loser gets an exception and must retry. On a popular product under flash-sale
 *       traffic that is a retry storm. The conditional UPDATE lets contending
 *       transactions queue briefly and then succeed.</li>
 *   <li><strong>Pessimistic locking ({@code SELECT … FOR UPDATE})</strong> — also
 *       correct, and in fact what the conditional UPDATE does implicitly, but as
 *       two statements and two round trips, with a lock held across application
 *       code for the gap in between.</li>
 *   <li><strong>{@code synchronized} or a JVM lock</strong> — wrong. It protects
 *       one process. Two application instances behind a load balancer share a
 *       database, not a monitor.</li>
 *   <li><strong>SERIALIZABLE isolation</strong> — correct, and slower for every
 *       other query in the application to solve a problem localised to one table.</li>
 * </ul>
 *
 * <h2>Why native SQL</h2>
 * JPQL has no portable way to express "update only if the current value satisfies
 * a predicate, and tell me how many rows matched" without the dialect ambiguity
 * that would undermine the guarantee. The constraint is a PostgreSQL behaviour, so
 * the statement is PostgreSQL.
 *
 * <p>{@code clearAutomatically = true} matters: these UPDATEs bypass the
 * persistence context, so a previously loaded {@code Inventory} in the same
 * transaction would otherwise still report the stale {@code available}.
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Atomically moves {@code quantity} units from available to reserved.
     *
     * @return 1 when the reservation succeeded, 0 when stock was insufficient.
     *         Never throws for insufficient stock — zero rows is the answer.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE inventory
               SET available  = available - :quantity,
                   reserved   = reserved  + :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND available >= :quantity
            """, nativeQuery = true)
    int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Returns reserved units to the available pool — a failed payment, or a
     * cancelled order.
     *
     * <p>{@code AND reserved >= :quantity} makes this safe to call twice: the
     * second call matches no row and returns 0 rather than inflating stock. Payment
     * failure handling can therefore be retried without inventing phantom units.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE inventory
               SET available  = available + :quantity,
                   reserved   = reserved  - :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND reserved  >= :quantity
            """, nativeQuery = true)
    int release(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Consumes a reservation permanently — the goods have shipped.
     *
     * <p>{@code available} is untouched; those units left it at reservation time.
     * This only clears the hold, so the row stops claiming stock it no longer has.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE inventory
               SET reserved   = reserved - :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND reserved  >= :quantity
            """, nativeQuery = true)
    int commitReservation(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Administrative restock. Additive, not absolute, so two concurrent deliveries
     * both count — {@code setAvailable} would let one overwrite the other.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE inventory
               SET available  = available + :quantity,
                   updated_at = now()
             WHERE product_id = :productId
            """, nativeQuery = true)
    int addStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /** Batch read for cart and checkout views; one query instead of one per line. */
    List<Inventory> findByProductIdIn(Collection<Long> productIds);
}
