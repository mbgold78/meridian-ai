package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractEvent;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY {@link ContractEvent}
 * audit timeline (engine-spec contracts.md §2.2 invariants, §3.4, §10.9 —
 * "save-only otherwise").
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system: no
 * {@code delete}, {@code deleteAll}, {@code deleteById} is inherited or
 * declared (precedent: {@code CascadeLevelActionRepository}; the ArchUnit
 * rule banning {@code delete*}/mutators on this repo lands with chunk 3c).
 */
@org.springframework.stereotype.Repository
public interface ContractEventRepository extends Repository<ContractEvent, UUID> {

    /** Insert-only in practice (append-only log) — never call with a loaded, mutated row. */
    ContractEvent save(ContractEvent event);

    Optional<ContractEvent> findById(UUID id);

    List<ContractEvent> findByContractIdOrderByOccurredAtAsc(UUID contractId);

    /** §7.1 {@code GET /contracts/{id}/events?after} filter lane. */
    List<ContractEvent> findByContractIdAndOccurredAtAfterOrderByOccurredAtAsc(UUID contractId, Instant after);

    /**
     * Domain-scoped trailing-window count for the §11.2
     * {@code coordinationPatternsThisWeek} signal (contract half — computed,
     * never stamped). Events carry {@code contract_id} only, so the domain
     * scope joins through {@code contracts} (soft-ref posture unchanged —
     * this is a read, not a constraint). Additive read-only finder; the
     * insert-only discipline of this surface is untouched.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(e) FROM ContractEvent e
            WHERE e.occurredAt > :since
              AND e.contractId IN (SELECT c.id FROM Contract c WHERE c.domainId = :domainId)
            """)
    long countByDomainIdAndOccurredAtAfter(
            @org.springframework.data.repository.query.Param("domainId") UUID domainId,
            @org.springframework.data.repository.query.Param("since") Instant since);
}
