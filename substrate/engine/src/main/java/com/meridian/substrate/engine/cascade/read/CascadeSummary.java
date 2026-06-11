package com.meridian.substrate.engine.cascade.read;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /cascades} row (engine-spec cascade.md §6.1) — the dashboard-grid
 * projection. State vocabulary is the fixture vocabulary: {@code currentState},
 * {@code attentionSignal} and {@code frontierState} serialize kebab-case via
 * the enums' {@code @JsonValue} (ADR D13). {@code overdue} is an overlay flag,
 * never a signal value (spec §2).
 */
public record CascadeSummary(
        UUID id,
        String originatedBy,
        UUID originatedByMemberId,
        Instant originatedAt,
        String originalCritique,
        String acceptanceCriteria,
        String summary,
        CascadeState currentState,
        CascadeAttentionSignal attentionSignal,
        boolean overdue,
        int levelCount,
        int frontierDepth,
        CascadeLevelState frontierState,
        String frontierOwnerLabel,
        Instant lastActivityAt,
        UUID affectedDomainId,
        String affectedDomainName,
        UUID cascadeGroupId,
        UUID predecessorCascadeId,
        int chainSeq
) {

    public static CascadeSummary from(Cascade cascade, List<CascadeLevel> levels,
                                      Instant lastActivityAt, String affectedDomainName) {
        String frontierOwnerLabel = levels.stream()
                .filter(l -> l.getDepth() == cascade.getFrontierDepth())
                .map(CascadeLevel::getOwnerLabel)
                .findFirst()
                .orElse(null);
        return new CascadeSummary(
                cascade.getId(),
                cascade.getOriginOwnerLabel(),
                cascade.getOriginOwnerMemberId(),
                cascade.getOriginatedAt(),
                cascade.getOriginalCritique(),
                cascade.getAcceptanceCriteria(),
                cascade.getSummary(),
                cascade.getCurrentState(),
                cascade.getAttentionSignal(),
                cascade.isOverdue(),
                levels.size(),
                cascade.getFrontierDepth(),
                cascade.getFrontierState(),
                frontierOwnerLabel,
                lastActivityAt,
                cascade.getAffectedDomainId(),
                affectedDomainName,
                cascade.getCascadeGroupId(),
                cascade.getPredecessorCascadeId(),
                cascade.getChainSeq());
    }
}
