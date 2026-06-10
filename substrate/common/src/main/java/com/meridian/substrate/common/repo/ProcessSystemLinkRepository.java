package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ProcessSystemLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link ProcessSystemLink} entity.
 */
@Repository
public interface ProcessSystemLinkRepository extends JpaRepository<ProcessSystemLink, UUID> {

    List<ProcessSystemLink> findByProcessId(UUID processId);

    List<ProcessSystemLink> findBySystemId(UUID systemId);
}
