package com.bhavesh.ecommerce.catalog;

import com.bhavesh.ecommerce.catalog.dto.CategoryResponse;
import com.bhavesh.ecommerce.catalog.dto.ProductResponse;
import com.bhavesh.ecommerce.catalog.service.CategoryService;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis caching, with caching actually switched on.
 *
 * <p>Tagged {@code redis} and excluded from {@code ./mvnw test}, because it needs a
 * running Redis. The rest of the suite runs with
 * {@code ecommerce.cache.enabled=false} and a {@code NoOpCacheManager}.
 *
 * <h2>Why the cache HIT path needs its own test</h2>
 * A cache <em>miss</em> returns the value straight from the method, so nothing is
 * deserialised and everything appears to work. Only the second call reads from
 * Redis — and with a serializer that writes plain JSON and no type information, that
 * read produces a {@code LinkedHashMap} which cannot be cast to the record the
 * method declares. Every test that called an endpoint once would pass; the
 * application would fail on the second request in production.
 *
 * <p>So every test here calls twice and asserts the two results are identical.
 */
@Tag("redis")
@TestPropertySource(properties = "ecommerce.cache.enabled=true")
class CatalogCacheIT extends AbstractIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private Long productId;

    @BeforeEach
    void clearCaches() {
        productId = productIdBySku("ELEC-KBD-001");
        cacheManager.getCacheNames()
                .forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("the configured cache manager is the Redis one, not the no-op fallback")
    void redisIsActuallyInUse() {
        assertThat(cacheManager)
                .isInstanceOf(org.springframework.data.redis.cache.RedisCacheManager.class);
        assertThat(redisConnectionFactory.getConnection().ping()).isEqualTo("PONG");
    }

    /**
     * The regression this whole configuration exists for: records are implicitly
     * final, so Jackson's {@code DefaultTyping.NON_FINAL} skips exactly the classes
     * that would have needed the {@code @class} marker. Each cache therefore binds a
     * serializer to a concrete {@code JavaType} taken from the configuration, never
     * from the payload.
     */
    @Test
    @DisplayName("a cached product record survives the round trip through Redis")
    void productRoundTripsThroughRedis() throws Exception {
        String first = mockMvc.perform(get("/api/v1/products/" + productId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Second call: served from Redis, so the value is deserialised.
        String second = mockMvc.perform(get("/api/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ELEC-KBD-001"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);

        // And the deserialised value really is the record type, not a LinkedHashMap.
        Object cached = cacheManager.getCache(ProductService.CACHE_PRODUCT)
                .get(productId).get();
        assertThat(cached).isInstanceOf(ProductResponse.class);
        assertThat(((ProductResponse) cached).sku()).isEqualTo("ELEC-KBD-001");
    }

    /**
     * Generics are erased at runtime, so a {@code List.class} literal alone would
     * deserialise the elements as maps. The cache configuration builds the
     * {@code CollectionType} explicitly.
     */
    @Test
    @DisplayName("a cached List<CategoryResponse> survives the round trip, elements included")
    void categoryListRoundTripsThroughRedis() throws Exception {
        String first = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].slug").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);

        Object cached = cacheManager.getCache(CategoryService.CACHE_CATEGORY_LIST)
                .get("active").get();
        assertThat(cached).isInstanceOf(List.class);
        assertThat((List<?>) cached).isNotEmpty()
                .allSatisfy(element -> assertThat(element).isInstanceOf(CategoryResponse.class));
    }

    @Test
    @DisplayName("the stored JSON is clean — no @class type markers in Redis")
    void storedJsonCarriesNoClassNames() throws Exception {
        mockMvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isOk());

        byte[] raw = redisConnectionFactory.getConnection()
                .stringCommands()
                .get(("ecommerce:" + ProductService.CACHE_PRODUCT + "::" + productId).getBytes());

        assertThat(raw).isNotNull();
        assertThat(new String(raw))
                .as("embedding class names in a cache is its own hazard; the type comes "
                    + "from configuration instead")
                .doesNotContain("@class")
                .doesNotContain("com.bhavesh")
                .contains("ELEC-KBD-001");
    }

    @Test
    @DisplayName("updating a product evicts its cache entry")
    void writeEvictsTheProduct() throws Exception {
        String admin = adminToken("admin@example.com");

        mockMvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isOk());
        assertThat(cacheManager.getCache(ProductService.CACHE_PRODUCT).get(productId)).isNotNull();

        mockMvc.perform(patch("/api/v1/products/" + productId + "/active?active=false")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

        assertThat(cacheManager.getCache(ProductService.CACHE_PRODUCT).get(productId))
                .as("a stale cache entry would keep a retired product visible")
                .isNull();
    }

    @Test
    @DisplayName("creating a category evicts the whole list cache")
    void writeEvictsTheCategoryList() throws Exception {
        String admin = adminToken("admin@example.com");

        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        assertThat(cacheManager.getCache(CategoryService.CACHE_CATEGORY_LIST).get("active"))
                .isNotNull();

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"TEST-Gardening","description":"Tools and seeds"}"""))
                .andExpect(status().isCreated());

        assertThat(cacheManager.getCache(CategoryService.CACHE_CATEGORY_LIST).get("active"))
                .as("the list cache cannot be selectively updated, so it is cleared whole")
                .isNull();

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$.length()").value(6));
    }

    /**
     * Caching an entry under a key that is not configured with a concrete type would
     * reintroduce the {@code LinkedHashMap} problem. This pins the set of named
     * caches so a new {@code @Cacheable} with an unlisted name is noticed.
     */
    @Test
    @DisplayName("every configured cache is one with an explicit value type")
    void onlyConfiguredCachesExist() throws Exception {
        mockMvc.perform(get("/api/v1/products/" + productId));
        mockMvc.perform(get("/api/v1/categories"));
        mockMvc.perform(get("/api/v1/categories/" + categoryIdOf(productId)));

        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrder(
                        ProductService.CACHE_PRODUCT,
                        CategoryService.CACHE_CATEGORY,
                        CategoryService.CACHE_CATEGORY_LIST);
    }

    @Test
    @DisplayName("a cached read returns the same body as an uncached one")
    void cachedAndUncachedAgree() throws Exception {
        String uncached = mockMvc.perform(get("/api/v1/products/" + productId))
                .andReturn().getResponse().getContentAsString();

        cacheManager.getCache(ProductService.CACHE_PRODUCT).clear();

        String afterClear = mockMvc.perform(get("/api/v1/products/" + productId))
                .andReturn().getResponse().getContentAsString();
        String cached = mockMvc.perform(get("/api/v1/products/" + productId))
                .andReturn().getResponse().getContentAsString();

        assertThat(cached).isEqualTo(afterClear).isEqualTo(uncached);
    }
}
