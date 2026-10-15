package com.bhavesh.ecommerce.order.service;

import com.bhavesh.ecommerce.cart.entity.Cart;
import com.bhavesh.ecommerce.cart.repository.CartRepository;
import com.bhavesh.ecommerce.cart.service.CartService;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.common.util.ReferenceGenerator;
import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.InsufficientStockException;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.service.InventoryService;
import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Checkout and the order lifecycle. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartService cartService;

    @Mock
    private ProductService productService;

    @Mock
    private InventoryService inventoryService;

    private OrderService orderService;

    private Cart cart;
    private Product keyboard;
    private Product laptop;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, cartRepository, cartService,
                productService, inventoryService, new ReferenceGenerator());

        Category electronics = new Category("Electronics", null);
        ReflectionTestUtils.setField(electronics, "id", 1L);

        keyboard = product(1L, "ELEC-KBD-001", "TactileOne Keyboard", "6499.00", electronics);
        laptop = product(5L, "ELEC-LAPTOP-001", "UltraBook 14 Pro", "89999.00", electronics);

        cart = new Cart(7L);
        ReflectionTestUtils.setField(cart, "id", 3L);

        when(orderRepository.existsByOrderNumber(anyString())).thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartService.requireCart(7L)).thenReturn(cart);
    }

    private Product product(Long id, String sku, String name, String price, Category category) {
        Product p = new Product(sku, name, "A product", new BigDecimal(price), category);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Nested
    @DisplayName("checkout")
    class Checkout {

        @Test
        @DisplayName("an empty cart is 422, not an empty order")
        void emptyCart() {
            assertThatThrownBy(() -> orderService.checkout(7L))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMPTY_CART);

            verify(inventoryService, never()).reserve(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("creates an order with a price snapshot taken from the catalogue")
        void snapshotsPrices() {
            cart.addItem(1L, 2);
            when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard));

            var response = orderService.checkout(7L);

            assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
            assertThat(response.orderNumber()).startsWith("ORD-");
            assertThat(response.total()).isEqualByComparingTo("12998.00");
            assertThat(response.items()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.sku()).isEqualTo("ELEC-KBD-001");
                        assertThat(item.name()).isEqualTo("TactileOne Keyboard");
                        assertThat(item.unitPrice()).isEqualByComparingTo("6499.00");
                        assertThat(item.quantity()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("reserves stock for every line")
        void reservesEveryLine() {
            cart.addItem(1L, 2);
            cart.addItem(5L, 1);
            when(productService.loadByIds(anySet()))
                    .thenReturn(Map.of(1L, keyboard, 5L, laptop));

            orderService.checkout(7L);

            verify(inventoryService).reserve(1L, "TactileOne Keyboard", 2);
            verify(inventoryService).reserve(5L, "UltraBook 14 Pro", 1);
        }

        /**
         * Two customers buying {A, B} and {B, A} would take the row locks in
         * opposite orders and deadlock. PostgreSQL would detect it and kill one
         * transaction — turning a perfectly valid pair of orders into a 500. Sorting
         * makes the cycle impossible rather than merely recoverable.
         */
        @Test
        @DisplayName("reserves in ascending product id, whatever order the cart holds")
        void deterministicLockOrder() {
            cart.addItem(5L, 1);   // added first, but has the higher id
            cart.addItem(1L, 2);
            when(productService.loadByIds(anySet()))
                    .thenReturn(Map.of(1L, keyboard, 5L, laptop));

            orderService.checkout(7L);

            InOrder order = inOrder(inventoryService);
            order.verify(inventoryService).reserve(eq(1L), anyString(), anyInt());
            order.verify(inventoryService).reserve(eq(5L), anyString(), anyInt());
        }

        /**
         * No compensating logic to write and none to get wrong: the exception rolls
         * the transaction back, and the database undoes every reservation taken
         * before it. That is the main reason reservation lives inside the checkout
         * transaction rather than in a separate call.
         */
        @Test
        @DisplayName("insufficient stock propagates, so the transaction rolls back every reservation")
        void insufficientStockAborts() {
            cart.addItem(1L, 2);
            cart.addItem(5L, 99);
            when(productService.loadByIds(anySet()))
                    .thenReturn(Map.of(1L, keyboard, 5L, laptop));
            doThrow(new InsufficientStockException(5L, "UltraBook 14 Pro", 99, 3))
                    .when(inventoryService).reserve(eq(5L), anyString(), eq(99));

            assertThatThrownBy(() -> orderService.checkout(7L))
                    .isInstanceOf(InsufficientStockException.class);

            // The cart is deliberately NOT cleared: the customer must still have it.
            verify(cartRepository, never()).save(any(Cart.class));
        }

        @Test
        @DisplayName("validates every line before locking anything")
        void validatesBeforeLocking() {
            keyboard.setActive(false);
            cart.addItem(1L, 2);
            cart.addItem(5L, 1);
            when(productService.loadByIds(anySet()))
                    .thenReturn(Map.of(1L, keyboard, 5L, laptop));

            assertThatThrownBy(() -> orderService.checkout(7L))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_UNAVAILABLE);

            verify(inventoryService, never()).reserve(anyLong(), anyString(), anyInt());
        }

        @Test
        @DisplayName("a product in a deactivated category is also refused")
        void deactivatedCategoryBlocksCheckout() {
            keyboard.getCategory().setActive(false);
            cart.addItem(1L, 1);
            when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard));

            assertThatThrownBy(() -> orderService.checkout(7L))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_UNAVAILABLE);
        }

        @Test
        @DisplayName("a product deleted from the catalogue under the cart is refused")
        void vanishedProduct() {
            cart.addItem(99L, 1);
            when(productService.loadByIds(anySet())).thenReturn(Map.of());

            assertThatThrownBy(() -> orderService.checkout(7L))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_UNAVAILABLE);
        }

        @Test
        @DisplayName("the cart is emptied on success")
        void clearsTheCart() {
            cart.addItem(1L, 2);
            when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard));

            orderService.checkout(7L);

            ArgumentCaptor<Cart> saved = ArgumentCaptor.forClass(Cart.class);
            verify(cartRepository).save(saved.capture());
            assertThat(saved.getValue().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an order-number collision is retried rather than surfacing")
        void retriesOnNumberCollision() {
            cart.addItem(1L, 1);
            when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard));
            when(orderRepository.existsByOrderNumber(anyString()))
                    .thenReturn(true)     // first candidate is taken
                    .thenReturn(false);

            assertThat(orderService.checkout(7L).orderNumber()).startsWith("ORD-");
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        private Order placedOrder() {
            Order order = new Order("ORD-20261014-ABC123", 7L, "INR");
            ReflectionTestUtils.setField(order, "id", 42L);
            order.addItem(1L, "ELEC-KBD-001", "TactileOne Keyboard", new BigDecimal("6499.00"), 2);
            return order;
        }

        @Test
        @DisplayName("releases the reserved stock")
        void releasesStock() {
            Order order = placedOrder();
            when(orderRepository.findByIdAndUserIdWithItems(42L, 7L)).thenReturn(Optional.of(order));

            var response = orderService.cancel(42L, 7L);

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(inventoryService).releaseAll(Map.of(1L, 2));
        }

        @Test
        @DisplayName("a paid order is also marked refunded")
        void paidOrderIsRefunded() {
            Order order = placedOrder();
            order.confirm();
            when(orderRepository.findByIdAndUserIdWithItems(42L, 7L)).thenReturn(Optional.of(order));

            var response = orderService.cancel(42L, 7L);

            assertThat(response.paymentStatus())
                    .isEqualTo(com.bhavesh.ecommerce.payment.entity.PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("a shipped order no longer holds stock, so nothing is released")
        void shippedOrderReleasesNothing() {
            Order order = placedOrder();
            order.confirm();
            order.advanceTo(OrderStatus.PROCESSING);
            order.advanceTo(OrderStatus.SHIPPED);
            when(orderRepository.findByIdAndUserIdWithItems(42L, 7L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.cancel(42L, 7L))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_CANCELLABLE);

            verify(inventoryService, never()).releaseAll(any());
        }

        @Test
        @DisplayName("another customer's order is 404, not 403")
        void otherCustomersOrderIsNotFound() {
            when(orderRepository.findByIdAndUserIdWithItems(42L, 999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.cancel(42L, 999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("administrative status changes")
    class StatusChanges {

        private Order confirmedOrder() {
            Order order = new Order("ORD-20261014-ABC123", 7L, "INR");
            ReflectionTestUtils.setField(order, "id", 42L);
            order.addItem(1L, "ELEC-KBD-001", "TactileOne Keyboard", new BigDecimal("6499.00"), 2);
            order.confirm();
            when(orderRepository.findByIdWithItems(42L)).thenReturn(Optional.of(order));
            return order;
        }

        @Test
        @DisplayName("shipping consumes the reservation rather than returning it")
        void shippingCommitsTheReservation() {
            Order order = confirmedOrder();
            orderService.updateStatus(42L, OrderStatus.PROCESSING);
            orderService.updateStatus(42L, OrderStatus.SHIPPED);

            verify(inventoryService).commitAll(Map.of(1L, 2));
            verify(inventoryService, never()).releaseAll(any());
            assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("confirming an unpaid order is refused")
        void cannotConfirmUnpaidOrder() {
            Order order = new Order("ORD-X", 7L, "INR");
            ReflectionTestUtils.setField(order, "id", 42L);
            order.addItem(1L, "SKU", "Thing", new BigDecimal("10.00"), 1);
            when(orderRepository.findByIdWithItems(42L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.updateStatus(42L, OrderStatus.CONFIRMED))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("payment succeeds");
        }

        @Test
        @DisplayName("an illegal jump is refused and the order is untouched")
        void illegalJump() {
            Order order = confirmedOrder();

            assertThatThrownBy(() -> orderService.updateStatus(42L, OrderStatus.DELIVERED))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATUS_TRANSITION);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("moving to CANCELLED routes through cancel, so stock is released")
        void cancelViaStatusUpdate() {
            confirmedOrder();

            orderService.updateStatus(42L, OrderStatus.CANCELLED);

            verify(inventoryService).releaseAll(Map.of(1L, 2));
        }
    }

    @Nested
    @DisplayName("quantitiesByProduct")
    class Quantities {

        @Test
        @DisplayName("is ordered by product id, so release takes locks in the same order as reserve")
        void ascendingProductId() {
            Order order = new Order("ORD-X", 7L, "INR");
            order.addItem(9L, "SKU-9", "Nine", new BigDecimal("1.00"), 1);
            order.addItem(2L, "SKU-2", "Two", new BigDecimal("1.00"), 1);
            order.addItem(5L, "SKU-5", "Five", new BigDecimal("1.00"), 1);

            assertThat(orderService.quantitiesByProduct(order).keySet())
                    .containsExactly(2L, 5L, 9L);
        }

        @Test
        @DisplayName("skips lines whose product has been deleted — there is nothing to release")
        void skipsDeletedProducts() {
            Order order = new Order("ORD-X", 7L, "INR");
            order.addItem(null, "SKU-GONE", "Deleted", new BigDecimal("1.00"), 1);
            order.addItem(2L, "SKU-2", "Two", new BigDecimal("1.00"), 3);

            assertThat(orderService.quantitiesByProduct(order)).containsExactly(Map.entry(2L, 3));
        }

        /**
         * An order should not contain the same product twice, but summing is the
         * behaviour that stays correct if it ever does — {@code Collectors.toMap}
         * would throw instead, turning a data oddity into a failed cancellation.
         */
        @Test
        @DisplayName("sums duplicate lines rather than throwing")
        void sumsDuplicates() {
            Order order = new Order("ORD-X", 7L, "INR");
            order.addItem(2L, "SKU-2", "Two", new BigDecimal("1.00"), 3);
            order.addItem(2L, "SKU-2", "Two", new BigDecimal("1.00"), 4);

            assertThat(orderService.quantitiesByProduct(order)).containsExactly(Map.entry(2L, 7));
        }
    }

    @Nested
    @DisplayName("ownership scoping")
    class Ownership {

        @Test
        @DisplayName("get is scoped by user id in the query, not checked afterwards")
        void getIsScoped() {
            when(orderRepository.findByIdAndUserIdWithItems(42L, 7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.get(42L, 7L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(orderRepository).findByIdAndUserIdWithItems(42L, 7L);
            verify(orderRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("the administrative read is deliberately unscoped")
        void adminReadIsUnscoped() {
            Order order = new Order("ORD-X", 7L, "INR");
            ReflectionTestUtils.setField(order, "id", 42L);
            when(orderRepository.findByIdWithItems(42L)).thenReturn(Optional.of(order));

            assertThat(orderService.getAsAdmin(42L).id()).isEqualTo(42L);
        }
    }

    @Test
    @DisplayName("the history listing omits order lines, keeping it to one query")
    void listingOmitsItems() {
        Order order = new Order("ORD-X", 7L, "INR");
        ReflectionTestUtils.setField(order, "id", 42L);
        when(orderRepository.findByUserIdOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(order)));

        var page = orderService.listForUser(7L, null,
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.content()).singleElement()
                .satisfies(o -> assertThat(o.items()).isNull());
    }
}
