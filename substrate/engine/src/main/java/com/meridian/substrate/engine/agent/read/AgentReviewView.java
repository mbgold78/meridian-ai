package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentComplianceReview;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One compliance-review row (promotion-gate currency — engine-spec agents.md
 * §5.1 M4/M5; ADR-003 D10). Records reviewer identity, decision, rationale,
 * evidence refs and NOTHING else — review-content semantics are UNKNOWN
 * (flag-not-invent, spec §17 #3). REJECTED renders info/neutral, never error.
 */
public record AgentReviewView(
        UUID id,
        UUID agentRegistrationId,
        AgentReviewKind reviewKind,
        Instant requestedAt,
        String requestedBy,
        String reviewer,
        UUID reviewerMemberId,
        Instant decidedAt,
        AgentReviewDecision decision,
        String rationale,
        JsonNode evidenceRefs,
        UUID consumedByEventId) {

    public static AgentReviewView from(AgentComplianceReview r) {
        return new AgentReviewView(r.getId(), r.getAgentRegistrationId(), r.getReviewKind(),
                r.getRequestedAt(), r.getRequestedByLabel(), r.getReviewerLabel(),
                r.getReviewerMemberId(), r.getDecidedAt(), r.getDecision(), r.getRationale(),
                r.getEvidenceRefs(), r.getConsumedByEventId());
    }
}
