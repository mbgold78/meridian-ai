package com.meridian.substrate.engine.cascade.read;

import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /cascades/{id}/actions} row (engine-spec cascade.md §6.1) — the
 * originator's single-throat-to-choke timeline ([CASCADE-VISIBILITY]): every
 * level's decision/translation/timing, chain_seq asc, straight off the
 * append-only audit log. Payload fields are action-conditional exactly as the
 * log records them (invariant I4); {@code action} serializes kebab-case
 * ({@code decline} is a documented act on equal footing with every other verb).
 */
public record CascadeActionDto(
        UUID id,
        int chainSeq,
        int depth,
        int round,
        CascadeActionType action,
        String actorLabel,
        UUID actorMemberId,
        String refinedCritique,
        String declineReasoning,
        String resolutionNote,
        String nextOwnerLabel,
        Instant actedAt
) {

    public static CascadeActionDto from(CascadeLevelAction action) {
        return new CascadeActionDto(
                action.getId(),
                action.getChainSeq(),
                action.getDepth(),
                action.getRound(),
                action.getAction(),
                action.getActorLabel(),
                action.getActorMemberId(),
                action.getRefinedCritique(),
                action.getDeclineReasoning(),
                action.getResolutionNote(),
                action.getNextOwnerLabel(),
                action.getActedAt());
    }
}
