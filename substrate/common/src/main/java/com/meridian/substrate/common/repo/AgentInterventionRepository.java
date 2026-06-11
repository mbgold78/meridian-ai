package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link AgentIntervention} attention overlay (engine-spec
 * agents.md §2/§4 table 4).
 */
@Repository
public interface AgentInterventionRepository extends JpaRepository<AgentIntervention, UUID> {

    List<AgentIntervention> findByAgentRegistrationIdOrderByOpenedAtDesc(UUID agentRegistrationId);

    List<AgentIntervention> findByAgentRegistrationIdAndStatus(UUID agentRegistrationId,
                                                               AgentInterventionStatus status);

    /** Attention/signals feeds (idx_ai_agent_status; domain scoped at read). */
    List<AgentIntervention> findByDomainIdAndStatus(UUID domainId, AgentInterventionStatus status);

    /** Engine-raised dedupe check (invariant I12; V6_1 partial unique is the PG backstop). */
    Optional<AgentIntervention> findFirstByAgentRegistrationIdAndSourceKeyAndStatus(
            UUID agentRegistrationId, String sourceKey, AgentInterventionStatus status);

    /** Escalation-sweeper candidate list — every OPEN intervention, all domains (spec §13.4). */
    List<AgentIntervention> findByStatus(AgentInterventionStatus status);

    long countByAgentRegistrationIdAndStatus(UUID agentRegistrationId, AgentInterventionStatus status);
}
