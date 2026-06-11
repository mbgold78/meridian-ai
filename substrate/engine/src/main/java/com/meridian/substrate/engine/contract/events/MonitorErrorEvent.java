package com.meridian.substrate.engine.contract.events;

import java.util.UUID;

/**
 * Published AFTER COMMIT of a monitor run that ended {@code ERROR} (the
 * evaluator threw — engine-spec contracts.md §2.5/§5.3/§9). An ADMIN signal:
 * breach state is untouched by construction (ADR D10 hard invariant — ERROR
 * never opens or refreshes a breach).
 */
public record MonitorErrorEvent(UUID monitorId, UUID contractId, String monitorName,
                                String message) {
}
