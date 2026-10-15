package com.bhavesh.ecommerce.inventory.service;

import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.repository.ProductRepository;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.InsufficientStockException;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.dto.InventoryResponse;
import com.bhavesh.ecommerce.inventory.entity.Inventory;
import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Stock reservation, release and administration.
 *
 * <p>This is the concurrency-critical class in the application, and the whole of
 * its correctness rests on one idea: <strong>the decision and the change are a
 * single SQL statement</strong>. See
 * {@link InventoryRepository#reserve} for why, and for why the obvious
 * alternatives are worse.
 *
 * <h2>Transaction boundaries</h2>
 * {@link #reserve} and {@link #releaseAll} are {@code MANDATORY}: they must run
 * inside the caller's transaction, because a reservation that commits separately
 * from the order it is for can leave stock held against an order that never
 * existed. Declaring it {@code MANDATORY} rather than {@code REQUIRED} turns a
 * future misuse into a startup-time-obvious failure instead of a silent leak.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;

    public InventoryService(InventoryRepository inventoryRepository,
                            ProductRepository productRepository) {
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
    }

    // ------------------------------------------------------------ reservation --

    /**
     * Atomically reserves stock for one product.
     *
     * <p>The repository returns the number of rows the conditional UPDATE matched.
     * One means the reservation is held. Zero means the database re-evaluated
     * {@code available >= quantity} against the latest committed row and found it
     * false — there was not enough stock at the moment it mattered, not at the
     * moment somebody read it.
     *
     * <p>The error path re-reads the row purely to report how much <em>is</em>
     * available. That figure is advisory and may already be stale; it is for the
     * message, never for a decision.
     *
     * @throws InsufficientStockException when the UPDATE matched no row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long productId, String productName, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }

        int rowsAffected = inventoryRepository.reserve(productId, quantity);

        if (rowsAffected == 0) {
            int available = inventoryRepository.findById(productId)
                    .map(Inventory::getAvailable)
                    .orElse(0);
            log.warn("Reservation refused: product={} requested={} available={}",
                    productId, quantity, available);
            throw new InsufficientStockException(productId, productName, quantity, available);
        }

        log.debug("Reserved {} unit(s) of product {}", quantity, productId);
    }

    /**
     * Returns reserved units to the available pool.
     *
     * <p>Logs rather than throws when the UPDATE matches nothing. Release is called
     * from failure handling — a declined payment, a cancelled order — and a
     * compensating action that throws on a double-call turns one problem into two.
     * The {@code reserved >= quantity} guard in the UPDATE means a second call is a
     * no-op rather than an inflation of stock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long productId, int quantity) {
        int rowsAffected = inventoryRepository.release(productId, quantity);
        if (rowsAffected == 0) {
            log.warn("Release matched no row: product={} quantity={} "
                    + "(already released, or reserved is lower than expected)", productId, quantity);
        } else {
            log.debug("Released {} unit(s) of product {}", quantity, productId);
        }
    }

    /** Releases a whole order's worth of reservations: product id to quantity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAll(Map<Long, Integer> quantitiesByProduct) {
        quantitiesByProduct.forEach(this::release);
    }

    /**
     * Consumes reservations permanently — the goods have shipped.
     *
     * <p>{@code available} is untouched: those units left it when they were
     * reserved. This only clears the hold.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitAll(Map<Long, Integer> quantitiesByProduct) {
        quantitiesByProduct.forEach((productId, quantity) -> {
            int rowsAffected = inventoryRepository.commitReservation(productId, quantity);
            if (rowsAffected == 0) {
                log.warn("Commit matched no row: product={} quantity={}", productId, quantity);
            }
        });
    }

    // --------------------------------------------------------- administration --

    @Transactional(readOnly = true)
    public InventoryResponse get(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventory for product %d was not found".formatted(productId)));
        return InventoryResponse.from(inventory, product.getSku());
    }

    /**
     * Applies a relative stock adjustment.
     *
     * <p>Additive, so two concurrent deliveries both count. A negative adjustment
     * reuses the reservation statement, which is what makes it safe: its
     * {@code available >= quantity} predicate stops a correction from driving the
     * figure below zero, and a refusal is reported rather than clamped.
     *
     * <p>A negative adjustment moves units into {@code reserved} as a side effect of
     * reusing that statement, so it is immediately released — the net effect is a
     * pure decrement of {@code available}, achieved without ever reading the value
     * first.
     */
    @Transactional
    public InventoryResponse adjust(Long productId, int delta, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        if (delta == 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "quantity must not be zero");
        }

        if (delta > 0) {
            int rowsAffected = inventoryRepository.addStock(productId, delta);
            if (rowsAffected == 0) {
                throw new ResourceNotFoundException(
                        "Inventory for product %d was not found".formatted(productId));
            }
        } else {
            int magnitude = -delta;
            // Reserve-then-release: an atomic guarded decrement, with no read.
            int reserved = inventoryRepository.reserve(productId, magnitude);
            if (reserved == 0) {
                int available = inventoryRepository.findById(productId)
                        .map(Inventory::getAvailable)
                        .orElse(0);
                throw new InsufficientStockException(productId, product.getName(),
                        magnitude, available);
            }
            inventoryRepository.commitReservation(productId, magnitude);
        }

        log.info("Stock adjusted: product={} delta={} reason={}",
                productId, delta, reason == null ? "(none)" : reason);

        Inventory updated = inventoryRepository.findById(productId).orElseThrow();
        return InventoryResponse.from(updated, product.getSku());
    }

    /** Batch read for cart and checkout views. */
    @Transactional(readOnly = true)
    public List<Inventory> loadByProductIds(List<Long> productIds) {
        return productIds.isEmpty() ? List.of() : inventoryRepository.findByProductIdIn(productIds);
    }
}
