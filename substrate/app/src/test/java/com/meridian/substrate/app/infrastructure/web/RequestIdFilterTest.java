package com.meridian.substrate.app.infrastructure.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setsMdcDuringChain_setsHeader_andClearsMdcAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get("requestId"));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get())
                .as("requestId must be present in MDC while the chain executes")
                .isNotBlank();
        assertThatCode(() -> UUID.fromString(mdcDuringChain.get()))
                .as("requestId must be a valid UUID")
                .doesNotThrowAnyException();
        assertThat(response.getHeader("X-Request-Id"))
                .as("response must carry the same requestId as a header")
                .isEqualTo(mdcDuringChain.get());
        assertThat(MDC.get("requestId"))
                .as("MDC must be cleared after the chain to avoid leaking across pooled threads")
                .isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { throw new RuntimeException("downstream failure"); };

        assertThatCode(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
