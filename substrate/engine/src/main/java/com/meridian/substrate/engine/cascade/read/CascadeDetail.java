package com.meridian.substrate.engine.cascade.read;

import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /cascades/{id}} body (engine-spec cascade.md §6.1) — exactly the
 * {@link CascadeSummary} fields, flattened, plus:
 *
 * <ul>
 *   <li>{@code levels[]} ordered by depth asc, 1:1 with the Beacon
 *       {@code CascadeLevel} view type (the §11.1 swap seam) — including
 *       frontier LOOKING rows the fixtures never contained (§11.2);</li>
 *   <li>{@code resolution} — {@code null} while in-flight; the originator's
 *       accountability record after RESOLVE_* (both terminal states are
 *       peers, decline is never failure);</li>
 *   <li>{@code siblingDeclineAdvisories} — ADR D2 read-only advisories derived
 *       from SIBLING_DECLINE notification rows; zero state coupling.</li>
 * </ul>
 *
 * <p>This is also the body the chunk-5 command API returns on 200 and inside
 * the 409 envelope {@code data} (§6.2: the client refreshes from current
 * state, never renders a generic failure).
 */
public record CascadeDetail(
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
        int chainSeq,
        List<CascadeLevelDto> levels,
        CascadeResolution resolution,
        List<SiblingDeclineAdvisory> siblingDeclineAdvisories
) {

    public static CascadeDetail from(CascadeSummary s, List<CascadeLevelDto> levels,
                                     CascadeResolution resolution,
                                     List<SiblingDeclineAdvisory> siblingDeclineAdvisories) {
        return new CascadeDetail(
                s.id(), s.originatedBy(), s.originatedByMemberId(), s.originatedAt(),
                s.originalCritique(), s.acceptanceCriteria(), s.summary(),
                s.currentState(), s.attentionSignal(), s.overdue(),
                s.levelCount(), s.frontierDepth(), s.frontierState(), s.frontierOwnerLabel(),
                s.lastActivityAt(), s.affectedDomainId(), s.affectedDomainName(),
                s.cascadeGroupId(), s.predecessorCascadeId(), s.chainSeq(),
                levels, resolution, siblingDeclineAdvisories);
    }
}
