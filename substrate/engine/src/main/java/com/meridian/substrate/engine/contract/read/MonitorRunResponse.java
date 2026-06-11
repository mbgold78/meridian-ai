package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /contracts/{id}/monitor-runs} row (engine-spec contracts.md
 * §7.1): {@code {id, monitorId, startedAt, finishedAt, outcome, periodKey,
 * details}}. Runs are appended by the 3b scheduler; 3a serves the (empty
 * until then) read lane.
 */
public record MonitorRunResponse(
        UUID id,
        UUID monitorId,
        Instant startedAt,
        Instant finishedAt,
        MonitorRunOutcome outcome,
        String periodKey,
        JsonNode details
) {

    public static MonitorRunResponse from(ContractMonitorRun r) {
        return new MonitorRunResponse(r.getId(), r.getMonitorId(), r.getStartedAt(),
                r.getFinishedAt(), r.getOutcome(), r.getPeriodKey(), r.getDetails());
    }
}
