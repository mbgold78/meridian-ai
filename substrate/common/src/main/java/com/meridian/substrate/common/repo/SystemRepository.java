package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.SystemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link SystemEntity} entity.
 */
@Repository
public interface SystemRepository extends JpaRepository<SystemEntity, UUID> {

    List<SystemEntity> findByDomainId(UUID domainId);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<SystemEntity> findFirstByDomainIdAndName(UUID domainId, String name);
}
