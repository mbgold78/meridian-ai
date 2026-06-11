package com.meridian.substrate.engine.contract.events;

import java.util.UUID;

/**
 * Published AFTER COMMIT when the sweep's arming pass flips a
 * predicate-bearing decline trigger to ARMED (engine-spec contracts.md
 * §2.6/§9). Backs the {@code DECLINE_ELIGIBLE} signal — subtle-destructive
 * accent, <b>never a breach</b> (ADR D6 graft). The signals feed itself reads
 * the denormalized {@code armed} flag at read time.
 */
public record DeclineTriggerArmedEvent(UUID contractId, UUID domainId, String contractKey,
                                       UUID triggerId, String triggerText) {
}
