package com.bhavesh.ecommerce.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document and Swagger UI.
 *
 * <p>Declaring the bearer scheme here — rather than annotating every controller —
 * puts an "Authorize" button in the UI that applies the token to every request, so
 * the documentation is usable for exploration rather than only for reading.
 *
 * <p>The scheme is declared globally and listed as a requirement, which marks every
 * operation as needing a token. The genuinely public endpoints (registration,
 * login, catalogue browsing) override that with
 * {@code @SecurityRequirements} on the method.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI ecommerceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-commerce Backend API")
                        .version("1.0.0")
                        .description("""
                                Catalogue, cart, checkout, inventory and simulated payments.

                                **No real payments are processed.** The payment provider is a \
                                deterministic simulator; no card details exist anywhere in this \
                                application.

                                **Authentication** — `POST /api/v1/auth/login` returns an access \
                                token and a refresh token. Send the access token as \
                                `Authorization: Bearer <token>`. When it expires, exchange the \
                                refresh token at `POST /api/v1/auth/refresh`; the refresh token is \
                                rotated on every use.

                                **Errors** follow RFC 9457 Problem Details. Branch on the `code` \
                                field, which is stable; `detail` may be reworded.

                                **Idempotency** — `POST /api/v1/payments` requires an \
                                `Idempotency-Key` header, and `POST /api/v1/orders` honours one. \
                                Retrying with the same key replays the original response.
                                """)
                        .contact(new Contact().name("Bhavesh Bokde"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
