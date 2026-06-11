package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
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
 * Repository for {@link CascadeDraft} authoring rows (engine-spec cascade.md §4).
 */
@Repository
public interface CascadeDraftRepository extends JpaRepository<CascadeDraft, UUID> {

    List<CascadeDraft> findByDomainIdAndStatus(UUID domainId, CascadeDraftStatus status);

    /** Status-agnostic list half of {@code GET /cascade-drafts?domainId&status} (spec §6.3). */
    List<CascadeDraft> findByDomainId(UUID domainId);

    /**
     * Issuance serialization point (ADR D8): no cascade row exists yet at
     * {@code /issue} time, so the draft row carries the {@code PESSIMISTIC_WRITE}
     * lock for the OPEN→ISSUED flip; re-issuing an ISSUED draft returns the
     * existing cascade (idempotent). Callers MUST be {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CascadeDraft d where d.id = :id")
    Optional<CascadeDraft> findByIdForUpdate(@Param("id") UUID id);
}
