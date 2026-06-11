package com.meridian.substrate.engine.contract.monitor;

/**
 * Liveness-ONLY probe seam for the MCP_PROBE evaluator (engine-spec
 * contracts.md §5.3; ADR D11): substrate pings the party's registered MCP
 * endpoint ({@code ReaderRegistration kind=MCP_CLIENT}, P19 outward
 * direction). Reachable ⇒ COMPLIANT; unreachable ⇒ INDETERMINATE +
 * {@code ADHERENCE_UNOBSERVABLE}. It is NEVER the metric path and never a
 * breach source — metrics are push-based ({@code report_metric}, ADR D11).
 */
@FunctionalInterface
public interface McpLivenessProbe {

    /**
     * Whether the endpoint answered at all (any HTTP response counts as
     * alive; only connect/timeout failures are "unreachable").
     */
    boolean isReachable(String uri);
}
