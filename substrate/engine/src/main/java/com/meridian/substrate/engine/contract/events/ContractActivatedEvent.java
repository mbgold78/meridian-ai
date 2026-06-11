package com.meridian.substrate.engine.contract.events;

import java.util.UUID;

/**
 * Published AFTER COMMIT of the PROPOSED → ACTIVE transition (engine-spec
 * contracts.md §2.2/§9 — Spring application event, the v1 notification seam
 * §11.1). 3c's GuardCache invalidation listener and future channels consume
 * it; nothing in 3a listens yet.
 */
public record ContractActivatedEvent(UUID contractId, UUID domainId, String contractKey) {
}
