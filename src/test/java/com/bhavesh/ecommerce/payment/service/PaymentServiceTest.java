package com.bhavesh.ecommerce.payment.service;

import com.bhavesh.ecommerce.common.util.ReferenceGenerator;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.inventory.service.InventoryService;
import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.service.OrderService;
import com.bhavesh.ecommerce.payment.dto.PaymentRequest;
import com.bhavesh.ecommerce.payment.entity.Payment;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import com.bhavesh.ecommerce.payment.provider.PaymentProvider;
import com.bhavesh.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Charging an order, and compensating when the charge fails.
 *
 * <p>The provider is mocked here, which is the right boundary: the interesting
 * behaviour is not what the simulator returns but what this service does with each
 * answer — confirm the order, or release the stock and leave it retryable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentProvider paymentProvider;

    @Mock
    private OrderService orderService;

    @Mock
    private InventoryService inventoryService;

    private PaymentService paymentService;
    private Order order;

    @BeforeEach
    void setUp() {
        paymentService = buildService(new MockEnvironment());

        order = new Order("ORD-20261014-ABC123", 7L, "INR");
        ReflectionTestUtils.setField(order, "id", 42L);
        order.addItem(1L, "SKU-1", "Keyboard", new BigDecimal("6499.00"), 2);

        when(orderService.requireOwnedOrder(42L, 7L)).thenReturn(order);
        when(orderService.quantitiesByProduct(order)).thenReturn(Map.of(1L, 2));
        when(paymentRepository.findActiveByOrderId(42L)).thenReturn(List.of());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentService buildService(MockEnvironment environment) {
        return new PaymentService(paymentRepository, paymentProvider, orderService,
                inventoryService, new ReferenceGenerator(), environment);
    }

    @Nested
    @DisplayName("successful charge")
    class Success {

        @BeforeEach
        void providerAccepts() {
            when(paymentProvider.charge(anyString(), any(), anyString(), any()))
                    .thenReturn(PaymentProvider.ChargeResult.succeeded("SIM-ABC123"));
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("the payment succeeds and the order is confirmed")
        void confirmsTheOrder() {
            var outcome = paymentService.pay(7L,
                    new PaymentRequest(42L, SimulatedOutcome.SUCCESS), "key-1");

            assertThat(outcome.replayed()).isFalse();
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(outcome.payment().providerReference()).isEqualTo("SIM-ABC123");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("stock is NOT released on success — it stays reserved until shipping")
        void keepsTheReservation() {
            paymentService.pay(7L, new PaymentRequest(42L, SimulatedOutcome.SUCCESS), "key-1");

            verify(inventoryService, never()).releaseAll(any());
        }

        @Test
        @DisplayName("the amount is taken from the order, not from the request")
        void amountComesFromTheOrder() {
            var outcome = paymentService.pay(7L,
                    new PaymentRequest(42L, SimulatedOutcome.SUCCESS), "key-1");

            // 2 x 6499.00. There is no amount field in PaymentRequest at all, so
            // price tampering has nowhere to enter.
            assertThat(outcome.payment().amount()).isEqualByComparingTo("12998.00");
        }
    }

    @Nested
    @DisplayName("declined charge")
    class Declined {

        @BeforeEach
        void providerDeclines() {
            when(paymentProvider.charge(anyString(), any(), anyString(), any()))
                    .thenReturn(PaymentProvider.ChargeResult.declined("Card declined by issuer"));
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.empty());
        }

        /**
         * A decline is an answer, not an error. Throwing would roll back the very
         * rows that record the failure and release the stock, so the next attempt
         * would find no trace of it.
         */
        @Test
        @DisplayName("returns normally with status FAILED rather than throwing")
        void doesNotThrow() {
            var outcome = paymentService.pay(7L,
                    new PaymentRequest(42L, SimulatedOutcome.DECLINE), "key-1");

            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(outcome.payment().failureReason()).isEqualTo("Card declined by issuer");
        }

        @Test
        @DisplayName("the reserved stock is released")
        void releasesStock() {
            paymentService.pay(7L, new PaymentRequest(42L, SimulatedOutcome.DECLINE), "key-1");

            verify(inventoryService).releaseAll(Map.of(1L, 2));
        }

        @Test
        @DisplayName("the order stays CREATED so the customer can try another instrument")
        void orderRemainsRetryable() {
            paymentService.pay(7L, new PaymentRequest(42L, SimulatedOutcome.DECLINE), "key-1");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getStatus().isCancellable()).isTrue();
        }
    }

    @Nested
    @DisplayName("provider unreachable")
    class ProviderError {

        /**
         * The genuinely hard case: the charge may or may not have landed. This
         * application treats it as a failure and releases stock — which risks
         * releasing stock for a charge that actually succeeded. The correct fix is
         * reconciliation against the provider, which needs a provider to reconcile
         * against. The choice is recorded rather than silently made.
         */
        @Test
        @DisplayName("an unknown outcome is treated as a failure, and that is recorded")
        void treatedAsFailure() {
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.empty());
            when(paymentProvider.charge(anyString(), any(), anyString(), any()))
                    .thenReturn(PaymentProvider.ChargeResult.error("Payment provider did not respond"));

            var outcome = paymentService.pay(7L,
                    new PaymentRequest(42L, SimulatedOutcome.PROVIDER_ERROR), "key-1");

            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(outcome.payment().failureReason()).contains("did not respond");
            verify(inventoryService).releaseAll(Map.of(1L, 2));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a missing Idempotency-Key is rejected — a duplicate charge is not opt-in")
        void keyIsMandatory() {
            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), null))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "  "))
                    .isInstanceOf(ApiException.class);

            verify(paymentProvider, never()).charge(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("a known key replays the original outcome without charging again")
        void replaysWithoutCharging() {
            Payment original = new Payment("PAY-1", 42L, 7L, new BigDecimal("12998.00"),
                    "INR", "key-1");
            original.markFailed("Card declined by issuer");
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.of(original));

            // Asks for SUCCESS this time; the stored failure must win.
            var outcome = paymentService.pay(7L,
                    new PaymentRequest(42L, SimulatedOutcome.SUCCESS), "key-1");

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.FAILED);
            verify(paymentProvider, never()).charge(anyString(), any(), anyString(), any());
            verify(inventoryService, never()).releaseAll(any());
        }

        @Test
        @DisplayName("reusing a key for a different order is 422, not a silent replay")
        void keyReuseAcrossOrders() {
            Payment original = new Payment("PAY-1", 99L, 7L, new BigDecimal("500.00"),
                    "INR", "key-1");
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.of(original));

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-1"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        /**
         * Two concurrent retries both pass the pre-read; the unique index decides.
         * The loser must be told to retry rather than receiving a 500.
         */
        @Test
        @DisplayName("losing the unique-index race on the key reports a conflict, not a 500")
        void concurrentSameKey() {
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-1"))
                    .thenReturn(Optional.empty())              // the pre-read
                    .thenReturn(Optional.of(new Payment("PAY-1", 42L, 7L,
                            new BigDecimal("12998.00"), "INR", "key-1")));   // after the violation
            when(paymentRepository.saveAndFlush(any(Payment.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_payments_user_idempotency"));

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-1"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        }

        @Test
        @DisplayName("losing the partial-index race on the order reports PAYMENT_ALREADY_EXISTS")
        void concurrentDifferentKeySameOrder() {
            when(paymentRepository.findByUserIdAndIdempotencyKey(7L, "key-2"))
                    .thenReturn(Optional.empty());
            when(paymentRepository.saveAndFlush(any(Payment.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_payments_order_active"));

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-2"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_EXISTS);
        }
    }

    @Nested
    @DisplayName("order eligibility")
    class Eligibility {

        @BeforeEach
        void noPriorKey() {
            when(paymentRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("an already-paid order is refused")
        void alreadyPaid() {
            order.confirm();

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-1"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_EXISTS);

            verify(paymentProvider, never()).charge(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("a cancelled order is refused")
        void cancelledOrder() {
            order.cancel();

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-1"))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("cancelled");
        }

        @Test
        @DisplayName("an order already in flight is refused before the provider is called")
        void liveAttemptExists() {
            when(paymentRepository.findActiveByOrderId(42L)).thenReturn(
                    List.of(new Payment("PAY-9", 42L, 7L, new BigDecimal("1"), "INR", "other-key")));

            assertThatThrownBy(() -> paymentService.pay(7L, new PaymentRequest(42L, null), "key-1"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_EXISTS);

            verify(paymentProvider, never()).charge(anyString(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("the simulateOutcome hint")
    class OutcomeHint {

        @BeforeEach
        void noPriorKey() {
            when(paymentRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                    .thenReturn(Optional.empty());
            when(paymentProvider.charge(anyString(), any(), anyString(), any()))
                    .thenReturn(PaymentProvider.ChargeResult.succeeded("SIM-1"));
        }

        @Test
        @DisplayName("is passed through outside production")
        void honouredInDevelopment() {
            paymentService.pay(7L, new PaymentRequest(42L, SimulatedOutcome.DECLINE), "key-1");

            verify(paymentProvider).charge(anyString(), any(), anyString(),
                    eq(SimulatedOutcome.DECLINE));
        }

        /**
         * Under {@code prod} the hint is dropped, so no request can talk a production
         * deployment into declining or succeeding on demand.
         */
        @Test
        @DisplayName("is ignored under the prod profile")
        void ignoredInProduction() {
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            PaymentService production = buildService(prod);

            production.pay(7L, new PaymentRequest(42L, SimulatedOutcome.DECLINE), "key-1");

            verify(paymentProvider).charge(anyString(), any(), anyString(), isNull());
        }
    }
}
