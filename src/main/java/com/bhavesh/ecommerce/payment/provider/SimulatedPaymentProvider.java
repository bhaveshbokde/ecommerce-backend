package com.bhavesh.ecommerce.payment.provider;

import com.bhavesh.ecommerce.config.PaymentProperties;
import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A payment provider that moves no money.
 *
 * <p><strong>Nothing here contacts a real gateway, and no real card details exist
 * anywhere in this application.</strong> That is a deliberate scope decision, not
 * an omission: a real integration needs merchant credentials, a publicly reachable
 * webhook endpoint and PCI considerations, none of which belong in a portfolio
 * project — and none of which would teach anything the simulation does not.
 *
 * <p>What the simulation preserves is everything that makes payments hard in a
 * backend: the idempotency key and the unique index behind it, the stock
 * reservation that must be released when a charge fails, the order state machine,
 * and the distinction between "declined" and "could not ask".
 *
 * <h2>Deterministic, not random</h2>
 * The outcome is chosen by configuration or by an explicit per-request hint. A
 * provider that failed one call in ten would make the test suite flaky and a
 * demonstration unreproducible, and "it only fails sometimes" is the worst
 * possible property for a payment path.
 *
 * <h2>The latency knob</h2>
 * {@code ecommerce.payment.simulated-latency} inserts a delay before answering.
 * Zero by default; a concurrency test raises it to widen the window between
 * reserving stock and resolving the charge, which is precisely where a double-pay
 * bug would hide.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final PaymentProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SimulatedPaymentProvider(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChargeResult charge(String reference, BigDecimal amount, String currency,
                               SimulatedOutcome requestedOutcome) {

        SimulatedOutcome outcome = requestedOutcome != null
                ? requestedOutcome
                : properties.defaultOutcome();

        applySimulatedLatency();

        log.info("Simulated charge: reference={} amount={} {} outcome={}",
                reference, amount, currency, outcome);

        return switch (outcome) {
            case SUCCESS -> ChargeResult.succeeded("SIM-" + randomHex());
            case DECLINE -> ChargeResult.declined("Card declined by issuer");
            case PROVIDER_ERROR -> ChargeResult.error("Payment provider did not respond");
        };
    }

    /**
     * Restores the interrupt flag before returning.
     *
     * <p>Swallowing an {@link InterruptedException} without re-interrupting leaves
     * the thread looking un-interrupted to everything upstream, so a shutdown or a
     * cancellation silently fails to propagate. Treated as "stop waiting, answer
     * now" rather than as a failed charge, because the delay is a test fixture and
     * has nothing to do with the outcome.
     */
    private void applySimulatedLatency() {
        long millis = properties.simulatedLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Simulated latency interrupted; answering immediately");
        }
    }

    private String randomHex() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
