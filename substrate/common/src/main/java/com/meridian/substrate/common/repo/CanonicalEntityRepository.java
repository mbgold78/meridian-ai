package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CanonicalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link CanonicalEntity} entity.
 */
@Repository
public interface CanonicalEntityRepository extends JpaRepository<CanonicalEntity, UUID> {

    List<CanonicalEntity> findByDomainId(UUID domainId);

    List<CanonicalEntity> findBySystemId(UUID systemId);

    /** Natural-key lookup for the ingestion idempotent upsert (port-map §2.4 C6). */
    Optional<CanonicalEntity> findFirstByDomainIdAndName(UUID domainId, String name);
}
