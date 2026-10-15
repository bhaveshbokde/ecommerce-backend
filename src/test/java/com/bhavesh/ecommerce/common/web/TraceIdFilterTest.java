package com.bhavesh.ecommerce.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Request correlation ids. */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("a request without a header gets a generated id in the MDC and the response")
    void generatesWhenAbsent() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        var response = new MockHttpServletResponse();
        var seenInMdc = new String[1];

        filter.doFilter(request, response,
                (req, res) -> seenInMdc[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seenInMdc[0]).isNotNull().hasSize(16);
        assertThat(response.getHeader(TraceIdFilter.HEADER))
                .as("the caller must be able to quote the id back")
                .isEqualTo(seenInMdc[0]);
    }

    @Test
    @DisplayName("an inbound id is honoured so a trace can span services")
    void honoursInboundId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader(TraceIdFilter.HEADER, "upstream-trace-123");
        var response = new MockHttpServletResponse();
        var seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seen[0]).isEqualTo("upstream-trace-123");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("upstream-trace-123");
    }

    /**
     * An attacker-supplied value lands in log files. A newline in it would let them
     * forge whole log entries — a CRLF injection into the log rather than the
     * response.
     */
    @Test
    @DisplayName("control characters are stripped so a header cannot forge log lines")
    void stripsControlCharacters() throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceIdFilter.HEADER, "abc\n2026-01-01 ERROR forged entry\rdef");
        var response = new MockHttpServletResponse();
        var seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seen[0]).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
        assertThat(seen[0]).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("an overlong inbound id is capped")
    void capsLength() throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceIdFilter.HEADER, "a".repeat(5_000));
        var response = new MockHttpServletResponse();
        var seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seen[0]).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "\n\r"})
    @DisplayName("an unusable inbound id is replaced with a fresh one")
    void unusableInboundIdIsReplaced(String header) throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceIdFilter.HEADER, header);
        var response = new MockHttpServletResponse();
        var seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get(TraceIdFilter.MDC_KEY));

        assertThat(seen[0]).isNotNull().hasSize(16).matches("[0-9a-f]{16}");
    }

    /**
     * The MDC is a {@link ThreadLocal} and servlet containers pool threads. A value
     * left behind reappears on the next request served by that thread, so every
     * subsequent log line carries a stranger's trace id — worse than having none,
     * because it looks authoritative.
     */
    @Test
    @DisplayName("the MDC is cleared after the request")
    void clearsTheMdcAfterwards() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the chain throws")
    void clearsTheMdcOnFailure() {
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("downstream blew up");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/x"),
                new MockHttpServletResponse(), exploding))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY))
                .as("a leaked id would mislabel the next request on this pooled thread")
                .isNull();
    }

    /**
     * Headers cannot be added once the response is committed, so a streaming or
     * early-committed response would otherwise lose the id entirely.
     */
    @Test
    @DisplayName("the header is set before the chain runs, not after")
    void headerIsSetBeforeTheChain() throws Exception {
        var response = new MockHttpServletResponse();
        var headerDuringChain = new String[1];

        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response,
                (req, res) -> headerDuringChain[0] =
                        ((MockHttpServletResponse) res).getHeader(TraceIdFilter.HEADER));

        assertThat(headerDuringChain[0]).isNotNull();
    }
}
