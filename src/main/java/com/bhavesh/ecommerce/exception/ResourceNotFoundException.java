package com.bhavesh.ecommerce.exception;

/**
 * Thrown when a resource does not exist <em>or</em> exists but belongs to a
 * different user.
 *
 * <p>Deliberately 404 for both cases. Returning 403 for "someone else's order"
 * would confirm that the id exists, turning every endpoint into an existence
 * oracle an attacker can enumerate. Every ownership-scoped lookup in this
 * application is written as {@code findByIdAndUserId(...)}, so "missing" and
 * "not yours" are genuinely indistinguishable in the code too — this is not a
 * cosmetic choice applied after the fact.
 *
 * <p>See docs/ARCHITECTURE.md.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s %s was not found".formatted(resource, id));
    }

    public ResourceNotFoundException(String detail) {
        super(ErrorCode.RESOURCE_NOT_FOUND, detail);
    }
}
