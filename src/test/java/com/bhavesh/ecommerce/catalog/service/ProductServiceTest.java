package com.bhavesh.ecommerce.catalog.service;

import com.bhavesh.ecommerce.catalog.dto.ProductRequest;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.repository.ProductRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Catalogue reads and writes. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private CategoryService categoryService;

    private ProductService productService;

    private Category electronics;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, inventoryRepository, categoryService);

        electronics = new Category("Electronics", "Phones and laptops");
        ReflectionTestUtils.setField(electronics, "id", 1L);

        keyboard = new Product("ELEC-KBD-001", "TactileOne Keyboard", "Mechanical",
                new BigDecimal("6499.00"), electronics);
        ReflectionTestUtils.setField(keyboard, "id", 1L);

        when(categoryService.requireCategory(1L)).thenReturn(electronics);
        when(productRepository.findByIdWithCategory(1L)).thenReturn(Optional.of(keyboard));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 1L);
            return p;
        });
    }

    @Nested
    @DisplayName("search")
    class Search {

        /**
         * The null-parameter-inside-{@code lower()} bug: PostgreSQL resolves an
         * untyped null inside a function to {@code bytea}, and {@code lower(bytea)}
         * does not exist. Building the pattern in Java means the parameter sits
         * beside {@code LIKE}, where the column gives it a type — and a blank search
         * becomes {@code "%"} rather than a null the server has to guess about.
         */
        @Test
        @DisplayName("a blank search becomes the match-all pattern, never a null parameter")
        void blankSearchBecomesMatchAll() {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            productService.search(null, null, null, "   ", PageRequest.of(0, 20));

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(productRepository).search(isNull(), isNull(), isNull(),
                    pattern.capture(), any());
            assertThat(pattern.getValue()).isEqualTo("%");
        }

        @Test
        @DisplayName("a null search also becomes the match-all pattern")
        void nullSearchBecomesMatchAll() {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            productService.search(null, null, null, null, PageRequest.of(0, 20));

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(productRepository).search(isNull(), isNull(), isNull(), pattern.capture(), any());
            assertThat(pattern.getValue()).isEqualTo("%");
        }

        @Test
        @DisplayName("a search term is lower-cased and wrapped for a substring match")
        void buildsSubstringPattern() {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            productService.search(null, null, null, "  UltraBook  ", PageRequest.of(0, 20));

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(productRepository).search(isNull(), isNull(), isNull(), pattern.capture(), any());
            assertThat(pattern.getValue()).isEqualTo("%ultrabook%");
        }

        /**
         * Without escaping, a search for {@code "50%"} would mean "anything starting
         * with 50" and a search for {@code "_"} would match every single character.
         */
        @ParameterizedTest(name = "{0} is escaped")
        @ValueSource(strings = {"50%", "a_b", "back\\slash"})
        @DisplayName("LIKE wildcards in user input are escaped to literals")
        void escapesWildcards(String input) {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            productService.search(null, null, null, input, PageRequest.of(0, 20));

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(productRepository).search(isNull(), isNull(), isNull(), pattern.capture(), any());

            String body = pattern.getValue();
            assertThat(body).startsWith("%").endsWith("%");
            // Every wildcard between the surrounding % markers must be preceded by a
            // backslash.
            String inner = body.substring(1, body.length() - 1);
            assertThat(inner.replace("\\%", "").replace("\\_", "").replace("\\\\", ""))
                    .doesNotContain("%").doesNotContain("_");
        }

        @Test
        @DisplayName("an inverted price range is 400, not an empty page")
        void invertedPriceRange() {
            assertThatThrownBy(() -> productService.search(null,
                    new BigDecimal("500"), new BigDecimal("100"), null, PageRequest.of(0, 20)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        }

        /**
         * Stock for the whole page comes from one query, not one per row. Twenty
         * products: two queries, not forty-one.
         */
        @Test
        @DisplayName("stock for a page is resolved in a single batched query")
        void batchesStockLookups() {
            Product laptop = new Product("ELEC-LAPTOP-001", "UltraBook", "A laptop",
                    new BigDecimal("89999.00"), electronics);
            ReflectionTestUtils.setField(laptop, "id", 5L);

            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of(keyboard, laptop)));
            when(inventoryRepository.findByProductIdIn(anyCollection()))
                    .thenReturn(List.of(new Inventory(1L, 10), new Inventory(5L, 0)));

            var page = productService.search(null, null, null, null, PageRequest.of(0, 20));

            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).inStock()).isTrue();
            assertThat(page.content().get(1).inStock()).isFalse();

            verify(inventoryRepository, times(1)).findByProductIdIn(anyCollection());
            verify(inventoryRepository, times(0)).findById(eq(1L));
        }

        @Test
        @DisplayName("an empty page issues no stock query at all")
        void emptyPageIssuesNoStockQuery() {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            productService.search(null, null, null, null, PageRequest.of(0, 20));

            verify(inventoryRepository, times(0)).findByProductIdIn(anyCollection());
        }

        @Test
        @DisplayName("a product with no inventory row reads as out of stock, not as an error")
        void missingInventoryRowIsOutOfStock() {
            when(productRepository.search(any(), any(), any(), anyString(), any()))
                    .thenReturn(new PageImpl<>(List.of(keyboard)));
            when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of());

            assertThat(productService.search(null, null, null, null, PageRequest.of(0, 20))
                    .content().get(0).inStock()).isFalse();
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("normalises the SKU to uppercase so lookups collide on the unique index")
        void normalisesSku() {
            var response = productService.create(new ProductRequest("elec-kbd-002",
                    "Another Keyboard", "desc", new BigDecimal("1999.00"), 1L, 25));

            assertThat(response.sku()).isEqualTo("ELEC-KBD-002");
        }

        /**
         * A product with no stock row would make the reservation UPDATE match
         * nothing — which reads as "out of stock" but is really a missing row, a far
         * more confusing failure to diagnose.
         */
        @Test
        @DisplayName("always creates an inventory row, even at zero stock")
        void alwaysCreatesInventory() {
            productService.create(new ProductRequest("ELEC-KBD-002", "Another", "desc",
                    new BigDecimal("1999.00"), 1L, null));

            ArgumentCaptor<Inventory> inventory = ArgumentCaptor.forClass(Inventory.class);
            verify(inventoryRepository).save(inventory.capture());
            assertThat(inventory.getValue().getAvailable()).isZero();
        }

        @Test
        void rejectsDuplicateSku() {
            when(productRepository.existsBySku("ELEC-KBD-001")).thenReturn(true);

            assertThatThrownBy(() -> productService.create(new ProductRequest("elec-kbd-001",
                    "Clash", "desc", new BigDecimal("1.00"), 1L, 1)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_SKU);
        }

        @Test
        void rejectsUnknownCategory() {
            when(categoryService.requireCategory(99L))
                    .thenThrow(new ResourceNotFoundException("Category", 99L));

            assertThatThrownBy(() -> productService.create(new ProductRequest("ELEC-NEW-001",
                    "New", "desc", new BigDecimal("1.00"), 99L, 1)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        /**
         * The SKU is the stable business identifier and {@code order_items} snapshots
         * it. Letting it change would make a past receipt refer to a code that now
         * means something else.
         */
        @Test
        @DisplayName("the SKU is immutable")
        void skuIsImmutable() {
            assertThatThrownBy(() -> productService.update(1L, new ProductRequest(
                    "ELEC-KBD-999", "TactileOne Keyboard", "desc",
                    new BigDecimal("6499.00"), 1L, null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("sku cannot be changed");
        }

        @Test
        @DisplayName("a price change affects the catalogue only, never existing order lines")
        void repricing() {
            when(inventoryRepository.findById(1L)).thenReturn(Optional.of(new Inventory(1L, 5)));

            var response = productService.update(1L, new ProductRequest("ELEC-KBD-001",
                    "TactileOne Keyboard", "desc", new BigDecimal("5999.00"), 1L, null));

            assertThat(response.price()).isEqualByComparingTo("5999.00");
            assertThat(keyboard.getPrice()).isEqualByComparingTo("5999.00");
        }

        @Test
        @DisplayName("stock is untouched by a catalogue edit")
        void doesNotTouchStock() {
            when(inventoryRepository.findById(1L)).thenReturn(Optional.of(new Inventory(1L, 5)));

            productService.update(1L, new ProductRequest("ELEC-KBD-001", "Renamed", "desc",
                    new BigDecimal("6499.00"), 1L, 9999));

            verify(inventoryRepository, times(0)).save(any(Inventory.class));
            verify(inventoryRepository, times(0)).addStock(eq(1L), eq(9999));
        }

        @Test
        void unknownProductIs404() {
            when(productRepository.findByIdWithCategory(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.update(404L, new ProductRequest("X-001",
                    "Ghost", "desc", new BigDecimal("1.00"), 1L, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("purchasability")
    class Purchasability {

        @Test
        @DisplayName("deactivating a category hides its products too")
        void inactiveCategoryHidesProducts() {
            assertThat(keyboard.isPurchasable()).isTrue();

            electronics.setActive(false);
            assertThat(keyboard.isPurchasable())
                    .as("retiring a category must not leave its items buyable by direct link")
                    .isFalse();
        }

        @Test
        void inactiveProductIsNotPurchasable() {
            keyboard.setActive(false);
            assertThat(keyboard.isPurchasable()).isFalse();
        }
    }

    @Test
    @DisplayName("an empty id set issues no query")
    void loadByIdsShortCircuits() {
        assertThat(productService.loadByIds(java.util.Set.of())).isEmpty();
        verify(productRepository, times(0)).findAllByIdWithCategory(anyCollection());
    }
}
