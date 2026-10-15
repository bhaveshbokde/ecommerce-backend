package com.bhavesh.ecommerce.order;

import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cart to confirmed order, against a real database.
 *
 * <p>Every assertion about stock reads the {@code inventory} table directly rather
 * than trusting the API's own response. The point of these tests is that the
 * database ends up in the right state, and a service that reported success while
 * leaving stock wrong would pass a test that only read its own output.
 */
class CheckoutFlowIT extends AbstractIntegrationTest {

    private static final String LAPTOP_SKU = "ELEC-LAPTOP-001";   // seeded with 5 units
    private static final String KEYBOARD_SKU = "ELEC-KBD-001";

    private String token;
    private Long laptopId;
    private Long keyboardId;

    @BeforeEach
    void setUp() throws Exception {
        token = customerToken("priya@example.com");
        laptopId = productIdBySku(LAPTOP_SKU);
        keyboardId = productIdBySku(KEYBOARD_SKU);
    }

    private void addToCart(Long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}""".formatted(productId, quantity)))
                .andExpect(status().isOk());
    }

    private String checkout(String idempotencyKey) throws Exception {
        var request = post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private String pay(Long orderId, String outcome, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "simulateOutcome": "%s"}"""
                                .formatted(orderId, outcome)))
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("cart")
    class Cart {

        @Test
        @DisplayName("an empty cart reads as empty rather than 404")
        void emptyCart() throws Exception {
            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.subtotal").value(0.00));
        }

        @Test
        @DisplayName("adding the same product twice merges into one line")
        void mergesLines() throws Exception {
            addToCart(laptopId, 2);
            addToCart(laptopId, 1);

            mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.totalQuantity").value(3));

            assertThat(countRows("cart_items")).isEqualTo(1);
        }

        /**
         * Reserving at cart time would let a handful of abandoned carts make a
         * product unbuyable.
         */
        @Test
        @DisplayName("adding to the cart does not reserve stock")
        void doesNotReserveStock() throws Exception {
            int before = availableStock(LAPTOP_SKU);
            addToCart(laptopId, 3);

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(before);
            assertThat(reservedStock(LAPTOP_SKU)).isZero();
        }

        @Test
        @DisplayName("a cart holding more than is available is flagged but still readable")
        void overStockIsFlagged() throws Exception {
            addToCart(laptopId, 5);
            mockMvc.perform(patch("/api/v1/cart/items/" + laptopId)
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity": 99}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checkoutBlocked").value(true));
        }

        @Test
        @DisplayName("a deactivated product cannot be added")
        void deactivatedProductRefused() throws Exception {
            String admin = adminToken("admin@example.com");
            mockMvc.perform(patch("/api/v1/products/" + laptopId + "/active?active=false")
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/cart/items")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId": %d, "quantity": 1}""".formatted(laptopId)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        }

        @Test
        @DisplayName("clearing the cart is idempotent")
        void clearIsIdempotent() throws Exception {
            addToCart(laptopId, 1);
            mockMvc.perform(delete("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }
    }

    @Nested
    @DisplayName("checkout")
    class Checkout {

        @Test
        @DisplayName("an empty cart is 404 — there is no cart to check out")
        void emptyCartCannotCheckOut() throws Exception {
            mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("moves stock from available to reserved, atomically")
        void reservesStock() throws Exception {
            addToCart(laptopId, 2);
            addToCart(keyboardId, 3);

            int laptopBefore = availableStock(LAPTOP_SKU);
            int keyboardBefore = availableStock(KEYBOARD_SKU);

            mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CREATED"))
                    .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(laptopBefore - 2);
            assertThat(reservedStock(LAPTOP_SKU)).isEqualTo(2);
            assertThat(availableStock(KEYBOARD_SKU)).isEqualTo(keyboardBefore - 3);
            assertThat(reservedStock(KEYBOARD_SKU)).isEqualTo(3);
        }

        @Test
        @DisplayName("empties the cart")
        void emptiesTheCart() throws Exception {
            addToCart(laptopId, 1);
            checkout(null);

            assertThat(countRows("cart_items")).isZero();
        }

        /**
         * The price snapshot — the single most important modelling decision in the
         * project, so it gets a test that genuinely reprices a product mid-flight
         * rather than asserting on a comment.
         *
         * <p>Operates on a product this test creates. Repricing a seeded one would
         * leave the catalogue changed for every later test, and {@code
         * resetDatabase} deliberately does not restore seeded prices — restoring
         * them would mean duplicating V9's data in a second place that could drift.
         */
        @Test
        @DisplayName("order lines keep their purchase-time price when the catalogue changes")
        void priceSnapshotSurvivesRepricing() throws Exception {
            String admin = adminToken("admin@example.com");
            String sku = TEST_SKU_PREFIX + "SNAPSHOT-001";

            String created = mockMvc.perform(post("/api/v1/products")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"%s","name":"Snapshot Probe","description":"For one test",
                                     "price":500.00,"categoryId":%d,"initialStock":10}"""
                                    .formatted(sku, categoryIdOf(laptopId))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            Long probeId = json(created).get("id").asLong();

            addToCart(probeId, 2);
            String order = checkout(null);
            Long orderId = json(order).get("id").asLong();

            assertThat(json(order).get("items").get(0).get("unitPrice").asDouble())
                    .isEqualTo(500.00);

            // The catalogue changes underneath the placed order.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/products/" + probeId)
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sku":"%s","name":"Snapshot Probe RENAMED","description":"Repriced",
                                     "price":1.00,"categoryId":%d}"""
                                    .formatted(sku, categoryIdOf(laptopId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").value(1.00));

            // The receipt is unchanged: price, name and line total all as purchased.
            mockMvc.perform(get("/api/v1/orders/" + orderId)
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].unitPrice").value(500.00))
                    .andExpect(jsonPath("$.items[0].name").value("Snapshot Probe"))
                    .andExpect(jsonPath("$.items[0].lineTotal").value(1000.00))
                    .andExpect(jsonPath("$.total").value(1000.00));
        }

        @Test
        @DisplayName("insufficient stock is 409 and leaves the cart and stock untouched")
        void insufficientStock() throws Exception {
            addToCart(laptopId, 5);
            mockMvc.perform(patch("/api/v1/cart/items/" + laptopId)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"quantity": 99}"""));

            int before = availableStock(LAPTOP_SKU);

            mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.available").value(before))
                    .andExpect(jsonPath("$.requested").value(99));

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(before);
            assertThat(reservedStock(LAPTOP_SKU)).isZero();
            assertThat(countRows("orders")).isZero();
            assertThat(countRows("cart_items"))
                    .as("the cart must survive a failed checkout")
                    .isEqualTo(1);
        }

        /**
         * The exception rolls the transaction back and the database undoes the
         * reservations taken before it — no compensating logic to write, and none to
         * get wrong.
         */
        @Test
        @DisplayName("a mid-basket failure rolls back the reservations already taken")
        void partialFailureRollsBackEverything() throws Exception {
            addToCart(keyboardId, 2);   // product id 1 — reserved first
            addToCart(laptopId, 5);
            mockMvc.perform(patch("/api/v1/cart/items/" + laptopId)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"quantity": 99}"""));

            int keyboardBefore = availableStock(KEYBOARD_SKU);

            mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isConflict());

            assertThat(availableStock(KEYBOARD_SKU))
                    .as("the keyboard was reserved before the laptop failed; it must be released")
                    .isEqualTo(keyboardBefore);
            assertThat(reservedStock(KEYBOARD_SKU)).isZero();
        }

        @Test
        @DisplayName("an Idempotency-Key makes a retry return the original order")
        void idempotentCheckout() throws Exception {
            addToCart(laptopId, 1);

            String first = checkout("checkout-key-1");
            mockMvc.perform(post("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "checkout-key-1"))
                    .andExpect(status().isOk())   // 200, not 201: nothing was created
                    .andExpect(jsonPath("$.orderNumber").value(json(first).get("orderNumber").asText()));

            assertThat(countRows("orders")).isEqualTo(1);
            assertThat(reservedStock(LAPTOP_SKU))
                    .as("a replay must not reserve a second time")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("order numbers are not sequential ids")
        void orderNumberIsOpaque() throws Exception {
            addToCart(laptopId, 1);

            mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.orderNumber")
                            .value(org.hamcrest.Matchers.matchesPattern("ORD-\\d{8}-[0-9A-F]{6}")));
        }
    }

    @Nested
    @DisplayName("payment")
    class Payment {

        private Long orderId;

        @BeforeEach
        void placeAnOrder() throws Exception {
            addToCart(laptopId, 2);
            orderId = json(checkout(null)).get("id").asLong();
        }

        @Test
        @DisplayName("a successful charge confirms the order and keeps the reservation")
        void successConfirms() throws Exception {
            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "simulateOutcome": "SUCCESS"}""".formatted(orderId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.providerReference").isNotEmpty());

            mockMvc.perform(get("/api/v1/orders/" + orderId)
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));

            assertThat(reservedStock(LAPTOP_SKU))
                    .as("stock stays reserved until it ships")
                    .isEqualTo(2);
        }

        /**
         * The compensation. A failed charge must give the stock back, or the units
         * sit held against an order that will never ship.
         */
        @Test
        @DisplayName("a declined charge releases the reserved stock")
        void declineReleasesStock() throws Exception {
            int availableBefore = availableStock(LAPTOP_SKU);

            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "simulateOutcome": "DECLINE"}""".formatted(orderId)))
                    // 201: the attempt was processed. The outcome is in the body.
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.failureReason").value("Card declined by issuer"));

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(availableBefore + 2);
            assertThat(reservedStock(LAPTOP_SKU)).isZero();
        }

        @Test
        @DisplayName("a declined charge leaves the order retryable")
        void declineLeavesOrderRetryable() throws Exception {
            pay(orderId, "DECLINE", "pay-1");

            mockMvc.perform(get("/api/v1/orders/" + orderId)
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.status").value("CREATED"))
                    .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                    .andExpect(jsonPath("$.cancellable").value(true));
        }

        @Test
        @DisplayName("an unreachable provider is also treated as a failure, and recorded as one")
        void providerErrorReleasesStock() throws Exception {
            int before = availableStock(LAPTOP_SKU);

            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "simulateOutcome": "PROVIDER_ERROR"}"""
                                    .formatted(orderId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"));

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(before + 2);
        }

        @Test
        @DisplayName("the Idempotency-Key header is required")
        void keyIsRequired() throws Exception {
            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d}""".formatted(orderId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            assertThat(countRows("payments")).isZero();
        }

        @Test
        @DisplayName("a retry with the same key replays the outcome and does not charge again")
        void replayDoesNotCharge() throws Exception {
            pay(orderId, "DECLINE", "pay-1");
            int afterFirst = availableStock(LAPTOP_SKU);

            // Asks for SUCCESS this time; the stored failure must win.
            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "simulateOutcome": "SUCCESS"}""".formatted(orderId)))
                    .andExpect(status().isOk())   // 200: replayed, nothing created
                    .andExpect(jsonPath("$.status").value("FAILED"));

            assertThat(countRows("payments")).isEqualTo(1);
            assertThat(availableStock(LAPTOP_SKU))
                    .as("a replay must not release stock a second time")
                    .isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("paying an already-paid order is refused, whatever key is used")
        void noDoubleCharge() throws Exception {
            pay(orderId, "SUCCESS", "pay-1");

            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "a-completely-different-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d}""".formatted(orderId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS"));

            assertThat(countRows("payments")).isEqualTo(1);
        }

        @Test
        @DisplayName("reusing a key for a different order is 422")
        void keyReuseAcrossOrders() throws Exception {
            pay(orderId, "SUCCESS", "pay-1");

            addToCart(keyboardId, 1);
            Long second = json(checkout(null)).get("id").asLong();

            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d}""".formatted(second)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }

        /**
         * The partial index {@code uq_payments_order_active} excludes FAILED rows
         * precisely so a declined card can be retried.
         */
        @Test
        @DisplayName("a fresh key after a decline is allowed to succeed")
        void retryAfterDecline() throws Exception {
            pay(orderId, "DECLINE", "pay-1");

            // The decline released the stock, so the order must be re-checked-out.
            addToCart(laptopId, 2);
            Long retryOrder = json(checkout(null)).get("id").asLong();

            mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "simulateOutcome": "SUCCESS"}"""
                                    .formatted(retryOrder)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        }

        @Test
        @DisplayName("there is no amount field, so the charge cannot be tampered with")
        void amountCannotBeTampered() throws Exception {
            String body = mockMvc.perform(post("/api/v1/payments")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "pay-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderId": %d, "amount": 0.01, "total": 0.01}"""
                                    .formatted(orderId)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            // Two laptops at the seeded price, not the 0.01 the client asked for.
            assertThat(json(body).get("amount").asDouble()).isGreaterThan(1.0);
        }
    }

    @Nested
    @DisplayName("cancellation and fulfilment")
    class Lifecycle {

        private Long orderId;

        @BeforeEach
        void placeAnOrder() throws Exception {
            addToCart(laptopId, 2);
            orderId = json(checkout(null)).get("id").asLong();
        }

        @Test
        @DisplayName("cancelling an unpaid order releases its stock")
        void cancelReleasesStock() throws Exception {
            int before = availableStock(LAPTOP_SKU);

            mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            assertThat(availableStock(LAPTOP_SKU)).isEqualTo(before + 2);
            assertThat(reservedStock(LAPTOP_SKU)).isZero();
        }

        @Test
        @DisplayName("cancelling a paid order also records a refund")
        void cancelPaidOrderRefunds() throws Exception {
            pay(orderId, "SUCCESS", "pay-1");

            mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        }

        @Test
        @DisplayName("shipping consumes the reservation rather than returning it")
        void shippingCommitsTheReservation() throws Exception {
            pay(orderId, "SUCCESS", "pay-1");
            String admin = adminToken("admin@example.com");

            int availableAfterPayment = availableStock(LAPTOP_SKU);

            for (String target : new String[]{"PROCESSING", "SHIPPED"}) {
                mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                                .header(HttpHeaders.AUTHORIZATION, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status": "%s"}""".formatted(target)))
                        .andExpect(status().isOk());
            }

            assertThat(reservedStock(LAPTOP_SKU))
                    .as("the hold is cleared — the units have physically left")
                    .isZero();
            assertThat(availableStock(LAPTOP_SKU))
                    .as("they are NOT returned to available")
                    .isEqualTo(availableAfterPayment);
        }

        @Test
        @DisplayName("a shipped order can no longer be cancelled")
        void shippedCannotBeCancelled() throws Exception {
            pay(orderId, "SUCCESS", "pay-1");
            String admin = adminToken("admin@example.com");

            for (String target : new String[]{"PROCESSING", "SHIPPED"}) {
                mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "%s"}""".formatted(target)));
            }

            mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        }

        @Test
        @DisplayName("an illegal status jump is 422 and names the allowed targets")
        void illegalJump() throws Exception {
            String admin = adminToken("admin@example.com");

            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "DELIVERED"}"""))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("CONFIRMED")));
        }

        @Test
        @DisplayName("an order cannot be confirmed before its payment succeeds")
        void cannotConfirmUnpaid() throws Exception {
            String admin = adminToken("admin@example.com");

            mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "CONFIRMED"}"""))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    @DisplayName("the order history omits lines, and the detail view includes them")
    void listVersusDetail() throws Exception {
        addToCart(laptopId, 1);
        Long orderId = json(checkout(null)).get("id").asLong();

        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].items").doesNotExist());

        mockMvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
