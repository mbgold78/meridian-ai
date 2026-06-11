package com.meridian.substrate.engine.contract.gateway;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Thread-bound explicit principal for governed invocations (engine-spec
 * contracts.md §4.1/§4.2). The §8.3 MCP write-tool adapters wrap their engine
 * service calls in {@link #callAs} with the AGENT principal lifted from the
 * {@code X-MCP-Principal} transport context, so the {@link GovernedActionAspect}
 * attributes the SAME service method to the right principal type per
 * transport: REST → HUMAN (via {@link GovernedPrincipalSource}), MCP → AGENT
 * (this scope), engine-internal → SERVICE (the aspect's documented fallback).
 *
 * <p>Scope wins over sources in the aspect's resolution order — an MCP call
 * must never be misattributed as HUMAN even if some filter populated request
 * attributes on the servlet thread.
 *
 * <p>Plain {@code ThreadLocal}, save/restore semantics (re-entrant): tool
 * handlers run on the request thread ({@code immediateExecution}, §8.1) and
 * engine services never hop threads mid-transaction.
 */
public final class GovernedPrincipalScope {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private GovernedPrincipalScope() {
    }

    /** Runs {@code body} with {@code principal} as the governed-action principal. */
    public static <T> T callAs(Principal principal, Callable<T> body) {
        if (principal == null) {
            throw new IllegalArgumentException("callAs requires a principal");
        }
        Principal previous = CURRENT.get();
        CURRENT.set(principal);
        try {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("governed principal scope body failed", e);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The explicitly scoped principal of the current thread, if any. */
    public static Optional<Principal> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
