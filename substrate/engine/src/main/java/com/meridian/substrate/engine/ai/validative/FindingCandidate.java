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
 */
public record FindingCandidate(
        FindingCandidateType type,
        FindingCandidateSeverity severity,
        String affectedEntityType,
        UUID affectedEntityId,
        String description,
        String suggestedAction,
        List<UUID> evidenceIds,
        UUID assertionId
) {}
