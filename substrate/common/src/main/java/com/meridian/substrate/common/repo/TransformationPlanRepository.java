package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.TransformationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TransformationPlan} (engine-spec
 * diagnostic.md §3.3 — D5 thin slice, V8).
 */
@Repository
public interface TransformationPlanRepository extends JpaRepository<TransformationPlan, UUID> {

    List<TransformationPlan> findByDomainIdOrderByCreatedAtAsc(UUID domainId);
}
