package com.bhavesh.ecommerce.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error identifiers returned in every error body.
 *
 * <p>Clients branch on {@code code}; the human-readable {@code detail} may be
 * reworded freely without breaking them. Each constant also carries the HTTP
 * status it maps to, which keeps status selection a property of the error rather
 * than an if-else chain in the exception handler.
 */
public enum ErrorCode {

    // ---- 400 ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is invalid"),

    // ---- 401 ----
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The access token is invalid"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The refresh token is invalid, expired or revoked"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "This account is disabled"),

    // ---- 403 ----
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to perform this action"),

    // ---- 404 ----
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource does not exist"),

    // ---- 405 / 415 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    // ---- 409 ----
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "An account with this email already exists"),
    DUPLICATE_SKU(HttpStatus.CONFLICT, "A product with this SKU already exists"),
    DUPLICATE_CATEGORY(HttpStatus.CONFLICT, "A category with this name already exists"),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "This category still has products and cannot be deleted"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Not enough stock available"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified by another request; retry"),
    PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "A payment is already in progress for this order"),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "This order can no longer be cancelled"),

    // ---- 422 ----
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "The request violates a business rule"),
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "That order status transition is not allowed"),
    EMPTY_CART(HttpStatus.UNPROCESSABLE_ENTITY, "The cart is empty"),
    PRODUCT_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "This product is not available for purchase"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This idempotency key was already used with a different request body"),

    // ---- 500 / 502 / 503 ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    PAYMENT_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "The payment provider could not be reached"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required downstream service is unavailable");

    private final HttpStatus status;
    private final String defaultDetail;

    ErrorCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
