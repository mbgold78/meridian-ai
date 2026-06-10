package com.meridian.substrate.app.infrastructure.reactive;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

/**
 * Registers a global Reactor operator hook that wires the {@link MdcContextLifter}
 * into every {@code Flux} / {@code Mono} subscriber chain in the JVM.
 *
 * <p><b>Blast radius is intentional and JVM-global.</b> {@code Hooks.onEachOperator}
 * applies to every reactive operator in the application, not only to the SSE chat
 * stream. This is the right default for the current codebase: every reactive path
 * here ultimately serves a request that started on a Tomcat thread holding MDC
 * (request-id, persona) that we want preserved across scheduler shifts.
 *
 * <p>If a future module ever introduces a reactive flow where this MDC propagation
 * would be incorrect (e.g. background processing that should not inherit the
 * caller's request-id), that module is responsible for opting out explicitly —
 * either by clearing the {@link MdcContextLifter#MDC_CONTEXT_KEY} from its
 * Reactor Context, or by clearing MDC on the relevant scheduler. Do not flip the
 * default to "off" without auditing every reactive caller.
 */
@Configuration
public class ReactiveMdcConfig {

    private static final Logger log = LoggerFactory.getLogger(ReactiveMdcConfig.class);

    static final String LIFTER_KEY = "substrate-mdc-context-lifter";

    @PostConstruct
    public void registerLifter() {
        Hooks.onEachOperator(LIFTER_KEY,
                Operators.lift((scannable, subscriber) -> new MdcContextLifter<>(subscriber)));
        log.info("Registered MDC context lifter as Reactor onEachOperator hook [key={}]", LIFTER_KEY);
    }

    @PreDestroy
    public void deregisterLifter() {
        Hooks.resetOnEachOperator(LIFTER_KEY);
        log.info("Deregistered MDC context lifter [key={}]", LIFTER_KEY);
    }
}
