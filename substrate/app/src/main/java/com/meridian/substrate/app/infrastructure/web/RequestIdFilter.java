package com.meridian.substrate.app.infrastructure.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique request ID to every inbound request and places it in the MDC
 * for structured logging. Also returns it as a response header.
 *
 * <p><b>MDC contract — read before changing the {@code finally} block.</b>
 * The {@code requestId} is cleared in {@code finally} so that Tomcat worker threads
 * do not leak request-scoped state into the next request that reuses them. This is
 * correct for synchronous handlers, where the filter chain returns only after the
 * response is fully written.
 *
 * <p>Reactive handlers (controller methods returning {@code Flux} / {@code Mono})
 * subscribe to their reactive type <em>after</em> this filter has cleared MDC — the
 * controller method returns immediately, control unwinds to this filter, and the
 * scheduler-bound emission then runs on a thread that has never seen the request's
 * MDC. Such handlers MUST capture {@link MDC#getCopyOfContextMap()} at controller
 * entry and inject it into the Reactor {@link reactor.util.context.Context} via
 * {@code .contextWrite(ctx -&gt; ctx.put(MdcContextLifter.MDC_CONTEXT_KEY, snapshot))}
 * before returning. The JVM-global {@code Hooks.onEachOperator} lifter
 * ({@code com.meridian.substrate.app.infrastructure.reactive.MdcContextLifter},
 * registered by {@code ReactiveMdcConfig}) then re-applies that map on every
 * signal across scheduler boundaries. See {@code ChatController.chatStream} for
 * the canonical pattern.
 *
 * <p>Do not "fix" the synchronous flow by deleting the {@code finally MDC.remove(...)}
 * — it would silently leak request IDs across requests on reused worker threads.
 */
@Component
@Order(1)
public class RequestIdFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        try {
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
