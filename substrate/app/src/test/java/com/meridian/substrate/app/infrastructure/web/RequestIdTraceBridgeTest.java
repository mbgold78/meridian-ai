package com.meridian.substrate.app.infrastructure.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 3 requestId→trace bridge (plan §8): when tracing is on (a
 * {@link Tracer} bean exists), the filter stamps the generated requestId on
 * the current server span as {@code substrate.request_id} — the SAME id that
 * goes to the MDC and the {@code X-Request-Id} header, so logs and traces
 * join. With no tracer (tracing off / legacy constructor) the filter is
 * byte-for-byte the pre-Stage-3 behavior.
 */
class RequestIdTraceBridgeTest {

    @Test
    @SuppressWarnings("unchecked")
    void tagsTheCurrentSpanWithTheSameRequestIdItReturnsInTheHeader() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter(provider)
                .doFilter(new MockHttpServletRequest(), response, mock(FilterChain.class));

        ArgumentCaptor<String> tagged = ArgumentCaptor.forClass(String.class);
        verify(span).tag(eq(RequestIdFilter.SPAN_TAG), tagged.capture());
        assertThat(tagged.getValue()).isEqualTo(response.getHeader("X-Request-Id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noTracerBean_filterStillSetsHeaderAndNeverThrows() throws Exception {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter(provider)
                .doFilter(new MockHttpServletRequest(), response, mock(FilterChain.class));

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    void legacyConstructor_behavesExactlyAsBeforeStage3() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter()
                .doFilter(new MockHttpServletRequest(), response, mock(FilterChain.class));
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }
}
