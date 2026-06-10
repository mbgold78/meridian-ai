package com.meridian.substrate.app.infrastructure.reactive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Reactor MDC propagation seam without requiring the SSE chat
 * stack (which lands in later chunks): the JVM-global {@code Hooks.onEachOperator}
 * lifter registered by {@link ReactiveMdcConfig} must re-apply the MDC map
 * captured in Reactor Context on signals that run on scheduler threads, and
 * must be a strict no-op when the context key is absent.
 */
class MdcContextLifterTest {

    private final ReactiveMdcConfig config = new ReactiveMdcConfig();

    @BeforeEach
    void registerHook() {
        config.registerLifter();
    }

    @AfterEach
    void deregisterHook() {
        config.deregisterLifter();
        MDC.clear();
    }

    @Test
    void mdcFromReactorContext_isAppliedAcrossSchedulerBoundary() {
        Map<String, String> mdcSnapshot = Map.of("requestId", "rid-123");
        List<String> observedRequestIds = new CopyOnWriteArrayList<>();
        List<String> observedThreads = new CopyOnWriteArrayList<>();

        Flux.just("a", "b", "c")
                .publishOn(Schedulers.parallel())
                .doOnNext(v -> {
                    observedRequestIds.add(String.valueOf(MDC.get("requestId")));
                    observedThreads.add(Thread.currentThread().getName());
                })
                .contextWrite(ctx -> ctx.put(MdcContextLifter.MDC_CONTEXT_KEY, mdcSnapshot))
                .blockLast(Duration.ofSeconds(10));

        assertThat(observedRequestIds)
                .as("every emission on the scheduler thread must see the captured requestId")
                .containsExactly("rid-123", "rid-123", "rid-123");
        assertThat(observedThreads)
                .as("emissions must actually have crossed onto a Reactor scheduler thread "
                        + "(otherwise this test proves nothing)")
                .allSatisfy(name -> assertThat(name).startsWith("parallel"));
    }

    @Test
    void absentContextKey_neverClobbersExistingMdc() {
        MDC.put("requestId", "outer-rid");
        List<String> observed = new ArrayList<>();

        Flux.just("x")
                .doOnNext(v -> observed.add(MDC.get("requestId")))
                .blockLast(Duration.ofSeconds(10));

        assertThat(observed).containsExactly("outer-rid");
        assertThat(MDC.get("requestId"))
                .as("lifter must be a no-op when the context key is absent")
                .isEqualTo("outer-rid");
    }
}
