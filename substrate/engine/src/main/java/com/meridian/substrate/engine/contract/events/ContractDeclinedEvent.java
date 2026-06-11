package com.meridian.substrate.engine.contract.events;

import com.meridian.substrate.common.domain.enums.ContractStatus;

import java.util.UUID;

/**
 * Published AFTER COMMIT of either decline transition (PROPOSED → DECLINED via
 * respond, ACTIVE → DECLINED via invoke_decline — engine-spec contracts.md
 * §2.2/§6.1/§9). A LEGITIMATE terminal outcome, never an error or breach
 * (port-map trap #8).
 *
 * <p>The cascade engine MAY consume this event; auto-cascade creation is NOT
 * implemented and reviewers must reject any wiring beyond a listener stub
 * (spec §9 / §15 extension point #4 — queue #24 / plan open decision #2).
 */
public record ContractDeclinedEvent(UUID contractId, UUID domainId, String contractKey,
                                    ContractStatus fromStatus) {
}
