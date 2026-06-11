package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.TransformationInitiative;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TransformationInitiative}
 * (engine-spec diagnostic.md §3.3 — D5 thin slice, V8). {@code planId} is a
 * soft ref (no DB FK) — per-domain initiative resolution goes plan-list →
 * {@link #findByPlanIdInOrderByCreatedAtAsc} in the read service, app-enforced.
 */
@Repository
public interface TransformationInitiativeRepository extends JpaRepository<TransformationInitiative, UUID> {

    List<TransformationInitiative> findByPlanIdOrderByCreatedAtAsc(UUID planId);

    List<TransformationInitiative> findByPlanIdInOrderByCreatedAtAsc(Collection<UUID> planIds);
}
