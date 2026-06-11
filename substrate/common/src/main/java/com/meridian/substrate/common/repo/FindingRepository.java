package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Finding} entity.
 */
@Repository
public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findByDomainId(UUID domainId);

    List<Finding> findByFindingType(FindingType type);

    List<Finding> findByDomainIdAndFindingType(UUID domainId, FindingType type);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<Finding> findFirstByDomainIdAndTitle(UUID domainId, String title);

    /**
     * The {@code /diagnostics/verify} sweep population (engine-spec
     * diagnostic.md §6.1): every AI-generated finding in the domain — fixture
     * rows ({@code aiGenerated=false/null}) are never in the sweep.
     */
    List<Finding> findByDomainIdAndAiGeneratedTrue(UUID domainId);

    /**
     * Supersession-at-finalization candidate set (engine-spec diagnostic.md
     * §4.5; ADR-004 D3): callers pass {@code PENDING_REVIEW} and then filter
     * by origin-run scope-set — NULL-status operator/fixture rows are never
     * returned (V7 index {@code idx_findings_domain_curation}).
     */
    List<Finding> findByDomainIdAndCurationStatus(UUID domainId, CurationStatus curationStatus);
}
