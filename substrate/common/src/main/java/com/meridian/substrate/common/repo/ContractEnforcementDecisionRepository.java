package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY high-volume PDP log
 * (engine-spec contracts.md §3.4, §10.9; ADR D17). DENY rows are written in
 * {@code REQUIRES_NEW} by the gateway's decision writer (3c) so they survive
 * business-transaction rollback (§10.4).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system (precedent:
 * {@code CascadeLevelActionRepository}; ArchUnit rule lands with chunk 3c).
 * Queried by domain+time in v1; per-contract filtering happens app-side over
 * the {@code contract_ids} JSON array (no FK by design).
 */
@org.springframework.stereotype.Repository
public interface ContractEnforcementDecisionRepository extends Repository<ContractEnforcementDecision, UUID> {

    /** Insert-only in practice (append-only log) — never call with a loaded, mutated row. */
    ContractEnforcementDecision save(ContractEnforcementDecision decision);

    Optional<ContractEnforcementDecision> findById(UUID id);

    List<ContractEnforcementDecision> findByDomainIdAndDecidedAtAfter(UUID domainId, Instant since);

    // §7.1 GET /contracts/{id}/decisions?since&decision filtered finders
    List<ContractEnforcementDecision> findByDomainIdAndDecision(UUID domainId, GatewayDecision decision);

    List<ContractEnforcementDecision> findByDomainIdAndDecisionAndDecidedAtAfter(
            UUID domainId, GatewayDecision decision, Instant since);

    /**
     * Cross-domain time-bounded read backing the MCP
     * {@code get_enforcement_decisions} tool when no {@code contract_key} is
     * given (§8.2) — v1 deploys are per-engagement single-domain, so this is
     * effectively domain-scoped; actor filtering happens app-side.
     */
    List<ContractEnforcementDecision> findByDecidedAtAfter(Instant since);
}
