package com.meridian.substrate.app.infrastructure.reactive;

import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.util.Map;

/**
 * Per-subscriber wrapper that re-applies MDC from the Reactor {@link Context}
 * before delegating each signal. Installed JVM-wide via
 * {@link ReactiveMdcConfig} as a {@code Hooks.onEachOperator} lifter so that
 * MDC set by {@code RequestIdFilter} on the request thread survives operator
 * boundaries that shift to Reactor scheduler threads (e.g. {@code delayElements},
 * {@code publishOn}).
 *
 * <p>Reads MDC from the well-known context key {@link #MDC_CONTEXT_KEY}; callers
 * must capture the request-thread MDC into Reactor Context via
 * {@code .contextWrite(ctx -&gt; ctx.put(MdcContextLifter.MDC_CONTEXT_KEY, mdcMap))}
 * at the controller boundary before returning the reactive type. If the key is
 * absent the lifter is a no-op — it never clears MDC, to avoid clobbering
 * synchronous threads that legitimately set MDC themselves.
 */
public final class MdcContextLifter<T> implements CoreSubscriber<T> {

    /** Reactor Context key under which the captured MDC map is stored. */
    public static final String MDC_CONTEXT_KEY = "substrate.reactive.mdc";

    private final CoreSubscriber<? super T> delegate;
    private final Context context;

    public MdcContextLifter(CoreSubscriber<? super T> delegate) {
        this.delegate = delegate;
        this.context = delegate.currentContext();
    }

    @Override
    public Context currentContext() {
        return context;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        applyMdc();
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(T value) {
        applyMdc();
        delegate.onNext(value);
    }

    @Override
    public void onError(Throwable error) {
        applyMdc();
        delegate.onError(error);
    }

    @Override
    public void onComplete() {
        applyMdc();
        delegate.onComplete();
    }

    private void applyMdc() {
        Map<String, String> mdc = context.getOrDefault(MDC_CONTEXT_KEY, null);
        if (mdc != null && !mdc.isEmpty()) {
            MDC.setContextMap(mdc);
        }
    }
}
