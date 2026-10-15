package com.bhavesh.ecommerce.catalog;

import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalogue browsing, management and stock administration. */
class CatalogIT extends AbstractIntegrationTest {

    private String admin;
    private Long electronicsId;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken("admin@example.com");
        electronicsId = categoryIdOf(productIdBySku("ELEC-KBD-001"));
    }

    @Nested
    @DisplayName("browsing")
    class Browsing {

        @Test
        @DisplayName("lists only purchasable products, paginated")
        void listsPurchasableProducts() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(18))
                    .andExpect(jsonPath("$.content.length()").value(5))
                    .andExpect(jsonPath("$.totalPages").value(4))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(false));
        }

        @Test
        @DisplayName("filters by category")
        void filtersByCategory() throws Exception {
            mockMvc.perform(get("/api/v1/products")
                            .param("categoryId", electronicsId.toString())
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.content[0].categoryName").value("Electronics"));
        }

        @Test
        @DisplayName("filters by price range, inclusive at both ends")
        void filtersByPrice() throws Exception {
            String body = mockMvc.perform(get("/api/v1/products")
                            .param("minPrice", "1000")
                            .param("maxPrice", "5000")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            json(body).get("content").forEach(product -> {
                double price = product.get("price").asDouble();
                assertThat(price).isBetween(1000.0, 5000.0);
            });
        }

        @Test
        @DisplayName("an inverted price range is 400, not a silently empty page")
        void invertedRangeIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/products")
                            .param("minPrice", "5000").param("maxPrice", "1000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

        @Test
        @DisplayName("search matches a substring of the name, case-insensitively")
        void searchesByName() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("search", "ULTRABOOK"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].sku").value("ELEC-LAPTOP-001"));
        }

        /**
         * The bug this guards: {@code (:search IS NULL OR lower(p.name) LIKE
         * lower(concat('%', :search, '%')))} fails against PostgreSQL with
         * {@code function lower(bytea) does not exist}, because a null bind
         * parameter inside a function has no column to take its type from. The
         * pattern is built in Java instead, so a blank search is {@code "%"} rather
         * than a null the server has to guess about.
         */
        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank search returns the whole catalogue rather than failing")
        void blankSearchReturnsEverything(String search) throws Exception {
            mockMvc.perform(get("/api/v1/products").param("search", search).param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(18));
        }

        @Test
        @DisplayName("an omitted search parameter also returns the whole catalogue")
        void absentSearchReturnsEverything() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(18));
        }

        /**
         * Without escaping, a search for {@code "50%"} would mean "anything starting
         * with 50".
         */
        @Test
        @DisplayName("a percent sign in the search is a literal, not a wildcard")
        void wildcardsAreEscaped() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("search", "%"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("sorting by price works and is applied by the database")
        void sortsByPrice() throws Exception {
            String body = mockMvc.perform(get("/api/v1/products")
                            .param("sort", "price").param("direction", "ASC").param("size", "50"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var prices = new java.util.ArrayList<Double>();
            json(body).get("content").forEach(p -> prices.add(p.get("price").asDouble()));
            assertThat(prices).isSorted();
        }

        @Test
        @DisplayName("a deactivated product disappears from the public catalogue")
        void deactivatedProductIsHidden() throws Exception {
            Long laptopId = productIdBySku("ELEC-LAPTOP-001");

            mockMvc.perform(patch("/api/v1/products/" + laptopId + "/active?active=false")
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/products").param("size", "50"))
                    .andExpect(jsonPath("$.totalElements").value(17));
        }

        /**
         * Otherwise "retire this category" would silently leave its items
         * purchasable through a direct link.
         */
        @Test
        @DisplayName("deactivating a category hides its products too")
        void deactivatedCategoryHidesItsProducts() throws Exception {
            mockMvc.perform(patch("/api/v1/categories/" + electronicsId + "/active?active=false")
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/products").param("size", "50"))
                    .andExpect(jsonPath("$.totalElements").value(13));   // 18 minus 5 electronics

            mockMvc.perform(get("/api/v1/categories"))
                    .andExpect(jsonPath("$.length()").value(4));
        }

        @Test
        @DisplayName("an unknown product is 404 in the standard shape")
        void unknownProduct() throws Exception {
            mockMvc.perform(get("/api/v1/products/99999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("management")
    class Management {

        @Test
        @DisplayName("creating a product also creates its inventory row")
        void createAlsoCreatesInventory() throws Exception {
            String body = mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"TEST-NEW-001","name":"Test Widget","description":"A widget",
                                     "price":1234.56,"categoryId":%d,"initialStock":42}"""
                                    .formatted(electronicsId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sku").value("TEST-NEW-001"))
                    .andExpect(jsonPath("$.price").value(1234.56))
                    .andExpect(jsonPath("$.inStock").value(true))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/v1/admin/inventory/" + json(body).get("id").asLong())
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(42))
                    .andExpect(jsonPath("$.reserved").value(0));
        }

        @Test
        @DisplayName("a product with no initial stock still gets an inventory row at zero")
        void zeroStockStillGetsARow() throws Exception {
            String body = mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"TEST-NOSTOCK-001","name":"Backorder Item","price":10.00,
                                     "categoryId":%d}""".formatted(electronicsId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.inStock").value(false))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/v1/admin/inventory/" + json(body).get("id").asLong())
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(jsonPath("$.available").value(0));
        }

        @Test
        @DisplayName("the SKU is uppercased before storage")
        void skuIsNormalised() throws Exception {
            mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"test-lower-001","name":"Lowercase SKU","price":10.00,
                                     "categoryId":%d,"initialStock":1}""".formatted(electronicsId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sku").value("TEST-LOWER-001"));
        }

        @Test
        @DisplayName("a duplicate SKU is 409, case-insensitively")
        void duplicateSku() throws Exception {
            mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"elec-kbd-001","name":"Clash","price":10.00,
                                     "categoryId":%d,"initialStock":1}""".formatted(electronicsId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        }

        @Test
        @DisplayName("the SKU cannot be changed — order lines reference it")
        void skuIsImmutable() throws Exception {
            Long id = productIdBySku("ELEC-KBD-001");

            mockMvc.perform(put("/api/v1/products/" + id)
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"ELEC-KBD-999","name":"Renamed","price":10.00,
                                     "categoryId":%d}""".formatted(electronicsId)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("sku cannot be changed")));
        }

        @Test
        @DisplayName("price validation rejects zero, negative and over-precise values")
        void priceValidation() throws Exception {
            for (String price : new String[]{"0", "-1.00", "10.123456"}) {
                mockMvc.perform(post("/api/v1/products")
                                .header(HttpHeaders.AUTHORIZATION, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sku":"TEST-BAD-001","name":"Bad Price","price":%s,
                                         "categoryId":%d,"initialStock":1}"""
                                        .formatted(price, electronicsId)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
        }

        @Test
        @DisplayName("a category slug is derived from its name")
        void categorySlugIsDerived() throws Exception {
            mockMvc.perform(post("/api/v1/categories")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"TEST-Home & Garden","description":"Outdoor"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.slug").value("test-home-garden"));
        }

        @Test
        @DisplayName("a category still holding products cannot be deleted")
        void cannotDeleteCategoryInUse() throws Exception {
            mockMvc.perform(delete("/api/v1/categories/" + electronicsId)
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("deactivate it instead")));
        }

        @Test
        @DisplayName("an empty category can be deleted")
        void emptyCategoryIsDeletable() throws Exception {
            String created = mockMvc.perform(post("/api/v1/categories")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"TEST-Ephemeral"}"""))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(delete("/api/v1/categories/" + json(created).get("id").asLong())
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("the admin listing includes deactivated rows that the public one hides")
        void adminListingIncludesInactive() throws Exception {
            Long laptopId = productIdBySku("ELEC-LAPTOP-001");
            mockMvc.perform(patch("/api/v1/products/" + laptopId + "/active?active=false")
                    .header(HttpHeaders.AUTHORIZATION, admin));

            mockMvc.perform(get("/api/v1/products").param("size", "50"))
                    .andExpect(jsonPath("$.totalElements").value(17));

            mockMvc.perform(get("/api/v1/admin/products").param("size", "50")
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(18));
        }
    }

    @Nested
    @DisplayName("stock administration")
    class Stock {

        private Long laptopId;

        @BeforeEach
        void findTheLaptop() {
            laptopId = productIdBySku("ELEC-LAPTOP-001");
        }

        @Test
        @DisplayName("a positive adjustment is additive")
        void positiveAdjustment() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/" + laptopId + "/adjust")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": 20, "reason": "Supplier delivery #4471"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(25));   // 5 seeded + 20

            assertThat(availableStock("ELEC-LAPTOP-001")).isEqualTo(25);
        }

        @Test
        @DisplayName("a negative adjustment decrements")
        void negativeAdjustment() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/" + laptopId + "/adjust")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": -2, "reason": "Damaged in transit"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(3))
                    .andExpect(jsonPath("$.reserved").value(0));
        }

        /**
         * Silently losing a correction is worse than rejecting it, so the guarded
         * decrement refuses rather than clamping to zero.
         */
        @Test
        @DisplayName("a negative adjustment larger than stock is refused, not clamped")
        void refusesRatherThanClamping() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/" + laptopId + "/adjust")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": -100, "reason": "Shrinkage"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.available").value(5));

            assertThat(availableStock("ELEC-LAPTOP-001"))
                    .as("a refused adjustment must change nothing")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("a zero adjustment is rejected as meaningless")
        void zeroIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/" + laptopId + "/adjust")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": 0}"""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an implausibly large adjustment is rejected by validation")
        void absurdQuantityIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/admin/inventory/" + laptopId + "/adjust")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": 999999999}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }
}
