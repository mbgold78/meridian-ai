package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.DocumentRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link DocumentRegistry} entity.
 */
@Repository
public interface DocumentRegistryRepository extends JpaRepository<DocumentRegistry, UUID> {

    Optional<DocumentRegistry> findBySourceFileSha256(String sha256);
}
