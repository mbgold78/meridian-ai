package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.EntityOverlap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link EntityOverlap} entity.
 */
@Repository
public interface EntityOverlapRepository extends JpaRepository<EntityOverlap, UUID> {

    List<EntityOverlap> findByDomainId(UUID domainId);
}
