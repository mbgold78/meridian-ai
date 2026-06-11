package com.meridian.substrate.engine.contract.monitor;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Evaluation instant + zone handed to every {@link MonitorEvaluator} (engine-spec
 * contracts.md §5.1/§5.3): {@code now} comes from the injectable engine
 * {@code java.time.Clock} (S15 demo determinism), {@code zone} from
 * {@code substrate.contracts.clock.zone}.
 */
public record PeriodContext(Instant now, ZoneId zone) {
}
