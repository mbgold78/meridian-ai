package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Integration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Integration} entity.
 */
@Repository
public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findByDomainId(UUID domainId);

    List<Integration> findBySourceSystemIdOrTargetSystemId(UUID sourceId, UUID targetId);
}
