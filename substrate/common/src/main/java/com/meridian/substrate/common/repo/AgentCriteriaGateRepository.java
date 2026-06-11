package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentCriteriaGate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link AgentCriteriaGate} rows (engine-spec agents.md §2/§4
 * table 6 / §9; ADR-003 D9). Gate CONTENT is immutable post-deploy
 * (invariant I2) — only standing/lastEvaluatedAt are updated by the 6b/6c
 * evaluation passes.
 */
@Repository
public interface AgentCriteriaGateRepository extends JpaRepository<AgentCriteriaGate, UUID> {

    /** The digest-ordered mandate clauses (uq_acg_order). */
    List<AgentCriteriaGate> findByAgentRegistrationIdOrderBySequenceOrderAsc(UUID agentRegistrationId);
}
