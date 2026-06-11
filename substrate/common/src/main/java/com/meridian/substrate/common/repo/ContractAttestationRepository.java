package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractAttestation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract attestations (engine-spec contracts.md §3.4). The row is an
 * idempotent upsert on {@code UNIQUE(commitment_id, period_key)} (ADR D13);
 * submission history lives in {@code contract_events}, so updates here are
 * legal (latest-wins row).
 */
@Repository
public interface ContractAttestationRepository extends JpaRepository<ContractAttestation, UUID> {

    /** The ADR D13 upsert key — unique, hence Optional. */
    Optional<ContractAttestation> findByCommitmentIdAndPeriodKey(UUID commitmentId, String periodKey);

    List<ContractAttestation> findByContractId(UUID contractId);

    /**
     * All periods attested for one commitment — the §5.3
     * ATTESTATION_CADENCE evaluator's "has the party since reported a later
     * period" lookup (the §13 3b acceptance flow attests the CURRENT period
     * after a missed one).
     */
    List<ContractAttestation> findByCommitmentId(UUID commitmentId);
}
