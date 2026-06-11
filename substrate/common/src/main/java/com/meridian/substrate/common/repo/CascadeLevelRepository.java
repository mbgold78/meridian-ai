package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CascadeLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link CascadeLevel} projection rows (engine-spec cascade.md §4).
 * Levels are mutable projections of the append-only action log (ADR D1) and are
 * only ever written under the per-cascade {@code PESSIMISTIC_WRITE} lock held
 * via {@link CascadeRepository#findByIdForUpdate}.
 */
@Repository
public interface CascadeLevelRepository extends JpaRepository<CascadeLevel, UUID> {

    List<CascadeLevel> findByCascadeIdOrderByDepthAsc(UUID cascadeId);

    Optional<CascadeLevel> findByCascadeIdAndDepth(UUID cascadeId, int depth);
}
