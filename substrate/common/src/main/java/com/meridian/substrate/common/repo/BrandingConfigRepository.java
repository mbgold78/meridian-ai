package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandingConfigRepository extends JpaRepository<BrandingConfig, UUID> {

    List<BrandingConfig> findByDomainId(UUID domainId);

    /**
     * Read-only lookup of the single ACTIVE config for a domain — used by the
     * boot-time {@code GET /configs/active} resolver. Service-side enforcement
     * (see {@code BrandingConfigService.demoteCurrentActive}) keeps at most
     * one ACTIVE row per domain; concurrent activations use the locked variant
     * below.
     */
    Optional<BrandingConfig> findFirstByDomainIdAndStatus(UUID domainId, BrandingConfigStatus status);

    /**
     * Same shape as {@link #findFirstByDomainIdAndStatus} but takes a
     * {@code PESSIMISTIC_WRITE} row lock on the matching row (if any).
     * Callers MUST invoke this from inside a {@code @Transactional} context
     * so the lock is held until commit.
     *
     * <p><b>Why the locked variant exists:</b> the demote-then-promote dance
     * in {@code BrandingConfigService.demoteCurrentActive(...)} reads a row,
     * then updates it, then updates another row. Without a lock, two
     * concurrent activations on the same domain can each read "there is an
     * ACTIVE config Z" (or both "there is no ACTIVE"), both update, and both
     * commit — producing two ACTIVE rows and violating the single-ACTIVE
     * invariant. Locking the existing ACTIVE row serializes the common case
     * (existing ACTIVE being replaced); see homepoc's
     * {@code docs/phase2b1-observations.md} for the residual edge case where
     * no prior ACTIVE exists (on Postgres the partial unique index
     * {@code uq_branding_configs_active_per_domain} backstops it).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BrandingConfig b WHERE b.domainId = :domainId AND b.status = :status")
    Optional<BrandingConfig> findFirstByDomainIdAndStatusForUpdate(
            @Param("domainId") UUID domainId,
            @Param("status") BrandingConfigStatus status);
}
