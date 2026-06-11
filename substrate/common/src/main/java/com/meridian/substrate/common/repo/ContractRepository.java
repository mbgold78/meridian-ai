package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.ContractStatus;
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
 * Contract aggregate repository (engine-spec contracts.md §3.4).
 */
@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {

    List<Contract> findByDomainId(UUID domainId);

    List<Contract> findByDomainIdAndStatus(UUID domainId, ContractStatus status);

    /**
     * All contracts in a lifecycle status, across domains — the 3b sweep's
     * decline-trigger arming pass iterates ACTIVE contracts (spec §2.6/§5.2
     * step 3; v1 deploys are per-engagement single-domain, plan §8).
     */
    List<Contract> findByStatus(ContractStatus status);

    /** {@code contract_key} is the slug identity, unique per domain (ADR D8). */
    Optional<Contract> findByDomainIdAndContractKey(UUID domainId, String contractKey);

    /**
     * Cross-domain key lookup backing §7.1 {@code {idOrKey}} resolution
     * ("UUID first, then (current engagement domain, contract_key)"): v1
     * deploys are per-engagement single-domain (plan §8), so a bare
     * {@code contract_key} resolves when it is globally unambiguous; an
     * ambiguous key (same slug in several domains — spec §15 extension point
     * #10, cross-engagement namespacing) must be addressed by UUID instead.
     */
    List<Contract> findByContractKey(String contractKey);

    /**
     * Pessimistic-lock load for lifecycle transitions (respond / decline /
     * activate — spec §10.1): the last-required-acceptance check happens under
     * this lock so two concurrent accepts cannot double-activate. H2/PG both
     * map to {@code SELECT … FOR UPDATE}. Callers MUST be inside
     * {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contract c WHERE c.id = :id")
    Optional<Contract> findByIdForUpdate(@Param("id") UUID id);
}
