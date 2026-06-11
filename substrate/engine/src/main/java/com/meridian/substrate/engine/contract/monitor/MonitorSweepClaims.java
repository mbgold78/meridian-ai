package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-monitor claim queries for the sweep (engine-spec contracts.md §5.2
 * step 1 / §10.3): on Postgres a native {@code FOR UPDATE SKIP LOCKED} so a
 * concurrent claimer (second node, or {@code run-now} racing the sweep) skips
 * instead of blocking; on H2 dev the plain {@code ForUpdate} JPQL fallback
 * (single-node per-engagement deploy assumption, documented — H2-green is
 * never acceptance for the SKIP LOCKED semantics; the §14.4 PG smoke is the
 * gate).
 *
 * <p>Callers MUST be inside an active transaction; the returned managed
 * entity stays locked until that transaction ends.
 */
@Component
public class MonitorSweepClaims {

    private static final Logger log = LoggerFactory.getLogger(MonitorSweepClaims.class);

    private static final String PG_CLAIM_DUE_SQL = """
            SELECT * FROM contract_monitors
            WHERE id = :id AND status = 'ACTIVE' AND next_run_at <= :now
            FOR UPDATE SKIP LOCKED
            """;

    private final EntityManager entityManager;
    private final ContractMonitorRepository monitorRepository;

    /** Lazily resolved DB vendor ("PostgreSQL" picks the SKIP LOCKED lane). */
    private volatile Boolean postgres;

    public MonitorSweepClaims(EntityManager entityManager,
                              ContractMonitorRepository monitorRepository) {
        this.entityManager = entityManager;
        this.monitorRepository = monitorRepository;
    }

    /**
     * Claims monitor {@code id} iff still due ({@code status=ACTIVE AND
     * next_run_at <= now}) — empty when another claimer holds/served it.
     */
    public Optional<ContractMonitor> claimDue(UUID id, Instant now) {
        if (isPostgres()) {
            @SuppressWarnings("unchecked")
            List<ContractMonitor> rows = entityManager
                    .createNativeQuery(PG_CLAIM_DUE_SQL, ContractMonitor.class)
                    .setParameter("id", id)
                    .setParameter("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .getResultList();
            return rows.stream().findFirst();
        }
        return monitorRepository.findByIdForUpdate(id)
                .filter(m -> m.getStatus() == MonitorStatus.ACTIVE)
                .filter(m -> m.getNextRunAt() != null && !m.getNextRunAt().isAfter(now));
    }

    /**
     * {@code run-now} claim (§5.2): bypasses {@code next_run_at} but HONORS
     * locks — a plain blocking {@code FOR UPDATE} on both vendors, so a
     * run-now issued mid-sweep waits for the sweep's claim to finish instead
     * of double-running.
     */
    public Optional<ContractMonitor> claimForRunNow(UUID id) {
        return monitorRepository.findByIdForUpdate(id);
    }

    public boolean isPostgres() {
        Boolean resolved = postgres;
        if (resolved == null) {
            String product = entityManager.unwrap(Session.class).doReturningWork(
                    connection -> connection.getMetaData().getDatabaseProductName());
            resolved = "PostgreSQL".equalsIgnoreCase(product);
            postgres = resolved;
            log.info("Monitor sweep claim lane: {} ({})",
                    resolved ? "native FOR UPDATE SKIP LOCKED" : "JPQL ForUpdate fallback", product);
        }
        return resolved;
    }
}
