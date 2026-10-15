package com.bhavesh.ecommerce.exception;

/**
 * A syntactically valid request that a domain rule rejects — an empty cart at
 * checkout, an inactive product added to a cart, an illegal order status
 * transition.
 *
 * <p>Maps to 422 Unprocessable Entity by default: the server understood the
 * request and the body was well-formed, but the instruction cannot be carried
 * out. That is a different situation from 400 (could not parse or bind the
 * input), and clients routinely need to tell the two apart.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String detail) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, detail);
    }

    public BusinessRuleException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
