package com.bhavesh.ecommerce.config;

import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code ecommerce.payment.*}.
 *
 * @param defaultOutcome  what the simulated provider returns when a request does
 *                        not ask for a specific outcome. <strong>Deterministic by
 *                        design</strong> — a random outcome would make the test
 *                        suite flaky and a demonstration unreproducible.
 * @param simulatedLatency artificial delay before the provider responds, used to
 *                        widen the window in concurrency tests. Zero by default.
 */
@ConfigurationProperties(prefix = "ecommerce.payment")
public record PaymentProperties(
        @DefaultValue("SUCCESS") SimulatedOutcome defaultOutcome,
        @DefaultValue("PT0S") Duration simulatedLatency
) {
}
