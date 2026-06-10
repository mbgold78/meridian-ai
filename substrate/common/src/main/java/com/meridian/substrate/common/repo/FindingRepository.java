package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.FindingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Finding} entity.
 */
@Repository
public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findByDomainId(UUID domainId);

    List<Finding> findByFindingType(FindingType type);

    List<Finding> findByDomainIdAndFindingType(UUID domainId, FindingType type);
}
