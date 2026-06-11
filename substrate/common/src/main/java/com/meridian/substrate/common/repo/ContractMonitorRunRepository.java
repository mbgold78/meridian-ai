package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractMonitorRun;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository surface for the APPEND-ONLY
 * {@link ContractMonitorRun} log (engine-spec contracts.md §3.2 table 9,
 * §10.9).
 *
 * <p>Deliberately extends {@link Repository} — NOT {@code JpaRepository} — so
 * no update/delete methods exist anywhere in the type system (precedent:
 * {@code CascadeLevelActionRepository}; ArchUnit rule lands with chunk 3c).
 */
@org.springframework.stereotype.Repository
public interface ContractMonitorRunRepository extends Repository<ContractMonitorRun, UUID> {

    /** Insert-only in practice (append-only log) — never call with a loaded, mutated row. */
    ContractMonitorRun save(ContractMonitorRun run);

    Optional<ContractMonitorRun> findById(UUID id);

    List<ContractMonitorRun> findByMonitorIdOrderByStartedAtAsc(UUID monitorId);

    /** §7.1 {@code GET /contracts/{id}/monitor-runs?monitorId&since} filter lane. */
    List<ContractMonitorRun> findByMonitorIdAndStartedAtAfterOrderByStartedAtAsc(UUID monitorId, Instant since);
}
