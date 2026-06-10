package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Domain} entity.
 */
@Repository
public interface DomainRepository extends JpaRepository<Domain, UUID> {
}
