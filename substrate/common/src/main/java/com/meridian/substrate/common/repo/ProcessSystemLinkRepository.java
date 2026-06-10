package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.enums.LinkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link ProcessSystemLink} entity.
 */
@Repository
public interface ProcessSystemLinkRepository extends JpaRepository<ProcessSystemLink, UUID> {

    List<ProcessSystemLink> findByProcessId(UUID processId);

    List<ProcessSystemLink> findBySystemId(UUID systemId);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<ProcessSystemLink> findFirstByProcessIdAndSystemIdAndLinkType(
            UUID processId, UUID systemId, LinkType linkType);
}
