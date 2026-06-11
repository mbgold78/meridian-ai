package com.meridian.substrate.engine.contract.events;

import com.meridian.substrate.common.domain.enums.Severity;

import java.util.UUID;

/**
 * Published AFTER COMMIT of the breach DETECTED → SURFACED transition
 * (engine-spec contracts.md §2.4/§5.4/§9 — Spring application event, the v1
 * notification seam §11.1). One of the two leader-attention signal classes;
 * the optional orchestrator-remediation hook
 * ({@code substrate.contracts.orchestrator-remediation}, off by default) and
 * future channels consume it. The dashboard signal itself is computed at read
 * time by {@code ContractSignalService} — no signal table exists in v1.
 */
public record BreachSurfacedEvent(UUID breachId, UUID contractId, UUID domainId,
                                  String contractKey, Severity severity, String summary) {
}
