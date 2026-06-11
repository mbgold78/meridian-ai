package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.SynthesisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link SynthesisRun} entity.
 */
@Repository
public interface SynthesisRunRepository extends JpaRepository<SynthesisRun, UUID> {

    List<SynthesisRun> findByDomainId(UUID domainId);

    /**
     * One-active-diagnostic-run-per-domain check (engine-spec diagnostic.md
     * §4.2 step 1; ADR-004 D11): callers pass
     * {@code runType='diagnostic', statuses=[PLANNED, RUNNING]}. The V7_1
     * Postgres partial-unique index is the DB backstop; H2 dev is
     * service-enforced only.
     */
    List<SynthesisRun> findByDomainIdAndRunTypeAndStatusIn(
            UUID domainId, String runType, List<String> statuses);

    /** Run-list read model (diagnostic.md §10.1) — newest first. */
    List<SynthesisRun> findByDomainIdAndRunTypeOrderByCreatedAtDesc(UUID domainId, String runType);
}
