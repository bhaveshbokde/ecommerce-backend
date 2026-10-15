package com.bhavesh.ecommerce.payment.provider;

import com.bhavesh.ecommerce.config.PaymentProperties;
import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The simulated provider. No real money moves and no card details exist. */
class SimulatedPaymentProviderTest {

    private SimulatedPaymentProvider provider(SimulatedOutcome defaultOutcome) {
        return new SimulatedPaymentProvider(
                new PaymentProperties(defaultOutcome, Duration.ZERO));
    }

    @Test
    @DisplayName("SUCCESS returns a provider reference")
    void success() {
        var result = provider(SimulatedOutcome.SUCCESS)
                .charge("PAY-1", new BigDecimal("100.00"), "INR", SimulatedOutcome.SUCCESS);

        assertThat(result.successful()).isTrue();
        assertThat(result.providerReference()).startsWith("SIM-");
        assertThat(result.failureReason()).isNull();
        assertThat(result.retryable()).isFalse();
    }

    /**
     * A decline is a final answer, so the reservation is released and the customer
     * can try another instrument. An unreachable provider is an <em>unknown</em>
     * outcome, which is genuinely different: the charge may or may not have landed.
     * Marking it retryable is what lets support tell "the card was refused" from "we
     * could not ask".
     */
    @Test
    @DisplayName("DECLINE is a final answer and is not retryable")
    void decline() {
        var result = provider(SimulatedOutcome.SUCCESS)
                .charge("PAY-1", new BigDecimal("100.00"), "INR", SimulatedOutcome.DECLINE);

        assertThat(result.successful()).isFalse();
        assertThat(result.providerReference()).isNull();
        assertThat(result.failureReason()).isEqualTo("Card declined by issuer");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    @DisplayName("PROVIDER_ERROR is an unknown outcome and is marked retryable")
    void providerError() {
        var result = provider(SimulatedOutcome.SUCCESS)
                .charge("PAY-1", new BigDecimal("100.00"), "INR", SimulatedOutcome.PROVIDER_ERROR);

        assertThat(result.successful()).isFalse();
        assertThat(result.retryable())
                .as("distinct from a decline: we did not get an answer, rather than getting 'no'")
                .isTrue();
    }

    @Test
    @DisplayName("a null hint falls back to the configured default")
    void nullHintUsesTheDefault() {
        assertThat(provider(SimulatedOutcome.DECLINE)
                .charge("PAY-1", new BigDecimal("100.00"), "INR", null).successful())
                .isFalse();

        assertThat(provider(SimulatedOutcome.SUCCESS)
                .charge("PAY-1", new BigDecimal("100.00"), "INR", null).successful())
                .isTrue();
    }

    /**
     * A provider that failed one call in ten would make the suite flaky and a
     * demonstration unreproducible. "It only fails sometimes" is the worst possible
     * property for a payment path.
     */
    @ParameterizedTest
    @EnumSource(SimulatedOutcome.class)
    @DisplayName("the outcome is deterministic across repeated calls")
    void deterministic(SimulatedOutcome outcome) {
        var p = provider(SimulatedOutcome.SUCCESS);
        boolean first = p.charge("PAY-1", BigDecimal.ONE, "INR", outcome).successful();

        for (int i = 0; i < 50; i++) {
            assertThat(p.charge("PAY-" + i, BigDecimal.ONE, "INR", outcome).successful())
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("provider references are unique per charge")
    void uniqueReferences() {
        var p = provider(SimulatedOutcome.SUCCESS);
        var references = new java.util.HashSet<String>();

        for (int i = 0; i < 200; i++) {
            references.add(p.charge("PAY-" + i, BigDecimal.ONE, "INR",
                    SimulatedOutcome.SUCCESS).providerReference());
        }
        // 32 random bits; a handful of collisions in 200 would be extraordinary.
        assertThat(references).hasSizeGreaterThan(195);
    }

    @Test
    @DisplayName("the latency knob delays the answer without changing it")
    void simulatedLatencyDelaysButDoesNotAlter() {
        var slow = new SimulatedPaymentProvider(
                new PaymentProperties(SimulatedOutcome.SUCCESS, Duration.ofMillis(120)));

        long startedAt = System.nanoTime();
        var result = slow.charge("PAY-1", BigDecimal.ONE, "INR", null);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.successful()).isTrue();
        // A lower bound only. Asserting an upper bound would flake on a loaded machine.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100);
    }

    /**
     * Swallowing the interrupt without re-setting the flag would leave the thread
     * looking un-interrupted to everything upstream, so a shutdown would silently
     * fail to propagate.
     */
    @Test
    @DisplayName("an interrupt during the delay answers immediately and restores the flag")
    void interruptIsRestored() throws Exception {
        var slow = new SimulatedPaymentProvider(
                new PaymentProperties(SimulatedOutcome.SUCCESS, Duration.ofSeconds(30)));

        var flagAfterCall = new java.util.concurrent.atomic.AtomicBoolean();
        var succeeded = new java.util.concurrent.atomic.AtomicBoolean();

        Thread worker = new Thread(() -> {
            var result = slow.charge("PAY-1", BigDecimal.ONE, "INR", null);
            succeeded.set(result.successful());
            flagAfterCall.set(Thread.currentThread().isInterrupted());
        });

        worker.start();
        Thread.sleep(150);        // let it enter the sleep
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).as("the charge must return promptly after an interrupt").isFalse();
        assertThat(succeeded).as("the delay is a test fixture, not part of the outcome").isTrue();
        assertThat(flagAfterCall).as("the interrupt flag must be restored").isTrue();
    }
}
