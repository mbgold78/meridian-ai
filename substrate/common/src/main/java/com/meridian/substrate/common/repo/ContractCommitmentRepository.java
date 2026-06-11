package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractCommitment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Contract commitments (engine-spec contracts.md §3.2 table 4). The
 * denormalized {@code complianceState} is updated only by monitor runs,
 * attestation claims and breach transitions (§2.3) — never via API.
 */
@Repository
public interface ContractCommitmentRepository extends JpaRepository<ContractCommitment, UUID> {

    List<ContractCommitment> findByContractIdOrderBySequenceOrderAsc(UUID contractId);

    List<ContractCommitment> findByPartyIdOrderBySequenceOrderAsc(UUID partyId);
}
