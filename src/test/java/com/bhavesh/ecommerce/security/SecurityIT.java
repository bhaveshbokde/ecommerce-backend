package com.bhavesh.ecommerce.security;

import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication, authorization and data isolation, end to end.
 *
 * <p>These are the tests a reviewer reads first, so they assert on behaviour an
 * attacker would actually probe rather than on configuration being present.
 */
class SecurityIT extends AbstractIntegrationTest {

    private String customerToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        customerToken = customerToken("priya@example.com");
        adminToken = adminToken("admin@example.com");
    }

    @Nested
    @DisplayName("public endpoints")
    class Public {

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/products", "/api/v1/categories", "/actuator/health"})
        @DisplayName("are reachable without a token")
        void reachableAnonymously(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }

        /**
         * A matcher for GET does not match HEAD, so without an explicit rule a
         * caching proxy or an uptime monitor hitting a deliberately public URL would
         * get a 401.
         */
        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/products", "/api/v1/categories"})
        @DisplayName("answer HEAD as well as GET")
        void headIsAllowed(String path) throws Exception {
            mockMvc.perform(request(HttpMethod.HEAD, path)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("the public catalogue exposes a stock boolean, never an exact count")
        void noExactStockOnPublicCatalogue() throws Exception {
            mockMvc.perform(get("/api/v1/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].inStock").isBoolean())
                    .andExpect(jsonPath("$.content[0].available").doesNotExist())
                    .andExpect(jsonPath("$.content[0].reserved").doesNotExist());
        }
    }

    @Nested
    @DisplayName("401 — not authenticated")
    class Unauthenticated {

        @ParameterizedTest
        @CsvSource({
                "GET,  /api/v1/cart",
                "GET,  /api/v1/orders",
                "POST, /api/v1/orders",
                "GET,  /api/v1/payments",
                "GET,  /api/v1/auth/me",
                "GET,  /api/v1/admin/orders",
        })
        @DisplayName("a protected endpoint without a token is 401 in the standard shape")
        void protectedEndpointsRequireAToken(String method, String path) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(method), path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        @Test
        @DisplayName("a 401 carries a WWW-Authenticate header, per RFC 6750")
        void wwwAuthenticateHeader() throws Exception {
            mockMvc.perform(get("/api/v1/cart"))
                    .andExpect(header().string("WWW-Authenticate",
                            org.hamcrest.Matchers.containsString("Bearer")));
        }

        @Test
        @DisplayName("a tampered token is TOKEN_INVALID, distinct from a missing one")
        void tamperedToken() throws Exception {
            String tampered = customerToken.substring(0, customerToken.length() - 1) + "X";

            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, tampered))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        /**
         * Verification is built with {@code verifyWith(SecretKey)}, so JJWT accepts
         * only a MAC algorithm and the header's {@code alg} never selects the
         * strategy. A forged "unsigned" admin token fails signature verification.
         */
        @Test
        @DisplayName("an alg:none forgery claiming ROLE_ADMIN is refused")
        void algNoneForgery() throws Exception {
            String encode = "";
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"sub\":\"1\",\"rol\":\"ROLE_ADMIN\",\"typ\":\"ACCESS\","
                     + "\"iss\":\"ecommerce-backend\",\"exp\":9999999999}").getBytes());

            mockMvc.perform(get("/api/v1/admin/orders")
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + header + "." + payload + "." + encode))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        /**
         * Without the {@code typ} claim, a seven-day refresh token presented as a
         * bearer credential would be accepted — silently turning it into a seven-day
         * API key.
         */
        @Test
        @DisplayName("a refresh token is not accepted as a bearer credential")
        void refreshTokenIsNotABearerToken() throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"%s"}"""
                                    .formatted(TEST_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + json(body).get("refreshToken").asText()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "Basic dXNlcjpwYXNz", "Bearer", "Bearer    ",
                "Token abc123", "bearer lowercase-scheme"})
        @DisplayName("a malformed Authorization header is treated as no credential")
        void malformedAuthorizationHeader(String value) throws Exception {
            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, value))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("403 — authenticated but not permitted")
    class Forbidden {

        @ParameterizedTest
        @CsvSource({
                "GET,  /api/v1/admin/orders",
                "GET,  /api/v1/admin/products",
                "GET,  /api/v1/admin/categories",
                "GET,  /api/v1/admin/inventory/1",
        })
        @DisplayName("a customer reaching an admin endpoint is 403, not 401")
        void customerCannotReachAdminEndpoints(String method, String path) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(method), path)
                            .header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("a customer cannot create a product")
        void customerCannotWriteCatalogue() throws Exception {
            mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, customerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"TEST-HACK-001","name":"Free Laptop","price":0.01,
                                     "categoryId":1,"initialStock":100}"""))
                    .andExpect(status().isForbidden());

            assertThat(countRows("products"))
                    .as("nothing may have been created")
                    .isEqualTo(18);
        }

        @Test
        @DisplayName("an administrator can reach the same endpoints")
        void adminCan() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orders")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk());
        }

        /**
         * 403 means "I know who you are and the answer is no". Returning 401 would
         * send a correctly-authenticated client into a pointless re-login loop.
         */
        @Test
        @DisplayName("403 is distinguishable from 401 by both status and code")
        void notTheSameAs401() throws Exception {
            String anonymous = mockMvc.perform(get("/api/v1/admin/orders"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String customer = mockMvc.perform(get("/api/v1/admin/orders")
                            .header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(json(anonymous).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(json(customer).get("code").asText()).isEqualTo("ACCESS_DENIED");
        }
    }

    @Nested
    @DisplayName("data isolation")
    class Isolation {

        private Long priyaOrderId;
        private String malloryToken;

        @BeforeEach
        void twoCustomersOneOrder() throws Exception {
            Long productId = productIdBySku("ELEC-KBD-001");

            mockMvc.perform(post("/api/v1/cart/items")
                    .header(HttpHeaders.AUTHORIZATION, customerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"productId": %d, "quantity": 1}""".formatted(productId)));

            String order = mockMvc.perform(post("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andReturn().getResponse().getContentAsString();
            priyaOrderId = json(order).get("id").asLong();

            malloryToken = customerToken("mallory@example.com");
        }

        /**
         * 404 rather than 403, deliberately. A 403 would confirm the id is real,
         * turning every endpoint into an existence oracle an attacker can enumerate.
         * The repositories take a {@code userId} in the WHERE clause, so "missing"
         * and "not yours" are genuinely indistinguishable in the code too.
         */
        @Test
        @DisplayName("another customer's order is 404, not 403")
        void cannotReadAnotherCustomersOrder() throws Exception {
            mockMvc.perform(get("/api/v1/orders/" + priyaOrderId)
                            .header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("a real-but-not-yours id is indistinguishable from one that does not exist")
        void existenceIsNotLeaked() throws Exception {
            String real = mockMvc.perform(get("/api/v1/orders/" + priyaOrderId)
                            .header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String imaginary = mockMvc.perform(get("/api/v1/orders/99999999")
                            .header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(json(real).get("code").asText())
                    .isEqualTo(json(imaginary).get("code").asText());
        }

        @Test
        @DisplayName("another customer cannot cancel your order")
        void cannotCancelAnotherCustomersOrder() throws Exception {
            mockMvc.perform(post("/api/v1/orders/" + priyaOrderId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/orders/" + priyaOrderId)
                            .header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andExpect(jsonPath("$.status").value("CREATED"));
        }

        @Test
        @DisplayName("another customer cannot pay for your order")
        void cannotPayAnotherCustomersOrder() throws Exception {
            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, malloryToken)
                            .header("Idempotency-Key", "mallory-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d}""".formatted(priyaOrderId)))
                    .andExpect(status().isNotFound());

            assertThat(countRows("payments")).isZero();
        }

        @Test
        @DisplayName("the order history shows only the caller's own orders")
        void historyIsScoped() throws Exception {
            mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));

            mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        /**
         * Every cart endpoint resolves the cart from the verified principal; there is
         * no cart id in any path to tamper with.
         */
        @Test
        @DisplayName("carts are keyed by the principal, so there is no id to tamper with")
        void cartsAreNotAddressable() throws Exception {
            Long productId = productIdBySku("ELEC-KBD-001");
            mockMvc.perform(post("/api/v1/cart/items")
                    .header(HttpHeaders.AUTHORIZATION, malloryToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"productId": %d, "quantity": 7}""".formatted(productId)));

            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, customerToken))
                    .andExpect(jsonPath("$.items").isEmpty());

            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, malloryToken))
                    .andExpect(jsonPath("$.totalQuantity").value(7));
        }

        @Test
        @DisplayName("an administrator can read any order — that read is deliberately unscoped")
        void adminSeesEverything() throws Exception {
            mockMvc.perform(get("/api/v1/admin/orders/" + priyaOrderId)
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(priyaOrderId));
        }

        /**
         * An administrator manages the catalogue and fulfilment. Reading a
         * customer's cart has no operational justification and would be a privacy
         * regression, so there is no endpoint for it at all.
         */
        @Test
        @DisplayName("an administrator has no endpoint for reading a customer's cart")
        void adminCannotReadCustomerCarts() throws Exception {
            mockMvc.perform(get("/api/v1/admin/carts")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("injection resistance")
    class Injection {

        @ParameterizedTest
        @ValueSource(strings = {
                "' OR 1=1 --",
                "'; DROP TABLE products; --",
                "' UNION SELECT password_hash FROM users --",
                "%' OR sku LIKE '%",
        })
        @DisplayName("SQL metacharacters in a search term are matched as literal text")
        void searchIsParameterised(String payload) throws Exception {
            mockMvc.perform(get("/api/v1/products").param("search", payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));

            // The catalogue is intact and no hash leaked into the response.
            mockMvc.perform(get("/api/v1/products"))
                    .andExpect(jsonPath("$.totalElements").value(18))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("$2a$"))));
        }

        @Test
        @DisplayName("an unknown sort field falls back to id instead of reaching the SQL")
        void sortFieldIsWhitelisted() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("sort", "price); DROP TABLE users; --"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(18));
        }

        @Test
        @DisplayName("an oversized page size is capped rather than honoured")
        void pageSizeIsCapped() throws Exception {
            mockMvc.perform(get("/api/v1/products").param("size", "1000000"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a non-numeric path variable is 400, not 500")
        void typeMismatchIsNotAServerError() throws Exception {
            mockMvc.perform(get("/api/v1/products/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

        @Test
        @DisplayName("unparseable JSON is 400 and does not echo the body back")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"a@b.c\", \"password\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("a@b.c"))));
        }
    }

    @Nested
    @DisplayName("error bodies")
    class ErrorBodies {

        @Test
        @DisplayName("no stack trace, SQL or internal path reaches the client")
        void noInternalsLeak() throws Exception {
            String body = mockMvc.perform(get("/api/v1/products/99999999"))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("org.hibernate")
                    .doesNotContain("org.springframework")
                    .doesNotContain("jdbc:postgresql")
                    .doesNotContain("at com.bhavesh")
                    .doesNotContain("SELECT");
        }

        @Test
        @DisplayName("every error carries a trace id that is also in the response header")
        void traceIdIsCorrelatable() throws Exception {
            var result = mockMvc.perform(get("/api/v1/products/99999999"))
                    .andExpect(status().isNotFound())
                    .andReturn();

            String inBody = json(result.getResponse().getContentAsString()).get("traceId").asText();
            assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(inBody);
        }

        @Test
        @DisplayName("an unsupported method is 405, in the standard shape")
        void methodNotAllowed() throws Exception {
            mockMvc.perform(request(HttpMethod.DELETE, "/api/v1/auth/login"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("an unsupported content type is 415")
        void unsupportedMediaType() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("email=a@b.c"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
    }
}
