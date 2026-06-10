package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Capability;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CapabilityRepository extends JpaRepository<Capability, UUID> {

    List<Capability> findByDomainId(UUID domainId);

    List<Capability> findByDomainIdAndParentCapabilityIdIsNull(UUID domainId);

    /**
     * Read-only lookup of a sibling group — used for auto-assigning a
     * {@code sequenceOrder} to a newly-created capability. Returns children
     * of a specific parent (use {@link #findRootSiblings} for the
     * no-parent case).
     */
    @Query("""
            SELECT c FROM Capability c
            WHERE c.domainId = :domainId
              AND c.parentCapabilityId = :parentId
            ORDER BY c.sequenceOrder ASC
            """)
    List<Capability> findSiblingsUnderParent(
            @Param("domainId") UUID domainId,
            @Param("parentId") UUID parentId);

    @Query("""
            SELECT c FROM Capability c
            WHERE c.domainId = :domainId
              AND c.parentCapabilityId IS NULL
            ORDER BY c.sequenceOrder ASC
            """)
    List<Capability> findRootSiblings(@Param("domainId") UUID domainId);

    /**
     * Pessimistic-lock variant of {@link #findSiblingsUnderParent} for the
     * reorder cascade. Callers MUST invoke this from inside a
     * {@code @Transactional} context so the row locks are held until commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM Capability c
            WHERE c.domainId = :domainId
              AND c.parentCapabilityId = :parentId
            ORDER BY c.sequenceOrder ASC
            """)
    List<Capability> findSiblingsUnderParentForUpdate(
            @Param("domainId") UUID domainId,
            @Param("parentId") UUID parentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM Capability c
            WHERE c.domainId = :domainId
              AND c.parentCapabilityId IS NULL
            ORDER BY c.sequenceOrder ASC
            """)
    List<Capability> findRootSiblingsForUpdate(@Param("domainId") UUID domainId);
}
