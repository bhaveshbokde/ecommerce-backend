package com.bhavesh.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Binds {@code ecommerce.cors.*}.
 *
 * <p>An explicit allow-list, never a wildcard. The default covers the usual local
 * front-end dev servers; a real deployment overrides it with the actual origins.
 *
 * @param allowedOrigins exact origins permitted to call the API from a browser
 */
@ConfigurationProperties(prefix = "ecommerce.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:3000", "http://localhost:5173"})
        List<String> allowedOrigins
) {
}
