package com.bhavesh.ecommerce.exception;

import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds every error body this application returns, in RFC 9457 Problem Details
 * form.
 *
 * <p>Spring Framework 6 ships {@link ProblemDetail}, so the standard fields
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance})
 * come for free and serialise with the correct
 * {@code application/problem+json} content type. RFC 9457 permits extension
 * members, which is where {@code code}, {@code traceId}, {@code timestamp} and
 * {@code errors} live.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "type":      "https://api.ecommerce.local/problems/validation-failed",
 *   "title":     "Validation Failed",
 *   "status":    400,
 *   "detail":    "One or more fields are invalid",
 *   "instance":  "/api/v1/products",
 *   "code":      "VALIDATION_FAILED",
 *   "traceId":   "0f6b1c2d9a3e4f58",
 *   "timestamp": "2026-10-14T09:31:22.481Z",
 *   "errors": [ { "field": "price", "rejectedValue": "-10", "message": "must be greater than 0" } ]
 * }
 * }</pre>
 *
 * <p>Clients branch on {@code code}, which is stable; {@code detail} may be
 * reworded freely. {@code traceId} is echoed so a user-reported failure can be
 * correlated with a server log line without exposing a stack trace.
 */
public final class ProblemDetailFactory {

    /**
     * Base URI for the {@code type} member. It need not resolve — RFC 9457 says a
     * type URI is an identifier first and a dereferenceable document second — but
     * using a stable namespace means a future documentation site can be published
     * at these URLs without changing any response.
     */
    private static final String TYPE_BASE = "https://api.ecommerce.local/problems/";

    private ProblemDetailFactory() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    public static ProblemDetail create(ErrorCode code, String detail, String path, String traceId) {
        return create(code, detail, path, traceId, null);
    }

    /**
     * @param errors field-level violations; omitted from the body when null or empty
     */
    public static ProblemDetail create(ErrorCode code, String detail, String path, String traceId,
                                       List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(TYPE_BASE + slug(code)));
        problem.setTitle(title(code));
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceId);
        problem.setProperty("timestamp", Instant.now().toString());
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    /** A short correlation id, also returned in the {@code X-Trace-Id} response header. */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** {@code VALIDATION_FAILED} becomes {@code validation-failed}. */
    private static String slug(ErrorCode code) {
        return code.name().toLowerCase().replace('_', '-');
    }

    /** {@code VALIDATION_FAILED} becomes {@code Validation Failed}. */
    private static String title(ErrorCode code) {
        String[] words = code.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(code.name().length());
        for (String word : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return sb.toString();
    }

    /**
     * One rejected field.
     *
     * <p>{@code rejectedValue} is rendered as a string and is redacted for any
     * field whose name suggests a secret — validation error bodies end up in
     * logs, bug reports and screenshots.
     */
    public record FieldViolation(String field, String rejectedValue, String message) {

        public static FieldViolation of(String field, Object rejectedValue, String message) {
            return new FieldViolation(field, redact(field, rejectedValue), message);
        }

        private static String redact(String field, Object value) {
            if (field != null) {
                String lower = field.toLowerCase();
                if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
                    return "***";
                }
            }
            if (value == null) {
                return null;
            }
            String text = value.toString();
            return text.length() > 120 ? text.substring(0, 117) + "..." : text;
        }
    }
}
