package com.meridian.substrate.engine.ai.validative;

import java.util.List;
import java.util.UUID;

/**
 * A single audit finding produced by {@link ValidativeModeService}. Returned
 * as a DTO — never auto-persisted as a {@code Finding} entity. The caller
 * (Phase 2B Capability Designer) decides whether to surface, dismiss, or
 * commit each candidate.
 *
 * <p>{@code evidenceIds} is empty for {@link FindingCandidateType#STRUCTURAL}
 * findings (model-state observations, no Universe citation needed) and
 * guaranteed non-empty for {@link FindingCandidateType#CONTENT} findings
 * (the service filters CONTENT findings whose claimed evidence does not
 * resolve to Evidence rows in the requested domain).
 *
 * <p>{@code affectedEntityId} and {@code affectedEntityType} are nullable —
 * domain-wide findings ("the capability tree has no L1 nodes") apply to no
 * single entity.
 *
 * <p>{@code title} and {@code findingType} are the engine-spec diagnostic.md
 * §5.1 additive trailing components (nullable, optional in the candidate JSON
 * contract). {@code findingType} carries the RAW string as the LLM emitted it
 * — validation against the canonical {@code FindingType} enum (and the
 * differential defaulting for invalid/absent values) is the COMMIT step's job
 * (spec §5.2), never the filter's: an invalid LLM-supplied type must never
 * discard an otherwise-grounded candidate. The compatibility constructor
 * preserves the original Phase-2A arity so pinned callers compile unchanged.
 */
public record FindingCandidate(
        FindingCandidateType type,
        FindingCandidateSeverity severity,
        String affectedEntityType,
        UUID affectedEntityId,
        String description,
        String suggestedAction,
        List<UUID> evidenceIds,
        UUID assertionId,
        String title,
        String findingType
) {

    /**
     * Compatibility constructor preserving the original Phase-2A arity
     * (spec §5.1): delegates {@code title} and {@code findingType} as null.
     */
    public FindingCandidate(FindingCandidateType type,
                            FindingCandidateSeverity severity,
                            String affectedEntityType,
                            UUID affectedEntityId,
                            String description,
                            String suggestedAction,
                            List<UUID> evidenceIds,
                            UUID assertionId) {
        this(type, severity, affectedEntityType, affectedEntityId,
                description, suggestedAction, evidenceIds, assertionId,
                null, null);
    }
}
