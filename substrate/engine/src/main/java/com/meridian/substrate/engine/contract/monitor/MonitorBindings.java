package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolution of the {@code rule_spec} ↔ commitment bindings (engine-spec
 * contracts.md §3.2 table 8 rule_spec shape; §5.3 "for each commitment in
 * {@code rule_spec.commitmentIds} (default: all cadence-bearing commitments
 * of the contract)").
 *
 * <p>The reverse lookup ({@link #cadenceMonitorFor}) keeps the ADR D13 dedup
 * key coherent across detection sources: a MISSED attestation auto-opens its
 * breach attributed to the commitment's bound ATTESTATION_CADENCE monitor, so
 * the sweep's later re-detection of the same (monitor, commitment, period)
 * REFRESHES that row ({@code BREACH_RECONFIRMED}) instead of opening a
 * duplicate — exactly the §5.3 sentence "detected_by=HUMAN_ATTESTATION if the
 * breach originated from the attestation write itself; MONITOR if found by
 * sweep" (one breach, two possible first-detectors).
 */
public final class MonitorBindings {

    private MonitorBindings() {
    }

    /** {@code rule_spec.commitmentIds} as UUIDs; empty when absent/blank. */
    public static List<UUID> boundCommitmentIds(ContractMonitor monitor) {
        JsonNode spec = monitor.getRuleSpec();
        if (spec == null || !spec.has("commitmentIds") || !spec.get("commitmentIds").isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode id : spec.get("commitmentIds")) {
            try {
                ids.add(UUID.fromString(id.asText()));
            } catch (IllegalArgumentException malformed) {
                // surfaced by the evaluator's trace — never silently dropped
            }
        }
        return ids;
    }

    /**
     * The ATTESTATION_CADENCE monitor governing {@code commitment} among the
     * contract's monitors: explicit {@code rule_spec.commitmentIds} binding
     * first, then the §5.3 default (no commitmentIds ⇒ all cadence-bearing
     * commitments). Several candidates resolve to the lowest-id one for a
     * deterministic ADR D13 dedup key.
     */
    public static Optional<ContractMonitor> cadenceMonitorFor(List<ContractMonitor> contractMonitors,
                                                              ContractCommitment commitment) {
        List<ContractMonitor> cadenceMonitors = contractMonitors.stream()
                .filter(m -> m.getRuleType() == MonitorRuleType.ATTESTATION_CADENCE)
                .sorted(Comparator.comparing(ContractMonitor::getId))
                .toList();
        Optional<ContractMonitor> explicit = cadenceMonitors.stream()
                .filter(m -> boundCommitmentIds(m).contains(commitment.getId()))
                .findFirst();
        if (explicit.isPresent()) {
            return explicit;
        }
        if (commitment.getCadence() == null || commitment.getCadence().isBlank()) {
            return Optional.empty();
        }
        return cadenceMonitors.stream()
                .filter(m -> boundCommitmentIds(m).isEmpty())
                .findFirst();
    }
}
