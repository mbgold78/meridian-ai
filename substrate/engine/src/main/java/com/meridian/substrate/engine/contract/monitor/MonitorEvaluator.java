package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;

/**
 * Evaluator SPI (engine-spec contracts.md §5.3): one implementation per
 * {@link MonitorRuleType}, dispatched by {@link MonitorRunService}. Implementations
 * are read-only describers — see {@link MonitorEvaluation} for the
 * side-effect boundary and the ADR D10 hard invariant it enforces.
 *
 * <p>An implementation that throws is recorded as an {@code ERROR} run with an
 * admin signal ({@code MonitorErrorEvent}); breach state stays untouched
 * (§5.3 last line).
 */
public interface MonitorEvaluator {

    MonitorRuleType ruleType();

    MonitorEvaluation evaluate(ContractMonitor monitor, PeriodContext ctx);
}
