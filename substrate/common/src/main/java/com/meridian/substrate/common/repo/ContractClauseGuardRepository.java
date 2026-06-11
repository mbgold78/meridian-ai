package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractClauseGuard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Clause guards — the PDP hot path (engine-spec contracts.md §3.4; ADR D9).
 * {@link #findByActionTypeAndActiveTrue} rides the
 * {@code idx_guards_action_active} index; the GuardCache (3c) sits in front
 * so evaluation stays at 1 indexed read on miss (§4.3).
 */
@Repository
public interface ContractClauseGuardRepository extends JpaRepository<ContractClauseGuard, UUID> {

    List<ContractClauseGuard> findByActionTypeAndActiveTrue(String actionType);

    List<ContractClauseGuard> findByContractId(UUID contractId);

    /**
     * The GuardCache miss load (§4.3): ALL active guards of one engagement
     * domain, one read — the cache is keyed {@code (domainId)} per spec and
     * filters by action/entity type in memory. Guards carry no domain column
     * (domain lives on the contract aggregate root), hence the JPQL subquery.
     */
    @Query("select g from ContractClauseGuard g where g.active = true and g.contractId in "
            + "(select c.id from Contract c where c.domainId = :domainId)")
    List<ContractClauseGuard> findActiveByDomainId(@Param("domainId") UUID domainId);
}
