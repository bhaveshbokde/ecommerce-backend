package com.bhavesh.ecommerce.security;

/**
 * Distinguishes the two tokens this application issues.
 *
 * <p>The value is written into a custom {@code typ} claim and checked on every
 * verification. Without it, a long-lived refresh token presented in an
 * {@code Authorization} header would be accepted as a bearer credential — a
 * token-confusion bug that silently turns a 7-day token into a 7-day API key.
 */
public enum TokenType {

    /** Short-lived credential presented in the {@code Authorization} header. */
    ACCESS,

    /** Long-lived credential accepted <em>only</em> by {@code POST /api/v1/auth/refresh}. */
    REFRESH
}
