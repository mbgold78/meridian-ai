package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Process} entity.
 */
@Repository
public interface ProcessRepository extends JpaRepository<Process, UUID> {

    List<Process> findByDomainId(UUID domainId);

    List<Process> findByClassification(ManualProcessClassification classification);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<Process> findFirstByDomainIdAndName(UUID domainId, String name);
}
