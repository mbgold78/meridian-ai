package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One governed state mutation presented to the PDP (engine-spec contracts.md
 * §4.1). Built by the three substrate-side PEPs (§4.2/ADR D2): MCP governed
 * write tools, {@code @Governed} REST service commands, and the
 * dispatch-result apply path.
 *
 * <p>{@code payloadSummary} is SMALL, never the full request body (§4.1) —
 * exactly the fields guard predicates may reference via {@code payload.*}
 * dot-paths (§4.4). {@code dispatchId} is non-null only for
 * {@code actionType=apply_dispatch_result} (§4.2 PEP 3) and is stamped onto
 * the decision row's {@code dispatch_id} column.
 */
public record GovernedAction(String actionType, Principal principal, UUID domainId,
                             TargetRef target,
                             JsonNode payloadSummary,
                             String dispatchId) {

    public GovernedAction {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("governed action requires a non-blank actionType");
        }
        if (principal == null) {
            throw new IllegalArgumentException("governed action requires a principal");
        }
        if (domainId == null) {
            throw new IllegalArgumentException("governed action requires a domainId");
        }
    }

    /** Convenience for the common no-dispatch case. */
    public GovernedAction(String actionType, Principal principal, UUID domainId,
                          TargetRef target, JsonNode payloadSummary) {
        this(actionType, principal, domainId, target, payloadSummary, null);
    }
}
