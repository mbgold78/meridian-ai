package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.RoleOverlap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link RoleOverlap} entity.
 */
@Repository
public interface RoleOverlapRepository extends JpaRepository<RoleOverlap, UUID> {

    List<RoleOverlap> findByDomainId(UUID domainId);
}
