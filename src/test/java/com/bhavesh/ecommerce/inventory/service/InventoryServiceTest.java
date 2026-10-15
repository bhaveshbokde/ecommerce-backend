package com.bhavesh.ecommerce.inventory.service;

import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.repository.ProductRepository;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.InsufficientStockException;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.entity.Inventory;
import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Reservation, release and adjustment.
 *
 * <p>These tests assert on the <em>contract between the service and the
 * repository</em>: the service must treat a zero-row UPDATE as "not enough stock"
 * and must never read-then-decide. The database-level proof that the UPDATE really
 * is atomic lives in {@code InventoryConcurrencyIT}, which runs real threads
 * against real PostgreSQL — a mock cannot demonstrate atomicity, and pretending it
 * can would be the most misleading test in the suite.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private InventoryService inventoryService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product("ELEC-LAPTOP-001", "UltraBook 14 Pro", "A laptop",
                new BigDecimal("89999.00"), new Category("Electronics", null));
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("one affected row means the reservation is held")
        void successfulReservation() {
            when(inventoryRepository.reserve(5L, 2)).thenReturn(1);

            inventoryService.reserve(5L, "UltraBook 14 Pro", 2);

            verify(inventoryRepository).reserve(5L, 2);
            // No read at all on the happy path — the UPDATE is the decision.
            verify(inventoryRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("zero affected rows means insufficient stock")
        void zeroRowsMeansInsufficientStock() {
            when(inventoryRepository.reserve(5L, 10)).thenReturn(0);
            when(inventoryRepository.findById(5L))
                    .thenReturn(Optional.of(new Inventory(5L, 3)));

            assertThatThrownBy(() -> inventoryService.reserve(5L, "UltraBook 14 Pro", 10))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK)
                    .hasFieldOrPropertyWithValue("productId", 5L)
                    .hasFieldOrPropertyWithValue("requested", 10)
                    .hasFieldOrPropertyWithValue("available", 3)
                    .hasMessageContaining("UltraBook 14 Pro");
        }

        /**
         * The conditional UPDATE runs <em>before</em> any read. This ordering is the
         * whole design: reading first and deciding in Java is the race the UPDATE
         * exists to eliminate, and a refactor that reversed these two calls would
         * reintroduce it while leaving every other assertion green.
         */
        @Test
        @DisplayName("the UPDATE precedes the read; the read is only for the message")
        void updateComesBeforeAnyRead() {
            when(inventoryRepository.reserve(5L, 10)).thenReturn(0);
            when(inventoryRepository.findById(5L)).thenReturn(Optional.of(new Inventory(5L, 3)));

            assertThatThrownBy(() -> inventoryService.reserve(5L, "UltraBook", 10))
                    .isInstanceOf(InsufficientStockException.class);

            InOrder order = inOrder(inventoryRepository);
            order.verify(inventoryRepository).reserve(5L, 10);
            order.verify(inventoryRepository).findById(5L);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("a missing inventory row reports zero available rather than failing to report")
        void missingRowReportsZero() {
            when(inventoryRepository.reserve(99L, 1)).thenReturn(0);
            when(inventoryRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.reserve(99L, "Ghost", 1))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasFieldOrPropertyWithValue("available", 0);
        }

        @Test
        @DisplayName("a non-positive quantity is a programming error, not a stock problem")
        void nonPositiveQuantityIsAProgrammingError() {
            assertThatThrownBy(() -> inventoryService.reserve(5L, "UltraBook", 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inventoryService.reserve(5L, "UltraBook", -3))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoMoreInteractions(inventoryRepository);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        void releasesTheRequestedQuantity() {
            when(inventoryRepository.release(5L, 2)).thenReturn(1);

            inventoryService.release(5L, 2);

            verify(inventoryRepository).release(5L, 2);
        }

        /**
         * Release is compensation: it runs from failure handling, after a declined
         * payment or a cancellation. Throwing on a double-call would turn one
         * problem into two, and the repository's {@code reserved >= quantity} guard
         * already makes the second call a no-op rather than an inflation of stock.
         */
        @Test
        @DisplayName("a no-op release does not throw")
        void doubleReleaseIsSilent() {
            when(inventoryRepository.release(5L, 2)).thenReturn(0);

            inventoryService.release(5L, 2);   // must not throw

            verify(inventoryRepository).release(5L, 2);
        }

        @Test
        @DisplayName("releaseAll releases every line of an order")
        void releasesEveryLine() {
            when(inventoryRepository.release(anyLong(), anyInt())).thenReturn(1);

            Map<Long, Integer> quantities = new LinkedHashMap<>();
            quantities.put(1L, 2);
            quantities.put(5L, 3);

            inventoryService.releaseAll(quantities);

            verify(inventoryRepository).release(1L, 2);
            verify(inventoryRepository).release(5L, 3);
        }

        @Test
        @DisplayName("committing a reservation clears the hold without touching available")
        void commitClearsTheHold() {
            when(inventoryRepository.commitReservation(anyLong(), anyInt())).thenReturn(1);

            inventoryService.commitAll(Map.of(5L, 3));

            verify(inventoryRepository).commitReservation(5L, 3);
            verify(inventoryRepository, never()).release(anyLong(), anyInt());
            verify(inventoryRepository, never()).addStock(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("administrative adjustment")
    class Adjustment {

        @BeforeEach
        void productExists() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        }

        @Test
        @DisplayName("a positive delta is additive, so concurrent deliveries both count")
        void positiveDeltaIsAdditive() {
            when(inventoryRepository.addStock(5L, 50)).thenReturn(1);
            when(inventoryRepository.findById(5L)).thenReturn(Optional.of(new Inventory(5L, 60)));

            var response = inventoryService.adjust(5L, 50, "Supplier delivery");

            verify(inventoryRepository).addStock(5L, 50);
            assertThat(response.available()).isEqualTo(60);
        }

        /**
         * The negative path reuses the reservation statement precisely because its
         * {@code available >= quantity} predicate is the guarded decrement — it
         * cannot drive stock below zero, and it never reads the value first.
         */
        @Test
        @DisplayName("a negative delta goes through the guarded reserve-then-commit path")
        void negativeDeltaUsesTheGuardedDecrement() {
            when(inventoryRepository.reserve(5L, 10)).thenReturn(1);
            when(inventoryRepository.commitReservation(5L, 10)).thenReturn(1);
            when(inventoryRepository.findById(5L)).thenReturn(Optional.of(new Inventory(5L, 40)));

            inventoryService.adjust(5L, -10, "Damaged in transit");

            InOrder order = inOrder(inventoryRepository);
            order.verify(inventoryRepository).reserve(5L, 10);
            order.verify(inventoryRepository).commitReservation(5L, 10);

            verify(inventoryRepository, never()).addStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("a negative delta larger than stock is refused rather than clamped")
        void refusesRatherThanClamping() {
            when(inventoryRepository.reserve(5L, 100)).thenReturn(0);
            when(inventoryRepository.findById(5L)).thenReturn(Optional.of(new Inventory(5L, 7)));

            assertThatThrownBy(() -> inventoryService.adjust(5L, -100, "Shrinkage"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasFieldOrPropertyWithValue("available", 7);

            verify(inventoryRepository, never()).commitReservation(anyLong(), anyInt());
        }

        @Test
        @DisplayName("a zero adjustment is rejected as a meaningless request")
        void zeroIsRejected() {
            assertThatThrownBy(() -> inventoryService.adjust(5L, 0, "Nothing"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);

            verifyNoMoreInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("adjusting a product with no stock row reports it as missing")
        void missingInventoryRow() {
            when(inventoryRepository.addStock(5L, 10)).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.adjust(5L, 10, "Delivery"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Inventory");
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void getReturnsStockWithTheSku() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product));
            when(inventoryRepository.findById(5L)).thenReturn(Optional.of(new Inventory(5L, 12)));

            var response = inventoryService.get(5L);

            assertThat(response.sku()).isEqualTo("ELEC-LAPTOP-001");
            assertThat(response.available()).isEqualTo(12);
            assertThat(response.reserved()).isZero();
            assertThat(response.total()).isEqualTo(12);
        }

        @Test
        void getUnknownProduct() {
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.get(404L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a batch read of no ids issues no query at all")
        void emptyBatchIssuesNoQuery() {
            assertThat(inventoryService.loadByProductIds(java.util.List.of())).isEmpty();
            verify(inventoryRepository, never()).findByProductIdIn(org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("a batch read is one query, not one per product")
        void batchReadIsOneQuery() {
            var ids = java.util.List.of(1L, 2L, 3L);
            when(inventoryRepository.findByProductIdIn(ids))
                    .thenReturn(java.util.List.of(new Inventory(1L, 5)));

            inventoryService.loadByProductIds(ids);

            verify(inventoryRepository, times(1)).findByProductIdIn(ids);
            verify(inventoryRepository, never()).findById(anyLong());
        }
    }
}
