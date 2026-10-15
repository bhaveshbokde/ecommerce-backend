package com.bhavesh.ecommerce;

import com.bhavesh.ecommerce.config.AdminProperties;
import com.bhavesh.ecommerce.config.CacheProperties;
import com.bhavesh.ecommerce.config.CorsProperties;
import com.bhavesh.ecommerce.config.JwtProperties;
import com.bhavesh.ecommerce.config.PaymentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * E-commerce backend — catalogue, cart, checkout, inventory and simulated payments.
 *
 * <p>{@code @SpringBootApplication} combines {@code @Configuration},
 * {@code @EnableAutoConfiguration} and {@code @ComponentScan}. Scanning starts at
 * this package and walks every sub-package, which is why the feature packages
 * below need no explicit registration.
 *
 * <p>Caching is enabled on {@link com.bhavesh.ecommerce.config.RedisCacheConfig}
 * rather than here, so it can be switched off wholesale with
 * {@code ecommerce.cache.enabled=false} for local debugging without touching this
 * class.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        CacheProperties.class,
        PaymentProperties.class,
        AdminProperties.class,
        CorsProperties.class
})
@EnableJpaAuditing          // populates @CreatedDate / @LastModifiedDate
@EnableTransactionManagement
public class EcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}
