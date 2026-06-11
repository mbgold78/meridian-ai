package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link DiagnosticRunBatch} resumability
 * ledger (engine-spec diagnostic.md §3.1; ADR-004 D2). Mutable by design —
 * batches are claimed (PENDING→RUNNING) and finalized in the per-batch
 * commit transaction; the append-only discipline applies to
 * {@code ai_curation_events}, not this ledger.
 */
@Repository
public interface DiagnosticRunBatchRepository extends JpaRepository<DiagnosticRunBatch, UUID> {

    List<DiagnosticRunBatch> findBySynthesisRunIdOrderByBatchNoAsc(UUID synthesisRunId);

    List<DiagnosticRunBatch> findBySynthesisRunIdAndStatus(UUID synthesisRunId, String status);

    Optional<DiagnosticRunBatch> findBySynthesisRunIdAndBatchNo(UUID synthesisRunId, int batchNo);

    long countBySynthesisRunIdAndStatusIn(UUID synthesisRunId, List<String> statuses);
}
