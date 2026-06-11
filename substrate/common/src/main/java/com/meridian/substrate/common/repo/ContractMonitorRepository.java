package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract monitors (engine-spec contracts.md §3.4). The sweep claims due
 * monitors ({@code status=ACTIVE AND next_run_at <= now}); on Postgres the
 * scheduler (chunk 3b) adds {@code FOR UPDATE SKIP LOCKED} via a native query
 * (§10.3) — H2 dev falls back to the plain {@code ForUpdate} JPQL below
 * (single-node per-engagement deploy assumption, documented).
 */
@Repository
public interface ContractMonitorRepository extends JpaRepository<ContractMonitor, UUID> {

    List<ContractMonitor> findByContractId(UUID contractId);

    List<ContractMonitor> findByStatusAndNextRunAtBefore(MonitorStatus status, Instant cutoff);

    /**
     * Pessimistic-lock claim variant (spec §5.2 step 1, H2 fallback lane).
     * Callers MUST be inside {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT m FROM ContractMonitor m
            WHERE m.status = :status
              AND m.nextRunAt < :cutoff
            """)
    List<ContractMonitor> findByStatusAndNextRunAtBeforeForUpdate(
            @Param("status") MonitorStatus status,
            @Param("cutoff") Instant cutoff);

    /**
     * Per-monitor pessimistic claim (spec §5.2 step 1 / §10.3, H2 fallback
     * lane; also the {@code run-now} "honoring locks" path). On Postgres the
     * sweep prefers the native {@code FOR UPDATE SKIP LOCKED} claim in the
     * engine's {@code MonitorSweepClaims}. Callers MUST be inside
     * {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ContractMonitor m WHERE m.id = :id")
    Optional<ContractMonitor> findByIdForUpdate(@Param("id") UUID id);
}
