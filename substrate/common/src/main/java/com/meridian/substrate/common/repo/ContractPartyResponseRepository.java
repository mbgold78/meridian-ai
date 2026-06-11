package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractPartyResponse;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for IMMUTABLE
 * {@link ContractPartyResponse} rows (engine-spec contracts.md §2.2
 * invariants, §10.9).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system (precedent:
 * {@code CascadeLevelActionRepository}; the ArchUnit append-only rule lands
 * with chunk 3c). {@code save} is for initial inserts only; idempotency/
 * conflict handling re-reads via {@link #findByPartyId} after a
 * {@code UNIQUE(party_id)} violation (§10.2).
 */
@org.springframework.stereotype.Repository
public interface ContractPartyResponseRepository extends Repository<ContractPartyResponse, UUID> {

    /** Insert-only in practice — never call with a loaded, mutated row. */
    ContractPartyResponse save(ContractPartyResponse response);

    Optional<ContractPartyResponse> findById(UUID id);

    List<ContractPartyResponse> findByContractId(UUID contractId);

    /** One per party ({@code UNIQUE(party_id)}) — the §10.2 idempotency re-read. */
    Optional<ContractPartyResponse> findByPartyId(UUID partyId);
}
