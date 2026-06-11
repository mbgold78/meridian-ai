package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentComplianceReview;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AgentComplianceReview} promotion-gate rows
 * (engine-spec agents.md §2/§4 table 5; ADR-003 D10).
 */
@Repository
public interface AgentComplianceReviewRepository extends JpaRepository<AgentComplianceReview, UUID> {

    List<AgentComplianceReview> findByAgentRegistrationIdOrderByRequestedAtDesc(UUID agentRegistrationId);

    List<AgentComplianceReview> findByAgentRegistrationIdAndReviewKind(UUID agentRegistrationId,
                                                                       AgentReviewKind kind);

    /**
     * The M4 promotion-gate lookup: an unconsumed APPROVED PROMOTION review
     * decided after the request — else 409 {@code PROMOTION_REVIEW_REQUIRED}.
     * The floor is INCLUSIVE ({@code >=}): the M4 guard exists to exclude
     * PRE-request reviews; a decision landing on the very clock tick of the
     * request is a timestamp-resolution artifact, not a stale review (a
     * strict {@code >} flaked CI whenever request + decide shared a
     * millisecond tick).
     */
    Optional<AgentComplianceReview> findFirstByAgentRegistrationIdAndReviewKindAndDecisionAndConsumedByEventIdIsNullAndDecidedAtGreaterThanEqualOrderByDecidedAtDesc(
            UUID agentRegistrationId, AgentReviewKind kind, AgentReviewDecision decision, Instant floor);
}
