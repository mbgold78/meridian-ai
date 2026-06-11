package com.meridian.substrate.engine.cascade.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;

import java.time.Instant;
import java.util.UUID;

/**
 * Cascade draft response record (engine-spec cascade.md §6.3) — kebab-case
 * enum wire forms via {@code @JsonValue} (ADR D13).
 *
 * <p>{@code suggestionState} is AI-assist <em>plumbing</em> status, NOT
 * governance state: {@code error} means the orchestrator dispatch failed,
 * never anything about declines (§6.3 DTO note). {@code suggestionProvenance}
 * is exposed as-is — it is the engagement's audit trail of the AI assist
 * (ADR D6; {@code AI_GENERATIVE} appears only inside it).
 */
public record CascadeDraftDto(
        UUID id,
        UUID domainId,
        UUID authorMemberId,
        String authorLabel,
        String originalCritique,
        String acceptanceCriteria,
        String summary,
        UUID affectedDomainId,
        UUID originFindingId,
        UUID cascadeGroupId,
        CascadeAuthoringMode authoringMode,
        Integer responseSlaDays,
        String suggestedCritique,
        String suggestedAcceptanceCriteria,
        JsonNode suggestionProvenance,
        CascadeSuggestionState suggestionState,
        String orchestratorTaskId,
        CascadeDraftStatus status,
        UUID issuedCascadeId,
        Instant createdAt,
        Instant updatedAt
) {

    public static CascadeDraftDto from(CascadeDraft draft) {
        return new CascadeDraftDto(
                draft.getId(),
                draft.getDomainId(),
                draft.getAuthorMemberId(),
                draft.getAuthorLabel(),
                draft.getOriginalCritique(),
                draft.getAcceptanceCriteria(),
                draft.getSummary(),
                draft.getAffectedDomainId(),
                draft.getOriginFindingId(),
                draft.getCascadeGroupId(),
                draft.getAuthoringMode(),
                draft.getResponseSlaDays(),
                draft.getSuggestedCritique(),
                draft.getSuggestedAcceptanceCriteria(),
                draft.getSuggestionProvenance(),
                draft.getSuggestionState(),
                draft.getOrchestratorTaskId(),
                draft.getStatus(),
                draft.getIssuedCascadeId(),
                draft.getCreatedAt(),
                draft.getUpdatedAt());
    }
}
