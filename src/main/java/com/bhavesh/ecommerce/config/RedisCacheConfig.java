package com.bhavesh.ecommerce.config;

import com.bhavesh.ecommerce.catalog.dto.CategoryResponse;
import com.bhavesh.ecommerce.catalog.dto.ProductResponse;
import com.bhavesh.ecommerce.catalog.service.CategoryService;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis caching for the catalogue.
 *
 * <h2>Why each cache gets its own serializer</h2>
 * This is the subtle part, and it is worth the detail because the failure mode is
 * invisible in testing.
 *
 * <p>The obvious configuration — one {@code GenericJackson2JsonRedisSerializer} for
 * everything — writes plain JSON with no record of what type it was. On the way
 * back, Jackson has a {@code String} and an {@code Object} target, so it produces a
 * {@code LinkedHashMap}. Spring then hands that to a method declared to return
 * {@code ProductResponse} and the cast fails.
 *
 * <p>The usual answer is Jackson's default typing, which embeds a {@code @class}
 * field. It does not work here: the response types are <strong>records</strong>,
 * and records are implicitly final, so {@code DefaultTyping.NON_FINAL} skips
 * exactly the classes that needed it. {@code NON_FINAL_AND_ENUMS} has the same
 * blind spot. {@code EVERYTHING} would work and would also write a {@code @class}
 * field into every nested string and number — and embedding class names in a
 * cache is its own hazard, since a deserialiser that instantiates arbitrary named
 * types has been the root of a long line of CVEs.
 *
 * <p>So each cache binds a {@link Jackson2JsonRedisSerializer} to a concrete
 * {@link JavaType}. The type comes from the configuration, not from the payload:
 * nothing in Redis can influence what class is instantiated, and the stored JSON
 * stays clean.
 *
 * <p>Found by running the application, not by the unit suite: a cache <em>miss</em>
 * silently works, because the value is returned straight from the method. Only a
 * hit goes through deserialisation. Every test that ran once passed.
 *
 * <h2>Degradation</h2>
 * {@link #cacheErrorHandler} logs and continues when Redis is unavailable. A cache
 * is an optimisation; an outage in it should cost latency, not availability. The
 * default handler rethrows, which would turn a Redis blip into a site-wide 500.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    public RedisCacheConfig(CacheProperties cacheProperties, ObjectMapper objectMapper) {
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Active when {@code ecommerce.cache.enabled} is true (the default).
     *
     * <p>TTLs differ by volatility: products change more often than categories, so
     * they expire sooner. The TTL is a safety net, not the mechanism — writes evict
     * explicitly. It exists for the cases eviction cannot cover: a direct database
     * edit, or a cache entry written by an instance that then crashed.
     */
    @Bean
    @ConditionalOnProperty(name = "ecommerce.cache.enabled", havingValue = "true",
            matchIfMissing = true)
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> caches = new HashMap<>();

        caches.put(ProductService.CACHE_PRODUCT,
                configFor(ProductResponse.class, cacheProperties.productTtl()));

        caches.put(CategoryService.CACHE_CATEGORY,
                configFor(CategoryResponse.class, cacheProperties.categoryTtl()));

        // A List<CategoryResponse>, so the JavaType has to be constructed rather
        // than taken from a class literal — generics are erased at runtime, and
        // List.class alone would deserialise the elements as maps.
        caches.put(CategoryService.CACHE_CATEGORY_LIST,
                configFor(objectMapper.getTypeFactory()
                        .constructCollectionType(java.util.List.class, CategoryResponse.class),
                        cacheProperties.categoryTtl()));

        log.info("Redis cache enabled: {} (product TTL {}, category TTL {})",
                caches.keySet(), cacheProperties.productTtl(), cacheProperties.categoryTtl());

        return RedisCacheManager.builder(connectionFactory)
                .withInitialCacheConfigurations(caches)
                // Any cache name not listed above gets this. Serializing to the
                // concrete type is impossible without knowing it, so an unconfigured
                // cache is a configuration error — it must be added here.
                .cacheDefaults(baseConfig(cacheProperties.productTtl())
                        .serializeValuesWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper,
                                        objectMapper.constructType(Object.class)))))
                .transactionAware()   // evictions apply only if the transaction commits
                .build();
    }

    /**
     * The no-op fallback for {@code ecommerce.cache.enabled=false}.
     *
     * <p>Exists so the integration tests can run without Redis. Spring still
     * processes {@code @Cacheable}; the manager just never stores anything, which
     * keeps the code path identical rather than conditionally absent.
     */
    @Bean
    @ConditionalOnProperty(name = "ecommerce.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        log.info("Caching disabled (ecommerce.cache.enabled=false); using NoOpCacheManager");
        return new NoOpCacheManager();
    }

    /**
     * Keeps a Redis outage from becoming an application outage.
     *
     * <p>Get, put and evict failures are logged and swallowed, so the request falls
     * through to the database. One exception: an <em>evict</em> failure means stale
     * data may now be served, which is a correctness problem rather than a
     * performance one — so it is logged at ERROR to make it visible, while still
     * not failing the write that had already succeeded.
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception,
                                            org.springframework.cache.Cache cache, Object key) {
                log.warn("Cache GET failed on '{}' key={}; falling through to the database: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception,
                                            org.springframework.cache.Cache cache,
                                            Object key, Object value) {
                log.warn("Cache PUT failed on '{}' key={}: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception,
                                              org.springframework.cache.Cache cache, Object key) {
                log.error("Cache EVICT failed on '{}' key={}; stale data may be served until the TTL expires",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception,
                                              org.springframework.cache.Cache cache) {
                log.error("Cache CLEAR failed on '{}'; stale data may be served until the TTL expires",
                        cache.getName(), exception);
            }
        };
    }

    private RedisCacheConfiguration configFor(Class<?> type, java.time.Duration ttl) {
        return configFor(objectMapper.constructType(type), ttl);
    }

    private RedisCacheConfiguration configFor(JavaType type, java.time.Duration ttl) {
        return baseConfig(ttl).serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }

    private RedisCacheConfiguration baseConfig(java.time.Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // A null is a legitimate answer ("no such product") and caching it
                // would be a small win, but Spring cannot distinguish a cached null
                // from a miss without extra machinery. Disabling it keeps the
                // semantics obvious.
                .disableCachingNullValues()
                .prefixCacheNameWith("ecommerce:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));
    }
}
