package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Domain} entity.
 */
@Repository
public interface DomainRepository extends JpaRepository<Domain, UUID> {

    /** Name lookup for the ingestion target-domain resolution (port-map §2.4 C6). */
    Optional<Domain> findFirstByName(String name);
}
