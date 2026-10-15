package com.bhavesh.ecommerce.order.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fulfilment lifecycle of an order, with the legal transitions encoded.
 *
 * <p>Keeping the transition table <em>in the enum</em> rather than as an if-else
 * chain inside a service means the rule exists in exactly one place. Adding a
 * state is a change to this map; no service needs to be audited for a missing
 * branch.
 *
 * <pre>
 *   CREATED ──► CONFIRMED ──► PROCESSING ──► SHIPPED ──► DELIVERED
 *      │            │              │
 *      └────────────┴──────────────┴──────► CANCELLED
 * </pre>
 *
 * <p>Cancellation stops being legal at SHIPPED: once the parcel has left, the
 * remedy is a return, not a cancellation, and they are different operations with
 * different financial consequences. DELIVERED and CANCELLED are terminal.
 *
 * <p>This is separate from {@code payment_status} on purpose. CONFIRMED + REFUNDED
 * is a real state; one enum covering both lifecycles would need a constant for
 * every combination.
 */
public enum OrderStatus {

    /** Placed; stock reserved; payment not yet attempted. */
    CREATED,

    /** Payment succeeded. The order is now a commitment. */
    CONFIRMED,

    /** Being picked and packed. */
    PROCESSING,

    /** Handed to the carrier. No longer cancellable. */
    SHIPPED,

    /** Received by the customer. Terminal. */
    DELIVERED,

    /** Cancelled before shipping; reserved stock released. Terminal. */
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED;

    static {
        // Built in a static block rather than per-constant fields: a constant
        // cannot reference another constant that has not been initialised yet,
        // and CREATED must name CONFIRMED.
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(CREATED, EnumSet.of(CONFIRMED, CANCELLED));
        transitions.put(CONFIRMED, EnumSet.of(PROCESSING, CANCELLED));
        transitions.put(PROCESSING, EnumSet.of(SHIPPED, CANCELLED));
        transitions.put(SHIPPED, EnumSet.of(DELIVERED));
        transitions.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));

        // Each set is wrapped individually, not just the map. unmodifiableMap stops
        // put and remove on the map itself, but hands out the *same* mutable EnumSet
        // instances — so a caller of allowedTransitions() could add(CANCELLED) to the
        // SHIPPED set and grant themselves a transition for the lifetime of the JVM.
        // The map being read-only says nothing about what is inside it.
        transitions.replaceAll((status, allowed) -> Collections.unmodifiableSet(allowed));
        ALLOWED = Collections.unmodifiableMap(transitions);
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    public Set<OrderStatus> allowedTransitions() {
        return ALLOWED.get(this);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Stock is still held by the warehouse, so cancelling must release it. */
    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }
}
