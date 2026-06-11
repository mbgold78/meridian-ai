package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * §5.3 MCP_PROBE — liveness ONLY (ADR D11): probes the party's registered MCP
 * endpoint ({@code ReaderRegistration kind=MCP_CLIENT}, id in
 * {@code rule_spec.readerRegistrationId}); reachable → COMPLIANT;
 * absent/erroring → INDETERMINATE + {@code ADHERENCE_UNOBSERVABLE} signal
 * (materialized at read time off {@code last_outcome}, §11.2). <b>Never a
 * breach. Never fetches metrics.</b>
 */
@Component
public class McpProbeEvaluator implements MonitorEvaluator {

    private final ReaderRegistrationRepository readerRegistrationRepository;
    private final McpLivenessProbe probe;
    private final ObjectMapper objectMapper;

    public McpProbeEvaluator(ReaderRegistrationRepository readerRegistrationRepository,
                             McpLivenessProbe probe,
                             ObjectMapper objectMapper) {
        this.readerRegistrationRepository = readerRegistrationRepository;
        this.probe = probe;
        this.objectMapper = objectMapper;
    }

    @Override
    public MonitorRuleType ruleType() {
        return MonitorRuleType.MCP_PROBE;
    }

    @Override
    public MonitorEvaluation evaluate(ContractMonitor monitor, PeriodContext ctx) {
        ObjectNode trace = objectMapper.createObjectNode();
        trace.put("rule", MonitorRuleType.MCP_PROBE.name());
        // Liveness has no closed-period arithmetic — the evaluated period is
        // the one containing the probe instant (run-row bookkeeping only).
        String period = PeriodKeys.periodKey(monitor.getCadence(), ctx.now(), ctx.zone());
        trace.put("periodKey", period);

        UUID registrationId = readerRegistrationId(monitor.getRuleSpec());
        if (registrationId == null) {
            trace.put("note", "rule_spec.readerRegistrationId absent/malformed —"
                    + " party endpoint unobservable (ADHERENCE_UNOBSERVABLE)");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
        }
        trace.put("readerRegistrationId", registrationId.toString());

        Optional<ReaderRegistration> registration =
                readerRegistrationRepository.findById(registrationId);
        if (registration.isEmpty() || registration.get().getKind() != ReaderKind.MCP_CLIENT
                || registration.get().getUri() == null || registration.get().getUri().isBlank()) {
            trace.put("note", "no MCP_CLIENT registration with a URI behind that id —"
                    + " unobservable (ADHERENCE_UNOBSERVABLE), never a breach (ADR D11)");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
        }

        String uri = registration.get().getUri();
        boolean reachable = probe.isReachable(uri);
        trace.put("uri", uri);
        trace.put("reachable", reachable);
        if (reachable) {
            return MonitorEvaluation.of(MonitorRunOutcome.COMPLIANT, period, trace);
        }
        trace.put("note", "endpoint unreachable — ADHERENCE_UNOBSERVABLE; never a breach (ADR D11)");
        return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
    }

    private static UUID readerRegistrationId(JsonNode spec) {
        if (spec == null || !spec.hasNonNull("readerRegistrationId")) {
            return null;
        }
        try {
            return UUID.fromString(spec.get("readerRegistrationId").asText());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
