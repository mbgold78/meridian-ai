package com.meridian.substrate.engine.contract.monitor;

/**
 * EXTENSION POINT — deliberately empty in v1 (engine-spec contracts.md §9 /
 * §15 #8; ADR D11): pull-based metric feeds / real telemetry replacing the
 * push-only {@code report_metric} + {@code contract_metric_samples} path.
 * Unblocks via the Phase 4+ reader/connector framework. DO NOT implement or
 * wire this interface before then — flag-not-invent.
 */
public interface ContractMetricProvider {
}
