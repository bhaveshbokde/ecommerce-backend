package com.bhavesh.ecommerce.common.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates the human-facing identifiers {@code ORD-20261014-7F3A91} and
 * {@code PAY-20261014-3C81D0}.
 *
 * <h2>Why not just use the primary key</h2>
 * A sequential surrogate id published to customers leaks the business's order
 * volume — order 1,204 today and 1,391 tomorrow says exactly how many orders were
 * taken — and invites someone to try the neighbouring number. These references
 * carry a date, which is genuinely useful in a support conversation, plus 24 bits
 * of randomness, which is not guessable in bulk.
 *
 * <h2>Why SecureRandom</h2>
 * {@code Random} is a linear congruential generator: observing a couple of outputs
 * reveals the internal state and therefore every subsequent value. Predicting
 * order references is not catastrophic here — every lookup is still
 * ownership-scoped — but there is no reason to choose the predictable generator
 * when the cost is a few microseconds.
 *
 * <h2>Collisions</h2>
 * 24 random bits per day means collisions become likely around a few thousand
 * orders in one day (birthday bound). {@code uq_orders_order_number} catches one,
 * and the caller retries; the retry loop is in {@code OrderService} rather than
 * here so this class stays a pure function of clock and randomness.
 *
 * <p>The {@link Clock} is injected so tests can pin the date without waiting for
 * midnight.
 */
@Component
public class ReferenceGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("uuuuMMdd");
    private static final char[] ALPHABET = "0123456789ABCDEF".toCharArray();
    private static final int RANDOM_CHARS = 6;   // 6 hex characters = 24 bits

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ReferenceGenerator() {
        this(Clock.systemDefaultZone());
    }

    public ReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    public String orderNumber() {
        return build("ORD");
    }

    public String paymentReference() {
        return build("PAY");
    }

    private String build(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 1 + 8 + 1 + RANDOM_CHARS);
        sb.append(prefix).append('-')
          .append(LocalDate.now(clock).format(DATE_PART)).append('-');
        for (int i = 0; i < RANDOM_CHARS; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
