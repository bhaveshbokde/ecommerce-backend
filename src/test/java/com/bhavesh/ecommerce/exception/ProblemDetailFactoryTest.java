package com.bhavesh.ecommerce.exception;

import com.bhavesh.ecommerce.exception.ProblemDetailFactory.FieldViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 9457 Problem Details construction. */
class ProblemDetailFactoryTest {

    @Test
    @DisplayName("carries the standard members plus the extensions clients branch on")
    void carriesStandardAndExtensionMembers() {
        var problem = ProblemDetailFactory.create(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid", "/api/v1/products", "abc123def456");

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Validation Failed");
        assertThat(problem.getDetail()).isEqualTo("One or more fields are invalid");
        assertThat(problem.getType().toString())
                .isEqualTo("https://api.ecommerce.local/problems/validation-failed");
        assertThat(problem.getInstance().toString()).isEqualTo("/api/v1/products");

        assertThat(problem.getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("traceId", "abc123def456")
                .containsKey("timestamp");
    }

    @Test
    @DisplayName("field violations are omitted when there are none, rather than an empty array")
    void omitsEmptyViolations() {
        assertThat(ProblemDetailFactory.create(ErrorCode.RESOURCE_NOT_FOUND, "Gone", "/x", "t")
                .getProperties()).doesNotContainKey("errors");

        assertThat(ProblemDetailFactory.create(ErrorCode.VALIDATION_FAILED, "Bad", "/x", "t", List.of())
                .getProperties()).doesNotContainKey("errors");
    }

    @Test
    void includesViolationsWhenPresent() {
        var problem = ProblemDetailFactory.create(ErrorCode.VALIDATION_FAILED, "Bad", "/x", "t",
                List.of(FieldViolation.of("price", "-10", "must be greater than 0")));

        assertThat(problem.getProperties()).containsKey("errors");
    }

    /**
     * {@code type} is an identifier first and a dereferenceable document second
     * (RFC 9457 §3.1.1), but the namespace must stay stable so a future
     * documentation site can be published at these URLs without changing any
     * response.
     */
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("every error code produces a well-formed type URI and title")
    void everyCodeHasAUsableTypeAndTitle(ErrorCode code) {
        var problem = ProblemDetailFactory.create(code, code.defaultDetail(), "/x", "t");

        assertThat(problem.getType().toString())
                .startsWith("https://api.ecommerce.local/problems/")
                .doesNotContain("_")
                .isLowerCase();
        assertThat(problem.getTitle()).isNotBlank().doesNotContain("_");
        assertThat(problem.getStatus()).isEqualTo(code.status().value());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("no error code maps to a status outside 4xx/5xx")
    void everyCodeIsAnErrorStatus(ErrorCode code) {
        HttpStatus status = code.status();
        assertThat(status.is4xxClientError() || status.is5xxServerError()).isTrue();
    }

    @Test
    @DisplayName("trace ids are unique and short enough to quote over the phone")
    void traceIds() {
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(ProblemDetailFactory.newTraceId());
        }
        assertThat(ids).hasSize(1_000);
        assertThat(ids.iterator().next()).hasSize(16).matches("[0-9a-f]{16}");
    }

    /**
     * Validation error bodies end up in logs, bug reports and screenshots. A
     * rejected password echoed back into one of those is a credential in a log
     * aggregator.
     */
    @ParameterizedTest
    @ValueSource(strings = {"password", "newPassword", "user.password", "clientSecret",
            "refreshToken", "accessToken", "API_TOKEN"})
    @DisplayName("a rejected value is redacted for any field whose name suggests a secret")
    void redactsSecrets(String field) {
        assertThat(FieldViolation.of(field, "hunter2-the-real-password", "too short")
                .rejectedValue()).isEqualTo("***");
    }

    @Test
    @DisplayName("an ordinary field keeps its rejected value, which is what makes the error useful")
    void keepsOrdinaryValues() {
        assertThat(FieldViolation.of("price", "-10", "must be greater than 0").rejectedValue())
                .isEqualTo("-10");
    }

    @Test
    @DisplayName("an overlong value is truncated so one bad field cannot bloat the response")
    void truncatesLongValues() {
        String rejected = FieldViolation.of("name", "x".repeat(500), "too long").rejectedValue();

        assertThat(rejected).hasSize(120).endsWith("...");
    }

    @Test
    void nullValueStaysNull() {
        assertThat(FieldViolation.of("name", null, "is required").rejectedValue()).isNull();
    }

    @Test
    void nullFieldNameIsHandled() {
        assertThat(FieldViolation.of(null, "value", "message").rejectedValue()).isEqualTo("value");
    }
}
