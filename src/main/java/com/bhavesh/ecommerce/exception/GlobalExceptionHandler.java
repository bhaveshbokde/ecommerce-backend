package com.bhavesh.ecommerce.exception;

import com.bhavesh.ecommerce.exception.ProblemDetailFactory.FieldViolation;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates every exception that escapes a controller into one RFC 9457
 * Problem Details body.
 *
 * <p>{@code @RestControllerAdvice} registers these handlers across all
 * controllers; Spring selects the handler whose parameter type most closely
 * matches the thrown exception, so the specific handlers below always win over
 * {@link #handleUnexpected}.
 *
 * <h2>Logging policy</h2>
 * <ul>
 *   <li>4xx caused by the client are logged at WARN <em>without</em> a stack
 *       trace — they are expected traffic, and ten thousand bad logins should not
 *       produce ten thousand stack traces.</li>
 *   <li>5xx are logged at ERROR <em>with</em> the stack trace, keyed by the same
 *       {@code traceId} returned to the caller.</li>
 *   <li>The stack trace never enters the response. The caller gets an id to
 *       quote; the detail stays server-side.</li>
 * </ul>
 *
 * <p>Exceptions thrown inside the security filter chain never reach this class —
 * filters run before the {@code DispatcherServlet}. Those are handled by
 * {@link com.bhavesh.ecommerce.security.RestAuthenticationEntryPoint} and
 * {@link com.bhavesh.ecommerce.security.RestAccessDeniedHandler}, which emit the
 * identical shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------- application --

    /** Every deliberately raised error; the code already knows its HTTP status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        return respond(code, ex.getMessage(), request, code.status().is5xxServerError() ? ex : null);
    }

    /**
     * Insufficient stock, with machine-readable detail.
     *
     * <p>Handled separately from the generic {@link ApiException} so the response
     * can carry {@code productId}, {@code requested} and {@code available} — a
     * client can then show "only 2 left" without a second round trip.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException ex,
                                                                 HttpServletRequest request) {
        String traceId = traceId();
        log.warn("INSUFFICIENT_STOCK [traceId={}] product={} requested={} available={}",
                traceId, ex.productId(), ex.requested(), ex.available());

        ProblemDetail problem = ProblemDetailFactory.create(
                ex.errorCode(), ex.getMessage(), request.getRequestURI(), traceId);
        problem.setProperty("productId", ex.productId());
        problem.setProperty("requested", ex.requested());
        problem.setProperty("available", ex.available());

        return ResponseEntity.status(ex.errorCode().status()).body(problem);
    }

    // -------------------------------------------------------------- validation --

    /** {@code @Valid} failure on a {@code @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        return respondWithFields(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultDetail(), request, violations);
    }

    /** {@code @Validated} failure on a method parameter (query params, path variables). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> FieldViolation.of(lastNode(v.getPropertyPath().toString()),
                        v.getInvalidValue(), v.getMessage()))
                .toList();
        return respondWithFields(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultDetail(), request, violations);
    }

    /** Unparseable JSON, or a value that cannot be coerced into the target type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        // Surface *which* field failed to coerce, but never echo the raw body:
        // it may contain a password on the registration endpoint.
        String detail = "Request body could not be parsed";
        if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            detail = "Invalid value for field '%s'; expected %s".formatted(
                    ife.getPath().get(ife.getPath().size() - 1).getFieldName(),
                    ife.getTargetType().getSimpleName());
        }
        return respond(ErrorCode.MALFORMED_REQUEST, detail, request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER,
                "Required parameter '%s' is missing".formatted(ex.getParameterName()), request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String required = ex.getRequiredType() == null ? "the expected type"
                                                       : ex.getRequiredType().getSimpleName();
        return respond(ErrorCode.INVALID_PARAMETER,
                "Parameter '%s' must be a valid %s".formatted(ex.getName(), required), request, null);
    }

    // ---------------------------------------------------------------- security --
    // Reached only when the exception originates inside a controller or service,
    // i.e. after the filter chain has already authenticated the caller.

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex,
                                                              HttpServletRequest request) {
        // Deliberately identical for "unknown email" and "wrong password", so the
        // endpoint cannot be used to enumerate registered addresses.
        return respond(ErrorCode.INVALID_CREDENTIALS,
                ErrorCode.INVALID_CREDENTIALS.defaultDetail(), request, null);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ProblemDetail> handleDisabled(DisabledException ex, HttpServletRequest request) {
        return respond(ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED.defaultDetail(), request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return respond(ErrorCode.AUTHENTICATION_REQUIRED,
                ErrorCode.AUTHENTICATION_REQUIRED.defaultDetail(), request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex,
                                                            HttpServletRequest request) {
        return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultDetail(), request, null);
    }

    // ----------------------------------------------------------------- database --

    /**
     * A unique or check constraint rejected the write.
     *
     * <p>Services pre-check for duplicates, but that check and the insert are not
     * atomic: two concurrent requests can both pass it. The database constraint is
     * the real guarantee, and this handler turns the resulting race into a clean
     * 409 instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        String traceId = traceId();
        String root = rootMessage(ex);
        log.warn("Data integrity violation [traceId={}] on {} {}: {}",
                traceId, request.getMethod(), request.getRequestURI(), root);

        // Map the specific constraints we know about to a precise code; anything
        // else stays a generic conflict rather than leaking the constraint name.
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        String detail = "The operation conflicts with an existing record";
        if (root != null) {
            if (root.contains("uq_users_email_lower")) {
                code = ErrorCode.EMAIL_ALREADY_REGISTERED;
                detail = code.defaultDetail();
            } else if (root.contains("uq_products_sku")) {
                code = ErrorCode.DUPLICATE_SKU;
                detail = code.defaultDetail();
            } else if (root.contains("uq_categories_name_lower") || root.contains("uq_categories_slug")) {
                code = ErrorCode.DUPLICATE_CATEGORY;
                detail = code.defaultDetail();
            } else if (root.contains("ck_inventory_available_non_negative")) {
                code = ErrorCode.INSUFFICIENT_STOCK;
                detail = "Not enough stock available";
            } else if (root.contains("uq_payments_order_active")
                    || root.contains("uq_payments_user_idempotency")) {
                code = ErrorCode.PAYMENT_ALREADY_EXISTS;
                detail = code.defaultDetail();
            }
        }
        return ResponseEntity.status(code.status())
                .body(ProblemDetailFactory.create(code, detail, request.getRequestURI(), traceId));
    }

    /** Lost an optimistic-lock race: the row changed between read and write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.defaultDetail(), request, null);
    }

    // -------------------------------------------------------------- HTTP basics --

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                "%s is not supported for this endpoint".formatted(ex.getMethod()), request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type '%s' is not supported".formatted(ex.getContentType()), request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint matches this path", request, null);
    }

    // ------------------------------------------------------------------ fallback --

    /**
     * Last resort. Anything landing here is a bug or an unavailable dependency.
     *
     * <p>The response body is deliberately generic — an exception's own message
     * can contain a JDBC URL, a file path or part of a query — while the full
     * stack trace goes to the log under the returned {@code traceId}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultDetail(), request, ex);
    }

    // -------------------------------------------------------------------- helpers --

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail,
                                                  HttpServletRequest request, Exception toLog) {
        String traceId = traceId();
        if (toLog != null) {
            log.error("Unhandled failure [traceId={}] on {} {}",
                    traceId, request.getMethod(), request.getRequestURI(), toLog);
        } else if (code.status().is4xxClientError()) {
            log.warn("{} [traceId={}] on {} {}: {}",
                    code, traceId, request.getMethod(), request.getRequestURI(), detail);
        }
        return ResponseEntity.status(code.status())
                .body(ProblemDetailFactory.create(code, detail, request.getRequestURI(), traceId));
    }

    private ResponseEntity<ProblemDetail> respondWithFields(ErrorCode code, String detail,
                                                            HttpServletRequest request,
                                                            List<FieldViolation> violations) {
        String traceId = traceId();
        log.warn("{} [traceId={}] on {} {}: {} field error(s)",
                code, traceId, request.getMethod(), request.getRequestURI(), violations.size());
        return ResponseEntity.status(code.status())
                .body(ProblemDetailFactory.create(code, detail, request.getRequestURI(), traceId, violations));
    }

    private FieldViolation toViolation(FieldError fieldError) {
        return FieldViolation.of(fieldError.getField(), fieldError.getRejectedValue(),
                fieldError.getDefaultMessage());
    }

    /** Reuses the MDC trace id when one is present so logs and responses agree. */
    private String traceId() {
        String fromMdc = MDC.get("traceId");
        return fromMdc != null ? fromMdc : ProblemDetailFactory.newTraceId();
    }

    private String lastNode(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return idx < 0 ? propertyPath : propertyPath.substring(idx + 1);
    }

    private String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
