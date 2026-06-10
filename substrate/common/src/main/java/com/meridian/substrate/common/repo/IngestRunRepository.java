package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.IngestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link IngestRun} audit entity.
 * Lands with chunk C6 (ingestion port) — see port-map §7.
 */
@Repository
public interface IngestRunRepository extends JpaRepository<IngestRun, UUID> {

    List<IngestRun> findByDomainId(UUID domainId);

    List<IngestRun> findByStatus(String status);
}
