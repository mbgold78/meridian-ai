package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /agents} row (engine-spec agents.md §7.3) — 1:1 with the fixture
 * {@code BeaconAgent} so OperationsTab swaps with a THIN adapter, plus the
 * additive substrate fields. <b>Render rule, binding:</b>
 * {@code deploymentCriteria} is CLICK-THROUGH ONLY ([AGENT-PROVENANCE]) — it
 * deliberately does NOT exist on this row shape; only {@link AgentDetail}
 * carries it.
 *
 * <p>{@code status} is the §5.2 pinned-precedence projection in exact fixture
 * vocabulary; {@code retired} rows are excluded from the fleet list by
 * default (the swapped pill map crashes on unknown values — ADR-003 D4).
 * {@code recentActivity} is DERIVED (latest sighting detail, else latest
 * lifecycle event, else "No activity observed") — never blank, never
 * invented. {@code adherence} is NULLABLE — latest {@code adherence} metric
 * sample; null until data exists (em-dash in the UI, never fabricated).
 */
public record AgentFleetRow(
        String id,                              // = agent_key (fixture parity); registrationId additive
        UUID registrationId,
        UUID domainId,
        String name,
        String role,                            // fixture field name; column role_description
        AgentWireStatus status,
        AgentOperationalMode operationalMode,   // machine state — the OTHER dimension, additive
        String recentActivity,
        Double adherence,
        UUID capabilityId,
        String deployedBy,
        Instant deployedAt,
        List<String> oversightChain,            // ordered labels, position asc
        AgentExecutionLocus executionLocus,
        AgentSupervisoryModel supervisoryModel,
        int openInterventions,
        int eventSeq
) {

    public static AgentFleetRow from(AgentRegistration reg,
                                     List<AgentOversightLink> oversightLinks,
                                     String recentActivity,
                                     Double adherence) {
        return new AgentFleetRow(
                reg.getAgentKey(),
                reg.getId(),
                reg.getDomainId(),
                reg.getName(),
                reg.getRoleDescription(),
                AgentWireStatus.project(reg.getOperationalMode(), reg.getOpenInterventionCount()),
                reg.getOperationalMode(),
                recentActivity,
                adherence,
                reg.getCapabilityId(),
                reg.getDeployedByLabel(),
                reg.getDeployedAt(),
                oversightLinks.stream().map(AgentOversightLink::getLabel).toList(),
                reg.getExecutionLocus(),
                reg.getSupervisoryModel(),
                reg.getOpenInterventionCount(),
                reg.getEventSeq());
    }
}
