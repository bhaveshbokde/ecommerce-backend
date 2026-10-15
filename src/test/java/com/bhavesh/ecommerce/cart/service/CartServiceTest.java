package com.bhavesh.ecommerce.cart.service;

import com.bhavesh.ecommerce.cart.dto.AddCartItemRequest;
import com.bhavesh.ecommerce.cart.entity.Cart;
import com.bhavesh.ecommerce.cart.entity.CartItem;
import com.bhavesh.ecommerce.cart.repository.CartRepository;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.entity.Inventory;
import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cart management and read-time pricing. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductService productService;

    @Mock
    private InventoryRepository inventoryRepository;

    private CartService cartService;

    private Cart cart;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartRepository, productService, inventoryRepository);

        Category electronics = new Category("Electronics", null);
        ReflectionTestUtils.setField(electronics, "id", 1L);

        keyboard = new Product("ELEC-KBD-001", "TactileOne Keyboard", "Mechanical",
                new BigDecimal("6499.00"), electronics);
        ReflectionTestUtils.setField(keyboard, "id", 1L);

        cart = new Cart(7L);
        ReflectionTestUtils.setField(cart, "id", 3L);

        when(cartRepository.findByUserIdWithItems(7L)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productService.requireProduct(1L)).thenReturn(keyboard);
        when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard));
        when(inventoryRepository.findByProductIdIn(anyCollection()))
                .thenReturn(List.of(new Inventory(1L, 10)));
    }

    @Nested
    @DisplayName("reading an empty cart")
    class Empty {

        @Test
        @DisplayName("a customer with no cart row gets an empty cart, not a 404")
        void noCartRow() {
            when(cartRepository.findByUserIdWithItems(99L)).thenReturn(Optional.empty());

            var response = cartService.getCart(99L);

            assertThat(response.items()).isEmpty();
            assertThat(response.subtotal()).isEqualByComparingTo("0.00");
            assertThat(response.checkoutBlocked()).isFalse();
        }
    }

    @Nested
    @DisplayName("adding items")
    class Adding {

        @Test
        @DisplayName("prices the line from the current catalogue")
        void pricesFromCatalogue() {
            var response = cartService.addItem(7L, new AddCartItemRequest(1L, 2));

            assertThat(response.items()).singleElement().satisfies(item -> {
                assertThat(item.unitPrice()).isEqualByComparingTo("6499.00");
                assertThat(item.lineTotal()).isEqualByComparingTo("12998.00");
                assertThat(item.available()).isEqualTo(10);
                assertThat(item.purchasable()).isTrue();
            });
            assertThat(response.subtotal()).isEqualByComparingTo("12998.00");
        }

        @Test
        @DisplayName("adding the same product merges into one line")
        void mergesDuplicateProduct() {
            cartService.addItem(7L, new AddCartItemRequest(1L, 2));
            var response = cartService.addItem(7L, new AddCartItemRequest(1L, 3));

            assertThat(response.items())
                    .as("uq_cart_items_cart_product would reject a second line")
                    .hasSize(1);
            assertThat(response.totalQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("an inactive product is refused up front")
        void inactiveProductRefused() {
            keyboard.setActive(false);

            assertThatThrownBy(() -> cartService.addItem(7L, new AddCartItemRequest(1L, 1)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_UNAVAILABLE);
        }

        @Test
        @DisplayName("a product in an inactive category is refused")
        void inactiveCategoryRefused() {
            keyboard.getCategory().setActive(false);

            assertThatThrownBy(() -> cartService.addItem(7L, new AddCartItemRequest(1L, 1)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_UNAVAILABLE);
        }

        /**
         * Reserving at cart time would let a handful of abandoned carts make a
         * product unbuyable. The cost is that the customer may be told at checkout;
         * the advisory {@code available} field exists to warn them earlier.
         */
        @Test
        @DisplayName("adding to the cart does not reserve stock")
        void doesNotReserveStock() {
            cartService.addItem(7L, new AddCartItemRequest(1L, 2));

            verify(inventoryRepository, times(0)).reserve(1L, 2);
        }

        @Test
        @DisplayName("a cart holding more than is available is flagged, not rejected")
        void overStockIsFlaggedNotRejected() {
            when(inventoryRepository.findByProductIdIn(anyCollection()))
                    .thenReturn(List.of(new Inventory(1L, 1)));

            var response = cartService.addItem(7L, new AddCartItemRequest(1L, 5));

            assertThat(response.checkoutBlocked()).isTrue();
            assertThat(response.items()).singleElement()
                    .satisfies(i -> assertThat(i.available()).isEqualTo(1));
        }
    }

    @Nested
    @DisplayName("updating and removing")
    class Mutating {

        @BeforeEach
        void seedCart() {
            cart.addItem(1L, 2);
        }

        @Test
        @DisplayName("the quantity is set absolutely, so a retry is safe")
        void absoluteQuantity() {
            var response = cartService.updateItem(7L, 1L, 5);

            assertThat(response.totalQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("setting a line to zero removes it")
        void zeroRemovesTheLine() {
            var response = cartService.updateItem(7L, 1L, 0);

            assertThat(response.items()).isEmpty();
        }

        @Test
        @DisplayName("updating a product that is not in the cart is 404")
        void updateMissingLine() {
            assertThatThrownBy(() -> cartService.updateItem(7L, 999L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void removesALine() {
            assertThat(cartService.removeItem(7L, 1L).items()).isEmpty();
        }

        @Test
        @DisplayName("removing a product that is not in the cart is 404")
        void removeMissingLine() {
            assertThatThrownBy(() -> cartService.removeItem(7L, 999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("clearing an already-empty cart succeeds")
        void clearIsIdempotent() {
            cartService.clear(7L);
            assertThat(cartService.clear(7L).items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getOrCreateCart")
    class GetOrCreate {

        @Test
        @DisplayName("returns the existing cart without creating a second one")
        void returnsExisting() {
            assertThat(cartService.getOrCreateCart(7L)).isSameAs(cart);
            verify(cartRepository, times(0)).saveAndFlush(any(Cart.class));
        }

        @Test
        void createsWhenAbsent() {
            when(cartRepository.findByUserIdWithItems(99L)).thenReturn(Optional.empty());
            when(cartRepository.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(cartService.getOrCreateCart(99L).getUserId()).isEqualTo(99L);
        }

        /**
         * Two simultaneous "add to cart" requests can both find no cart and both
         * insert. {@code uq_carts_user} rejects the loser, which then re-reads and
         * finds the winner's cart — so both succeed and there is still exactly one.
         */
        @Test
        @DisplayName("losing the uq_carts_user race falls back to the winner's cart")
        void concurrentCreateFallsBack() {
            Cart winner = new Cart(99L);
            ReflectionTestUtils.setField(winner, "id", 4L);

            when(cartRepository.findByUserIdWithItems(99L))
                    .thenReturn(Optional.empty())     // our first read
                    .thenReturn(Optional.of(winner)); // after the violation
            when(cartRepository.saveAndFlush(any(Cart.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_carts_user"));

            assertThat(cartService.getOrCreateCart(99L)).isSameAs(winner);
        }
    }

    @Nested
    @DisplayName("batched reads")
    class Batching {

        /**
         * One query for the products and one for the stock, whatever the cart holds.
         * Doing it per line would be the N+1 problem: ten lines, twenty queries.
         */
        @Test
        @DisplayName("a multi-line cart still costs two queries, not two per line")
        void twoQueriesRegardlessOfSize() {
            Product laptop = new Product("ELEC-LAPTOP-001", "UltraBook", "A laptop",
                    new BigDecimal("89999.00"), keyboard.getCategory());
            ReflectionTestUtils.setField(laptop, "id", 5L);

            cart.addItem(1L, 1);
            cart.addItem(5L, 1);
            when(productService.loadByIds(anySet())).thenReturn(Map.of(1L, keyboard, 5L, laptop));
            when(inventoryRepository.findByProductIdIn(anyCollection()))
                    .thenReturn(List.of(new Inventory(1L, 10), new Inventory(5L, 3)));

            var response = cartService.getCart(7L);

            assertThat(response.items()).hasSize(2);
            verify(productService, times(1)).loadByIds(anySet());
            verify(inventoryRepository, times(1)).findByProductIdIn(anyCollection());
        }

        @Test
        @DisplayName("a hard-deleted product flags its line instead of failing the whole read")
        void vanishedProductFlagsTheLine() {
            cart.addItem(1L, 1);
            when(productService.loadByIds(anySet())).thenReturn(Map.of());

            var response = cartService.getCart(7L);

            assertThat(response.checkoutBlocked()).isTrue();
            assertThat(response.items()).singleElement().satisfies(item -> {
                assertThat(item.purchasable()).isFalse();
                assertThat(item.sku()).isEqualTo("(removed)");
            });
        }
    }

    @Nested
    @DisplayName("the Cart aggregate itself")
    class Aggregate {

        @Test
        @DisplayName("the item list is a copy, so lines cannot be added behind the invariants")
        void itemListIsACopy() {
            cart.addItem(1L, 1);
            List<CartItem> items = cart.getItems();

            assertThatThrownBy(() -> items.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(cart.getItems()).hasSize(1);
        }

        @Test
        @DisplayName("adding beyond the cap clamps rather than failing the request")
        void clampsAtTheMaximum() {
            cart.addItem(1L, CartItem.MAX_QUANTITY);
            cart.addItem(1L, 50);

            assertThat(cart.totalQuantity()).isEqualTo(CartItem.MAX_QUANTITY);
        }

        @Test
        void clearEmptiesEverything() {
            cart.addItem(1L, 1);
            cart.addItem(5L, 1);
            cart.clear();

            assertThat(cart.isEmpty()).isTrue();
            assertThat(cart.totalQuantity()).isZero();
        }
    }
}
