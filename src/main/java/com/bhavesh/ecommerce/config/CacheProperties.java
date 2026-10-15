package com.bhavesh.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code ecommerce.cache.*}.
 *
 * <p>Named {@code CacheProperties} rather than reusing Spring's own
 * {@code spring.cache.*} tree, which is bound by Boot itself.
 *
 * @param enabled     master switch; false disables {@code @Cacheable} entirely,
 *                    which is the documented way to debug a suspected cache problem
 * @param productTtl  TTL for product reads — short, because an administrator edit
 *                    must become visible quickly even if an eviction is lost
 * @param categoryTtl TTL for the category list — longer, since it changes rarely
 */
@ConfigurationProperties(prefix = "ecommerce.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT10M") Duration productTtl,
        @DefaultValue("PT30M") Duration categoryTtl
) {
}
