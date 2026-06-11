package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentThrottleBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AgentThrottleBudget} rows (engine-spec agents.md
 * §2/§4 table 8; ADR-003 D12). One ACTIVE budget per (agent, kind):
 * service-enforced, V6_1 partial unique backstop on Postgres.
 */
@Repository
public interface AgentThrottleBudgetRepository extends JpaRepository<AgentThrottleBudget, UUID> {

    List<AgentThrottleBudget> findByAgentRegistrationIdAndActiveTrue(UUID agentRegistrationId);

    Optional<AgentThrottleBudget> findByAgentRegistrationIdAndBudgetKindAndActiveTrue(
            UUID agentRegistrationId, String budgetKind);
}
