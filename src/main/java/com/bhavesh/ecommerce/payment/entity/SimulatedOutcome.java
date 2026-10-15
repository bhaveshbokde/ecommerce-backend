package com.bhavesh.ecommerce.payment.entity;

/**
 * What the simulated payment provider should do with a charge request.
 *
 * <p>No real money moves anywhere in this application. A real gateway integration
 * would mean credentials, webhooks, a public callback URL and a sandbox account —
 * none of which belong in a portfolio project, and none of which teach anything
 * the simulation does not.
 *
 * <p>What <em>is</em> real is everything around the provider call: the idempotency
 * key, the unique index that enforces it, the stock reservation that must be
 * released when a charge fails, and the order state machine. Those are where the
 * interesting bugs live, and a deterministic provider is what makes them testable.
 *
 * <p>The outcome is chosen explicitly — by configuration, or per-request in a
 * non-production profile — rather than randomly. A random provider would make the
 * test suite flaky and a demonstration unreproducible.
 */
public enum SimulatedOutcome {

    /** The charge is accepted. The default. */
    SUCCESS,

    /** The charge is declined — the provider answered, and said no. */
    DECLINE,

    /**
     * The provider could not be reached at all.
     *
     * <p>Distinct from {@link #DECLINE} on purpose: a decline is a final answer, so
     * the reservation is released and the customer can try another instrument. A
     * provider error is an <em>unknown</em> outcome, which is the genuinely hard
     * case — the charge may or may not have landed. This application treats it as
     * a failure and releases stock, and records that choice in the payment row so
     * a reconciliation job could revisit it.
     */
    PROVIDER_ERROR
}
