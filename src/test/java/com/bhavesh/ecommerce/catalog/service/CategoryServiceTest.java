package com.bhavesh.ecommerce.catalog.service;

import com.bhavesh.ecommerce.catalog.dto.CategoryRequest;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.repository.CategoryRepository;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Category management, including slug derivation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;
    private Category electronics;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
        electronics = new Category("Electronics", "Phones and laptops");
        ReflectionTestUtils.setField(electronics, "id", 1L);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(electronics));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 1L);
            return c;
        });
    }

    @Nested
    @DisplayName("slug derivation")
    class Slugs {

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({
                "Electronics,      electronics",
                "Home & Kitchen,   home-kitchen",
                "Sports  Outdoors, sports-outdoors",
                "  Trimmed  ,      trimmed",
                "A--B,             a-b",
                "2026 Collection,  2026-collection",
        })
        void derivesFromTheName(String name, String expected) {
            assertThat(Category.slugify(name)).isEqualTo(expected);
        }

        /**
         * NFD decomposition splits an accented letter into its base plus a combining
         * mark, so stripping the marks leaves readable ASCII instead of dropping the
         * character entirely.
         */
        @Test
        @DisplayName("accents decompose to their ASCII base rather than vanishing")
        void stripsAccents() {
            assertThat(Category.slugify("Café & Thé")).isEqualTo("cafe-the");
            assertThat(Category.slugify("Jardinería")).isEqualTo("jardineria");
        }

        @ParameterizedTest
        @ValueSource(strings = {"!!!", "   ", "---", "###"})
        @DisplayName("a name with nothing sluggable yields null, so the service can answer 400")
        void unsluggableNamesYieldNull(String name) {
            assertThat(Category.slugify(name)).isNull();
        }

        @Test
        void nullNameYieldsNull() {
            assertThat(Category.slugify(null)).isNull();
        }

        @Test
        @DisplayName("renaming re-derives the slug so the two cannot contradict each other")
        void renameRederives() {
            electronics.rename("Consumer Electronics");
            assertThat(electronics.getSlug()).isEqualTo("consumer-electronics");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        void createsWithADerivedSlug() {
            when(categoryRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);

            var response = categoryService.create(new CategoryRequest("Home & Kitchen", "Cookware"));

            assertThat(response.name()).isEqualTo("Home & Kitchen");
            assertThat(response.slug()).isEqualTo("home-kitchen");
            assertThat(response.active()).isTrue();
        }

        @Test
        @DisplayName("a duplicate name is 409")
        void duplicateName() {
            when(categoryRepository.existsByNameIgnoreCase("ELECTRONICS")).thenReturn(true);

            assertThatThrownBy(() -> categoryService.create(new CategoryRequest("ELECTRONICS", null)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_CATEGORY);
        }

        /**
         * Letting it through would violate {@code ck_categories_slug} as an opaque
         * 500; answering 400 says what to do about it.
         */
        @Test
        @DisplayName("a name that slugifies to nothing is 400, not a constraint violation")
        void unsluggableNameIs400() {
            when(categoryRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);

            assertThatThrownBy(() -> categoryService.create(new CategoryRequest("!!!", null)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)
                    .hasMessageContaining("at least one letter or digit");

            verify(categoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("a clash with a different category is 409")
        void nameClash() {
            when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Books", 1L)).thenReturn(true);

            assertThatThrownBy(() -> categoryService.update(1L, new CategoryRequest("Books", null)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_CATEGORY);
        }

        @Test
        @DisplayName("keeping its own name is not a clash")
        void keepingItsOwnNameIsFine() {
            when(categoryRepository.existsByNameIgnoreCaseAndIdNot(anyString(), anyLong()))
                    .thenReturn(false);

            assertThat(categoryService.update(1L,
                    new CategoryRequest("Electronics", "New description")).description())
                    .isEqualTo("New description");
        }

        @Test
        void unknownCategoryIs404() {
            when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.update(404L, new CategoryRequest("X", null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        /**
         * {@code ON DELETE RESTRICT} would also stop this, but as a constraint
         * violation that surfaces as a 500. Counting first produces a 409 that says
         * what to do instead.
         */
        @Test
        @DisplayName("a category still holding products is 409, naming the count")
        void inUseIs409() {
            when(categoryRepository.countProducts(1L)).thenReturn(7L);

            assertThatThrownBy(() -> categoryService.delete(1L))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CATEGORY_IN_USE)
                    .hasMessageContaining("7 product")
                    .hasMessageContaining("deactivate it instead");

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        void emptyCategoryIsDeleted() {
            when(categoryRepository.countProducts(1L)).thenReturn(0L);

            categoryService.delete(1L);

            verify(categoryRepository).delete(electronics);
        }
    }

    @Test
    @DisplayName("deactivating is preferred over deleting and preserves the row")
    void deactivate() {
        assertThat(categoryService.setActive(1L, false).active()).isFalse();
        assertThat(electronics.isActive()).isFalse();
        verify(categoryRepository, never()).delete(any());
    }
}
