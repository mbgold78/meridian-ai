package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {
    List<Evidence> findByEntityId(UUID entityId);
    List<Evidence> findByDomainId(UUID domainId);
    List<Evidence> findByEntityIdAndEntityType(UUID entityId, String entityType);
    List<Evidence> findByDocumentRegistryId(UUID documentRegistryId);
}
