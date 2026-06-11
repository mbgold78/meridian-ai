package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract breaches (engine-spec contracts.md §3.4). Rows are never deleted;
 * status moves monotonically forward (§2.4).
 */
@Repository
public interface ContractBreachRepository extends JpaRepository<ContractBreach, UUID> {

    List<ContractBreach> findByContractIdAndStatusIn(UUID contractId, Collection<BreachStatus> statuses);

    /**
     * All breaches in one lifecycle status — the sweep's surfacing step
     * (spec §5.2 step 4 / §5.4: DETECTED → SURFACED, same sweep, separate
     * transaction; idempotent because SURFACED rows leave the result set).
     */
    List<ContractBreach> findByStatus(BreachStatus status);

    /**
     * Id-only candidate projection for the surfacing pass: loading ENTITIES
     * here would put them in the persistence context, and the subsequent
     * {@link #findByIdForUpdate} lock would return the CACHED (possibly
     * stale) state instead of the freshly locked row — the id list keeps the
     * lock-then-recheck honest.
     */
    @Query("SELECT b.id FROM ContractBreach b WHERE b.status = :status")
    List<UUID> findIdsByStatus(@Param("status") BreachStatus status);

    /**
     * Open-breach dedup lookup under row lock (ADR D13; §5.4/§10.6): at most
     * one open breach per {@code (monitor_id, commitment_id, period_key)}.
     * NULL source components match NULL (the V5_1 partial index COALESCEs the
     * same way). Open = DETECTED | SURFACED | ACKNOWLEDGED — keep in lockstep
     * with {@link BreachStatus#isOpen()} and the V5_1 predicate. Callers MUST
     * be inside {@code @Transactional}; on the PG constraint violation race,
     * retry as refresh (§10.6).
     */
    /**
     * Single-row PESSIMISTIC_WRITE load — the breach mutation serialization
     * point shared by the §2.4 human verbs and the §5.4 surfacing pass.
     * Without it the verbs mutated an UNLOCKED {@code findById} read and
     * raced the sweep's locked reconfirm writes (and the surfacing pass)
     * into request-failing optimistic 409s — every other write path in the
     * system serializes pessimistically (§10).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM ContractBreach b WHERE b.id = :id")
    Optional<ContractBreach> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM ContractBreach b
            WHERE b.contractId = :contractId
              AND ((:monitorId IS NULL AND b.monitorId IS NULL) OR b.monitorId = :monitorId)
              AND ((:commitmentId IS NULL AND b.commitmentId IS NULL) OR b.commitmentId = :commitmentId)
              AND ((:periodKey IS NULL AND b.periodKey IS NULL) OR b.periodKey = :periodKey)
              AND b.status IN (com.meridian.substrate.common.domain.enums.BreachStatus.DETECTED,
                               com.meridian.substrate.common.domain.enums.BreachStatus.SURFACED,
                               com.meridian.substrate.common.domain.enums.BreachStatus.ACKNOWLEDGED)
            """)
    Optional<ContractBreach> findOpenBySourceForUpdate(
            @Param("contractId") UUID contractId,
            @Param("monitorId") UUID monitorId,
            @Param("commitmentId") UUID commitmentId,
            @Param("periodKey") String periodKey);
}
