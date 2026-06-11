package com.meridian.substrate.engine.contract.gateway;

import java.util.List;
import java.util.UUID;

/**
 * Gateway evaluation outcome (engine-spec contracts.md §4.1). In ENFORCE mode
 * a deny outcome is raised as {@link ContractDeniedException} (carrying the
 * {@link Deny} record) rather than returned, so callers inside a business
 * transaction roll back automatically; in SHADOW a would-be denial returns
 * {@link Allow} {@code flagged} after rowing {@code WOULD_DENY} (§4.3 —
 * shadow never blocks, ADR D4).
 */
public sealed interface Decision {

    /**
     * The action proceeds. {@code flagged} when a FLAG guard matched, an
     * unevaluable predicate was skipped (§4.4 never-silent rule), the HUMAN
     * fail-open posture fired (ADR D5), or a shadow-mode would-be denial was
     * recorded.
     */
    record Allow(boolean flagged, List<UUID> matchedClauseIds, String flagReason)
            implements Decision {

        public Allow {
            matchedClauseIds = matchedClauseIds == null ? List.of() : List.copyOf(matchedClauseIds);
        }

        /** The §4.3 mode-off / no-guard plain allow. */
        public static Allow unflagged() {
            return new Allow(false, List.of(), null);
        }
    }

    /**
     * The §4.5 denial citation: immutable guard ids + contract keys + reason
     * + the id of the decision row persisted in REQUIRES_NEW (§10.4 — it
     * survives the business-transaction rollback, ADR D17). Carried by
     * {@link ContractDeniedException}; the MCP adapter serializes exactly
     * these fields into the structured {@code isError} payload (§4.5).
     */
    record Deny(List<UUID> clauseIds, List<String> contractKeys, String reason, UUID decisionId)
            implements Decision {

        public Deny {
            clauseIds = clauseIds == null ? List.of() : List.copyOf(clauseIds);
            contractKeys = contractKeys == null ? List.of() : List.copyOf(contractKeys);
        }
    }
}
