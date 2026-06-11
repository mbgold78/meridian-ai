package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.enums.CascadeState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link Cascade} aggregate root (engine-spec cascade.md §4).
 */
@Repository
public interface CascadeRepository extends JpaRepository<Cascade, UUID> {

    List<Cascade> findByDomainId(UUID domainId);

    List<Cascade> findByDomainIdAndCurrentState(UUID domainId, CascadeState state);

    List<Cascade> findByCascadeGroupIdAndCurrentState(UUID groupId, CascadeState state);

    /**
     * Cross-domain in-flight sweep set for {@code CascadeEscalationSweeper}
     * (engine-spec cascade.md §9) — additive read-only finder; the sweeper
     * re-reads each candidate under {@link #findByIdForUpdate} before writing.
     */
    List<Cascade> findByCurrentState(CascadeState state);

    /**
     * The per-cascade serialization point (ADR D8): {@code PESSIMISTIC_WRITE}
     * maps to {@code SELECT … FOR UPDATE} on Postgres and is honored by H2
     * PG-mode. Every mutation in {@code CascadeLifecycleService} takes this
     * lock first, then performs the idempotency check, fencing, guards, action
     * append, projection updates and outbox writes in ONE transaction
     * (invariant I9). Callers MUST be {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cascade c where c.id = :id")
    Optional<Cascade> findByIdForUpdate(@Param("id") UUID id);
}
