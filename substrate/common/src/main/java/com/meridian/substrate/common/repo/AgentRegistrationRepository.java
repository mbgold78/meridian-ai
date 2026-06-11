package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link AgentRegistration} aggregate root (engine-spec
 * agents.md §2/§5.5).
 */
@Repository
public interface AgentRegistrationRepository extends JpaRepository<AgentRegistration, UUID> {

    List<AgentRegistration> findByDomainId(UUID domainId);

    /** Fleet list lane (idx_agents_domain_mode). */
    List<AgentRegistration> findByDomainIdAndOperationalMode(UUID domainId, AgentOperationalMode mode);

    /** Default fleet list: {@code retired} EXCLUDED (spec §5.2 / ADR-003 D4 pill-map crash mitigation). */
    List<AgentRegistration> findByDomainIdAndOperationalModeNot(UUID domainId, AgentOperationalMode mode);

    /** Slug resolution: {@code {idOrKey}} resolves UUID first, then (domain, agent_key) (spec §7). */
    Optional<AgentRegistration> findByDomainIdAndAgentKey(UUID domainId, String agentKey);

    /**
     * Unscoped slug resolution for {@code GET /agents/{idOrKey}} without a
     * {@code domainId} (spec §7): keys are unique per domain only
     * (uq_agent_domain_key) — multiple matches make the read service demand
     * the domain scope rather than guess.
     */
    List<AgentRegistration> findByAgentKey(String agentKey);

    /** Gateway hot path (6c): principal → registration (idx_agents_principal). */
    Optional<AgentRegistration> findByMcpPrincipalId(String mcpPrincipalId);

    /**
     * Scalar projection for the {@code GovernedContextResolver} lane (6c):
     * resolving a governed call's domain/target must NOT load the
     * registration ENTITY into the business persistence context — the §8
     * registry gate commits registration updates (sightings, zombie
     * interventions) in REQUIRES_NEW between resolution and the method body,
     * and a pre-gate L1-cached entity would flush a stale {@code @Version}
     * (ObjectOptimisticLockingFailureException). The body's
     * {@link #findByIdForUpdate} stays the FIRST entity load of the
     * transaction.
     */
    interface GovernedRef {
        UUID getDomainId();

        String getAgentKey();
    }

    @Query("select a.domainId as domainId, a.agentKey as agentKey"
            + " from AgentRegistration a where a.id = :id")
    Optional<GovernedRef> findGovernedRefById(@Param("id") UUID id);

    /**
     * Gate-state scalar projection for the §8.3 registry lookup
     * ({@code AgentRegistryLookup}) — same stale-{@code @Version} rationale
     * as {@link GovernedRef}: the lookup runs inside the governed business
     * transaction BEFORE the gate's REQUIRES_NEW registration writes, so it
     * must never park the entity in the caller's persistence context.
     */
    interface GateView {
        UUID getId();

        String getAgentKey();

        UUID getDomainId();

        AgentOperationalMode getOperationalMode();

        Boolean getReadOnly();

        Integer getOpenInterventionCount();
    }

    @Query("select a.id as id, a.agentKey as agentKey, a.domainId as domainId,"
            + " a.operationalMode as operationalMode, a.readOnly as readOnly,"
            + " a.openInterventionCount as openInterventionCount"
            + " from AgentRegistration a where a.mcpPrincipalId = :principalId")
    Optional<GateView> findGateViewByMcpPrincipalId(@Param("principalId") String principalId);

    /**
     * Escalation-sweeper candidate list (spec §13.4): RECORDED_INTENT controls
     * still awaiting human confirmation — runtime acknowledgment alone never
     * stops the dwell clock (ADR-003 D6: acknowledgment ≠ confirmation).
     */
    List<AgentRegistration> findByControlPendingVerbIsNotNullAndControlConfirmedAtIsNull();

    /** Criteria-gate sweep candidate list (spec §9.2) — non-retired fleet, all domains. */
    List<AgentRegistration> findByOperationalModeNot(AgentOperationalMode mode);

    /**
     * The per-agent serialization point (spec §5.1/§5.5; cascade D8 idiom
     * verbatim): {@code PESSIMISTIC_WRITE} maps to {@code SELECT … FOR UPDATE}
     * on Postgres and is honored by H2 PG-mode. Every lifecycle mutation takes
     * this lock first, then performs the idempotency check, fencing, guards,
     * event append, projection updates and outbox writes in ONE transaction
     * (invariant I9). Callers MUST be {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentRegistration a where a.id = :id")
    Optional<AgentRegistration> findByIdForUpdate(@Param("id") UUID id);
}
